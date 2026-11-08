package com.merlin.perfmon.ui.screens

import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.repository.ExportFile
import com.merlin.perfmon.ui.ExportUiState
import com.merlin.perfmon.ui.ExportViewModel
import com.merlin.perfmon.util.formatBytes
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState       by viewModel.uiState.collectAsStateWithLifecycle()
    val exports       by viewModel.exports.collectAsStateWithLifecycle()
    val recordedCount by viewModel.recordedCount.collectAsStateWithLifecycle()

    // Share launcher
    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.resetState() }

    // Launch share intent when export finishes
    LaunchedEffect(uiState) {
        if (uiState is ExportUiState.Done) {
            val done = uiState as ExportUiState.Done
            val intent = viewModel.shareIntent(done.uri, done.mimeType)
            shareLauncher.launch(Intent.createChooser(intent, "Share export"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Data") },
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

            // ── Recording control ──────────────────────────────────────────
            item {
                Card(
                    colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Data Recording",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Record live metrics to a buffer, then export as CSV or JSON.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))

                        val isRecording = uiState == ExportUiState.Recording

                        if (isRecording) {
                            Row(
                                modifier          = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Default.FiberManualRecord,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        "Recording — $recordedCount samples",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.stopRecording() },
                                    colors  = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Stop") }
                            }
                        } else {
                            Button(
                                onClick  = { viewModel.startRecording() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled  = uiState == ExportUiState.Idle,
                            ) {
                                Icon(Icons.Default.FiberManualRecord, null,
                                    Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Start Recording")
                            }
                        }
                    }
                }
            }

            // ── Export buttons ─────────────────────────────────────────────
            item {
                Card(
                    colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Export Buffer",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "$recordedCount samples in buffer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(12.dp))

                        val isExporting = uiState == ExportUiState.Exporting
                        val hasData     = recordedCount > 0

                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick  = { viewModel.exportCsv() },
                                modifier = Modifier.weight(1f),
                                enabled  = hasData && !isExporting &&
                                           uiState == ExportUiState.Idle,
                            ) {
                                Icon(Icons.Default.TableChart, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("CSV")
                            }
                            OutlinedButton(
                                onClick  = { viewModel.exportJson() },
                                modifier = Modifier.weight(1f),
                                enabled  = hasData && !isExporting &&
                                           uiState == ExportUiState.Idle,
                            ) {
                                Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("JSON")
                            }
                        }

                        if (isExporting) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        if (uiState is ExportUiState.Error) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                (uiState as ExportUiState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { viewModel.resetState() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }

            // ── Previous exports ───────────────────────────────────────────
            if (exports.isNotEmpty()) {
                item {
                    Text(
                        "Previous Exports",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(exports, key = { it.name }) { file ->
                    ExportFileRow(
                        file     = file,
                        onShare  = {
                            val mimeType = if (file.name.endsWith(".csv")) "text/csv"
                                           else "application/json"
                            val intent = viewModel.shareIntent(file.uri, mimeType)
                            context.startActivity(Intent.createChooser(intent, "Share"))
                        },
                        onDelete = { viewModel.deleteExport(file.uri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportFileRow(
    file:     ExportFile,
    onShare:  () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = if (file.name.endsWith(".csv")) Icons.Default.TableChart
                                     else Icons.Default.Code,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatBytes(file.sizeBytes)}  •  " +
                        DateFormat.format("yyyy-MM-dd HH:mm", Date(file.lastModified)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, "Share", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}
