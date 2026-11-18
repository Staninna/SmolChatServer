/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollm

// SmolChat Server's completion API on top of [SmolLM]: chat-template rendering without an extra
// user message, per-request sampling, prompt processing in steps, and token-by-token output.
// It lives here instead of in SmolLM.kt so that syncing upstream stays a small merge; the native
// side is smollm/src/main/cpp/LLMInferenceServer.cpp.

/** Sampling parameters that can be changed between completions without reloading the model. */
data class SamplingParams(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val presencePenalty: Float,
    /** Negative means "random seed". */
    val seed: Long,
)

val SmolLM.isLoaded: Boolean
    get() = nativePtr != 0L

/** Replaces the sampler chain used by the next completion. */
fun SmolLM.setSamplingParams(params: SamplingParams) =
    ChatNative.setSamplingParams(
        handle(),
        params.temperature,
        params.topP,
        params.topK,
        params.minP,
        params.presencePenalty,
        params.seed,
    )

/**
 * Renders all messages added so far (system/user/assistant) with the model's chat template and
 * tokenizes them. Unlike [SmolLM.getResponseAsFlow], no extra user message is appended. After this
 * call, [getPromptTokenCount] returns the prompt length, and [nextToken] produces the response.
 *
 * @param enableThinking forwarded to the chat template as the `enable_thinking` kwarg.
 * @param continuation raw text the model already wrote for these messages (an interrupted answer).
 *   It is appended after the prompt and generation continues from its end.
 * @return true if the Jinja template was used, false if the legacy renderer was the fallback.
 */
fun SmolLM.prepareChatCompletion(enableThinking: Boolean, continuation: String?): Boolean {
    // passed as UTF-8 bytes: JNI strings are "modified UTF-8", which mangles emoji
    val bytes = continuation?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
    return ChatNative.startChatCompletion(handle(), enableThinking, bytes)
}

/** Number of tokens in the prompt prepared by the last completion call. */
fun SmolLM.getPromptTokenCount(): Int = ChatNative.getPromptTokenCount(handle())

/** Prompt tokens the last [prepareChatCompletion] found in the KV cache (not decoded again). */
fun SmolLM.getPromptCachedTokenCount(): Int = ChatNative.getPromptCachedTokenCount(handle())

/**
 * Decodes up to [maxTokens] more prompt tokens and returns how many are left. Call it in a loop to
 * report progress and to stop early; [nextToken] decodes whatever is left.
 */
fun SmolLM.processPrompt(maxTokens: Int): Int = ChatNative.processPrompt(handle(), maxTokens)

/** True if the prompt prepared last ends with an opening `<think>` tag. */
fun SmolLM.promptOpensThink(): Boolean = ChatNative.promptOpensThink(handle())

/** Context window (n_ctx) of the loaded model context. */
fun SmolLM.getContextSize(): Int = ChatNative.getLoadedContextSize(handle())

/**
 * Decodes one step and returns the next piece of text. Returns `null` at end of generation. May
 * return an empty string while a multi-byte UTF-8 character is incomplete.
 *
 * @throws IllegalStateException with message "context size reached" when the context is full.
 */
fun SmolLM.nextToken(): String? = ChatNative.nextToken(handle()).takeIf { it != "[EOG]" }

/** Ends the completion started with [prepareChatCompletion]. */
fun SmolLM.finishCompletion() = ChatNative.finishCompletion(handle())

private fun SmolLM.handle(): Long {
    check(nativePtr != 0L) { "Model is not loaded. Use SmolLM.load() to load the model" }
    return nativePtr
}

/** JNI bindings, resolved in the smollm library that [SmolLM] loaded. */
internal object ChatNative {
    external fun startChatCompletion(modelPtr: Long, enableThinking: Boolean, continuation: ByteArray?): Boolean

    external fun setSamplingParams(
        modelPtr: Long,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        seed: Long,
    )

    external fun getPromptTokenCount(modelPtr: Long): Int

    external fun promptOpensThink(modelPtr: Long): Boolean

    external fun getPromptCachedTokenCount(modelPtr: Long): Int

    external fun processPrompt(modelPtr: Long, maxTokens: Int): Int

    external fun getLoadedContextSize(modelPtr: Long): Int

    external fun nextToken(modelPtr: Long): String

    external fun finishCompletion(modelPtr: Long)
}
