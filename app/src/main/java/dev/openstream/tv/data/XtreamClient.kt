package dev.openstream.tv.data

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader
import java.io.FilterReader
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
        val channels = readStreams("get_live_streams") { item ->
            val streamId = item["stream_id"]?.toLongOrNull() ?: return@readStreams null
            val name = item["name"] ?: "Canal $streamId"
            val categoryId = item["category_id"]
            val extension = item["container_extension"] ?: "ts"
            val streamUrl = "$baseUrl/live/${Uri.encode(username)}/${Uri.encode(password)}/$streamId.$extension"
            Channel(
                id = "xtream:$streamId",
                name = name,
                url = streamUrl,
                group = categoryNames[categoryId] ?: "Sin categoría",
                logoUrl = item["stream_icon"],
                tvgId = item["epg_channel_id"],
            )
        }
        return Playlist("Xtream · $username", channels)
    }

    fun loadVodCategories(kind: ContentKind): Map<String, String> {
        val type = if (kind == ContentKind.SERIES) "series" else "vod"
        return parseCategories(JSONArray(request("get_${type}_categories")))
    }

    fun loadCatalog(kind: ContentKind, categoryId: String? = null): List<Channel> =
        readCatalog(kind, categoryId)

    internal fun searchCatalog(kind: ContentKind, search: VodSearch): List<Channel> =
        readCatalog(kind, null, search)

    private fun readCatalog(kind: ContentKind, categoryId: String?, search: VodSearch? = null): List<Channel> {
        val series = kind == ContentKind.SERIES
        val type = if (series) "series" else "vod"
        val categories = if (categoryId == null) runCatching { loadVodCategories(kind) }.getOrDefault(emptyMap()) else emptyMap()
        return readStreams(if (series) "get_series" else "get_vod_streams", categoryId,
            if (search == null) MAX_STREAMS else VodSearch.MAX_RESULTS,
            if (search == null) MAX_RESPONSE_CHARS else MAX_SEARCH_CHARS,
        ) { item ->
            val id = item[if (series) "series_id" else "stream_id"]?.toLongOrNull() ?: return@readStreams null
            val name = item["name"] ?: "Título $id"
            if (search != null && !search.matches(name)) return@readStreams null
            Channel(
                id = "xtream:$type:$id",
                name = name,
                url = if (series) "" else playbackUrl("movie", id, item["container_extension"]),
                group = categories[item["category_id"]] ?: "Sin categoría",
                logoUrl = item[if (series) "cover" else "stream_icon"],
                kind = kind,
                description = item["plot"],
            )
        }
    }

    fun loadEpisodes(series: Channel): List<Channel> {
        val id = series.id.substringAfterLast(':').toLong()
        val raw = JSONObject(request("get_series_info", id)).opt("episodes")
        val seasons = when (raw) {
            is JSONObject -> raw.keys().asSequence().toList().sortedBy { it.toIntOrNull() }
                .mapNotNull(raw::optJSONArray)
            is JSONArray -> List(raw.length(), raw::optJSONArray).filterNotNull()
            else -> emptyList()
        }
        return buildList {
            for (season in seasons) {
                for (index in 0 until season.length()) {
                    val item = season.optJSONObject(index) ?: continue
                    val episodeId = item.optLong("id", -1).takeIf { it >= 0 } ?: continue
                    val info = item.optJSONObject("info")
                    add(series.copy(
                        id = "xtream:episode:$episodeId",
                        name = item.optString("title").ifBlank { "Episodio ${item.optInt("episode_num", index + 1)}" },
                        url = playbackUrl("series", episodeId, item.optString("container_extension")),
                        group = "Temporada ${item.optInt("season", 1)}",
                        logoUrl = info?.optString("movie_image")?.takeIf(String::isNotBlank) ?: series.logoUrl,
                        description = info?.optString("plot")?.takeIf(String::isNotBlank) ?: series.description,
                    ))
                }
            }
        }
    }

    private fun playbackUrl(type: String, id: Long, extension: String?): String {
        val format = extension?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) } ?: "mp4"
        return "$baseUrl/$type/${Uri.encode(username)}/${Uri.encode(password)}/$id.$format"
    }

    private fun readStreams(action: String, categoryId: String? = null, limit: Int = MAX_STREAMS,
        responseLimit: Int = MAX_RESPONSE_CHARS, transform: (Map<String, String>) -> Channel?): List<Channel> =
        response(action, categoryId = categoryId) { reader ->
            val bounded = object : FilterReader(reader) {
                private var remaining = responseLimit

                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count > 0) {
                        remaining -= count
                        require(remaining >= 0) { "La respuesta Xtream supera el límite de seguridad" }
                    }
                    return count
                }
            }
            val json = JsonReader(bounded)
            val streams = LinkedHashMap<String, Channel>()
            json.beginArray()
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue()
                    continue
                }
                require(streams.size < MAX_STREAMS) { "El catálogo Xtream supera el límite de seguridad" }
                val fields = mutableMapOf<String, String>()
                json.beginObject()
                while (json.hasNext()) {
                    val name = json.nextName()
                    val token = json.peek()
                    if (name in STREAM_FIELDS && (token == JsonToken.STRING || token == JsonToken.NUMBER)) {
                        json.nextString().takeIf(String::isNotBlank)?.let { fields[name] = it }
                    } else json.skipValue()
                }
                json.endObject()
                transform(fields)?.let { streams.putIfAbsent(it.id, it) }
                if (limit < MAX_STREAMS && streams.size >= limit) return@response streams.values.toList()
            }
            json.endArray()
            streams.values.toList()
        }

    private fun parseCategories(array: JSONArray): Map<String, String> = buildMap {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotBlank() && name.isNotBlank()) put(id, name)
        }
    }

    private fun request(action: String?, seriesId: Long? = null): String =
        response(action, seriesId) { readLimited(it, MAX_RESPONSE_CHARS) }

    private fun <T> response(action: String?, seriesId: Long? = null, categoryId: String? = null, read: (Reader) -> T): T {
        val query = buildString {
            append("username=").append(encode(username))
            append("&password=").append(encode(password))
            if (action != null) append("&action=").append(encode(action))
            if (seriesId != null) append("&series_id=").append(seriesId)
            if (categoryId != null) append("&category_id=").append(encode(categoryId))
        }
        val connection = URL("$baseUrl/player_api.php?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OpenStreamTV/0.8")
        try {
            val status = connection.responseCode
            require(status in 200..299) { "El servidor Xtream respondió HTTP $status" }
            return connection.inputStream.bufferedReader().use(read)
        } finally {
            connection.disconnect()
        }
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

    companion object {
        private const val MAX_RESPONSE_CHARS = 30_000_000
        private const val MAX_SEARCH_CHARS = 120_000_000
        private const val MAX_STREAMS = 200_000
        private val STREAM_FIELDS = setOf(
            "stream_id", "series_id", "name", "category_id", "container_extension",
            "stream_icon", "cover", "epg_channel_id", "plot",
        )
    }
}
