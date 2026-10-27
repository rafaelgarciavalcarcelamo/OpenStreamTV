package dev.openstream.tv.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class XtreamClient(
    server: String,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {
    private val baseUrl = server.trim().trimEnd('/').removeSuffix("/player_api.php").also {
        require(it.startsWith("http://") || it.startsWith("https://")) { "El servidor debe empezar por http:// o https://" }
    }

    fun loadLivePlaylist(): Playlist {
        require(username.isNotBlank() && password.isNotBlank()) { "Usuario y contraseña son obligatorios" }
        val account = JSONObject(request(null))
        val userInfo = account.optJSONObject("user_info") ?: error("Respuesta Xtream no válida")
        val authenticated = userInfo.optInt("auth", userInfo.optString("auth").toIntOrNull() ?: 0) == 1
        require(authenticated) { "Credenciales Xtream rechazadas" }
        val status = userInfo.optString("status")
        require(status.isBlank() || status.equals("Active", true)) { "Cuenta Xtream: $status" }

        val categoryNames = parseCategories(JSONArray(request("get_live_categories")))
        val streams = JSONArray(request("get_live_streams"))
        val channels = ArrayList<Channel>(streams.length())
        for (index in 0 until streams.length()) {
            val item = streams.optJSONObject(index) ?: continue
            val streamId = item.optLong("stream_id", -1)
            if (streamId < 0) continue
            val name = item.optString("name").ifBlank { "Canal $streamId" }
            val categoryId = item.optString("category_id")
            val extension = item.optString("container_extension").ifBlank { "ts" }
            val streamUrl = "$baseUrl/live/${Uri.encode(username)}/${Uri.encode(password)}/$streamId.$extension"
            channels += Channel(
                id = "xtream:$streamId",
                name = name,
                url = streamUrl,
                group = categoryNames[categoryId] ?: "Sin categoría",
                logoUrl = item.optString("stream_icon").takeIf(String::isNotBlank),
                tvgId = item.optString("epg_channel_id").takeIf(String::isNotBlank),
            )
        }
        require(channels.isNotEmpty()) { "La cuenta Xtream no devolvió canales en directo" }
        return Playlist("Xtream · $username", channels.distinctBy(Channel::id))
    }

    private fun parseCategories(array: JSONArray): Map<String, String> = buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotBlank() && name.isNotBlank()) put(id, name)
        }
    }

    private fun request(action: String?): String {
        val query = buildString {
            append("username=").append(encode(username))
            append("&password=").append(encode(password))
            if (action != null) append("&action=").append(encode(action))
        }
        val connection = URL("$baseUrl/player_api.php?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OpenStreamTV/0.8")
        val status = connection.responseCode
        require(status in 200..299) { "El servidor Xtream respondió HTTP $status" }
        return connection.inputStream.bufferedReader().use { readLimited(it, MAX_RESPONSE_CHARS) }
    }

    private fun readLimited(reader: Reader, limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(result.length + count <= limit) { "La respuesta Xtream supera el límite de seguridad" }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object { private const val MAX_RESPONSE_CHARS = 30_000_000 }
}
