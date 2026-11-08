package com.merlin.fpslite

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.merlin.fpscore.CpuLoad
import com.merlin.fpscore.FpsStats
import com.merlin.fpscore.FrameTracker
import com.merlin.fpscore.LatencyParser
import com.merlin.fpscore.LayerResolver
import com.merlin.fpscore.MemInfo
import com.merlin.fpscore.ThermalParser

/** Everything the overlay draws. Primitive fields only, copied under a lock. */
class Snapshot {
    var status: String = ""
    var pkg: String = ""
    var fps = -1f
    var avgFps = 0f
    var low1 = 0f
    var low01 = 0f
    var frameTimeMs = 0f
    var jank = 0
    var refreshHz = 0f
    var gaps = 0
    val graph = FloatArray(GRAPH_POINTS)
    var graphN = 0
    var cpuLoad = -1f
    val clusterMHz = IntArray(MAX_CLUSTERS)
    var clusters = 0
    var gpuBusy = -1f
    var gpuMHz = -1
    var gpuState = 0 // 1 ok, 0 waiting, -1 unavailable
    var tCpu = Float.NaN
    var tGpu = Float.NaN
    var tBattery = Float.NaN
    var ramUsedMB = -1
    var ramTotalMB = -1
    var appRamMB = -1
    var swapUsedMB = -1
    var swapTotalMB = -1

    fun copyFrom(o: Snapshot) {
        status = o.status; pkg = o.pkg
        fps = o.fps; avgFps = o.avgFps; low1 = o.low1; low01 = o.low01
        frameTimeMs = o.frameTimeMs; jank = o.jank; refreshHz = o.refreshHz; gaps = o.gaps
        System.arraycopy(o.graph, 0, graph, 0, o.graphN); graphN = o.graphN
        cpuLoad = o.cpuLoad
        System.arraycopy(o.clusterMHz, 0, clusterMHz, 0, o.clusters); clusters = o.clusters
        gpuBusy = o.gpuBusy; gpuMHz = o.gpuMHz; gpuState = o.gpuState
        tCpu = o.tCpu; tGpu = o.tGpu; tBattery = o.tBattery
        ramUsedMB = o.ramUsedMB; ramTotalMB = o.ramTotalMB; appRamMB = o.appRamMB
        swapUsedMB = o.swapUsedMB; swapTotalMB = o.swapTotalMB
    }

    companion object {
        const val GRAPH_POINTS = 120
        const val MAX_CLUSTERS = 6
    }
}

/**
 * Polls on one background thread. FPS every [Prefs.pollMs] (default 250 ms);
 * CPU/RAM/GPU every 1 s and temperatures every 2 s, only when enabled.
 */
class Sampler(private val ctx: Context, private val prefs: Prefs, private val onUpdate: Runnable) {
    val lock = Any()
    val shared = Snapshot()
    private val work = Snapshot()

    private val thread = HandlerThread("fps-sampler", Process.THREAD_PRIORITY_DEFAULT)
    private lateinit var handler: Handler
    @Volatile private var running = false

    private val latencyParser = LatencyParser()
    private val tsBuf = LongArray(256)
    private val tracker = FrameTracker(4096)
    private val stats = FpsStats()
    private val cpuLoad = CpuLoad()
    private val memInfo = MemInfo()
    private var appPid = -1
    private var appPidFor: String? = null
    private var nextPidLookupAt = 0L
    private var nextRamAt = 0L

    private var component: String? = null
    private var layer: String? = null
    private var lastFrameAt = 0L
    private var nextFocusAt = 0L
    private var nextResolveAt = 0L
    private var nextCpuAt = 0L
    private var nextGpuAt = 0L
    private var nextTempAt = 0L
    private var gpuStarted = false
    private var lastSvc: PrivApi? = null

    private var clusterPaths: Array<String>? = null
    private var cpuPaths: Array<String> = emptyArray()

