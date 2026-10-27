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
