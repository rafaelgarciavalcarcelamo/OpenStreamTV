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
)

data class Playlist(
    val name: String,
    val channels: List<Channel>,
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
