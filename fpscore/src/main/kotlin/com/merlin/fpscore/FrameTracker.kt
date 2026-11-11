package com.merlin.fpscore

/**
 * Accumulates actual-present timestamps from successive `--latency` dumps and
 * derives FPS statistics.
 *
 * SurfaceFlinger keeps only the last ~127 frames (≈1.06 s at 120 FPS), so the
 * caller must poll faster than that. Each frame is counted exactly once by only
 * accepting timestamps newer than the last one seen. If a dump does not overlap
 * the previous one while its buffer is full, frames were lost — [gapCount] counts
 * those events so the UI can warn that polling is too slow.
 *
 * All timestamps are CLOCK_MONOTONIC ns (same clock as System.nanoTime()).
 * Nothing here allocates after construction.
 */
class FrameTracker(capacity: Int = 4096) {
    private val ts = LongArray(capacity)
    private var head = 0 // index of next write
    private var size = 0
    private val sortBuf = FloatArray(capacity)

    var lastTimestamp: Long = 0L
        private set

    /** Polls where frames were probably lost (no overlap with the previous dump). */
    var gapCount: Int = 0
        private set

    /** Session counters since the last [reset] (i.e. since the layer/app changed). */
    var sessionFrames: Long = 0L
        private set
    var sessionJank: Int = 0
        private set
    var sessionBigJank: Int = 0
        private set

    /** Optional receiver of every accepted frame time (session recording). */
    var sink: FrameSink? = null

    /** Vsync period used for jank thresholds; update from the --latency header. */
    var vsyncPeriodNs: Long = 16_666_667L

    /** Frames at or before this timestamp are excluded from stats (set by [resetSession]). */
    private var statsFromNs = 0L

    private var prev1 = 0f
    private var prev2 = 0f
    private var prev3 = 0f

    fun reset() {
        head = 0
        size = 0
        lastTimestamp = 0L
        gapCount = 0
        sessionFrames = 0
        sessionJank = 0
        sessionBigJank = 0
        statsFromNs = 0L
        prev1 = 0f; prev2 = 0f; prev3 = 0f
    }

    /**
     * Starts a fresh measurement (e.g. at the start of a match) without losing
     * the frame history: jank counters and avg / 1% / 0.1% lows only consider
     * frames presented after this call. Current FPS and the graph are unaffected.
     */
    fun resetSession() {
        sessionFrames = 0
        sessionJank = 0
        sessionBigJank = 0
        gapCount = 0
        statsFromNs = lastTimestamp
        prev1 = 0f; prev2 = 0f; prev3 = 0f
    }

    val frameCount: Int get() = size

    /**
     * Feed one dump's timestamps (ascending, as produced by [LatencyParser]).
     * @return number of frames that were new.
     */
    fun add(present: LongArray, count: Int): Int {
        if (count == 0) return 0
        if (lastTimestamp != 0L && present[0] > lastTimestamp && count >= FULL_BUFFER_THRESHOLD) {
            gapCount++
        }
        var added = 0
        for (k in 0 until count) {
            val t = present[k]
            if (t <= lastTimestamp) continue
            if (lastTimestamp != 0L) {
                val ftMs = (t - lastTimestamp) / 1_000_000f
                if (ftMs < PAUSE_MS) {
                    trackJank(ftMs)
                    sink?.onFrame(ftMs)
                }
            }
            ts[head] = t
            head = (head + 1) % ts.size
            if (size < ts.size) size++
            lastTimestamp = t
            added++
        }
        sessionFrames += added
        return added
    }

    /**
     * Jank definition (similar in spirit to PerfDog): a frame is janky when it took
     * more than twice the average of the previous three frames AND more than two
     * vsync periods. "Big jank" additionally exceeds 3× and 125 ms-equivalent of 3 movie frames.
     */
    private fun trackJank(ftMs: Float) {
        if (prev3 > 0f) {
            val avg3 = (prev1 + prev2 + prev3) / 3f
            val vsyncMs = vsyncPeriodNs / 1_000_000f
            if (ftMs > 2f * avg3 && ftMs > 2f * vsyncMs) {
                sessionJank++
                if (ftMs > 3f * avg3 && ftMs > BIG_JANK_MS) sessionBigJank++
            }
        }
        prev3 = prev2; prev2 = prev1; prev1 = ftMs
    }

