package com.merlin.framescope

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("framescope", Context.MODE_PRIVATE)

    /** The default overlay style (used wherever no game profile overrides it). */
    var globalPreset: Int
        get() = migrate(sp.getInt("preset", PRESET_PILL))
        set(v) = sp.edit().putInt("preset", v).apply()

    /**
     * The style in effect right now: the profile of the game in front
     * ([activeGame]) when it sets one, otherwise [globalPreset].
     */
    val preset: Int
        get() {
            val g = activeGame
            if (g != null) {
                val p = sp.getInt("g.$g.preset", -1)
                if (p >= 0) return migrate(p)
            }
            return globalPreset
        }

    /** Styles removed in 1.3: Standard becomes Full, Compact becomes Pill. */
    private fun migrate(p: Int) = when (p) {
        PRESET_STANDARD_OLD -> PRESET_FULL
        PRESET_COMPACT_OLD -> PRESET_PILL
        else -> p
    }

    // ── per-game profiles ────────────────────────────────────────────────────

    fun games(): List<String> = (sp.getStringSet("games", null) ?: emptySet()).sorted()
    fun hasGame(pkg: String): Boolean = sp.getStringSet("games", null)?.contains(pkg) == true

    fun addGame(pkg: String) {
        val set = HashSet(sp.getStringSet("games", null) ?: emptySet())
        set.add(pkg)
        sp.edit().putStringSet("games", set).apply()
    }

    fun removeGame(pkg: String) {
        val set = HashSet(sp.getStringSet("games", null) ?: emptySet())
        set.remove(pkg)
        val auto = HashSet(sp.getStringSet("autoGames", null) ?: emptySet())
        auto.remove(pkg)
        sp.edit().putStringSet("games", set).putStringSet("autoGames", auto)
            .remove("g.$pkg.preset").remove("g.$pkg.x").remove("g.$pkg.y").remove("g.$pkg.xl").remove("g.$pkg.yl")
            .apply()
    }

    /** Style for this game, or -1 for "same as default". */
    fun gamePreset(pkg: String): Int = sp.getInt("g.$pkg.preset", -1).let { if (it >= 0) migrate(it) else -1 }
    fun setGamePreset(pkg: String, preset: Int) {
        if (preset < 0) sp.edit().remove("g.$pkg.preset").apply()
        else sp.edit().putInt("g.$pkg.preset", preset).apply()
    }

    /** Games in which the overlay shows by itself; when any are set, it hides everywhere else. */
    fun autoGames(): Set<String> = sp.getStringSet("autoGames", null) ?: emptySet()
    fun isAuto(pkg: String): Boolean = autoGames().contains(pkg)
    fun setAuto(pkg: String, on: Boolean) {
        val auto = HashSet(autoGames())
        if (on) auto.add(pkg) else auto.remove(pkg)
        sp.edit().putStringSet("autoGames", auto).apply()
    }

    /** Record a session summary (average FPS, lows, jank, temperature) while the overlay runs. */
    var sessionSummary: Boolean
        get() = sp.getBoolean("sessionSummary", true)
        set(v) = sp.edit().putBoolean("sessionSummary", v).apply()

    private fun posPrefix(): String {
        val g = activeGame
        return if (g != null && hasGame(g)) "g.$g." else ""
    }

    var showCpuLoad: Boolean
        get() = sp.getBoolean("cpuLoad", true)
        set(v) = sp.edit().putBoolean("cpuLoad", v).apply()

    var showCpuFreq: Boolean
        get() = sp.getBoolean("cpuFreq", true)
        set(v) = sp.edit().putBoolean("cpuFreq", v).apply()

    /**
     * Experimental: GPU data from perfetto tracepoints. On MediaTek Dimensity 9000+
     * the GPU's own microcontroller does DVFS and the kernel event stays at a few
     * coarse steps, so values are not representative there. Off by default.
     */
    var showGpu: Boolean
        get() = sp.getBoolean("gpuExperimental", false)
        set(v) = sp.edit().putBoolean("gpuExperimental", v).apply()

    var showRam: Boolean
        get() = sp.getBoolean("ram", true)
        set(v) = sp.edit().putBoolean("ram", v).apply()

    var showSwap: Boolean
        get() = sp.getBoolean("swap", false)
        set(v) = sp.edit().putBoolean("swap", v).apply()

    var showTemps: Boolean
        get() = sp.getBoolean("temps", true)
        set(v) = sp.edit().putBoolean("temps", v).apply()

    /** FPS poll interval. Must stay well below the ~1 s span of SurfaceFlinger's 128-frame buffer at 120 Hz. */
    var pollMs: Int
        get() = sp.getInt("pollMs", 250)
        set(v) = sp.edit().putInt("pollMs", v).apply()

    /** Window for the big "current FPS" number. */
    var fpsWindowMs: Int
        get() = sp.getInt("fpsWindowMs", 1000)
        set(v) = sp.edit().putInt("fpsWindowMs", v).apply()

    var textScale: Float
        get() = sp.getFloat("textScale", 1f)
        set(v) = sp.edit().putFloat("textScale", v).apply()

    /** Background opacity 0–255. */
    var bgAlpha: Int
        get() = sp.getInt("bgAlpha", 150)
        set(v) = sp.edit().putInt("bgAlpha", v).apply()

    /** Overlay position, remembered separately for portrait and landscape. */
    // A game with a profile keeps its own position; it starts from the default one.
    fun posX(landscape: Boolean): Int {
        val k = if (landscape) "xl" else "x"
        return sp.getInt(posPrefix() + k, sp.getInt(k, DEFAULT_X))
    }
    fun posY(landscape: Boolean): Int {
        val k = if (landscape) "yl" else "y"
        return sp.getInt(posPrefix() + k, sp.getInt(k, DEFAULT_Y))
    }
    fun setPos(landscape: Boolean, x: Int, y: Int) {
        val pre = posPrefix()
        sp.edit().putInt(pre + (if (landscape) "xl" else "x"), x).putInt(pre + (if (landscape) "yl" else "y"), y).apply()
    }

    /** FPS turns green / amber / red by smoothness; temperature amber / red when hot. */
    var colorize: Boolean
        get() = sp.getBoolean("colorize", true)
        set(v) = sp.edit().putBoolean("colorize", v).apply()

    /** Dropped overlays snap to the left / right / top / bottom edge or the centre when close. */
    var snapEdges: Boolean
        get() = sp.getBoolean("snapEdges", true)
        set(v) = sp.edit().putBoolean("snapEdges", v).apply()

    /** First-run setup guide finished or skipped. */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    /** True when settings already exist (an install from before the setup guide). */
    val isExistingInstall: Boolean get() = sp.all.isNotEmpty()

    /** How the privileged helper is started: [ACCESS_AUTO], [ACCESS_SHIZUKU] or [ACCESS_ROOT]. */
    var access: Int
        get() = sp.getInt("access", ACCESS_AUTO)
        set(v) = sp.edit().putInt("access", v).apply()

    /** Locked overlays ignore touches (they pass through to the game). */
    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()

    val wantsExtras: Boolean get() = preset == PRESET_FULL

    /** Moves the overlay back to its default spot (top-left, below the status bar). */
    fun resetPosition() {
        val e = sp.edit().remove("x").remove("y").remove("xl").remove("yl")
        for (g in games()) e.remove("g.$g.x").remove("g.$g.y").remove("g.$g.xl").remove("g.$g.yl")
        e.apply()
    }

    companion object {
        /** Shizuku when it is running and granted, otherwise root (su) if present. */
        const val ACCESS_AUTO = 0
        const val ACCESS_SHIZUKU = 1
        const val ACCESS_ROOT = 2
        const val DEFAULT_X = 24
        const val DEFAULT_Y = 160
        /** The game currently in front while the overlay runs, if it has a profile. Set by OverlayService. */
        @Volatile
        var activeGame: String? = null

        const val PRESET_MINIMAL = 0
        /** FPS stats, frame-time graph, CPU, RAM, GPU, temperatures. */
        const val PRESET_FULL = 2
        /** Rounded pill: colored FPS, mini frame-time line, CPU / GPU / RAM %, temperature. */
        const val PRESET_PILL = 4
        /** Ring that fills toward the target FPS, with CPU / GPU / RAM / temperature and the 1% low. */
        const val PRESET_RING = 5
        private const val PRESET_STANDARD_OLD = 1
        private const val PRESET_COMPACT_OLD = 3

        /** Pill and Ring share the same data needs (system RAM %, driver-only GPU, no per-app RAM). */
        fun isOneLine(preset: Int) = preset == PRESET_PILL || preset == PRESET_RING
    }
}
