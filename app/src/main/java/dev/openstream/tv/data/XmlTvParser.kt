package dev.openstream.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmlTvParser {
    fun parse(input: InputStream): List<Program> = parseGuide(input).programs

    fun parseGuide(input: InputStream): GuideData {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val result = mutableListOf<Program>()
        val channelIds = linkedMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> parseChannel(parser).forEach { nameAndId -> channelIds[nameAndId.first] = nameAndId.second }
                    "programme" -> parseProgram(parser)?.let(result::add)
                }
            }
            event = parser.next()
        }
        return GuideData(result, channelIds)
    }

    private fun parseChannel(parser: XmlPullParser): List<Pair<String, String>> {
        val id = parser.getAttributeValue(null, "id") ?: return emptyList()
        val names = mutableListOf<Pair<String, String>>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                parser.nextText().trim().takeIf(String::isNotBlank)?.let { names += it to id }
            }
            event = parser.next()
        }
        return names
    }

    private fun parseProgram(parser: XmlPullParser): Program? {
        val channel = parser.getAttributeValue(null, "channel") ?: return null
        val start = parseTime(parser.getAttributeValue(null, "start")) ?: return null
        val stop = parseTime(parser.getAttributeValue(null, "stop")) ?: return null
        var title = "Sin información"
        var description: String? = null
        var iconUrl: String? = null
        var category: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "programme")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> title = parser.nextText().trim()
                    "desc" -> description = parser.nextText().trim()
                    "icon" -> iconUrl = parser.getAttributeValue(null, "src")?.trim()
                    "category" -> if (category.isNullOrBlank()) category = parser.nextText().trim()
                }
            }
            event = parser.next()
        }
        return Program(channel, title, description, start, stop, iconUrl, category)
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
