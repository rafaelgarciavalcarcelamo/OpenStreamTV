package dev.openstream.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import dev.openstream.tv.R
import dev.openstream.tv.BuildConfig
import dev.openstream.tv.data.AppRepository
import dev.openstream.tv.data.Channel
import dev.openstream.tv.data.ChannelStabilityStore
import dev.openstream.tv.data.DecoderPreference
import dev.openstream.tv.data.HomeChannelRanker
import dev.openstream.tv.data.Preferences
import dev.openstream.tv.data.ResizeMode
import dev.openstream.tv.local.LocalControlServer
import dev.openstream.tv.player.PlaybackDiagnostics
import dev.openstream.tv.player.PlayerFactory
import dev.openstream.tv.player.RecoveryDelayPolicy

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {
    private lateinit var playerView: PlayerView
    private lateinit var banner: LinearLayout
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var guideOverlay: LinearLayout
    private lateinit var guideList: RecyclerView
    private lateinit var guideAdapter: ChannelAdapter
    private lateinit var guideFilterButton: Button
    private lateinit var guideEmpty: TextView
    private lateinit var preferences: Preferences
    private lateinit var stabilityStore: ChannelStabilityStore
    private lateinit var repository: AppRepository
    private var playbackSourceId: String? = null
    private var touchControls: LinearLayout? = null
    private var playPause: Button? = null
    private var playlistReceiverRegistered = false
    private var player: ExoPlayer? = null
    private var channels = emptyList<Channel>()
    private var index = 0
    private var retryAttempt = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private var wasOffline = false
    private var bufferingSinceMs = 0L
    private var lastPlaybackPositionMs = C.TIME_UNSET
    private var stagnantChecks = 0
    private var hasBeenReady = false
    private var diagnosticsVisible = false
    private var networkState = "conectada"
    private var currentDecoderOverride: DecoderPreference? = null
    private var currentAudioDecoderOverride: DecoderPreference? = null
    private var currentQualityLimit = 0
    private val diagnostics = PlaybackDiagnostics()
    private var stableRunnable: Runnable? = null
    private var recoveryRunnable: Runnable? = null
    private var channelGeneration = 0
    private var guideFavoritesOnly = false
    private var bannerHideRunnable: Runnable? = null
    private var numberCommitRunnable: Runnable? = null
    private var numberBuffer = ""
    private val fallbackChannelIds = ArrayDeque<String>()

    private val playlistReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocalControlServer.ACTION_PLAYLISTS_CHANGED && !isPlaybackSourceAvailable()) finish()
        }
    }

    private fun isPlaybackSourceAvailable(): Boolean =
        playbackSourceId?.let { id ->
            id !in repository.hiddenPlaylistSourceIds() && repository.listPlaylistSources().any { it.id == id }
        } == true

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            retryHandler.post {
                if (!isNetworkAvailable()) {
                    wasOffline = true
                    networkState = "sin conexión"
                    hint.text = "Sin conexión · esperando a que vuelva la red…"
                    showBanner()
                }
            }
        }

        override fun onAvailable(network: Network) {
            retryHandler.post {
                if (wasOffline) {
                    wasOffline = false
                    networkState = "conectada"
                    cancelRecovery()
                    restartStream("Red recuperada · reconectando…")
                }
            }
        }
    }

    private val healthWatchdog = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                val now = SystemClock.elapsedRealtime()
                val bufferingLimit = if (!hasBeenReady && fallbackChannelIds.isNotEmpty()) INITIAL_FALLBACK_BUFFERING_MS else MAX_BUFFERING_MS
                if (current.playbackState == Player.STATE_BUFFERING && bufferingSinceMs > 0 && now - bufferingSinceMs >= bufferingLimit) {
                    if (!tryNextFallback("Canal sin señal · probando alternativa…")) {
                        scheduleRecovery("El flujo tarda demasiado · reiniciando…", immediate = true)
                    }
                } else if (current.playbackState == Player.STATE_ENDED && recoveryRunnable == null) {
                    if (!tryNextFallback("El directo terminó · probando alternativa…")) {
                        scheduleRecovery("El directo terminó · intentando volver…")
                    }
                } else if (current.isPlaying) {
                    val position = current.currentPosition
                    stagnantChecks = if (lastPlaybackPositionMs != C.TIME_UNSET && position - lastPlaybackPositionMs < MIN_PROGRESS_MS) stagnantChecks + 1 else 0
                    lastPlaybackPositionMs = position
                    if (stagnantChecks >= MAX_STAGNANT_CHECKS) {
                        scheduleRecovery("Reproducción bloqueada · recuperando…", immediate = true)
                    }
                } else {
                    stagnantChecks = 0
                    lastPlaybackPositionMs = current.currentPosition
                }
                updateDiagnostics()
            }
            retryHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!BuildConfig.IS_MOBILE) useTvFullscreen()
        preferences = Preferences(this)
        stabilityStore = ChannelStabilityStore(this)
        repository = AppRepository(this)
        val savedSource = repository.activePlaylistSource()
        playbackSourceId = savedSource?.id
        val savedChannels = preferences.visibleChannels(savedSource?.let(repository::loadSavedPlaylist))
        channels = HomeChannelRanker.rank(
            channels = savedChannels,
            pinnedIds = preferences.pinnedChannelOrder,
            favoriteIds = emptySet(),
            watchCounts = emptyMap(),
            limit = savedChannels.size.coerceAtLeast(1),
            smart = false,
        )
        val channelId = savedInstanceState?.getString(EXTRA_CHANNEL_ID) ?: intent.getStringExtra(EXTRA_CHANNEL_ID)
        intent.getStringArrayListExtra(EXTRA_FALLBACK_CHANNEL_IDS)
            .orEmpty()
            .filterNot { it == channelId }
            .forEach(fallbackChannelIds::addLast)
        index = channels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (guideOverlay.visibility == View.VISIBLE) hideGuide() else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(EXTRA_CHANNEL_ID, channels.getOrNull(index)?.id)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi() {
        val mobile = BuildConfig.IS_MOBILE
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(android.R.color.black))
            if (mobile) applyTouchInsets()
        }
        playerView = PlayerView(this).apply {
            useController = false
            isFocusable = true
            resizeMode = when (preferences.resizeMode) {
                ResizeMode.AJUSTAR -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                ResizeMode.RELLENAR -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ResizeMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            }
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(0xD9081724.toInt())
            background = getDrawable(R.drawable.hero_card)
        }
        title = label("", if (mobile) 20f else 27f).apply { maxLines = 2 }
        hint = label(if (mobile) "" else "OK: canales    ↑↓: cambiar    Atrás: salir", if (mobile) 14f else 16f, secondary = true)
        banner.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        banner.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.TOP).apply {
            setMargins(dp(26), dp(22), dp(26), 0)
        })
        diagnosticsView = label("", 13f).apply {
            visibility = View.GONE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xDD081019.toInt())
        }
        root.addView(
            diagnosticsView,
            FrameLayout.LayoutParams(if (mobile) ViewGroup.LayoutParams.MATCH_PARENT else dp(570), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(dp(18), dp(18), dp(18), dp(if (mobile) 72 else 18))
            },
        )
        guideOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.tv_background)
            setPadding(dp(20), dp(22), dp(20), dp(20))
            elevation = dp(20).toFloat()
            visibility = View.GONE
        }
        val guideHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        guideHeader.addView(label("Canales", 28f).apply { setTypeface(null, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(56), 1f))
        guideHeader.addView(action("Cerrar").apply { setOnClickListener { hideGuide() } }, LinearLayout.LayoutParams(dp(110), dp(52)))
        guideOverlay.addView(guideHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        guideFilterButton = action("☆  Todos los canales").apply {
            setOnClickListener {
                guideFavoritesOnly = !guideFavoritesOnly
                refreshGuide()
                guideList.post { guideList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
        }
        guideOverlay.addView(guideFilterButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(12) })
        guideAdapter = ChannelAdapter(
            onClick = { channel ->
                val chosen = channels.indexOfFirst { it.id == channel.id }
                if (chosen >= 0) play(chosen)
                hideGuide()
            },
            onLongClick = { channel -> toggleGuideFavorite(channel) },
        )
        guideList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = guideAdapter
        }
        guideOverlay.addView(guideList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        guideEmpty = label("Aún no hay favoritos. Mantén pulsado un canal para añadirlo.", 16f, secondary = true).apply { visibility = View.GONE }
        guideOverlay.addView(guideEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        if (!mobile) guideOverlay.addView(label("OK: ver canal    Mantén OK: favorito", 14f, secondary = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        if (mobile) addTouchControls(root)
        val guideWidth = if (mobile && resources.configuration.screenWidthDp < 600) ViewGroup.LayoutParams.MATCH_PARENT else dp(530)
        root.addView(guideOverlay, FrameLayout.LayoutParams(guideWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        setContentView(root)
        playerView.requestFocus()
    }

    private fun addTouchControls(root: FrameLayout) {
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xE6171717.toInt())
        }
        fun control(text: String, description: String, weight: Float = 1f, click: () -> Unit): Button =
            action(text).apply {
                contentDescription = description
                setPadding(0, 0, 0, 0)
                setOnClickListener { click() }
                controls.addView(this, LinearLayout.LayoutParams(0, dp(48), weight))
            }
        control("‹", "Volver") { finish() }
        control("−", "Canal anterior") { play(index - 1) }
        playPause = control("▶", "Reproducir") {
            player?.let { it.playWhenReady = !it.playWhenReady }
        }
        control("+", "Canal siguiente") { play(index + 1) }
        control("Canales", "Abrir selector de canales", 1.6f) { showGuide() }
        control("ⓘ", "Diagnóstico de reproducción") { toggleDiagnostics() }
        touchControls = controls
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        playerView.setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (controls.visibility == View.VISIBLE) showBanner(autoHide = true)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, playlistReceiver, IntentFilter(LocalControlServer.ACTION_PLAYLISTS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        playlistReceiverRegistered = true
        if (!isPlaybackSourceAvailable()) {
            finish()
            return
        }
        registerNetworkMonitor()
        player = PlayerFactory.create(this, preferences, { currentDecoderOverride }, { currentAudioDecoderOverride }).also {
            it.addAnalyticsListener(diagnostics)
            it.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    playPause?.apply {
                        text = if (playWhenReady) "Ⅱ" else "▶"
                        contentDescription = if (playWhenReady) "Pausar" else "Reproducir"
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        scheduleRecovery("Volviendo al punto actual del directo…", immediate = retryAttempt == 0)
                        return
                    }
                    val httpError = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()
                    if (httpError?.responseCode in setOf(401, 403)) {
                        if (tryNextFallback("Canal rechazado · probando alternativa…")) return
                        scheduleRecovery("Acceso rechazado · revisa las credenciales", unauthorized = true)
                        return
                    }
                    if (isDecoderFailure(error)) {
                        val channel = channels.getOrNull(index) ?: return
                        val isAudio = (error as? ExoPlaybackException)?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
                        if (isAudio && currentAudioDecoderOverride != DecoderPreference.SOFTWARE) {
                            stabilityStore.recordAudioDecoderFailure(channel.id, error.errorCodeName)
                            currentAudioDecoderOverride = DecoderPreference.SOFTWARE
                            restartStream("Audio: probando otro decodificador…")
                            return
                        }
                        if (!isAudio && currentDecoderOverride != DecoderPreference.SOFTWARE) {
                            stabilityStore.recordDecoderFailure(channel.id, error.errorCodeName)
                            currentDecoderOverride = DecoderPreference.SOFTWARE
                            restartStream("Vídeo: probando otro decodificador…")
                            return
                        }
                    }
                    val channel = channels.getOrNull(index)
                    if (channel != null) stabilityStore.recordSourceFailure(channel.id, error.errorCodeName)
                    if (tryNextFallback("Canal no disponible · probando alternativa…")) return
                    scheduleRecovery("Error ${error.errorCodeName} · reconectando…")
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        if (!hasBeenReady) channels.getOrNull(index)?.let { preferences.recordChannelWatched(it.id) }
                        bufferingSinceMs = 0
                        cancelRecovery()
                        scheduleStableMark()
                        hasBeenReady = true
                        hint.text = if (BuildConfig.IS_MOBILE) "En directo" else "OK: canales    ↑↓: cambiar    Atrás: salir"
                        showBanner(autoHide = true)
                    } else if (playbackState == Player.STATE_BUFFERING) {
                        stableRunnable?.let(retryHandler::removeCallbacks)
                        stableRunnable = null
                        if (bufferingSinceMs == 0L) {
                            bufferingSinceMs = SystemClock.elapsedRealtime()
                            if (hasBeenReady) adaptAfterRebuffer()
                        }
                        hint.text = "Cargando canal…"
                        showBanner()
                    } else if (playbackState == Player.STATE_ENDED) {
                        if (!tryNextFallback("El directo terminó · probando alternativa…")) {
                            scheduleRecovery("El directo terminó · intentando volver…")
                        }
                    }
                }
            })
            playerView.player = it
        }
        if (channels.isNotEmpty()) play(index)
        retryHandler.postDelayed(healthWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun play(newIndex: Int) {
        if (channels.isEmpty()) return
        channelGeneration++
        cancelRecovery()
        stableRunnable?.let(retryHandler::removeCallbacks)
        stableRunnable = null
        index = (newIndex + channels.size) % channels.size
        val channel = channels[index]
        fallbackChannelIds.remove(channel.id)
        if (preferences.rememberLastChannel) preferences.lastChannelId = channel.id
        val profile = stabilityStore.get(channel.id)
        currentDecoderOverride = if (profile.forceSoftwareVideo) DecoderPreference.SOFTWARE else null
        currentAudioDecoderOverride = if (profile.forceSoftwareAudio) DecoderPreference.SOFTWARE else null
        val learnedLimit = profile.maxVideoHeight
        val userLimit = preferences.maxVideoHeight
        currentQualityLimit = when {
            learnedLimit > 0 && userLimit > 0 -> minOf(learnedLimit, userLimit)
            learnedLimit > 0 -> learnedLimit
            else -> userLimit
        }
        applyVideoQualityLimit()
        retryAttempt = 0
        hasBeenReady = false
        bufferingSinceMs = 0
        numberBuffer = ""
        numberCommitRunnable?.let(retryHandler::removeCallbacks)
        diagnostics.resetForChannel()
        title.text = "${index + 1}  ${channel.name}"
        hint.text = "Cargando canal…"
        showBanner()
        val targetOffset = preferences.liveOffsetMs.toLong().coerceAtLeast(3_000L)
        val builder = MediaItem.Builder().setUri(channel.url).setMediaId(channel.id)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(targetOffset)
                    .setMinOffsetMs((targetOffset - 5_000L).coerceAtLeast(1_000L))
                    .setMaxOffsetMs(targetOffset + 15_000L)
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
        if (channel.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        player?.apply {
            setMediaItem(builder.build())
            prepare()
            playWhenReady = preferences.autoplay
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { play(index + 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (guideOverlay.visibility != View.VISIBLE) { play(index + 1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (guideOverlay.visibility != View.VISIBLE) { play(index - 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (guideOverlay.visibility != View.VISIBLE) { showGuide(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (guideOverlay.visibility != View.VISIBLE) { showGuide(); return true }
            KeyEvent.KEYCODE_MENU -> { if (guideOverlay.visibility == View.VISIBLE) hideGuide() else showGuide(); return true }
            KeyEvent.KEYCODE_INFO -> { toggleDiagnostics(); return true }
            KeyEvent.KEYCODE_SETTINGS -> { startActivity(Intent(this, SettingsActivity::class.java)); return true }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && event?.repeatCount == 0) {
            enterChannelNumber(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        if (playlistReceiverRegistered) {
            unregisterReceiver(playlistReceiver)
            playlistReceiverRegistered = false
        }
        retryHandler.removeCallbacksAndMessages(null)
        recoveryRunnable = null
        stableRunnable = null
        bannerHideRunnable = null
        numberCommitRunnable = null
        guideOverlay.visibility = View.GONE
        unregisterNetworkMonitor()
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        repository.shutdown()
        super.onDestroy()
    }

    private fun adaptAfterRebuffer() {
        val channel = channels.getOrNull(index) ?: return
        val previousLimit = currentQualityLimit
        val profile = stabilityStore.recordRebuffer(channel.id, previousLimit)
        if (profile.maxVideoHeight > 0 && profile.maxVideoHeight != previousLimit) {
            currentQualityLimit = profile.maxVideoHeight
            applyVideoQualityLimit()
            hint.text = "Red inestable · limitando vídeo a ${currentQualityLimit}p"
        }
    }

    private fun applyVideoQualityLimit() {
        val current = player ?: return
        current.trackSelectionParameters = current.trackSelectionParameters.buildUpon().apply {
            if (currentQualityLimit > 0) {
                setMaxVideoSize(currentQualityLimit * 16 / 9, currentQualityLimit)
            } else {
                setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            }
        }.build()
    }

    private fun scheduleStableMark() {
        stableRunnable?.let(retryHandler::removeCallbacks)
        val channelId = channels.getOrNull(index)?.id ?: return
        val generation = channelGeneration
        val task = Runnable {
            if (generation == channelGeneration && channels.getOrNull(index)?.id == channelId && player?.isPlaying == true) {
                stabilityStore.recordStable(channelId)
                retryAttempt = 0
            }
        }
        stableRunnable = task
        retryHandler.postDelayed(task, STABLE_PLAYBACK_MS)
    }

    private fun scheduleRecovery(message: String, unauthorized: Boolean = false, immediate: Boolean = false) {
        if (!preferences.autoReconnect || player == null || channels.isEmpty()) {
            hint.text = message
            return
        }
        if (recoveryRunnable != null && !immediate) return
        cancelRecovery()
        retryAttempt = (retryAttempt + 1).coerceAtMost(1_000_000)
        val delayMs = if (immediate) 0L else RecoveryDelayPolicy.delayMs(
            retryAttempt, preferences.retryCount, preferences.retryDelayMs,
            unauthorized = unauthorized, offline = wasOffline,
        )
        hint.text = "$message · reintento en ${if (delayMs == 0L) 0 else (delayMs + 999L) / 1_000L}s"
        showBanner()
        val generation = channelGeneration
        val task = Runnable {
            recoveryRunnable = null
            if (generation == channelGeneration) restartStream("Reconectando · intento $retryAttempt…")
        }
        recoveryRunnable = task
        retryHandler.postDelayed(task, delayMs)
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let(retryHandler::removeCallbacks)
        recoveryRunnable = null
    }

    private fun showBanner(autoHide: Boolean = false) {
        bannerHideRunnable?.let(retryHandler::removeCallbacks)
        banner.visibility = View.VISIBLE
        if (autoHide) {
            val task = Runnable { banner.visibility = View.GONE }
            bannerHideRunnable = task
            retryHandler.postDelayed(task, preferences.controllerTimeoutMs.toLong().coerceIn(3_000L, 12_000L))
        }
    }

    private fun showGuide() {
        touchControls?.visibility = View.GONE
        guideOverlay.visibility = View.VISIBLE
        banner.visibility = View.GONE
        refreshGuide()
        val visible = if (guideFavoritesOnly) channels.filter { it.id in preferences.favorites } else channels
        val selected = visible.indexOfFirst { it.id == channels.getOrNull(index)?.id }.coerceAtLeast(0)
        guideList.scrollToPosition(selected)
        guideList.post {
            (guideList.findViewHolderForAdapterPosition(selected)?.itemView ?: guideFilterButton).requestFocus()
        }
    }

    private fun hideGuide() {
        touchControls?.visibility = View.VISIBLE
        guideOverlay.visibility = View.GONE
        playerView.requestFocus()
        showBanner(autoHide = true)
    }

    private fun refreshGuide() {
        val visible = if (guideFavoritesOnly) channels.filter { it.id in preferences.favorites } else channels
        guideFilterButton.text = if (guideFavoritesOnly) "★  Solo favoritos" else "☆  Todos los canales"
        guideEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        val numbers = channels.mapIndexed { position, channel -> channel.id to position + 1 }.toMap()
        guideAdapter.submit(visible, emptyMap(), preferences.favorites, numbers)
    }

    private fun toggleGuideFavorite(channel: Channel) {
        val favorites = preferences.favorites.toMutableSet()
        val added = if (favorites.add(channel.id)) true else { favorites.remove(channel.id); false }
        preferences.favorites = favorites
        android.widget.Toast.makeText(this, if (added) "Añadido a favoritos" else "Quitado de favoritos", android.widget.Toast.LENGTH_SHORT).show()
        refreshGuide()
    }

    private fun enterChannelNumber(digit: Int) {
        numberBuffer = (numberBuffer + digit).takeLast(4)
        hint.text = "Ir al canal $numberBuffer…"
        showBanner()
        numberCommitRunnable?.let(retryHandler::removeCallbacks)
        val task = Runnable {
            val number = numberBuffer.toIntOrNull()
            numberBuffer = ""
            if (number != null && number in 1..channels.size) play(number - 1)
            else {
                hint.text = "Ese número de canal no existe"
                showBanner(autoHide = true)
            }
        }
        numberCommitRunnable = task
        retryHandler.postDelayed(task, 1_500L)
    }

    private fun toggleDiagnostics() {
        diagnosticsVisible = !diagnosticsVisible
        diagnosticsView.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        if (!diagnosticsVisible) return
        val current = player ?: return
        val decoderMode = (currentDecoderOverride ?: preferences.videoDecoder).label
        diagnosticsView.text = diagnostics.render(
            bufferedDurationMs = current.totalBufferedDuration,
            liveOffsetMs = current.currentLiveOffset,
            decoderMode = decoderMode,
            qualityLimit = currentQualityLimit,
            networkState = networkState,
        )
    }

    private fun isDecoderFailure(error: PlaybackException): Boolean = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

    private fun restartStream(message: String) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        hint.text = message
        showBanner()
        bufferingSinceMs = SystemClock.elapsedRealtime()
        stagnantChecks = 0
        lastPlaybackPositionMs = C.TIME_UNSET
        current.setMediaItem(item, true)
        current.prepare()
        if (!BuildConfig.IS_MOBILE) current.playWhenReady = true
    }

    private fun tryNextFallback(message: String): Boolean {
        while (fallbackChannelIds.isNotEmpty()) {
            val nextId = fallbackChannelIds.removeFirst()
            val nextIndex = channels.indexOfFirst { it.id == nextId }
            if (nextIndex >= 0 && nextIndex != index) {
                hint.text = message
                showBanner()
                play(nextIndex)
                return true
            }
        }
        return false
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = connectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun registerNetworkMonitor() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(networkCallback)
            } else {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    networkCallback,
                )
            }
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkMonitor() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_FALLBACK_CHANNEL_IDS = "fallback_channel_ids"
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val MAX_BUFFERING_MS = 40_000L
        private const val INITIAL_FALLBACK_BUFFERING_MS = 12_000L
        private const val MIN_PROGRESS_MS = 250L
        private const val MAX_STAGNANT_CHECKS = 4
        private const val STABLE_PLAYBACK_MS = 120_000L
    }
}
