package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugCaptureDashboardTest {

    @Test
    fun `BLE and Main statuses stay independent`() {
        assertEquals("BLE CONNECTED", debugBleStatusText(ConnectionState.CONNECTED))
        assertEquals("WAITING MAIN", debugMainStatusText(GpsData.Empty.copy(isConnected = true)))
        assertEquals(
            "MAIN STALE",
            debugMainStatusText(GpsData.Empty.copy(hasMainFrame = true, isStale = true)),
        )
    }

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

    @Test
    fun `GPS protocol clock is projected from monotonic Main receive time`() {
        val gpsData = GpsData.Empty.copy(
            timestamp = 1_700_000_000_000L,
            isTimeSynced = true,
            hasMainFrame = true,
            mainFrameReceivedAtElapsedRealtimeMs = 10_000L,
        )

        assertEquals(
            1_700_000_000_250L,
            debugProjectedGpsTimeMs(gpsData, nowElapsedRealtimeMs = 10_250L),
        )
    }

    @Test
    fun `unsynced GPS protocol clock is never replaced by phone time`() {
        val gpsData = GpsData.Empty.copy(
            timestamp = Long.MIN_VALUE,
            isTimeSynced = false,
            hasMainFrame = true,
            mainFrameReceivedAtElapsedRealtimeMs = 10_000L,
        )

        assertNull(debugProjectedGpsTimeMs(gpsData, nowElapsedRealtimeMs = 10_250L))
    }
}
