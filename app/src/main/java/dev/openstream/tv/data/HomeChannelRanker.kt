package dev.openstream.tv.data

import java.text.Normalizer
import java.util.Locale

object HomeChannelRanker {
    private val highlightTopics = listOf(
        listOf("laliga", "la liga"),
        listOf("dazn"),
        listOf("champions", "campeones"),
        listOf("formula 1", "f1"),
    )

    fun rank(
        channels: List<Channel>,
        pinnedIds: List<String>,
        favoriteIds: Set<String>,
        watchCounts: Map<String, Int>,
        limit: Int,
        smart: Boolean = true,
    ): List<Channel> {
        val result = LinkedHashMap<String, Channel>()
        val byId = channels.associateBy(Channel::id)
        fun add(channel: Channel?) {
            if (channel != null && channel.id !in result) result[channel.id] = channel
        }
        pinnedIds.forEach { add(byId[it]) }
        if (smart) {
            channels.asSequence().filter { it.id in favoriteIds }
                .sortedByDescending { watchCounts[it.id] ?: 0 }.forEach(::add)
            channels.asSequence().filter { (watchCounts[it.id] ?: 0) > 0 }
                .sortedByDescending { watchCounts[it.id] ?: 0 }.forEach(::add)
            sportHighlights(channels, 5).forEach(::add)
        }
        channels.forEach(::add)
        return result.values.take(limit.coerceAtLeast(1))
    }

    fun sportHighlights(channels: List<Channel>, perTopic: Int): List<Channel> = buildList {
        highlightTopics.forEach { words ->
            channels.asSequence().filter { channel ->
                val searchable = normalize("${channel.name} ${channel.group}")
                words.any(searchable::contains)
            }.take(perTopic).forEach(::add)
        }
    }.distinctBy(Channel::id)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase(Locale.ROOT)
}
