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
    private val playlistFile = File(context.filesDir, "playlist.m3u")
    private val encryptedXtreamFile = File(context.filesDir, "xtream_playlist.bin")
    private val epgFile = File(context.filesDir, "guide.xml")
    private val sourcePrefs = context.getSharedPreferences("openstream_sources", Context.MODE_PRIVATE)
    private val vault = CredentialVault(context)

    init {
        recoverBackup(playlistFile)
        recoverBackup(encryptedXtreamFile)
        recoverBackup(epgFile)
    }

    fun loadSavedPlaylist(): Playlist? = when (sourcePrefs.getString("active_type", "m3u")) {
        "xtream" -> if (encryptedXtreamFile.exists()) runCatching {
            val text = vault.decrypt(encryptedXtreamFile.readBytes()).toString(Charsets.UTF_8)
            text.reader().use { M3uParser.parse(it, "Xtream") }
        }.getOrNull() else null
        else -> if (playlistFile.exists()) playlistFile.reader().use { M3uParser.parse(it) } else null
    }

    fun importPlaylist(source: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val pending = File(context.filesDir, "playlist.m3u.pending")
            openSource(source).use { input -> FileOutputStream(pending).use { BoundedStreams.copy(input, it, MAX_PLAYLIST_BYTES) } }
            pending.reader().use { M3uParser.parse(it, source.substringAfterLast('/').ifBlank { "Mi lista" }) }
                .also { require(it.channels.isNotEmpty()) { "La lista no contiene canales válidos" } }
                .also { replaceFile(pending, playlistFile) }
                .also { sourcePrefs.edit().putString("active_type", "m3u").apply() }
        }
        callback(result)
    }

    fun importPlaylist(uri: Uri, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val pending = File(context.filesDir, "playlist.m3u.pending")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se pudo abrir el archivo" }
                FileOutputStream(pending).use { BoundedStreams.copy(input, it, MAX_PLAYLIST_BYTES) }
            }
            pending.reader().use { M3uParser.parse(it) }
                .also { require(it.channels.isNotEmpty()) { "La lista no contiene canales válidos" } }
                .also { replaceFile(pending, playlistFile) }
                .also { sourcePrefs.edit().putString("active_type", "m3u").apply() }
        }
        callback(result)
    }

    fun importXtream(server: String, username: String, password: String, callback: (Result<Playlist>) -> Unit) = executor.execute {
        val result = runCatching {
            val prefs = Preferences(context)
            val playlist = XtreamClient(
                server = server,
                username = username,
                password = password,
                connectTimeoutMs = prefs.connectTimeoutMs,
                readTimeoutMs = prefs.readTimeoutMs,
            ).loadLivePlaylist()
            val m3u = playlist.toM3u()
            val pending = File(context.filesDir, "xtream_playlist.bin.pending")
            pending.writeBytes(vault.encrypt(m3u.toByteArray(Charsets.UTF_8)))
            replaceFile(pending, encryptedXtreamFile)
            sourcePrefs.edit().putString("active_type", "xtream").apply()
            playlist
        }
        callback(result)
    }

    fun refreshActivePlaylist(callback: (Result<Playlist>) -> Unit): Boolean {
        val preferences = Preferences(context)
        return when (sourcePrefs.getString("active_type", "m3u")) {
            "xtream" -> {
                val password = vault.loadXtreamPassword()
                if (preferences.xtreamServer.isBlank() || preferences.xtreamUsername.isBlank() || password.isBlank()) false
                else {
                    importXtream(preferences.xtreamServer, preferences.xtreamUsername, password, callback)
                    true
                }
            }
            else -> if (preferences.playlistUrl.isNotBlank()) {
                importPlaylist(preferences.playlistUrl, callback)
                true
            } else false
        }
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
