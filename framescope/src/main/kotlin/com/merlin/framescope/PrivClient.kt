package com.merlin.framescope

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * App-side proxy for [PrivApi] that talks to the helper process over its
 * stdin/stdout. Calls are serialized; each is one request/response round trip
 * over a local pipe (no binder transaction through Shizuku per call).
 */
class PrivClient(private val proc: Process) : PrivApi {
    private val out = DataOutputStream(BufferedOutputStream(proc.outputStream, 8 * 1024))
    private val inp = DataInputStream(BufferedInputStream(proc.inputStream, 64 * 1024))
    private val lock = Any()
    @Volatile var dead = false
        private set
    var helperUid = -1
        private set

    /** Waits for the helper's magic header, skipping any stray output before it. */
    fun handshake(): Boolean {
        var window = 0
        var skipped = 0
        while (skipped < 64 * 1024) {
            val b = inp.read()
            if (b < 0) return false
            window = (window shl 8) or b
            if (window == Wire.MAGIC) {
                helperUid = inp.readInt()
                return true
            }
            skipped++
        }
        return false
    }

    private inline fun <T> call(op: Int, writeArgs: (DataOutputStream) -> Unit, read: (DataInputStream) -> T): T =
        synchronized(lock) {
            if (dead) throw IOException("helper process is gone")
            try {
                out.writeInt(op)
                writeArgs(out)
                out.flush()
                val status = inp.readUnsignedByte()
                if (status != Wire.OK) throw HelperError(Wire.readStr(inp) ?: "helper error")
                read(inp)
            } catch (e: IOException) {
                // Broken pipe / EOF: the helper died. Priv will reconnect.
                dead = true
                throw e
            }
        }

    /** The helper reported a failure for one request; the connection is still fine. */
    class HelperError(msg: String) : RuntimeException(msg)

    override fun dump(service: String, args: Array<String>): String? =
        call(Wire.OP_DUMP, { Wire.writeStr(it, service); Wire.writeStrArr(it, args) }) { Wire.readStr(it) }

    override fun readText(path: String): String? =
        call(Wire.OP_READ, { Wire.writeStr(it, path) }) { Wire.readStr(it) }

    override fun readTexts(paths: Array<String>): Array<String?> =
        call(Wire.OP_READ_MANY, { Wire.writeStrArr(it, paths) }) { Wire.readStrArr(it) }

    override fun listDir(path: String): Array<String> =
        call(Wire.OP_LIST, { Wire.writeStr(it, path) }) { i -> Wire.readStrArr(i).map { it ?: "" }.toTypedArray() }

    override fun exec(cmd: String): String? =
        call(Wire.OP_EXEC, { Wire.writeStr(it, cmd) }) { Wire.readStr(it) }

    override fun gpuStart(): Boolean = call(Wire.OP_GPU_START, {}) { it.readBoolean() }

    override fun gpuSample(): LongArray = call(Wire.OP_GPU_SAMPLE, {}) { i ->
        val n = i.readInt()
        LongArray(n) { i.readLong() }
    }

    override fun gpuStop() = call(Wire.OP_GPU_STOP, {}) { }

    override fun gpuLog(): String = call(Wire.OP_GPU_LOG, {}) { Wire.readStr(it) ?: "" }

    override fun uid(): Int = call(Wire.OP_UID, {}) { it.readInt() }

    override fun exit() {
        synchronized(lock) {
            try {
                out.writeInt(Wire.OP_EXIT)
                out.flush()
            } catch (_: Throwable) {}
            dead = true
        }
        try { proc.destroy() } catch (_: Throwable) {}
    }

    fun markDead() {
        dead = true
    }
}
