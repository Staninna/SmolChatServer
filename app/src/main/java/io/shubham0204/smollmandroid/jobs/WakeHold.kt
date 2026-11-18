/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.jobs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Keeps the CPU running from [acquire] until [release], however long that is. */
interface WakeHold {
    fun acquire()

    fun release()
}

/**
 * A partial wake lock: the processor stays on with the screen off, from queue to result.
 *
 * Android wants a timeout on every wake lock. A generation may run for hours, so the lock is taken
 * with a timeout and taken again every [RENEW_MS] until [release]; the timeout is only a safety
 * net for a process that stops renewing it.
 */
class PartialWakeLock(context: Context) : WakeHold {
    private val lock =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmolChat:jobs")
            .apply { setReferenceCounted(false) }
    private val handler = Handler(Looper.getMainLooper())
    private val renew =
        object : Runnable {
            override fun run() {
                lock.acquire(TIMEOUT_MS)
                handler.postDelayed(this, RENEW_MS)
            }
        }

    @Synchronized
    override fun acquire() {
        handler.removeCallbacks(renew)
        renew.run()
    }

    @Synchronized
    override fun release() {
        handler.removeCallbacks(renew)
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TIMEOUT_MS = 60 * 60_000L
        const val RENEW_MS = 10 * 60_000L
    }
}
