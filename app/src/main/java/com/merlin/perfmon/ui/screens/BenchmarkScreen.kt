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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.repository.BenchmarkResult
import com.merlin.perfmon.data.repository.BenchmarkState
import com.merlin.perfmon.ui.BenchmarkViewModel
import com.merlin.perfmon.ui.components.GaugeBar
import com.merlin.perfmon.ui.components.MetricCard
import com.merlin.perfmon.ui.components.MetricRow
import com.merlin.perfmon.ui.components.percentColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    viewModel: BenchmarkViewModel = hiltViewModel(),
) {
    val state    by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val result   by viewModel.result.collectAsStateWithLifecycle()

    var selectedDuration by remember { mutableLongStateOf(30_000L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Benchmark") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Control panel ──────────────────────────────────────────────
            item {
                MetricCard(title = "Benchmark Control") {
                    when (state) {
                        BenchmarkState.IDLE -> {
                            Text(
                                text  = "Runs synthetic CPU load across all 8 cores while sampling hardware metrics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(12.dp))

                            // Duration selector
                            Text("Duration", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15_000L to "15s", 30_000L to "30s", 60_000L to "60s").forEach { (ms, label) ->
                                    FilterChip(
                                        selected = selectedDuration == ms,
                                        onClick  = { selectedDuration = ms },
                                        label    = { Text(label) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick  = { viewModel.start(selectedDuration) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Start Benchmark")
                            }
                        }

                        BenchmarkState.RUNNING -> {
                            Text(
                                text  = "Running… ${"%.0f".format(progress * 100)}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color    = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick  = { viewModel.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text("Cancel")
                            }
                        }

                        BenchmarkState.FINISHED -> {
                            Text("Benchmark complete!", style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF66BB6A))
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick  = { viewModel.reset() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Run Again") }
                            }
                        }
                    }
                }
            }

            // ── Results ────────────────────────────────────────────────────
            if (result != null) {
                item { BenchmarkResultCard(result!!) }
            }
        }
    }
}

@Composable
private fun BenchmarkResultCard(r: BenchmarkResult) {
    MetricCard(title = "Results — ${r.durationMs / 1000}s test • ${r.samples.size} samples") {
        // CPU
        SectionLabel("CPU")
        MetricRow("Avg Usage",  r.avgCpuPct?.let  { "%.1f%%".format(it) } ?: "N/A")
        MetricRow("Peak Usage", r.peakCpuPct?.let { "%.1f%%".format(it) } ?: "N/A")
        r.avgCpuPct?.let { GaugeBar(it, color = percentColor(it)) }
        Spacer(Modifier.height(4.dp))
        MetricRow("Avg Temp",   r.avgCpuTempC?.let  { "%.1f °C".format(it) } ?: "N/A")
        MetricRow("Peak Temp",  r.peakCpuTempC?.let { "%.1f °C".format(it) } ?: "N/A")

        Spacer(Modifier.height(8.dp))

        // GPU
        SectionLabel("GPU")
        MetricRow("Avg Utilization",  r.avgGpuPct?.let  { "%.1f%%".format(it) } ?: "N/A")
        MetricRow("Peak Utilization", r.peakGpuPct?.let { "%.1f%%".format(it) } ?: "N/A")
        r.avgGpuPct?.let { GaugeBar(it, color = percentColor(it)) }

        Spacer(Modifier.height(8.dp))

        // FPS
        SectionLabel("FPS")
        MetricRow("Avg FPS", r.avgFps?.let { "%.1f".format(it) } ?: "N/A")
        MetricRow("Min FPS", r.minFps?.let { "%.1f".format(it) } ?: "N/A",
            valueColor = if (r.minFps != null && r.minFps < 30f) Color(0xFFEF5350)
                         else MaterialTheme.colorScheme.onSurface)

        Spacer(Modifier.height(8.dp))

        // Power
        SectionLabel("Power")
        MetricRow("Avg Battery Draw", r.avgBatteryPowerMw?.let { "%.0f mW".format(it) } ?: "N/A")
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
