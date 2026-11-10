package dev.openstream.tv.local

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

object LocalControlAccess {
    const val PORT = 8765
    private const val PREFS = "openstream_local_control"
    private const val KEY_ACCESS_CODE = "access_code"

    fun accessCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ACCESS_CODE, null)?.takeIf { it.length == 6 }?.let { return it }
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        prefs.edit().putString(KEY_ACCESS_CODE, code).apply()
        return code
    }

    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun mobileUrl(context: Context): String? = localAddress()?.let {
        "http://$it:$PORT/?k=${accessCode(context)}"
    }
}
