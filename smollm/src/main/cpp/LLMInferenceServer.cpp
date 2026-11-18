// SmolChat Server: the fork's additions to LLMInference and their JNI bindings.
// Kept out of the upstream files (LLMInference.cpp, smollm.cpp) so that syncing upstream stays a
// small merge; the declarations live in LLMInference.h. Kotlin side: smollm/.../ChatCompletion.kt.

#include "LLMInference.h"
#include <algorithm>
#include <android/log.h>
#include <jni.h>
#include <stdexcept>

#define TAG "[SmolLMAndroid-Cpp]"
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void
LLMInference::_rebuildSampler() {
    if (_sampler) {
        llama_sampler_free(_sampler);
        _sampler = nullptr;
    }
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true; // disable performance metrics
    _sampler = llama_sampler_chain_init(sampler_params);
    if (_presencePenalty != 0.0f) {
        // penalty_last_n = 64, repeat = 1 (off), frequency = 0 (off), presence = _presencePenalty
        llama_sampler_chain_add(_sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(llama_model_get_vocab(_model)), 64, 1.0f, 0.0f, _presencePenalty));
    }
    if (_topK > 0) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_k(_topK));
    }
    if (_topP > 0.0f && _topP < 1.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_top_p(_topP, 1));
    }
    if (_minP >= 0.01f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_min_p(_minP, 1));
    }
    if (_temperature <= 0.0f) {
        llama_sampler_chain_add(_sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(_sampler, llama_sampler_init_temp(_temperature));
        llama_sampler_chain_add(_sampler, llama_sampler_init_dist(_seed));
    }
}

void
LLMInference::setSamplingParams(float temperature, float topP, int topK, float minP, float presencePenalty,
                                int64_t seed) {
    _temperature     = temperature;
    _topP            = topP;
    _topK            = topK;
    _minP            = minP;
    _presencePenalty = presencePenalty;
    _seed            = seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed;
    _rebuildSampler();
}

bool
LLMInference::promptOpensThink() const {
    return _promptOpensThink;
}

int
LLMInference::getPromptTokenCount() const {
    return (int) _promptTokens.size();
}

int
LLMInference::getContextSize() const {
    return _ctx ? (int) llama_n_ctx(_ctx) : 0;
}

bool
LLMInference::_preparePrompt(bool enableThinking, const char* continuation) {
    llama_sampler_reset(_sampler);
    _cacheResponseTokens.clear();
    _response.clear();
    _responseGenerationTime = 0;
    _responseNumTokens      = 0;
    _nCtxUsed               = 0;
    _timeToFirstToken       = 0;
    _isPrefill              = true;

    // apply the chat-template
    std::vector<common_chat_msg> messages;
    for (const llama_chat_message& message : _messages) {
        common_chat_msg msg;
        msg.role    = message.role;
        msg.content = message.content;
        messages.push_back(msg);
    }
    auto templates = common_chat_templates_init(_model, _chatTemplate ? _chatTemplate : "");

    common_chat_templates_inputs inputs;
    inputs.messages        = messages;
    inputs.enable_thinking = enableThinking;

    // Try Jinja rendering first with tools defined to prevent "tojson on Undefined" errors.
    // If Jinja fails (e.g. unsupported filters like lstrip), fall back to legacy rendering.
    inputs.use_jinja                               = true;
    inputs.chat_template_kwargs["tools"]           = "[]";
    inputs.chat_template_kwargs["enable_thinking"] = enableThinking ? "true" : "false";

    std::string prompt;
    bool        usedJinja = true;
    try {
        prompt = common_chat_templates_apply(templates.get(), inputs).prompt.str();
    } catch (const std::exception& e) {
        LOGe("Jinja template failed: %s — retrying with legacy renderer", e.what());
        inputs.use_jinja = false;
        inputs.chat_template_kwargs.clear();
        prompt    = common_chat_templates_apply(templates.get(), inputs).prompt.str();
        usedJinja = false;
    }
    {
        size_t end = prompt.find_last_not_of(" \n\r\t");
        std::string trimmed = end == std::string::npos ? "" : prompt.substr(0, end + 1);
        const std::string openTag = "<think>";
        _promptOpensThink = trimmed.size() >= openTag.size() &&
                            trimmed.compare(trimmed.size() - openTag.size(), openTag.size(), openTag) == 0;
    }
    // Resuming an interrupted answer: what the model had written goes right after the prompt, so
    // generation carries on from there instead of starting over.
    if (continuation != nullptr) {
        prompt += continuation;
    }
    _promptTokens = common_tokenize(llama_model_get_vocab(_model), prompt, true, true);

    // Reuse the KV cache for the tokens this prompt shares with what the context already holds
    // (typically the system prompt, which clients repeat on every request). At least one token is
    // always decoded again, because sampling needs fresh logits.
    size_t keep = 0;
    while (keep < _ctxTokens.size() && keep < _promptTokens.size() && _ctxTokens[keep] == _promptTokens[keep]) {
        keep++;
    }
    if (keep > 0 && keep == _promptTokens.size()) {
        keep--;
    }
    if (keep > 0 && keep < _ctxTokens.size()) {
        // some memory types (recurrent / hybrid models) cannot drop a tail: start over then
        if (!llama_memory_seq_rm(llama_get_memory(_ctx), 0, (llama_pos) keep, -1)) {
            keep = 0;
        }
    }
    if (keep == 0) {
        llama_memory_clear(llama_get_memory(_ctx), false);
    }
    _ctxTokens.resize(keep);
    _promptPos          = keep;
    _promptCachedTokens = (int) keep;
    return usedJinja;
}

