package com.merlin.framescope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.merlin.fpscore.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the session report card. The same [render] call paints the on-screen
 * view and the bitmap that is shared, so both look identical. Always dark, in
 * the app's brand colors, so the shared image is consistent on any phone.
 */
@SuppressLint("ViewConstructor")
class SummaryView(ctx: Context, private val s: SessionSummary, private val appLabel: String) : View(ctx) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * RATIO).toInt())
    }

    override fun onDraw(canvas: Canvas) = render(canvas, width.toFloat(), s, appLabel)

    companion object {
        /** height / width */
        const val RATIO = 480f / 360f

        private const val BG = 0xFF0E151B.toInt()
        private const val PANEL = 0xFF17212A.toInt()
        private const val INK = 0xFFE8F0F5.toInt()
        private const val MUTED = 0xFF8FA1AF.toInt()
        private const val CYAN = 0xFF4FC3F7.toInt()
        private const val GREEN = 0xFF7ED48A.toInt()
        private const val AMBER = 0xFFFFC46B.toInt()

        fun render(canvas: Canvas, width: Float, s: SessionSummary, appLabel: String) {
            val u = width / 360f // design unit
            val h = width * RATIO
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = RectF()

            p.color = BG
            rect.set(0f, 0f, width, h)
            canvas.drawRoundRect(rect, 18 * u, 18 * u, p)

            val x = 20 * u
            // Header
            p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            p.color = CYAN; p.textSize = 11 * u; p.letterSpacing = 0.12f
            canvas.drawText("FRAMESCOPE · SESSION", x, 30 * u, p)
            p.letterSpacing = 0f
            p.typeface = Typeface.DEFAULT_BOLD
            p.color = INK; p.textSize = 21 * u
            canvas.drawText(ellipsize(appLabel, p, width - 2 * x), x, 56 * u, p)
            p.typeface = Typeface.DEFAULT
            p.color = MUTED; p.textSize = 12 * u
            val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(s.endedAtWallMs))
            canvas.drawText(Ui.duration(s.durationMs) + "  ·  " + date + "  ·  " + Math.round(s.refreshHz) + " Hz", x, 75 * u, p)

            // Average FPS
            p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            p.color = GREEN; p.textSize = 58 * u
            val avg = String.format(Locale.US, "%.1f", s.avgFps)
            canvas.drawText(avg, x, 138 * u, p)
            val avgW = p.measureText(avg)
            p.typeface = Typeface.DEFAULT
            p.color = MUTED; p.textSize = 14 * u
            canvas.drawText("average FPS", x + avgW + 10 * u, 138 * u, p)

            // Stat tiles: two rows of three
            val throttled = when {
                s.throttledMs < 0 -> "n/a"
                s.durationMs <= 0 -> "0%"
                else -> Math.round(s.throttledMs * 100f / s.durationMs).toString() + "%"
            }
            val tiles = arrayOf(
                "1% low" to Math.round(s.low1Fps).toString(),
                "0.1% low" to Math.round(s.low01Fps).toString(),
                "Stutters" to s.jank.toString(),
                "Peak SoC" to (if (s.peakTempC.isNaN()) "n/a" else Math.round(s.peakTempC).toString() + "°C"),
                "Throttled" to throttled,
                "Frames" to s.frames.toString(),
            )
            val gap = 8 * u
            val tw = (width - 2 * x - 2 * gap) / 3f
            val th = 46 * u
            for (i in tiles.indices) {
                val tx = x + (i % 3) * (tw + gap)
                val ty = 156 * u + (i / 3) * (th + gap)
                p.color = PANEL
                rect.set(tx, ty, tx + tw, ty + th)
                canvas.drawRoundRect(rect, 10 * u, 10 * u, p)
                p.typeface = Typeface.DEFAULT
                p.color = MUTED; p.textSize = 10.5f * u
                canvas.drawText(tiles[i].first, tx + 10 * u, ty + 17 * u, p)
                p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                p.color = INK; p.textSize = 17 * u
                canvas.drawText(tiles[i].second, tx + 10 * u, ty + 37 * u, p)
            }

            // Frame-time graph
            val gx = x
            val gy = 276 * u
            val gw = width - 2 * x
            val gh = 150 * u
            p.color = PANEL
            rect.set(gx, gy, gx + gw, gy + gh)
            canvas.drawRoundRect(rect, 10 * u, 10 * u, p)
            p.typeface = Typeface.DEFAULT
            p.color = MUTED; p.textSize = 10.5f * u
            canvas.drawText("Frame time over the session (ms)", gx + 10 * u, gy + 17 * u, p)

            val n = s.avgFrameMs.size
            val left = gx + 10 * u
            val right = gx + gw - 10 * u
            val top = gy + 28 * u
            val bottom = gy + gh - 22 * u
            val target = if (s.refreshHz > 1f) 1000f / s.refreshHz else 16.67f
            var maxMs = target * 2.5f
            for (v in s.maxFrameMs) if (v > maxMs) maxMs = v
            if (maxMs > 120f) maxMs = 120f
            fun py(ms: Float) = bottom - (minOf(ms, maxMs) / maxMs) * (bottom - top)

            // target line
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1 * u
            p.color = 0x807ED48A.toInt()
            canvas.drawLine(left, py(target), right, py(target), p)
            if (n >= 2) {
                val step = (right - left) / (n - 1)
                val path = Path()
                // worst frame per point (faint)
                for (i in 0 until n) { val px = left + i * step; if (i == 0) path.moveTo(px, py(s.maxFrameMs[i])) else path.lineTo(px, py(s.maxFrameMs[i])) }
                p.color = 0x99FFC46B.toInt(); p.strokeWidth = 1 * u
                canvas.drawPath(path, p)
                // average per point
                path.rewind()
                for (i in 0 until n) { val px = left + i * step; if (i == 0) path.moveTo(px, py(s.avgFrameMs[i])) else path.lineTo(px, py(s.avgFrameMs[i])) }
                p.color = CYAN; p.strokeWidth = 1.6f * u; p.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, p)
            }
            p.style = Paint.Style.FILL
            // legend + axis
            p.textSize = 10 * u
            p.color = CYAN; canvas.drawText("average", left, gy + gh - 7 * u, p)
            p.color = AMBER; canvas.drawText("worst", left + 52 * u, gy + gh - 7 * u, p)
            p.color = GREEN; canvas.drawText(String.format(Locale.US, "target %.1f", target), left + 92 * u, gy + gh - 7 * u, p)
            p.color = MUTED
            p.textAlign = Paint.Align.RIGHT
            canvas.drawText(Ui.duration(s.durationMs), right, gy + gh - 7 * u, p)
            canvas.drawText(Math.round(maxMs).toString(), right, top + 9 * u, p)
            p.textAlign = Paint.Align.LEFT

            // Footer
            p.color = MUTED; p.textSize = 10.5f * u
            canvas.drawText("Measured with FrameScope · github.com/yashsaxena15/FrameScope", x, h - 18 * u, p)
        }

        private fun ellipsize(text: String, p: Paint, maxW: Float): String {
            if (p.measureText(text) <= maxW) return text
            var t = text
            while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
            return "$t…"
        }
    }
}
