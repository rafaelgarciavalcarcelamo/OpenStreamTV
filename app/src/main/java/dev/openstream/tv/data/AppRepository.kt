package dev.openstream.tv.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class AppRepository(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val epgFile = File(context.filesDir, "guide.xml")
    private val vault = CredentialVault(context)
    private val sourceStore = PlaylistSourceStore(context, vault)

    init {
        recoverBackup(epgFile)
    }

    fun listPlaylistSources(): List<PlaylistSource> = sourceStore.all()
    fun activePlaylistSource(): PlaylistSource? = sourceStore.active()
    fun loadSavedPlaylist(): Playlist? = sourceStore.active()?.let(::loadCachedPlaylist)

    fun importPlaylist(name: String, source: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val definition = sourceStore.create(name, PlaylistSourceType.M3U, endpoint = source)
            refreshSource(definition).also {
                sourceStore.upsert(definition)
                sourceStore.setActive(definition.id)
            }
        }
        callback(result)
    }

    fun importPlaylist(uri: Uri, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val definition = sourceStore.create("Lista importada", PlaylistSourceType.FILE)
            val pending = File(context.filesDir, "${definition.cacheFile}.pending")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo" }
                FileOutputStream(pending).use { BoundedStreams.copy(input, it, MAX_PLAYLIST_BYTES) }
            }
            val playlist = pending.reader().use { M3uParser.parse(it, definition.name) }
                .also { require(it.channels.isNotEmpty()) { "La lista no contiene canales válidos" } }
            savePlaylistCache(definition, pending.readText())
            pending.delete()
            sourceStore.upsert(definition)
            sourceStore.setActive(definition.id)
            playlist
        }
        callback(result)
    }

    fun importXtream(name: String, server: String, username: String, password: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val definition = sourceStore.create(
                name = name,
                type = PlaylistSourceType.XTREAM,
                endpoint = server,
                username = username,
                password = password,
            )
            refreshSource(definition).also {
                sourceStore.upsert(definition)
                sourceStore.setActive(definition.id)
            }
        }
        callback(result)
    }

    fun activatePlaylistSource(id: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val definition = sourceStore.all().firstOrNull { it.id == id } ?: error("La lista ya no existe")
            val playlist = loadCachedPlaylist(definition) ?: refreshSource(definition)
            sourceStore.setActive(id)
            playlist
        }
        callback(result)
    }

    fun deletePlaylistSource(id: String, callback: (Result<Playlist?>) -> Unit) = executor.execute {
        val result = runCatching {
            val removed = sourceStore.remove(id) ?: error("La lista ya no existe")
            File(context.filesDir, removed.cacheFile).delete()
            sourceStore.active()?.let { loadCachedPlaylist(it) ?: refreshSource(it) }
        }
        callback(result)
    }

    fun updatePlaylistSource(
        id: String,
        name: String?,
        endpoint: String?,
        username: String?,
        password: String?,
        callback: (Result<Playlist>) -> Unit,
    ) = executor.execute {
        val result = runCatching {
            val current = sourceStore.all().firstOrNull { it.id == id } ?: error("La lista ya no existe")
            val updated = current.copy(
                name = name?.trim()?.takeIf(String::isNotBlank) ?: current.name,
                endpoint = endpoint?.trim()?.takeIf(String::isNotBlank) ?: current.endpoint,
                username = username?.trim()?.takeIf(String::isNotBlank) ?: current.username,
                password = password?.takeIf(String::isNotBlank) ?: current.password,
            )
            val playlist = when (updated.type) {
                PlaylistSourceType.FILE -> loadCachedPlaylist(updated)?.copy(name = updated.name)
                    ?: error("No hay copia de la lista importada")
                else -> refreshSource(updated)
            }
            sourceStore.upsert(updated)
            playlist
        }
        callback(result)
    }

    fun refreshPlaylistSource(id: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        callback(runCatching {
            val source = sourceStore.all().firstOrNull { it.id == id } ?: error("La lista ya no existe")
            require(source.type != PlaylistSourceType.FILE && source.endpoint.isNotBlank()) {
                "Esta lista no tiene una fuente actualizable"
            }
            refreshSource(source)
        })
    }

    fun refreshActivePlaylist(callback: (Result<Playlist>) -> Unit): Boolean {
        val source = sourceStore.active() ?: return false
        if (source.type == PlaylistSourceType.FILE || source.endpoint.isBlank()) return false
        executor.execute { callback(runCatching { refreshSource(source) }) }
        return true
    }

    private fun refreshSource(source: PlaylistSource): Playlist = when (source.type) {
        PlaylistSourceType.M3U -> {
            val pending = File(context.filesDir, "${source.cacheFile}.download")
            openSource(source.endpoint).use { input ->
                FileOutputStream(pending).use { BoundedStreams.copy(input, it, MAX_PLAYLIST_BYTES) }
            }
            val text = pending.readText()
            pending.delete()
            text.reader().use { M3uParser.parse(it, source.name) }
                .also { require(it.channels.isNotEmpty()) { "La lista no contiene canales válidos" } }
                .also { savePlaylistCache(source, text) }
        }
        PlaylistSourceType.XTREAM -> {
            val prefs = Preferences(context)
            XtreamClient(
                server = source.endpoint,
                username = source.username,
                password = source.password,
                connectTimeoutMs = prefs.connectTimeoutMs,
                readTimeoutMs = prefs.readTimeoutMs,
            ).loadLivePlaylist().let { playlist ->
                val named = playlist.copy(name = source.name)
                savePlaylistCache(source, named.toM3u())
                named
            }
        }
        PlaylistSourceType.FILE -> loadCachedPlaylist(source) ?: error("No hay copia de la lista importada")
    }

    private fun loadCachedPlaylist(source: PlaylistSource): Playlist? {
        val file = File(context.filesDir, source.cacheFile)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            val text = if (source.cacheEncrypted) vault.decrypt(bytes).toString(Charsets.UTF_8)
            else bytes.toString(Charsets.UTF_8)
            text.reader().use { M3uParser.parse(it, source.name) }
        }.getOrNull()
    }

    private fun savePlaylistCache(source: PlaylistSource, text: String) {
        val pending = File(context.filesDir, "${source.cacheFile}.pending")
        val bytes = text.toByteArray(Charsets.UTF_8)
        pending.writeBytes(if (source.cacheEncrypted) vault.encrypt(bytes) else bytes)
        replaceFile(pending, File(context.filesDir, source.cacheFile))
    }

    fun importEpg(source: String, callback: (Result<List<Program>>) -> Unit) = executor.execute {
        val result = runCatching {
            val pending = File(context.filesDir, "guide.xml.pending")
            openSource(source).use { original ->
                val buffered = BufferedInputStream(original)
                buffered.mark(2)
                val isGzip = buffered.read() == 0x1f && buffered.read() == 0x8b
                buffered.reset()
                val decoded: InputStream = if (isGzip) GZIPInputStream(buffered) else buffered
                decoded.use { input -> FileOutputStream(pending).use { BoundedStreams.copy(input, it, MAX_EPG_BYTES) } }
            }
            FileInputStream(pending).use(XmlTvParser::parse)
                .also { replaceFile(pending, epgFile) }
        }
        callback(result)
    }

    fun loadSavedEpg(): List<Program> = if (epgFile.exists()) {
        runCatching { FileInputStream(epgFile).use(XmlTvParser::parse) }.getOrDefault(emptyList())
    } else emptyList()

    fun loadSavedEpgAsync(callback: (List<Program>) -> Unit) = executor.execute {
        callback(loadSavedEpg())
    }

    private fun openSource(source: String): InputStream {
        val preferences = Preferences(context)
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = preferences.connectTimeoutMs
        connection.readTimeout = preferences.readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", preferences.userAgent)
        require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        return connection.inputStream
    }

    fun shutdown() = executor.shutdownNow()

    private fun replaceFile(pending: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.backup")
        if (backup.exists() && !backup.delete()) error("No se pudo limpiar la copia temporal anterior")
        if (target.exists() && !target.renameTo(backup)) error("No se pudo proteger la lista anterior")
        if (!pending.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("No se pudo activar la nueva lista")
        }
        backup.delete()
    }

    private fun recoverBackup(target: File) {
        val backup = File(target.parentFile, "${target.name}.backup")
        if (!target.exists() && backup.exists()) backup.renameTo(target)
    }

    private fun Playlist.toM3u(): String = buildString {
        appendLine("#EXTM3U")
        channels.forEach { channel ->
            fun safe(value: String?) = value.orEmpty().replace('"', '\'')
            append("#EXTINF:-1 tvg-id=\"").append(safe(channel.tvgId)).append("\"")
            append(" tvg-logo=\"").append(safe(channel.logoUrl)).append("\"")
            append(" group-title=\"").append(safe(channel.group)).append("\",")
            appendLine(channel.name.replace('\n', ' '))
            appendLine(channel.url)
        }
    }

    companion object {
        private const val MAX_PLAYLIST_BYTES = 64L * 1024L * 1024L
        private const val MAX_EPG_BYTES = 128L * 1024L * 1024L
    }
}
