package com.merlin.fpscore

/** Receives every frame time the [FrameTracker] accepts (pauses over 1 s excluded). */
interface FrameSink {
    fun onFrame(frameTimeMs: Float)
}

/**
 * Whole-session statistics for the summary shown when the overlay stops.
 *
 * Frame times go into a histogram (0.25 ms bins), so average FPS and the 1% /
 * 0.1% lows cover every frame of a session of any length in fixed memory. A
 * per-second series (average and worst frame time) feeds the graph; when it
 * fills up, neighbouring points are merged so long sessions still fit.
 */
class SessionStats(private val maxPoints: Int = 600) : FrameSink {
    private val hist = IntArray(BINS + 1)
    private var frames = 0L
    private var sumMs = 0.0
    private var jank = 0
    private var p1 = 0f
    private var p2 = 0f
    private var p3 = 0f

    /** Vsync period used by the jank rule (same rule as [FrameTracker]). */
    var vsyncMs = 16.67f

    private val avgSeries = FloatArray(maxPoints)
    private val maxSeries = FloatArray(maxPoints)
    private var points = 0
    private var secondsPerPoint = 1
    private var bucketSum = 0.0
    private var bucketN = 0
    private var bucketMax = 0f
    private var bucketSeconds = 0

    private var startMs = -1L
    private var lastTickMs = -1L
    private var activeMs = 0L
    private var throttledMs = 0L
    private var capKnown = false
    private var peakTemp = Float.NaN

    val frameCount: Long get() = frames

    override fun onFrame(frameTimeMs: Float) {
        val ft = frameTimeMs
        if (ft <= 0f) return
        frames++
        sumMs += ft
        val bin = (ft / BIN_MS).toInt()
        hist[if (bin >= BINS) BINS else bin]++
        if (p3 > 0f) {
            val avg3 = (p1 + p2 + p3) / 3f
            if (ft > 2f * avg3 && ft > 2f * vsyncMs) jank++
        }
        p3 = p2; p2 = p1; p1 = ft
        bucketSum += ft
        bucketN++
        if (ft > bucketMax) bucketMax = ft
    }

    /**
     * Call about once per sampler tick while the app is being measured.
     * @param nowMs monotonic clock
     * @param capReadable whether CPU limits can be read on this phone at all
     */
    fun tick(nowMs: Long, socCelsius: Float, cpuCapped: Boolean, capReadable: Boolean) {
        if (startMs < 0) {
            startMs = nowMs
            lastTickMs = nowMs
            return
        }
        val dt = nowMs - lastTickMs
        lastTickMs = nowMs
        if (dt <= 0 || dt > 5000) return // paused (screen off, overlay hidden)
        activeMs += dt
        if (capReadable) capKnown = true
        if (cpuCapped) throttledMs += dt
        if (!socCelsius.isNaN() && (peakTemp.isNaN() || socCelsius > peakTemp)) peakTemp = socCelsius
        // Close a graph bucket every `secondsPerPoint` seconds of measured time.
        val sec = (activeMs / 1000).toInt()
        if (sec > bucketSeconds) {
            bucketSeconds = sec
            if (sec % secondsPerPoint == 0) closeBucket()
        }
    }

    private fun closeBucket() {
        if (bucketN == 0) return
        if (points == maxPoints) {
            // Merge pairs: half the resolution, twice the time span.
            val half = maxPoints / 2
            for (i in 0 until half) {
                avgSeries[i] = (avgSeries[2 * i] + avgSeries[2 * i + 1]) / 2f
                maxSeries[i] = maxOf(maxSeries[2 * i], maxSeries[2 * i + 1])
            }
            points = half
            secondsPerPoint *= 2
        }
        avgSeries[points] = (bucketSum / bucketN).toFloat()
        maxSeries[points] = bucketMax
        points++
        bucketSum = 0.0; bucketN = 0; bucketMax = 0f
    }

