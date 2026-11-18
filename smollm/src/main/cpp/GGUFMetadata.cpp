// SmolChat Server: typed GGUF metadata lookups for the model importer, kept out of the upstream
// GGUFReader.cpp so syncing upstream stays a small merge. Kotlin side: smollm/.../GGUFMetadata.kt.

#include "gguf.h"
#include <algorithm>
#include <jni.h>

// Returns the string value for `key`, or an empty string if the key is missing or not a string.
extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_GGUFMetadataNative_getString(JNIEnv* env, jobject thiz, jlong nativeHandle, jstring key) {
    auto*       ggufContext = reinterpret_cast<gguf_context*>(nativeHandle);
    const char* keyCStr     = env->GetStringUTFChars(key, nullptr);
    int64_t     keyId       = gguf_find_key(ggufContext, keyCStr);
    env->ReleaseStringUTFChars(key, keyCStr);
    if (keyId == -1 || gguf_get_kv_type(ggufContext, keyId) != GGUF_TYPE_STRING) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(gguf_get_val_str(ggufContext, keyId));
}

// element `i` of integer data of `type`; -1 for a non-integer type
static int64_t readIntegerAt(gguf_type type, const void* data, size_t i) {
    switch (type) {
        case GGUF_TYPE_UINT8:  return ((const uint8_t*) data)[i];
        case GGUF_TYPE_INT8:   return ((const int8_t*) data)[i];
        case GGUF_TYPE_UINT16: return ((const uint16_t*) data)[i];
        case GGUF_TYPE_INT16:  return ((const int16_t*) data)[i];
        case GGUF_TYPE_UINT32: return ((const uint32_t*) data)[i];
        case GGUF_TYPE_INT32:  return ((const int32_t*) data)[i];
        case GGUF_TYPE_UINT64: return (int64_t) ((const uint64_t*) data)[i];
        case GGUF_TYPE_INT64:  return ((const int64_t*) data)[i];
        default:               return -1;
    }
}

// Returns an integer value for `key` (for integer arrays: the maximum element), or -1 if missing.
extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_GGUFMetadataNative_getInteger(JNIEnv* env, jobject thiz, jlong nativeHandle, jstring key) {
    auto*       ggufContext = reinterpret_cast<gguf_context*>(nativeHandle);
    const char* keyCStr     = env->GetStringUTFChars(key, nullptr);
    int64_t     keyId       = gguf_find_key(ggufContext, keyCStr);
    env->ReleaseStringUTFChars(key, keyCStr);
    if (keyId == -1) {
        return -1;
    }
    gguf_type type = gguf_get_kv_type(ggufContext, keyId);
    if (type != GGUF_TYPE_ARRAY) {
        return type == GGUF_TYPE_STRING ? -1 : readIntegerAt(type, gguf_get_val_data(ggufContext, keyId), 0);
    }
    gguf_type arrType = gguf_get_arr_type(ggufContext, keyId);
    if (arrType == GGUF_TYPE_STRING || arrType == GGUF_TYPE_ARRAY) {
        return -1;
    }
    const void* data = gguf_get_arr_data(ggufContext, keyId);
    size_t      n    = gguf_get_arr_n(ggufContext, keyId);
    int64_t     best = -1;
    for (size_t i = 0; i < n; i++) {
        best = std::max(best, readIntegerAt(arrType, data, i));
    }
    return best;
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_GGUFMetadataNative_free(JNIEnv* env, jobject thiz, jlong nativeHandle) {
    if (nativeHandle != 0) {
        gguf_free(reinterpret_cast<gguf_context*>(nativeHandle));
    }
}
