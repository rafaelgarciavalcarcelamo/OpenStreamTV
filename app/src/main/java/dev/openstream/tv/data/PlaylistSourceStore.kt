package dev.openstream.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PlaylistSourceStore(private val context: Context, private val vault: CredentialVault) {
    private val registryFile = File(context.filesDir, "playlist_sources.bin")
    private val prefs = context.getSharedPreferences("openstream_sources", Context.MODE_PRIVATE)
    private val preferences = Preferences(context)

    fun all(): List<PlaylistSource> = synchronized(registryLock) {
        migrateLegacyIfNeeded()
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            val json = JSONArray(vault.decrypt(registryFile.readBytes()).toString(Charsets.UTF_8))
            buildList {
                for (index in 0 until json.length()) json.optJSONObject(index)?.toSource()?.let(::add)
            }
        }.getOrDefault(emptyList())
    }

    fun active(): PlaylistSource? = synchronized(registryLock) {
        val sources = all()
        val activeId = prefs.getString(KEY_ACTIVE_ID, null)
        if (activeId == NO_ACTIVE_SOURCE) return null
        val hidden = hiddenIds()
        val enabled = sources.filterNot { it.id in hidden }
        return enabled.firstOrNull { it.id == activeId } ?: enabled.firstOrNull()
    }

    fun hiddenIds(): Set<String> = synchronized(registryLock) {
        prefs.getStringSet(KEY_HIDDEN_IDS, null)?.toSet() ?: preferences.hiddenPlaylistSourceIds
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(registryLock) {
        val sources = all()
        require(sources.any { it.id == id }) { "La lista ya no existe" }
        val hidden = hiddenIds()
        if (enabled == (id !in hidden)) return@synchronized
        val editor = prefs.edit()
        if (!enabled && active()?.id == id) {
            val next = sources.firstOrNull { it.id != id && it.id !in hidden }
            editor.putString(KEY_ACTIVE_ID, next?.id ?: NO_ACTIVE_SOURCE)
        }
        editor.putStringSet(KEY_HIDDEN_IDS, if (enabled) hidden - id else hidden + id).apply()
    }

    fun create(
        name: String,
        type: PlaylistSourceType,
        endpoint: String = "",
        username: String = "",
        password: String = "",
    ): PlaylistSource {
        val id = UUID.randomUUID().toString()
        return PlaylistSource(
            id = id,
            name = name.trim().ifBlank { "Mi lista" },
            type = type,
            endpoint = endpoint,
            username = username,
            password = password,
            cacheFile = "playlist_$id.bin",
        )
    }

    fun upsert(source: PlaylistSource) = synchronized(registryLock) {
        val updated = all().filterNot { it.id == source.id } + source
        save(updated)
    }

    fun setActive(id: String?) = synchronized(registryLock) {
        prefs.edit().putString(KEY_ACTIVE_ID, id ?: NO_ACTIVE_SOURCE).apply()
    }

    fun remove(id: String): PlaylistSource? = synchronized(registryLock) {
        val current = all()
        val removed = current.firstOrNull { it.id == id } ?: return null
        val wasActive = active()?.id == id
        val remaining = current.filterNot { it.id == id }
        save(remaining)
        val hidden = hiddenIds()
        if (wasActive) {
            setActive(remaining.firstOrNull { it.id !in hidden }?.id)
        }
        if (id in hidden) prefs.edit().putStringSet(KEY_HIDDEN_IDS, hidden - id).apply()
        return removed
    }

    private fun save(sources: List<PlaylistSource>) {
        val json = JSONArray().apply { sources.forEach { put(it.toJson()) } }
        val pending = File(context.filesDir, "${registryFile.name}.pending")
        pending.writeBytes(vault.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
        val backup = File(context.filesDir, "${registryFile.name}.backup")
        if (backup.exists()) backup.delete()
        if (registryFile.exists()) registryFile.renameTo(backup)
        if (!pending.renameTo(registryFile)) {
            if (backup.exists()) backup.renameTo(registryFile)
            error("No se pudo guardar el gestor de listas")
        }
        backup.delete()
    }

    private fun migrateLegacyIfNeeded() {
        if (registryFile.exists()) return
        File(context.filesDir, "${registryFile.name}.backup").takeIf(File::exists)?.renameTo(registryFile)
        if (registryFile.exists()) return

        val preferences = Preferences(context)
        val legacyType = prefs.getString("active_type", "m3u")
        val source = when {
            legacyType == "xtream" && File(context.filesDir, "xtream_playlist.bin").exists() -> PlaylistSource(
                id = "legacy-xtream",
                name = "Lista principal",
                type = PlaylistSourceType.XTREAM,
                endpoint = preferences.xtreamServer,
                username = preferences.xtreamUsername,
                password = vault.loadXtreamPassword(),
                cacheFile = "xtream_playlist.bin",
                cacheEncrypted = true,
            )
            File(context.filesDir, "playlist.m3u").exists() || preferences.playlistUrl.isNotBlank() -> PlaylistSource(
                id = "legacy-m3u",
                name = "Lista principal",
                type = PlaylistSourceType.M3U,
                endpoint = preferences.playlistUrl,
                cacheFile = "playlist.m3u",
                cacheEncrypted = false,
            )
            else -> null
        }
        if (source != null) {
            save(listOf(source))
            setActive(source.id)
        }
    }

    private fun PlaylistSource.toJson() = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("endpoint", endpoint).put("username", username).put("password", password)
        .put("cacheFile", cacheFile).put("cacheEncrypted", cacheEncrypted)

    private fun JSONObject.toSource(): PlaylistSource? = runCatching {
        PlaylistSource(
            id = getString("id"),
            name = optString("name", "Mi lista"),
            type = PlaylistSourceType.valueOf(getString("type")),
            endpoint = optString("endpoint"),
            username = optString("username"),
            password = optString("password"),
            cacheFile = getString("cacheFile"),
            cacheEncrypted = optBoolean("cacheEncrypted", true),
        )
    }.getOrNull()

    companion object {
        private const val KEY_ACTIVE_ID = "active_source_id"
        private const val KEY_HIDDEN_IDS = "hidden_source_ids"
        private const val NO_ACTIVE_SOURCE = ""
        private val registryLock = Any()
    }
}
