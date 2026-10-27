package dev.openstream.tv.data

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest

object M3uParser {
    private val attributePattern = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(reader: Reader, playlistName: String = "Mi lista"): Playlist {
        val lines = BufferedReader(reader).lineSequence().map(String::trim).iterator()
        var pending: Map<String, String>? = null
        val channels = mutableListOf<Channel>()

        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseInfo(line)
                line.isBlank() || line.startsWith("#") -> Unit
                pending != null -> {
                    val info = pending!!
                    val displayName = info["name"].orEmpty().ifBlank { "Canal ${channels.size + 1}" }
                    channels += Channel(
                        id = stableId(info["tvg-id"], displayName, line),
                        name = displayName,
                        url = line,
                        group = info["group-title"].orEmpty().ifBlank { "Sin categoría" },
                        logoUrl = info["tvg-logo"].takeUnless(String?::isNullOrBlank),
                        tvgId = info["tvg-id"].takeUnless(String?::isNullOrBlank),
                    )
                    pending = null
                }
            }
        }
        return Playlist(playlistName, channels.distinctBy(Channel::id))
    }

    private fun parseInfo(line: String): Map<String, String> {
        val attributes = attributePattern.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
        attributes["name"] = line.substringAfterLast(',', "").trim()
        return attributes
    }

    private fun stableId(tvgId: String?, name: String, url: String): String {
        if (!tvgId.isNullOrBlank()) return tvgId
        val bytes = MessageDigest.getInstance("SHA-256").digest("$name\u0000$url".toByteArray())
        return bytes.take(10).joinToString("") { "%02x".format(it) }
    }
}
