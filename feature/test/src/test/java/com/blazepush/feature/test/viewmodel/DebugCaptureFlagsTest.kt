package com.blazepush.feature.test.viewmodel

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TimingHandshakeState
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugCaptureFlagsTest {

    @Test
    fun `all-zero no-fix Main frame remains distinguishable from silence`() {
        val flags = debugCaptureFlags(
            GpsData.Empty.copy(
                isConnected = true,
                hasMainFrame = true,
                mainFrameSequence = 1L,
            ),
        )

        assertEquals(0xC0, flags)
    }

    @Test
    fun `reliable Main frame persists all confidence signals`() {
        val flags = debugCaptureFlags(
            GpsData.Empty.copy(
                timestamp = 1_000L,
                satelliteCount = 8,
                hdop = 1.0,
                isConnected = true,
                fixQuality = 1,
                isTimeSynced = true,
                hasMainFrame = true,
                mainFrameSequence = 2L,
                consecutiveReliableMainFrames = 3,
                requiredReliableMainFrames = 3,
                reliableMainStableDurationMs = 1_000L,
                isRecoveryStable = true,
                timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
            ),
        )

        assertEquals(0xFB, flags)
    }

    @Test
    fun `stale Main snapshot is marked stale and not timing-reliable`() {
        val flags = debugCaptureFlags(
            GpsData.Empty.copy(
                timestamp = 1_000L,
                satelliteCount = 8,
                hdop = 1.0,
                isConnected = true,
                fixQuality = 1,
                isTimeSynced = true,
                isStale = true,
                hasMainFrame = true,
                mainFrameSequence = 3L,
                consecutiveReliableMainFrames = 3,
                requiredReliableMainFrames = 3,
                reliableMainStableDurationMs = 1_000L,
                isRecoveryStable = true,
                timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
            ),
        )

        assertEquals(0xF7, flags)
    }
}
