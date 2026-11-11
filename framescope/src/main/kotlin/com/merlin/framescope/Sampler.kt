package com.merlin.framescope

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
import com.merlin.fpscore.GpuSysfs
import com.merlin.fpscore.LatencyParser
import com.merlin.fpscore.LayerChooser
import com.merlin.fpscore.LayerResolver
import com.merlin.fpscore.MemInfo
import com.merlin.fpscore.ThermalParser
import com.merlin.fpscore.ThermalZones

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
    /** True when GPU values come from real driver nodes (root, or readable sysfs). */
    var gpuAccurate = false
    /** True while any cluster's max frequency is limited below its hardware max. */
    var cpuCapped = false
    var tSoc = Float.NaN
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
        gpuBusy = o.gpuBusy; gpuMHz = o.gpuMHz; gpuState = o.gpuState; gpuAccurate = o.gpuAccurate
        cpuCapped = o.cpuCapped
        tSoc = o.tSoc; tBattery = o.tBattery
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
    /** Package of the layer being measured; differs from the focus while a popup is on top. */
    private var layerPkg: String? = null
    private var lastFrameAt = 0L
    private var nextFocusAt = 0L
    private var nextResolveAt = 0L
    private var nextCpuAt = 0L
    private var nextGpuAt = 0L
    private var nextTempAt = 0L
    private var gpuStarted = false
    private var lastSvc: PrivApi? = null

    private var clusterPaths: Array<String>? = null
    private var clusterLimitPaths: Array<String> = emptyArray()
    private var clusterHwMaxKHz: LongArray = LongArray(0)

    // Driver-level sources, detected once per helper process (null = not readable).
    private var sourcesProbed = false
    private var gpuLoadPath: String? = null
    private var gpuFreqPath: String? = null
    private var socZonePaths: Array<String> = emptyArray()
    private var batteryZonePath: String? = null
    private var cpuPaths: Array<String> = emptyArray()

    private var pollMs = 250L
    private var fpsWindowNs = 1_000_000_000L
    private var extras = false
    private var wantCpu = false
    private var wantGpu = false
    private var wantTemps = false
    private var wantRam = false
    /** Per-app RAM needs a pid lookup; the Compact preset only shows system RAM %. */
    private var wantAppRam = false

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
        val compact = prefs.preset == Prefs.PRESET_COMPACT
        wantCpu = compact || (extras && (prefs.showCpuLoad || prefs.showCpuFreq))
        wantGpu = extras && prefs.showGpu
        wantTemps = compact || (extras && prefs.showTemps)
        wantRam = compact || (extras && prefs.showRam)
        wantAppRam = extras && prefs.showRam
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
                Priv.State.NO_ROOT -> "Root not available"
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
            sourcesProbed = false
            appPid = -1
            appPidFor = null
            component = null
            layerPkg = null
            layer = null
        }
        val now = SystemClock.uptimeMillis()

        if (now >= nextFocusAt) {
            nextFocusAt = now + 1000
            val comp = LayerResolver.focusedComponent(svc.dump("window", arrayOf("displays")) ?: "")
            if (comp != null && comp != component) {
                component = comp
                resolveLayer(svc)
            } else if (comp != null && layerPkg != null && layerPkg != LayerResolver.packageOf(comp) &&
                now >= nextResolveAt
            ) {
                // A popup has focus and we kept measuring the app behind it; re-check
                // in case that app stopped drawing (then the popup's app takes over).
                nextResolveAt = now + 2000
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
            if (!sourcesProbed) probeSources(svc)
            if (socZonePaths.isNotEmpty()) {
                // Real sensors (root): hottest SoC zone = what throttling reacts to.
                val paths = if (batteryZonePath != null) socZonePaths + batteryZonePath!! else socZonePaths
                val r = svc.readTexts(paths)
                var hottest = Float.NaN
                for (i in socZonePaths.indices) {
                    val c = ThermalZones.parse("soc", r.getOrNull(i))?.celsius ?: continue
                    if (hottest.isNaN() || c > hottest) hottest = c
                }
                if (!hottest.isNaN()) {
                    work.tSoc = hottest
                    work.tBattery = if (batteryZonePath != null)
                        ThermalZones.parse("battery", r.getOrNull(socZonePaths.size))?.celsius ?: Float.NaN else Float.NaN
                    if (work.tBattery.isNaN()) batteryOnlyTemp()
                    return
                }
            }
            val t = ThermalParser.parse(svc.dump("thermalservice", emptyArray()) ?: "")
            // HyperOS (and many vendors) report one chip-wide temperature under the
            // CPU/GPU/NPU/SOC labels; show it once as "SoC" instead of implying
            // separate CPU and GPU sensors. Prefer SOC, fall back to the hottest of CPU/GPU.
            val soc = t["SOC"] ?: maxOfNonNull(t["CPU"], t["GPU"])
            work.tSoc = soc ?: Float.NaN
            work.tBattery = t["BATTERY"] ?: Float.NaN
            if (work.tBattery.isNaN()) batteryOnlyTemp()
        }
    }

    private fun resolveLayer(svc: PrivApi) {
        val comp = component ?: return
        val pkg = LayerResolver.packageOf(comp)
        val list = svc.dump("SurfaceFlinger", arrayOf("--list")) ?: return
        val cutoff = System.nanoTime() - 1_000_000_000L
        fun recentFrames(layerName: String): Int {
            val n = latencyParser.parse(svc.dump("SurfaceFlinger", arrayOf("--latency", layerName)) ?: "", tsBuf)
            var recent = 0
            for (i in 0 until n) if (tsBuf[i] > cutoff) recent++
            return recent
        }
        val cands = LayerResolver.candidates(list, pkg, comp).take(4).map { it to recentFrames(it) }

        // Keep measuring the current layer if it belongs to another app that is still
        // drawing (a popup or dialog is on top of a running game).
        val cur = layer
        val competing = cur != null && layerPkg != null && layerPkg != pkg
        val curListed = competing && list.lineSequence().any { LayerResolver.extractName(it) == cur }
        val curFrames = if (curListed) recentFrames(cur!!) else 0
        val chosen = LayerChooser.choose(if (competing) cur else null, curFrames, curListed, cands)

        val chosenPkg = when {
            chosen == null -> null
            chosen == cur && competing && curListed -> layerPkg
            else -> pkg
        }
        if (chosen != layer) {
            layer = chosen
            tracker.reset()
            lastFrameAt = SystemClock.uptimeMillis()
        }
        layerPkg = chosenPkg
        work.pkg = chosenPkg ?: pkg
    }

    private fun sampleCpu(svc: PrivApi) {
        if (clusterPaths == null) {
            val base = "/sys/devices/system/cpu/cpufreq"
            // Order clusters slowest → fastest by their max frequency. Policy numbering
            // does not imply speed: on MT6765 (vivo 1938) policy0 is the 2.3 GHz
            // cluster and policy4 the 1.8 GHz one; on Dimensity 9500s it is reversed.
            val names = svc.listDir(base).filter { it.startsWith("policy") }
            val maxes = svc.readTexts(names.map { "$base/$it/cpuinfo_max_freq" }.toTypedArray())
            val policies = names.indices
                .sortedWith(compareBy({ maxes.getOrNull(it)?.trim()?.toLongOrNull() ?: Long.MAX_VALUE },
                    { names[it].removePrefix("policy").toIntOrNull() ?: 0 }))
                .map { names[it] }
            val kept = policies.take(Snapshot.MAX_CLUSTERS)
            val paths = kept.map { "$base/$it/scaling_cur_freq" }
            clusterPaths = paths.toTypedArray()
            clusterLimitPaths = kept.map { "$base/$it/scaling_max_freq" }.toTypedArray()
            clusterHwMaxKHz = kept.map { p -> maxes.getOrNull(names.indexOf(p))?.trim()?.toLongOrNull() ?: 0L }.toLongArray()
            cpuPaths = (paths + "/proc/stat" + clusterLimitPaths).toTypedArray()
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
        // Thermal / power capping: scaling_max_freq pulled below the hardware max.
        // (Not readable on some phones, e.g. HyperOS without root: then never "capped".)
        var capped = false
        for (i in 0 until nClusters) {
            val lim = res.getOrNull(nClusters + 1 + i)?.trim()?.toLongOrNull() ?: continue
            val hw = clusterHwMaxKHz.getOrElse(i) { 0L }
            if (hw > 0 && lim < hw * 98 / 100) capped = true
        }
        work.cpuCapped = capped
    }

    /**
     * Looks once for GPU and thermal nodes that this helper can read. With root
     * (or on phones that expose them to shell) these replace the less accurate
     * sources: perfetto GPU events and the thermal service.
     */
    private fun probeSources(svc: PrivApi) {
        sourcesProbed = true
        val loads = svc.readTexts(GpuSysfs.LOAD_PATHS)
        gpuLoadPath = GpuSysfs.LOAD_PATHS.indices.firstOrNull { i ->
            loads.getOrNull(i)?.let { GpuSysfs.parseLoad(GpuSysfs.LOAD_PATHS[i], it) >= 0 } == true
        }?.let { GpuSysfs.LOAD_PATHS[it] }
        val freqs = svc.readTexts(GpuSysfs.FREQ_PATHS)
        gpuFreqPath = GpuSysfs.FREQ_PATHS.indices.firstOrNull { i ->
            freqs.getOrNull(i)?.let { GpuSysfs.parseFreqMHz(GpuSysfs.FREQ_PATHS[i], it) > 0 } == true
        }?.let { GpuSysfs.FREQ_PATHS[it] }

        val base = "/sys/class/thermal"
        val zones = svc.listDir(base).filter { it.startsWith("thermal_zone") }
        if (zones.isNotEmpty()) {
            val types = svc.readTexts(zones.map { "$base/$it/type" }.toTypedArray())
            val temps = svc.readTexts(zones.map { "$base/$it/temp" }.toTypedArray())
            val soc = ArrayList<String>()
            for (i in zones.indices) {
                val z = ThermalZones.parse(types.getOrNull(i), temps.getOrNull(i)) ?: continue
                val path = "$base/${zones[i]}/temp"
                if (ThermalZones.isSocZone(z.type)) soc.add(path)
                else if (batteryZonePath == null && ThermalZones.isBatteryZone(z.type)) batteryZonePath = path
            }
            socZonePaths = soc.toTypedArray()
        }
    }

    /**
     * System RAM from /proc/meminfo (used = MemTotal − MemAvailable) and the
     * foreground app's resident memory (VmRSS from /proc/<pid>/status; shell can
     * read it thanks to the readproc group). The pid is looked up only when the
     * focused app changes or the process disappears.
     */
    private fun sampleRam(svc: PrivApi, now: Long) {
        val pkg = if (!wantAppRam) null
            else layerPkg ?: component?.let { LayerResolver.packageOf(it) } // app being measured, not a popup on top
        if (!wantAppRam) appPid = -1
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
        if (!sourcesProbed) probeSources(svc)
        val lp = gpuLoadPath
        val fp = gpuFreqPath
        if (lp != null || fp != null) {
            val r = svc.readTexts(arrayOf(lp ?: "", fp ?: ""))
            val load = if (lp != null) r.getOrNull(0)?.let { GpuSysfs.parseLoad(lp, it) } ?: -1 else -1
            val mhz = if (fp != null) r.getOrNull(1)?.let { GpuSysfs.parseFreqMHz(fp, it) } ?: -1 else -1
            work.gpuBusy = if (load >= 0) load.toFloat() else -1f
            work.gpuMHz = mhz
            work.gpuState = 1
            work.gpuAccurate = true
            return
        }
        work.gpuAccurate = false
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

    private fun maxOfNonNull(a: Float?, b: Float?): Float? =
        if (a == null) b else if (b == null) a else maxOf(a, b)

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
