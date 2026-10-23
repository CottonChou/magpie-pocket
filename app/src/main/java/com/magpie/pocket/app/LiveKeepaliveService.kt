package com.magpie.pocket.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.magpie.pocket.core.model.DataMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the already-authenticated process-owned LIVE session alive while the user has
 * enabled background keepalive. It owns no session or network state of its own: the
 * application ViewModel remains the single runtime owner.
 */
class LiveKeepaliveService : Service() {

    private var foregroundStarted = false
    private var notifiedActive = false
    private var model: AppViewModel? = null
    private var stateJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // Satisfy the startForegroundService() contract before reading model or
        // preferences. Android enforces this even when the service is about to
        // stop itself because keepalive is disabled or the connection is Demo.
        if (!startAsForeground()) {
            stopSelf()
            return
        }

        val model = appViewModel()
        if (model == null) {
            stopSelf()
            return
        }
        this.model = model

        if (definitivelyDisallowed(model)) {
            stopSelf()
            return
        }

        // The process may have been recreated by the OS from a sticky service
        // before DataStore emitted. Mark the service present; AppViewModel still
        // gates all background networking on preference readiness.
        notifyActive(model)
        observePreferenceState(model)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val model = model ?: appViewModel()
        if (model == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        this.model = model

        if (!model.displayPreferencesReady.value) {
            if (definitivelyDisallowed(model)) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            // Stay promoted and wait for the shared model's definitive preference.
            return START_STICKY
        }
        if (!serviceAllowed(model)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        notifyActive(model)
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        stateJob = null
        serviceScope.cancel()
        // Clear the controller's pending-start/running ownership even when
        // promotion failed before the ViewModel was notified. Otherwise the next
        // visible lifecycle would believe a start is still pending and skip retry.
        model?.onKeepaliveServiceActive(false)
        notifiedActive = false
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun appViewModel(): AppViewModel? =
        (application as? MagpieApplication)?.appViewModel

    private fun observePreferenceState(model: AppViewModel) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            combine(
                model.connectionConfig,
                model.displayPreferences,
                model.displayPreferencesReady
            ) { config, preferences, ready -> Triple(config, preferences, ready) }
                .collect { (config, preferences, ready) ->
                    if (!ready) return@collect
                    val allowed = config.mode == DataMode.LIVE &&
                        preferences.keepLiveConnectionInBackground
                    if (allowed) {
                        notifyActive(model)
                    } else {
                        stopSelf()
                    }
                }
        }
    }

    private fun notifyActive(model: AppViewModel) {
        if (notifiedActive) return
        notifiedActive = true
        model.onKeepaliveServiceActive(true)
    }

    private fun serviceAllowed(model: AppViewModel): Boolean {
        val config = model.connectionConfig.value
        return config.mode == DataMode.LIVE &&
            model.displayPreferences.value.keepLiveConnectionInBackground
    }

    private fun definitivelyDisallowed(model: AppViewModel): Boolean {
        if (model.connectionConfig.value.mode != DataMode.LIVE) return true
        return model.displayPreferencesReady.value && !serviceAllowed(model)
    }

    private fun startAsForeground(): Boolean {
        if (foregroundStarted) return true
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "后台保持连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持已配置的 Magpie 实时连接"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Magpie Pocket")
            .setContentText("后台保持实时连接")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            return true
        } catch (_: RuntimeException) {
            // Android can reject a foreground promotion (for example after a
            // permission/notification restriction). Stop cleanly; the next visible
            // Activity transition may retry without crashing.
            return false
        }
    }

    companion object {
        private const val CHANNEL_ID = "magpie_live_keepalive"
        private const val NOTIFICATION_ID = 4102
    }
}
