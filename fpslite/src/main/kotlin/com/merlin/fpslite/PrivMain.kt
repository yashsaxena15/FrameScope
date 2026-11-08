package com.merlin.fpslite

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Entry point of the privileged helper, started by the app through Shizuku as
 *   CLASSPATH=<our apk> app_process /system/bin com.merlin.fpslite.PrivMain
 *
 * Why not Shizuku's UserService? On MediaTek firmware (e.g. Dimensity 9500s,
 * HyperOS 3) Shizuku 13.6's UserService starter crashes in a vendor-patched
 * LoadedApk.makeApplicationInner() (Shizuku issues #1198, #2561). A plain
 * app_process main never creates an Application, so it is unaffected.
 *
 * Protocol: stdin = requests, stdout = responses (see [Wire]); stderr = logs.
 * The process exits when stdin closes (app died / disconnected).
 */
object PrivMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val rawOut = FileOutputStream(FileDescriptor.out)
        // Anything printed by libraries must not corrupt the protocol stream.
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true))
        val out = DataOutputStream(BufferedOutputStream(rawOut, 64 * 1024))
        val inp = DataInputStream(BufferedInputStream(FileInputStream(FileDescriptor.`in`), 8 * 1024))
        val impl = PrivImpl()
        try {
            out.writeInt(Wire.MAGIC)
            out.writeInt(android.os.Process.myUid())
            out.flush()
            loop(inp, out, impl)
        } catch (_: Throwable) {
        } finally {
            try { impl.gpuStop() } catch (_: Throwable) {}
            exitProcess(0)
        }
    }

    private fun loop(inp: DataInputStream, out: DataOutputStream, impl: PrivImpl) {
        while (true) {
            val op = try { inp.readInt() } catch (_: EOFException) { return }
            if (op == Wire.OP_EXIT) return
            // 1) read all arguments, 2) compute, 3) write status + result.
            var result: ((DataOutputStream) -> Unit)? = null
            var error: String? = null
            try {
                when (op) {
                    Wire.OP_DUMP -> {
                        val service = Wire.readStr(inp) ?: ""
                        val a = Wire.readStrArr(inp).map { it ?: "" }.toTypedArray()
                        val r = runCatching { impl.dump(service, a) }
                        result = { o -> Wire.writeStr(o, r.getOrNull()) }
                    }
                    Wire.OP_READ -> {
                        val p = Wire.readStr(inp) ?: ""
                        val r = impl.readText(p)
                        result = { o -> Wire.writeStr(o, r) }
                    }
                    Wire.OP_READ_MANY -> {
                        val ps = Wire.readStrArr(inp).map { it ?: "" }.toTypedArray()
                        val r = impl.readTexts(ps)
                        result = { o -> Wire.writeStrArr(o, r) }
                    }
                    Wire.OP_LIST -> {
                        val p = Wire.readStr(inp) ?: ""
                        val r = impl.listDir(p)
                        result = { o -> Wire.writeStrArr(o, r) }
                    }
                    Wire.OP_EXEC -> {
                        val c = Wire.readStr(inp) ?: ""
                        val r = impl.exec(c)
                        result = { o -> Wire.writeStr(o, r) }
                    }
                    Wire.OP_GPU_START -> {
                        val r = impl.gpuStart()
                        result = { o -> o.writeBoolean(r) }
                    }
                    Wire.OP_GPU_SAMPLE -> {
                        val r = impl.gpuSample()
                        result = { o -> o.writeInt(r.size); for (v in r) o.writeLong(v) }
                    }
                    Wire.OP_GPU_STOP -> {
                        impl.gpuStop()
                        result = { }
                    }
                    Wire.OP_GPU_LOG -> {
                        val r = impl.gpuLog()
                        result = { o -> Wire.writeStr(o, r) }
                    }
                    Wire.OP_UID -> {
                        val r = impl.uid()
                        result = { o -> o.writeInt(r) }
                    }
                    else -> error = "unknown op $op"
                }
            } catch (e: EOFException) {
                return
            } catch (t: Throwable) {
                error = t.toString()
            }
            if (result != null && error == null) {
                out.writeByte(Wire.OK)
                result(out)
            } else {
                out.writeByte(Wire.ERR)
                Wire.writeStr(out, error ?: "failed")
            }
            out.flush()
        }
    }
}
