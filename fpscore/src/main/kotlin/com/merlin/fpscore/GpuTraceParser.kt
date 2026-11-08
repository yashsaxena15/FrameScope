package com.merlin.fpscore

/**
 * Minimal, allocation-light reader for a Perfetto trace file that is being written
 * incrementally (`write_into_file: true`). Extracts only two ftrace events:
 *
 *  - power/gpu_frequency   (FtraceEvent field 332: {1 gpu_id, 2 state kHz})
 *  - power/gpu_work_period (FtraceEvent field 488: {1 gpu_id, 2 uid, 3 start_ns,
 *                                                  4 end_ns, 5 total_active_ns})
 *
 * Proto path: Trace.packet(1) → TracePacket.ftrace_events(1) →
 *             FtraceEventBundle.event(2) → FtraceEvent{timestamp(1), …}.
 * Field numbers are stable in Perfetto's protos (append-only).
 *
 * The GPU sysfs nodes on MediaTek Mali (e.g. Dimensity 9500s) are SELinux-blocked
 * for the shell user, but these tracepoints are allowed via perfetto.
 */
class GpuTraceParser(private val sink: Sink) {
    interface Sink {
        fun onGpuFrequency(tsNs: Long, gpuId: Int, kHz: Long)
        fun onGpuWorkPeriod(gpuId: Int, uid: Int, startNs: Long, endNs: Long, activeNs: Long)
    }

    /**
     * Parses as many complete top-level packets as possible from buf[0, len).
     * @return number of bytes consumed; the caller keeps the remainder for next time.
     */
    fun parse(buf: ByteArray, len: Int): Int {
        var pos = 0
        while (pos < len) {
            val start = pos
            val tag = readVarint(buf, pos, len) ?: return start
            pos = vEnd
            val wire = (tag and 7L).toInt()
            val field = (tag ushr 3).toInt()
            if (wire != 2) {
                // Unexpected at top level; try to skip.
                pos = skip(buf, pos, len, wire) ?: return start
                continue
            }
            val l = readVarint(buf, pos, len) ?: return start
            pos = vEnd
            if (l < 0 || pos + l > len) return start // incomplete packet
            if (field == 1) parsePacket(buf, pos, pos + l.toInt())
            pos += l.toInt()
        }
        return pos
    }

    private fun parsePacket(b: ByteArray, from: Int, to: Int) {
        forEachField(b, from, to) { f, w, s, e ->
            if (f == 1 && w == 2) parseBundle(b, s, e)
        }
    }

    private fun parseBundle(b: ByteArray, from: Int, to: Int) {
        forEachField(b, from, to) { f, w, s, e ->
            if (f == 2 && w == 2) parseEvent(b, s, e)
        }
    }

    private fun parseEvent(b: ByteArray, from: Int, to: Int) {
        var ts = 0L
        var payloadField = 0
        var ps = 0
        var pe = 0
        forEachField(b, from, to) { f, w, s, e ->
            when {
                f == 1 && w == 0 -> ts = varintValue
                (f == FIELD_GPU_FREQUENCY || f == FIELD_GPU_WORK_PERIOD) && w == 2 -> {
                    payloadField = f; ps = s; pe = e
                }
            }
        }
        if (payloadField == FIELD_GPU_FREQUENCY) {
            var gpuId = 0L
            var state = 0L
            forEachField(b, ps, pe) { f, w, _, _ ->
                if (w == 0) when (f) { 1 -> gpuId = varintValue; 2 -> state = varintValue }
            }
            sink.onGpuFrequency(ts, gpuId.toInt(), state)
        } else if (payloadField == FIELD_GPU_WORK_PERIOD) {
            var gpuId = 0L; var uid = 0L; var st = 0L; var en = 0L; var act = 0L
            forEachField(b, ps, pe) { f, w, _, _ ->
                if (w == 0) when (f) {
                    1 -> gpuId = varintValue
                    2 -> uid = varintValue
                    3 -> st = varintValue
                    4 -> en = varintValue
                    5 -> act = varintValue
                }
            }
            sink.onGpuWorkPeriod(gpuId.toInt(), uid.toInt(), st, en, act)
        }
    }

    // ── tiny protobuf helpers (single-threaded use) ──────────────────────────
    private var vEnd = 0
    private var varintValue = 0L

