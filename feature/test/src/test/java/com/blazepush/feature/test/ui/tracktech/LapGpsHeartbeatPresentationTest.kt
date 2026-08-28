package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapGpsHeartbeatPresentationTest {

    @Test
    fun `fresh armed stationary frame shows trustworthy zero and live heartbeat`() {
        val result = present(
            readiness = LapGpsReadiness.ARMED,
            filteredSpeedKmh = 0.0,
            nowElapsedRealtimeMs = 10_120L,
        )

        assertEquals(LapGpsHeartbeatState.LIVE, result.state)
        assertEquals(0.0, result.speedKmh!!, 0.0)
        assertEquals(25.0, result.frequencyHz!!, 0.0)
        assertEquals(9, result.satelliteCount)
        assertEquals(120L, result.ageMs)
        assertTrue(result.isMainFresh)
    }

    @Test
    fun `age reaching dynamic deadline hides old speed and reports stale age`() {
        val atDeadline = present(
            readiness = LapGpsReadiness.ARMED,
            filteredSpeedKmh = 137.4,
            nowElapsedRealtimeMs = 10_400L,
        )
        val laterWithoutNewFrame = present(
            readiness = LapGpsReadiness.ARMED,
            filteredSpeedKmh = 137.4,
            nowElapsedRealtimeMs = 11_250L,
        )

        assertEquals(LapGpsHeartbeatState.STALE, atDeadline.state)
        assertNull(atDeadline.speedKmh)
        assertNull(atDeadline.frequencyHz)
        assertNull(atDeadline.satelliteCount)
        assertEquals(400L, atDeadline.ageMs)
        assertFalse(atDeadline.isMainFresh)
        assertEquals(LapGpsHeartbeatState.STALE, laterWithoutNewFrame.state)
        assertNull(laterWithoutNewFrame.speedKmh)
        assertEquals(1_250L, laterWithoutNewFrame.ageMs)
    }

    @Test
    fun `fresh Main while acquiring fix exposes heartbeat but not speed`() {
        val result = present(
            readiness = LapGpsReadiness.ACQUIRING_FIX,
            filteredSpeedKmh = 42.0,
            nowElapsedRealtimeMs = 10_100L,
        )

        assertEquals(LapGpsHeartbeatState.ACQUIRING_FIX, result.state)
        assertNull(result.speedKmh)
        assertEquals(25.0, result.frequencyHz!!, 0.0)
        assertEquals(9, result.satelliteCount)
        assertTrue(result.isMainFresh)
    }

    @Test
    fun `fresh Main while recovery stabilizes does not present reset zero as stationary`() {
        val result = present(
            readiness = LapGpsReadiness.STABILIZING,
            filteredSpeedKmh = 0.0,
            nowElapsedRealtimeMs = 10_100L,
        )

        assertEquals(LapGpsHeartbeatState.STABILIZING, result.state)
        assertNull(result.speedKmh)
        assertTrue(result.isMainFresh)
    }

    @Test
    fun `connected without Main has no cached heartbeat values`() {
        val result = present(
            readiness = LapGpsReadiness.WAITING_MAIN,
            gpsData = freshGps.copy(
                hasMainFrame = false,
                mainFrameReceivedAtElapsedRealtimeMs = 0L,
            ),
            filteredSpeedKmh = 88.0,
            nowElapsedRealtimeMs = 10_100L,
        )

        assertEquals(LapGpsHeartbeatState.WAITING_MAIN, result.state)
        assertNull(result.speedKmh)
        assertNull(result.frequencyHz)
        assertNull(result.satelliteCount)
        assertNull(result.ageMs)
    }

    @Test
    fun `BLE disconnect wins over otherwise fresh Main`() {
        val result = present(
            connectionState = ConnectionState.DISCONNECTED,
            readiness = LapGpsReadiness.WAITING_DEVICE,
            filteredSpeedKmh = 64.0,
            nowElapsedRealtimeMs = 10_100L,
        )

        assertEquals(LapGpsHeartbeatState.DISCONNECTED, result.state)
        assertNull(result.speedKmh)
        assertFalse(result.isMainFresh)
    }

    private fun present(
        connectionState: ConnectionState = ConnectionState.CONNECTED,
        readiness: LapGpsReadiness,
        gpsData: GpsData = freshGps,
        filteredSpeedKmh: Double,
        nowElapsedRealtimeMs: Long,
    ) = LapGpsHeartbeatPresentationMapper.present(
        connectionState = connectionState,
        readiness = readiness,
        gpsData = gpsData,
        filteredSpeedKmh = filteredSpeedKmh,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
    )

    private companion object {
        val freshGps = GpsData.Empty.copy(
            isConnected = true,
            hasMainFrame = true,
            isStale = false,
            mainFrameSequence = 12L,
            mainFrameReceivedAtElapsedRealtimeMs = 10_000L,
            mainFrameSilenceTimeoutMs = 400L,
            frequency = 25.0,
            satelliteCount = 9,
        )
    }
}
