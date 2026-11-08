package com.merlin.perfmon.data.model

/** Wraps a hardware reading; null value means the node was unreadable. */
sealed class MetricValue<out T> {
    data class Available<T>(val value: T) : MetricValue<T>()
    object Unavailable : MetricValue<Nothing>()
}

fun <T> T?.toMetric(): MetricValue<T> =
    if (this != null) MetricValue.Available(this) else MetricValue.Unavailable

// ─── CPU ──────────────────────────────────────────────────────────────────────

data class CpuSnapshot(
    /** 0..100 % total CPU load */
    val totalUsagePercent: MetricValue<Float>,
    /** Per-core usage 0..100 %, indexed 0..7 */
    val perCoreUsagePercent: List<MetricValue<Float>>,
    /** Little cluster freq in MHz (policy0) */
    val littleClusterFreqMhz: MetricValue<Int>,
    /** Big cluster freq in MHz (policy6) */
    val bigClusterFreqMhz: MetricValue<Int>,
    /** CPU temperature in °C from mtktscpu */
    val temperatureCelsius: MetricValue<Float>,
)

// ─── GPU ──────────────────────────────────────────────────────────────────────

data class GpuSnapshot(
    /** GPU utilisation 0..100 % from /proc/mali/utilization gpu field */
    val utilizationPercent: MetricValue<Int>,
    /** GPU frequency in MHz converted from OPP index */
    val frequencyMhz: MetricValue<Int>,
    /** GPU allocated memory in bytes */
    val memoryBytes: MetricValue<Long>,
)

// ─── RAM ──────────────────────────────────────────────────────────────────────

data class RamSnapshot(
    val totalBytes: MetricValue<Long>,
    val availableBytes: MetricValue<Long>,
    val freeBytes: MetricValue<Long>,
    val cachedBytes: MetricValue<Long>,
    val buffersBytes: MetricValue<Long>,
    val swapTotalBytes: MetricValue<Long>,
    val swapFreeBytes: MetricValue<Long>,
) {
    val usedBytes: MetricValue<Long>
        get() = when {
            totalBytes is MetricValue.Available && availableBytes is MetricValue.Available ->
                MetricValue.Available(totalBytes.value - availableBytes.value)
            else -> MetricValue.Unavailable
        }

    val usedPercent: MetricValue<Float>
        get() = when {
            totalBytes is MetricValue.Available && usedBytes is MetricValue.Available
                    && totalBytes.value > 0L ->
                MetricValue.Available(usedBytes.value.toFloat() / totalBytes.value.toFloat() * 100f)
            else -> MetricValue.Unavailable
        }
}

// ─── Battery ─────────────────────────────────────────────────────────────────

data class BatterySnapshot(
    val capacityPercent: MetricValue<Int>,
    /** °C */
    val temperatureCelsius: MetricValue<Float>,
    /** mV */
    val voltageMv: MetricValue<Float>,
    /** mA  (negative = discharging) */
    val currentMa: MetricValue<Float>,
    /** mA average */
    val currentAvgMa: MetricValue<Float>,
    /** mW  */
    val powerMw: MetricValue<Float>,
    /** µAh */
    val chargeCounterUah: MetricValue<Int>,
    val chargeFullUah: MetricValue<Int>,
    val chargeFullDesignUah: MetricValue<Int>,
    val health: MetricValue<String>,
    val status: MetricValue<String>,
    val cycleCount: MetricValue<Int>,
    val technology: MetricValue<String>,
)

// ─── Thermal ─────────────────────────────────────────────────────────────────

data class ThermalZone(
    val index: Int,
    val type: String,
    val temperatureCelsius: MetricValue<Float>,
)

data class ThermalSnapshot(
    val zones: List<ThermalZone>,
)

// ─── Storage ─────────────────────────────────────────────────────────────────

data class StorageSnapshot(
    val totalBytes: MetricValue<Long>,
    val freeBytes: MetricValue<Long>,
) {
    val usedBytes: MetricValue<Long>
        get() = when {
            totalBytes is MetricValue.Available && freeBytes is MetricValue.Available ->
                MetricValue.Available(totalBytes.value - freeBytes.value)
            else -> MetricValue.Unavailable
        }
    val usedPercent: MetricValue<Float>
        get() = when {
            totalBytes is MetricValue.Available && usedBytes is MetricValue.Available
                    && totalBytes.value > 0L ->
                MetricValue.Available(usedBytes.value.toFloat() / totalBytes.value.toFloat() * 100f)
            else -> MetricValue.Unavailable
        }
}

// ─── Network ─────────────────────────────────────────────────────────────────

data class NetworkSnapshot(
    /** bytes/s */
    val downloadBps: MetricValue<Long>,
    /** bytes/s */
    val uploadBps: MetricValue<Long>,
)

// ─── FPS ─────────────────────────────────────────────────────────────────────

data class FpsSnapshot(
    val currentFps: MetricValue<Float>,
    val averageFps: MetricValue<Float>,
    val frameTimeMs: MetricValue<Float>,
)

// ─── Aggregate ───────────────────────────────────────────────────────────────

data class PerformanceSnapshot(
    val timestampMs: Long,
    val cpu: CpuSnapshot,
    val gpu: GpuSnapshot,
    val ram: RamSnapshot,
    val battery: BatterySnapshot,
    val thermal: ThermalSnapshot,
    val storage: StorageSnapshot,
    val network: NetworkSnapshot,
    val fps: FpsSnapshot,
)
