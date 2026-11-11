package com.merlin.framescope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.merlin.fpscore.Smoothness

/**
 * Single custom View drawn on a Canvas. No layouts, no allocations in onDraw:
 * paints, path, rect, string builder and the snapshot are all preallocated.
 */
@SuppressLint("ViewConstructor")
class OverlayView(ctx: Context, private val source: SnapshotSource, private val prefs: Prefs) : View(ctx) {
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
    // Compact preset: bright values, dim labels, one line.
    private val compactVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val compactLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AB0C0.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        style = Paint.Style.STROKE
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private var colorize = true
    private var fpsColor = Color.WHITE
    private var tempColor = Color.WHITE

    private var pad = 0f
    private var lineH = 0f
    private var bigH = 0f
    private var graphH = 0f
    private var widthPx = 0
    private var heightPx = 0
    private var preset = Prefs.PRESET_FULL
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
        colorize = prefs.colorize
        pillBorder.strokeWidth = 1f * density
        sparkPaint.strokeWidth = 1.3f * density
        bgPaint.color = Color.argb(prefs.bgAlpha.coerceIn(0, 255), 0, 0, 0)
        pad = 6 * density * s
        bigPaint.textSize = (if (preset == Prefs.PRESET_MINIMAL) 20 else 22) * density * s
        textPaint.textSize = 11 * density * s
        dimPaint.textSize = 10 * density * s
        graphPaint.strokeWidth = 1.2f * density
        targetPaint.strokeWidth = 1f * density
        compactVal.textSize = 13 * density * s
        compactLabel.textSize = 11 * density * s
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
            Prefs.PRESET_RING -> {
                widthPx = ringWidth(compactHasGpu)
                heightPx = (ringDiameter() + 1.6f * pad).toInt()
            }
            Prefs.PRESET_PILL -> {
                widthPx = pillWidth(compactHasGpu)
                heightPx = (compactVal.fontSpacing + 1.7f * pad).toInt()
            }
            else -> {
                var widest = textPaint.measureText("avg 000 1% 000 .1% 000")
                if (preset == Prefs.PRESET_FULL && showRam) {
                    widest = maxOf(widest, textPaint.measureText("RAM 10.8/11.0G 98% App 1.65G"))
                }
                if (preset == Prefs.PRESET_FULL && (showCpuLoad || showCpuFreq)) {
                    widest = maxOf(widest, textPaint.measureText("CPU 100% 2.4|3.3|3.7 GHz ▼"))
                }
                if (preset == Prefs.PRESET_FULL && showTemps) {
                    widest = maxOf(widest, textPaint.measureText("SoC 100° Bat 45°"))
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
        synchronized(source.lock) { snap.copyFrom(source.shared) }
        val w = width.toFloat()
        rect.set(0f, 0f, w, height.toFloat())
        val r = if (preset == Prefs.PRESET_PILL) height / 2f else 8 * density
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // Color by smoothness: FPS against its target, temperature against throttling.
        fpsColor = levelColor(if (colorize) Smoothness.fpsLevel(snap.fps, snap.avgFps, snap.refreshHz) else Smoothness.UNKNOWN, true)
        tempColor = levelColor(if (colorize) Smoothness.tempLevel(snap.tSoc, snap.cpuCapped) else Smoothness.UNKNOWN, false)
        bigPaint.color = fpsColor

        if (preset == Prefs.PRESET_PILL) {
            val half = pillBorder.strokeWidth / 2f
            rect.inset(half, half)
            canvas.drawRoundRect(rect, r, r, pillBorder)
            drawPill(canvas)
            return
        }

        if (preset == Prefs.PRESET_RING) {
            drawRing(canvas)
            return
        }

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
                if (snap.cpuCapped) sb.append(" ▼")
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
                sb.append("Swap ")
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
            sb.append(if (snap.gpuAccurate) "GPU " else "GPU* ")
            when {
                snap.gpuState < 0 -> sb.append("N/A")
                snap.gpuState == 0 || snap.gpuBusy < 0f -> sb.append("…")
                else -> {
                    if (snap.gpuBusy >= 0f) { appendFixed(sb, snap.gpuBusy, 0); sb.append("% ") }
                    if (snap.gpuMHz > 0) { sb.append(snap.gpuMHz); sb.append("MHz") }
                }
            }
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
            y += lineH
        }
        if (showTemps) {
            sb.setLength(0)
            appendTemp(sb, "SoC ", snap.tSoc)
            appendTemp(sb, "  Bat ", snap.tBattery)
            val normal = textPaint.color
            if (tempColor != Color.WHITE) textPaint.color = tempColor
            canvas.drawText(sb, 0, sb.length, x, y, textPaint)
            textPaint.color = normal
        }
    }

    /** Green stays white for temperature (only warnings are colored); FPS shows green too. */
    private fun levelColor(level: Int, greenWhenGood: Boolean): Int = when (level) {
        Smoothness.GOOD -> if (greenWhenGood) COLOR_GOOD else Color.WHITE
        Smoothness.WARN -> COLOR_WARN
        Smoothness.BAD -> COLOR_BAD
        else -> Color.WHITE
    }

