package com.merlin.framescope

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tiny styling helper for the app's screens: cards, buttons and chips built
 * from platform views and GradientDrawables (no AppCompat / Material library),
 * with a palette for the phone's light or dark theme.
 */
internal class Ui(private val ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    val card = if (night) 0xFF17212A.toInt() else 0xFFFFFFFF.toInt()
    val stroke = if (night) 0xFF2A3944.toInt() else 0xFFD5DEE6.toInt()
    val ink = if (night) 0xFFE8F0F5.toInt() else 0xFF0F1B24.toInt()
    val muted = if (night) 0xFF8FA1AF.toInt() else 0xFF5B6B78.toInt()
    val accent = if (night) 0xFF4FC3F7.toInt() else 0xFF0277BD.toInt()
    val onAccent = if (night) 0xFF06222F.toInt() else 0xFFFFFFFF.toInt()
    val accentSoft = if (night) 0xFF10222D.toInt() else 0xFFE1F1FA.toInt()
    val good = if (night) 0xFF7ED48A.toInt() else 0xFF1E7B43.toInt()
    val goodSoft = if (night) 0xFF173021.toInt() else 0xFFE3F4EA.toInt()
    val warn = if (night) 0xFFFFC46B.toInt() else 0xFF9A5B00.toInt()
    val warnSoft = if (night) 0xFF3A2A10.toInt() else 0xFFFFF1D9.toInt()
    val danger = 0xFFE5484D.toInt()

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

    fun column(padDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (padDp > 0) setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun cardColumn() = column(14).apply { background = rounded(card, 14, stroke) }

    fun text(s: CharSequence, sizeSp: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Small uppercase section label. */
    fun label(s: String) = text(s.uppercase(), 11f, muted).apply { letterSpacing = 0.1f }

    fun chip(): TextView = text("", 12f, good, bold = true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    fun setChip(v: TextView, s: String, fg: Int, bg: Int) {
        v.text = s
        v.setTextColor(fg)
        v.background = rounded(bg, 99)
    }

    /** Filled, full-width button. */
    fun button(s: String, fill: Int = accent, fg: Int = onAccent, sizeSp: Float = 15f) = text(s, sizeSp, fg, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(fill, 12)
        isClickable = true
        isFocusable = true
    }

    /** Outlined secondary button. */
    fun outlineButton(s: String) = text(s, 14f, ink, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = rounded(0x00000000, 12, stroke)
        isClickable = true
        isFocusable = true
    }

    fun lp(topDp: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(topDp) }

    fun gap(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(ctx), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val SHIZUKU_URL = "https://github.com/RikkaApps/Shizuku/releases"

        fun shizukuInstalled(ctx: Context): Boolean = try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (_: Throwable) {
            false
        }

        /** Opens the Shizuku app, or its download page when it is not installed. */
        fun openShizuku(ctx: Context) {
            val launch = try { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) } catch (_: Throwable) { null }
            try {
                ctx.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
            } catch (_: Throwable) {}
        }

        fun openOverlayPermission(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            } catch (_: Throwable) {}
        }

        /** Overlay styles in display order: stored value, name, one-line description. */
        val STYLES = arrayOf(
            Triple(Prefs.PRESET_MINIMAL, "Minimal", "FPS number only"),
            Triple(Prefs.PRESET_PILL, "Pill", "Colored FPS, mini graph, CPU, RAM, temp"),
            Triple(Prefs.PRESET_COMPACT, "Compact", "One line of text"),
            Triple(Prefs.PRESET_STANDARD, "Standard", "FPS stats and frame-time graph"),
            Triple(Prefs.PRESET_FULL, "Full", "Everything: CPU, RAM, GPU, temps"),
        )
    }
}
