package dev.openstream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeChannelRankerTest {
    private fun channel(id: String, name: String, group: String = "General") =
        Channel(id = id, name = name, url = "https://example.test/$id", group = group)

    @Test
    fun pinnedFavoritesAndMostWatchedArePlacedFirstWithoutDuplicates() {
        val channels = listOf(
            channel("one", "Canal Uno"),
            channel("dazn", "DAZN 1"),
            channel("f1", "M+ Fórmula 1"),
            channel("champions", "Liga de Campeones"),
        )

        val ranked = HomeChannelRanker.rank(
            channels = channels,
            pinnedIds = listOf("f1"),
            favoriteIds = setOf("dazn", "f1"),
            watchCounts = mapOf("one" to 8, "dazn" to 2),
            limit = 4,
        )

        assertEquals(listOf("f1", "dazn", "one", "champions"), ranked.map(Channel::id))
    }

    @Test
    fun sportsSuggestionsCoverEachRequestedTopic() {
        val channels = listOf(
            channel("liga", "M+ LaLiga"),
            channel("dazn", "DAZN LaLiga"),
            channel("champions", "M+ Liga de Campeones"),
            channel("f1", "M+ Fórmula 1"),
            channel("other", "Noticias"),
        )

        assertEquals(listOf("liga", "dazn", "champions", "f1"),
            HomeChannelRanker.sportHighlights(channels, 1).map(Channel::id))
    }
}