    private var pollMs = 250L
    private var fpsWindowNs = 1_000_000_000L
    private var extras = false
    private var wantCpu = false
    private var wantGpu = false
    private var wantTemps = false
    private var wantRam = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val start = SystemClock.uptimeMillis()
            try {
                sample()
            } catch (t: Throwable) {
                work.status = "error: " + t.javaClass.simpleName
            }
            synchronized(lock) { shared.copyFrom(work) }
            onUpdate.run()
            val delay = pollMs - (SystemClock.uptimeMillis() - start)
            handler.postDelayed(this, if (delay < 20) 20 else delay)
        }
    }

    fun start() {
        if (!thread.isAlive) {
            thread.start()
            handler = Handler(thread.looper)
        }
        applyPrefs()
        if (running) return
        running = true
        handler.post(tick)
    }

    /** Resets jank counters and avg / 1% / 0.1% lows (double-tap on the overlay). */
    fun resetStats() {
        if (::handler.isInitialized) handler.post { tracker.resetSession() }
    }

    /** Pause polling (screen off). Keeps state so resuming is instant. */
    fun pause() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacks(tick)
    }

    fun stop() {
        pause()
        if (::handler.isInitialized) handler.post { stopGpu() }
        thread.quitSafely()
    }

    fun applyPrefs() {
        pollMs = prefs.pollMs.toLong().coerceIn(100, 500)
        fpsWindowNs = prefs.fpsWindowMs.toLong().coerceIn(250, 2000) * 1_000_000L
        extras = prefs.wantsExtras
        wantCpu = extras && (prefs.showCpuLoad || prefs.showCpuFreq)
        wantGpu = extras && prefs.showGpu
        wantTemps = extras && prefs.showTemps
        wantRam = extras && prefs.showRam
        if (::handler.isInitialized && !wantGpu && gpuStarted) handler.post { stopGpu() }
    }

    private fun stopGpu() {
        if (!gpuStarted) return
        gpuStarted = false
        try { Priv.service?.gpuStop() } catch (_: Throwable) {}
    }

    private fun sample() {
        val svc = Priv.service
        if (svc == null) {
            work.status = when (Priv.state()) {
                Priv.State.NO_SHIZUKU -> "Shizuku not running"
                Priv.State.NO_PERMISSION -> "Shizuku permission needed"
                Priv.State.FAILED -> "Helper failed (see app)"
                else -> "Connecting…"
            }
            work.fps = -1f
            gpuStarted = false
            if (wantTemps) batteryOnlyTemp()
            return
        }
        if (svc !== lastSvc) {
            // New privileged process (first bind or rebind after a crash): its state is fresh.
            lastSvc = svc
            gpuStarted = false
            clusterPaths = null
            appPid = -1
            appPidFor = null
            component = null
            layer = null
        }
        val now = SystemClock.uptimeMillis()

        if (now >= nextFocusAt) {
            nextFocusAt = now + 1000
            val comp = LayerResolver.focusedComponent(svc.dump("window", arrayOf("displays")) ?: "")
            if (comp != null && comp != component) {
                component = comp
                work.pkg = LayerResolver.packageOf(comp)
                resolveLayer(svc)
            }
        }

        val l = layer
        if (l == null) {
            work.status = if (component == null) "No app in focus" else "No frames yet"
            work.fps = -1f
            if (component != null && now >= nextResolveAt) {
                nextResolveAt = now + 3000
                resolveLayer(svc)
            }
        } else {
            val nowNs = System.nanoTime()
            val txt = svc.dump("SurfaceFlinger", arrayOf("--latency", l)) ?: ""
            val n = latencyParser.parse(txt, tsBuf)
            if (latencyParser.refreshPeriodNs > 0) {
                tracker.vsyncPeriodNs = latencyParser.refreshPeriodNs
                work.refreshHz = 1e9f / latencyParser.refreshPeriodNs
            }
            if (tracker.add(tsBuf, n) > 0) {
                lastFrameAt = now
            } else if (now - lastFrameAt > 3000 && now >= nextResolveAt) {
                // Surface may have been recreated (e.g. game moved from loader to match).
                nextResolveAt = now + 3000
                resolveLayer(svc)
            }
            work.status = ""
            work.fps = tracker.currentFps(nowNs, fpsWindowNs)
            tracker.computeStats(nowNs, STATS_WINDOW_NS, stats)
            work.avgFps = stats.avgFps
            work.low1 = stats.low1Fps
            work.low01 = stats.low01Fps
            work.jank = stats.jank
            work.gaps = tracker.gapCount
            work.graphN = tracker.recentFrameTimes(work.graph)
            work.frameTimeMs = if (work.graphN > 0) work.graph[work.graphN - 1] else 0f
        }

        if (wantCpu && now >= nextCpuAt) {
            nextCpuAt = now + 1000
            sampleCpu(svc)
        }
        if (wantRam && now >= nextRamAt) {
            nextRamAt = now + 1000
            sampleRam(svc, now)
        }
        if (wantGpu && now >= nextGpuAt) {
            nextGpuAt = now + 1000
            sampleGpu(svc)
        }
        if (wantTemps && now >= nextTempAt) {
            nextTempAt = now + 2000
            val t = ThermalParser.parse(svc.dump("thermalservice", emptyArray()) ?: "")
            work.tCpu = t["CPU"] ?: t["SOC"] ?: Float.NaN
            work.tGpu = t["GPU"] ?: Float.NaN
            work.tBattery = t["BATTERY"] ?: Float.NaN
            if (work.tBattery.isNaN()) batteryOnlyTemp()
        }
    }

    private fun resolveLayer(svc: PrivApi) {
        val comp = component ?: return
        val list = svc.dump("SurfaceFlinger", arrayOf("--list")) ?: return
        val cands = LayerResolver.candidates(list, LayerResolver.packageOf(comp), comp)
        var best: String? = null
        var bestFrames = -1
        val cutoff = System.nanoTime() - 1_000_000_000L
        for (c in cands.take(4)) {
            val n = latencyParser.parse(svc.dump("SurfaceFlinger", arrayOf("--latency", c)) ?: "", tsBuf)
            var recent = 0
            for (i in 0 until n) if (tsBuf[i] > cutoff) recent++
            if (recent > bestFrames) {
                bestFrames = recent
                best = c
            }
        }
        if (best != layer) {
            layer = best
            tracker.reset()
            lastFrameAt = SystemClock.uptimeMillis()
        }
    }

    private fun sampleCpu(svc: PrivApi) {
        if (clusterPaths == null) {
            val base = "/sys/devices/system/cpu/cpufreq"
            val policies = svc.listDir(base).filter { it.startsWith("policy") }
                .sortedBy { it.removePrefix("policy").toIntOrNull() ?: 0 }
            val paths = policies.take(Snapshot.MAX_CLUSTERS).map { "$base/$it/scaling_cur_freq" }
            clusterPaths = paths.toTypedArray()
            cpuPaths = (paths + "/proc/stat").toTypedArray()
        }
        val res = svc.readTexts(cpuPaths)
        val nClusters = clusterPaths!!.size
        var k = 0
        for (i in 0 until nClusters) {
            val v = res.getOrNull(i)?.trim()?.toLongOrNull() ?: continue
            work.clusterMHz[k++] = (v / 1000).toInt()
        }
        work.clusters = k
        val stat = res.getOrNull(nClusters)
        if (stat != null) {
            cpuLoad.update(stat)
            work.cpuLoad = cpuLoad.load[0]
        }
    }

    /**
     * System RAM from /proc/meminfo (used = MemTotal − MemAvailable) and the
     * foreground app's resident memory (VmRSS from /proc/<pid>/status; shell can
     * read it thanks to the readproc group). The pid is looked up only when the
     * focused app changes or the process disappears.
     */
    private fun sampleRam(svc: PrivApi, now: Long) {
        val pkg = component?.let { LayerResolver.packageOf(it) }
        if (pkg != null && (pkg != appPidFor || appPid <= 0) && now >= nextPidLookupAt) {
            nextPidLookupAt = now + 5000
            appPidFor = pkg
            appPid = svc.exec("pidof $pkg")?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull() ?: -1
        }
        val paths = if (appPid > 0) arrayOf("/proc/meminfo", "/proc/$appPid/status") else arrayOf("/proc/meminfo")
        val res = svc.readTexts(paths)
        res[0]?.let {
            MemInfo.parse(it, memInfo)
            work.ramTotalMB = (memInfo.totalKb / 1024).toInt()
            work.ramUsedMB = (memInfo.usedKb / 1024).toInt()
            work.swapTotalMB = (memInfo.swapTotalKb / 1024).toInt()
            work.swapUsedMB = (memInfo.swapUsedKb / 1024).toInt()
        }
        if (appPid > 0) {
            val rss = res.getOrNull(1)?.let { MemInfo.processRssKb(it) } ?: -1L
            if (rss < 0) appPid = -1 // process gone; look it up again later
            work.appRamMB = if (rss >= 0) (rss / 1024).toInt() else -1
        } else {
            work.appRamMB = -1
        }
    }

    private fun sampleGpu(svc: PrivApi) {
        if (!gpuStarted) {
            gpuStarted = true
            if (!svc.gpuStart()) {
                work.gpuState = -1
                return
            }
        }
        val r = svc.gpuSample()
        work.gpuState = r[0].toInt()
        if (r[0] == -1L) {
            // Session gone (refused, or stopped by the probe screen): retry later.
            gpuStarted = false
            nextGpuAt = SystemClock.uptimeMillis() + 10_000
            return
        }
        if (r[0] == 1L) {
            if (r[1] >= 0) work.gpuMHz = (r[1] / 1000).toInt()
            if (r[2] >= 0) work.gpuBusy = r[2] / 10f
        }
    }

    private fun batteryOnlyTemp() {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t != Int.MIN_VALUE) work.tBattery = t / 10f
    }

    companion object {
        /** Window for avg / 1% low / 0.1% low. */
        const val STATS_WINDOW_NS = 10_000_000_000L
    }
}
