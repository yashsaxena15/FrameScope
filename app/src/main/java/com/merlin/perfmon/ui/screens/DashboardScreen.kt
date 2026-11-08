package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.ui.MainViewModel
import com.merlin.perfmon.ui.components.*
import com.merlin.perfmon.util.formatBytes
import com.merlin.perfmon.util.formatBytesPerSec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateSettings:   () -> Unit,
    onNavigateThermal:    () -> Unit,
    onNavigateBattery:    () -> Unit,
    onNavigateGraphs:     () -> Unit,
    onNavigateBenchmark:  () -> Unit,
    onNavigateExport:     () -> Unit,
    onNavigateAlerts:     () -> Unit,
    onRequestOverlayPerm: () -> Unit,
) {
    val settings       by viewModel.settings.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()

    val cpu     by viewModel.cpu.collectAsStateWithLifecycle()
    val gpu     by viewModel.gpu.collectAsStateWithLifecycle()
    val ram     by viewModel.ram.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val fps     by viewModel.fps.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance Monitor Pro") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    // Overlay toggle button
                    IconButton(onClick = {
                        if (!viewModel.hasOverlayPermission) onRequestOverlayPerm()
                        else viewModel.setOverlayEnabled(!settings.overlayEnabled)
                    }) {
                        Icon(
                            imageVector = if (settings.overlayEnabled)
                                Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle overlay",
                            tint = if (settings.overlayEnabled)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    // Alerts
                    IconButton(onClick = onNavigateAlerts) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = "Alerts")
                    }
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                NavigationBarItem(
                    selected = true,
                    onClick  = { },
                    icon     = { Icon(Icons.Default.Dashboard, null) },
                    label    = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick  = onNavigateGraphs,
                    icon     = { Icon(Icons.Default.ShowChart, null) },
                    label    = { Text("Graphs") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick  = onNavigateBenchmark,
                    icon     = { Icon(Icons.Default.Speed, null) },
                    label    = { Text("Benchmark") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick  = onNavigateExport,
                    icon     = { Icon(Icons.Default.Share, null) },
                    label    = { Text("Export") },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick        = { if (serviceRunning) viewModel.stopService() else viewModel.startService() },
                containerColor = if (serviceRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = if (serviceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (serviceRunning) "Stop monitoring" else "Start monitoring",
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        val hasAccess = viewModel.isRootAvailable || viewModel.isShizukuAvailable

        LazyColumn(
            contentPadding = PaddingValues(
                start  = 12.dp,
                end    = 12.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            // ── Access warning ─────────────────────────────────────────────
            if (!hasAccess) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1010))) {
                        Row(
                            modifier          = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint   = Color(0xFFFFCA28),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Root or Shizuku required for most hardware metrics.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ── FPS ────────────────────────────────────────────────────────
            item {
                MetricCard(title = "Display / FPS") {
                    val curFps = fps.currentFps
                    val avgFps = fps.averageFps
                    MetricRow(
                        label      = "Current FPS",
                        value      = curFps.displayOr { "%.1f".format(it) },
                        valueColor = if (curFps is MetricValue.Available)
                            fpsColor(curFps.value) else MaterialTheme.colorScheme.onSurface,
                    )
                    MetricRow("Average FPS",  avgFps.displayOr { "%.1f".format(it) })
                    MetricRow("Frame Time",   fps.frameTimeMs.displayOr { "%.2f ms".format(it) })
                    if (curFps is MetricValue.Available) {
                        Spacer(Modifier.height(4.dp))
                        GaugeBar(
                            value = (curFps.value / 60f * 100f).coerceIn(0f, 100f),
                            color = fpsColor(curFps.value),
                        )
                    }
                }
            }

            // ── CPU ────────────────────────────────────────────────────────
            item {
                MetricCard(title = "CPU — Helio G85 (Cortex-A55 ×6 + A75 ×2)") {
                    val totalUsage = cpu.totalUsagePercent
                    val cpuTemp   = cpu.temperatureCelsius

                    MetricRow(
                        label      = "Total Usage",
                        value      = totalUsage.displayOr { "%.1f%%".format(it) },
                        valueColor = if (totalUsage is MetricValue.Available)
                            percentColor(totalUsage.value) else MaterialTheme.colorScheme.onSurface,
                    )
                    MetricRow(
                        label = "Little Cluster (A55)",
                        value = cpu.littleClusterFreqMhz.displayOr { "$it MHz" },
                    )
                    MetricRow(
                        label = "Big Cluster (A75)",
                        value = cpu.bigClusterFreqMhz.displayOr { "$it MHz" },
                    )
                    MetricRow(
                        label      = "Temperature",
                        value      = cpuTemp.displayOr { "%.1f °C".format(it) },
                        valueColor = if (cpuTemp is MetricValue.Available)
                            tempColor(cpuTemp.value) else MaterialTheme.colorScheme.onSurface,
                    )

                    if (totalUsage is MetricValue.Available) {
                        Spacer(Modifier.height(4.dp))
                        GaugeBar(
                            value = totalUsage.value,
                            color = percentColor(totalUsage.value),
                        )
                    }

                    // Per-core mini bars
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        cpu.perCoreUsagePercent.forEachIndexed { idx, v ->
                            val pct = (v as? MetricValue.Available)?.value ?: 0f
                            Column(
                                modifier            = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text  = "C$idx",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    ),
                                )
                                Spacer(Modifier.height(2.dp))
                                GaugeBar(
                                    value    = pct,
                                    color    = percentColor(pct),
                                    modifier = Modifier.height(4.dp),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text  = if (v is MetricValue.Available) "%.0f".format(v.value) else "-",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // ── GPU ────────────────────────────────────────────────────────
            item {
                MetricCard(title = "GPU — Mali-G52 MC2") {
                    val util = gpu.utilizationPercent
                    MetricRow(
                        label      = "Utilization",
                        value      = util.displayOr { "$it%" },
                        valueColor = if (util is MetricValue.Available)
                            percentColor(util.value.toFloat()) else MaterialTheme.colorScheme.onSurface,
                    )
                    MetricRow("Frequency", gpu.frequencyMhz.displayOr { "$it MHz" })
                    MetricRow("Memory",    gpu.memoryBytes.displayOr { formatBytes(it) })
                    if (util is MetricValue.Available) {
                        Spacer(Modifier.height(4.dp))
                        GaugeBar(value = util.value.toFloat(), color = percentColor(util.value.toFloat()))
                    }
                }
            }

            // ── RAM ────────────────────────────────────────────────────────
            item {
                MetricCard(title = "RAM") {
                    val usedPct = (ram.usedPercent as? MetricValue.Available)?.value ?: 0f
                    MetricRow(
                        label      = "Used",
                        value      = ram.usedBytes.displayOr { formatBytes(it) },
                        valueColor = percentColor(usedPct),
                    )
                    MetricRow("Available", ram.availableBytes.displayOr { formatBytes(it) })
                    MetricRow("Total",     ram.totalBytes.displayOr { formatBytes(it) })
                    if (ram.usedPercent is MetricValue.Available) {
                        Spacer(Modifier.height(4.dp))
                        GaugeBar(value = usedPct, color = percentColor(usedPct))
                    }
                }
            }

            // ── Network ────────────────────────────────────────────────────
            item {
                MetricCard(title = "Network") {
                    MetricRow("↓ Download", network.downloadBps.displayOr { formatBytesPerSec(it) })
                    MetricRow("↑ Upload",   network.uploadBps.displayOr { formatBytesPerSec(it) })
                }
            }

            // ── Battery summary ────────────────────────────────────────────
            item {
                MetricCard(title = "Battery") {
                    val batTemp = battery.temperatureCelsius
                    val capPct  = (battery.capacityPercent as? MetricValue.Available)?.value?.toFloat() ?: 0f

                    MetricRow("Capacity", battery.capacityPercent.displayOr { "$it%" })
                    MetricRow("Status",   battery.status.displayOr { it })
                    MetricRow(
                        label      = "Temperature",
                        value      = batTemp.displayOr { "%.1f °C".format(it) },
                        valueColor = if (batTemp is MetricValue.Available)
                            tempColor(batTemp.value) else MaterialTheme.colorScheme.onSurface,
                    )
                    MetricRow("Current", battery.currentMa.displayOr { "%.0f mA".format(it) })
                    MetricRow("Power",   battery.powerMw.displayOr { "%.0f mW".format(it) })

                    Spacer(Modifier.height(4.dp))
                    GaugeBar(value = capPct, color = batteryColor(capPct))
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick  = onNavigateBattery,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Full battery detail →") }
                }
            }

            // ── Quick links ────────────────────────────────────────────────
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick   = onNavigateThermal,
                        modifier  = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Thermostat, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Thermal")
                    }
                    OutlinedButton(
                        onClick   = onNavigateGraphs,
                        modifier  = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.ShowChart, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Graphs")
                    }
                }
            }
        }
    }
}

private fun fpsColor(fps: Float): Color = when {
    fps >= 55f -> Color(0xFF66BB6A)
    fps >= 40f -> Color(0xFFFFCA28)
    else       -> Color(0xFFEF5350)
}

private fun batteryColor(pct: Float): Color = when {
    pct <= 15f -> Color(0xFFEF5350)
    pct <= 30f -> Color(0xFFFFCA28)
    else       -> Color(0xFF66BB6A)
}
