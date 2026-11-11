package com.merlin.fpscore

/**
 * Traffic-light rating for the overlay's "color by smoothness" option.
 *
 * FPS is rated against a target: the display refresh rate, unless the game is
 * clearly running at a lower standard cap (a 30 FPS game on a 60 Hz screen, or
 * 90 on 120 Hz). A cap only counts when the 10-second average sits right at it,
 * so a 60 FPS game struggling at 45 is not mistaken for a "45 FPS cap".
 */
object Smoothness {
    const val UNKNOWN = -1
    const val GOOD = 0
    const val WARN = 1
    const val BAD = 2

    private val CAPS = intArrayOf(30, 60, 90, 120, 144)

    fun targetFps(avgFps: Float, refreshHz: Float): Float {
        val refresh = if (refreshHz > 1f) refreshHz else 60f
        if (avgFps > 0f) {
            for (c in CAPS) {
                if (c >= refresh - 1f) break
                if (avgFps >= c * 0.93f && avgFps <= c * 1.03f) return c.toFloat()
            }
        }
        return refresh
    }

    fun fpsLevel(fps: Float, avgFps: Float, refreshHz: Float): Int {
        if (fps < 0f || fps.isNaN()) return UNKNOWN
        val ratio = fps / targetFps(avgFps, refreshHz)
        return when {
            ratio >= 0.9f -> GOOD
            ratio >= 0.7f -> WARN
            else -> BAD
        }
    }

    /** SoC temperature: amber from 70 °C or while the CPU is being capped, red from 85 °C. */
    fun tempLevel(socCelsius: Float, cpuCapped: Boolean): Int = when {
        socCelsius.isNaN() -> if (cpuCapped) WARN else UNKNOWN
        socCelsius >= 85f -> BAD
        socCelsius >= 70f || cpuCapped -> WARN
        else -> GOOD
    }
}