    private fun pillGap() = compactLabel.measureText("  ")
    private fun sparkWidth() = compactVal.textSize * 3.2f

    private fun pillWidth(withGpu: Boolean): Int {
        val gap = pillGap()
        var w = compactVal.measureText("144") + gap + sparkWidth() + gap +
            compactLabel.measureText("CPU ") + compactVal.measureText("100%") + gap +
            compactLabel.measureText("RAM ") + compactVal.measureText("100%") + gap +
            compactVal.measureText("100°")
        if (withGpu) w += compactLabel.measureText("GPU ") + compactVal.measureText("100%") + gap
        return (w + 2 * pillPadX() + 2 * density).toInt()
    }

    private fun pillPadX() = pad * 2f

    /** `60 ⌇⌇ CPU 23%  GPU 45%  RAM 79%  42°` in a rounded pill. */
    private fun drawPill(canvas: Canvas) {
        val hasGpu = snap.gpuAccurate && snap.gpuBusy >= 0f
        if (hasGpu != compactHasGpu) {
            compactHasGpu = hasGpu
            widthPx = pillWidth(hasGpu)
            post { requestLayout() }
        }
        val h = height.toFloat()
        val y = (h - compactVal.descent() - compactVal.ascent()) / 2f
        val gap = pillGap()
        var x = pillPadX()
        if (snap.status.isNotEmpty() && snap.fps < 0f) {
            canvas.drawText(snap.status, x, y, compactLabel)
            return
        }
        // FPS, colored
        sb.setLength(0)
        if (snap.fps < 0f) sb.append("--") else appendFixed(sb, snap.fps, 0)
        compactVal.color = fpsColor
        x = seg(canvas, x, y, compactVal) + gap
        compactVal.color = Color.WHITE
        // Mini frame-time line (most recent frames)
        drawSpark(canvas, x, h * 0.26f, sparkWidth(), h * 0.48f)
        x += sparkWidth() + gap
        // CPU
        x = label(canvas, x, y, "CPU ")
        sb.setLength(0)
        if (snap.cpuLoad < 0f) sb.append("--") else appendFixed(sb, snap.cpuLoad, 0)
        sb.append('%')
        x = seg(canvas, x, y, compactVal) + gap
        // GPU (driver source only)
        if (hasGpu) {
            x = label(canvas, x, y, "GPU ")
            sb.setLength(0)
            appendFixed(sb, snap.gpuBusy, 0)
            sb.append('%')
            x = seg(canvas, x, y, compactVal) + gap
        }
        // RAM
        x = label(canvas, x, y, "RAM ")
        sb.setLength(0)
        if (snap.ramTotalMB > 0 && snap.ramUsedMB >= 0) appendFixed(sb, snap.ramUsedMB * 100f / snap.ramTotalMB, 0) else sb.append("--")
        sb.append('%')
        x = seg(canvas, x, y, compactVal) + gap
        // SoC temperature, colored when hot
        sb.setLength(0)
        if (snap.tSoc.isNaN()) sb.append("--") else appendFixed(sb, snap.tSoc, 0)
        sb.append('°')
        compactVal.color = tempColor
        seg(canvas, x, y, compactVal)
        compactVal.color = Color.WHITE
    }

    private fun drawSpark(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val total = snap.graphN
        val n = if (total > SPARK_POINTS) SPARK_POINTS else total
        if (n < 2) return
        val start = total - n
        val targetMs = if (snap.refreshHz > 0f) 1000f / snap.refreshHz else 16.67f
        var maxMs = targetMs * 2.2f
        for (i in start until total) if (snap.graph[i] > maxMs) maxMs = snap.graph[i]
        if (maxMs > 100f) maxMs = 100f
        path.rewind()
        val step = w / (SPARK_POINTS - 1)
        val x0 = left + w - (n - 1) * step
        for (i in 0 until n) {
            val raw = snap.graph[start + i]
            val v = if (raw > maxMs) maxMs else raw
            val px = x0 + i * step
            val py = top + h - (v / maxMs) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, sparkPaint)
    }

