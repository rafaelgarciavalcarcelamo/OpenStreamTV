package dev.openstream.tv.local

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Playlist
import dev.openstream.tv.data.PlaylistSource
import dev.openstream.tv.data.PlaylistSourceType
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LocalControlServer(
    private val context: Context,
    private val bindAddress: String = LocalControlAccess.localAddress() ?: "0.0.0.0",
) : NanoHTTPD(
    bindAddress,
    LocalControlAccess.PORT,
) {
    private val repository = AppRepository(context)
    private val page by lazy { context.assets.open("mobile/index.html").bufferedReader().use { it.readText() } }
    private val failedAuth = ConcurrentHashMap<String, AuthAttempt>()
    private val listeningSocket = AtomicReference<ServerSocket?>()

    init {
        setServerSocketFactory {
            ServerSocket().also(listeningSocket::set)
        }
    }

    fun isReachable(): Boolean = listeningSocket.get()?.let { it.isBound && !it.isClosed } == true

    fun closeServer() {
        runCatching { stop() }
        repository.shutdown()
    }

    override fun serve(session: IHTTPSession): Response = runCatching {
        if (!isLocalClient(session.remoteIpAddress)) {
            return@runCatching json(Response.Status.FORBIDDEN, errorJson("Solo disponible en la red local"))
        }
        when {
            session.method == Method.GET && session.uri == "/" -> html(page)
            session.method == Method.GET && session.uri == "/manifest.webmanifest" -> manifest()
            session.method == Method.GET && session.uri == "/api/device" -> deviceResponse()
            session.method == Method.GET && session.uri == "/api/playlists" -> authorized(session) { playlistsResponse() }
            session.method == Method.POST && session.uri == "/api/playlists/m3u" -> authorized(session) {
                val body = bodyJson(session)
                val name = body.optString("name").trim().ifBlank { "Mi lista" }
                val url = body.optString("url").trim()
                require(url.startsWith("http://") || url.startsWith("https://")) { "Pega una URL M3U válida" }
                playlistMutation { callback -> repository.importPlaylist(name, url, callback) }
            }
            session.method == Method.POST && session.uri == "/api/playlists/xtream" -> authorized(session) {
                val body = bodyJson(session)
                val name = body.optString("name").trim().ifBlank { "Xtream" }
                val server = body.optString("server").trim()
                val username = body.optString("username").trim()
                val password = body.optString("password")
                require(server.startsWith("http://") || server.startsWith("https://")) { "El servidor no es válido" }
                require(username.isNotBlank() && password.isNotBlank()) { "Completa usuario y contraseña" }
                playlistMutation { callback -> repository.importXtream(name, server, username, password, callback) }
            }
            session.uri.matches(Regex("/api/playlists/[^/]+/activate")) && session.method == Method.POST -> authorized(session) {
                val id = session.uri.substringAfter("/api/playlists/").substringBefore('/')
                playlistMutation { callback -> repository.activatePlaylistSource(id, callback) }
            }
            session.uri.matches(Regex("/api/playlists/[^/]+/refresh")) && session.method == Method.POST -> authorized(session) {
                val id = session.uri.substringAfter("/api/playlists/").substringBefore('/')
                playlistMutation { callback -> repository.refreshPlaylistSource(id, callback) }
            }
            session.uri.matches(Regex("/api/playlists/[^/]+/enabled")) && session.method == Method.POST -> authorized(session) {
                val id = session.uri.substringAfter("/api/playlists/").substringBefore('/')
                val enabled = bodyJson(session).opt("enabled")
                require(enabled is Boolean) { "Indica si la lista debe estar habilitada" }
                playlistStateMutation<Unit> { repository.setPlaylistSourceEnabled(id, enabled, it) }
            }
            session.uri.matches(Regex("/api/playlists/[^/]+")) && session.method == Method.PUT -> authorized(session) {
                val id = session.uri.substringAfterLast('/')
                val body = bodyJson(session)
                playlistMutation { callback ->
                    repository.updatePlaylistSource(
                        id = id,
                        name = body.optString("name").takeIf(String::isNotBlank),
                        endpoint = body.optString("endpoint").takeIf(String::isNotBlank),
                        username = body.optString("username").takeIf(String::isNotBlank),
                        password = body.optString("password").takeIf(String::isNotBlank),
                        callback = callback,
                    )
                }
            }
            session.uri.matches(Regex("/api/playlists/[^/]+")) && session.method == Method.DELETE -> authorized(session) {
                val id = session.uri.substringAfterLast('/')
                playlistStateMutation<Playlist?> { repository.deletePlaylistSource(id, it) }
            }
            else -> json(Response.Status.NOT_FOUND, errorJson("No encontrado"))
        }
    }.getOrElse { json(Response.Status.BAD_REQUEST, errorJson(friendlyError(it))) }

    private fun playlistsResponse(): Response {
        val activeId = repository.activePlaylistSource()?.id
        val hidden = repository.hiddenPlaylistSourceIds()
        val items = JSONArray().apply {
            repository.listPlaylistSources().forEach { source ->
                val enabled = source.id !in hidden
                put(source.toPublicJson(source.id == activeId && enabled, enabled))
            }
        }
        return json(Response.Status.OK, JSONObject().put("ok", true).put("playlists", items))
    }

    private fun <T> playlistStateMutation(operation: ((Result<T>) -> Unit) -> Unit): Response =
        awaitResult(operation).fold(
            onSuccess = {
                notifyChanged()
                json(Response.Status.OK, JSONObject().put("ok", true))
            },
            onFailure = { json(Response.Status.BAD_REQUEST, errorJson(friendlyError(it))) },
        )

    private fun playlistMutation(operation: ((Result<Playlist>) -> Unit) -> Unit): Response {
        val result = awaitResult(operation)
        return result.fold(
            onSuccess = {
                notifyChanged()
                json(Response.Status.OK, JSONObject().put("ok", true).put("channels", it.channels.size).put("name", it.name))
            },
            onFailure = { json(Response.Status.BAD_REQUEST, errorJson(friendlyError(it))) },
        )
    }

    private fun authorized(session: IHTTPSession, action: () -> Response): Response {
        val client = session.remoteIpAddress.orEmpty()
        val now = System.currentTimeMillis()
        failedAuth[client]?.takeIf { it.blockedUntil > now }?.let {
            return json(Response.Status.FORBIDDEN, errorJson("Espera un minuto antes de volver a intentarlo"))
        }
        val supplied = session.headers["x-openstream-key"]
            ?: session.parameters["k"]?.firstOrNull()
        val expected = LocalControlAccess.accessCode(context)
        val matches = supplied != null && MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
        if (matches) {
            failedAuth.remove(client)
            return action()
        }
        synchronized(failedAuth) {
            val previous = failedAuth[client]
            val recent = previous?.takeIf { now - it.firstFailure < AUTH_WINDOW_MS }
            val failures = (recent?.failures ?: 0) + 1
            failedAuth[client] = AuthAttempt(
                failures = failures,
                firstFailure = recent?.firstFailure ?: now,
                blockedUntil = if (failures >= MAX_AUTH_FAILURES) now + AUTH_BLOCK_MS else 0L,
            )
        }
        return json(Response.Status.UNAUTHORIZED, errorJson("Introduce el código que aparece en la televisión"))
    }

    private fun deviceResponse(): Response {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val payload = JSONObject()
            .put("ok", true)
            .put("name", "OpenStream TV")
            .put("model", Build.MODEL)
            .put("version", versionName)
            .put("address", LocalControlAccess.localAddress().orEmpty())
            .put("port", LocalControlAccess.PORT)
        return json(Response.Status.OK, payload).apply { addHeader("Access-Control-Allow-Origin", "*") }
    }

    private fun manifest(): Response {
        val payload = JSONObject()
            .put("name", "OpenStream TV")
            .put("short_name", "OpenStream")
            .put("start_url", "/")
            .put("display", "standalone")
            .put("background_color", "#071018")
            .put("theme_color", "#e50914")
        return newFixedLengthResponse(Response.Status.OK, "application/manifest+json", payload.toString())
            .secureHeaders()
    }

    private fun bodyJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun <T> awaitResult(operation: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        operation {
            result.set(it)
            latch.countDown()
        }
        if (!latch.await(90, TimeUnit.SECONDS)) return Result.failure(IllegalStateException("La televisión tardó demasiado en responder"))
        return result.get() ?: Result.failure(IllegalStateException("No se recibió respuesta"))
    }

    private fun PlaylistSource.toPublicJson(active: Boolean, enabled: Boolean): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type.name)
        .put("active", active)
        .put("enabled", enabled)
        .put("source", sourceLabel())
        .put("username", maskedUsername())
        .put("updatedAt", File(context.filesDir, cacheFile).takeIf(File::exists)?.lastModified() ?: 0L)

    private fun PlaylistSource.sourceLabel(): String = when (type) {
        PlaylistSourceType.FILE -> "Archivo guardado en la TV"
        else -> runCatching {
            val parsed = URL(endpoint)
            "${parsed.protocol}://${parsed.host}${if (parsed.port > 0) ":${parsed.port}" else ""}"
        }.getOrDefault("Fuente protegida")
    }

    private fun PlaylistSource.maskedUsername(): String = username.takeIf(String::isNotBlank)?.let {
        if (it.length <= 2) "••••" else "${it.take(2)}••••${it.takeLast(1)}"
    }.orEmpty()

    private fun notifyChanged() {
        context.sendBroadcast(Intent(ACTION_PLAYLISTS_CHANGED).setPackage(context.packageName))
    }

    private fun html(content: String) = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", content)
        .secureHeaders()

    private fun json(status: Response.Status, body: JSONObject) = newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
        .secureHeaders()

    private fun Response.secureHeaders(): Response = apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self' http://*:8765")
    }

    private fun isLocalClient(address: String?): Boolean = runCatching {
        val parsed = InetAddress.getByName(address)
        if (parsed.isLoopbackAddress || parsed.isLinkLocalAddress) return@runCatching true
        val remoteV4 = address.orEmpty().substringAfterLast(':')
        val localV4 = LocalControlAccess.localAddress().orEmpty()
        remoteV4.substringBeforeLast('.') == localV4.substringBeforeLast('.') && remoteV4.count { it == '.' } == 3
    }.getOrDefault(false)

    private fun friendlyError(error: Throwable): String = error.message
        ?.replace(Regex("https?://[^ ]+"), "la fuente indicada")
        ?.take(180)
        ?.ifBlank { null }
        ?: "No se pudo completar la operación"

    private fun errorJson(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        const val ACTION_PLAYLISTS_CHANGED = "dev.openstream.tv.PLAYLISTS_CHANGED"
        private const val AUTH_WINDOW_MS = 5 * 60_000L
        private const val AUTH_BLOCK_MS = 60_000L
        private const val MAX_AUTH_FAILURES = 8
    }

    private data class AuthAttempt(val failures: Int, val firstFailure: Long, val blockedUntil: Long)
}
