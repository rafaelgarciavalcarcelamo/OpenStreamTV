package dev.openstream.tv.data

import android.content.Context

enum class BufferPreset(val label: String, val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int) {
    RAPIDO("Rápido", 5_000, 15_000, 900, 1_800),
    EQUILIBRADO("Equilibrado", 15_000, 50_000, 1_500, 3_000),
    ESTABLE("Estable", 20_000, 60_000, 8_000, 12_000),
    PERSONALIZADO("Personalizado", 15_000, 60_000, 1_500, 3_000),
}

enum class ResizeMode(val label: String) {
    AJUSTAR("Ajustar"), RELLENAR("Rellenar"), ZOOM("Zoom"), ORIGINAL("Original")
}

enum class DecoderPreference(val label: String) {
    AUTOMATICO("Automático"), HARDWARE("Priorizar hardware"), SOFTWARE("Priorizar software")
}

enum class StartupMode(val label: String) {
    LISTADO("Mostrar inicio"), ULTIMO_CANAL("Reproducir último canal"), FAVORITOS("Abrir favoritos")
}

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("openstream", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = prefs.getString("playlist_url", "") ?: ""
        set(value) = prefs.edit().putString("playlist_url", value).apply()
    var launchOnBoot: Boolean
        get() = prefs.getBoolean("launch_on_boot", false)
        set(value) = prefs.edit().putBoolean("launch_on_boot", value).apply()
    var rememberLastChannel: Boolean
        get() = prefs.getBoolean("remember_last_channel", true)
        set(value) = prefs.edit().putBoolean("remember_last_channel", value).apply()
    var lastChannelId: String
        get() = prefs.getString("last_channel_id", "") ?: ""
        set(value) = prefs.edit().putString("last_channel_id", value).apply()
    var startupMode: StartupMode
        get() = runCatching { StartupMode.valueOf(prefs.getString("startup_mode", StartupMode.LISTADO.name)!!) }.getOrDefault(StartupMode.LISTADO)
        set(value) = prefs.edit().putString("startup_mode", value.name).apply()
    var confirmExit: Boolean
        get() = prefs.getBoolean("confirm_exit", true)
        set(value) = prefs.edit().putBoolean("confirm_exit", value).apply()
    var refreshSourcesOnLaunch: Boolean
        get() = prefs.getBoolean("refresh_sources_on_launch", true)
        set(value) = prefs.edit().putBoolean("refresh_sources_on_launch", value).apply()
    var epgUrl: String
        get() = prefs.getString("epg_url", "") ?: ""
        set(value) = prefs.edit().putString("epg_url", value).apply()
    var bufferPreset: BufferPreset
        get() = runCatching { BufferPreset.valueOf(prefs.getString("buffer_preset", BufferPreset.ESTABLE.name)!!) }.getOrDefault(BufferPreset.ESTABLE)
        set(value) = prefs.edit().putString("buffer_preset", value.name).apply()
    var customMinMs: Int
        get() = prefs.getInt("custom_min", 15_000)
        set(value) = prefs.edit().putInt("custom_min", value).apply()
    var customMaxMs: Int
        get() = prefs.getInt("custom_max", 60_000)
        set(value) = prefs.edit().putInt("custom_max", value).apply()
    var customPlayMs: Int
        get() = prefs.getInt("custom_play", 1_500)
        set(value) = prefs.edit().putInt("custom_play", value).apply()
    var customRebufferMs: Int
        get() = prefs.getInt("custom_rebuffer", 3_000)
        set(value) = prefs.edit().putInt("custom_rebuffer", value).apply()
    var liveOffsetMs: Int
        get() = prefs.getInt("live_offset", 15_000)
        set(value) = prefs.edit().putInt("live_offset", value).apply()
    var userAgent: String
        get() = prefs.getString("user_agent", "OpenStreamTV/0.8") ?: "OpenStreamTV/0.8"
        set(value) = prefs.edit().putString("user_agent", value).apply()
    var connectTimeoutMs: Int
        get() = prefs.getInt("connect_timeout", 15_000)
        set(value) = prefs.edit().putInt("connect_timeout", value).apply()
    var readTimeoutMs: Int
        get() = prefs.getInt("read_timeout", 30_000)
        set(value) = prefs.edit().putInt("read_timeout", value).apply()
    var maxVideoHeight: Int
        get() = prefs.getInt("max_video_height", 1080)
        set(value) = prefs.edit().putInt("max_video_height", value).apply()
    var preferredAudioLanguage: String
        get() = prefs.getString("audio_language", "") ?: ""
        set(value) = prefs.edit().putString("audio_language", value).apply()
    var preferredSubtitleLanguage: String
        get() = prefs.getString("subtitle_language", "") ?: ""
        set(value) = prefs.edit().putString("subtitle_language", value).apply()
    var decoderFallback: Boolean
        get() = prefs.getBoolean("decoder_fallback", true)
        set(value) = prefs.edit().putBoolean("decoder_fallback", value).apply()
    var videoDecoder: DecoderPreference
        get() = runCatching { DecoderPreference.valueOf(prefs.getString("video_decoder", DecoderPreference.AUTOMATICO.name)!!) }.getOrDefault(DecoderPreference.AUTOMATICO)
        set(value) = prefs.edit().putString("video_decoder", value.name).apply()
    var audioDecoder: DecoderPreference
        get() = runCatching { DecoderPreference.valueOf(prefs.getString("audio_decoder", DecoderPreference.AUTOMATICO.name)!!) }.getOrDefault(DecoderPreference.AUTOMATICO)
        set(value) = prefs.edit().putString("audio_decoder", value.name).apply()
    var asynchronousCodecQueueing: Boolean
        get() = prefs.getBoolean("async_codec_queueing", true)
        set(value) = prefs.edit().putBoolean("async_codec_queueing", value).apply()
    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit().putBoolean("auto_reconnect", value).apply()
    var retryCount: Int
        get() = prefs.getInt("retry_count", 5)
        set(value) = prefs.edit().putInt("retry_count", value).apply()
    var retryDelayMs: Int
        get() = prefs.getInt("retry_delay", 2_000)
        set(value) = prefs.edit().putInt("retry_delay", value).apply()
    var controllerTimeoutMs: Int
        get() = prefs.getInt("controller_timeout", 4_000)
        set(value) = prefs.edit().putInt("controller_timeout", value).apply()
    var resizeMode: ResizeMode
        get() = runCatching { ResizeMode.valueOf(prefs.getString("resize_mode", ResizeMode.AJUSTAR.name)!!) }.getOrDefault(ResizeMode.AJUSTAR)
        set(value) = prefs.edit().putString("resize_mode", value.name).apply()
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(value) = prefs.edit().putBoolean("autoplay", value).apply()
    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("favorites", value).apply()

    var xtreamServer: String
        get() = prefs.getString("xtream_server", "") ?: ""
        set(value) = prefs.edit().putString("xtream_server", value).apply()
    var xtreamUsername: String
        get() = prefs.getString("xtream_username", "") ?: ""
        set(value) = prefs.edit().putString("xtream_username", value).apply()

    fun applyStableDefaults(force: Boolean = false): Boolean {
        if (!force && prefs.getBoolean("stable_defaults_v3", false)) return false
        prefs.edit()
            .putString("buffer_preset", BufferPreset.ESTABLE.name)
            .putInt("live_offset", 15_000)
            .putInt("connect_timeout", 15_000)
            .putInt("read_timeout", 30_000)
            .putInt("max_video_height", 1080)
            .putBoolean("decoder_fallback", true)
            .putBoolean("async_codec_queueing", true)
            .putBoolean("auto_reconnect", true)
            .putInt("retry_count", 5)
            .putInt("retry_delay", 2_000)
            .putBoolean("refresh_sources_on_launch", true)
            .putBoolean("stable_defaults_v3", true)
            .apply()
        return true
    }

    fun applyTvExperienceDefaults() {
        if (prefs.getBoolean("tv_experience_v2", false)) return
        prefs.edit().putBoolean("tv_experience_v2", true).apply()
    }
}
