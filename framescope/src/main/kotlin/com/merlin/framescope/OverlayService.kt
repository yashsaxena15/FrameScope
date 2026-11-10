package com.merlin.framescope

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager

/** Foreground service that owns the overlay window and the sampler. */
class OverlayService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var view: OverlayView? = null
    private var sampler: Sampler? = null
    private lateinit var params: WindowManager.LayoutParams
    private val invalidate = Runnable { view?.postInvalidate() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> sampler?.pause()
                Intent.ACTION_SCREEN_ON -> sampler?.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Priv.init(this)
        Priv.ensureBound()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val s = Sampler(this, prefs, invalidate)
        sampler = s
        val v = OverlayView(this, s, prefs)
        view = v
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.x
            y = prefs.y
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "FrameScope"
        }
        applyTouchMode()
        attachDrag(v)
        wm.addView(v, params)
        s.start()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        running = true
        requestTileUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_RESET -> sampler?.resetStats()
            ACTION_REFRESH -> {
                sampler?.applyPrefs()
                view?.applyPrefs()
                applyTouchMode()
                // Position is stored in prefs on every drag, so this only moves the
                // overlay when something else (e.g. "Reset overlay position") changed it.
                params.x = prefs.x
                params.y = prefs.y
                view?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, params) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        sampler?.stop()
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null
        Priv.shutdown()
        requestTileUpdate()
        super.onDestroy()
    }

    /**
     * Locked overlays pass touches through to the game. Android 12+ only lets
     * touches pass through overlays with alpha ≤ 0.8, so we cap window alpha then.
     */
    private fun applyTouchMode() {
        if (prefs.locked) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = 0.8f
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: OverlayView) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var lastTapAt = 0L
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val doubleTapMs = android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (Math.abs(dx) > slop || Math.abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        wm.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.x = params.x
                        prefs.y = params.y
                    } else {
                        // Double-tap: reset jank and avg / 1% / 0.1% lows (e.g. at match start).
                        val now = e.eventTime
                        if (now - lastTapAt <= doubleTapMs) {
                            sampler?.resetStats()
                            v.performHapticFeedback(
                                if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM
                                else android.view.HapticFeedbackConstants.VIRTUAL_KEY
                            )
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FPS overlay", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reset = PendingIntent.getService(
            this, 3, Intent(this, OverlayService::class.java).setAction(ACTION_RESET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fps)
            .setContentTitle("FrameScope overlay running")
            .setContentText("Double-tap the overlay to reset stats")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Reset stats", reset).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun requestTileUpdate() {
        try {
            TileService.requestListeningState(this, ComponentName(this, FpsTileService::class.java))
        } catch (_: Throwable) {}
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.merlin.framescope.STOP"
        const val ACTION_REFRESH = "com.merlin.framescope.REFRESH"
        const val ACTION_RESET = "com.merlin.framescope.RESET"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        fun refresh(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_REFRESH))
        }
    }
}
