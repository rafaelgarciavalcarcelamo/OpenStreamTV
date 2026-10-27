package dev.openstream.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BoundedStreamsTest {
    @Test fun copiesUpToLimit() {
        val bytes = ByteArray(32_769) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), BoundedStreams.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedSource() {
        BoundedStreams.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 9)
    }
}
