package com.merlin.framescope

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Settings screen built in code with platform widgets only (no AppCompat/Compose). */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var toggle: Button
    private val privListener = Runnable { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Priv.init(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Priv.addListener(privListener)
        Priv.ensureBound()
        refreshStatus()
    }

    override fun onPause() {
        Priv.removeListener(privListener)
        super.onPause()
    }

    private fun refreshStatus() {
        val st = Priv.state()
        shizukuStatus.text = when (st) {
            Priv.State.READY -> "Shizuku: connected ✓"
            Priv.State.CONNECTING -> "Shizuku: connecting…"
            Priv.State.NO_PERMISSION -> "Shizuku: permission needed"
            Priv.State.NO_SHIZUKU -> "Shizuku: not running — start it in the Shizuku app"
            Priv.State.FAILED -> "Shizuku: helper failed — ${Priv.lastError ?: "unknown error"}"
        }
        shizukuButton.visibility = if (st == Priv.State.NO_PERMISSION) View.VISIBLE else View.GONE
        val canDraw = Settings.canDrawOverlays(this)
        overlayStatus.text = if (canDraw) "Overlay permission: granted ✓" else "Overlay permission: needed"
        overlayButton.visibility = if (canDraw) View.GONE else View.VISIBLE
        toggle.text = if (OverlayService.running) "Stop overlay" else "Start overlay"
        toggle.isEnabled = canDraw
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "FrameScope"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "?"
            textSize = 18f
            contentDescription = "Help: what each overlay value means"
            setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Lightweight FPS / CPU / GPU overlay via Shizuku"
            alpha = 0.7f
        })

        section(root, "Setup")
        shizukuStatus = TextView(this)
        root.addView(shizukuStatus)
        shizukuButton = Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener { Priv.requestPermission() }
        }
        root.addView(shizukuButton)
        overlayStatus = TextView(this)
        root.addView(overlayStatus)
        overlayButton = Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayButton)

        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                if (OverlayService.running) OverlayService.stop(this@MainActivity)
                else OverlayService.start(this@MainActivity)
                postDelayed({ refreshStatus() }, 300)
            }
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(12)
        })

        section(root, "Overlay")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        // Display order; ids are 100 + the stored preset value.
        val presets = arrayOf(
            Prefs.PRESET_MINIMAL to "Minimal — FPS number only",
            Prefs.PRESET_COMPACT to "Compact — FPS, CPU %, RAM %, SoC temperature in one line",
            Prefs.PRESET_STANDARD to "Standard — FPS, avg, 1%/0.1% low, frame-time graph",
            Prefs.PRESET_FULL to "Full — Standard + CPU, RAM, temperatures"
        )
        presets.forEach { (value, n) ->
            group.addView(RadioButton(this).apply {
                id = 100 + value
                text = n
            })
        }
        group.check(100 + prefs.preset)
        group.setOnCheckedChangeListener { _, id ->
            prefs.preset = id - 100
            changed()
        }
        root.addView(group)

        section(root, "Full preset metrics")
        check(root, "CPU load", prefs.showCpuLoad) { prefs.showCpuLoad = it }
        check(root, "CPU frequency per core cluster", prefs.showCpuFreq) { prefs.showCpuFreq = it }
        hint(root, "Shown per core cluster from slowest to fastest, in GHz (e.g. 1.0|0.6|0.8 GHz)")
        check(root, "RAM usage (system + current app)", prefs.showRam) { prefs.showRam = it }
        check(root, "Swap (ZRAM + memory extension / virtual RAM)", prefs.showSwap) { prefs.showSwap = it }
        check(root, "GPU load + frequency (experimental — not accurate on MediaTek)", prefs.showGpu) { prefs.showGpu = it }
        check(root, "Temperatures (SoC / battery)", prefs.showTemps) { prefs.showTemps = it }

        section(root, "Appearance & sampling")
        slider(root, "Text size", 60, 200, (prefs.textScale * 100).toInt(), { "${it}%" }) { prefs.textScale = it / 100f }
        slider(root, "Background opacity", 0, 255, prefs.bgAlpha, { "${it * 100 / 255}%" }) { prefs.bgAlpha = it }
        slider(root, "FPS poll interval", 100, 500, prefs.pollMs, { "$it ms" }) { prefs.pollMs = it }
        slider(root, "Current-FPS window", 250, 2000, prefs.fpsWindowMs, { "$it ms" }) { prefs.fpsWindowMs = it }
        check(root, "Lock position (touches pass through to the game)", prefs.locked) { prefs.locked = it }
        hint(root, "Double-tap the overlay (or use \"Reset stats\" in the notification) to reset jank and lows")

        section(root, "Diagnostics")
        root.addView(Button(this).apply {
            text = "Device probe"
            setOnClickListener { startActivity(Intent(this@MainActivity, ProbeActivity::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "Version ${BuildConfig.VERSION_NAME}"
            alpha = 0.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun changed() = OverlayService.refresh(this)

    private fun section(root: LinearLayout, title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun hint(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.6f
            setPadding(dp(32), 0, 0, dp(4))
        })
    }

    private fun check(root: LinearLayout, label: String, value: Boolean, set: (Boolean) -> Unit) {
        root.addView(CheckBox(this).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, c -> set(c); changed() }
        })
    }

    private fun slider(
        root: LinearLayout, label: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, set: (Int) -> Unit
    ) {
        val tv = TextView(this).apply { text = "$label: ${fmt(value)}" }
        root.addView(tv)
        root.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    tv.text = "$label: ${fmt(p)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    set(s?.progress ?: value)
                    changed()
                }
            })
        })
    }
}