    private fun at(iFromOldest: Int): Long {
        val start = (head - size + ts.size) % ts.size
        return ts[(start + iFromOldest) % ts.size]
    }

    /**
     * Current FPS over the last [windowNs] ending at [nowNs].
     * If frames are still arriving, uses the exact span between the first and last
     * frame in the window (smooth, sub-integer precision). If rendering stalled,
     * falls back to frames / window so the value decays to 0.
     */
    fun currentFps(nowNs: Long, windowNs: Long): Float {
        if (size == 0) return 0f
        val from = nowNs - windowNs
        var n = 0
        var oldest = 0L
        var i = size - 1
        while (i >= 0) {
            val t = at(i)
            if (t <= from) break
            oldest = t
            n++
            i--
        }
        if (n == 0) return 0f
        val newest = at(size - 1)
        if (n >= 3) {
            val span = newest - oldest
            if (span > 0) {
                val avgInterval = span / (n - 1)
                val sinceLast = nowNs - newest
                if (sinceLast <= maxOf(3 * avgInterval, STALL_GRACE_NS)) {
                    return (n - 1) * 1e9f / span
                }
            }
        }
        return n * 1e9f / windowNs
    }

    /**
     * Statistics over the frames in the buffer that fall within the last
     * [windowNs] (pauses longer than 1 s are excluded from frame times).
     */
    fun computeStats(nowNs: Long, windowNs: Long, out: FpsStats) {
        out.clear()
        if (size < 2) return
        val from = maxOf(nowNs - windowNs, statsFromNs)
        var n = 0
        var sumMs = 0.0
        var maxMs = 0f
        for (k in 1 until size) {
            val t0 = at(k - 1)
            if (t0 <= from) continue
            val ftMs = (at(k) - t0) / 1_000_000f
            if (ftMs >= PAUSE_MS) continue
            sortBuf[n++] = ftMs
            sumMs += ftMs
            if (ftMs > maxMs) maxMs = ftMs
        }
        if (n == 0) return
        val avgMs = (sumMs / n).toFloat()
        java.util.Arrays.sort(sortBuf, 0, n)
        out.frames = n
        out.avgFps = 1000f / avgMs
        out.avgFrameTimeMs = avgMs
        out.maxFrameTimeMs = maxMs
        out.low1Fps = 1000f / worstMean(n, 0.01f)
        out.low01Fps = 1000f / worstMean(n, 0.001f)
        out.jank = sessionJank
        out.bigJank = sessionBigJank
    }

    /**
     * "1% low" = FPS equivalent of the mean of the slowest [fraction] of frame times
     * (at least one frame). Unlike a plain 99th percentile this cannot miss hitches
     * that make up exactly 1 % of frames. [sortBuf] must be sorted ascending.
     */
    private fun worstMean(n: Int, fraction: Float): Float {
        val k = maxOf(1, kotlin.math.ceil(n * fraction.toDouble()).toInt())
        var sum = 0.0
        for (i in n - k until n) sum += sortBuf[i]
        return (sum / k).toFloat()
    }

    /** Copies the most recent frame times (ms, oldest→newest) into [out]; returns count. */
    fun recentFrameTimes(out: FloatArray): Int {
        val want = minOf(out.size, size - 1)
        if (want <= 0) return 0
        val startK = size - want
        for (j in 0 until want) {
            val k = startK + j
            out[j] = (at(k) - at(k - 1)) / 1_000_000f
        }
        return want
    }

    companion object {
        const val PAUSE_MS = 1000f
        const val BIG_JANK_MS = 125f
        const val FULL_BUFFER_THRESHOLD = 120
        const val STALL_GRACE_NS = 50_000_000L
    }
}

class FpsStats {
    var frames = 0
    var avgFps = 0f
    var avgFrameTimeMs = 0f
    var maxFrameTimeMs = 0f
    var low1Fps = 0f
    var low01Fps = 0f
    var jank = 0
    var bigJank = 0

    fun clear() {
        frames = 0; avgFps = 0f; avgFrameTimeMs = 0f; maxFrameTimeMs = 0f
        low1Fps = 0f; low01Fps = 0f; jank = 0; bigJank = 0
    }
}
