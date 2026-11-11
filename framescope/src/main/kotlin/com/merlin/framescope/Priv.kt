package com.merlin.framescope

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Starts and supervises the privileged helper process ([PrivMain]) through
 * Shizuku's remote-process API, and exposes it as [service].
 */
object Priv {
    enum class State { NO_SHIZUKU, NO_PERMISSION, NO_ROOT, CONNECTING, FAILED, READY }

    /** How the running helper was started, for the UI and the probe report. */
    enum class Via { NONE, SHIZUKU, ROOT }

    @Volatile
    var via: Via = Via.NONE
        private set

    /** True when the helper runs as uid 0 (su, or Shizuku started with root). */
    val isRoot: Boolean get() = (service as? PrivClient)?.helperUid == 0

    private const val REQUEST_CODE = 4711

    @Volatile
    var service: PrivApi? = null
        private set

    /** Last connection error, shown in the UI and the probe report. */
    @Volatile
    var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var connecting = false
    @Volatile private var wanted = false
    private var failures = 0

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { if (wanted) ensureBound(); notifyChanged() }
        Shizuku.addBinderDeadListener {
            dropService()
            notifyChanged()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED && wanted) ensureBound()
            notifyChanged()
        }
    }

    fun shizukuAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        shizukuAlive() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun access(): Int = app?.let { Prefs(it).access } ?: Prefs.ACCESS_AUTO

    /** Path of a usable `su` binary (Magisk, KernelSU, APatch, SuperSU), or null. */
    fun suPath(): String? {
        val dirs = (System.getenv("PATH") ?: "").split(':') +
            listOf("/sbin", "/system/bin", "/system/xbin", "/system_ext/bin", "/debug_ramdisk",
                "/data/adb/ksu/bin", "/data/adb/ap/bin", "/su/bin")
        for (d in dirs) {
            if (d.isEmpty()) continue
            val f = java.io.File(d, "su")
            try { if (f.exists()) return f.absolutePath } catch (_: Throwable) {}
        }
        return null
    }

    private fun canUseShizuku() = access() != Prefs.ACCESS_ROOT && hasPermission()
    private fun canUseRoot() = access() != Prefs.ACCESS_SHIZUKU && suPath() != null

    fun state(): State = when {
        service != null -> State.READY
        connecting -> State.CONNECTING
        lastError != null && (canUseShizuku() || canUseRoot()) -> State.FAILED
        canUseShizuku() || canUseRoot() -> State.CONNECTING
        access() == Prefs.ACCESS_ROOT -> State.NO_ROOT
        !shizukuAlive() -> State.NO_SHIZUKU
        else -> State.NO_PERMISSION
    }

    fun requestPermission() {
        try {
            if (shizukuAlive() && !hasPermission()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {}
    }

    /** Starts the helper if needed. Safe to call repeatedly from any thread. */
    fun ensureBound() {
        wanted = true
        val ctx = app ?: return
        synchronized(this) {
            if (service != null || connecting || !(canUseShizuku() || canUseRoot())) return
            connecting = true
        }
        notifyChanged()
        Thread({ connect(ctx) }, "priv-connect").start()
    }

    private fun connect(ctx: Context) {
        var proc: Process? = null
        try {
            val apk = ctx.applicationInfo.sourceDir
            val launch = "CLASSPATH='$apk' exec app_process /system/bin --nice-name=${ctx.packageName}:priv " +
                PrivMain::class.java.name
            val useShizuku = canUseShizuku()
            val su = if (useShizuku) null else suPath()
            proc = if (useShizuku) {
                newProcess(arrayOf("sh", "-c", launch))
            } else {
                // Root: the same helper, started by su. Magisk/KernelSU asks the user once.
                ProcessBuilder(su ?: "su", "-c", launch).start()
            }
            val p = proc
            val stderr = StringBuilder()
            Thread({
                try {
                    p.errorStream.bufferedReader().forEachLine {
                        synchronized(stderr) { if (stderr.length < 2000) stderr.append(it).append('\n') }
                    }
                } catch (_: Throwable) {}
            }, "priv-stderr").apply { isDaemon = true }.start()

            val client = PrivClient(p)
            // Watchdog: a helper that never answers must not block forever.
            // Root needs time for the user to answer the superuser prompt.
            val ok = runWithTimeout(if (useShizuku) 10_000 else 30_000) { client.handshake() }
            if (!ok) {
                p.destroy()
                lastError = "helper did not start: " + synchronized(stderr) { stderr.toString().take(400) }
                failures++
                return
            }
            lastError = null
            failures = 0
            via = if (useShizuku) Via.SHIZUKU else Via.ROOT
            service = client
            Thread({
                try { p.waitFor() } catch (_: Throwable) {}
                client.markDead()
                if (service === client) {
                    service = null
                    via = Via.NONE
                    lastError = "helper exited: " + synchronized(stderr) { stderr.toString().take(400) }
                    notifyChanged()
                    if (wanted) main.postDelayed({ ensureBound() }, 2000)
                }
            }, "priv-watch").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            try { proc?.destroy() } catch (_: Throwable) {}
            lastError = "start failed: $t"
            failures++
        } finally {
            connecting = false
            notifyChanged()
            if (service == null && wanted && failures in 1..3) main.postDelayed({ ensureBound() }, 3000)
        }
    }

    /** Shizuku.newProcess is private in API 13 but is the stable remote-process entry point. */
    private fun newProcess(cmd: Array<String>): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as Process
    }

    private fun runWithTimeout(ms: Long, block: () -> Boolean): Boolean {
        var result = false
        val t = Thread({ result = try { block() } catch (_: Throwable) { false } }, "priv-handshake")
        t.isDaemon = true
        t.start()
        t.join(ms)
        return if (t.isAlive) false else result
    }

    private fun dropService() {
        if (via != Via.SHIZUKU) return // a root helper does not depend on Shizuku
        (service as? PrivClient)?.markDead()
        service = null
        via = Via.NONE
    }

    /** Stops the helper (which also stops perfetto). */
    fun shutdown() {
        wanted = false
        val s = service
        service = null
        via = Via.NONE
        try { s?.exit() } catch (_: Throwable) {}
        notifyChanged()
    }

    fun addListener(r: Runnable) = listeners.add(r)
    fun removeListener(r: Runnable) = listeners.remove(r)

    private fun notifyChanged() {
        for (l in listeners) main.post(l)
    }
}
