/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.jobs

import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.engine.Checkpoint
import io.shubham0204.smollmandroid.engine.GenerationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class JobPhase(val wire: String, val terminal: Boolean = false) {
    Queued("queued"),
    LoadingModel("loading_model"),
    ReadingPrompt("processing_prompt"),
    Generating("generating"),
    Completed("completed", terminal = true),
    Failed("failed", terminal = true),
    Cancelled("cancelled", terminal = true),
}

/** How the result travels back to the client. */
enum class JobMode(val wire: String) {
    /** The HTTP response is the result; a dropped connection cancels the job. */
    Blocking("blocking"),
    /** Server-sent events; a dropped connection cancels the job. */
    Stream("stream"),
    /** The job keeps running without a connection; the client polls `/v1/jobs/{id}`. */
    Background("background"),
    /** Started from the Playground screen. */
    Playground("playground"),
}

/** Everything the UI, the notification and `/v1/jobs/{id}` show about one generation. */
data class JobSnapshot(
    val id: String,
    val clientPackage: String?,
    val clientLabel: String,
    val mode: JobMode,
    val phase: JobPhase = JobPhase.Queued,
    val createdAt: Long,
    /** When the job got the inference slot (left the queue). */
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val promptProcessed: Int = 0,
    val cachedTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Upper bound for [completionTokens]: `max_tokens`, or the room left in the context window. */
    val maxTokens: Int? = null,
    /** True when the client sent `max_tokens`, so [maxTokens] is a meaningful target. */
    val maxTokensFromClient: Boolean = false,
    val tokensPerSecond: Double? = null,
    /** Tail of the visible output. Kept in memory only, never stored. */
    val preview: String = "",
    val loadMs: Long? = null,
    val promptStartedAt: Long? = null,
    val generationStartedAt: Long? = null,
    val finishReason: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** The client's own id for this request (idempotency key). */
    val requestId: String? = null,
    /** How often the job was picked up again after the server was stopped or killed. */
    val resumes: Int = 0,
) {
    val queueMs: Long?
        get() = startedAt?.let { it - createdAt }
}

sealed interface JobOutcome {
    data class Success(val result: GenerationResult, val model: String) : JobOutcome

    data class Failure(val error: ApiError, val message: String) : JobOutcome

    data object Cancelled : JobOutcome
}

sealed interface JobEvent {
    /** The prompt fits and generation is about to start: from here on errors are in-band. */
    data class Started(val model: String) : JobEvent

    data class Delta(val content: String, val reasoning: String) : JobEvent

    data class Finished(val outcome: JobOutcome) : JobEvent
}

class ServerJob
internal constructor(
    initial: JobSnapshot,
    val request: ResolvedRequest,
    /** Only for [JobMode.Stream] and [JobMode.Playground]. */
    val events: Channel<JobEvent>?,
    internal val requestBody: String?,
    /** Where to continue, for a job restored from disk. */
    internal val resumeFrom: Checkpoint? = null,
) {
    internal val mutableState = MutableStateFlow(initial)
    val state: StateFlow<JobSnapshot> = mutableState.asStateFlow()
    internal val outcomeDeferred = CompletableDeferred<JobOutcome>()
    internal var coroutine: Job? = null
    internal val text = StringBuilder()

    // checkpoint bookkeeping, only touched from the generation
    internal val raw = StringBuilder(resumeFrom?.rawText.orEmpty())
    internal var rawTokens = resumeFrom?.tokens ?: 0
    internal var checkpointTokens = resumeFrom?.tokens ?: 0
    internal var checkpointAt = 0L

    /** Set when the server stops: the job is parked on disk and continues at the next start. */
    @Volatile internal var parked = false

    val id: String
        get() = state.value.id

    /** The visible text written so far. */
    val content: String
        get() = synchronized(text) { text.toString() }

    /** The finished outcome, or null while the job is queued or running. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val outcome: JobOutcome?
        get() = if (outcomeDeferred.isCompleted) outcomeDeferred.getCompleted() else null

    suspend fun await(): JobOutcome = outcomeDeferred.await()
}
