package com.merlin.framescope

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Home screen ("Control Center"): connection status with one-tap fixes, a
 * preview of the overlay, a big Start/Stop button and the overlay styles.
 * Detailed options live in [SettingsActivity]. Platform widgets only.
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var chip: TextView
    private lateinit var fixCard: LinearLayout
    private lateinit var fixText: TextView
    private lateinit var fixPrimary: TextView
    private lateinit var fixSecondary: TextView
    private lateinit var startBtn: TextView
    private lateinit var preview: OverlayView
    private lateinit var demo: DemoSource
    private val styleCards = ArrayList<Pair<Int, LinearLayout>>()
    private val privListener = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        Priv.init(this)

        // First launch: guided setup. Installs that already have settings skip it.
        if (!prefs.setupDone) {
            if (prefs.isExistingInstall) prefs.setupDone = true
            else startActivity(Intent(this, SetupActivity::class.java))
        }

        @Suppress("DEPRECATION")
        demo = DemoSource(windowManager.defaultDisplay.refreshRate)
        setContentView(buildUi())

        if (prefs.setupDone && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Priv.addListener(privListener)
        Priv.ensureBound()
        preview.applyPrefs() // settings may have changed on another screen
        updateStyleCards()
        refreshStatus()
    }

    override fun onPause() {
        Priv.removeListener(privListener)
        super.onPause()
    }

    // ── status ───────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val st = Priv.state()
        val canDraw = Settings.canDrawOverlays(this)

        when (st) {
            Priv.State.READY -> ui.setChip(chip, if (Priv.isRoot) "Root ✓" else "Shizuku ✓", ui.good, ui.goodSoft)
            Priv.State.CONNECTING -> ui.setChip(chip, "Connecting…", ui.accent, ui.accentSoft)
            else -> ui.setChip(chip, "Setup needed", ui.warn, ui.warnSoft)
        }

        // One problem at a time, each with the button that fixes it.
        when {
            st == Priv.State.NO_PERMISSION -> showFix(
                "Shizuku is running, but FrameScope isn't allowed to use it yet.",
                "Grant permission", { Priv.requestPermission() }
            )
            st == Priv.State.NO_SHIZUKU -> {
                val installed = Ui.shizukuInstalled(this)
                val hasRoot = Priv.suPath() != null
                showFix(
                    if (installed) "Shizuku isn't running. Start it in the Shizuku app, then come back."
                    else "FrameScope needs Shizuku or root to read frame data.",
                    if (installed) "Open Shizuku" else "Get Shizuku", { Ui.openShizuku(this) },
                    if (hasRoot) "Use root instead" else null,
                    { prefs.access = Prefs.ACCESS_ROOT; Priv.ensureBound(); refreshStatus() }
                )
            }
            st == Priv.State.NO_ROOT -> showFix(
                "Root access isn't available on this phone.",
                "Use Shizuku", { prefs.access = Prefs.ACCESS_AUTO; Priv.ensureBound(); refreshStatus() }
            )
            st == Priv.State.FAILED -> showFix(
                "Couldn't start the helper: ${Priv.lastError ?: "unknown error"}",
                "Try again", { Priv.shutdown(); Priv.ensureBound(); refreshStatus() }
            )
            !canDraw -> showFix(
                "FrameScope needs permission to draw over other apps.",
                "Allow", { Ui.openOverlayPermission(this) }
            )
            else -> fixCard.visibility = View.GONE
        }

        val running = OverlayService.running
        startBtn.text = if (running) "■   Stop overlay" else "▶   Start overlay"
        startBtn.background = ui.rounded(if (running) ui.danger else ui.accent, 14)
        startBtn.setTextColor(if (running) 0xFFFFFFFF.toInt() else ui.onAccent)
        startBtn.isEnabled = canDraw
        startBtn.alpha = if (canDraw) 1f else 0.45f

        // The preview shows the GPU segment only when this phone can read it accurately.
        synchronized(demo.lock) { demo.shared.gpuAccurate = st == Priv.State.READY && Priv.isRoot }
        preview.invalidate()
    }

    private fun showFix(
        message: String, primary: String, onPrimary: () -> Unit,
        secondary: String? = null, onSecondary: (() -> Unit)? = null
    ) {
        fixCard.visibility = View.VISIBLE
        fixText.text = message
        fixPrimary.text = primary
        fixPrimary.setOnClickListener { onPrimary() }
        if (secondary != null && onSecondary != null) {
            fixSecondary.visibility = View.VISIBLE
            fixSecondary.text = secondary
            fixSecondary.setOnClickListener { onSecondary() }
        } else {
            fixSecondary.visibility = View.GONE
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = ui.column(16)

        // Header: icon, name, status chip, help
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        header.addView(ui.text("FrameScope", 22f, bold = true).apply { setPadding(ui.dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chip = ui.chip()
        header.addView(chip)
        header.addView(ui.text("?", 18f, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Help: what each overlay value means"
            background = ui.rounded(0x00000000, 99, ui.stroke)
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { leftMargin = ui.dp(8) })
        root.addView(header)

        // Status card with fix buttons (hidden when everything is ready)
        fixCard = ui.column(14).apply { background = ui.rounded(ui.warnSoft, 14) }
        fixText = ui.text("", 14f)
        fixCard.addView(fixText)
        fixPrimary = ui.button("")
        fixCard.addView(fixPrimary, ui.lp(10))
        fixSecondary = ui.outlineButton("")
        fixCard.addView(fixSecondary, ui.lp(8))
        root.addView(fixCard, ui.lp(14))

        // Preview of the overlay on a game-like backdrop
        val previewCard = ui.cardColumn()
        previewCard.addView(ui.label("Preview · sample values"))
        val stage = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF27527A.toInt(), 0xFF6E9CBB.toInt(), 0xFF4F5F3C.toInt(), 0xFF2C3524.toInt())
            ).apply { cornerRadius = ui.dp(10).toFloat() }
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            minimumHeight = ui.dp(96)
            clipChildren = true
        }
        preview = OverlayView(this, demo, prefs)
        stage.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        previewCard.addView(stage, ui.lp(8))
        root.addView(previewCard, ui.lp(14))

        // Start / stop
        startBtn = ui.button("", sizeSp = 17f).apply {
            minHeight = ui.dp(56)
            setOnClickListener {
                if (OverlayService.running) OverlayService.stop(this@MainActivity)
                else OverlayService.start(this@MainActivity)
                postDelayed({ refreshStatus() }, 350)
            }
        }
        root.addView(startBtn, ui.lp(14))

        // Overlay styles, two per row
        root.addView(ui.label("Overlay style"), ui.lp(20))
        Ui.STYLES.toList().chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (value, name, desc) ->
                val c = ui.column(12).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "$name overlay style: $desc"
                    addView(ui.text(name, 15f, bold = true))
                    addView(ui.text(desc, 12f, ui.muted))
                    setOnClickListener {
                        prefs.preset = value
                        updateStyleCards()
                        preview.applyPrefs()
                        OverlayService.refresh(this@MainActivity)
                    }
                }
                styleCards.add(value to c)
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = ui.dp(8)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ui.dp(8) })
            root.addView(row, ui.lp(8))
        }

        root.addView(ui.outlineButton("Customize  ›").apply {
            contentDescription = "Customize: metrics, appearance, access method and diagnostics"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, ui.lp(16))

        root.addView(ui.text("Double-tap the overlay to reset stats · drag to move", 12f, ui.muted).apply {
            gravity = Gravity.CENTER
        }, ui.lp(14))

        return ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun updateStyleCards() {
        val current = prefs.preset
        for ((value, card) in styleCards) {
            val on = value == current
            card.background = ui.rounded(if (on) ui.accentSoft else ui.card, 12, if (on) ui.accent else ui.stroke, if (on) 2 else 1)
            card.isSelected = on
        }
    }
}
