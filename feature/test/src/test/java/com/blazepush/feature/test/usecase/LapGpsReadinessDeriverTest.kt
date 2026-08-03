package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TimingHandshakeState
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness
import org.junit.Assert.assertEquals
import org.junit.Test

class LapGpsReadinessDeriverTest {
    @Test
    fun `readiness exposes device main fix stabilization and armed stages`() {
        val disconnected = reliableGps().copy(isConnected = false, hasMainFrame = false)
        assertEquals(
            LapGpsReadiness.WAITING_DEVICE,
            LapGpsReadinessDeriver.derive(ConnectionState.DISCONNECTED, disconnected),
        )

        val noMain = reliableGps().copy(hasMainFrame = false, consecutiveReliableMainFrames = 0)
        assertEquals(
            LapGpsReadiness.WAITING_MAIN,
            LapGpsReadinessDeriver.derive(ConnectionState.CONNECTED, noMain),
        )

        val noFix = reliableGps().copy(
            fixQuality = 0,
            satelliteCount = 0,
            hdop = 0.0,
            consecutiveReliableMainFrames = 0,
        )
        assertEquals(
            LapGpsReadiness.ACQUIRING_FIX,
            LapGpsReadinessDeriver.derive(ConnectionState.CONNECTED, noFix),
        )

        assertEquals(
            LapGpsReadiness.STABILIZING,
            LapGpsReadinessDeriver.derive(
                ConnectionState.CONNECTED,
                reliableGps().copy(consecutiveReliableMainFrames = 2, isRecoveryStable = false),
            ),
        )
        assertEquals(
            LapGpsReadiness.ARMED,
            LapGpsReadinessDeriver.derive(ConnectionState.CONNECTED, reliableGps()),
        )
    }

    @Test
    fun `stale Main regresses to waiting main rather than remaining armed`() {
        assertEquals(
            LapGpsReadiness.WAITING_MAIN,
            LapGpsReadinessDeriver.derive(
                ConnectionState.CONNECTED,
                reliableGps().copy(isStale = true),
            ),
        )
    }

    private fun reliableGps(): GpsData = GpsData.Empty.copy(
        timestamp = 1_000L,
        isConnected = true,
        hasMainFrame = true,
        mainFrameSequence = 3L,
        mainFrameReceivedAtElapsedRealtimeMs = 1_000L,
        fixQuality = 1,
        satelliteCount = 8,
        hdop = 1.2,
        isTimeSynced = true,
        consecutiveReliableMainFrames = 3,
        requiredReliableMainFrames = 3,
        reliableMainStableDurationMs = 1_000L,
        isRecoveryStable = true,
        timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
    )
}