    /** FPS equivalent of the mean of the slowest [fraction] of frames, from the histogram. */
    private fun lowFps(fraction: Double): Float {
        if (frames == 0L) return 0f
        var need = maxOf(1L, Math.ceil(frames * fraction).toLong())
        val total = need
        var sum = 0.0
        var bin = BINS
        while (bin >= 0 && need > 0) {
            val c = hist[bin].toLong()
            if (c > 0) {
                val take = if (c < need) c else need
                sum += take * (bin + 0.5) * BIN_MS
                need -= take
            }
            bin--
        }
        return (1000.0 / (sum / total)).toFloat()
    }

    fun summary(pkg: String, refreshHz: Float, endedAtWallMs: Long): SessionSummary {
        val n = points
        return SessionSummary(
            pkg = pkg,
            endedAtWallMs = endedAtWallMs,
            durationMs = activeMs,
            frames = frames,
            avgFps = if (sumMs > 0) (frames * 1000.0 / sumMs).toFloat() else 0f,
            low1Fps = lowFps(0.01),
            low01Fps = lowFps(0.001),
            jank = jank,
            peakTempC = peakTemp,
            throttledMs = if (capKnown) throttledMs else -1L,
            refreshHz = refreshHz,
            secondsPerPoint = secondsPerPoint,
            avgFrameMs = avgSeries.copyOf(n),
            maxFrameMs = maxSeries.copyOf(n),
        )
    }

    companion object {
        const val BIN_MS = 0.25f
        const val BINS = 1000 // up to 250 ms; slower frames share the last bin
    }
}

/** Result of one overlay session; plain data that can be saved and reloaded as text. */
class SessionSummary(
    val pkg: String,
    val endedAtWallMs: Long,
    val durationMs: Long,
    val frames: Long,
    val avgFps: Float,
    val low1Fps: Float,
    val low01Fps: Float,
    val jank: Int,
    /** NaN when the phone reported no temperature. */
    val peakTempC: Float,
    /** -1 when CPU limits are not readable on this phone. */
    val throttledMs: Long,
    val refreshHz: Float,
    val secondsPerPoint: Int,
    val avgFrameMs: FloatArray,
    val maxFrameMs: FloatArray,
) {
    fun encode(): String = buildString {
        append("v=1\n")
        append("pkg=").append(pkg).append('\n')
        append("ended=").append(endedAtWallMs).append('\n')
        append("duration=").append(durationMs).append('\n')
        append("frames=").append(frames).append('\n')
        append("avg=").append(avgFps).append('\n')
        append("low1=").append(low1Fps).append('\n')
        append("low01=").append(low01Fps).append('\n')
        append("jank=").append(jank).append('\n')
        append("peak=").append(peakTempC).append('\n')
        append("throttled=").append(throttledMs).append('\n')
        append("refresh=").append(refreshHz).append('\n')
        append("spp=").append(secondsPerPoint).append('\n')
        append("avgSeries=").append(avgFrameMs.joinToString(",")).append('\n')
        append("maxSeries=").append(maxFrameMs.joinToString(",")).append('\n')
    }

    companion object {
        fun decode(text: String): SessionSummary? {
            val m = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
            return try {
                fun floats(k: String) = m[k].orEmpty().split(',').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
                SessionSummary(
                    pkg = m["pkg"] ?: return null,
                    endedAtWallMs = m["ended"]!!.toLong(),
                    durationMs = m["duration"]!!.toLong(),
                    frames = m["frames"]!!.toLong(),
                    avgFps = m["avg"]!!.toFloat(),
                    low1Fps = m["low1"]!!.toFloat(),
                    low01Fps = m["low01"]!!.toFloat(),
                    jank = m["jank"]!!.toInt(),
                    peakTempC = m["peak"]!!.toFloat(),
                    throttledMs = m["throttled"]!!.toLong(),
                    refreshHz = m["refresh"]!!.toFloat(),
                    secondsPerPoint = m["spp"]!!.toInt(),
                    avgFrameMs = floats("avgSeries"),
                    maxFrameMs = floats("maxSeries"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
