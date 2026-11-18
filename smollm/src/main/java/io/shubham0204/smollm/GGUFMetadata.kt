/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollm

// SmolChat Server's typed metadata lookups on top of [GGUFReader], for the model importer. Kept
// out of GGUFReader.kt so syncing upstream stays a small merge; native side: GGUFMetadata.cpp.

/** `true` if the file could be parsed as GGUF. */
val GGUFReader.isValid: Boolean
    get() = nativeHandle != 0L

/** `general.architecture`, e.g. "qwen35", or null if missing. */
fun GGUFReader.getArchitecture(): String? = getStringValue("general.architecture")

/** Reads a string metadata value, or null if it is missing or not a string. */
fun GGUFReader.getStringValue(key: String): String? =
    GGUFMetadataNative.getString(handle(), key).ifEmpty { null }

/** Reads an integer metadata value (max element for integer arrays), or null if missing. */
fun GGUFReader.getIntegerValue(key: String): Long? =
    GGUFMetadataNative.getInteger(handle(), key).takeIf { it >= 0 }

/** Releases the native gguf_context. */
fun GGUFReader.close() {
    if (nativeHandle != 0L) {
        GGUFMetadataNative.free(nativeHandle)
        nativeHandle = 0L
    }
}

private fun GGUFReader.handle(): Long {
    check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
    return nativeHandle
}

/** JNI bindings, resolved in the ggufreader library that [GGUFReader] loaded. */
internal object GGUFMetadataNative {
    external fun getString(nativeHandle: Long, key: String): String

    external fun getInteger(nativeHandle: Long, key: String): Long

    external fun free(nativeHandle: Long)
}
