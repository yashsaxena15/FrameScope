package com.merlin.perfmon.data.model

/**
 * All user-configurable settings persisted via DataStore.
 */
data class AppSettings(
    val overlayEnabled: Boolean = false,
    val overlayX: Float = 0f,
    val overlayY: Float = 100f,
    val overlayOpacity: Float = 0.85f,
    val overlayTextSizeSp: Float = 11f,
    val overlayMode: OverlayMode = OverlayMode.COMPACT,
    /** Which metrics to show in the overlay, and in what order */
    val visibleMetrics: List<MetricType> = MetricType.defaultOrder(),
    val autoStartOnBoot: Boolean = false,
    val useShizukuFallback: Boolean = true,
    /** Poll intervals in milliseconds per category */
    val fpsIntervalMs: Long = 250L,
    val cpuIntervalMs: Long = 500L,
    val gpuIntervalMs: Long = 500L,
    val ramIntervalMs: Long = 1000L,
    val networkIntervalMs: Long = 1000L,
    val batteryIntervalMs: Long = 5000L,
    val thermalIntervalMs: Long = 5000L,
    val storageIntervalMs: Long = 30_000L,
)

enum class OverlayMode {
    COMPACT,
    STANDARD,
    ADVANCED,
}

enum class MetricType {
    FPS,
    CPU_USAGE,
    CPU_FREQ,
    CPU_TEMP,
    GPU_USAGE,
    GPU_FREQ,
    GPU_MEM,
    RAM_USED,
    BATTERY_LEVEL,
    BATTERY_TEMP,
    BATTERY_CURRENT,
    BATTERY_POWER,
    NETWORK_DOWN,
    NETWORK_UP,
    THERMAL_ZONES,
    STORAGE_USED,
    ;

    companion object {
        fun defaultOrder(): List<MetricType> = listOf(
            FPS,
            CPU_USAGE,
            CPU_FREQ,
            CPU_TEMP,
            GPU_USAGE,
            GPU_FREQ,
            RAM_USED,
            BATTERY_LEVEL,
            BATTERY_TEMP,
            BATTERY_CURRENT,
            NETWORK_DOWN,
            NETWORK_UP,
        )
    }
}
