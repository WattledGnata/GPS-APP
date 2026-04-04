package com.blazepush.feature.test.ui.screen

import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Test

class LapDebugExecutionScreenStateTest {

    @Test
    fun toLapDebugTelemetry_withoutSessionSamples_returnsZeroGAndCurrentTelemetry() {
        val gpsData = gpsData(speed = 108.0, bearing = 90.0)

        val telemetry = gpsData.toLapDebugTelemetry(previousSample = null)

        assertEquals(108.0, telemetry.speedKmh, 0.0001)
        assertEquals(90.0, telemetry.bearingDegrees, 0.0001)
        assertEquals(0.0, telemetry.forwardG, 0.0001)
        assertEquals(0.0, telemetry.lateralG, 0.0001)
    }

    @Test
    fun toLapDebugTelemetry_withSessionSamples_computesForwardAndLateralG() {
        val lapSession = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                GpsSample(timestampMillis = 1_000L, latitude = 0.0, longitude = 0.0, speedKmh = 36.0, bearingDegrees = 0.0),
                GpsSample(timestampMillis = 2_000L, latitude = 0.0, longitude = 0.0, speedKmh = 72.0, bearingDegrees = 90.0)
            )
        )

        val telemetry = gpsData(speed = 72.0, bearing = 90.0).toLapDebugTelemetry(lapSession)

        assertEquals(72.0, telemetry.speedKmh, 0.0001)
        assertEquals(90.0, telemetry.bearingDegrees, 0.0001)
        assertEquals(1.0197, telemetry.forwardG, 0.0005)
        assertEquals(3.2036, telemetry.lateralG, 0.0005)
    }

    @Test
    fun rememberStartFinishTimingCardState_withoutAcceptedStartFinishCrossing_keepsWaitingState() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            crossingEvents = listOf(
                crossingEvent(timestampMillis = 1_000L, accepted = false, gateType = TimingGateType.StartFinish),
                crossingEvent(timestampMillis = 2_000L, accepted = true, gateType = TimingGateType.Sector)
            )
        )

        val state = rememberStartFinishTimingCardState(session)

        assertEquals("--", state.lastLapElapsedLabel)
        assertEquals("0.000 s", state.currentLapElapsedLabel)
        assertEquals("0.0 m", state.currentLapDistanceLabel)
        assertEquals("--", state.lastStartFinishTimeLabel)
        assertEquals("等待起点", state.statusLabel)
    }

    @Test
    fun rememberStartFinishTimingCardState_withFirstAcceptedStartFinishCrossing_reportsCurrentLapSummary() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 4_500L, latitude = 39.000000, longitude = 116.000170)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 3_000L)
            )
        )

        val state = rememberStartFinishTimingCardState(session)

        assertEquals("--", state.lastLapElapsedLabel)
        assertEquals("1.500 s", state.currentLapElapsedLabel)
        assertEquals("14.7 m", state.currentLapDistanceLabel)
        assertEquals("08:00:03", state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    @Test
    fun rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_reportsLastAndCurrentLapSummary() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 5_000L, latitude = 39.000000, longitude = 116.000100),
                gpsSample(timestampMillis = 7_000L, latitude = 39.000000, longitude = 116.000200),
                gpsSample(timestampMillis = 8_000L, latitude = 39.000000, longitude = 116.000370)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 3_000L),
                crossingEvent(timestampMillis = 5_500L, accepted = false, gateType = TimingGateType.StartFinish),
                acceptedStartFinishCrossing(timestampMillis = 7_000L)
            )
        )

        val state = rememberStartFinishTimingCardState(session)

        assertEquals("4.000 s", state.lastLapElapsedLabel)
        assertEquals("1.000 s", state.currentLapElapsedLabel)
        assertEquals("14.7 m", state.currentLapDistanceLabel)
        assertEquals("08:00:07", state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    private fun gpsData(speed: Double, bearing: Double) = GpsData(
        timestamp = 2_000L,
        speed = speed,
        latitude = 0.0,
        longitude = 0.0,
        altitude = 0.0,
        bearing = bearing,
        satelliteCount = 0,
        hdop = 0.0,
        vdop = 0.0,
        frequency = 10.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null,
        fixQuality = 1
    )

    private fun acceptedStartFinishCrossing(timestampMillis: Long) = crossingEvent(
        timestampMillis = timestampMillis,
        accepted = true,
        gateType = TimingGateType.StartFinish
    )

    private fun gpsSample(timestampMillis: Long, latitude: Double, longitude: Double) = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude
    )

    private fun crossingEvent(timestampMillis: Long, accepted: Boolean, gateType: TimingGateType) = CrossingEvent(
        gateId = "gate-$timestampMillis",
        gateType = gateType,
        timestampMillis = timestampMillis,
        sampleIndex = 0,
        accepted = accepted,
        reason = if (accepted) CrossingReason.Accepted else CrossingReason.NoIntersection
    )
}
