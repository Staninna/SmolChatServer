/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.shubham0204.smollmandroid.api.LocalApi
import io.shubham0204.smollmandroid.data.ModelDao
import io.shubham0204.smollmandroid.data.PairedAppDao
import io.shubham0204.smollmandroid.pairing.AppSignature
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

/**
 * Lets paired apps start the server without opening this app (docs/LOCAL_LLM_API.md §3.5). A client
 * binds, makes the one `wake()` call and unbinds. While a visible app is bound here, Android lets
 * this app start its foreground service.
 */
class WakeService : Service() {
    private val controller by inject<ServerController>()
    private val pairedApps by inject<PairedAppDao>()
    private val models by inject<ModelDao>()

    private val gate by lazy {
        WakeGate(
            pairedApps = pairedApps,
            signature = { AppSignature.digest(packageManager, it) },
            hasModel = { models.getAll().isNotEmpty() },
            isRunning = { controller.isRunning },
            start = controller::start,
            isBackgroundRefusal = {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it is ForegroundServiceStartNotAllowedException
            },
        )
    }

    private val binder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != LocalApi.WAKE_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(LocalApi.WAKE_DESCRIPTOR)
                val packages = packageManager.getPackagesForUid(getCallingUid())?.toList().orEmpty()
                val result =
                    try {
                        runBlocking { gate.wake(packages) }
                    } catch (e: Exception) {
                        Log.w(TAG, "wake failed", e)
                        LocalApi.WAKE_FAILED
                    }
                Log.i(TAG, "wake from ${packages.firstOrNull()}: $result")
                reply?.writeNoException()
                reply?.writeInt(result)
                return true
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "WakeService"
    }
}
