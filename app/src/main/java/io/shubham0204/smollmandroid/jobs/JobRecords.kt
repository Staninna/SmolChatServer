/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.jobs

import io.shubham0204.smollmandroid.api.ApiError
import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.api.ResolvedRequest
import io.shubham0204.smollmandroid.data.JobEntity
import io.shubham0204.smollmandroid.data.RequestLogEntity
import io.shubham0204.smollmandroid.engine.Checkpoint
import io.shubham0204.smollmandroid.engine.GenerationResult
import kotlinx.serialization.encodeToString

// How a job is written down: its row in the jobs table (so it survives a restart) and its row in
// the request log. Pure mappings; JobManager decides when they are written.

/** The row for this job. Without an [outcome] it is unfinished and carries its checkpoint. */
internal fun ServerJob.toEntity(outcome: JobOutcome?): JobEntity {
    val s = state.value
    val result = (outcome as? JobOutcome.Success)?.result
    return JobEntity(
        id = s.id,
        requestId = s.requestId,
        clientPackage = s.clientPackage,
        clientLabel = s.clientLabel,
        createdAt = s.createdAt,
        requestJson = LocalApi.json.encodeToString(request),
        phase = s.phase.name,
        finishedAt = s.finishedAt,
        model = s.model,
        promptTokens = s.promptTokens,
        cachedTokens = s.cachedTokens,
        completionTokens = s.completionTokens,
        content = result?.content,
        reasoning = result?.reasoning,
        finishReason = s.finishReason,
        promptMs = result?.promptMs,
        generationMs = result?.generationMs,
        tokensPerSecond = s.tokensPerSecond,
        errorCode = s.errorCode,
        errorMessage = s.errorMessage,
        // a finished job needs no checkpoint any more
        checkpointText = if (outcome == null) raw.toString().takeIf { it.isNotEmpty() } else null,
        checkpointTokens = if (outcome == null) rawTokens else 0,
        resumes = s.resumes,
    )
}

/**
 * The job a stored row stands for. A finished job comes back finished, with its outcome; an
 * unfinished one comes back queued, to continue from its checkpoint. Null for a row that cannot be
 * read any more.
 */
internal fun JobEntity.toServerJob(): ServerJob? {
    val request = runCatching { LocalApi.json.decodeFromString<ResolvedRequest>(requestJson) }.getOrNull() ?: return null
    // a phase this version does not know can only be an unfinished one: run it again
    val stored = JobPhase.entries.firstOrNull { it.name == phase } ?: JobPhase.Queued
    val unfinished = !stored.terminal
    val snapshot =
        JobSnapshot(
            id = id,
            clientPackage = clientPackage,
            clientLabel = clientLabel,
            mode = JobMode.Background,
            phase = if (unfinished) JobPhase.Queued else stored,
            createdAt = createdAt,
            finishedAt = finishedAt,
            model = model,
            promptTokens = promptTokens,
            promptProcessed = if (unfinished) 0 else promptTokens,
            cachedTokens = cachedTokens,
            completionTokens = if (unfinished) checkpointTokens else completionTokens,
            maxTokens = request.maxTokens,
            maxTokensFromClient = request.maxTokens != null,
            tokensPerSecond = tokensPerSecond,
            finishReason = finishReason,
            errorCode = errorCode,
            errorMessage = errorMessage,
            requestId = requestId,
            resumes = resumes + if (unfinished) 1 else 0,
        )
    val checkpoint =
        checkpointText?.takeIf { unfinished && it.isNotEmpty() && checkpointTokens > 0 }?.let { Checkpoint(it, checkpointTokens) }
    val job = ServerJob(snapshot, request, events = null, requestBody = null, resumeFrom = checkpoint)
    if (!unfinished) job.outcomeDeferred.complete(storedOutcome(stored))
    return job
}

private fun JobEntity.storedOutcome(phase: JobPhase): JobOutcome =
    when (phase) {
        JobPhase.Completed ->
            JobOutcome.Success(
                GenerationResult(
                    content = content.orEmpty(),
                    reasoning = reasoning.orEmpty(),
                    finishReason = finishReason ?: "stop",
                    promptTokens = promptTokens,
                    cachedTokens = cachedTokens,
                    completionTokens = completionTokens,
                    promptMs = promptMs ?: 0.0,
                    generationMs = generationMs ?: 0.0,
                    tokensPerSecond = tokensPerSecond ?: 0.0,
                ),
                model.orEmpty(),
            )
        JobPhase.Failed ->
            JobOutcome.Failure(
                ApiError.entries.firstOrNull { it.code == errorCode } ?: ApiError.InternalError,
                errorMessage ?: "The request failed.",
            )
        else -> JobOutcome.Cancelled
    }

/** The request-log row a job writes when it ends. Bodies only with [logBodies]. */
internal fun ServerJob.toLogEntry(outcome: JobOutcome, logBodies: Boolean, now: Long): RequestLogEntity {
    val s = state.value
    val result = (outcome as? JobOutcome.Success)?.result
    return RequestLogEntity(
        timestamp = s.createdAt,
        clientPackage = s.clientPackage,
        clientLabel = s.clientLabel,
        method = "POST",
        path = LocalApi.CHAT_COMPLETIONS_PATH,
        status =
            when (outcome) {
                is JobOutcome.Success -> 200
                is JobOutcome.Failure -> outcome.error.httpStatus
                JobOutcome.Cancelled -> ApiError.Cancelled.httpStatus
            },
        errorCode = s.errorCode,
        stream = s.mode == JobMode.Stream,
        promptTokens = result?.promptTokens ?: s.promptTokens.takeIf { it > 0 },
        completionTokens = result?.completionTokens ?: s.completionTokens.takeIf { it > 0 },
        durationMs = (s.finishedAt ?: now) - s.createdAt,
        tokensPerSecond = result?.tokensPerSecond?.takeIf { it > 0 } ?: s.tokensPerSecond,
        requestBody = requestBody.takeIf { logBodies },
        responseBody = result?.content.takeIf { logBodies },
        mode = s.mode.wire,
        model = s.model,
        queueMs = s.queueMs,
        loadMs = s.loadMs,
        promptMs =
            result?.promptMs?.toLong()
                ?: s.promptStartedAt?.let { (s.generationStartedAt ?: s.finishedAt ?: it) - it },
        generationMs =
            result?.generationMs?.toLong()
                ?: s.generationStartedAt?.let { (s.finishedAt ?: it) - it },
        cachedTokens = s.cachedTokens.takeIf { s.promptTokens > 0 },
        finishReason = s.finishReason,
    )
}
