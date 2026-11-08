package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.merlin.perfmon.data.model.AlertCondition
import com.merlin.perfmon.data.model.AlertRule
import com.merlin.perfmon.data.model.MetricType
import com.merlin.perfmon.ui.AlertsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    onBack: () -> Unit,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add alert")
            }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No alerts configured",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to add a threshold alert",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start  = 12.dp,
                    end    = 12.dp,
                    top    = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 80.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    AlertRuleCard(
                        rule      = rule,
                        onToggle  = { viewModel.setEnabled(rule.id, !rule.enabled) },
                        onDelete  = { viewModel.removeRule(rule.id) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddAlertDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { metric, condition, threshold, label ->
                viewModel.addRule(metric, condition, threshold, label)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AlertRuleCard(
    rule:     AlertRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        colors    = CardDefaults.cardColors(
            containerColor = if (rule.enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = rule.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Text(
                    text  = rule.metric.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (rule.enabled) 1f else 0.4f
                    ),
                )
            }
            Switch(
                checked         = rule.enabled,
                onCheckedChange = { onToggle() },
                modifier        = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete alert",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun AddAlertDialog(
    onDismiss: () -> Unit,
    onConfirm: (MetricType, AlertCondition, Float, String) -> Unit,
) {
    var selectedMetric    by remember { mutableStateOf(MetricType.CPU_TEMP) }
    var selectedCondition by remember { mutableStateOf(AlertCondition.ABOVE) }
    var thresholdText     by remember { mutableStateOf("") }
    var labelText         by remember { mutableStateOf("") }
    var metricExpanded    by remember { mutableStateOf(false) }

    val thresholdFloat = thresholdText.toFloatOrNull()
    val isValid        = thresholdFloat != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("New Alert Rule") },
        text   = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Metric picker
                ExposedDropdownMenuBox(
                    expanded         = metricExpanded,
                    onExpandedChange = { metricExpanded = it },
                ) {
                    OutlinedTextField(
                        value            = selectedMetric.name.lowercase().replace('_', ' '),
                        onValueChange    = {},
                        readOnly         = true,
                        label            = { Text("Metric") },
                        trailingIcon     = { ExposedDropdownMenuDefaults.TrailingIcon(metricExpanded) },
                        modifier         = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded         = metricExpanded,
                        onDismissRequest = { metricExpanded = false },
                    ) {
                        MetricType.entries.forEach { metric ->
                            DropdownMenuItem(
                                text    = { Text(metric.name.lowercase().replace('_', ' ')) },
                                onClick = {
                                    selectedMetric  = metric
                                    metricExpanded  = false
                                },
                            )
                        }
                    }
                }

                // Condition picker
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertCondition.entries.forEach { cond ->
                        FilterChip(
                            selected = selectedCondition == cond,
                            onClick  = { selectedCondition = cond },
                            label    = { Text("${cond.symbol} ${cond.name.lowercase()}") },
                        )
                    }
                }

                // Threshold input
                OutlinedTextField(
                    value         = thresholdText,
                    onValueChange = { thresholdText = it },
                    label         = { Text("Threshold value") },
                    isError       = thresholdText.isNotEmpty() && !isValid,
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier      = Modifier.fillMaxWidth(),
                )

                // Optional label
                OutlinedTextField(
                    value         = labelText,
                    onValueChange = { labelText = it },
                    label         = { Text("Label (optional)") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConfirm(selectedMetric, selectedCondition, thresholdFloat!!, labelText) },
                enabled  = isValid,
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
