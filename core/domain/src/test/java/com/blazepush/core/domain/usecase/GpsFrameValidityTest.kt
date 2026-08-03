package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TimingHandshakeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsFrameValidityTest {
    private fun valid(): GpsData = GpsData.Empty.copy(
        timestamp = 1_000L,
        satelliteCount = 8,
        hdop = 1.2,
        fixQuality = 1,
        isConnected = true,
        isTimeSynced = true,
        isStale = false,
        hasMainFrame = true,
        mainFrameSequence = 1L,
        consecutiveReliableMainFrames = 3,
        requiredReliableMainFrames = 3,
        reliableMainStableDurationMs = 1_000L,
        isRecoveryStable = true,
        timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
    )

    @Test
    fun validCurrentGenerationMainFrame_isUsable() {
        assertTrue(valid().isUsableForTiming())
    }

    @Test
    fun allZeroNoFixFrame_isNotUsableEvenThoughItIsNew() {
        assertFalse(
            valid().copy(
                timestamp = Long.MIN_VALUE,
                satelliteCount = 0,
                hdop = 0.0,
                fixQuality = 0,
                isTimeSynced = false,
            ).isUsableForTiming(),
        )
    }

    @Test
    fun silentOrMissingMainFrame_isNotUsable() {
        assertFalse(valid().copy(isStale = true).isUsableForTiming())
        assertFalse(valid().copy(hasMainFrame = false).isUsableForTiming())
    }

    @Test
    fun recoveryRequiresDynamicFramesAndStableDuration() {
        assertFalse(valid().copy(isRecoveryStable = false).isUsableForTiming())
        assertFalse(valid().copy(consecutiveReliableMainFrames = 2).isUsableForTiming())
        assertFalse(valid().copy(reliableMainStableDurationMs = 999L).isUsableForTiming())
        assertTrue(valid().copy(consecutiveReliableMainFrames = 3).isUsableForTiming())
    }
}
