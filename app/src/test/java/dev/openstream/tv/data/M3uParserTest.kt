package dev.openstream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.StringReader

class M3uParserTest {
    @Test fun parsesAttributesGroupsAndUrls() {
        val source = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news.es" tvg-logo="https://example.test/news.png" group-title="Noticias",Canal Noticias
            https://example.test/live/news.m3u8
            #EXTINF:-1 group-title="Deportes",Canal Deporte
            https://example.test/live/sport.ts
        """.trimIndent()

        val playlist = M3uParser.parse(StringReader(source), "Demo")

        assertEquals("Demo", playlist.name)
        assertEquals(2, playlist.channels.size)
        assertEquals("news.es", playlist.channels[0].id)
        assertEquals("Noticias", playlist.channels[0].group)
        assertEquals("Canal Deporte", playlist.channels[1].name)
        assertNotEquals(playlist.channels[0].id, playlist.channels[1].id)
    }

    @Test fun ignoresCommentsAndDuplicateIds() {
        val source = """
            #EXTM3U
            # comentario
            #EXTINF:-1 tvg-id="same",Primero
            https://example.test/one
            #EXTINF:-1 tvg-id="same",Duplicado
            https://example.test/two
        """.trimIndent()

        assertEquals(1, M3uParser.parse(StringReader(source)).channels.size)
    }
}
