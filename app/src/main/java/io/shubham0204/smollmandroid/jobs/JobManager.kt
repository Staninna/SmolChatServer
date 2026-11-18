/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.jobs

import android.util.Log
import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ApiException
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.data.JobDao
import io.shubham0204.smollmandroid.data.RequestLogDao
import io.shubham0204.smollmandroid.engine.GenerationBackend
import io.shubham0204.smollmandroid.engine.GenerationProgress
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs every generation as a job that does not depend on the connection that asked for it.
 *
 * The inference engine runs one request at a time in FIFO order; this class is the only place that
 * admits work to it (at most one running plus [MAX_WAITING] waiting) and adds what makes long
 * generations survive on a phone:
 * - a job outlives a dropped or frozen client ([JobMode.Background]);
 * - a client can name its request (`request_id`) and finds exactly that job again, however often it
 *   reconnects or repeats the request;
 * - background jobs are on disk ([JobDao], see JobRecords.kt) with a checkpoint of the answer so
 *   far, so a job that was running when the server was stopped or killed continues where it was at
 *   the next start;
 * - the CPU stays awake from queue to result, every phase is observable, and each job writes one
 *   request-log row when it ends.
 */
class JobManager(
    private val engine: GenerationBackend,
    private val requestLog: RequestLogDao,
    private val store: JobDao,
    private val wakeLock: WakeHold,
    private val logBodies: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val jobs = LinkedHashMap<String, ServerJob>()
    private val random = SecureRandom()
    private val restoreGate = Mutex()
    private var restored = false

    private val _active = MutableStateFlow<List<JobSnapshot>>(emptyList())

    /** Queued and running jobs, oldest first. */
    val active: StateFlow<List<JobSnapshot>> = _active.asStateFlow()

    private val _wakeLockHeld = MutableStateFlow(false)
    val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

    /**
     * Queues a generation and returns at once. A background request that carries a `request_id`
     * this client used before returns that job (running or finished) instead of a new one.
     *
     * @throws ApiException [ApiError.ServerBusy] when the queue is full, [ApiError.InternalError]
     *   when a background job cannot be stored (it would not survive a restart).
     */
    suspend fun submit(
        clientPackage: String?,
        clientLabel: String,
        request: ResolvedRequest,
        mode: JobMode,
        requestBody: String? = null,
    ): ServerJob {
        val requestId = request.requestId.takeIf { mode == JobMode.Background }
        if (requestId != null) restore() // the job may be on disk from before a restart
        val job: ServerJob
        synchronized(lock) {
            purgeLocked()
            if (requestId != null) {
                jobs.values
                    .firstOrNull { it.state.value.clientPackage == clientPackage && it.state.value.requestId == requestId }
                    ?.let { return it }
            }
            if (jobs.values.count { !it.state.value.phase.terminal } > MAX_WAITING) {
                throw ApiException(ApiError.ServerBusy, "The local AI is busy. Try again in a few seconds.")
            }
            job =
                ServerJob(
                    JobSnapshot(
                        id = newId(),
                        clientPackage = clientPackage,
                        clientLabel = clientLabel,
                        mode = mode,
                        createdAt = System.currentTimeMillis(),
                        maxTokens = request.maxTokens,
                        maxTokensFromClient = request.maxTokens != null,
                        requestId = requestId,
                    ),
                    request,
                    if (mode == JobMode.Stream || mode == JobMode.Playground) Channel(Channel.UNLIMITED) else null,
                    requestBody,
                )
            jobs[job.id] = job
            holdWakeLockLocked()
            publishLocked()
        }
        // on disk before the client hears about it: from here on the job cannot get lost
        if (mode == JobMode.Background) {
            try {
                store.upsert(job.toEntity(null))
            } catch (e: Exception) {
                Log.e(TAG, "could not store job ${job.id}", e)
                synchronized(lock) {
                    jobs.remove(job.id)
                    releaseWakeLockIfIdleLocked()
                    publishLocked()
                }
                throw ApiException(ApiError.InternalError, "The server could not store the job. Try again.")
            }
        }
        job.coroutine = scope.launch { run(job) }
        return job
    }

    /**
     * Loads the jobs that were on disk when the process started: finished ones can be fetched again,
     * unfinished ones go back into the queue and continue from their checkpoint. Runs once.
     */
    suspend fun restore() {
        restoreGate.withLock {
            if (restored) return
            val rows =
                try {
                    store.deleteFinishedBefore(System.currentTimeMillis() - RETAIN_MS)
                    store.getAll()
                } catch (e: Exception) {
                    Log.w(TAG, "could not read stored jobs, trying again on the next request", e)
                    return
                }
            for (row in rows) {
                val job = row.toServerJob()
                if (job == null) {
                    Log.w(TAG, "dropping unreadable job ${row.id}")
                    runCatching { store.delete(row.id) }
                    continue
                }
                val unfinished = !job.state.value.phase.terminal
                synchronized(lock) {
                    if (jobs.containsKey(job.id)) return@synchronized
                    jobs[job.id] = job
                    if (unfinished) {
                        holdWakeLockLocked()
                        job.coroutine = scope.launch { run(job) }
                    }
                    publishLocked()
                }
                if (unfinished) Log.i(TAG, "resuming job ${job.id} at ${row.checkpointTokens} tokens")
            }
            restored = true
        }
    }

    /** The job with this id (the server's, or the client's own `request_id`), if it belongs to [clientPackage]. */
    suspend fun find(id: String, clientPackage: String?): ServerJob? {
        restore()
        return synchronized(lock) {
            purgeLocked()
            jobs[id]?.takeIf { it.state.value.clientPackage == clientPackage }
                ?: jobs.values.firstOrNull { it.state.value.clientPackage == clientPackage && it.state.value.requestId == id }
        }
    }

    /** This client's jobs that are known right now, oldest first. */
    suspend fun list(clientPackage: String?): List<ServerJob> {
        restore()
        return synchronized(lock) {
            purgeLocked()
            jobs.values.filter { it.state.value.clientPackage == clientPackage && it.state.value.mode == JobMode.Background }
        }
    }

    /** Position in the waiting line (1 = next), or null when the job is not waiting. */
    fun queuePosition(id: String): Int? =
        synchronized(lock) {
            val waiting = jobs.values.filter { it.state.value.phase == JobPhase.Queued }
            waiting.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
        }

    fun cancel(id: String) {
        synchronized(lock) { jobs[id] }?.let(::cancel)
    }

    fun cancel(job: ServerJob) {
        job.coroutine?.cancel()
    }

    /** Drops a finished job and its stored result (the client has it, or does not want it). */
    suspend fun forget(job: ServerJob) {
        if (!job.state.value.phase.terminal) return
        synchronized(lock) { jobs.remove(job.id) }
        runCatching { store.delete(job.id) }.onFailure { Log.w(TAG, "could not delete job ${job.id}", it) }
    }

    /**
     * The server stops. Background jobs are parked: their checkpoint is on disk and they continue
     * at the next start. Everything else is cancelled. Returns when the engine is free.
     */
    suspend fun stopAll() {
        val running = synchronized(lock) { jobs.values.filter { !it.state.value.phase.terminal } }
        running.forEach {
            if (it.state.value.mode == JobMode.Background) it.parked = true
            it.coroutine?.cancel()
        }
        running.forEach { it.coroutine?.join() }
        // parked jobs are read back from disk at the next start
        restoreGate.withLock { restored = false }
    }

    private suspend fun run(job: ServerJob) {
        val outcome =
            try {
                engine.withSlot { session ->
                    job.update { it.copy(startedAt = System.currentTimeMillis()) }
                    var loadStart = 0L
                    val model =
                        session.ensureLoaded(
                            onLoading = {
                                loadStart = System.currentTimeMillis()
                                job.update { it.copy(phase = JobPhase.LoadingModel) }
                            }
                        )
                    val loadMs = if (loadStart > 0) System.currentTimeMillis() - loadStart else null
                    val prepared = session.prepare(job.request, job.resumeFrom)
                    job.update {
                        it.copy(
                            phase = JobPhase.ReadingPrompt,
                            model = model.displayName,
                            loadMs = loadMs,
                            promptTokens = prepared.promptTokens,
                            promptProcessed = prepared.cachedTokens,
                            cachedTokens = prepared.cachedTokens,
                            maxTokens = prepared.maxTokens,
                            promptStartedAt = System.currentTimeMillis(),
                        )
                    }
                    job.events?.send(JobEvent.Started(model.displayName))
                    val result =
                        session.generate(prepared, progressOf(job)) { content, reasoning ->
                            if (content.isNotEmpty()) {
                                val preview =
                                    synchronized(job.text) {
                                        job.text.append(content)
                                        job.text.takeLast(PREVIEW_CHARS).toString()
                                    }
                                job.update { it.copy(preview = preview) }
                            }
                            job.events?.send(JobEvent.Delta(content, reasoning))
                            checkpointIfDue(job)
                        }
                    JobOutcome.Success(result, model.displayName)
                }
            } catch (e: ApiException) {
                JobOutcome.Failure(e.error, e.message)
            } catch (e: CancellationException) {
                JobOutcome.Cancelled
            } catch (e: Exception) {
                Log.e(TAG, "job ${job.id} failed", e)
                JobOutcome.Failure(ApiError.InternalError, e.message ?: "Internal error")
            }
        withContext(NonCancellable) {
            if (outcome == JobOutcome.Cancelled && job.parked) park(job) else finish(job, outcome)
        }
    }

    private fun progressOf(job: ServerJob) =
        object : GenerationProgress {
            override fun onPrompt(processed: Int, total: Int, cached: Int) {
                job.update { it.copy(promptProcessed = processed) }
            }

            override fun onRaw(piece: String) {
                job.raw.append(piece)
                job.rawTokens++
            }

            override fun onTokens(count: Int, tokensPerSecond: Double?) {
                job.update {
                    it.copy(
                        phase = JobPhase.Generating,
                        promptProcessed = it.promptTokens,
                        completionTokens = count,
                        tokensPerSecond = tokensPerSecond ?: it.tokensPerSecond,
                        generationStartedAt = it.generationStartedAt ?: System.currentTimeMillis(),
                    )
                }
            }
        }

    /** Saves the answer so far every [CHECKPOINT_TOKENS] tokens or [CHECKPOINT_MS], whichever comes first. */
    private suspend fun checkpointIfDue(job: ServerJob) {
        if (job.state.value.mode != JobMode.Background) return
        val now = System.currentTimeMillis()
        if (job.rawTokens - job.checkpointTokens < CHECKPOINT_TOKENS && now - job.checkpointAt < CHECKPOINT_MS) return
        if (job.rawTokens == job.checkpointTokens) return
        writeCheckpoint(job)
    }

    private suspend fun writeCheckpoint(job: ServerJob) {
        job.checkpointTokens = job.rawTokens
        job.checkpointAt = System.currentTimeMillis()
        runCatching { store.checkpoint(job.id, job.raw.toString(), job.rawTokens, job.state.value.phase.name) }
            .onFailure { Log.w(TAG, "could not write a checkpoint", it) }
    }

    /** Takes a job out of memory without ending it; [restore] brings it back. */
    private suspend fun park(job: ServerJob) {
        if (job.rawTokens > 0) writeCheckpoint(job)
        synchronized(lock) {
            jobs.remove(job.id)
            releaseWakeLockIfIdleLocked()
            publishLocked()
        }
    }

    private suspend fun finish(job: ServerJob, outcome: JobOutcome) {
        val now = System.currentTimeMillis()
        job.update {
            when (outcome) {
                is JobOutcome.Success ->
                    it.copy(
                        phase = JobPhase.Completed,
                        finishedAt = now,
                        completionTokens = outcome.result.completionTokens,
                        promptProcessed = it.promptTokens,
                        tokensPerSecond = outcome.result.tokensPerSecond.takeIf { v -> v > 0 } ?: it.tokensPerSecond,
                        finishReason = outcome.result.finishReason,
                    )
                is JobOutcome.Failure ->
                    it.copy(
                        phase = JobPhase.Failed,
                        finishedAt = now,
                        errorCode = outcome.error.code,
                        errorMessage = outcome.message,
                    )
                JobOutcome.Cancelled -> it.copy(phase = JobPhase.Cancelled, finishedAt = now, errorCode = CLIENT_CLOSED)
            }
        }
        // the result is on disk before anybody is told that the job is done
        if (job.state.value.mode == JobMode.Background) {
            runCatching { store.upsert(job.toEntity(outcome)) }.onFailure { Log.w(TAG, "could not store the result of ${job.id}", it) }
        }
        job.outcomeDeferred.complete(outcome)
        job.events?.let {
            it.trySend(JobEvent.Finished(outcome))
            it.close()
        }
        synchronized(lock) {
            // only background results are fetched later; everything else was delivered just now
            if (job.state.value.mode != JobMode.Background) jobs.remove(job.id)
            releaseWakeLockIfIdleLocked()
            publishLocked()
        }
        runCatching {
            requestLog.insert(job.toLogEntry(outcome, logBodies(), System.currentTimeMillis()))
            requestLog.trim()
        }
            .onFailure { Log.w(TAG, "could not write the request log", it) }
    }

    private fun ServerJob.update(transform: (JobSnapshot) -> JobSnapshot) {
        mutableState.update(transform)
        synchronized(lock) { publishLocked() }
    }

    private fun publishLocked() {
        _active.value = jobs.values.map { it.state.value }.filter { !it.phase.terminal }
    }

    /** Drops finished background jobs that nobody fetched within [RETAIN_MS], and the oldest beyond [RETAIN_MAX]. */
    private fun purgeLocked() {
        val now = System.currentTimeMillis()
        val finished = jobs.values.filter { it.state.value.phase.terminal }
        val expired = finished.filter { now - (it.state.value.finishedAt ?: now) > RETAIN_MS }
        val overflow = (finished - expired.toSet()).dropLast(RETAIN_MAX)
        val gone = expired + overflow
        if (gone.isEmpty()) return
        gone.forEach { jobs.remove(it.id) }
        scope.launch { gone.forEach { runCatching { store.delete(it.id) } } }
    }

    private fun holdWakeLockLocked() {
        wakeLock.acquire()
        _wakeLockHeld.value = true
    }

    private fun releaseWakeLockIfIdleLocked() {
        if (jobs.values.any { !it.state.value.phase.terminal }) return
        wakeLock.release()
        _wakeLockHeld.value = false
    }

    private fun newId(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "chatcmpl-" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "JobManager"
        const val CHAT_PATH = "/v1/chat/completions"
        const val CLIENT_CLOSED = "client_closed"
        private const val PREVIEW_CHARS = 600

        /** Waiting jobs on top of the running one (docs/LOCAL_LLM_API.md §1.2). */
        const val MAX_WAITING = 4

        /** How long a finished background job and its result are kept for the client. */
        const val RETAIN_MS = 24 * 3_600_000L
        private const val RETAIN_MAX = 50
        private const val CHECKPOINT_TOKENS = 32
        private const val CHECKPOINT_MS = 20_000L
    }
}
