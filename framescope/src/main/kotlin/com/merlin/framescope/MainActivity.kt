package com.merlin.framescope

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Main screen with four tabs:
 *  Home    – status and fixes, overlay preview, Start/Stop, style, last session
 *  Overlay – appearance, position, per-game profiles
 *  Metrics – what the Full style shows, session summary, sampling, access method
 *  About   – help, setup guide, device probe, version
 * Built from platform widgets only.
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
    private lateinit var lastCard: LinearLayout
    private lateinit var lastText: TextView
    private lateinit var gamesList: LinearLayout
    private val styleCards = ArrayList<Pair<Int, LinearLayout>>()
    private val pages = ArrayList<View>()
    private val tabs = ArrayList<Pair<View, TextView>>()
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
        selectTab(currentTab)

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
        preview.applyPrefs()
        updateStyleCards()
        refreshStatus()
    }

    override fun onPause() {
        Priv.removeListener(privListener)
        super.onPause()
    }

    /** A setting changed: update the preview and the running overlay. */
    private fun changed() {
        preview.applyPrefs()
        OverlayService.refresh(this)
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
        val auto = prefs.autoGames().size
        startBtn.text = when {
            running -> "■   Stop overlay"
            auto > 0 -> "▶   Start (shows in $auto game${if (auto == 1) "" else "s"})"
            else -> "▶   Start overlay"
        }
        startBtn.background = ui.rounded(if (running) ui.danger else ui.accent, 14)
        startBtn.setTextColor(if (running) 0xFFFFFFFF.toInt() else ui.onAccent)
        startBtn.isEnabled = canDraw
        startBtn.alpha = if (canDraw) 1f else 0.45f

        // The preview shows the GPU segment only when this phone can read it accurately.
        synchronized(demo.lock) { demo.shared.gpuAccurate = st == Priv.State.READY && Priv.isRoot }
        preview.invalidate()

        val last = SessionStore.load(this)
        if (last == null) {
            lastCard.visibility = View.GONE
        } else {
            lastCard.visibility = View.VISIBLE
            lastText.text = Ui.appLabel(this, last.pkg) + " · avg " + Math.round(last.avgFps) + " FPS · 1% low " +
                Math.round(last.low1Fps) + " · " + Ui.duration(last.durationMs)
        }
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

    // ── frame: pages + bottom tabs ───────────────────────────────────────────

    private fun buildUi(): View {
        val frame = FrameLayout(this)
        listOf(buildHome(), buildOverlay(), buildMetrics(), buildAbout()).forEach { content ->
            val page = ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            }
            pages.add(page)
            frame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.card)
        }
        arrayOf("Home", "Overlay", "Metrics", "About").forEachIndexed { i, name ->
            val indicator = View(this)
            val label = ui.text(name, 12f, ui.muted, bold = true).apply { gravity = Gravity.CENTER }
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(8), 0, ui.dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = "$name tab"
                addView(indicator, LinearLayout.LayoutParams(ui.dp(28), ui.dp(3)))
                addView(label, ui.lp(6))
                setOnClickListener { selectTab(i) }
            }
            tabs.add(indicator to label)
            bar.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(ui.stroke) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
            addView(bar)
        }
    }

    private fun selectTab(index: Int) {
        val i = index.coerceIn(0, pages.size - 1)
        currentTab = i
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, (indicator, label) ->
            val on = k == i
            label.setTextColor(if (on) ui.accent else ui.muted)
            indicator.background = ui.rounded(if (on) ui.accent else 0x00000000, 2)
        }
    }

    // ── Home ─────────────────────────────────────────────────────────────────

    private fun buildHome(): View {
        val root = ui.column(16)

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
        root.addView(header)

        // Status card: what is wrong, and the button that fixes it
        fixCard = ui.column(14).apply { background = ui.rounded(ui.warnSoft, 14) }
        fixText = ui.text("", 14f)
        fixCard.addView(fixText)
        fixPrimary = ui.button("")
        fixCard.addView(fixPrimary, ui.lp(10))
        fixSecondary = ui.outlineButton("")
        fixCard.addView(fixSecondary, ui.lp(8))
        root.addView(fixCard, ui.lp(14))

        // Preview on a game-like backdrop
        val previewCard = ui.cardColumn()
        previewCard.addView(ui.label("Preview · sample values"))
        val stage = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF27527A.toInt(), 0xFF6E9CBB.toInt(), 0xFF4F5F3C.toInt(), 0xFF2C3524.toInt())
            ).apply { cornerRadius = ui.dp(10).toFloat() }
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            minimumHeight = ui.dp(96)
        }
        preview = OverlayView(this, demo, prefs)
        stage.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        previewCard.addView(stage, ui.lp(8))
        root.addView(previewCard, ui.lp(14))

        startBtn = ui.button("", sizeSp = 17f).apply {
            minHeight = ui.dp(56)
            setOnClickListener { toggleOverlay() }
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
                        prefs.globalPreset = value
                        updateStyleCards()
                        changed()
                    }
                }
                styleCards.add(value to c)
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) leftMargin = ui.dp(8)
                })
            }
            root.addView(row, ui.lp(8))
        }

        // Last session
        lastCard = ui.cardColumn()
        lastCard.addView(ui.label("Last session"))
        lastText = ui.text("", 14f)
        lastCard.addView(lastText, ui.lp(4))
        lastCard.addView(ui.outlineButton("View summary").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SummaryActivity::class.java)) }
        }, ui.lp(10))
        root.addView(lastCard, ui.lp(16))

        root.addView(ui.text("Double-tap the overlay to reset stats · drag to move", 12f, ui.muted).apply {
            gravity = Gravity.CENTER
        }, ui.lp(14))
        return root
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            val before = SessionStore.load(this)?.endedAtWallMs ?: 0L
            OverlayService.stop(this)
            // When the session was long enough, its summary opens right away.
            startBtn.postDelayed({
                refreshStatus()
                val s = SessionStore.load(this)
                if (s != null && s.endedAtWallMs > before && !isFinishing) {
                    startActivity(Intent(this, SummaryActivity::class.java))
                }
            }, 700)
        } else {
            OverlayService.start(this)
            startBtn.postDelayed({ refreshStatus() }, 350)
        }
    }

    private fun updateStyleCards() {
        val current = prefs.globalPreset
        for ((value, card) in styleCards) {
            val on = value == current
            card.background = ui.rounded(if (on) ui.accentSoft else ui.card, 12, if (on) ui.accent else ui.stroke, if (on) 2 else 1)
            card.isSelected = on
        }
    }

    // ── Overlay tab ──────────────────────────────────────────────────────────

    private fun buildOverlay(): View {
        val root = ui.column(16)
        root.addView(ui.text("Overlay", 22f, bold = true))

        val look = section(root, "Appearance")
        slider(look, "Text size", 60, 200, (prefs.textScale * 100).toInt(), { "$it%" }) { prefs.textScale = it / 100f }
        slider(look, "Background opacity", 0, 255, prefs.bgAlpha, { "${it * 100 / 255}%" }) { prefs.bgAlpha = it }
        check(look, "Color by smoothness", prefs.colorize) { prefs.colorize = it }
        hint(look, "FPS turns green, amber or red against its target; temperature turns amber or red when hot or throttled.")

        val pos = section(root, "Position")
        check(pos, "Snap to screen edges", prefs.snapEdges) { prefs.snapEdges = it }
        hint(pos, "Dropped near an edge or the centre, the overlay jumps there. It always stays on screen, and remembers portrait and landscape separately.")
        check(pos, "Lock position (touches pass through to the game)", prefs.locked) { prefs.locked = it }
        pos.addView(ui.outlineButton("Reset overlay position").apply {
            setOnClickListener {
                prefs.resetPosition()
                changed()
                Toast.makeText(this@MainActivity, "Position reset, also for your games", Toast.LENGTH_SHORT).show()
            }
        }, ui.lp(8))

        val games = section(root, "Games")
        games.addView(ui.text("Give a game its own overlay style and position. Drag the overlay while that game is open and the spot is remembered for it.", 13f, ui.muted))
        games.addView(ui.text("Show automatically: start FrameScope once and it waits in the background. The overlay then appears in those games and stays hidden everywhere else.", 13f, ui.muted), ui.lp(6))
        gamesList = ui.column()
        games.addView(gamesList, ui.lp(4))
        games.addView(ui.button("Add a game").apply { setOnClickListener { pickGame() } }, ui.lp(10))
        rebuildGames()
        return root
    }

    private fun rebuildGames() {
        gamesList.removeAllViews()
        for (pkg in prefs.games()) {
            val row = ui.column(12).apply { background = ui.rounded(0x00000000, 12, ui.stroke) }
            row.addView(ui.text(Ui.appLabel(this, pkg), 15f, bold = true))
            row.addView(ui.text(pkg, 11f, ui.muted))

            val styleBtn = ui.outlineButton(styleLabel(prefs.gamePreset(pkg)))
            styleBtn.setOnClickListener {
                val names = arrayOf("Same as default") + Ui.STYLES.map { it.second }
                val values = intArrayOf(-1) + Ui.STYLES.map { it.first }.toIntArray()
                AlertDialog.Builder(this)
                    .setTitle("Overlay style for this game")
                    .setSingleChoiceItems(names, values.indexOf(prefs.gamePreset(pkg)).coerceAtLeast(0)) { d, which ->
                        prefs.setGamePreset(pkg, values[which])
                        styleBtn.text = styleLabel(values[which])
                        changed()
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            row.addView(styleBtn, ui.lp(8))

            row.addView(CheckBox(this).apply {
                text = "Show overlay automatically in this game"
                isChecked = prefs.isAuto(pkg)
                setOnCheckedChangeListener { _, c ->
                    prefs.setAuto(pkg, c)
                    changed()
                    refreshStatus()
                }
            }, ui.lp(4))

            row.addView(ui.text("Remove", 13f, ui.danger, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = ui.dp(40)
                isClickable = true
                isFocusable = true
                contentDescription = "Remove ${Ui.appLabel(this@MainActivity, pkg)} from the games list"
                setOnClickListener {
                    prefs.removeGame(pkg)
                    rebuildGames()
                    changed()
                    refreshStatus()
                }
            })
            gamesList.addView(row, ui.lp(8))
        }
    }

    private fun styleLabel(preset: Int): String =
        "Style: " + (Ui.STYLES.firstOrNull { it.first == preset }?.second ?: "Same as default") + "  ▾"

    /** Lets the user pick an installed app; games are listed first. */
    private fun pickGame() {
        val pm = packageManager
        val apps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName && !prefs.hasGame(it.packageName) }
                .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), isGame(it)) }
                .sortedWith(compareBy<Triple<String, String, Boolean>>({ !it.third }, { it.second.lowercase() }))
        } catch (_: Throwable) {
            emptyList()
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No apps found to add", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add a game")
            .setItems(apps.map { it.second }.toTypedArray()) { _, which ->
                prefs.addGame(apps[which].first)
                rebuildGames()
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isGame(info: ApplicationInfo): Boolean {
        @Suppress("DEPRECATION")
        if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return true
        return Build.VERSION.SDK_INT >= 26 && info.category == ApplicationInfo.CATEGORY_GAME
    }

    // ── Metrics tab ──────────────────────────────────────────────────────────

    private fun buildMetrics(): View {
        val root = ui.column(16)
        root.addView(ui.text("Metrics", 22f, bold = true))

        val full = section(root, "Full style: what to show")
        check(full, "CPU load", prefs.showCpuLoad) { prefs.showCpuLoad = it }
        check(full, "CPU frequency per core cluster", prefs.showCpuFreq) { prefs.showCpuFreq = it }
        hint(full, "Slowest to fastest cluster, in GHz (e.g. 1.0|0.6|0.8 GHz). ▼ means a cluster is being throttled.")
        check(full, "RAM usage (system + current app)", prefs.showRam) { prefs.showRam = it }
        check(full, "Swap (ZRAM + memory extension)", prefs.showSwap) { prefs.showSwap = it }
        check(full, "GPU load + frequency", prefs.showGpu) { prefs.showGpu = it }
        hint(full, "Accurate with root. Without root it is experimental and shown as GPU*.")
        check(full, "Temperatures (SoC / battery)", prefs.showTemps) { prefs.showTemps = it }

        val session = section(root, "Session summary")
        check(session, "Record a summary of each session", prefs.sessionSummary) { prefs.sessionSummary = it }
        hint(session, "Average FPS, 1% low, stutters, peak temperature and throttled time, with a graph you can share. Shown when the overlay stops or a game set to show automatically is closed.")

        val sampling = section(root, "Sampling")
        slider(sampling, "FPS poll interval", 100, 500, prefs.pollMs, { "$it ms" }) { prefs.pollMs = it }
        slider(sampling, "Current-FPS window", 250, 2000, prefs.fpsWindowMs, { "$it ms" }) { prefs.fpsWindowMs = it }

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
            refreshStatus()
        }
        access.addView(group)
        return root
    }

    // ── About tab ────────────────────────────────────────────────────────────

    private fun buildAbout(): View {
        val root = ui.column(16)
        root.addView(ui.text("About", 22f, bold = true))

        val help = section(root, "Help")
        help.addView(ui.outlineButton("What the numbers mean").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, HelpActivity::class.java)) }
        }, ui.lp())
        help.addView(ui.outlineButton("Run the setup guide again").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }
        }, ui.lp(8))

        val diag = section(root, "Diagnostics")
        diag.addView(ui.text("If something looks wrong on your phone, run the probe and share its report.", 13f, ui.muted))
        diag.addView(ui.outlineButton("Device probe").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ProbeActivity::class.java)) }
        }, ui.lp(8))

        val about = section(root, "FrameScope")
        about.addView(ui.text("Version ${BuildConfig.VERSION_NAME}", 14f, bold = true))
        about.addView(ui.text("Free and open source (GPL-3.0). No ads, no internet permission: nothing leaves your phone.", 13f, ui.muted), ui.lp(4))
        about.addView(ui.text("github.com/yashsaxena15/FrameScope", 13f, ui.accent).apply { setTextIsSelectable(true) }, ui.lp(6))
        return root
    }

    // ── shared builders ──────────────────────────────────────────────────────

    private fun section(root: LinearLayout, title: String): LinearLayout {
        root.addView(ui.label(title), ui.lp(18))
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

    companion object {
        /** Remembered while the app process lives, so rotation or returning keeps the tab. */
        private var currentTab = 0
    }
}
