package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.GpuSnapshot
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val NODE_UTIL   = "/proc/mali/utilization"
private const val NODE_FREQ   = "/proc/mali/frequency"
private const val NODE_MEM    = "/proc/mali/memory_usage"

/**
 * Mali-G52 MC2 OPP table (index → MHz) as verified from /proc/gpufreq/gpufreq_opp_dump.
 * Index 0 is the highest frequency.
 */
private val GPU_OPP_TABLE: Map<Int, Int> = mapOf(
     0 to 1000,  1 to 975,   2 to 950,   3 to 925,   4 to 900,
     5 to 875,   6 to 850,   7 to 823,   8 to 796,   9 to 769,
    10 to 743,  11 to 716,  12 to 690,  13 to 663,  14 to 637,
    15 to 611,  16 to 586,  17 to 560,  18 to 535,  19 to 509,
    20 to 484,  21 to 467,  22 to 450,  23 to 434,  24 to 417,
    25 to 400,  26 to 383,  27 to 366,  28 to 349,  29 to 332,
    30 to 315,  31 to 299,
)

@Singleton
class GpuCollector @Inject constructor(
    private val root: RootShellManager,
) {

    suspend fun collect(): GpuSnapshot = withContext(Dispatchers.IO) {
        GpuSnapshot(
            utilizationPercent = readUtilization(),
            frequencyMhz       = readFrequency(),
            memoryBytes        = readMemory(),
        )
    }

    // ── utilization ───────────────────────────────────────────────────────────
    /**
     * /proc/mali/utilization line format:
     *   gpu/cljs0/cljs1=13/0/0
     *
     * The "gpu" field is the overall GPU utilization percentage.
     * cljs0/cljs1 are compute job slots (usually 0 during graphics workloads).
     */
    private suspend fun readUtilization(): MetricValue<Int> {
        val raw = root.readNode(NODE_UTIL) ?: return MetricValue.Unavailable
        // Format: "gpu/cljs0/cljs1=<gpuPct>/<cljs0Pct>/<cljs1Pct>"
        return try {
            val eqIdx   = raw.indexOf('=')
            if (eqIdx < 0) return MetricValue.Unavailable
            val values  = raw.substring(eqIdx + 1).split('/')
            val gpuPct  = values[0].trim().toInt()
            gpuPct.coerceIn(0, 100).toMetric()
        } catch (e: Exception) {
            MetricValue.Unavailable
        }
    }

    // ── frequency ─────────────────────────────────────────────────────────────
    /**
     * /proc/mali/frequency line format:
     *   GPU Frequency Index: 31
     *
     * Map index → MHz using OPP table.
     */
    private suspend fun readFrequency(): MetricValue<Int> {
        val raw = root.readNode(NODE_FREQ) ?: return MetricValue.Unavailable
        return try {
            // "GPU Frequency Index: 31"
            val idx = raw.substringAfterLast(":").trim().toInt()
            GPU_OPP_TABLE[idx].toMetric()
        } catch (e: Exception) {
            MetricValue.Unavailable
        }
    }

    // ── memory ────────────────────────────────────────────────────────────────
    /**
     * /proc/mali/memory_usage line format:
     *   Total 53514 (219193344 bytes)
     *
     * Extract the byte value in parentheses.
     */
    private suspend fun readMemory(): MetricValue<Long> {
        val raw = root.readNode(NODE_MEM) ?: return MetricValue.Unavailable
        return try {
            // Find "(NNN bytes)"
            val start = raw.indexOf('(')
            val end   = raw.indexOf(' ', start + 1)
            if (start < 0 || end < 0) return MetricValue.Unavailable
            raw.substring(start + 1, end).trim().toLong().toMetric()
        } catch (e: Exception) {
            MetricValue.Unavailable
        }
    }
}
