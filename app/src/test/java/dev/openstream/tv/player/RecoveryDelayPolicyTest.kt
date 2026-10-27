package dev.openstream.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryDelayPolicyTest {
    @Test fun ordinaryFailuresKeepRetryingWithBoundedDelay() {
        assertEquals(2_000L, RecoveryDelayPolicy.delayMs(1, 5, 2_000))
        assertEquals(32_000L, RecoveryDelayPolicy.delayMs(5, 5, 2_000))
        assertEquals(60_000L, RecoveryDelayPolicy.delayMs(6, 5, 2_000))
        assertEquals(60_000L, RecoveryDelayPolicy.delayMs(1_000_000, 5, 2_000))
    }

    @Test fun offlineAndUnauthorizedErrorsDoNotFloodTheServer() {
        assertEquals(30_000L, RecoveryDelayPolicy.delayMs(1, 5, 2_000, offline = true))
        assertEquals(300_000L, RecoveryDelayPolicy.delayMs(1, 5, 2_000, unauthorized = true))
    }
}
