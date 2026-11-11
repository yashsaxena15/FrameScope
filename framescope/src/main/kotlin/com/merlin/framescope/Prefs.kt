package com.merlin.framescope

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("framescope", Context.MODE_PRIVATE)

    var preset: Int
        get() = sp.getInt("preset", PRESET_STANDARD)
        set(v) = sp.edit().putInt("preset", v).apply()

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
    fun posX(landscape: Boolean): Int = sp.getInt(if (landscape) "xl" else "x", DEFAULT_X)
    fun posY(landscape: Boolean): Int = sp.getInt(if (landscape) "yl" else "y", DEFAULT_Y)
    fun setPos(landscape: Boolean, x: Int, y: Int) {
        sp.edit().putInt(if (landscape) "xl" else "x", x).putInt(if (landscape) "yl" else "y", y).apply()
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
        sp.edit().remove("x").remove("y").remove("xl").remove("yl").apply()
    }

    companion object {
        /** Shizuku when it is running and granted, otherwise root (su) if present. */
        const val ACCESS_AUTO = 0
        const val ACCESS_SHIZUKU = 1
        const val ACCESS_ROOT = 2
        const val DEFAULT_X = 24
        const val DEFAULT_Y = 160
        const val PRESET_MINIMAL = 0
        const val PRESET_STANDARD = 1
        const val PRESET_FULL = 2
        /** One line: FPS · CPU % · RAM % · SoC °C. (Value 3 keeps stored presets stable.) */
        const val PRESET_COMPACT = 3
        /** Rounded pill: colored FPS, mini frame-time line, CPU / GPU / RAM %, temperature. */
        const val PRESET_PILL = 4

        /** One-line presets share the same data needs (system RAM %, driver-only GPU). */
        fun isOneLine(preset: Int) = preset == PRESET_COMPACT || preset == PRESET_PILL
    }
}
