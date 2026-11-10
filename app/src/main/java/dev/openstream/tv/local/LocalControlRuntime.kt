package dev.openstream.tv.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LocalControlRuntime {
    @Volatile private var server: LocalControlServer? = null
    @Volatile private var monitorStarted = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val monitor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "OpenStream local control monitor").apply { isDaemon = true }
    }

    fun start(context: Context) {
        val appContext = context.applicationContext
        ensureServer(appContext)
        startMonitor(appContext)
    }

    private fun ensureServer(context: Context) {
        if (server?.isReachable() == true) return
        synchronized(this) {
            if (server?.isReachable() == true) return
            server?.closeServer()
            server = null
            val candidate = LocalControlServer(context)
            runCatching { candidate.start(5_000, false) }
                .onSuccess {
                    server = candidate
                    advertise(context)
                }
        }
    }

    private fun startMonitor(context: Context) {
        if (monitorStarted) return
        synchronized(this) {
            if (monitorStarted) return
            monitorStarted = true
            monitor.scheduleWithFixedDelay(
                { runCatching { ensureServer(context) } },
                10,
                15,
                TimeUnit.SECONDS,
            )
        }
    }

    fun isRunning(): Boolean = server?.isReachable() == true
    fun mobileUrl(context: Context): String? = LocalControlAccess.mobileUrl(context)
    fun accessCode(context: Context): String = LocalControlAccess.accessCode(context)

    private fun advertise(context: Context) {
        if (registrationListener != null) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceName = "OpenStream TV - ${Build.MODEL.take(24)}"
            serviceType = "_openstream._tcp."
            port = LocalControlAccess.PORT
            setAttribute("version", "1")
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registrationListener = listener }
    }
}
