package dev.openstream.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.Locale

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackDiagnostics : AnalyticsListener {
    var videoDecoder: String = "—"
        private set
    var audioDecoder: String = "—"
        private set
    var videoFormat: Format? = null
        private set
    var audioFormat: Format? = null
        private set
    var bandwidthBitsPerSecond: Long = 0
        private set
    var droppedFrames: Int = 0
        private set
    var lastLoadError: String = "—"
        private set

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        videoFormat = format
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        audioFormat = format
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        bandwidthBitsPerSecond = bitrateEstimate
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        lastLoadError = error.javaClass.simpleName
    }

    fun resetForChannel() {
        videoDecoder = "—"
        audioDecoder = "—"
        videoFormat = null
        audioFormat = null
        bandwidthBitsPerSecond = 0
        droppedFrames = 0
        lastLoadError = "—"
    }

    fun render(bufferedDurationMs: Long, liveOffsetMs: Long, decoderMode: String, qualityLimit: Int, networkState: String): String {
        val video = videoFormat
        val audio = audioFormat
        val resolution = if (video != null && video.width > 0) "${video.width}×${video.height} @ ${formatFps(video.frameRate)}" else "—"
        val videoCodec = video?.sampleMimeType ?: video?.codecs ?: "—"
        val audioCodec = audio?.sampleMimeType ?: audio?.codecs ?: "—"
        val bandwidth = if (bandwidthBitsPerSecond > 0) String.format(Locale.US, "%.1f Mbps", bandwidthBitsPerSecond / 1_000_000.0) else "—"
        val liveOffset = if (liveOffsetMs != C.TIME_UNSET) String.format(Locale.US, "%.1f s", liveOffsetMs / 1000.0) else "—"
        val limit = if (qualityLimit > 0) "${qualityLimit}p" else "automática"
        return buildString {
            appendLine("DIAGNÓSTICO")
            appendLine("Red: $networkState · estimación: $bandwidth")
            appendLine("Vídeo: $resolution · $videoCodec")
            appendLine("Decodificador vídeo: $videoDecoder ($decoderMode)")
            appendLine("Audio: $audioCodec · $audioDecoder")
            appendLine("Búfer: ${bufferedDurationMs / 1000}s · retraso directo: $liveOffset")
            appendLine("Límite adaptativo: $limit · frames perdidos: $droppedFrames")
            append("Último error de carga: $lastLoadError")
        }
    }

    private fun formatFps(value: Float): String = if (value > 0) String.format(Locale.US, "%.1f fps", value) else "fps —"
}
