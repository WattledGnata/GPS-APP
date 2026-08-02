package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugCaptureDashboardTest {

    @Test
    fun `frame age is unavailable before first Main frame`() {
        assertNull(debugFrameAgeMs(GpsData.Empty, nowElapsedRealtimeMs = 2_000L))
    }

    @Test
    fun `frame age uses monotonic receive clock`() {
        val gpsData = GpsData.Empty.copy(
            hasMainFrame = true,
            mainFrameReceivedAtElapsedRealtimeMs = 1_600L,
        )

        assertEquals(400L, debugFrameAgeMs(gpsData, nowElapsedRealtimeMs = 2_000L))
    }

    @Test
    fun `negative clock edge is clamped instead of showing negative delay`() {
        val gpsData = GpsData.Empty.copy(
            hasMainFrame = true,
            mainFrameReceivedAtElapsedRealtimeMs = 2_100L,
        )

        assertEquals(0L, debugFrameAgeMs(gpsData, nowElapsedRealtimeMs = 2_000L))
    }
}
