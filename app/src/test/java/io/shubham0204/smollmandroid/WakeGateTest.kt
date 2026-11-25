/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid

import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.data.PairedAppEntity
import io.shubham0204.smollmandroid.service.WakeGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeGateTest {
    private class Refused : IllegalStateException()

    private val apps = FakePairedApps()
    private var signatures = mapOf("com.denser.june" to "aa", "helium314.keyboard" to "bb")
    private var hasModel = true
    private var running = false
    private var refuse = false
    private var starts = 0

    private val gate =
        WakeGate(
            pairedApps = apps,
            signature = { signatures[it] },
            hasModel = { hasModel },
            isRunning = { running },
            start = {
                if (refuse) throw Refused()
                starts++
            },
            isBackgroundRefusal = { it is Refused },
        )

    private fun pair(pkg: String, digest: String?) =
        apps.add(PairedAppEntity(pkg, pkg, "hash-$pkg", pairedAt = 0, certDigest = digest))

    private fun wake(vararg packages: String) = runBlocking { gate.wake(packages.toList()) }

    @Test
    fun `a paired app with the same signature starts the server`() {
        pair("com.denser.june", "aa")
        assertEquals(LocalApi.WAKE_STARTED, wake("com.denser.june"))
        assertEquals(1, starts)
    }

    @Test
    fun `an app that is not paired is refused`() {
        assertEquals(LocalApi.WAKE_NOT_PAIRED, wake("com.denser.june"))
        assertEquals(0, starts)
    }

    @Test
    fun `a reinstall signed by someone else is refused`() {
        pair("com.denser.june", "aa")
        signatures = mapOf("com.denser.june" to "evil")
        assertEquals(LocalApi.WAKE_NOT_PAIRED, wake("com.denser.june"))
        assertEquals(0, starts)
    }

    @Test
    fun `a pairing from before version 4 learns its signature at the first wake`() {
        pair("com.denser.june", null)
        assertEquals(LocalApi.WAKE_STARTED, wake("com.denser.june"))
        assertEquals("aa", runBlocking { apps.get("com.denser.june") }!!.certDigest)
        signatures = mapOf("com.denser.june" to "evil")
        assertEquals(LocalApi.WAKE_NOT_PAIRED, wake("com.denser.june"))
    }

    @Test
    fun `any package of a shared uid can be the paired one`() {
        pair("helium314.keyboard", "bb")
        assertEquals(LocalApi.WAKE_STARTED, wake("com.example.other", "helium314.keyboard"))
    }

    @Test
    fun `without a model the server is not started`() {
        pair("com.denser.june", "aa")
        hasModel = false
        assertEquals(LocalApi.WAKE_NO_MODEL, wake("com.denser.june"))
        assertEquals(0, starts)
    }

    @Test
    fun `a running server is not started again`() {
        pair("com.denser.june", "aa")
        running = true
        assertEquals(LocalApi.WAKE_STARTED, wake("com.denser.june"))
        assertEquals(0, starts)
    }

    @Test
    fun `Android refusing a background start is reported as not allowed`() {
        pair("com.denser.june", "aa")
        refuse = true
        assertEquals(LocalApi.WAKE_NOT_ALLOWED, wake("com.denser.june"))
    }
}
