package dev.openstream.tv.ui

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.openstream.tv.R
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.ChannelStabilityStore
import dev.openstream.tv.data.Preferences
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

class DiagnosticsActivity : ComponentActivity() {
    private lateinit var results: TextView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        useTvFullscreen()
        buildUi()
        runDiagnostics()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(42), dp(26), dp(42), dp(26))
        }
        root.addView(label("Diagnóstico automático", 34f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(label("Comprobamos la televisión, la red y la configuración sin reproducir ni descargar canales.", 16f, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        results = label("Analizando…", 17f).apply {
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = getDrawable(R.drawable.panel_surface)
            gravity = Gravity.TOP
        }
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
            bottomMargin = dp(14)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(action("Repetir diagnóstico").apply { setOnClickListener { runDiagnostics() } },
            LinearLayout.LayoutParams(dp(260), dp(52)).apply { rightMargin = dp(12) })
        actions.addView(action("Volver").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(150), dp(52)))
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(root)
    }

    private fun runDiagnostics() {
        results.text = "Analizando red, servidor, listas, guía y canales…"
        executor.execute {
            val repository = AppRepository(this)
            val preferences = Preferences(this)
            val playlist = repository.loadSavedPlaylist()
            val source = repository.activePlaylistSource()
            val guide = repository.loadSavedEpg()
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val transport = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "cable"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi‑Fi"
                else -> "otra conexión"
            }
            val endpoint = source?.endpoint?.takeIf(String::isNotBlank) ?: playlist?.channels?.firstOrNull()?.url
            val serverResult = endpoint?.let(::probeServer)
            val stability = ChannelStabilityStore(this)
            val profiles = playlist?.channels.orEmpty().map { stability.get(it.id) }
            val measured = profiles.count { it.updatedAtMs > 0 }
            val warningChannels = profiles.count {
                it.consecutiveFailures > 0 || it.lastError.isNotBlank() || it.forceSoftwareVideo || it.forceSoftwareAudio || it.maxVideoHeight in 1..720
            }
            val now = System.currentTimeMillis()
            val currentGuideEntries = guide.count { now in it.startMillis until it.endMillis }
            val report = buildString {
                appendLine(if (connected) "✓ Red conectada por $transport" else "✕ No hay conexión de red disponible")
                appendLine(if (validated) "✓ Android confirma acceso a Internet" else "⚠ Android no ha validado el acceso a Internet")
                appendLine("${if (vpn) "✓" else "•"} VPN: ${if (vpn) "activa" else "no detectada"}")
                localIpv4()?.let { appendLine("✓ Dirección local: $it") }
                appendLine()
                appendLine(if (source != null) "✓ Lista activa: ${source.name}" else "✕ No hay una lista activa")
                appendLine(if (!playlist?.channels.isNullOrEmpty()) "✓ ${playlist?.channels?.size} canales disponibles" else "✕ La lista no contiene canales")
                appendLine(when (serverResult) {
                    true -> "✓ El servidor IPTV acepta conexiones"
                    false -> "✕ El servidor IPTV no responde en este momento"
                    null -> "⚠ No se pudo identificar el servidor IPTV"
                })
                appendLine(if (guide.isNotEmpty()) "✓ Guía cargada: ${guide.size} programas · $currentGuideEntries en emisión" else "⚠ No hay guía EPG cargada")
                appendLine()
                appendLine("Búfer: ${preferences.customMinMs / 1000}–${preferences.customMaxMs / 1000} s · inicio ${preferences.customPlayMs / 1000} s")
                appendLine("Retraso objetivo: ${preferences.liveOffsetMs / 1000} s · vídeo máximo ${preferences.maxVideoHeight}p")
                appendLine("Decodificación: vídeo ${preferences.videoDecoder.label.lowercase()} · audio ${preferences.audioDecoder.label.lowercase()}")
                appendLine("Reconexión automática: ${if (preferences.autoReconnect) "activada" else "desactivada"}")
                appendLine()
                appendLine("Calidad observada: $measured canales medidos · $warningChannels con adaptación o avisos")
                appendLine(if (connected && serverResult == true && !playlist?.channels.isNullOrEmpty())
                    "\nRESULTADO: La configuración básica está preparada para reproducir."
                else "\nRESULTADO: Revisa los puntos marcados con ✕ antes de reproducir.")
            }
            repository.shutdown()
            runOnUiThread { if (!isFinishing && !isDestroyed) results.text = report }
        }
    }

    private fun probeServer(raw: String): Boolean? = runCatching {
        val uri = Uri.parse(raw)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        Socket().use { it.connect(InetSocketAddress(host, port), 3_000) }
        true
    }.getOrDefault(false)

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
