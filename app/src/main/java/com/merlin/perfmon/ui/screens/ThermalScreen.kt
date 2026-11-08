package com.merlin.perfmon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.merlin.perfmon.ui.components.MetricRow
import com.merlin.perfmon.ui.components.displayOr
import com.merlin.perfmon.ui.components.tempColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val thermal by viewModel.thermal.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thermal Sensors") },
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
        ) {
            // Group: valid sensors first, unavailable last
            val (valid, unavail) = thermal.zones.partition {
                it.temperatureCelsius is MetricValue.Available
            }

            item {
                Text(
                    text     = "Active Sensors",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            items(valid, key = { it.index }) { zone ->
                val temp = zone.temperatureCelsius as MetricValue.Available
                MetricRow(
                    label      = "[${zone.index}] ${zone.type}",
                    value      = "%.1f °C".format(temp.value),
                    valueColor = tempColor(temp.value),
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                )
            }

            if (unavail.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text     = "No Data",
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                items(unavail, key = { it.index }) { zone ->
                    MetricRow(
                        label      = "[${zone.index}] ${zone.type}",
                        value      = "N/A",
                        valueColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}