    private fun readVarint(b: ByteArray, pos0: Int, limit: Int): Long? {
        var pos = pos0
        var result = 0L
        var shift = 0
        while (pos < limit && shift < 64) {
            val c = b[pos++].toInt() and 0xff
            result = result or ((c and 0x7f).toLong() shl shift)
            if (c < 0x80) {
                vEnd = pos
                return result
            }
            shift += 7
        }
        return null
    }

    private fun skip(b: ByteArray, pos: Int, limit: Int, wire: Int): Int? = when (wire) {
        0 -> if (readVarint(b, pos, limit) != null) vEnd else null
        1 -> if (pos + 8 <= limit) pos + 8 else null
        5 -> if (pos + 4 <= limit) pos + 4 else null
        2 -> readVarint(b, pos, limit)?.let { l -> val e = vEnd + l.toInt(); if (l >= 0 && e <= limit) e else null }
        else -> null
    }

    /** Calls [fn](field, wireType, valueStart, valueEnd); for varints [varintValue] holds the value. */
    private inline fun forEachField(b: ByteArray, from: Int, to: Int, fn: (Int, Int, Int, Int) -> Unit) {
        var pos = from
        while (pos < to) {
            val tag = readVarint(b, pos, to) ?: return
            pos = vEnd
            val wire = (tag and 7L).toInt()
            val field = (tag ushr 3).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(b, pos, to) ?: return
                    pos = vEnd
                    varintValue = v
                    fn(field, 0, pos, pos)
                }
                2 -> {
                    val l = readVarint(b, pos, to) ?: return
                    val s = vEnd
                    val e = s + l.toInt()
                    if (l < 0 || e > to) return
                    fn(field, 2, s, e)
                    pos = e
                }
                1 -> { if (pos + 8 > to) return; fn(field, 1, pos, pos + 8); pos += 8 }
                5 -> { if (pos + 4 > to) return; fn(field, 5, pos, pos + 4); pos += 4 }
                else -> return
            }
        }
    }

    companion object {
        const val FIELD_GPU_FREQUENCY = 332
        const val FIELD_GPU_WORK_PERIOD = 488
    }
}

/**
 * Aggregates parsed GPU events into numbers for display.
 * Frequency: time-weighted average over the events since the last [take], counting
 * only "active" states (≥ [activeThresholdKHz]); the MediaTek driver reports a
 * 26 MHz state while the GPU is idle/power-collapsed, which would otherwise drag
 * the average down meaninglessly.
 * Load: Σ active time of all work periods ÷ the time span they cover.
 */
class GpuAggregator(private val activeThresholdKHz: Long = 100_000L) : GpuTraceParser.Sink {
    private var lastTs = 0L
    private var lastState = 0L
    private var weighted = 0.0
    private var weightedTime = 0L
    var lastFreqKHz = 0L
        private set
    var maxSeenKHz = 0L
        private set

    private var activeSum = 0L
    private var spanStart = Long.MAX_VALUE
    private var spanEnd = Long.MIN_VALUE
    var events = 0
        private set

    override fun onGpuFrequency(tsNs: Long, gpuId: Int, kHz: Long) {
        events++
        if (lastTs != 0L && tsNs > lastTs && lastState >= activeThresholdKHz) {
            val dt = tsNs - lastTs
            weighted += lastState.toDouble() * dt
            weightedTime += dt
        }
        lastTs = tsNs
        lastState = kHz
        lastFreqKHz = kHz
        if (kHz > maxSeenKHz) maxSeenKHz = kHz
    }

    override fun onGpuWorkPeriod(gpuId: Int, uid: Int, startNs: Long, endNs: Long, activeNs: Long) {
        events++
        if (endNs <= startNs) return
        activeSum += activeNs
        if (startNs < spanStart) spanStart = startNs
        if (endNs > spanEnd) spanEnd = endNs
    }

    /**
     * Returns [avgActiveFreqKHz, busyPermille] for the data since the previous call
     * and resets the window. -1 means "no data in this window".
     */
    fun take(out: LongArray) {
        out[0] = when {
            weightedTime > 0 -> (weighted / weightedTime).toLong()
            lastState >= activeThresholdKHz -> lastState
            lastTs != 0L -> 0L // idle the whole window
            else -> -1L
        }
        out[1] = if (spanEnd > spanStart) ((activeSum * 1000) / (spanEnd - spanStart)).coerceIn(0, 1000) else -1L
        weighted = 0.0; weightedTime = 0L
        activeSum = 0L; spanStart = Long.MAX_VALUE; spanEnd = Long.MIN_VALUE
    }
}
