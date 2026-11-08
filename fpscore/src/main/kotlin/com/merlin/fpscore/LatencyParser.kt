package com.merlin.fpscore

/**
 * Parses `dumpsys SurfaceFlinger --latency "<layer>"` without allocating per line.
 *
 * Output format (Android 10–16):
 * ```
 * <refresh period ns>
 * <desiredPresentTime> <actualPresentTime> <frameReadyTime>   (×127, oldest first)
 * ```
 * Column 2 (actualPresentTime) is when the frame actually reached the display —
 * that is what "FPS" means. Pending frames report INT64_MAX, empty slots report 0.
 * (Verified on HyperOS 3 / Android 16: column 3 is earlier than column 2, i.e. it
 * is the GPU-ready fence, not the present time.)
 */
class LatencyParser {
    /** Refresh period from the header line, in ns (0 if absent). */
    var refreshPeriodNs: Long = 0L
        private set

    /**
     * Parses [text] and writes valid actual-present timestamps (ascending) into [out].
     * @return number of timestamps written.
     */
    fun parse(text: CharSequence, out: LongArray): Int {
        refreshPeriodNs = 0L
        val n = text.length
        var i = 0
        var headerDone = false
        var count = 0
        while (i < n) {
            var nums = 0
            var a = 0L
            var b = 0L
            while (i < n && text[i] != '\n') {
                val ch = text[i]
                if (ch in '0'..'9') {
                    var v = 0L
                    var overflow = false
                    while (i < n && text[i] in '0'..'9') {
                        val d = text[i] - '0'
                        if (v > (Long.MAX_VALUE - d) / 10) overflow = true else v = v * 10 + d
                        i++
                    }
                    if (overflow) v = Long.MAX_VALUE
                    when (nums) {
                        0 -> a = v
                        1 -> b = v
                    }
                    nums++
                } else {
                    i++
                }
            }
            i++ // skip '\n'
            if (nums == 0) continue
            if (!headerDone) {
                headerDone = true
                if (nums == 1) {
                    refreshPeriodNs = a
                    continue
                }
            }
            if (nums >= 3 && b != 0L && b != Long.MAX_VALUE && count < out.size) {
                out[count++] = b
            }
        }
        // Rows are already chronological; insertion sort is O(n) for sorted input
        // and guards against any reordering.
        for (k in 1 until count) {
            val v = out[k]
            var j = k - 1
            while (j >= 0 && out[j] > v) {
                out[j + 1] = out[j]
                j--
            }
            out[j + 1] = v
        }
        return count
    }
}
