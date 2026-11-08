package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.NetworkSnapshot
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val NODE_NET_DEV = "/proc/net/dev"

/** Interfaces to ignore (loopback, dummy) */
private val IGNORED_INTERFACES = setOf("lo", "dummy0")

private data class IfaceBytes(val rx: Long, val tx: Long)

@Singleton
class NetworkCollector @Inject constructor(
    private val root: RootShellManager,
    private val shizuku: ShizukuShellManager,
) {
    private var prevBytes: Map<String, IfaceBytes> = emptyMap()
    private var prevTimeMs: Long = 0L

    suspend fun collect(): NetworkSnapshot = withContext(Dispatchers.IO) {
        val lines = root.readLines(NODE_NET_DEV)
            ?: shizuku.exec("cat $NODE_NET_DEV")
            ?: return@withContext NetworkSnapshot(
                downloadBps = MetricValue.Unavailable,
                uploadBps   = MetricValue.Unavailable,
            )

        val nowMs = System.currentTimeMillis()
        val current = parseNetDev(lines)

        val elapsedMs = nowMs - prevTimeMs
        val result = if (prevBytes.isNotEmpty() && elapsedMs > 0) {
            var totalRxBytes = 0L
            var totalTxBytes = 0L
            for ((iface, cur) in current) {
                val prev = prevBytes[iface] ?: continue
                totalRxBytes += (cur.rx - prev.rx).coerceAtLeast(0L)
                totalTxBytes += (cur.tx - prev.tx).coerceAtLeast(0L)
            }
            val elapsedSec = elapsedMs / 1000.0
            NetworkSnapshot(
                downloadBps = (totalRxBytes / elapsedSec).toLong().toMetric(),
                uploadBps   = (totalTxBytes / elapsedSec).toLong().toMetric(),
            )
        } else {
            NetworkSnapshot(
                downloadBps = MetricValue.Unavailable,
                uploadBps   = MetricValue.Unavailable,
            )
        }

        prevBytes  = current
        prevTimeMs = nowMs
        result
    }

    /**
     * /proc/net/dev format (after two header lines):
     * iface: rxBytes rxPkts rxErr rxDrop rxFifo rxFrame rxCompressed rxMulticast
     *        txBytes txPkts txErr txDrop txFifo txColls txCarrier txCompressed
     */
    private fun parseNetDev(lines: List<String>): Map<String, IfaceBytes> {
        val map = mutableMapOf<String, IfaceBytes>()
        for (line in lines.drop(2)) {
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val iface  = line.substring(0, colonIdx).trim()
            if (iface in IGNORED_INTERFACES) continue
            val fields = line.substring(colonIdx + 1).trim().split("\\s+".toRegex())
            if (fields.size < 9) continue
            val rx = fields[0].toLongOrNull() ?: continue
            val tx = fields[8].toLongOrNull() ?: continue
            map[iface] = IfaceBytes(rx = rx, tx = tx)
        }
        return map
    }
}
