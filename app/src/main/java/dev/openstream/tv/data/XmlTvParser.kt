package dev.openstream.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmlTvParser {
    fun parse(input: InputStream): List<Program> {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val result = mutableListOf<Program>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                parseProgram(parser)?.let(result::add)
            }
            event = parser.next()
        }
        return result
    }

    private fun parseProgram(parser: XmlPullParser): Program? {
        val channel = parser.getAttributeValue(null, "channel") ?: return null
        val start = parseTime(parser.getAttributeValue(null, "start")) ?: return null
        val stop = parseTime(parser.getAttributeValue(null, "stop")) ?: return null
        var title = "Sin información"
        var description: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "programme")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText().trim()
                    "desc" -> description = parser.nextText().trim()
                }
            }
            event = parser.next()
        }
        return Program(channel, title, description, start, stop)
    }

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        val patterns = listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z", "yyyyMMddHHmmss")
        for (pattern in patterns) {
            runCatching {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return formatter.parse(value)?.time
            }
        }
        return null
    }
}
