package com.merlin.fpslite

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Operations available in the privileged helper process (shell uid via Shizuku,
 * or root). Implemented by [PrivImpl] inside the helper and by [PrivClient]
 * (pipe-based proxy) inside the app.
 */
interface PrivApi {
    /** Like `dumpsys <service> <args>` but via IBinder.dump() — no process per call. */
    fun dump(service: String, args: Array<String>): String?
    /** Reads a sysfs/procfs file via a cached, re-seeked descriptor. null if unreadable. */
    fun readText(path: String): String?
    fun readTexts(paths: Array<String>): Array<String?>
    fun listDir(path: String): Array<String>
    /** Runs a shell command (Device probe only). */
    fun exec(cmd: String): String?
    fun gpuStart(): Boolean
    /** [status(1 data, 0 waiting, -1 unavailable), avgActiveFreqKHz, busyPermille, lastFreqKHz, events] */
    fun gpuSample(): LongArray
    fun gpuStop()
    fun gpuLog(): String
    fun uid(): Int
    fun exit()
}

/** Tiny length-prefixed wire format shared by both ends of the pipe. */
internal object Wire {
    const val MAGIC = 0x46505331 // "FPS1"

    const val OP_EXIT = 0
    const val OP_DUMP = 1
    const val OP_READ = 2
    const val OP_READ_MANY = 3
    const val OP_LIST = 4
    const val OP_EXEC = 5
    const val OP_GPU_START = 6
    const val OP_GPU_SAMPLE = 7
    const val OP_GPU_STOP = 8
    const val OP_GPU_LOG = 9
    const val OP_UID = 10

    const val OK = 0
    const val ERR = 1

    fun writeStr(o: DataOutputStream, s: String?) {
        if (s == null) {
            o.writeInt(-1)
        } else {
            val b = s.toByteArray()
            o.writeInt(b.size)
            o.write(b)
        }
    }

    fun readStr(i: DataInputStream): String? {
        val n = i.readInt()
        if (n < 0) return null
        val b = ByteArray(n)
        i.readFully(b)
        return String(b)
    }

    fun writeStrArr(o: DataOutputStream, a: Array<out String?>) {
        o.writeInt(a.size)
        for (s in a) writeStr(o, s)
    }

    fun readStrArr(i: DataInputStream): Array<String?> {
        val n = i.readInt()
        return Array(n) { readStr(i) }
    }
}
