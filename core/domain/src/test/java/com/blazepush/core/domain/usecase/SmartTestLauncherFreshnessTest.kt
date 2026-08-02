package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTestLauncherFreshnessTest {
    private val launcher = SmartTestLauncher()

    private fun reliableGps() = GpsData.Empty.copy(
        timestamp = 1_000L,
        satelliteCount = 8,
        hdop = 1.0,
        fixQuality = 1,
        isConnected = true,
        isTimeSynced = true,
        hasMainFrame = true,
        mainFrameSilenceTimeoutMs = 400L,
        consecutiveReliableMainFrames = 3,
    )

    @Test
    fun launchUsesFrameSpecificDynamicDeadline() {
        assertTrue(launcher.canLaunch(reliableGps(), ConnectionState.CONNECTED, 399L))
        assertFalse(launcher.canLaunch(reliableGps(), ConnectionState.CONNECTED, 400L))
    }

    @Test
    fun launchWaitsForThreeReliableRecoveryFrames() {
        assertFalse(
            launcher.canLaunch(
                reliableGps().copy(consecutiveReliableMainFrames = 2),
                ConnectionState.CONNECTED,
                100L,
            ),
        )
    }
}
