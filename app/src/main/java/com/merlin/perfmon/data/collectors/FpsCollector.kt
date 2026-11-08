package com.merlin.perfmon.data.collectors

import android.util.Log
import com.merlin.perfmon.data.model.FpsSnapshot
import com.merlin.perfmon.data.model.MetricValue
import com.merlin.perfmon.data.model.toMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FpsCollector"

/**
 * Reads FPS from SurfaceFlinger's frame latency ring buffer.
 *
 * Strategy:
 *  1. `dumpsys SurfaceFlinger --latency <layer>` returns a ring buffer of
 *     128 frame timestamps in nanoseconds (desiredPresentTime, actualStartTime,
 *     actualPresentTime).
 *  2. We use the actualPresentTime column, compute inter-frame deltas, and
 *     derive current FPS from the most recent frames.
 *
 * Choosing the layer:
 *  We first run `dumpsys SurfaceFlinger` to list active layers and pick the
 *  topmost visible application layer. We refresh the layer name every ~2 s to
 *  track app switches without hammering SurfaceFlinger.
 */
@Singleton
class FpsCollector @Inject constructor(
    private val root: RootShellManager,
    private val shizuku: ShizukuShellManager,
) {
    private var cachedLayerName: String? = null
    private var layerRefreshCountdown  = 0

    // Ring buffer of actualPresentTime nanoseconds from the previous call
    private var prevTimestamps: LongArray = LongArray(0)

    suspend fun collect(): FpsSnapshot = withContext(Dispatchers.IO) {
        // Refresh active layer name every ~8 collections (~2 s at 250 ms interval)
        if (layerRefreshCountdown <= 0) {
            cachedLayerName     = findTopLayer()
            layerRefreshCountdown = 8
        } else {
            layerRefreshCountdown--
        }

        val layer = cachedLayerName ?: return@withContext unavailable()

        val lines = execDumpsys("SurfaceFlinger --latency \"$layer\"")
            ?: return@withContext unavailable()

        computeFps(lines)
    }

    // ── SurfaceFlinger layer discovery ────────────────────────────────────────

    /**
     * Parse `dumpsys SurfaceFlinger` to find the topmost visible non-status-bar layer.
     * We look for lines containing "isVisible=1" (or similar) near a "name=" line.
     */
    private suspend fun findTopLayer(): String? {
        val lines = execDumpsys("SurfaceFlinger") ?: return null

        // SurfaceFlinger dump lists layers; find a visible app layer.
        // Format varies but typically:
        //   + Layer "com.example.app/MainActivity#0" (...)
        //     isVisible=1
        //     ...
        // We scan for the "fps=" line in the header which contains the name.
        // Simpler heuristic: find any layer whose name does not contain
        // "StatusBar", "NavigationBar", "Wallpaper", "SurfaceView - com.android"
        var candidate: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            // Look for quoted layer names
            if (trimmed.startsWith("+ Layer \"") || trimmed.startsWith("* Layer \"")) {
                val start = trimmed.indexOf('"') + 1
                val end   = trimmed.lastIndexOf('"')
                if (end > start) {
                    val name = trimmed.substring(start, end)
                    if (!isSystemLayer(name)) {
                        candidate = name
                        // Don't break — keep going to find the topmost (last listed) app layer
                    }
                }
            }
        }
        if (candidate == null) {
            Log.d(TAG, "No suitable layer found; falling back to fps line")
        }
        return candidate
    }

    private fun isSystemLayer(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("statusbar") ||
                lower.contains("navigationbar") ||
                lower.contains("wallpaper") ||
                lower.contains("backgroundthread") ||
                lower.contains("screendecor") ||
                lower.contains("pointer") ||
                lower.contains("inputmethod")
    }

    // ── Latency ring-buffer parsing ───────────────────────────────────────────

    /**
     * dumpsys SurfaceFlinger --latency output:
     *
     *   <refresh-period-ns>
     *   <desiredPresentTime>  <actualStartTime>  <actualPresentTime>
     *   ...  (128 rows)
     *   ...
     *
     * Rows with actualPresentTime == Long.MAX_VALUE (0x7fffffffffffffff) are
     * pending/invalid and must be skipped.
     */
    private fun computeFps(lines: List<String>): FpsSnapshot {
        if (lines.isEmpty()) return unavailable()

        // First line is refresh period in ns
        val refreshPeriodNs = lines[0].trim().toLongOrNull()

        val presentTimes = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split("\\s+".toRegex())
            if (parts.size < 3) continue
            val actual = parts[2].toLongOrNull() ?: continue
            if (actual == Long.MAX_VALUE || actual <= 0L) continue
            presentTimes.add(actual)
        }

        if (presentTimes.size < 2) return unavailable()

        presentTimes.sort()

        // Compute inter-frame intervals in ms
        val intervals = mutableListOf<Float>()
        for (i in 1 until presentTimes.size) {
            val dt = (presentTimes[i] - presentTimes[i - 1]) / 1_000_000f  // ns → ms
            if (dt > 0f && dt < 1000f) intervals.add(dt)   // sanity: ignore gaps > 1 s
        }

        if (intervals.isEmpty()) return unavailable()

        // Average frame time → average FPS
        val avgFrameTimeMs = intervals.average().toFloat()
        val avgFps         = if (avgFrameTimeMs > 0f) 1000f / avgFrameTimeMs else 0f

        // "Current" FPS = last 8 frames only
        val recent = intervals.takeLast(8)
        val recentAvgMs = recent.average().toFloat()
        val currentFps  = if (recentAvgMs > 0f) 1000f / recentAvgMs else avgFps

        prevTimestamps = presentTimes.toLongArray()

        return FpsSnapshot(
            currentFps  = currentFps.coerceIn(0f, 120f).toMetric(),
            averageFps  = avgFps.coerceIn(0f, 120f).toMetric(),
            frameTimeMs = recentAvgMs.toMetric(),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private suspend fun execDumpsys(args: String): List<String>? =
        root.dumpsys(args) ?: shizuku.dumpsys(args)

    private fun unavailable() = FpsSnapshot(
        currentFps  = MetricValue.Unavailable,
        averageFps  = MetricValue.Unavailable,
        frameTimeMs = MetricValue.Unavailable,
    )
}
