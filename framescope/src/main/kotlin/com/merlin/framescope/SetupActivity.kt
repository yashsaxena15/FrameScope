package com.merlin.framescope

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-run guide: 1) connect through Shizuku or root, 2) allow the overlay,
 * 3) pick a style. Each step explains why it is needed and offers the button
 * that completes it; the screen updates itself when the user comes back.
 */
class SetupActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui

    private lateinit var badge1: TextView
    private lateinit var status1: TextView
    private lateinit var primary1: TextView
    private lateinit var secondary1: TextView
    private lateinit var badge2: TextView
    private lateinit var status2: TextView
    private lateinit var primary2: TextView
    private lateinit var badge3: TextView
    private lateinit var done: TextView
    private val styleRows = ArrayList<Pair<Int, LinearLayout>>()
    private val privListener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        Priv.init(this)

        val root = ui.column(16)
        root.addView(ui.text("Set up FrameScope", 24f, bold = true))
        root.addView(ui.text("Three quick steps. FrameScope never uses the internet.", 14f, ui.muted), ui.lp(4))

        // Step 1: connect
        val (b1, c1) = step(root, "Connect")
        badge1 = b1
        c1.addView(ui.text("Android only shares real frame data with ADB or root. Shizuku gives FrameScope that access without rooting your phone.", 13f, ui.muted))
        status1 = ui.text("", 14f, bold = true)
        c1.addView(status1, ui.lp(8))
        primary1 = ui.button("")
        c1.addView(primary1, ui.lp(8))
        secondary1 = ui.outlineButton("")
        c1.addView(secondary1, ui.lp(8))

        // Step 2: overlay permission
        val (b2, c2) = step(root, "Allow the overlay")
        badge2 = b2
        c2.addView(ui.text("Lets FrameScope draw its numbers on top of your games.", 13f, ui.muted))
        status2 = ui.text("", 14f, bold = true)
        c2.addView(status2, ui.lp(8))
        primary2 = ui.button("Allow display over other apps").apply {
            setOnClickListener { Ui.openOverlayPermission(this@SetupActivity) }
        }
        c2.addView(primary2, ui.lp(8))

        // Step 3: style
        val (b3, c3) = step(root, "Choose a style")
        badge3 = b3
        c3.addView(ui.text("You can change this any time.", 13f, ui.muted))
        for ((value, name, desc) in Ui.STYLES) {
            val row = ui.column(10).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "$name overlay style: $desc"
                addView(ui.text(name, 14f, bold = true))
                addView(ui.text(desc, 12f, ui.muted))
                setOnClickListener {
                    prefs.globalPreset = value
                    refresh()
                }
            }
            styleRows.add(value to row)
            c3.addView(row, ui.lp(8))
        }

        done = ui.button("Done", sizeSp = 16f).apply { setOnClickListener { finishSetup() } }
        root.addView(done, ui.lp(20))
        root.addView(ui.text("Skip for now", 14f, ui.muted, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ui.dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { finishSetup() }
        }, ui.lp(4))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        Priv.addListener(privListener)
        Priv.ensureBound()
        refresh()
    }

    override fun onPause() {
        Priv.removeListener(privListener)
        super.onPause()
    }

    private fun finishSetup() {
        prefs.setupDone = true
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        finish()
    }

    private fun refresh() {
        val st = Priv.state()
        val connected = st == Priv.State.READY
        val canDraw = Settings.canDrawOverlays(this)
        val hasRoot = Priv.suPath() != null
        val installed = Ui.shizukuInstalled(this)

        // Step 1
        setBadge(badge1, 1, connected, current = !connected)
        secondary1.visibility = View.GONE
        when (st) {
            Priv.State.READY -> {
                status1.text = if (Priv.isRoot) "Connected with root ✓" else "Connected through Shizuku ✓"
                primary1.visibility = View.GONE
            }
            Priv.State.CONNECTING -> {
                status1.text = "Connecting… If a superuser prompt appears, tap Grant."
                primary1.visibility = View.GONE
            }
            Priv.State.NO_PERMISSION -> {
                status1.text = "Shizuku is running. Allow FrameScope to use it."
                primary(primary1, "Grant Shizuku permission") { Priv.requestPermission() }
            }
            Priv.State.NO_SHIZUKU -> {
                status1.text = if (installed) "Shizuku isn't running. Start it in the Shizuku app, then come back."
                else "Shizuku isn't installed."
                primary(primary1, if (installed) "Open Shizuku" else "Get Shizuku") { Ui.openShizuku(this) }
                if (hasRoot) {
                    secondary1.visibility = View.VISIBLE
                    secondary1.text = "I use root instead"
                    secondary1.setOnClickListener { prefs.access = Prefs.ACCESS_ROOT; Priv.ensureBound(); refresh() }
                }
            }
            Priv.State.NO_ROOT -> {
                status1.text = "Root access isn't available on this phone."
                primary(primary1, "Use Shizuku") { prefs.access = Prefs.ACCESS_AUTO; Priv.ensureBound(); refresh() }
            }
            Priv.State.FAILED -> {
                status1.text = "Couldn't start the helper: ${Priv.lastError ?: "unknown error"}"
                primary(primary1, "Try again") { Priv.shutdown(); Priv.ensureBound(); refresh() }
            }
        }
        status1.setTextColor(if (connected) ui.good else ui.ink)

        // Step 2
        setBadge(badge2, 2, canDraw, current = connected && !canDraw)
        status2.text = if (canDraw) "Allowed ✓" else "Not allowed yet"
        status2.setTextColor(if (canDraw) ui.good else ui.ink)
        primary2.visibility = if (canDraw) View.GONE else View.VISIBLE

        // Step 3
        setBadge(badge3, 3, false, current = connected && canDraw)
        val current = prefs.globalPreset
        for ((value, row) in styleRows) {
            val on = value == current
            row.background = ui.rounded(if (on) ui.accentSoft else 0x00000000, 10, if (on) ui.accent else ui.stroke, if (on) 2 else 1)
            row.isSelected = on
        }

        done.alpha = if (connected && canDraw) 1f else 0.6f
        done.text = if (connected && canDraw) "Done" else "Finish later"
    }

    private fun primary(v: TextView, label: String, action: () -> Unit) {
        v.visibility = View.VISIBLE
        v.text = label
        v.setOnClickListener { action() }
    }

    private fun setBadge(v: TextView, n: Int, complete: Boolean, current: Boolean) {
        v.text = if (complete) "✓" else n.toString()
        when {
            complete -> { v.background = ui.rounded(ui.goodSoft, 99); v.setTextColor(ui.good) }
            current -> { v.background = ui.rounded(ui.accent, 99); v.setTextColor(ui.onAccent) }
            else -> { v.background = ui.rounded(0x00000000, 99, ui.stroke); v.setTextColor(ui.muted) }
        }
    }

    /** Adds a numbered step card; returns its badge and its content column. */
    private fun step(root: LinearLayout, title: String): Pair<TextView, LinearLayout> {
        val card = ui.cardColumn()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = ui.text("", 13f, bold = true).apply { gravity = Gravity.CENTER }
        head.addView(badge, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        head.addView(ui.text(title, 17f, bold = true).apply { setPadding(ui.dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val content = ui.column()
        card.addView(content, ui.lp(8))
        root.addView(card, ui.lp(14))
        return badge to content
    }
}