int
LLMInference::getPromptCachedTokenCount() const {
    return _promptCachedTokens;
}

int
LLMInference::processPrompt(int maxTokens) {
    const int remaining = (int) _promptTokens.size() - (int) _promptPos;
    // the last prompt token is left for completionLoop(), which samples right after decoding it
    const int n = std::min(maxTokens, remaining - 1);
    if (n > 0) {
        _decodeTokens(_promptTokens.data() + _promptPos, n);
        _promptPos += n;
    }
    return (int) _promptTokens.size() - (int) _promptPos;
}

void
LLMInference::_decodeTokens(llama_token* tokens, int n) {
    // check if the length of the inputs to the model
    // have exceeded the context size of the model
    uint32_t contextSize = llama_n_ctx(_ctx);
    _nCtxUsed            = (int) _ctxTokens.size();
    if (_nCtxUsed + n > (int) contextSize) {
        throw std::runtime_error("context size reached");
    }
    if (llama_decode(_ctx, llama_batch_get_one(tokens, n)) != 0) {
        // the cache may hold part of the batch now: do not trust it for the next prompt
        llama_memory_clear(llama_get_memory(_ctx), false);
        _ctxTokens.clear();
        throw std::runtime_error("llama_decode() failed");
    }
    _ctxTokens.insert(_ctxTokens.end(), tokens, tokens + n);
    _nCtxUsed = (int) _ctxTokens.size();
}

bool
LLMInference::startChatCompletion(bool enableThinking, const char* continuation) {
    return _preparePrompt(enableThinking, continuation);
}

// ---- JNI: io.shubham0204.smollm.ChatNative ----

extern "C" JNIEXPORT jboolean JNICALL
Java_io_shubham0204_smollm_ChatNative_startChatCompletion(JNIEnv* env, jobject thiz, jlong modelPtr,
                                                      jboolean enableThinking, jbyteArray continuation) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    // passed as UTF-8 bytes: JNI strings are "modified UTF-8", which mangles emoji
    std::string text;
    if (continuation != nullptr) {
        jsize  n     = env->GetArrayLength(continuation);
        jbyte* bytes = env->GetByteArrayElements(continuation, nullptr);
        text.assign(reinterpret_cast<const char*>(bytes), (size_t) n);
        env->ReleaseByteArrayElements(continuation, bytes, JNI_ABORT);
    }
    try {
        return llmInference->startChatCompletion(enableThinking == JNI_TRUE,
                                                 continuation != nullptr ? text.c_str() : nullptr)
                   ? JNI_TRUE
                   : JNI_FALSE;
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return JNI_TRUE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_ChatNative_setSamplingParams(JNIEnv* env, jobject thiz, jlong modelPtr, jfloat temperature,
                                                    jfloat topP, jint topK, jfloat minP, jfloat presencePenalty,
                                                    jlong seed) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->setSamplingParams(temperature, topP, topK, minP, presencePenalty, seed);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_ChatNative_getPromptTokenCount(JNIEnv* env, jobject thiz, jlong modelPtr) {
    return reinterpret_cast<LLMInference*>(modelPtr)->getPromptTokenCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_ChatNative_getLoadedContextSize(JNIEnv* env, jobject thiz, jlong modelPtr) {
    return reinterpret_cast<LLMInference*>(modelPtr)->getContextSize();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_shubham0204_smollm_ChatNative_promptOpensThink(JNIEnv* env, jobject thiz, jlong modelPtr) {
    return reinterpret_cast<LLMInference*>(modelPtr)->promptOpensThink() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_ChatNative_getPromptCachedTokenCount(JNIEnv* env, jobject thiz, jlong modelPtr) {
    return reinterpret_cast<LLMInference*>(modelPtr)->getPromptCachedTokenCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_ChatNative_processPrompt(JNIEnv* env, jobject thiz, jlong modelPtr, jint maxTokens) {
    try {
        return reinterpret_cast<LLMInference*>(modelPtr)->processPrompt(maxTokens);
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }
}

// Same as SmolLM.completionLoop(); the fork's ChatNative API reaches it from here.
extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_ChatNative_nextToken(JNIEnv* env, jobject thiz, jlong modelPtr) {
    try {
        std::string piece = reinterpret_cast<LLMInference*>(modelPtr)->completionLoop();
        return env->NewStringUTF(piece.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_ChatNative_finishCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    reinterpret_cast<LLMInference*>(modelPtr)->stopCompletion();
}
