package com.merlin.framescope

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** "Customize": metrics, appearance, behaviour, access method and diagnostics. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        Priv.init(this)

        val root = ui.column(16)

        // ── Appearance ───────────────────────────────────────────────────────
        val look = section(root, "Appearance", first = true)
        slider(look, "Text size", 60, 200, (prefs.textScale * 100).toInt(), { "$it%" }) { prefs.textScale = it / 100f }
        slider(look, "Background opacity", 0, 255, prefs.bgAlpha, { "${it * 100 / 255}%" }) { prefs.bgAlpha = it }
        check(look, "Color by smoothness", prefs.colorize) { prefs.colorize = it }
        hint(look, "FPS turns green, amber or red against its target; temperature turns amber or red when hot or throttled.")

        // ── Position ─────────────────────────────────────────────────────────
        val pos = section(root, "Position")
        check(pos, "Snap to screen edges", prefs.snapEdges) { prefs.snapEdges = it }
        hint(pos, "When dropped near an edge or the centre, the overlay jumps there. It always stays on screen and clear of the notch, and remembers portrait and landscape separately.")
        check(pos, "Lock position (touches pass through to the game)", prefs.locked) { prefs.locked = it }
        pos.addView(ui.outlineButton("Reset overlay position").apply {
            setOnClickListener {
                prefs.resetPosition()
                changed()
                Toast.makeText(
                    this@SettingsActivity,
                    if (OverlayService.running) "Overlay moved to the top-left corner" else "Overlay will start in the top-left corner",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ui.lp(8))

        // ── Full style metrics ───────────────────────────────────────────────
        val metrics = section(root, "Full style: what to show")
        check(metrics, "CPU load", prefs.showCpuLoad) { prefs.showCpuLoad = it }
        check(metrics, "CPU frequency per core cluster", prefs.showCpuFreq) { prefs.showCpuFreq = it }
        hint(metrics, "Slowest to fastest cluster, in GHz (e.g. 1.0|0.6|0.8 GHz). ▼ means a cluster is being throttled.")
        check(metrics, "RAM usage (system + current app)", prefs.showRam) { prefs.showRam = it }
        check(metrics, "Swap (ZRAM + memory extension)", prefs.showSwap) { prefs.showSwap = it }
        check(metrics, "GPU load + frequency", prefs.showGpu) { prefs.showGpu = it }
        hint(metrics, "Accurate with root. Without root it is experimental and shown as GPU*.")
        check(metrics, "Temperatures (SoC / battery)", prefs.showTemps) { prefs.showTemps = it }

        // ── Sampling ─────────────────────────────────────────────────────────
        val sampling = section(root, "Sampling")
        slider(sampling, "FPS poll interval", 100, 500, prefs.pollMs, { "$it ms" }) { prefs.pollMs = it }
        slider(sampling, "Current-FPS window", 250, 2000, prefs.fpsWindowMs, { "$it ms" }) { prefs.fpsWindowMs = it }

        // ── Access method ────────────────────────────────────────────────────
        val access = section(root, "Access method")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        arrayOf(
            Prefs.ACCESS_AUTO to "Auto: Shizuku if granted, otherwise root",
            Prefs.ACCESS_SHIZUKU to "Shizuku only",
            Prefs.ACCESS_ROOT to "Root only (su): accurate GPU and real sensors"
        ).forEach { (v, label) ->
            group.addView(RadioButton(this).apply { id = 200 + v; text = label })
        }
        group.check(200 + prefs.access)
        group.setOnCheckedChangeListener { _, id ->
            prefs.access = id - 200
            Priv.shutdown() // restart the helper with the new method
            Priv.ensureBound()
            changed()
        }
        access.addView(group)

        // ── Help & diagnostics ───────────────────────────────────────────────
        val more = section(root, "Help and diagnostics")
        more.addView(ui.outlineButton("What the numbers mean").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, HelpActivity::class.java)) }
        }, ui.lp())
        more.addView(ui.outlineButton("Run setup guide again").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, SetupActivity::class.java)) }
        }, ui.lp(8))
        more.addView(ui.outlineButton("Device probe").apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ProbeActivity::class.java)) }
        }, ui.lp(8))

        root.addView(ui.text("Version ${BuildConfig.VERSION_NAME}", 12f, ui.muted).apply { gravity = Gravity.CENTER }, ui.lp(16))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(root)
        })
    }

    private fun changed() = OverlayService.refresh(this)

    private fun section(root: LinearLayout, title: String, first: Boolean = false): LinearLayout {
        root.addView(ui.label(title), ui.lp(if (first) 0 else 20))
        val card = ui.cardColumn()
        root.addView(card, ui.lp(8))
        return card
    }

    private fun hint(parent: LinearLayout, s: String) {
        parent.addView(ui.text(s, 12f, ui.muted).apply { setPadding(ui.dp(32), 0, 0, ui.dp(6)) })
    }

    private fun check(parent: LinearLayout, label: String, value: Boolean, set: (Boolean) -> Unit) {
        parent.addView(CheckBox(this).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, c -> set(c); changed() }
        })
    }

    private fun slider(
        parent: LinearLayout, label: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, set: (Int) -> Unit
    ) {
        val tv: TextView = ui.text("$label: ${fmt(value)}", 14f)
        parent.addView(tv)
        parent.addView(SeekBar(this).apply {
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
        }, ui.lp(2).apply { bottomMargin = ui.dp(8) })
    }
}
