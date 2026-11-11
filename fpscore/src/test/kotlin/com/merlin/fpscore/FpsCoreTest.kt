package com.merlin.fpscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsCoreTest {
    private fun res(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    private fun resBytes(name: String): ByteArray =
        javaClass.classLoader!!.getResource(name)!!.readBytes()

    // ── LatencyParser ────────────────────────────────────────────────────────

    @Test fun parsesRealBgmiDumpAt120Hz() {
        val p = LatencyParser()
        val out = LongArray(256)
        val n = p.parse(res("latency_bgmi_1.txt"), out)
        assertEquals(8_333_333L, p.refreshPeriodNs)
        assertTrue("expected ~127 frames, got $n", n in 120..128)
        for (k in 1 until n) assertTrue(out[k] > out[k - 1])
        val fps = (n - 1) * 1e9 / (out[n - 1] - out[0])
        assertTrue("BGMI ran at ~120 FPS, got $fps", fps in 100.0..125.0)
    }

    @Test fun surfaceViewParentLayerHasNoFrames() {
        val n = LatencyParser().parse(res("latency_surfaceview_parent_empty.txt"), LongArray(256))
        assertEquals(0, n)
    }

    @Test fun skipsPendingAndZeroRowsAndUsesActualPresentColumn() {
        val text = """
            16666666
            0	0	0
            1000	2000	1500
            1100	3000	2500
            1200	9223372036854775807	3500
        """.trimIndent()
        val out = LongArray(8)
        val n = LatencyParser().parse(text, out)
        assertEquals(2, n)
        assertEquals(2000L, out[0])
        assertEquals(3000L, out[1])
    }

    // ── FrameTracker ─────────────────────────────────────────────────────────

    private fun frames(start: Long, count: Int, intervalNs: Long) =
        LongArray(count) { start + it * intervalNs }

    @Test fun countsEachFrameOnceAcrossOverlappingDumps() {
        val t = FrameTracker()
        val all = frames(1_000_000_000L, 300, 8_333_333L)
        // Dumps of 127 frames, each overlapping the previous by ~half.
        var added = 0
        var start = 0
        while (start < 300) {
            val end = minOf(start + 127, 300)
            val dump = all.copyOfRange(start, end)
            added += t.add(dump, dump.size)
            start += 60
        }
        assertEquals(300, added)
        assertEquals(0, t.gapCount)
        val now = all.last() + 1_000_000L
        assertEquals(120f, t.currentFps(now, 1_000_000_000L), 0.5f)
    }

    @Test fun detectsLostFramesWhenPollingTooSlowly() {
        val t = FrameTracker()
        val all = frames(1_000_000_000L, 400, 8_333_333L)
        t.add(all.copyOfRange(0, 127), 127)
        t.add(all.copyOfRange(200, 327), 127) // frames 127..199 never seen
        assertEquals(1, t.gapCount)
    }

    @Test fun fpsDecaysToZeroWhenRenderingStops() {
        val t = FrameTracker()
        val f = frames(1_000_000_000L, 120, 8_333_333L)
        t.add(f, f.size)
        val now = f.last() + 2_000_000_000L
        assertEquals(0f, t.currentFps(now, 1_000_000_000L), 0.001f)
    }

    @Test fun onePercentLowReflectsStutters() {
        val t = FrameTracker()
        // 60 FPS with one 50 ms hitch every 100 frames.
        val list = ArrayList<Long>()
        var ts = 1_000_000_000L
        for (i in 0 until 1000) {
            ts += if (i % 100 == 50) 50_000_000L else 16_666_667L
            list.add(ts)
        }
        val arr = list.toLongArray()
        t.vsyncPeriodNs = 8_333_333L
        t.add(arr, arr.size)
        val s = FpsStats()
        t.computeStats(arr.last(), 60_000_000_000L, s)
        assertEquals(59f, s.avgFps, 1.5f)
        assertTrue("1% low should expose 50 ms hitches: ${s.low1Fps}", s.low1Fps < 30f)
        assertEquals(10, s.jank)
    }

    @Test fun realBgmiDumpsGiveAbout120Fps() {
        val p = LatencyParser()
        val buf = LongArray(256)
        val t = FrameTracker()
        val n1 = p.parse(res("latency_bgmi_1.txt"), buf)
        t.add(buf, n1)
        val n2 = p.parse(res("latency_bgmi_2.txt"), buf)
        t.add(buf, n2)
        val now = t.lastTimestamp + 1_000_000L
        val fps = t.currentFps(now, 500_000_000L)
        assertTrue("fps=$fps", fps in 100f..125f)
    }

    // ── LayerResolver ────────────────────────────────────────────────────────

    @Test fun extractsNamesFromAndroid16ListFormat() {
        assertEquals(
            "b1f402 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST)#100207",
            LayerResolver.extractName("RequestedLayerState{b1f402 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST)#100207 parentId=100206}")
        )
        assertEquals(
            "com.pubg.imobile/com.epicgames.ue4.GameActivity#100204",
            LayerResolver.extractName("RequestedLayerState{com.pubg.imobile/com.epicgames.ue4.GameActivity#100204 parentId=100200}")
        )
        // Pre-Android-14 bare format
        assertEquals("SurfaceView - com.foo/com.foo.Main#0", LayerResolver.extractName("SurfaceView - com.foo/com.foo.Main#0"))
    }

    @Test fun picksBlastSurfaceViewForBgmi() {
        val comp = LayerResolver.focusedComponent(res("window_displays_bgmi.txt"))
        assertEquals("com.pubg.imobile/com.epicgames.ue4.GameActivity", comp)
        val c = LayerResolver.candidates(res("sf_list_bgmi.txt"), LayerResolver.packageOf(comp!!), comp)
        assertTrue(c.isNotEmpty())
        assertTrue("best=${c[0]}", c[0].contains("SurfaceView[") && c[0].contains("(BLAST)"))
        assertTrue(c.none { it.startsWith("Background for") || it.startsWith("Bounds for") || it.contains("InputSink") })
    }

    @Test fun focusFallsBackToCurrentFocus() {
        val dump = "  mCurrentFocus=Window{3521ba0 u0 com.mi.android.globallauncher/com.miui.home.launcher.Launcher}\n"
        assertEquals("com.mi.android.globallauncher/com.miui.home.launcher.Launcher", LayerResolver.focusedComponent(dump))
        assertEquals(null, LayerResolver.focusedComponent("  mCurrentFocus=Window{1 u0 NotificationShade}\n"))
    }

    // ── System parsers ───────────────────────────────────────────────────────

    @Test fun parsesThermalserviceHalTemperatures() {
        val m = ThermalParser.parse(res("thermalservice.txt"))
        assertNotNull(m["CPU"]); assertNotNull(m["GPU"]); assertNotNull(m["BATTERY"])
        assertTrue(m["CPU"]!! in 15f..110f)
    }

    @Test fun cpuLoadFromTwoProcStatReads() {
        val c = CpuLoad()
        c.update(res("proc_stat_1.txt"))
        c.update(res("proc_stat_2.txt"))
        assertEquals(8, c.cores)
        assertTrue(c.load[0] in 0f..100f)
        for (i in 1..8) assertTrue("core $i = ${c.load[i]}", c.load[i] in 0f..100f)
    }

    // ── Perfetto GPU trace ───────────────────────────────────────────────────

    @Test fun parsesRealPerfettoGpuTrace() {
        val agg = GpuAggregator()
        val parser = GpuTraceParser(agg)
        val bytes = resBytes("gpu_probe.pftrace")
        val consumed = parser.parse(bytes, bytes.size)
        assertEquals(bytes.size, consumed)
        assertTrue("events=${agg.events}", agg.events > 1000)
        assertEquals(598_000L, agg.maxSeenKHz)
        val out = LongArray(2)
        agg.take(out)
        assertTrue("avg active MHz=${out[0] / 1000}", out[0] / 1000 in 300..600)
        assertTrue("busy‰=${out[1]}", out[1] in 100..400) // BGMI measured ≈20 %
    }

    @Test fun incrementalParseKeepsPartialPacket() {
        val bytes = resBytes("gpu_probe.pftrace")
        val agg = GpuAggregator()
        val parser = GpuTraceParser(agg)
        val cut = bytes.size / 2
        val first = parser.parse(bytes, cut)
        assertTrue(first in 1..cut)
        val rest = bytes.copyOfRange(first, bytes.size)
        val second = parser.parse(rest, rest.size)
        assertEquals(rest.size, second)
        assertEquals(598_000L, agg.maxSeenKHz)
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    @Test fun parsesMeminfoFromPoco() {
        val text = """
            MemTotal:       11539412 kB
            MemFree:          149276 kB
            MemAvailable:    2268328 kB
            Buffers:            1840 kB
            SwapCached:        12000 kB
            SwapTotal:      12582908 kB
            SwapFree:        7587732 kB
        """.trimIndent()
        val m = MemInfo.parse(text, MemInfo())
        assertEquals(11539412L, m.totalKb)
        assertEquals(2268328L, m.availableKb)
        assertEquals(11539412L - 2268328L, m.usedKb)
        assertEquals(12582908L - 7587732L, m.swapUsedKb)
    }

    @Test fun parsesProcessRss() {
        val status = "Name:\tUnityMain\nVmPeak:\t9999 kB\nVmRSS:\t 1687512 kB\nRssAnon:\t 1122832 kB\n"
        assertEquals(1687512L, MemInfo.processRssKb(status))
        assertEquals(-1L, MemInfo.processRssKb("Name:\tx\n"))
    }

    @Test fun resetSessionClearsJankAndLowsButKeepsFps() {
        val t = FrameTracker()
        t.vsyncPeriodNs = 8_333_333L
        // 300 frames at 60 FPS with a 100 ms hitch at frame 150.
        val list = ArrayList<Long>()
        var ts = 1_000_000_000L
        for (i in 0 until 300) { ts += if (i == 150) 100_000_000L else 16_666_667L; list.add(ts) }
        val a = list.toLongArray()
        t.add(a, a.size)
        val s = FpsStats()
        t.computeStats(a.last(), 10_000_000_000L, s)
        assertEquals(1, s.jank)
        assertTrue("1% low=${s.low1Fps}", s.low1Fps < 30f) // worst 3 of 299 frames: (100+16.7+16.7)/3 ms ≈ 22 FPS

        t.resetSession()
        val b = LongArray(120) { a.last() + (it + 1) * 16_666_667L }
        t.add(b, b.size)
        t.computeStats(b.last(), 10_000_000_000L, s)
        assertEquals(0, s.jank)
        assertEquals(119, s.frames) // only intervals after the reset
        assertEquals(60f, s.low1Fps, 0.5f)
        assertEquals(60f, t.currentFps(b.last() + 1_000_000L, 1_000_000_000L), 0.5f)
    }

    // ── vivo 1938 (MT6765 / Helio P35, Android 12) ───────────────────────────

    @Test fun vivoShortComponentIsNormalizedAndMatchesLayer() {
        val comp = LayerResolver.focusedComponent(res("vivo1938_window_focus.txt"))
        assertEquals("com.android.settings/com.android.settings.SubSettings", comp)
        val c = LayerResolver.candidates(res("vivo1938_sf_list_settings.txt"), LayerResolver.packageOf(comp!!), comp)
        assertEquals("com.android.settings/com.android.settings.SubSettings#0", c[0])
        assertTrue(c.none { it.startsWith("ActivityRecord{") })
    }

    @Test fun vivoLatencyAt60Hz() {
        val p = LatencyParser()
        val buf = LongArray(256)
        val n = p.parse(res("vivo1938_latency_60hz.txt"), buf)
        assertEquals(16_666_667L, p.refreshPeriodNs)
        assertTrue("n=$n", n > 50)
        for (k in 1 until n) assertTrue(buf[k] > buf[k - 1])
    }

    @Test fun vivoThermalHasNoSocButHasCpuGpuBattery() {
        val m = ThermalParser.parse(res("vivo1938_thermalservice.txt"))
        assertEquals(null, m["SOC"])
        assertNotNull(m["CPU"]); assertNotNull(m["GPU"]); assertNotNull(m["BATTERY"])
    }

    @Test fun vivoMeminfo() {
        val m = MemInfo.parse(res("vivo1938_meminfo.txt"), MemInfo())
        assertTrue(m.totalKb in 3_000_000L..4_200_000L) // 4 GB phone
        assertTrue(m.usedKb > 0)
        assertTrue(m.swapTotalKb > 0)
    }

    @Test fun normalizeLeavesFullComponentsAlone() {
        assertEquals("com.pubg.imobile/com.epicgames.ue4.GameActivity",
            LayerResolver.normalize("com.pubg.imobile/com.epicgames.ue4.GameActivity"))
        assertEquals("a.b/a.b.Main", LayerResolver.normalize("a.b/.Main"))
    }

    @Test fun vivoSubwaySurfersPicksBlastLayerAndRuns60Fps() {
        val comp = LayerResolver.focusedComponent(res("vivo1938_window_focus_subway.txt"))!!
        val c = LayerResolver.candidates(res("vivo1938_sf_list_subway.txt"), LayerResolver.packageOf(comp), comp)
        assertEquals("SurfaceView[com.kiloo.subwaysurf/com.sybogames.chili.multidex.ChiliMultidexSupportActivity](BLAST)#0", c[0])
        val p = LatencyParser()
        val buf = LongArray(256)
        val n = p.parse(res("vivo1938_latency_subway.txt"), buf) // contains pending (INT64_MAX) rows
        val fps = (n - 1) * 1e9 / (buf[n - 1] - buf[0])
        assertTrue("fps=$fps", fps in 40.0..61.0)
    }

    // ── Redmi 10A (MT6762 / Helio G25, Android 11) ───────────────────────────

    @Test fun android11LatencyWithLayerNameLine() {
        val p = LatencyParser()
        val buf = LongArray(256)
        p.parse(res("redmi10a_latency_settings.txt"), buf)
        assertEquals(16_666_667L, p.refreshPeriodNs)
    }

    @Test fun layerNameWithDigitsIsNeverAFrameRow() {
        val text = "16666667\nb1f402 SurfaceView[com.game2/com.game2.Main3]#0\n1000\t2000\t1500\n1100\t3000\t2500\n"
        val out = LongArray(8)
        val n = LatencyParser().parse(text, out)
        assertEquals(2, n)
        assertEquals(2000L, out[0])
        assertEquals(3000L, out[1])
    }

    @Test fun redmiFocusWithModeAndRootTaskFields() {
        assertEquals("com.android.settings/com.android.settings.SubSettings",
            LayerResolver.focusedComponent(res("redmi10a_window_focus.txt")))
    }

    @Test fun redmiThermal() {
        val m = ThermalParser.parse(res("redmi10a_thermalservice.txt"))
        assertEquals(null, m["SOC"])
        assertNotNull(m["CPU"]); assertNotNull(m["BATTERY"])
    }

    @Test fun redmiTempleRunUsesAndroid11SurfaceViewLayer() {
        val comp = LayerResolver.focusedComponent(res("redmi10a_window_focus_templerun.txt"))!!
        assertEquals("com.imangi.templerun/com.imangi.unityactivity.ImangiUnityActivity", comp)
        val c = LayerResolver.candidates(res("redmi10a_sf_list_templerun.txt"), LayerResolver.packageOf(comp), comp)
        assertEquals("SurfaceView - com.imangi.templerun/com.imangi.unityactivity.ImangiUnityActivity#0", c[0])
        assertTrue(c.none { it.startsWith("Background for") || it.startsWith("Bounds for") })
        val p = LatencyParser()
        val buf = LongArray(256)
        val n = p.parse(res("redmi10a_latency_templerun.txt"), buf)
        assertTrue("n=$n", n > 100)
        val fps = (n - 1) * 1e9 / (buf[n - 1] - buf[0])
        assertTrue("fps=$fps", fps in 55.0..62.0)
    }

    // ── Popup / app-switch layer choice ──────────────────────────────────────

    private val game = "SurfaceView - com.imangi.templerun/com.imangi.unityactivity.ImangiUnityActivity#0"
    private val dialog = "com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity#0"

    @Test fun popupOverRunningGameKeepsMeasuringTheGame() {
        assertEquals(game, LayerChooser.choose(game, 60, true, listOf(dialog to 3)))
    }

    @Test fun realAppSwitchMovesToTheNewApp() {
        // The old game is in the background: it stopped drawing.
        assertEquals(dialog, LayerChooser.choose(game, 0, true, listOf(dialog to 40)))
        // The old app was closed: its layer is gone from --list.
        assertEquals(dialog, LayerChooser.choose(game, 60, false, listOf(dialog to 0)))
    }

    @Test fun nothingDrawingPicksTheNewAppsBestRankedLayer() {
        assertEquals(dialog, LayerChooser.choose(game, 0, true, listOf(dialog to 0, "other#0" to 0)))
        assertEquals(dialog, LayerChooser.choose(null, 0, false, listOf(dialog to 0)))
        assertEquals(game, LayerChooser.choose(game, 0, true, emptyList()))
        assertEquals(null, LayerChooser.choose(null, 0, false, emptyList()))
    }

    // ── Redmi Note 9 (MT6768 / Helio G85, Android 10, rooted) ────────────────

    @Test fun android10ThermalNamesAreNormalized() {
        val m = ThermalParser.parse(res("note9_thermalservice_a10.txt"))
        assertNotNull("CPU_T should map to CPU: $m", m["CPU"])
        assertNotNull(m["GPU"]); assertNotNull(m["BATTERY"])
    }

    @Test fun android10FocusAndAsphaltLayer() {
        val comp = LayerResolver.focusedComponent(res("note9_window_focus_asphalt8.txt"))!!
        assertEquals("com.gameloft.android.ANMP.GloftA8HM/com.gameloft.android.ANMP.GloftA8HM.MainActivity", comp)
        val c = LayerResolver.candidates(res("note9_sf_list_asphalt8.txt"), LayerResolver.packageOf(comp), comp)
        assertEquals("SurfaceView - com.gameloft.android.ANMP.GloftA8HM/com.gameloft.android.ANMP.GloftA8HM.MainActivity#0", c[0])
        assertTrue(c.none { it.startsWith("AppWindowToken{") })
        val p = LatencyParser()
        val buf = LongArray(256)
        val n = p.parse(res("note9_latency_asphalt8.txt"), buf)
        assertEquals(16_675_003L, p.refreshPeriodNs)
        assertTrue(n > 60)
    }

    @Test fun rootGpuNodesOnMali() {
        assertEquals(63, GpuSysfs.parseLoad("/proc/mali/utilization", res("note9_mali_utilization.txt")))
        assertEquals(1000, GpuSysfs.parseFreqMHz("/proc/gpufreq/gpufreq_var_dump", res("note9_gpufreq_var_dump.txt")))
        assertEquals(56, GpuSysfs.parseLoad("/sys/module/ged/parameters/gpu_loading", "56\n"))
    }

    @Test fun rootGpuNodesOnAdreno() {
        assertEquals(45, GpuSysfs.parseLoad("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "45 %\n"))
        assertEquals(587, GpuSysfs.parseFreqMHz("/sys/class/kgsl/kgsl-3d0/gpuclk", "587000000\n"))
    }

    @Test fun thermalZonesPickHottestSocSensorAndDropAbsentOnes() {
        val zones = res("note9_thermal_zones.txt").lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i < 0) null else ThermalZones.parse(l.substring(0, i), l.substring(i + 1))
        }
        assertTrue(zones.none { it.celsius < -40f }) // -127000 sensors removed
        val soc = ThermalZones.socTemp(zones)
        assertTrue("soc=$soc", soc in 30f..100f)
        val cpu = zones.first { it.type == "mtktscpu" }.celsius
        val ap = zones.first { it.type == "mtktsAP" }.celsius
        assertEquals(maxOf(cpu, ap), soc, 0.01f)
        assertEquals(zones.first { it.type == "mtktsbattery" }.celsius, ThermalZones.batteryTemp(zones), 0.01f)
        assertTrue(zones.none { it.type == "mtktspmic" && ThermalZones.isSocZone(it.type) })
    }

    @Test fun runningMeanSmoothsMaliDvfsSawtooth() {
        // One second of real 10 Hz samples from the Redmi Note 9 in Asphalt 8.
        val freq = intArrayOf(299, 299, 315, 349, 332, 975, 950, 900, 875, 823)
        val m = RunningMean()
        for (f in freq) m.add(f.toDouble())
        m.add(-1.0) // missing reading is ignored
        assertEquals(10, m.count)
        assertEquals(611.7, m.take(), 0.1)
        assertEquals(-1.0, m.take(), 0.0) // reset after take
    }

    // ── Color by smoothness ──────────────────────────────────────────────────

    @Test fun smoothnessTargetsRefreshOrAClearLowerCap() {
        assertEquals(60f, Smoothness.targetFps(59f, 60f), 0.01f)
        assertEquals(30f, Smoothness.targetFps(29.5f, 60f), 0.01f)   // 30 FPS game (Asphalt 8 menu)
        assertEquals(60f, Smoothness.targetFps(47.8f, 60f), 0.01f)   // struggling, not a "cap"
        assertEquals(90f, Smoothness.targetFps(88f, 120f), 0.01f)
        assertEquals(120f, Smoothness.targetFps(118f, 120f), 0.01f)
        assertEquals(60f, Smoothness.targetFps(0f, 0f), 0.01f)       // nothing known yet
    }

    @Test fun smoothnessLevels() {
        assertEquals(Smoothness.GOOD, Smoothness.fpsLevel(119f, 118f, 120f))
        assertEquals(Smoothness.GOOD, Smoothness.fpsLevel(27.9f, 29.5f, 60f))
        assertEquals(Smoothness.WARN, Smoothness.fpsLevel(47.8f, 52f, 60f))
        assertEquals(Smoothness.BAD, Smoothness.fpsLevel(20f, 52f, 60f))
        assertEquals(Smoothness.UNKNOWN, Smoothness.fpsLevel(-1f, 0f, 60f))
        assertEquals(Smoothness.GOOD, Smoothness.tempLevel(48f, false))
        assertEquals(Smoothness.WARN, Smoothness.tempLevel(62f, true))   // Redmi Note 9: capped at 63 C
        assertEquals(Smoothness.WARN, Smoothness.tempLevel(75f, false))
        assertEquals(Smoothness.BAD, Smoothness.tempLevel(90f, false))
        assertEquals(Smoothness.UNKNOWN, Smoothness.tempLevel(Float.NaN, false))
    }

    // ── Session summary ──────────────────────────────────────────────────────

    @Test fun sessionStatsOverAWholeSession() {
        val st = SessionStats()
        st.vsyncMs = 16.67f
        var now = 0L
        st.tick(now, 40f, false, true)
        // 60 s at 60 FPS with a 100 ms hitch every 10 s; throttled for the last 20 s.
        for (sec in 0 until 60) {
            for (f in 0 until 60) st.onFrame(if (f == 0 && sec % 10 == 5) 100f else 16.67f)
            for (q in 0 until 4) { now += 250; st.tick(now, 40f + sec * 0.5f, sec >= 40, true) }
        }
        val s = st.summary("com.example.game", 60f, 123L)
        assertEquals(3600L, s.frames)
        assertEquals(60_000L, s.durationMs)
        assertTrue("avg=${s.avgFps}", s.avgFps in 58f..60.1f)
        assertTrue("1% low=${s.low1Fps}", s.low1Fps < 40f)   // 6 hitches among the worst 36 frames
        assertTrue("0.1% low=${s.low01Fps}", s.low01Fps < 11f) // worst 4 frames are all 100 ms
        assertEquals(6, s.jank)
        assertEquals(69.5f, s.peakTempC, 0.01f)
        assertEquals(20_000L, s.throttledMs)
        assertEquals(60, s.avgFrameMs.size)
        assertEquals(1, s.secondsPerPoint)
        assertTrue(s.maxFrameMs.max() >= 99f)
    }

    @Test fun sessionSeriesMergesWhenFull() {
        val st = SessionStats(maxPoints = 8)
        var now = 0L
        st.tick(now, Float.NaN, false, false)
        for (sec in 0 until 20) {
            for (f in 0 until 30) st.onFrame(33.3f)
            for (q in 0 until 4) { now += 250; st.tick(now, Float.NaN, false, false) }
        }
        val s = st.summary("p", 60f, 0L)
        assertTrue(s.avgFrameMs.size <= 8)
        assertTrue(s.secondsPerPoint >= 2)
        assertEquals(-1L, s.throttledMs)          // limits not readable on this phone
        assertTrue(s.peakTempC.isNaN())
    }

    @Test fun sessionSummaryRoundTripsAsText() {
        val a = SessionSummary("com.x", 5L, 61_000L, 3600L, 59.2f, 38.5f, 10.1f, 6, 69.5f, 20_000L, 60f, 1,
            floatArrayOf(16.7f, 17.1f), floatArrayOf(16.7f, 100f))
        val b = SessionSummary.decode(a.encode())!!
        assertEquals("com.x", b.pkg); assertEquals(3600L, b.frames); assertEquals(6, b.jank)
        assertEquals(59.2f, b.avgFps, 0.001f); assertEquals(20_000L, b.throttledMs)
        assertEquals(2, b.maxFrameMs.size); assertEquals(100f, b.maxFrameMs[1], 0.001f)
        assertEquals(null, SessionSummary.decode("garbage"))
    }
}
