package com.merlin.fpscore

/**
 * Parsers for GPU and thermal nodes that are readable only with root on most
 * phones. Verified on a rooted Redmi Note 9 (MT6768 / Helio G85, Mali-G52,
 * Android 10); Adreno (kgsl) formats are the documented Qualcomm ones.
 */
object GpuSysfs {
    /** Known GPU load nodes, best first. */
    val LOAD_PATHS = arrayOf(
        "/proc/mali/utilization",                        // MTK Mali: "gpu/cljs0/cljs1=58/0/0, frequency index=18 ..."
        "/sys/module/ged/parameters/gpu_loading",        // MTK GED: "56"
        "/sys/kernel/ged/hal/gpu_utilization",           // MTK GED (newer): "58 0 0"
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",  // Adreno: "45 %"
    )

    /** Known GPU frequency nodes, best first. */
    val FREQ_PATHS = arrayOf(
        "/proc/gpufreq/gpufreq_var_dump",                // MTK: "g_cur_opp_freq = 484000" (kHz)
        "/sys/kernel/ged/hal/current_freq",              // MTK GED (newer): "<idx> <kHz>"
        "/sys/class/kgsl/kgsl-3d0/gpuclk",               // Adreno: Hz
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",     // Adreno: Hz
    )

    /** GPU load 0–100 from any node in [LOAD_PATHS], or -1. */
    fun parseLoad(path: String, text: String): Int {
        val t = text.trim()
        val v = when {
            path.endsWith("/proc/mali/utilization") -> Regex("""=\s*(\d+)\s*/""").find(t)?.groupValues?.get(1)
            else -> Regex("""\d+""").find(t)?.value // first number: "56", "58 0 0", "45 %"
        }?.toIntOrNull() ?: return -1
        return if (v in 0..100) v else -1
    }

    /** GPU frequency in MHz from any node in [FREQ_PATHS], or -1. */
    fun parseFreqMHz(path: String, text: String): Int {
        val t = text.trim()
        return when {
            path.endsWith("gpufreq_var_dump") ->
                Regex("""g_cur_opp_freq\s*=\s*(\d+)""").find(t)?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 1000).toInt() }
            path.endsWith("/ged/hal/current_freq") ->
                Regex("""\d+""").findAll(t).lastOrNull()?.value?.toLongOrNull()?.let { (it / 1000).toInt() }
            else -> t.toLongOrNull()?.let { (it / 1_000_000).toInt() } // Hz
        } ?: -1
    }
}

/**
 * Raw thermal zones (`/sys/class/thermal/thermal_zone*`: type + temp in m°C).
 * Absent sensors report values like -127000 and are dropped.
 */
object ThermalZones {
    data class Zone(val type: String, val celsius: Float)

    fun parse(type: String?, temp: String?): Zone? {
        val ty = type?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = temp?.trim()?.toLongOrNull() ?: return null
        // Most kernels report m°C; a few report °C or d°C.
        val c = when {
            raw > 1000 || raw < -1000 -> raw / 1000f
            raw > 200 -> raw / 10f
            else -> raw.toFloat()
        }
        if (c < -40f || c > 150f) return null
        return Zone(ty, c)
    }

    /** True for zones that measure the SoC itself (CPU clusters, GPU, AP/SoC die). */
    fun isSocZone(type: String): Boolean {
        val t = type.lowercase()
        if (t.contains("batt") || t.contains("bms") || t.contains("charger") || t.contains("pmic") ||
            t.contains("buck") || t.contains("backlight") || t.contains("skin") || t.contains("pa_") ||
            t.contains("wmt") || t.contains("modem") || t.contains("xo") || t.contains("usb")
        ) return false
        return t.contains("cpu") || t.contains("gpu") || t.contains("soc") || t == "mtktsap" ||
            t.contains("tsap") || t.startsWith("cluster") || t.contains("apc") || t.contains("big") ||
            t.contains("little") || t.contains("mid")
    }

    fun isBatteryZone(type: String): Boolean {
        val t = type.lowercase()
        return t == "battery" || t == "mtktsbattery" || t == "bms" || t.contains("batt")
    }

    /** Hottest SoC zone (what throttling reacts to), or NaN. */
    fun socTemp(zones: List<Zone>): Float =
        zones.filter { isSocZone(it.type) }.maxOfOrNull { it.celsius } ?: Float.NaN

    fun batteryTemp(zones: List<Zone>): Float =
        zones.firstOrNull { isBatteryZone(it.type) }?.celsius ?: Float.NaN
}
