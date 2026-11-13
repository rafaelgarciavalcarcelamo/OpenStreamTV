package dev.openstream.tv.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.Collections
import java.util.concurrent.TimeUnit

data class SportsEventLookup(val key: String, val title: String, val expectedDate: String)

enum class SportsMetadataStatus { VERIFIED, DATE_MISMATCH, UNKNOWN }

data class SportsMetadataResult(
    val key: String,
    val status: SportsMetadataStatus,
    val artwork: SportsEventArtwork? = null,
)

data class SportsEventArtwork(
    val officialTitle: String,
    val league: String?,
    val coverUrl: String?,
    val homeBadgeUrl: String?,
    val awayBadgeUrl: String?,
    val eventDate: String?,
)

/** Enriches XMLTV events with official sports metadata and artwork, while keeping IPTV usable offline. */
class SportsMetadataClient(context: Context) {
    private val preferences = context.getSharedPreferences("sports_metadata", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val requestedKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    fun enrich(events: List<SportsEventLookup>, callback: (SportsMetadataResult) -> Unit) {
        events.take(MAX_REQUESTS_PER_REFRESH).forEach { lookup ->
            if (!requestedKeys.add(lookup.key)) return@forEach
            executor.execute {
                val cached = loadCached(lookup.title, lookup.expectedDate)
                val result = if (cached != null) {
                    SportsMetadataResult(lookup.key, SportsMetadataStatus.VERIFIED, cached)
                } else {
                    verify(lookup).also { verified ->
                        verified.artwork?.let { saveCached(lookup.title, lookup.expectedDate, it) }
                    }
                }
                callback(result)
                if (cached == null) Thread.sleep(REQUEST_SPACING_MS)
            }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun verify(lookup: SportsEventLookup): SportsMetadataResult {
        val exact = fetch(lookup.title, lookup.expectedDate)
        if (exact != null) return SportsMetadataResult(lookup.key, SportsMetadataStatus.VERIFIED, exact)
        val anotherDate = fetch(lookup.title, null)
        val status = if (!anotherDate?.eventDate.isNullOrBlank() && anotherDate?.eventDate != lookup.expectedDate) {
            SportsMetadataStatus.DATE_MISMATCH
        } else SportsMetadataStatus.UNKNOWN
        return SportsMetadataResult(lookup.key, status)
    }

    private fun fetch(title: String, expectedDate: String?): SportsEventArtwork? {
        val query = apiFriendlyTitle(title)
        val dateFilter = expectedDate?.let { "&d=${Uri.encode(it)}" }.orEmpty()
        val url = "$BASE_URL/searchevents.php?e=${Uri.encode(query)}$dateFilter"
        return runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "OpenStreamTV/1.0").build())
                .execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} for $query")
                        return@use null
                    }
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_JSON_BYTES) return@use null
                    val root = JSONObject(body.string())
                    val events = root.optJSONArray("event") ?: root.optJSONArray("events") ?: return@use null
                    val event = events.optJSONObject(0) ?: return@use null
                    parse(event)
                }
        }.onFailure { Log.w(TAG, "Metadata request failed for $query", it) }.getOrNull()
    }

    private fun parse(event: JSONObject): SportsEventArtwork? {
        val title = event.optString("strEvent").takeIf(String::isNotBlank) ?: return null
        return SportsEventArtwork(
            officialTitle = title,
            league = event.optString("strLeague").takeIf(String::isNotBlank),
            coverUrl = sequenceOf("strThumb", "strFanart", "strBanner", "strPoster")
                .map(event::optString).firstOrNull(String::isNotBlank)?.let(::fullArtwork),
            homeBadgeUrl = event.optString("strHomeTeamBadge").takeIf(String::isNotBlank)?.let(::smallArtwork),
            awayBadgeUrl = event.optString("strAwayTeamBadge").takeIf(String::isNotBlank)?.let(::smallArtwork),
            eventDate = event.optString("dateEvent").takeIf(String::isNotBlank),
        )
    }

    private fun fullArtwork(url: String) = url.removeSuffix("/medium").removeSuffix("/small").removeSuffix("/tiny")
    private fun smallArtwork(url: String) = if (url.contains("thesportsdb.com") && !url.endsWith("/small")) "$url/small" else url

    private fun apiFriendlyTitle(value: String): String {
        var title = value.substringBefore(" - UEFA", value)
            .substringBefore(" - LALIGA", value)
            .substringBefore(" - LaLiga", value)
            .substringBefore(" - Copa", value)
        API_ALIASES.forEach { (from, to) -> title = title.replace(from, to, ignoreCase = true) }
        return title.replace(" - ", " vs ").trim()
    }

    private fun cacheKey(title: String, date: String) = "event_${"$date:$title".lowercase().hashCode()}"

    private fun loadCached(title: String, date: String): SportsEventArtwork? {
        val key = cacheKey(title, date)
        val savedAt = preferences.getLong("${key}_time", 0L)
        if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) return null
        return preferences.getString(key, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }

    private fun saveCached(title: String, date: String, artwork: SportsEventArtwork) {
        val key = cacheKey(title, date)
        val json = JSONObject().apply {
            put("strEvent", artwork.officialTitle)
            put("strLeague", artwork.league.orEmpty())
            put("strThumb", artwork.coverUrl.orEmpty())
            put("strHomeTeamBadge", artwork.homeBadgeUrl.orEmpty())
            put("strAwayTeamBadge", artwork.awayBadgeUrl.orEmpty())
            put("dateEvent", artwork.eventDate.orEmpty())
        }
        preferences.edit().putString(key, json.toString()).putLong("${key}_time", System.currentTimeMillis()).apply()
    }

    companion object {
        private const val BASE_URL = "https://www.thesportsdb.com/api/v1/json/123"
        private const val TAG = "OpenStreamSportsApi"
        private const val MAX_REQUESTS_PER_REFRESH = 10
        private const val MAX_JSON_BYTES = 512 * 1024L
        private const val CACHE_TTL_MS = 12 * 60 * 60 * 1_000L
        private const val REQUEST_SPACING_MS = 2_100L
        private val API_ALIASES = mapOf(
            "AEK Atenas" to "AEK Athens",
            "Atlético de Madrid" to "Atletico Madrid",
            "Bayern Múnich" to "Bayern Munich",
            "Inter de Milán" to "Inter Milan",
            "Liga de Campeones" to "UEFA Champions League",
        )
    }
}
