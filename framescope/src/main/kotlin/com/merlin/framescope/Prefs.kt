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

    var x: Int
        get() = sp.getInt("x", 24)
        set(v) = sp.edit().putInt("x", v).apply()

    var y: Int
        get() = sp.getInt("y", 160)
        set(v) = sp.edit().putInt("y", v).apply()

    /** Locked overlays ignore touches (they pass through to the game). */
    var locked: Boolean
        get() = sp.getBoolean("locked", false)
        set(v) = sp.edit().putBoolean("locked", v).apply()

    val wantsExtras: Boolean get() = preset == PRESET_FULL

    companion object {
        const val PRESET_MINIMAL = 0
        const val PRESET_STANDARD = 1
        const val PRESET_FULL = 2
        /** One line: FPS · CPU % · RAM % · SoC °C. (Value 3 keeps stored presets stable.) */
        const val PRESET_COMPACT = 3
    }
}
