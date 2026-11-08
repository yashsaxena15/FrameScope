package com.merlin.fpscore

/** CPU load from successive /proc/stat reads (shell-readable on Android 16). */
class CpuLoad(maxCores: Int = 16) {
    private val prevTotal = LongArray(maxCores + 1)
    private val prevIdle = LongArray(maxCores + 1)
    private var primed = false

    /** Index 0 = all CPUs, 1..n = cpu0..cpu(n-1). Values 0–100, or -1 if unknown. */
    val load = FloatArray(maxCores + 1) { -1f }
    var cores = 0
        private set

    fun update(procStat: CharSequence) {
        var slot = 0
        val fields = LongArray(10)
        for (line in procStat.lineSequence()) {
            if (!line.startsWith("cpu")) {
                if (slot > 0) break else continue
            }
            val parts = line.split(' ').filter { it.isNotEmpty() }
            val idx = if (parts[0] == "cpu") 0 else (parts[0].substring(3).toIntOrNull() ?: continue) + 1
            if (idx >= load.size) continue
            var n = 0
            for (p in 1 until parts.size) {
                if (n >= fields.size) break
                fields[n++] = parts[p].toLongOrNull() ?: 0L
            }
            var total = 0L
            for (k in 0 until n) total += fields[k]
            // user nice system idle iowait irq softirq steal guest guest_nice
            total -= if (n >= 10) fields[8] + fields[9] else 0L // guest time is already in user
            val idle = fields[3] + (if (n > 4) fields[4] else 0L)
            if (primed) {
                val dt = total - prevTotal[idx]
                val di = idle - prevIdle[idx]
                load[idx] = if (dt > 0) ((dt - di) * 100f / dt).coerceIn(0f, 100f) else load[idx]
            }
            prevTotal[idx] = total
            prevIdle[idx] = idle
            if (idx > cores) cores = idx
            slot++
        }
        primed = true
    }
}

/** Parses temperatures from `dumpsys thermalservice` (thermal_zone* is SELinux-blocked on HyperOS 3). */
object ThermalParser {
    private val ENTRY = Regex("""Temperature\{mValue=([-0-9.]+|NaN), mType=(-?\d+), mName=([^,}]+)""")

    /** Returns name → °C from the "Current temperatures from HAL" block (falls back to cached). */
    fun parse(dump: CharSequence): Map<String, Float> {
        val hal = section(dump, "Current temperatures from HAL:")
        val src = if (hal.isNotEmpty()) hal else section(dump, "Cached temperatures:")
        val out = LinkedHashMap<String, Float>()
        for (line in src) {
            val m = ENTRY.find(line) ?: continue
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            if (v.isNaN()) continue
            out[m.groupValues[3].trim()] = v
        }
        return out
    }

    private fun section(dump: CharSequence, header: String): List<String> {
        val lines = dump.lineSequence().toList()
        val i = lines.indexOfFirst { it.trim() == header }
        if (i < 0) return emptyList()
        val res = ArrayList<String>()
        for (k in i + 1 until lines.size) {
            val t = lines[k].trim()
            if (!t.startsWith("Temperature{")) break
            res.add(t)
        }
        return res
    }
}

/** CPU frequency cluster layout from sysfs (policy dirs + related_cpus). */
data class CpuCluster(val policyPath: String, val cpus: List<Int>, val maxKHz: Long)

/** Values from /proc/meminfo in kB (-1 when missing). */
class MemInfo {
    var totalKb = -1L
    var availableKb = -1L
    var swapTotalKb = -1L
    var swapFreeKb = -1L

    val usedKb: Long get() = if (totalKb > 0 && availableKb >= 0) totalKb - availableKb else -1L
    val swapUsedKb: Long get() = if (swapTotalKb > 0 && swapFreeKb >= 0) swapTotalKb - swapFreeKb else -1L

    companion object {
        fun parse(text: CharSequence, out: MemInfo): MemInfo {
            out.totalKb = -1; out.availableKb = -1; out.swapTotalKb = -1; out.swapFreeKb = -1
            for (line in text.lineSequence()) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val target = when (line.substring(0, colon)) {
                    "MemTotal" -> 0
                    "MemAvailable" -> 1
                    "SwapTotal" -> 2
                    "SwapFree" -> 3
                    else -> continue
                }
                val v = kbValue(line, colon + 1)
                when (target) {
                    0 -> out.totalKb = v
                    1 -> out.availableKb = v
                    2 -> out.swapTotalKb = v
                    3 -> out.swapFreeKb = v
                }
            }
            return out
        }

        /** Resident memory of a process from /proc/<pid>/status (VmRSS, kB), or -1. */
        fun processRssKb(status: CharSequence): Long {
            for (line in status.lineSequence()) {
                if (line.startsWith("VmRSS:")) return kbValue(line, 6)
            }
            return -1L
        }

        private fun kbValue(line: String, from: Int): Long {
            var i = from
            while (i < line.length && !line[i].isDigit()) i++
            var v = 0L
            var any = false
            while (i < line.length && line[i].isDigit()) {
                v = v * 10 + (line[i] - '0'); i++; any = true
            }
            return if (any) v else -1L
        }
    }
}
