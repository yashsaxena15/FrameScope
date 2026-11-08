package com.merlin.fpslite

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.merlin.fpscore.GpuAggregator
import com.merlin.fpscore.GpuTraceParser
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs inside the privileged helper process (see [PrivMain]) with shell (or root)
 * privileges. Kept deliberately small: it only fetches raw data; FPS parsing
 * happens in the app process. GPU trace parsing happens here because the trace
 * file is only readable by the shell user.
 */
class PrivImpl : PrivApi {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "priv-io").apply { isDaemon = true } }
    private val binders = ConcurrentHashMap<String, IBinder>()

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun exit() {
        try { gpuStop() } catch (_: Throwable) {}
    }

    override fun uid(): Int = android.os.Process.myUid()

    // ── dumpsys via direct binder dump ───────────────────────────────────────

    private fun binder(name: String): IBinder? {
        binders[name]?.let { if (it.isBinderAlive) return it }
        val b = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, name) as IBinder?
        } catch (_: Throwable) {
            null
        }
        if (b != null) binders[name] = b else binders.remove(name)
        return b
    }

    override fun dump(service: String, args: Array<String>): String? {
        val a = args
        val b = binder(service) ?: return runDumpsys(service, a)
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (_: Throwable) { return runDumpsys(service, a) }
        val reader = pool.submit(Callable {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { String(it.readBytes()) }
        })
        try {
            b.dump(pipe[1].fileDescriptor, a)
        } catch (_: Throwable) {
            binders.remove(service)
        } finally {
            try { pipe[1].close() } catch (_: Throwable) {}
        }
        return try {
            reader.get(5, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            reader.cancel(true)
            null
        }
    }

    private fun runDumpsys(service: String, args: Array<String>): String? =
        runProcess(listOf("dumpsys", service) + args, 8)

    // ── files ────────────────────────────────────────────────────────────────

    private val files = HashMap<String, RandomAccessFile>()
    private val fileBuf = ByteArray(128 * 1024)

    override fun readText(path: String): String? = synchronized(files) { readLocked(path) }

    override fun readTexts(paths: Array<String>): Array<String?> = synchronized(files) {
        Array(paths.size) { readLocked(paths[it]) }
    }

    private fun readLocked(path: String): String? {
        repeat(2) {
            val raf = files[path] ?: try {
                RandomAccessFile(path, "r").also { files[path] = it }
            } catch (_: Throwable) {
                return null
            }
            try {
                raf.seek(0)
                var total = 0
                while (total < fileBuf.size) {
                    val r = raf.read(fileBuf, total, fileBuf.size - total)
                    if (r <= 0) break
                    total += r
                }
                return String(fileBuf, 0, total)
            } catch (_: Throwable) {
                try { raf.close() } catch (_: Throwable) {}
                files.remove(path)
            }
        }
        return null
    }

    override fun listDir(path: String): Array<String> = File(path).list()?.sortedArray() ?: emptyArray()

    // ── shell (probe screen only) ────────────────────────────────────────────

    override fun exec(cmd: String): String? = runProcess(listOf("sh", "-c", cmd), 15)

    private fun runProcess(cmd: List<String>, timeoutSec: Long): String? = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.outputStream.close()
        val out = pool.submit(Callable { p.inputStream.use { String(it.readBytes()) } })
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroy()
        out.get(2, TimeUnit.SECONDS)
    } catch (t: Throwable) {
        "error: ${t.message}"
    }

    // ── GPU via perfetto ftrace ──────────────────────────────────────────────

    private val gpuLock = Any()
    private var perfetto: Process? = null
    private var traceRaf: RandomAccessFile? = null
    private var traceOffset = 0L
    private var pending = ByteArray(256 * 1024)
    private var pendingLen = 0
    private var agg = GpuAggregator()
    private var parser = GpuTraceParser(agg)
    private var restarts = 0
    @Volatile private var log = ""

    override fun gpuStart(): Boolean = synchronized(gpuLock) {
        restarts = 0
        startLocked()
    }

    private fun startLocked(): Boolean {
        stopLocked()
        runProcess(listOf("sh", "-c", "pkill -f $TRACE_NAME"), 3) // stale session from a previous run
        File(TRACE).delete()
        val p = try {
            ProcessBuilder("perfetto", "--txt", "-c", "-", "-o", TRACE).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            log = "perfetto start failed: ${t.message}"
            return false
        }
        try {
            p.outputStream.use { it.write(PERFETTO_CONFIG.toByteArray()) }
        } catch (_: Throwable) {}
        pool.execute {
            try {
                p.inputStream.use { s ->
                    val b = ByteArray(1024)
                    val sb = StringBuilder()
                    while (true) {
                        val r = s.read(b)
                        if (r < 0) break
                        if (sb.length < 4000) sb.append(String(b, 0, r))
                    }
                    log = sb.toString()
                }
            } catch (_: Throwable) {}
        }
        perfetto = p
        agg = GpuAggregator()
        parser = GpuTraceParser(agg)
        traceOffset = 0
        pendingLen = 0
        return true
    }

    override fun gpuSample(): LongArray = synchronized(gpuLock) {
        val p = perfetto ?: return@synchronized longArrayOf(-1, -1, -1, -1, 0)
        if (!p.isAlive) {
            // Session ended (duration / max size reached) or perfetto was refused.
            if (agg.events == 0 || ++restarts > 20) {
                return@synchronized longArrayOf(-1, -1, -1, -1, 0)
            }
            startLocked()
            return@synchronized longArrayOf(0, -1, -1, -1, 0)
        }
        try { readNewLocked() } catch (_: Throwable) {}
        val out = LongArray(2)
        agg.take(out)
        longArrayOf(if (agg.events > 0) 1 else 0, out[0], out[1], agg.lastFreqKHz, agg.events.toLong())
    }

    private fun readNewLocked() {
        val raf = traceRaf ?: try {
            RandomAccessFile(TRACE, "r").also { traceRaf = it }
        } catch (_: Throwable) {
            return
        }
        val len = raf.length()
        if (len < traceOffset) { // file was replaced
            traceOffset = 0
            pendingLen = 0
        }
        while (traceOffset < len) {
            if (pendingLen == pending.size) pending = pending.copyOf(pending.size * 2)
            raf.seek(traceOffset)
            val r = raf.read(pending, pendingLen, pending.size - pendingLen)
            if (r <= 0) break
            pendingLen += r
            traceOffset += r
            val consumed = parser.parse(pending, pendingLen)
            if (consumed > 0) {
                System.arraycopy(pending, consumed, pending, 0, pendingLen - consumed)
                pendingLen -= consumed
            }
        }
    }

    override fun gpuStop() {
        synchronized(gpuLock) { stopLocked() }
    }

    private fun stopLocked() {
        perfetto?.let {
            try { it.destroy() } catch (_: Throwable) {}
            try { it.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
        }
        perfetto = null
        try { traceRaf?.close() } catch (_: Throwable) {}
        traceRaf = null
        File(TRACE).delete()
    }

    override fun gpuLog(): String = log

    companion object {
        private const val TRACE_NAME = "fpslite_gpu.pftrace"
        private const val TRACE = "/data/misc/perfetto-traces/$TRACE_NAME"

        // Long-running ring buffer, flushed to file twice a second; the file is
        // read incrementally. Max 1 h / 32 MB, then it is restarted automatically.
        private val PERFETTO_CONFIG = """
            buffers { size_kb: 2048 fill_policy: RING_BUFFER }
            data_sources { config { name: "linux.ftrace" ftrace_config {
              ftrace_events: "power/gpu_frequency"
              ftrace_events: "power/gpu_work_period"
              drain_period_ms: 250
            } } }
            write_into_file: true
            file_write_period_ms: 500
            flush_period_ms: 1000
            max_file_size_bytes: 33554432
            duration_ms: 3600000
        """.trimIndent()
    }
}
