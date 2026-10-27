package dev.openstream.tv.player

/** Keeps trying while playback is open, without flooding a failing IPTV server. */
object RecoveryDelayPolicy {
    fun delayMs(attempt: Int, fastRetries: Int, baseDelayMs: Int, unauthorized: Boolean = false, offline: Boolean = false): Long {
        if (offline) return 30_000L
        if (unauthorized) return 300_000L
        if (attempt > fastRetries.coerceAtLeast(0)) return 60_000L
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (baseDelayMs.coerceAtLeast(1_000).toLong() shl exponent).coerceAtMost(60_000L)
    }
}
