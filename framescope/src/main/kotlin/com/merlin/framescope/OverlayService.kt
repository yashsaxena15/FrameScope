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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // Per-game profiles and automatic showing
    private var focusPkg: String? = null
    private var shown = true
    private val leaveGame = Runnable { applyFocus(null) }

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
            x = prefs.posX(isLandscape())
            y = prefs.posY(isLandscape())
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "FrameScope"
        }
        applyTouchMode()
        attachDrag(v)
        wm.addView(v, params)
        s.onFocus = { pkg -> main.post { onFocus(pkg) } }
        s.start()
        applyFocus(null) // hidden until a chosen game opens, when any game is set to show automatically
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
            // Settings or the game list changed: re-evaluate everything for the app in front.
            ACTION_REFRESH -> if (view != null) applyFocus(focusPkg?.takeIf { prefs.hasGame(it) })
        }
        return START_NOT_STICKY
    }

    /** The focused app changed (reported by the sampler). */
    private fun onFocus(pkg: String) {
        focusPkg = pkg
        main.removeCallbacks(leaveGame)
        if (prefs.hasGame(pkg)) {
            applyFocus(pkg)
        } else if (Prefs.activeGame != null) {
            // Wait a moment before leaving game mode: popups and permission dialogs
            // take focus briefly while the game keeps running behind them.
            main.postDelayed(leaveGame, 3000)
        }
    }

    /**
     * Applies the profile of [game] (null = no profiled game in front): its style
     * and saved position, and whether the overlay is shown. When any game is set
     * to show automatically, the overlay hides everywhere else.
     */
    private fun applyFocus(game: String?) {
        Prefs.activeGame = game
        val auto = prefs.autoGames()
        val show = auto.isEmpty() || (game != null && game in auto)
        if (show != shown) {
            shown = show
            view?.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            sampler?.setHidden(!show)
            if (!show) finishSession() // the game was closed: wrap up its session
        }
        sampler?.applyPrefs()
        view?.applyPrefs()
        applyTouchMode()
        params.x = prefs.posX(isLandscape())
        params.y = prefs.posY(isLandscape())
        view?.let { v ->
            if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
            v.post { settle(v, snap = false); if (v.isAttachedToWindow) wm.updateViewLayout(v, params) }
        }
    }

    /** Saves the session summary (if there was enough to measure) and offers it in a notification. */
    private fun finishSession() {
        val sum = sampler?.takeSummary() ?: return
        if (sum.frames < 300 || sum.durationMs < 10_000) return
        SessionStore.save(this, sum)
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SUMMARY, "Session summaries", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                this, 4, Intent(this, SummaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(NOTIF_SUMMARY, Notification.Builder(this, CHANNEL_SUMMARY)
                .setSmallIcon(R.drawable.ic_fps)
                .setContentTitle("Session summary: " + Ui.appLabel(this, sum.pkg))
                .setContentText("avg ${Math.round(sum.avgFps)} FPS · 1% low ${Math.round(sum.low1Fps)} · " + Ui.duration(sum.durationMs))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(leaveGame)
        finishSession()
        Prefs.activeGame = null
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        sampler?.stop()
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null
        Priv.shutdown()
        requestTileUpdate()
        super.onDestroy()
    }

    private fun isLandscape() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun screenSize(): android.graphics.Point {
        val p = android.graphics.Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    /**
     * Keeps the overlay fully on screen and clear of the notch. With [snap] it
     * also jumps to the left / right edge, the horizontal centre, or the top /
     * bottom edge when dropped close to one.
     */
    private fun settle(v: android.view.View, snap: Boolean) {
        val size = screenSize()
        val d = resources.displayMetrics.density
        val margin = (6 * d).toInt()
        val magnet = (28 * d).toInt()
        var l = margin; var t = margin; var r = margin; var b = margin
        if (Build.VERSION.SDK_INT >= 28) {
            v.rootWindowInsets?.displayCutout?.let {
                l += it.safeInsetLeft; t += it.safeInsetTop; r += it.safeInsetRight; b += it.safeInsetBottom
            }
        }
        val maxX = maxOf(l, size.x - v.width - r)
        val maxY = maxOf(t, size.y - v.height - b)
        var x = params.x.coerceIn(l, maxX)
        var y = params.y.coerceIn(t, maxY)
        if (snap) {
            val centre = (size.x - v.width) / 2
            when {
                x - l < magnet -> x = l
                maxX - x < magnet -> x = maxX
                Math.abs(x - centre) < magnet -> x = centre
            }
            if (y - t < magnet) y = t else if (maxY - y < magnet) y = maxY
        }
        params.x = x
        params.y = y
    }

    /** Rotation: restore the position saved for the new orientation. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        params.x = prefs.posX(isLandscape())
        params.y = prefs.posY(isLandscape())
        v.post {
            settle(v, snap = false)
            if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
        }
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
                        settle(v, snap = prefs.snapEdges)
                        wm.updateViewLayout(v, params)
                        prefs.setPos(isLandscape(), params.x, params.y)
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
            .setContentText(
                if (prefs.autoGames().isNotEmpty()) "Shows automatically in your chosen games"
                else "Double-tap the overlay to reset stats"
            )
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
        private const val CHANNEL_SUMMARY = "summary"
        const val NOTIF_SUMMARY = 2
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
