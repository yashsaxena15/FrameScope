package com.merlin.perfmon.data.collectors

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG               = "ShizukuShellManager"
private const val SHIZUKU_REQ_CODE  = 1001

/**
 * Shizuku-based command executor used as a fallback when root is unavailable.
 *
 * Permission lifecycle:
 *  1. Call [isShizukuAvailable] to check if the Shizuku daemon is running.
 *  2. Call [hasPermission] to check if we already hold the runtime grant.
 *  3. Call [requestPermission] to show the system grant dialog.
 *  4. Register a [Shizuku.OnRequestPermissionResultListener] in your Activity or
 *     ViewModel to receive the result — see [addPermissionResultListener] /
 *     [removePermissionResultListener].
 *
 * Shizuku runs commands as `shell` (uid 2000), which can access:
 *   - dumpsys, /proc/stat, /proc/meminfo, /proc/net/dev
 *   - Some /sys/class/ nodes readable by the shell group
 *
 * It CANNOT read most /sys/devices/system/cpu/cpufreq/* or /proc/mali/* nodes.
 * Those require root.
 */
@Singleton
class ShizukuShellManager @Inject constructor() {

    // ── Availability & permission ─────────────────────────────────────────────

    /** True if the Shizuku binder is alive (daemon is running). */
    val isShizukuAvailable: Boolean
        get() = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    /** True if we already hold the Shizuku runtime permission. */
    val hasPermission: Boolean
        get() = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /**
     * True if Shizuku is available AND we hold the permission.
     * This is the guard used by all exec methods.
     */
    val isAvailable: Boolean
        get() = isShizukuAvailable && hasPermission

    /**
     * Show the Shizuku system permission-grant dialog.
     * The result is delivered to any registered [Shizuku.OnRequestPermissionResultListener].
     * Safe to call even if Shizuku is not running — it silently no-ops via the try-catch.
     */
    fun requestPermission() {
        try {
            if (isShizukuAvailable && !hasPermission) {
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku.requestPermission() failed: ${e.message}")
        }
    }

    /**
     * Register a listener that receives the result of [requestPermission].
     * Call this in your component's resume/start lifecycle callback.
     */
    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Exception) {}
    }

    /**
     * Remove a previously registered permission-result listener.
     * Call this in your component's pause/stop lifecycle callback.
     */
    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Exception) {}
    }

    // ── Command execution ─────────────────────────────────────────────────────

    /**
     * Execute a command via Shizuku's newProcess API.
     * Returns stdout lines on success, null if unavailable or command fails.
     */
    suspend fun exec(command: String): List<String>? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val lines   = BufferedReader(InputStreamReader(process.inputStream)).readLines()
            process.waitFor()
            process.destroy()
            lines
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed for '$command': ${e.message}")
            null
        }
    }

    suspend fun readNode(path: String): String? =
        exec("cat $path")?.firstOrNull()?.trim()

    suspend fun dumpsys(args: String): List<String>? =
        exec("dumpsys $args")
}
