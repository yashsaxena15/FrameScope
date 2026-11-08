package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.ui.MainViewModel

private const val GRAPH_HISTORY = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val fpsHistory  = remember { ArrayDeque<Float>(GRAPH_HISTORY) }
    val cpuHistory  = remember { ArrayDeque<Float>(GRAPH_HISTORY) }
    val gpuHistory  = remember { ArrayDeque<Float>(GRAPH_HISTORY) }
    val ramHistory  = remember { ArrayDeque<Float>(GRAPH_HISTORY) }
    val batHistory  = remember { ArrayDeque<Float>(GRAPH_HISTORY) }
    val tempHistory = remember { ArrayDeque<Float>(GRAPH_HISTORY) }

    val fps     by viewModel.fps.collectAsStateWithLifecycle()
    val cpu     by viewModel.cpu.collectAsStateWithLifecycle()
    val gpu     by viewModel.gpu.collectAsStateWithLifecycle()
    val ram     by viewModel.ram.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()

    LaunchedEffect(fps.currentFps) {
        (fps.currentFps as? MetricValue.Available)?.value?.let { fpsHistory.addAndTrim(it) }
    }
    LaunchedEffect(cpu.totalUsagePercent) {
        (cpu.totalUsagePercent as? MetricValue.Available)?.value?.let { cpuHistory.addAndTrim(it) }
        (cpu.temperatureCelsius as? MetricValue.Available)?.value?.let { tempHistory.addAndTrim(it) }
    }
    LaunchedEffect(gpu.utilizationPercent) {
        (gpu.utilizationPercent as? MetricValue.Available)?.value?.let { gpuHistory.addAndTrim(it.toFloat()) }
    }
    LaunchedEffect(ram.usedPercent) {
        (ram.usedPercent as? MetricValue.Available)?.value?.let { ramHistory.addAndTrim(it) }
    }
    LaunchedEffect(battery.capacityPercent) {
        (battery.capacityPercent as? MetricValue.Available)?.value?.let { batHistory.addAndTrim(it.toFloat()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Graphs") },
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
            item {
                GraphCard("FPS", fpsHistory.toList(), Color(0xFF44FF88), 0f, 60f) {
                    "%.0f".format(it)
                }
            }
            item {
                GraphCard("CPU Usage (%)", cpuHistory.toList(), Color(0xFF00E5C8), 0f, 100f) {
                    "%.0f%%".format(it)
                }
            }
            item {
                GraphCard("GPU Utilization (%)", gpuHistory.toList(), Color(0xFFFFCA28), 0f, 100f) {
                    "%.0f%%".format(it)
                }
            }
            item {
                GraphCard("RAM Usage (%)", ramHistory.toList(), Color(0xFFFF7043), 0f, 100f) {
                    "%.0f%%".format(it)
                }
            }
            item {
                GraphCard("CPU Temperature (°C)", tempHistory.toList(), Color(0xFFEF5350), 30f, 100f) {
                    "%.0f°C".format(it)
                }
            }
            item {
                GraphCard("Battery (%)", batHistory.toList(), Color(0xFF66BB6A), 0f, 100f) {
                    "%.0f%%".format(it)
                }
            }
        }
    }
}

// ── Graph card ────────────────────────────────────────────────────────────────

@Composable
private fun GraphCard(
    title:  String,
    data:   List<Float>,
    color:  Color,
    minY:   Float,
    maxY:   Float,
    yLabel: (Float) -> String,
) {
    val current = data.lastOrNull()
    val peak    = data.maxOrNull()
    val avg     = if (data.isEmpty()) null else data.average().toFloat()

    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                if (current != null) {
                    Text(yLabel(current), style = MaterialTheme.typography.bodySmall, color = color)
                }
            }

            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp),
                    )
            ) {
                if (data.size >= 2) {
                    LineChart(data = data, color = color, minY = minY, maxY = maxY)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Collecting data…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                }
            }

            if (avg != null && peak != null && current != null) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatChip("AVG",  yLabel(avg))
                    StatChip("PEAK", yLabel(peak))
                    StatChip("NOW",  yLabel(current))
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label,  style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LineChart(data: List<Float>, color: Color, minY: Float, maxY: Float) {
    Canvas(modifier = Modifier
        .fillMaxSize()
        .padding(4.dp)) {

        val range = (maxY - minY).coerceAtLeast(1f)
        val stepX = if (data.size > 1) size.width / (data.size - 1).toFloat() else size.width

        val linePath = Path()
        data.forEachIndexed { idx, value ->
            val x = idx * stepX
            val y = size.height - ((value - minY) / range * size.height).coerceIn(0f, size.height)
            if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        drawPath(linePath, color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // Filled area
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo((data.size - 1) * stepX, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fillPath, color = color.copy(alpha = 0.10f))

        // Dot on latest value
        val lx = (data.size - 1) * stepX
        val ly = size.height - ((data.last() - minY) / range * size.height).coerceIn(0f, size.height)
        drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(lx, ly))
    }
}

private fun ArrayDeque<Float>.addAndTrim(value: Float) {
    addLast(value)
    while (size > GRAPH_HISTORY) removeFirst()
}
