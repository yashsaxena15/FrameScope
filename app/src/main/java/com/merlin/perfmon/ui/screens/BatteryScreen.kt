package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.ui.MainViewModel
import com.merlin.perfmon.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val battery by viewModel.battery.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start  = 12.dp,
                end    = 12.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            // ── State ──────────────────────────────────────────────────────
            item {
                MetricCard(title = "State") {
                    val capPct = (battery.capacityPercent as? MetricValue.Available)
                        ?.value?.toFloat() ?: 0f

                    MetricRow("Capacity",       battery.capacityPercent.displayOr { "$it%" })
                    MetricRow("Status",         battery.status.displayOr { it })
                    MetricRow("Health",         battery.health.displayOr { it })
                    MetricRow("Technology",     battery.technology.displayOr { it })
                    MetricRow("Cycle Count",    battery.cycleCount.displayOr { "$it cycles" })

                    Spacer(Modifier.height(6.dp))
                    GaugeBar(
                        value = capPct,
                        color = when {
                            capPct <= 15f -> androidx.compose.ui.graphics.Color(0xFFEF5350)
                            capPct <= 30f -> androidx.compose.ui.graphics.Color(0xFFFFCA28)
                            else          -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
                        },
                    )
                }
            }

            // ── Electrical ────────────────────────────────────────────────
            item {
                MetricCard(title = "Electrical") {
                    MetricRow("Voltage",       battery.voltageMv.displayOr { "%.0f mV".format(it) })
                    MetricRow("Current (now)", battery.currentMa.displayOr { "%.0f mA".format(it) })
                    MetricRow("Current (avg)", battery.currentAvgMa.displayOr { "%.0f mA".format(it) })
                    MetricRow("Power",         battery.powerMw.displayOr { "%.0f mW".format(it) })
                }
            }

            // ── Thermal ───────────────────────────────────────────────────
            item {
                MetricCard(title = "Thermal") {
                    val batTemp = battery.temperatureCelsius
                    MetricRow(
                        label      = "Temperature",
                        value      = batTemp.displayOr { "%.1f °C".format(it) },
                        valueColor = if (batTemp is MetricValue.Available)
                            tempColor(batTemp.value)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ── Charge counters ───────────────────────────────────────────
            item {
                MetricCard(title = "Charge Counters") {
                    MetricRow("Charge Counter",   battery.chargeCounterUah.displayOr { "$it µAh" })
                    MetricRow("Full (learned)",   battery.chargeFullUah.displayOr { "$it µAh" })
                    MetricRow("Full (design)",    battery.chargeFullDesignUah.displayOr { "$it µAh" })

                    // Capacity retention
                    val full   = battery.chargeFullUah
                    val design = battery.chargeFullDesignUah
                    if (full is MetricValue.Available && design is MetricValue.Available
                        && design.value > 0) {
                        val retention = full.value.toFloat() / design.value.toFloat() * 100f
                        MetricRow(
                            label      = "Capacity Retention",
                            value      = "%.1f%%".format(retention),
                            valueColor = percentColor(retention),
                        )
                        Spacer(Modifier.height(4.dp))
                        GaugeBar(value = retention, color = percentColor(retention))
                    }
                }
            }
        }
    }
}
