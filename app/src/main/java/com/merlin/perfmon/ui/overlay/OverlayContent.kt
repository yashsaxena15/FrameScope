package com.merlin.perfmon.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.*
import com.merlin.perfmon.data.repository.PerformanceRepository
import com.merlin.perfmon.data.repository.SettingsRepository
import com.merlin.perfmon.util.formatBytes
import com.merlin.perfmon.util.formatBytesPerSec

/**
 * Root composable for the floating overlay HUD.
 *
 * @param onMove called with (Δx, Δy) pixel deltas on each drag event.
 *               The caller (OverlayManager) translates these into absolute
 *               WindowManager coordinates.
 */
@Composable
fun OverlayContent(
    perfRepo:     PerformanceRepository,
    settingsRepo: SettingsRepository,
    onMove:       (dx: Int, dy: Int) -> Unit,
) {
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    val cpu     by perfRepo.cpuFlow.collectAsStateWithLifecycle()
    val gpu     by perfRepo.gpuFlow.collectAsStateWithLifecycle()
    val ram     by perfRepo.ramFlow.collectAsStateWithLifecycle()
    val battery by perfRepo.batteryFlow.collectAsStateWithLifecycle()
    val network by perfRepo.networkFlow.collectAsStateWithLifecycle()
    val fps     by perfRepo.fpsFlow.collectAsStateWithLifecycle()
    val thermal by perfRepo.thermalFlow.collectAsStateWithLifecycle()
    val storage by perfRepo.storageFlow.collectAsStateWithLifecycle()

    val textSizeSp = settings.overlayTextSizeSp
    val opacity    = settings.overlayOpacity
    val mode       = settings.overlayMode

    Box(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { _, dragAmount ->
                    onMove(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            }
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 90.dp, max = 220.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = opacity))
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            for (metric in settings.visibleMetrics) {
                when (mode) {
                    OverlayMode.COMPACT  ->
                        CompactRow(metric, cpu, gpu, ram, battery, network, fps, thermal, storage, textSizeSp)
                    OverlayMode.STANDARD ->
                        StandardRow(metric, cpu, gpu, ram, battery, network, fps, thermal, storage, textSizeSp)
                    OverlayMode.ADVANCED ->
                        AdvancedRow(metric, cpu, gpu, ram, battery, network, fps, thermal, storage, textSizeSp)
                }
            }
        }
    }
}

// ── Compact ───────────────────────────────────────────────────────────────────

@Composable
private fun CompactRow(
    metric:  MetricType,
    cpu: CpuSnapshot, gpu: GpuSnapshot, ram: RamSnapshot,
    battery: BatterySnapshot, network: NetworkSnapshot,
    fps: FpsSnapshot, thermal: ThermalSnapshot, storage: StorageSnapshot,
    textSp: Float,
) {
    val (label, value) = labelValue(metric, cpu, gpu, ram, battery, network, fps, thermal, storage)
    HudLine(label, value, textSp)
}

@Composable
private fun StandardRow(
    metric:  MetricType,
    cpu: CpuSnapshot, gpu: GpuSnapshot, ram: RamSnapshot,
    battery: BatterySnapshot, network: NetworkSnapshot,
    fps: FpsSnapshot, thermal: ThermalSnapshot, storage: StorageSnapshot,
    textSp: Float,
) {
    val (label, value) = labelValue(metric, cpu, gpu, ram, battery, network, fps, thermal, storage)
    val color = valueColor(metric, value)
    HudLine(label, value, textSp, valueColor = color)
}

@Composable
private fun AdvancedRow(
    metric:  MetricType,
    cpu: CpuSnapshot, gpu: GpuSnapshot, ram: RamSnapshot,
    battery: BatterySnapshot, network: NetworkSnapshot,
    fps: FpsSnapshot, thermal: ThermalSnapshot, storage: StorageSnapshot,
    textSp: Float,
) {
    when (metric) {
        MetricType.CPU_USAGE -> {
            val total = cpu.totalUsagePercent.fmt { "%.0f%%".format(it) }
            HudLine("CPU", total, textSp, valueColor = valueColor(metric, total))
            // Per-core rows at one sp smaller
            cpu.perCoreUsagePercent.forEachIndexed { i, v ->
                HudLine(
                    " c$i", v.fmt { "%.0f%%".format(it) },
                    (textSp - 1f).coerceAtLeast(7f),
                    labelColor = Color(0xFF777777),
                )
            }
        }
        MetricType.THERMAL_ZONES -> {
            // Show only valid sensors in advanced mode
            thermal.zones
                .filter { it.temperatureCelsius is MetricValue.Available }
                .forEach { zone ->
                    val t = (zone.temperatureCelsius as MetricValue.Available).value
                    HudLine(
                        zone.type.take(12), "%.0f°C".format(t),
                        (textSp - 1f).coerceAtLeast(7f),
                        valueColor = tempColor(t),
                    )
                }
        }
        else -> StandardRow(metric, cpu, gpu, ram, battery, network, fps, thermal, storage, textSp)
    }
}

// ── Label / value mapping ─────────────────────────────────────────────────────

