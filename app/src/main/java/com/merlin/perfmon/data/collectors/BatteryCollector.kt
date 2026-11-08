package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.BatterySnapshot
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val BAT = "/sys/class/power_supply/battery"

@Singleton
class BatteryCollector @Inject constructor(
    private val root: RootShellManager,
) {

    suspend fun collect(): BatterySnapshot = withContext(Dispatchers.IO) {
        // Read all nodes in parallel to reduce wall-clock time
        val capacity        = async { root.readInt("$BAT/capacity") }
        val tempRaw         = async { root.readInt("$BAT/temp") }
        val voltageRaw      = async { root.readInt("$BAT/voltage_now") }
        val currentNowRaw   = async { root.readInt("$BAT/current_now") }
        val currentAvgRaw   = async { root.readInt("$BAT/current_avg") }
        val chargeCounter   = async { root.readInt("$BAT/charge_counter") }
        val chargeFull      = async { root.readInt("$BAT/charge_full") }
        val chargeFullDesign= async { root.readInt("$BAT/charge_full_design") }
        val health          = async { root.readNode("$BAT/health") }
        val status          = async { root.readNode("$BAT/status") }
        val cycleCount      = async { root.readInt("$BAT/cycle_count") }
        val technology      = async { root.readNode("$BAT/technology") }

        // Temperature: raw is tenths of °C (e.g. 298 = 29.8°C)
        val tempC: MetricValue<Float> = tempRaw.await()?.let { raw ->
            if (raw <= -1000) null else raw / 10f
        }.toMetric()

        // Voltage: raw µV → mV
        val voltageMv: MetricValue<Float> = voltageRaw.await()?.let { raw ->
            if (raw <= 0) null else raw / 1000f
        }.toMetric()

        // Current: raw µA → mA  (negative = discharging on this device)
        val currentMa: MetricValue<Float> = currentNowRaw.await()?.let { raw ->
            raw / 1000f
        }.toMetric()

        val currentAvgMa: MetricValue<Float> = currentAvgRaw.await()?.let { raw ->
            raw / 1000f
        }.toMetric()

        // Power = V × I  (both in consistent units)
        val powerMw: MetricValue<Float> = when {
            voltageMv is MetricValue.Available && currentMa is MetricValue.Available ->
                // |V_mV / 1000| × |I_mA| = mW
                (voltageMv.value / 1000f * kotlin.math.abs(currentMa.value)).toMetric()
            else -> MetricValue.Unavailable
        }

        BatterySnapshot(
            capacityPercent      = capacity.await().toMetric(),
            temperatureCelsius   = tempC,
            voltageMv            = voltageMv,
            currentMa            = currentMa,
            currentAvgMa         = currentAvgMa,
            powerMw              = powerMw,
            chargeCounterUah     = chargeCounter.await().toMetric(),
            chargeFullUah        = chargeFull.await().toMetric(),
            chargeFullDesignUah  = chargeFullDesign.await().toMetric(),
            health               = health.await().toMetric(),
            status               = status.await().toMetric(),
            cycleCount           = cycleCount.await().toMetric(),
            technology           = technology.await().toMetric(),
        )
    }
}
