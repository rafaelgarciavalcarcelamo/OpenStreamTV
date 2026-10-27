package dev.openstream.tv.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.FileNotFoundException
import kotlin.math.min

/** Retries transient IPTV failures without looping on authentication or malformed media. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StableLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(MIN_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is ParserException || error is FileNotFoundException) return C.TIME_UNSET
        if (error is HttpDataSource.InvalidResponseCodeException) {
            when (error.responseCode) {
                401, 403 -> return C.TIME_UNSET
                429 -> return min(30_000L, 5_000L * loadErrorInfo.errorCount)
                404, 408, 410, 500, 502, 503, 504 -> Unit
                in 400..499 -> return C.TIME_UNSET
            }
        }
        val exponent = (loadErrorInfo.errorCount - 1).coerceIn(0, 4)
        return min(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS shl exponent)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MIN_RETRIES

    companion object {
        private const val MIN_RETRIES = 6
        private const val BASE_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 15_000L
    }
}
