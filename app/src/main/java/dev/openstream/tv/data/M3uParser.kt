package dev.openstream.tv.data

import java.io.BufferedReader
import java.io.Reader
import java.security.MessageDigest

object M3uParser {
    private val attributePattern = Regex("""([\w-]+)="([^"]*)"""")
    private val seriesGroup = Regex("""\b(series?|tv shows?|temporadas?)\b""", RegexOption.IGNORE_CASE)
    private val movieGroup = Regex("""\b(vod|movies?|films?|pel[ií]culas?|cine)\b""", RegexOption.IGNORE_CASE)
    private val episodeName = Regex("""\bS\d{1,3}E\d{1,4}\b""", RegexOption.IGNORE_CASE)

    fun parse(reader: Reader, playlistName: String = "Mi lista"): Playlist {
        val lines = BufferedReader(reader).lineSequence().map(String::trim).iterator()
        var pending: Map<String, String>? = null
        val channels = LinkedHashMap<String, Channel>()

        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = parseInfo(line)
                line.isBlank() || line.startsWith("#") -> Unit
                pending != null -> {
                    val info = pending!!
                    val displayName = info["name"].orEmpty().ifBlank { "Canal ${channels.size + 1}" }
                    val group = info["group-title"].orEmpty().ifBlank { "Sin categoría" }
                    val path = line.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
                    val kind = when {
                        path.startsWith("live/", true) -> ContentKind.LIVE
                        path.startsWith("series/", true) || seriesGroup.containsMatchIn(group) ||
                            episodeName.containsMatchIn(displayName) -> ContentKind.SERIES
                        path.startsWith("movie/", true) || path.endsWith(".mp4", true) ||
                            path.endsWith(".mkv", true) || movieGroup.containsMatchIn(group) -> ContentKind.MOVIE
                        else -> ContentKind.LIVE
                    }
                    val id = stableId(info["tvg-id"].takeIf { kind == ContentKind.LIVE }, displayName, line)
                    channels.putIfAbsent(id, Channel(
                        id = id,
                        name = displayName,
                        url = line,
                        group = group,
                        logoUrl = info["tvg-logo"].takeUnless(String?::isNullOrBlank),
                        tvgId = info["tvg-id"].takeUnless(String?::isNullOrBlank),
                        kind = kind,
                        description = (info["tvg-description"] ?: info["description"] ?: info["plot"])
                            ?.takeIf(String::isNotBlank),
                    ))
                    pending = null
                }
            }
        }
        return Playlist(playlistName, channels.values.toList())
    }

    private fun parseInfo(line: String): Map<String, String> {
        val matches = attributePattern.findAll(line).toList()
        val attributes = matches.associate { it.groupValues[1] to it.groupValues[2] }.toMutableMap()
        val attributeEnd = matches.lastOrNull()?.range?.last?.plus(1) ?: 0
        val separator = line.indexOf(',', attributeEnd)
        if (separator >= 0) attributes["name"] = line.substring(separator + 1).trim()
        return attributes
    }

    private fun stableId(tvgId: String?, name: String, url: String): String {
        if (!tvgId.isNullOrBlank()) return tvgId
        val bytes = MessageDigest.getInstance("SHA-256").digest("$name\u0000$url".toByteArray())
        return buildString(20) {
            for (index in 0 until 10) {
                val value = bytes[index].toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0x0f])
            }
        }
    }
}
