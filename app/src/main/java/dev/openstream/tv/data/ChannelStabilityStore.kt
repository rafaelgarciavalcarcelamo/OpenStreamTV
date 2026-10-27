package dev.openstream.tv.data

import android.content.Context
import org.json.JSONObject

data class ChannelStabilityProfile(
    val consecutiveFailures: Int = 0,
    val failureWindowStartedMs: Long = 0,
    val rebufferCount: Int = 0,
    val maxVideoHeight: Int = 0,
    val forceSoftwareVideo: Boolean = false,
    val forceSoftwareAudio: Boolean = false,
    val lastError: String = "",
    val updatedAtMs: Long = 0,
)

class ChannelStabilityStore(context: Context) {
    private val prefs = context.getSharedPreferences("channel_stability", Context.MODE_PRIVATE)

    @Synchronized
    fun get(channelId: String): ChannelStabilityProfile = decode(prefs.getString(key(channelId), null))

    @Synchronized
    fun recordSourceFailure(channelId: String, error: String, nowMs: Long = System.currentTimeMillis()): ChannelStabilityProfile {
        val previous = get(channelId)
        val insideWindow = nowMs - previous.failureWindowStartedMs <= FAILURE_WINDOW_MS
        val failures = if (insideWindow) previous.consecutiveFailures + 1 else 1
        return previous.copy(
            consecutiveFailures = failures,
            failureWindowStartedMs = if (insideWindow) previous.failureWindowStartedMs else nowMs,
            lastError = error.take(180),
            updatedAtMs = nowMs,
        ).also { save(channelId, it) }
    }

    @Synchronized
    fun recordRebuffer(channelId: String, baseHeight: Int, nowMs: Long = System.currentTimeMillis()): ChannelStabilityProfile {
        val previous = get(channelId)
        val count = previous.rebufferCount + 1
        val reduce = count >= REBUFFERS_BEFORE_REDUCTION
        val currentHeight = previous.maxVideoHeight.takeIf { it > 0 } ?: baseHeight.takeIf { it > 0 } ?: 2160
        return previous.copy(
            rebufferCount = if (reduce) 0 else count,
            maxVideoHeight = if (reduce) nextLowerHeight(currentHeight) else previous.maxVideoHeight,
            updatedAtMs = nowMs,
        ).also { save(channelId, it) }
    }

    @Synchronized
    fun recordDecoderFailure(channelId: String, error: String, nowMs: Long = System.currentTimeMillis()): ChannelStabilityProfile {
        val previous = get(channelId)
        return previous.copy(forceSoftwareVideo = true, lastError = error.take(180), updatedAtMs = nowMs)
            .also { save(channelId, it) }
    }

    @Synchronized
    fun recordAudioDecoderFailure(channelId: String, error: String, nowMs: Long = System.currentTimeMillis()): ChannelStabilityProfile {
        val previous = get(channelId)
        return previous.copy(forceSoftwareAudio = true, lastError = error.take(180), updatedAtMs = nowMs)
            .also { save(channelId, it) }
    }

    @Synchronized
    fun recordStable(channelId: String, nowMs: Long = System.currentTimeMillis()) {
        val previous = get(channelId)
        save(channelId, previous.copy(consecutiveFailures = 0, failureWindowStartedMs = 0, lastError = "", updatedAtMs = nowMs))
    }

    @Synchronized
    fun clearAll() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private fun save(channelId: String, profile: ChannelStabilityProfile) {
        val json = JSONObject()
            .put("failures", profile.consecutiveFailures)
            .put("window", profile.failureWindowStartedMs)
            .put("rebuffers", profile.rebufferCount)
            .put("height", profile.maxVideoHeight)
            .put("software", profile.forceSoftwareVideo)
            .put("softwareAudio", profile.forceSoftwareAudio)
            .put("error", profile.lastError)
            .put("updated", profile.updatedAtMs)
        prefs.edit().putString(key(channelId), json.toString()).apply()
        trimOldEntries()
    }

    private fun decode(raw: String?): ChannelStabilityProfile = runCatching {
        val json = JSONObject(raw ?: return ChannelStabilityProfile())
        ChannelStabilityProfile(
            consecutiveFailures = json.optInt("failures"),
            failureWindowStartedMs = json.optLong("window"),
            rebufferCount = json.optInt("rebuffers"),
            maxVideoHeight = json.optInt("height"),
            forceSoftwareVideo = json.optBoolean("software"),
            forceSoftwareAudio = json.optBoolean("softwareAudio"),
            lastError = json.optString("error"),
            updatedAtMs = json.optLong("updated"),
        )
    }.getOrDefault(ChannelStabilityProfile())

    private fun trimOldEntries() {
        val entries = prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) null else key to decode(value as? String).updatedAtMs
        }
        entries.sortedByDescending { it.second }.drop(MAX_PROFILES).forEach { prefs.edit().remove(it.first).apply() }
    }

    private fun key(channelId: String) = KEY_PREFIX + channelId

    companion object {
        private const val KEY_PREFIX = "channel:"
        private const val FAILURE_WINDOW_MS = 120_000L
        private const val REBUFFERS_BEFORE_REDUCTION = 3
        private const val MAX_PROFILES = 500

        internal fun nextLowerHeight(height: Int): Int = when {
            height > 1080 -> 1080
            height > 720 -> 720
            height > 480 -> 480
            else -> 360
        }
    }
}
