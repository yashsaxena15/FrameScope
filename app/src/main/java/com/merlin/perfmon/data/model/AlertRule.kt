package com.merlin.perfmon.data.model

import java.util.UUID

/**
 * A user-defined threshold alert.
 *
 * When [metric]'s numeric value crosses [threshold] in the direction indicated
 * by [condition], the alert fires a system notification.
 */
data class AlertRule(
    val id:        String  = UUID.randomUUID().toString(),
    val metric:    MetricType,
    val condition: AlertCondition,
    val threshold: Float,
    val enabled:   Boolean = true,
    /** Human-readable label, auto-generated if blank */
    val label:     String  = "",
) {
    val displayLabel: String
        get() = label.ifBlank {
            "${metric.name} ${condition.symbol} ${"%.1f".format(threshold)}${metric.unit()}"
        }
}

enum class AlertCondition(val symbol: String) {
    ABOVE(">"),
    BELOW("<"),
}

/** Returns a short unit string for the metric, used in alert display labels. */
private fun MetricType.unit(): String = when (this) {
    MetricType.FPS             -> " fps"
    MetricType.CPU_USAGE,
    MetricType.GPU_USAGE,
    MetricType.BATTERY_LEVEL   -> "%"
    MetricType.CPU_TEMP,
    MetricType.BATTERY_TEMP    -> "°C"
    MetricType.CPU_FREQ,
    MetricType.GPU_FREQ        -> " MHz"
    MetricType.GPU_MEM,
    MetricType.RAM_USED,
    MetricType.STORAGE_USED    -> " MB"
    MetricType.BATTERY_CURRENT -> " mA"
    MetricType.BATTERY_POWER   -> " mW"
    MetricType.NETWORK_DOWN,
    MetricType.NETWORK_UP      -> " KB/s"
    MetricType.THERMAL_ZONES   -> "°C"
}
