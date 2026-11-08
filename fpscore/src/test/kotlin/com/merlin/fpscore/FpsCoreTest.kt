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
}