private fun labelValue(
    metric: MetricType,
    cpu: CpuSnapshot, gpu: GpuSnapshot, ram: RamSnapshot,
    battery: BatterySnapshot, network: NetworkSnapshot,
    fps: FpsSnapshot, thermal: ThermalSnapshot, storage: StorageSnapshot,
): Pair<String, String> = when (metric) {
    MetricType.FPS             -> "FPS"  to fps.currentFps.fmt { "%.0f".format(it) }
    MetricType.CPU_USAGE       -> "CPU"  to cpu.totalUsagePercent.fmt { "%.0f%%".format(it) }
    MetricType.CPU_FREQ        -> "CLK"  to buildString {
        append(cpu.littleClusterFreqMhz.fmt { "${it}M" })
        append("/")
        append(cpu.bigClusterFreqMhz.fmt { "${it}M" })
    }
    MetricType.CPU_TEMP        -> "CPUT" to cpu.temperatureCelsius.fmt { "%.0f°C".format(it) }
    MetricType.GPU_USAGE       -> "GPU"  to gpu.utilizationPercent.fmt { "$it%" }
    MetricType.GPU_FREQ        -> "GCLK" to gpu.frequencyMhz.fmt { "${it}M" }
    MetricType.GPU_MEM         -> "GMEM" to gpu.memoryBytes.fmt { formatBytes(it) }
    MetricType.RAM_USED        -> "RAM"  to ram.usedBytes.fmt { formatBytes(it) }
    MetricType.BATTERY_LEVEL   -> "BAT"  to battery.capacityPercent.fmt { "$it%" }
    MetricType.BATTERY_TEMP    -> "BATT" to battery.temperatureCelsius.fmt { "%.0f°C".format(it) }
    MetricType.BATTERY_CURRENT -> "CUR"  to battery.currentMa.fmt { "%.0fmA".format(it) }
    MetricType.BATTERY_POWER   -> "PWR"  to battery.powerMw.fmt { "%.0fmW".format(it) }
    MetricType.NETWORK_DOWN    -> "↓"    to network.downloadBps.fmt { formatBytesPerSec(it) }
    MetricType.NETWORK_UP      -> "↑"    to network.uploadBps.fmt { formatBytesPerSec(it) }
    MetricType.THERMAL_ZONES   -> "TEMP" to run {
        // Compact: show cpu thermal zone (zone 3)
        thermal.zones.firstOrNull { it.index == 3 }
            ?.temperatureCelsius?.fmt { "%.0f°C".format(it) } ?: "N/A"
    }
    MetricType.STORAGE_USED    -> "STO"  to storage.usedBytes.fmt { formatBytes(it) }
}

// ── Colour helpers ────────────────────────────────────────────────────────────

private fun valueColor(metric: MetricType, value: String): Color {
    if (value == "N/A" || value.contains("--")) return Color(0xFF888888)
    return when (metric) {
        MetricType.CPU_TEMP, MetricType.BATTERY_TEMP -> {
            val n = value.replace("°C", "").toFloatOrNull() ?: return Color.White
            tempColor(n)
        }
        MetricType.CPU_USAGE, MetricType.GPU_USAGE -> {
            val n = value.replace("%", "").toFloatOrNull() ?: return Color.White
            when {
                n >= 90f -> Color(0xFFFF4444)
                n >= 70f -> Color(0xFFFFAA00)
                else     -> Color.White
            }
        }
        MetricType.FPS -> {
            val n = value.toFloatOrNull() ?: return Color.White
            when {
                n >= 55f -> Color(0xFF44FF88)
                n >= 40f -> Color(0xFFFFAA00)
                else     -> Color(0xFFFF4444)
            }
        }
        MetricType.BATTERY_LEVEL -> {
            val n = value.replace("%", "").toFloatOrNull() ?: return Color.White
            when {
                n <= 15f -> Color(0xFFFF4444)
                n <= 30f -> Color(0xFFFFAA00)
                else     -> Color.White
            }
        }
        else -> Color.White
    }
}

private fun tempColor(celsius: Float): Color = when {
    celsius >= 80f -> Color(0xFFFF4444)
    celsius >= 65f -> Color(0xFFFFAA00)
    else           -> Color(0xFF44FF88)
}

// ── Primitive composable ──────────────────────────────────────────────────────

@Composable
private fun HudLine(
    label:      String,
    value:      String,
    textSp:     Float,
    labelColor: Color = Color(0xFF999999),
    valueColor: Color = Color.White,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = label,
            fontSize   = textSp.sp,
            color      = labelColor,
            fontFamily = FontFamily.Monospace,
            maxLines   = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text       = value,
            fontSize   = textSp.sp,
            color      = valueColor,
            fontFamily = FontFamily.Monospace,
            textAlign  = TextAlign.End,
            maxLines   = 1,
        )
    }
}

// ── MetricValue extension ─────────────────────────────────────────────────────

private fun <T> MetricValue<T>.fmt(transform: (T) -> String): String =
    when (this) {
        is MetricValue.Available -> transform(value)
        MetricValue.Unavailable  -> "N/A"
    }
