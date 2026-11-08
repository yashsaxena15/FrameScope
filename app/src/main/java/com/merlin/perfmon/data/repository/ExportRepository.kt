package com.merlin.perfmon.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.merlin.perfmon.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val perfRepository: PerformanceRepository,
) {
    private val exportDir: File
        get() = File(context.filesDir, "exports").also { it.mkdirs() }

    private val stampFmt   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val displayFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ── Snapshot buffer ───────────────────────────────────────────────────────

    private val buffer     = mutableListOf<PerformanceSnapshot>()
    private val bufferLock = Any()
    private var isRecording = false

    fun startRecording() {
        synchronized(bufferLock) {
            buffer.clear()
            isRecording = true
        }
    }

    fun stopRecording() {
        synchronized(bufferLock) { isRecording = false }
    }

    fun recordSnapshot(snap: PerformanceSnapshot) {
        if (isRecording) synchronized(bufferLock) { buffer.add(snap) }
    }

    val recordedCount: Int
        get() = synchronized(bufferLock) { buffer.size }

    // ── CSV export ────────────────────────────────────────────────────────────

    suspend fun exportCsv(): Uri = withContext(Dispatchers.IO) {
        val snapshots = synchronized(bufferLock) { buffer.toList() }
        val file      = File(exportDir, "perfmon_${stampFmt.format(Date())}.csv")

        file.bufferedWriter().use { w ->
            w.write(CSV_HEADER)
            w.newLine()
            snapshots.forEach { s ->
                w.write(s.toCsvRow())
                w.newLine()
            }
        }
        fileUri(file)
    }

    // ── JSON export ───────────────────────────────────────────────────────────

    suspend fun exportJson(): Uri = withContext(Dispatchers.IO) {
        val snapshots = synchronized(bufferLock) { buffer.toList() }
        val file      = File(exportDir, "perfmon_${stampFmt.format(Date())}.json")

        file.bufferedWriter().use { w ->
            w.write("[\n")
            snapshots.forEachIndexed { idx, s ->
                w.write(s.toJson(displayFmt))
                if (idx < snapshots.lastIndex) w.write(",")
                w.newLine()
            }
            w.write("]\n")
        }
        fileUri(file)
    }

    // ── Share intent ──────────────────────────────────────────────────────────

    fun shareIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ── List / delete ─────────────────────────────────────────────────────────

    fun listExports(): List<ExportFile> =
        exportDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.map { ExportFile(it.name, it.length(), it.lastModified(), fileUri(it)) }
            ?: emptyList()

    fun deleteExport(uri: Uri) {
        // Reconstruct the File path from the URI authority path segment.
        // FileProvider URIs have the form: content://<authority>/<pathName>/<filename>
        // We list the exports dir and find the matching file by name.
        val fileName = uri.lastPathSegment ?: return
        File(exportDir, fileName).delete()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fileUri(file: File): Uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

    companion object {
        private val CSV_HEADER = buildString {
            append("timestamp_ms,datetime,")
            append("fps_current,fps_average,frame_time_ms,")
            append("cpu_total_pct,")
            append("cpu0_pct,cpu1_pct,cpu2_pct,cpu3_pct,cpu4_pct,cpu5_pct,cpu6_pct,cpu7_pct,")
            append("cpu_little_mhz,cpu_big_mhz,cpu_temp_c,")
            append("gpu_util_pct,gpu_freq_mhz,gpu_mem_bytes,")
            append("ram_total_bytes,ram_available_bytes,ram_used_bytes,")
            append("bat_capacity_pct,bat_temp_c,bat_voltage_mv,bat_current_ma,bat_power_mw,")
            append("net_down_bps,net_up_bps")
        }
    }
}

// ── Private serialisation extensions ─────────────────────────────────────────

private fun <T> MetricValue<T>.csv(): String =
    if (this is MetricValue.Available) value.toString() else ""

private fun PerformanceSnapshot.toCsvRow(): String {
    val dt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
    // Build the row as a list to avoid spread-operator issues with varargs.
    val cols = buildList<Any> {
        add(timestampMs); add(dt)
        add(fps.currentFps.csv()); add(fps.averageFps.csv()); add(fps.frameTimeMs.csv())
        add(cpu.totalUsagePercent.csv())
        cpu.perCoreUsagePercent.forEach { add(it.csv()) }
        add(cpu.littleClusterFreqMhz.csv()); add(cpu.bigClusterFreqMhz.csv())
        add(cpu.temperatureCelsius.csv())
        add(gpu.utilizationPercent.csv()); add(gpu.frequencyMhz.csv()); add(gpu.memoryBytes.csv())
        add(ram.totalBytes.csv()); add(ram.availableBytes.csv())
        add(if (ram.usedBytes is MetricValue.Available)
            (ram.usedBytes as MetricValue.Available).value else "")
        add(battery.capacityPercent.csv()); add(battery.temperatureCelsius.csv())
        add(battery.voltageMv.csv()); add(battery.currentMa.csv()); add(battery.powerMw.csv())
        add(network.downloadBps.csv()); add(network.uploadBps.csv())
    }
    return cols.joinToString(",")
}

private fun PerformanceSnapshot.toJson(fmt: SimpleDateFormat): String {
    fun <T> MetricValue<T>.j(): String =
        if (this is MetricValue.Available) value.toString() else "null"

    return buildString {
        appendLine("{")
        appendLine("""  "timestamp_ms": $timestampMs,""")
        appendLine("""  "datetime": "${fmt.format(Date(timestampMs))}",""")
        appendLine("""  "fps": { "current": ${fps.currentFps.j()}, "average": ${fps.averageFps.j()}, "frame_time_ms": ${fps.frameTimeMs.j()} },""")
        appendLine("""  "cpu": {""")
        appendLine("""    "total_pct": ${cpu.totalUsagePercent.j()},""")
        appendLine("""    "little_mhz": ${cpu.littleClusterFreqMhz.j()},""")
        appendLine("""    "big_mhz": ${cpu.bigClusterFreqMhz.j()},""")
        appendLine("""    "temp_c": ${cpu.temperatureCelsius.j()},""")
        appendLine("""    "cores": [${cpu.perCoreUsagePercent.joinToString(",") { it.j() }}]""")
        appendLine("  },")
        appendLine("""  "gpu": { "util_pct": ${gpu.utilizationPercent.j()}, "freq_mhz": ${gpu.frequencyMhz.j()}, "mem_bytes": ${gpu.memoryBytes.j()} },""")
        appendLine("""  "ram": { "total": ${ram.totalBytes.j()}, "available": ${ram.availableBytes.j()} },""")
        appendLine("""  "battery": {""")
        appendLine("""    "capacity_pct": ${battery.capacityPercent.j()},""")
        appendLine("""    "temp_c": ${battery.temperatureCelsius.j()},""")
        appendLine("""    "voltage_mv": ${battery.voltageMv.j()},""")
        appendLine("""    "current_ma": ${battery.currentMa.j()},""")
        appendLine("""    "power_mw": ${battery.powerMw.j()}""")
        appendLine("  },")
        appendLine("""  "network": { "down_bps": ${network.downloadBps.j()}, "up_bps": ${network.uploadBps.j()} }""")
        append("}")
    }
}

data class ExportFile(
    val name:         String,
    val sizeBytes:    Long,
    val lastModified: Long,
    val uri:          Uri,
)
