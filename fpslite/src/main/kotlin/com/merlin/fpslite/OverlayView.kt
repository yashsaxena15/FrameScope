package com.merlin.fpslite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Single custom View drawn on a Canvas. No layouts, no allocations in onDraw:
 * paints, path, rect, string builder and the snapshot are all preallocated.
 */
@SuppressLint("ViewConstructor")
class OverlayView(ctx: Context, private val sampler: Sampler, private val prefs: Prefs) : View(ctx) {
    private val snap = Snapshot()
    private val sb = StringBuilder(96)
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val targetPaint = Paint().apply {
        color = 0x8066BB6A.toInt()
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private val rect = RectF()

    private var pad = 0f
    private var lineH = 0f
    private var bigH = 0f
    private var graphH = 0f
    private var widthPx = 0
    private var heightPx = 0
    private var preset = Prefs.PRESET_STANDARD
    private var extraLines = 0
    private var showCpuLoad = true
    private var showCpuFreq = true
    private var showGpu = true
    private var showTemps = true
    private var showRam = true
    private var showSwap = false

    init {
        applyPrefs()
    }

    fun applyPrefs() {
        val s = prefs.textScale.coerceIn(0.6f, 2f)
        preset = prefs.preset
        showCpuLoad = prefs.showCpuLoad
        showCpuFreq = prefs.showCpuFreq
        showGpu = prefs.showGpu
        showTemps = prefs.showTemps
        showRam = prefs.showRam
        showSwap = prefs.showSwap
        bgPaint.color = Color.argb(prefs.bgAlpha.coerceIn(0, 255), 0, 0, 0)
        pad = 6 * density * s
        bigPaint.textSize = (if (preset == Prefs.PRESET_MINIMAL) 20 else 22) * density * s
        textPaint.textSize = 11 * density * s
        dimPaint.textSize = 10 * density * s
        graphPaint.strokeWidth = 1.2f * density
        targetPaint.strokeWidth = 1f * density
        lineH = textPaint.fontSpacing
        bigH = bigPaint.fontSpacing
        graphH = 34 * density * s

        extraLines = 0
        if (preset == Prefs.PRESET_FULL) {
            if (showCpuLoad || showCpuFreq) extraLines++
            if (showRam) extraLines++
            if (showRam && showSwap) extraLines++
            if (showGpu) extraLines++
            if (showTemps) extraLines++
        }
        when (preset) {
            Prefs.PRESET_MINIMAL -> {
                widthPx = (bigPaint.measureText("000") + 2 * pad).toInt()
                heightPx = (bigH + 2 * pad).toInt()
            }
            else -> {
                var widest = textPaint.measureText("avg 000 1% 000 .1% 000")
                if (preset == Prefs.PRESET_FULL && showRam) {
                    widest = maxOf(widest, textPaint.measureText("RAM 10.8/11.0G 98% App 1.65G"))
                }
                if (preset == Prefs.PRESET_FULL && (showCpuLoad || showCpuFreq)) {
                    widest = maxOf(widest, textPaint.measureText("CPU 100% 2.4|3.3|3.7 GHz"))
                }
                if (preset == Prefs.PRESET_FULL && showTemps) {
                    widest = maxOf(widest, textPaint.measureText("CPU 100° GPU 100° Bat 45°"))
                }
                widthPx = (widest + 2 * pad).toInt()
                heightPx = (bigH + 2 * lineH + graphH + extraLines * lineH + 3 * pad).toInt()
            }
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(widthPx, heightPx)
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(sampler.lock) { snap.copyFrom(sampler.shared) }
        val w = width.toFloat()
        rect.set(0f, 0f, w, height.toFloat())
        val r = 8 * density
        canvas.drawRoundRect(rect, r, r, bgPaint)

        var y = pad - bigPaint.ascent()
        val x = pad

        // ── main FPS ─────────────────────────────────────────────────────────
        sb.setLength(0)
        if (snap.fps < 0f) sb.append("--") else appendFixed(sb, snap.fps, if (preset == Prefs.PRESET_MINIMAL) 0 else 1)
        canvas.drawText(sb, 0, sb.length, x, y, bigPaint)
        if (preset == Prefs.PRESET_MINIMAL) return

        val bigW = bigPaint.measureText(sb, 0, sb.length)
        sb.setLength(0)
        sb.append(" FPS")
        if (snap.refreshHz > 0f) { sb.append(" @"); appendFixed(sb, snap.refreshHz, 0); sb.append("Hz") }
        canvas.drawText(sb, 0, sb.length, x + bigW, y, dimPaint)
        y += bigPaint.descent() + pad / 2 - textPaint.ascent()

        if (snap.status.isNotEmpty()) {
            canvas.drawText(snap.status, x, y, textPaint)
            y += lineH
            sb.setLength(0)
            sb.append(snap.pkg)
            canvas.drawText(sb, 0, minOf(sb.length, 28), x, y, dimPaint)
            return
        }

        // ── stats ────────────────────────────────────────────────────────────
        sb.setLength(0)
        sb.append("avg "); appendFixed(sb, snap.avgFps, 0)
        sb.append(" 1% "); appendFixed(sb, snap.low1, 0)
        sb.append(" .1% "); appendFixed(sb, snap.low01, 0)
        canvas.drawText(sb, 0, sb.length, x, y, textPaint)
        y += lineH

        sb.setLength(0)
        appendFixed(sb, snap.frameTimeMs, 1); sb.append("ms  jank "); sb.append(snap.jank)
        if (snap.gaps > 0) { sb.append("  gap "); sb.append(snap.gaps) }
        canvas.drawText(sb, 0, sb.length, x, y, textPaint)
        y += textPaint.descent() + pad / 2

        // ── frame-time graph ────────────────────────────────────────────────
        drawGraph(canvas, x, y, w - 2 * pad, graphH)
        y += graphH + pad / 2 - textPaint.ascent()

        if (preset != Prefs.PRESET_FULL) return

        // ── extras ───────────────────────────────────────────────────────────
        if (showCpuLoad || showCpuFreq) {
            sb.setLength(0)
            sb.append("CPU ")
            if (showCpuLoad) {
                if (snap.cpuLoad >= 0f) { appendFixed(sb, snap.cpuLoad, 0); sb.append("% ") } else sb.append("--% ")
            }
            if (showCpuFreq) {
                for (i in 0 until snap.clusters) {
                    if (i > 0) sb.append('|')
                    appendFixed(sb, snap.clusterMHz[i] / 1000f, 1)
                }
                if (snap.clusters > 0) sb.append(" GHz")
            }
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
            y += lineH
        }
        if (showRam) {
            sb.setLength(0)
            sb.append("RAM ")
            if (snap.ramTotalMB > 0 && snap.ramUsedMB >= 0) {
                appendFixed(sb, snap.ramUsedMB / 1024f, 1); sb.append('/')
                appendFixed(sb, snap.ramTotalMB / 1024f, 1); sb.append("G ")
                appendFixed(sb, snap.ramUsedMB * 100f / snap.ramTotalMB, 0); sb.append('%')
            } else {
                sb.append("--")
            }
            if (snap.appRamMB >= 0) {
                sb.append(" App ")
                appendFixed(sb, snap.appRamMB / 1024f, 2); sb.append('G')
            }
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
            y += lineH
            if (showSwap) {
                sb.setLength(0)
                sb.append("VRAM ")
                if (snap.swapTotalMB > 0 && snap.swapUsedMB >= 0) {
                    appendFixed(sb, snap.swapUsedMB / 1024f, 1); sb.append('/')
                    appendFixed(sb, snap.swapTotalMB / 1024f, 1); sb.append("G ")
                    appendFixed(sb, snap.swapUsedMB * 100f / snap.swapTotalMB, 0); sb.append('%')
                } else {
                    sb.append("none")
                }
                canvas.drawText(sb, 0, sb.length, x, y, textPaint)
                y += lineH
            }
        }
        if (showGpu) {
            sb.setLength(0)
            sb.append("GPU* ")
            when {
                snap.gpuState < 0 -> sb.append("N/A")
                snap.gpuState == 0 || snap.gpuBusy < 0f -> sb.append("…")
                else -> {
                    appendFixed(sb, snap.gpuBusy, 0); sb.append("% ")
                    sb.append(snap.gpuMHz); sb.append("MHz")
                }
            }
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
            y += lineH
        }
        if (showTemps) {
            sb.setLength(0)
            appendTemp(sb, "CPU ", snap.tCpu)
            appendTemp(sb, " GPU ", snap.tGpu)
            appendTemp(sb, " Bat ", snap.tBattery)
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
        }
    }

    private fun drawGraph(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val n = snap.graphN
        val targetMs = if (snap.refreshHz > 0f) 1000f / snap.refreshHz else 16.67f
        var maxMs = targetMs * 2f
        for (i in 0 until n) if (snap.graph[i] > maxMs) maxMs = snap.graph[i]
        if (maxMs > 100f) maxMs = 100f
        val ty = top + h - (targetMs / maxMs) * h
        canvas.drawLine(left, ty, left + w, ty, targetPaint)
        if (n < 2) return
        path.rewind()
        val step = w / (Snapshot.GRAPH_POINTS - 1)
        val x0 = left + w - (n - 1) * step
        for (i in 0 until n) {
            val v = if (snap.graph[i] > maxMs) maxMs else snap.graph[i]
            val px = x0 + i * step
            val py = top + h - (v / maxMs) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, graphPaint)
    }

    private fun appendTemp(sb: StringBuilder, label: String, t: Float) {
        sb.append(label)
        if (t.isNaN()) sb.append("--") else appendFixed(sb, t, 0)
        sb.append('°')
    }

    companion object {
        /** Appends a non-negative number with [decimals] (0–2) digits without allocating. */
        fun appendFixed(sb: StringBuilder, v: Float, decimals: Int) {
            var value = v
            if (value.isNaN()) { sb.append("--"); return }
            if (value < 0f) { sb.append('-'); value = -value }
            val mul = when (decimals) { 0 -> 1; 1 -> 10; else -> 100 }
            val scaled = (value * mul + 0.5f).toLong()
            sb.append(scaled / mul)
            if (decimals > 0) {
                sb.append('.')
                val frac = scaled % mul
                if (decimals == 2 && frac < 10) sb.append('0')
                sb.append(frac)
            }
        }
    }
}
