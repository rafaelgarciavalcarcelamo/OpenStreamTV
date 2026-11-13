package dev.openstream.tv.player

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.openstream.tv.data.BufferPreset
import dev.openstream.tv.data.DecoderPreference
import dev.openstream.tv.data.Preferences
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object PlayerFactory {
    fun create(
        context: Context,
        preferences: Preferences,
        videoDecoderOverride: () -> DecoderPreference? = { null },
        audioDecoderOverride: () -> DecoderPreference? = { null },
    ): ExoPlayer {
        val preset = preferences.bufferPreset
        val minMs = if (preset == BufferPreset.PERSONALIZADO) preferences.customMinMs else preset.minMs
        val maxMs = if (preset == BufferPreset.PERSONALIZADO) preferences.customMaxMs else preset.maxMs
        val playMs = if (preset == BufferPreset.PERSONALIZADO) preferences.customPlayMs else preset.playMs
        val rebufferMs = if (preset == BufferPreset.PERSONALIZADO) preferences.customRebufferMs else preset.rebufferMs
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minMs, maxMs.coerceAtLeast(minMs), playMs, rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val available = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            val preference = when {
                mimeType.startsWith("video/") -> videoDecoderOverride() ?: preferences.videoDecoder
                mimeType.startsWith("audio/") -> audioDecoderOverride() ?: preferences.audioDecoder
                else -> DecoderPreference.AUTOMATICO
            }
            when (preference) {
                DecoderPreference.AUTOMATICO -> available
                DecoderPreference.HARDWARE -> available.sortedByDescending { it.hardwareAccelerated }
                DecoderPreference.SOFTWARE -> available.sortedByDescending { it.softwareOnly }
            }
        }
        val renderers = DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(preferences.decoderFallback)
            .also {
                if (preferences.asynchronousCodecQueueing && Build.VERSION.SDK_INT in 23..30) {
                    it.forceEnableMediaCodecAsynchronousQueueing()
                }
            }
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(preferences.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(preferences.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(preferences.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val dataSource = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(preferences.userAgent)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(StableLoadErrorPolicy())
            .setLiveTargetOffsetMs(preferences.liveOffsetMs.toLong())
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setReleaseTimeoutMs(1_500L)
            .setDetachSurfaceTimeoutMs(1_000L)
            .setLivePlaybackSpeedControl(
                DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMinPlaybackSpeed(0.98f)
                    .setFallbackMaxPlaybackSpeed(1.02f)
                    .setTargetLiveOffsetIncrementOnRebufferMs(3_000)
                    .build()
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                trackSelectionParameters = trackSelectionParameters.buildUpon().apply {
                    if (preferences.maxVideoHeight > 0) {
                        val width = preferences.maxVideoHeight * 16 / 9
                        setMaxVideoSize(width, preferences.maxVideoHeight)
                    }
                    if (preferences.preferredAudioLanguage.isNotBlank()) setPreferredAudioLanguage(preferences.preferredAudioLanguage)
                    if (preferences.preferredSubtitleLanguage.isNotBlank()) setPreferredTextLanguage(preferences.preferredSubtitleLanguage)
                }.build()
            }
    }
}
