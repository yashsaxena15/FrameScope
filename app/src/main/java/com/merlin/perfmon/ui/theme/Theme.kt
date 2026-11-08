package com.merlin.perfmon.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Colour palette ────────────────────────────────────────────────────────────

val Teal200    = Color(0xFF00E5C8)
val Teal700    = Color(0xFF00B09B)
val Amber400   = Color(0xFFFFCA28)
val Red400     = Color(0xFFEF5350)
val Green400   = Color(0xFF66BB6A)
val Surface    = Color(0xFF0D0D0D)
val SurfaceVar = Color(0xFF1A1A1A)
val Background = Color(0xFF050505)
val OnSurface  = Color(0xFFE0E0E0)

private val DarkColorScheme = darkColorScheme(
    primary          = Teal200,
    onPrimary        = Color.Black,
    primaryContainer = Teal700,
    secondary        = Amber400,
    onSecondary      = Color.Black,
    error            = Red400,
    background       = Background,
    surface          = Surface,
    surfaceVariant   = SurfaceVar,
    onBackground     = OnSurface,
    onSurface        = OnSurface,
)

@Composable
fun PerfMonTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // This app is always dark — AMOLED black saves battery on OLED screens.
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content,
    )
}
