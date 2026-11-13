package dev.openstream.tv.data

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "Sin categoría",
    val logoUrl: String? = null,
    val tvgId: String? = null,
)

data class Program(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val iconUrl: String? = null,
    val category: String? = null,
)

data class GuideData(
    val programs: List<Program>,
    val channelIdsByDisplayName: Map<String, String>,
)

data class Playlist(
    val name: String,
    val channels: List<Channel>,
    val sourceId: String? = null,
)

enum class PlaylistSourceType { M3U, XTREAM, FILE }

data class PlaylistSource(
    val id: String,
    val name: String,
    val type: PlaylistSourceType,
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val cacheFile: String,
    val cacheEncrypted: Boolean = true,
)
