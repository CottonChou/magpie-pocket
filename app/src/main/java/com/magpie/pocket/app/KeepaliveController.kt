package com.magpie.pocket.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Test seam for the Activity/Application-owned foreground service lifecycle. */
interface KeepaliveController {
    fun start()
    fun stop()
    fun onServiceActive() = Unit
    fun onServiceInactive() = Unit
}

object NoOpKeepaliveController : KeepaliveController {
    override fun start() = Unit
    override fun stop() = Unit
}

class AndroidKeepaliveController(private val context: Context) : KeepaliveController {
    private var startRequested = false
    private var serviceRunning = false

    @Synchronized
    override fun start() {
        if (startRequested || serviceRunning) return
        startRequested = true
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveKeepaliveService::class.java)
            )
        } catch (_: RuntimeException) {
            // Android may reject a background start. The Activity retries from its
            // next started/resumed transition; foreground use is never blocked.
            startRequested = false
        }
    }

    @Synchronized
    override fun stop() {
        if (!startRequested && !serviceRunning) return
        startRequested = false
        serviceRunning = false
        context.stopService(Intent(context, LiveKeepaliveService::class.java))
    }

    @Synchronized
    override fun onServiceActive() {
        startRequested = false
        serviceRunning = true
    }

    @Synchronized
    override fun onServiceInactive() {
        startRequested = false
        serviceRunning = false
    }
}
