package com.merlin.perfmon.data.collectors

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RootShellManager"

/**
 * Manages a single persistent root shell via libsu.
 *
 * Using one long-lived shell rather than spawning `su` on every read
 * eliminates per-call fork/exec overhead — critical for 250 ms FPS polling.
 */
@Singleton
class RootShellManager @Inject constructor() {

    private val _isAvailable: Boolean by lazy {
        try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root unavailable: ${e.message}")
            false
        }
    }

    val isRootAvailable: Boolean get() = _isAvailable

    /**
     * Execute a single shell command and return stdout lines.
     * Returns null if root is unavailable or the command fails.
     */
    suspend fun exec(command: String): List<String>? = withContext(Dispatchers.IO) {
        if (!_isAvailable) return@withContext null
        try {
            val result = Shell.cmd(command).exec()
            if (result.isSuccess) result.out else null
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed for '$command': ${e.message}")
            null
        }
    }

    /**
     * Read a single sysfs/procfs node and return the raw trimmed string.
     * Returns null on any error.
     */
    suspend fun readNode(path: String): String? =
        exec("cat $path")?.firstOrNull()?.trim()

    /**
     * Read a sysfs/procfs node and parse it as an Int.
     */
    suspend fun readInt(path: String): Int? =
        readNode(path)?.toIntOrNull()

    /**
     * Read a sysfs/procfs node and parse it as a Long.
     */
    suspend fun readLong(path: String): Long? =
        readNode(path)?.toLongOrNull()

    /**
     * Read all lines from a procfs node.
     */
    suspend fun readLines(path: String): List<String>? =
        exec("cat $path")

    /**
     * Run dumpsys command (needs Shizuku or root).
     */
    suspend fun dumpsys(args: String): List<String>? =
        exec("dumpsys $args")
}
