package com.merlin.perfmon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.merlin.perfmon.data.model.MetricValue

@Composable
fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary,
                ),
                modifier   = Modifier.padding(bottom = 8.dp),
            )
            content()
        }
    }
}

@Composable
fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            ),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                color      = valueColor,
            ),
        )
    }
}

@Composable
fun GaugeBar(
    value: Float,       // 0..100
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction = (value / 100f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** Display a MetricValue as a string, falling back to "Unavailable" */
fun <T> MetricValue<T>.displayOr(transform: (T) -> String): String =
    when (this) {
        is MetricValue.Available -> transform(value)
        MetricValue.Unavailable  -> "Unavailable"
    }

/** Colour for percentage-based gauges */
fun percentColor(pct: Float): Color = when {
    pct >= 90f -> Color(0xFFEF5350)  // Red
    pct >= 70f -> Color(0xFFFFCA28)  // Amber
    else       -> Color(0xFF66BB6A)  // Green
}

/** Colour for temperature */
fun tempColor(celsius: Float): Color = when {
    celsius >= 80f -> Color(0xFFEF5350)
    celsius >= 65f -> Color(0xFFFFCA28)
    else           -> Color(0xFF66BB6A)
}
