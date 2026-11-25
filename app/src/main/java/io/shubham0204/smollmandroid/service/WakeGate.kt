/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.service

import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.data.PairedAppDao

/**
 * Decides whether a caller may start the server (docs/LOCAL_LLM_API.md §3.5). The caller is one of
 * [packages] (the packages of the calling UID), and must be paired and signed by the same
 * certificate as when it paired.
 */
class WakeGate(
    private val pairedApps: PairedAppDao,
    private val signature: (String) -> String?,
    private val hasModel: suspend () -> Boolean,
    private val isRunning: () -> Boolean,
    private val start: () -> Unit,
    private val isBackgroundRefusal: (Exception) -> Boolean,
) {
    suspend fun wake(packages: List<String>): Int {
        val app = packages.firstNotNullOfOrNull { pairedApps.get(it) } ?: return LocalApi.WAKE_NOT_PAIRED
        val digest = signature(app.packageName) ?: return LocalApi.WAKE_NOT_PAIRED
        when (app.certDigest) {
            null -> pairedApps.setCertDigest(app.packageName, digest) // paired before version 4
            digest -> Unit
            else -> return LocalApi.WAKE_NOT_PAIRED
        }
        if (!hasModel()) return LocalApi.WAKE_NO_MODEL
        if (isRunning()) return LocalApi.WAKE_STARTED
        return try {
            start()
            LocalApi.WAKE_STARTED
        } catch (e: Exception) {
            if (isBackgroundRefusal(e)) LocalApi.WAKE_NOT_ALLOWED else throw e
        }
    }
}
