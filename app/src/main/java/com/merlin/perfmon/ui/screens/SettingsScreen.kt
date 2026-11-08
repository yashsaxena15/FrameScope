package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.MetricType
import com.merlin.perfmon.data.model.OverlayMode
import com.merlin.perfmon.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val settings       by viewModel.settings.collectAsStateWithLifecycle()
    val shizukuGranted by viewModel.shizukuGranted.collectAsStateWithLifecycle()

    // Local mutable interval state for responsive sliders.
    var fpsMs by remember(settings.fpsIntervalMs)     { mutableLongStateOf(settings.fpsIntervalMs) }
    var cpuMs by remember(settings.cpuIntervalMs)     { mutableLongStateOf(settings.cpuIntervalMs) }
    var gpuMs by remember(settings.gpuIntervalMs)     { mutableLongStateOf(settings.gpuIntervalMs) }
    var ramMs by remember(settings.ramIntervalMs)     { mutableLongStateOf(settings.ramIntervalMs) }
    var netMs by remember(settings.networkIntervalMs) { mutableLongStateOf(settings.networkIntervalMs) }
    var batMs by remember(settings.batteryIntervalMs) { mutableLongStateOf(settings.batteryIntervalMs) }
    var thrMs by remember(settings.thermalIntervalMs) { mutableLongStateOf(settings.thermalIntervalMs) }
    var stoMs by remember(settings.storageIntervalMs) { mutableLongStateOf(settings.storageIntervalMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                start  = 16.dp,
                end    = 16.dp,
                top    = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── SECTION: Overlay ──────────────────────────────────────────
            item { SectionHeader("Overlay") }

            item {
                SettingRow(label = "Enable Overlay") {
                    Switch(
                        checked         = settings.overlayEnabled,
                        onCheckedChange = { viewModel.setOverlayEnabled(it) },
                    )
                }
            }

            item {
                SliderSetting(
                    label    = "Opacity",
                    valueStr = "${"%.0f".format(settings.overlayOpacity * 100)}%",
                    value    = settings.overlayOpacity,
                    range    = 0.1f..1f,
                ) { viewModel.setOverlayOpacity(it) }
            }

            item {
                SliderSetting(
                    label    = "Text Size",
                    valueStr = "${"%.0f".format(settings.overlayTextSizeSp)} sp",
                    value    = settings.overlayTextSizeSp,
                    range    = 8f..20f,
                ) { viewModel.setOverlayTextSize(it) }
            }

            item {
                Column {
                    Text("Overlay Mode", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverlayMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.overlayMode == mode,
                                onClick  = { viewModel.setOverlayMode(mode) },
                                label    = {
                                    Text(mode.name.lowercase().replaceFirstChar { it.uppercaseChar() })
                                },
                            )
                        }
                    }
                }
            }

            // ── SECTION: Visible metrics ───────────────────────────────────
            item { SectionDivider() }
            item {
                SectionHeader("Visible Metrics")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Tap to toggle which metrics appear in the overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            item {
                MetricType.entries.chunked(2).forEach { row ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { metric ->
                            val selected = metric in settings.visibleMetrics
                            FilterChip(
                                selected = selected,
                                onClick  = {
                                    val updated = if (selected)
                                        settings.visibleMetrics - metric
                                    else
                                        settings.visibleMetrics + metric
                                    viewModel.setVisibleMetrics(updated)
                                },
                                label    = {
                                    Text(
                                        metric.label(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // ── SECTION: Service ───────────────────────────────────────────
            item { SectionDivider() }
            item { SectionHeader("Service") }

            item {
                SettingRow(
                    label    = "Auto-start on boot",
                    sublabel = "Starts monitoring automatically after reboot",
                ) {
                    Switch(
                        checked         = settings.autoStartOnBoot,
                        onCheckedChange = { viewModel.setAutoStart(it) },
                    )
                }
            }

            // ── SECTION: Shizuku ───────────────────────────────────────────
            item { SectionDivider() }
            item { SectionHeader("Shizuku (Fallback Access)") }

            item {
                ShizukuPermissionCard(
                    isDaemonRunning = viewModel.isShizukuAvailable,
                    isGranted       = shizukuGranted,
                    isEnabled       = settings.useShizukuFallback,
                    onToggleEnabled = { viewModel.setUseShizuku(it) },
                    onRequest       = { viewModel.requestShizukuPermission() },
                )
            }

            // ── SECTION: Root status ───────────────────────────────────────
            item { SectionDivider() }
            item { SectionHeader("Root Status") }

            item {
                Card(
                    colors    = CardDefaults.cardColors(
                        containerColor = if (viewModel.isRootAvailable)
                            Color(0xFF1A3A1A) else Color(0xFF3A1A1A),
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val (statusText, statusColor) = if (viewModel.isRootAvailable)
                            "Root access granted via Magisk" to Color(0xFF66BB6A)
                        else
                            "Root not available — sysfs reads will be limited" to Color(0xFFEF5350)
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                        )
                    }
                }
            }

            // ── SECTION: Poll intervals ────────────────────────────────────
            item { SectionDivider() }
            item {
                SectionHeader("Poll Intervals")
                Spacer(Modifier.height(2.dp))
                Text(
                    "Lower values = more accurate, higher CPU/battery cost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            item {
                IntervalSlider("FPS",     fpsMs, 100L,    1000L)  { fpsMs = it }
                IntervalSlider("CPU",     cpuMs, 250L,    2000L)  { cpuMs = it }
                IntervalSlider("GPU",     gpuMs, 250L,    2000L)  { gpuMs = it }
                IntervalSlider("RAM",     ramMs, 500L,    5000L)  { ramMs = it }
                IntervalSlider("Network", netMs, 500L,    5000L)  { netMs = it }
                IntervalSlider("Battery", batMs, 1000L,  15000L) { batMs = it }
                IntervalSlider("Thermal", thrMs, 1000L,  30000L) { thrMs = it }
                IntervalSlider("Storage", stoMs, 10000L, 120000L){ stoMs = it }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick  = {
                        viewModel.setIntervals(
                            fpsMs, cpuMs, gpuMs, ramMs, netMs, batMs, thrMs, stoMs
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply Intervals") }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Changes take effect immediately without restarting the service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }
    }
}

// ── Shizuku permission card ───────────────────────────────────────────────────

@Composable
private fun ShizukuPermissionCard(
    isDaemonRunning: Boolean,
    isGranted:       Boolean,
    isEnabled:       Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRequest:       () -> Unit,
) {
    Card(
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SettingRow(
                label    = "Use Shizuku fallback",
                sublabel = "Enables dumpsys and /proc access without full root",
            ) {
                Switch(checked = isEnabled, onCheckedChange = onToggleEnabled)
            }

            Spacer(Modifier.height(8.dp))

            // Daemon status row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text  = "Shizuku daemon",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text  = if (isDaemonRunning) "Running" else "Not running",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDaemonRunning) Color(0xFF66BB6A) else Color(0xFFEF5350),
                    )
                }
                Text(
                    text  = "Permission: ${if (isGranted) "Granted" else "Not granted"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isGranted) Color(0xFF66BB6A) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }

            // Show grant button only when daemon is running but permission is missing
            if (isDaemonRunning && !isGranted) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant Shizuku Permission")
                }
            }

            if (!isDaemonRunning) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Install and start the Shizuku app, then return here to grant permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

// ── Reusable sub-composables ──────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    )
}

@Composable
private fun SettingRow(
    label:    String,
    sublabel: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (sublabel != null) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun SliderSetting(
    label:    String,
    valueStr: String,
    value:    Float,
    range:    ClosedFloatingPointRange<Float>,
    onFinished: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label,    style = MaterialTheme.typography.bodyMedium)
            Text(valueStr, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value                 = local,
            onValueChange         = { local = it },
            onValueChangeFinished = { onFinished(local) },
            valueRange            = range,
        )
    }
}

@Composable
private fun IntervalSlider(
    label:     String,
    valueMs:   Long,
    minMs:     Long,
    maxMs:     Long,
    onChanged: (Long) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                if (valueMs >= 1000L) "${"%.1f".format(valueMs / 1000f)}s" else "${valueMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value          = valueMs.toFloat(),
            onValueChange  = { onChanged(it.toLong()) },
            valueRange     = minMs.toFloat()..maxMs.toFloat(),
        )
    }
}

private fun MetricType.label(): String = when (this) {
    MetricType.FPS             -> "FPS"
    MetricType.CPU_USAGE       -> "CPU %"
    MetricType.CPU_FREQ        -> "CPU Freq"
    MetricType.CPU_TEMP        -> "CPU Temp"
    MetricType.GPU_USAGE       -> "GPU %"
    MetricType.GPU_FREQ        -> "GPU Freq"
    MetricType.GPU_MEM         -> "GPU Mem"
    MetricType.RAM_USED        -> "RAM Used"
    MetricType.BATTERY_LEVEL   -> "Battery"
    MetricType.BATTERY_TEMP    -> "Bat Temp"
    MetricType.BATTERY_CURRENT -> "Current"
    MetricType.BATTERY_POWER   -> "Power"
    MetricType.NETWORK_DOWN    -> "↓ Down"
    MetricType.NETWORK_UP      -> "↑ Up"
    MetricType.THERMAL_ZONES   -> "Thermal"
    MetricType.STORAGE_USED    -> "Storage"
}
