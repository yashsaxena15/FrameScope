package com.merlin.fpslite

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.merlin.fpscore.LatencyParser
import com.merlin.fpscore.LayerResolver
import com.merlin.fpscore.ThermalParser

/**
 * Runs every detection step through the privileged service and produces a text
 * report that can be shared, so device-specific paths can be tuned remotely.
 * Everything here is read-only except the optional GPU test, which starts a
 * short perfetto session and deletes its temporary trace file.
 */
class ProbeActivity : Activity() {
    private lateinit var out: TextView
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Priv.init(this)
        Priv.ensureBound()
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            fitsSystemWindows = true
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        buttons.addView(btn("Run probe") { run(gpu = false) })
        buttons.addView(btn("+ GPU test") { run(gpu = true) })
        buttons.addView(btn("Copy") { copy() })
        buttons.addView(btn("Share") { share() })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })
        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "Tap \"Run probe\". Open a game first and come back for the FPS layer check, " +
                "or use the overlay while gaming."
        }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun run(gpu: Boolean) {
        if (busy) return
        busy = true
        out.text = "Running…"
        Thread {
            val r = try { report(gpu) } catch (t: Throwable) { "Probe failed: $t" }
            runOnUiThread {
                out.text = r
                busy = false
            }
        }.start()
    }

    private fun report(gpu: Boolean): String {
        val sb = StringBuilder()
        fun h(t: String) = sb.append("\n== ").append(t).append(" ==\n")
        sb.append("FPS Lite ").append(BuildConfig.VERSION_NAME).append(" device probe\n")
        h("Device")
        sb.append("model=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" device=").append(Build.DEVICE).append('\n')
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append("soc=").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n')
        }
        @Suppress("DEPRECATION")
        val disp = windowManager.defaultDisplay
        sb.append("display modes=")
        for (m in disp.supportedModes) sb.append(m.refreshRate.toInt()).append("Hz ")
        sb.append(" current=").append(disp.refreshRate).append('\n')

        h("Backend")
        sb.append("state=").append(Priv.state()).append('\n')
        Priv.lastError?.let { sb.append("lastError=").append(it).append('\n') }
        val svc = Priv.service ?: return sb.append("Privileged service not connected. Start Shizuku and grant permission.\n").toString()
        sb.append("service uid=").append(svc.uid()).append('\n')
        sb.append(svc.exec("getprop ro.mi.os.version.incremental; getprop ro.board.platform; getprop ro.hardware.vulkan; getenforce") ?: "")

        h("CPU")
        val base = "/sys/devices/system/cpu/cpufreq"
        for (p in svc.listDir(base)) {
            if (!p.startsWith("policy")) continue
            sb.append(p).append(": cpus=").append(svc.readText("$base/$p/related_cpus")?.trim() ?: "DENIED")
            sb.append(" cur=").append(svc.readText("$base/$p/scaling_cur_freq")?.trim() ?: "DENIED")
            sb.append(" max=").append(svc.readText("$base/$p/cpuinfo_max_freq")?.trim() ?: "DENIED").append('\n')
        }
        sb.append("/proc/stat: ").append(if (svc.readText("/proc/stat") != null) "readable" else "DENIED").append('\n')

        h("RAM")
        val mi = svc.readText("/proc/meminfo")
        if (mi == null) sb.append("/proc/meminfo DENIED\n") else {
            val m = com.merlin.fpscore.MemInfo.parse(mi, com.merlin.fpscore.MemInfo())
            sb.append("total=").append(m.totalKb / 1024).append("MB used=").append(m.usedKb / 1024)
                .append("MB swap=").append(m.swapUsedKb / 1024).append('/').append(m.swapTotalKb / 1024).append("MB\n")
        }

        h("GPU sysfs candidates")
        for (path in GPU_PATHS) {
            val v = svc.readText(path)
            sb.append(if (v != null) "OK   " else "--   ").append(path)
            if (v != null) sb.append(" = ").append(v.trim().take(60).replace('\n', '|'))
            sb.append('\n')
        }

        h("Temperatures (thermalservice)")
        val t = ThermalParser.parse(svc.dump("thermalservice", emptyArray()) ?: "")
        if (t.isEmpty()) sb.append("none parsed\n")
        for ((k, v) in t) sb.append(k).append('=').append(v).append("°C  ")
        sb.append('\n')

        h("Foreground app + FPS layer")
        val comp = LayerResolver.focusedComponent(svc.dump("window", arrayOf("displays")) ?: "")
        sb.append("focus=").append(comp).append('\n')
        if (comp != null) {
            val list = svc.dump("SurfaceFlinger", arrayOf("--list")) ?: ""
            val cands = LayerResolver.candidates(list, LayerResolver.packageOf(comp), comp)
            val p = LatencyParser()
            val buf = LongArray(256)
            for (c in cands.take(6)) {
                val n = p.parse(svc.dump("SurfaceFlinger", arrayOf("--latency", c)) ?: "", buf)
                val fps = if (n > 1) (n - 1) * 1e9 / (buf[n - 1] - buf[0]) else 0.0
                sb.append(String.format("%3d frames %6.1f fps  %s\n", n, fps, c))
            }
            if (cands.isEmpty()) sb.append("no candidate layers\n")
            val pkg = LayerResolver.packageOf(comp)
            val pid = svc.exec("pidof $pkg")?.trim()?.split(' ')?.firstOrNull()?.toIntOrNull()
            val rss = pid?.let { svc.readText("/proc/$it/status") }?.let { com.merlin.fpscore.MemInfo.processRssKb(it) }
            sb.append("app pid=").append(pid).append(" rss=").append(rss?.let { "${it / 1024}MB" } ?: "DENIED").append('\n')
        }

        if (gpu) {
            h("GPU via perfetto (5 s)")
            val ok = svc.gpuStart()
            sb.append("start=").append(ok).append('\n')
            if (ok) {
                Thread.sleep(2500)
                svc.gpuSample() // discard warm-up window
                Thread.sleep(2500)
                val r = svc.gpuSample()
                sb.append("status=").append(r[0]).append(" avgActiveMHz=").append(r[1] / 1000)
                    .append(" busy%=").append(r[2] / 10.0).append(" lastMHz=").append(r[3] / 1000)
                    .append(" events=").append(r[4]).append('\n')
                svc.gpuStop()
            }
            val log = svc.gpuLog()
            if (log.isNotBlank()) sb.append("perfetto log:\n").append(log.take(1500)).append('\n')
        }
        return sb.toString()
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FPS Lite probe", out.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, out.text.toString()),
            "Share probe report"
        ))
    }

    companion object {
        private val GPU_PATHS = arrayOf(
            // Qualcomm Adreno
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            // MediaTek
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/kernel/ged/hal/current_freq",
            "/proc/gpufreqv2/gpufreq_status",
            "/proc/gpufreq/gpufreq_var_dump",
            "/proc/mali/utilization",
            "/proc/mali/frequency",
            // Generic Mali / devfreq / Exynos / Tensor
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/cur_freq",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/gpu/gpu_clock",
        )
    }
}
