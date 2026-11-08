package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.CpuSnapshot
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Helio G85 verified kernel nodes
private const val NODE_LITTLE_FREQ = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
private const val NODE_BIG_FREQ    = "/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq"
private const val NODE_PROC_STAT   = "/proc/stat"
private const val NODE_CPU_TEMP    = "/sys/class/thermal/thermal_zone3/temp"  // mtktscpu

/**
 * Parses a single /proc/stat cpu line: cpu<N> user nice system idle iowait irq softirq ...
 */
private data class StatLine(
    val idle: Long,
    val total: Long,
)

private fun parseStatLine(line: String): StatLine? {
    val parts = line.trim().split("\\s+".toRegex())
    if (parts.size < 5) return null
    return try {
        val user    = parts[1].toLong()
        val nice    = parts[2].toLong()
        val system  = parts[3].toLong()
        val idle    = parts[4].toLong()
        val iowait  = if (parts.size > 5) parts[5].toLong() else 0L
        val irq     = if (parts.size > 6) parts[6].toLong() else 0L
        val softirq = if (parts.size > 7) parts[7].toLong() else 0L
        val total   = user + nice + system + idle + iowait + irq + softirq
        StatLine(idle = idle + iowait, total = total)
    } catch (e: NumberFormatException) {
        null
    }
}

@Singleton
class CpuCollector @Inject constructor(
    private val root: RootShellManager,
    private val shizuku: ShizukuShellManager,
) {
    // Previous /proc/stat snapshots for delta calculation
    // Index 0 = total ("cpu"), 1..8 = cpu0..cpu7
    private var prevStats: Array<StatLine?> = arrayOfNulls(9)

    suspend fun collect(): CpuSnapshot = withContext(Dispatchers.IO) {
        val procStatLines = readProcStat()
        val (totalUsage, perCoreUsage) = computeUsage(procStatLines)

        val littleFreqKhz = root.readInt(NODE_LITTLE_FREQ)
        val bigFreqKhz    = root.readInt(NODE_BIG_FREQ)

        // Temperature: raw value is millidegrees → divide by 1000
        val tempRaw  = root.readInt(NODE_CPU_TEMP)
        val tempC: MetricValue<Float> = if (tempRaw != null && tempRaw > -100_000) {
            (tempRaw / 1000f).toMetric()
        } else {
            MetricValue.Unavailable
        }

        CpuSnapshot(
            totalUsagePercent     = totalUsage,
            perCoreUsagePercent   = perCoreUsage,
            littleClusterFreqMhz  = littleFreqKhz?.div(1000).toMetric(),
            bigClusterFreqMhz     = bigFreqKhz?.div(1000).toMetric(),
            temperatureCelsius    = tempC,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private suspend fun readProcStat(): List<String>? {
        return root.readLines(NODE_PROC_STAT)
            ?: shizuku.exec("cat $NODE_PROC_STAT")
    }

    /**
     * Compute usage via two consecutive reads — but since we're called on a
     * timer we use the stored previous snapshot as the "before" value.
     */
    private fun computeUsage(
        lines: List<String>?,
    ): Pair<MetricValue<Float>, List<MetricValue<Float>>> {
        if (lines == null) {
            // Preserve previous nulls
            prevStats = arrayOfNulls(9)
            return MetricValue.Unavailable to List(8) { MetricValue.Unavailable }
        }

        // Parse lines: first line is "cpu", then cpu0..cpu7
        val parsed = Array<StatLine?>(9) { null }
        for (line in lines) {
            when {
                line.startsWith("cpu ") || line.startsWith("cpu\t") ->
                    parsed[0] = parseStatLine(line)
                line.startsWith("cpu0") -> parsed[1] = parseStatLine(line)
                line.startsWith("cpu1") -> parsed[2] = parseStatLine(line)
                line.startsWith("cpu2") -> parsed[3] = parseStatLine(line)
                line.startsWith("cpu3") -> parsed[4] = parseStatLine(line)
                line.startsWith("cpu4") -> parsed[5] = parseStatLine(line)
                line.startsWith("cpu5") -> parsed[6] = parseStatLine(line)
                line.startsWith("cpu6") -> parsed[7] = parseStatLine(line)
                line.startsWith("cpu7") -> parsed[8] = parseStatLine(line)
            }
        }

        fun usage(prev: StatLine?, cur: StatLine?): MetricValue<Float> {
            if (prev == null || cur == null) return MetricValue.Unavailable
            val deltaTotal = cur.total - prev.total
            val deltaIdle  = cur.idle  - prev.idle
            if (deltaTotal <= 0) return MetricValue.Unavailable
            val pct = (1f - deltaIdle.toFloat() / deltaTotal.toFloat()) * 100f
            return pct.coerceIn(0f, 100f).toMetric()
        }

        val totalUsage = usage(prevStats[0], parsed[0])
        val perCore    = (1..8).map { i -> usage(prevStats[i], parsed[i]) }

        prevStats = parsed
        return totalUsage to perCore
    }
}
