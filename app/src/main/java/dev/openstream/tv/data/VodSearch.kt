package dev.openstream.tv.data

import java.text.Normalizer
import java.util.Locale

internal class VodSearch(query: String) {
    private val words = normalize(query).split(separator).filter(String::isNotBlank)

    fun matches(title: String): Boolean {
        val value = normalize(title)
        return words.isNotEmpty() && words.all(value::contains)
    }

    companion object {
        const val MAX_QUERY_LENGTH = 120
        const val MAX_RESULTS = 100
        private val marks = Regex("\\p{M}+")
        private val separator = Regex("[^\\p{L}\\p{N}]+")

        private fun normalize(value: String) =
            Normalizer.normalize(value, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
    }
}
