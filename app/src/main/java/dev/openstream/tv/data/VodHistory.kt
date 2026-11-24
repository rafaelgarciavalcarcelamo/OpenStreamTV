package dev.openstream.tv.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject

class VodHistory(context: Context) {
    private val prefs = context.getSharedPreferences("openstream_vod_history", Context.MODE_PRIVATE)
    private val vault by lazy { CredentialVault(context.applicationContext) }

    data class Entry(
        val id: String, val title: String, val sourceId: String, val subtitleId: String,
        val positionMs: Long, val updatedAt: Long, internal val encryptedUrl: String,
    )

    fun find(sourceId: String, id: String) = read(key(sourceId, id))

    fun recent(sourceId: String?): List<Entry> = entries().filter { it.second.sourceId == sourceId }
        .sortedByDescending { it.second.updatedAt }.map { it.second }

    fun playbackUrl(entry: Entry): String? = runCatching {
        vault.decrypt(Base64.decode(entry.encryptedUrl, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun save(sourceId: String, id: String, title: String, url: String, subtitleId: String, positionMs: Long) {
        if (positionMs < MIN_POSITION_MS) return
        val key = key(sourceId, id)
        val previous = read(key)
        val encryptedUrl = if (previous != null && playbackUrl(previous) == url) previous.encryptedUrl else runCatching {
            Base64.encodeToString(vault.encrypt(url.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrNull() ?: return
        val editor = prefs.edit().putString(key, JSONObject()
            .put("id", id).put("source", sourceId).put("title", title).put("subtitle", subtitleId)
            .put("url", encryptedUrl).put("position", positionMs).put("updated", System.currentTimeMillis()).toString())
        if (previous == null) {
            entries().sortedByDescending { it.second.updatedAt }.drop(MAX_ENTRIES - 1).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }

    fun remove(sourceId: String, id: String) = prefs.edit().remove(key(sourceId, id)).apply()

    fun subtitle(sourceId: String, id: String): String? = prefs.getString("text:$sourceId:$id", null)

    fun setSubtitle(sourceId: String, id: String, language: String?) {
        prefs.edit().putString("text:$sourceId:$id", language).apply()
    }

    private fun key(sourceId: String, id: String) = "position:$sourceId:$id"
    private fun entries() = prefs.all.keys.filter { it.startsWith("position:") }.mapNotNull { key ->
        read(key)?.let { key to it }
    }

    private fun read(key: String): Entry? = prefs.getString(key, null)?.let { raw ->
        runCatching {
            val data = JSONObject(raw)
            Entry(data.getString("id"), data.getString("title"), data.getString("source"),
                data.getString("subtitle"), data.getLong("position"), data.getLong("updated"), data.getString("url"))
        }.getOrNull()
    }

    companion object {
        const val MIN_POSITION_MS = 10_000L
        const val SUBTITLES_OFF = "off"
        private const val MAX_ENTRIES = 16
    }
}
