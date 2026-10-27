package dev.openstream.tv.data

import java.io.InputStream
import java.io.OutputStream

/** Limits provider downloads, including the expanded size of gzip XMLTV guides. */
object BoundedStreams {
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes > 0)
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            if (count == 0) continue
            require(total <= maxBytes - count) { "La fuente supera el tamaño máximo permitido" }
            output.write(buffer, 0, count)
            total += count
        }
    }
}
