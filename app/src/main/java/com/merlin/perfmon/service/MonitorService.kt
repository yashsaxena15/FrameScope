package com.merlin.perfmon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.merlin.perfmon.R
import com.merlin.perfmon.data.repository.AlertsRepository
import com.merlin.perfmon.data.repository.PerformanceRepository
import com.merlin.perfmon.data.repository.SettingsRepository
import com.merlin.perfmon.ui.MainActivity
import com.merlin.perfmon.ui.overlay.OverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

private const val CHANNEL_ID      = "perf_mon_channel"
private const val NOTIFICATION_ID = 1001

@AndroidEntryPoint
class MonitorService : Service() {

    @Inject lateinit var perfRepo:     PerformanceRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var overlayMgr:   OverlayManager
    @Inject lateinit var alertsRepo:   AlertsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        perfRepo.startCollecting()
        alertsRepo.startEvaluating()
        scope.launch {
            val s = settingsRepo.settings.first()
            if (s.overlayEnabled) overlayMgr.show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP           -> stopSelf()
            ACTION_SHOW_OVERLAY   -> overlayMgr.show()
            ACTION_HIDE_OVERLAY   -> overlayMgr.hide()
            ACTION_TOGGLE_OVERLAY -> scope.launch { overlayMgr.toggle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        perfRepo.stopCollecting()
        overlayMgr.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openPi    = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi    = pendingServiceIntent(1, ACTION_STOP)
        val overlayPi = pendingServiceIntent(2, ACTION_TOGGLE_OVERLAY)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_content_title))
            .setContentText(getString(R.string.notif_content_text))
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_monitor, getString(R.string.action_overlay), overlayPi)
            .addAction(R.drawable.ic_monitor, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun pendingServiceIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MonitorService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_STOP           = "com.merlin.perfmon.STOP"
        const val ACTION_SHOW_OVERLAY   = "com.merlin.perfmon.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY   = "com.merlin.perfmon.HIDE_OVERLAY"
        const val ACTION_TOGGLE_OVERLAY = "com.merlin.perfmon.TOGGLE_OVERLAY"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, MonitorService::class.java))

        fun stop(context: Context) =
            context.startService(
                Intent(context, MonitorService::class.java).apply { action = ACTION_STOP }
            )

        fun showOverlay(context: Context) =
            context.startService(
                Intent(context, MonitorService::class.java).apply { action = ACTION_SHOW_OVERLAY }
            )

        fun hideOverlay(context: Context) =
            context.startService(
                Intent(context, MonitorService::class.java).apply { action = ACTION_HIDE_OVERLAY }
            )

        fun toggleOverlay(context: Context) =
            context.startService(
                Intent(context, MonitorService::class.java).apply { action = ACTION_TOGGLE_OVERLAY }
            )
    }
}
