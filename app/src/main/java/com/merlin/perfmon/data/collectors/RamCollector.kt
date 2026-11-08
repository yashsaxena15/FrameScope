package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.RamSnapshot
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val NODE_MEMINFO = "/proc/meminfo"

@Singleton
class RamCollector @Inject constructor(
    private val root: RootShellManager,
    private val shizuku: ShizukuShellManager,
) {

    suspend fun collect(): RamSnapshot = withContext(Dispatchers.IO) {
        val lines = root.readLines(NODE_MEMINFO)
            ?: shizuku.exec("cat $NODE_MEMINFO")

        if (lines == null) return@withContext emptySnapshot()

        val map = mutableMapOf<String, Long>()
        for (line in lines) {
            // Format: "MemTotal:       3820388 kB"
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val key     = line.substring(0, colonIdx).trim()
            val rest    = line.substring(colonIdx + 1).trim()
            val kbValue = rest.removeSuffix(" kB").trim().toLongOrNull() ?: continue
            map[key] = kbValue * 1024L  // convert kB → bytes
        }

        RamSnapshot(
            totalBytes     = map["MemTotal"].toMetric(),
            availableBytes = map["MemAvailable"].toMetric(),
            freeBytes      = map["MemFree"].toMetric(),
            cachedBytes    = map["Cached"].toMetric(),
            buffersBytes   = map["Buffers"].toMetric(),
            swapTotalBytes = map["SwapTotal"].toMetric(),
            swapFreeBytes  = map["SwapFree"].toMetric(),
        )
    }

    private fun emptySnapshot() = RamSnapshot(
        totalBytes     = MetricValue.Unavailable,
        availableBytes = MetricValue.Unavailable,
        freeBytes      = MetricValue.Unavailable,
        cachedBytes    = MetricValue.Unavailable,
        buffersBytes   = MetricValue.Unavailable,
        swapTotalBytes = MetricValue.Unavailable,
        swapFreeBytes  = MetricValue.Unavailable,
    )
}
