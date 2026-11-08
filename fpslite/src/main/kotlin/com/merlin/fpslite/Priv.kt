package com.merlin.fpslite

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/** Client side of the Shizuku UserService: binding, permission and state. */
object Priv {
    enum class State { NO_SHIZUKU, NO_PERMISSION, CONNECTING, READY }

    private const val REQUEST_CODE = 4711

    @Volatile
    var service: IPrivService? = null
        private set

    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    @Volatile private var bindRequested = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app!!.packageName, PrivService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IPrivService.Stub.asInterface(binder)
                try {
                    binder.linkToDeath({
                        service = null
                        bindRequested = false
                        notifyChanged()
                        main.postDelayed({ ensureBound() }, 1000)
                    }, 0)
                } catch (_: Throwable) {}
            }
            notifyChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindRequested = false
            notifyChanged()
        }
    }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky { ensureBound(); notifyChanged() }
        Shizuku.addBinderDeadListener {
            service = null
            bindRequested = false
            notifyChanged()
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) ensureBound()
            notifyChanged()
        }
    }

    fun shizukuAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        shizukuAlive() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun state(): State = when {
        service != null -> State.READY
        !shizukuAlive() -> State.NO_SHIZUKU
        !hasPermission() -> State.NO_PERMISSION
        else -> State.CONNECTING
    }

    fun requestPermission() {
        try {
            if (shizukuAlive() && !hasPermission()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {}
    }

    fun ensureBound() {
        if (service != null || bindRequested || app == null) return
        if (!hasPermission()) return
        try {
            Shizuku.bindUserService(args, conn)
            bindRequested = true
        } catch (_: Throwable) {
            bindRequested = false
        }
    }

    /** Unbinds and kills the privileged process (also stops perfetto). */
    fun shutdown() {
        if (app == null) return
        try { Shizuku.unbindUserService(args, conn, true) } catch (_: Throwable) {}
        service = null
        bindRequested = false
        notifyChanged()
    }

    fun addListener(r: Runnable) = listeners.add(r)
    fun removeListener(r: Runnable) = listeners.remove(r)

    private fun notifyChanged() {
        for (l in listeners) main.post(l)
    }
}
