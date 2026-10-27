package dev.openstream.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelStabilityStoreTest {
    @Test fun qualityReductionUsesStableSteps() {
        assertEquals(1080, ChannelStabilityStore.nextLowerHeight(2160))
        assertEquals(720, ChannelStabilityStore.nextLowerHeight(1080))
        assertEquals(480, ChannelStabilityStore.nextLowerHeight(720))
        assertEquals(360, ChannelStabilityStore.nextLowerHeight(480))
        assertEquals(360, ChannelStabilityStore.nextLowerHeight(360))
    }
}
