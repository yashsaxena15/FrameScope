package com.merlin.perfmon.data.collectors

import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.ThermalSnapshot
import com.merlin.perfmon.data.model.ThermalZone
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val THERMAL_BASE = "/sys/class/thermal"

/**
 * Verified thermal zones for Redmi Note 9 (merlin / MT6769Z).
 * Zones returning -127°C (raw -127000) are ignored — they are placeholder
 * entries with no physical sensor attached.
 */
private val THERMAL_ZONE_TYPES = mapOf(
     0 to "mtktsbattery",
     1 to "mtktsAP",
     2 to "mtktsbtsmdpa",
     3 to "mtktscpu",
     4 to "mtktspa",
     5 to "mtktswmt",
     6 to "tzimgs0",
     7 to "tzimgs1",
     8 to "tzimgs2",
     9 to "tzimgs3",
    10 to "tzimgs4",
    11 to "tzimgs5",
    12 to "mtktsdctm",
    13 to "mtktspmic",
    14 to "backlight_therm",
    15 to "mt6358tsbuck1",
    16 to "mt6358tsbuck2",
    17 to "mt6358tsbuck3",
    18 to "battery",
    19 to "bms",
    20 to "mtktscharger",
)

private const val INVALID_TEMP_MILLIDEG = -127_000

@Singleton
class ThermalCollector @Inject constructor(
    private val root: RootShellManager,
) {

    suspend fun collect(): ThermalSnapshot = withContext(Dispatchers.IO) {
        val zoneDeferred = THERMAL_ZONE_TYPES.map { (index, type) ->
            async {
                val raw = root.readInt("$THERMAL_BASE/thermal_zone$index/temp")
                val tempC: MetricValue<Float> = when {
                    raw == null || raw <= INVALID_TEMP_MILLIDEG -> MetricValue.Unavailable
                    else -> (raw / 1000f).toMetric()
                }
                ThermalZone(
                    index              = index,
                    type               = type,
                    temperatureCelsius = tempC,
                )
            }
        }

        ThermalSnapshot(zones = zoneDeferred.awaitAll())
    }
}
