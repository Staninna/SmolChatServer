/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.pairing

import android.content.pm.PackageManager
import java.security.MessageDigest

/** Who signed an installed app, as a stable string to store with its pairing. */
object AppSignature {
    /** SHA-256 of [packageName]'s signing certificates (sorted, comma-joined), or null when it is not installed. */
    fun digest(pm: PackageManager, packageName: String): String? =
        try {
            val signers =
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            signers?.takeIf { it.isNotEmpty() }?.map { sha256(it.toByteArray()) }?.sorted()?.joinToString(",")
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