    /** Whether the Pill / Ring currently include the GPU segment (only with an accurate source). */
    private var compactHasGpu = false

    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
    }
    private val ringArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ringUnit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AB0C0.toInt()
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val ringRect = RectF()

    /** Three text lines tall, so the stats sit level with the ring. */
    private fun ringDiameter() = compactVal.fontSpacing * 3f

    private fun ringTextWidth(withGpu: Boolean): Float {
        val gap = pillGap()
        var l1 = compactLabel.measureText("CPU ") + compactVal.measureText("100%")
        if (withGpu) l1 += gap + compactLabel.measureText("GPU ") + compactVal.measureText("100%")
        val l2 = compactLabel.measureText("RAM ") + compactVal.measureText("100%") + gap +
            compactLabel.measureText("SoC ") + compactVal.measureText("100°")
        val l3 = compactLabel.measureText("1% low ") + compactVal.measureText("144")
        return maxOf(l1, l2, l3)
    }

    private fun ringWidth(withGpu: Boolean): Int =
        (pad * 1.4f + ringDiameter() + pad * 1.4f + ringTextWidth(withGpu) + pad * 1.8f).toInt()

    /** Ring filled toward the target FPS, with the FPS inside and system stats beside it. */
    private fun drawRing(canvas: Canvas) {
        val hasGpu = snap.gpuAccurate && snap.gpuBusy >= 0f
        if (hasGpu != compactHasGpu) {
            compactHasGpu = hasGpu
            widthPx = ringWidth(hasGpu)
            post { requestLayout() }
        }
        val h = height.toFloat()
        val d = ringDiameter()
        val stroke = d * 0.1f
        val left = pad * 1.4f
        val top = (h - d) / 2f
        ringRect.set(left + stroke / 2f, top + stroke / 2f, left + d - stroke / 2f, top + d - stroke / 2f)
        ringTrack.strokeWidth = stroke
        ringArc.strokeWidth = stroke
        canvas.drawOval(ringRect, ringTrack)
        if (snap.fps >= 0f) {
            val target = Smoothness.targetFps(snap.avgFps, snap.refreshHz)
            var ratio = snap.fps / target
            if (ratio > 1f) ratio = 1f
            ringArc.color = if (colorize) fpsColor else 0xFF4FC3F7.toInt()
            canvas.drawArc(ringRect, -90f, 360f * ratio, false, ringArc)
        }
        // FPS number and unit inside the ring
        val cx = left + d / 2f
        ringVal.textSize = d * 0.36f
        ringUnit.textSize = d * 0.15f
        sb.setLength(0)
        if (snap.fps < 0f) sb.append("--") else appendFixed(sb, snap.fps, 0)
        val numY = top + d / 2f - (ringVal.descent() + ringVal.ascent()) / 2f - d * 0.06f
        canvas.drawText(sb, 0, sb.length, cx, numY, ringVal)
        canvas.drawText("FPS", cx, numY + ringUnit.textSize * 1.25f, ringUnit)

        // Stats beside it
        val x0 = left + d + pad * 1.4f
        val gap = pillGap()
        val lineH = compactVal.fontSpacing
        var y = top - compactVal.ascent() + (lineH - (compactVal.descent() - compactVal.ascent())) / 2f
        if (snap.status.isNotEmpty() && snap.fps < 0f) {
            canvas.drawText(snap.status, x0, y + lineH, compactLabel)
            return
        }
        // line 1: CPU (and GPU when accurate)
        var x = label(canvas, x0, y, "CPU ")
        sb.setLength(0)
        if (snap.cpuLoad < 0f) sb.append("--") else appendFixed(sb, snap.cpuLoad, 0)
        sb.append('%')
        x = seg(canvas, x, y, compactVal)
        if (hasGpu) {
            x = label(canvas, x + gap, y, "GPU ")
            sb.setLength(0)
            appendFixed(sb, snap.gpuBusy, 0)
            sb.append('%')
            seg(canvas, x, y, compactVal)
        }
        // line 2: RAM and SoC temperature
        y += lineH
        x = label(canvas, x0, y, "RAM ")
        sb.setLength(0)
        if (snap.ramTotalMB > 0 && snap.ramUsedMB >= 0) appendFixed(sb, snap.ramUsedMB * 100f / snap.ramTotalMB, 0) else sb.append("--")
        sb.append('%')
        x = seg(canvas, x, y, compactVal)
        x = label(canvas, x + gap, y, "SoC ")
        sb.setLength(0)
        if (snap.tSoc.isNaN()) sb.append("--") else appendFixed(sb, snap.tSoc, 0)
        sb.append('°')
        compactVal.color = tempColor
        seg(canvas, x, y, compactVal)
        compactVal.color = Color.WHITE
        // line 3: 1% low
        y += lineH
        x = label(canvas, x0, y, "1% low ")
        sb.setLength(0)
        if (snap.low1 <= 0f) sb.append("--") else appendFixed(sb, snap.low1, 0)
        seg(canvas, x, y, compactVal)
    }

    private fun seg(canvas: Canvas, x: Float, y: Float, p: Paint): Float {
        canvas.drawText(sb, 0, sb.length, x, y, p)
        return x + p.measureText(sb, 0, sb.length)
    }

    private fun label(canvas: Canvas, x: Float, y: Float, text: String): Float {
        canvas.drawText(text, x, y, compactLabel)
        return x + compactLabel.measureText(text)
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
        private const val SEP = "  ·  "
        private const val SPARK_POINTS = 36
        private const val COLOR_GOOD = 0xFF7ED48A.toInt()
        private const val COLOR_WARN = 0xFFFFC46B.toInt()
        private const val COLOR_BAD = 0xFFFF7B72.toInt()

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
