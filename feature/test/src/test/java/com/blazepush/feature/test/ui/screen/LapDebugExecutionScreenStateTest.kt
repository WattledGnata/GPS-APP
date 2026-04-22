package com.blazepush.feature.test.ui.screen

import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGateType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

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
            ),
            activeLap = activeLap(startedAtMillis = 3_000L, sampleStartIndex = 0)
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("--", state.lastLapElapsedLabel)
        assertEquals("1.500 s", state.currentLapElapsedLabel)
        assertEquals("14.7 m", state.currentLapDistanceLabel)
        assertEquals(formatExpectedTimeOfDay(3_000L), state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    @Test
    fun rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_reportsFixedSummaryFields() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 32_529_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 32_533_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 32_533_625L, latitude = 39.000000, longitude = 116.000188),
                gpsSample(timestampMillis = 32_534_250L, latitude = 39.000000, longitude = 116.000375)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 32_529_000L),
                crossingEvent(timestampMillis = 32_531_500L, accepted = false, gateType = TimingGateType.StartFinish),
                acceptedStartFinishCrossing(timestampMillis = 32_533_000L)
            ),
            activeLap = activeLap(startedAtMillis = 32_533_000L, sampleStartIndex = 1)
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("4.000 s", state.lastLapElapsedLabel)
        assertEquals("1.250 s", state.currentLapElapsedLabel)
        assertEquals("32.4 m", state.currentLapDistanceLabel)
        assertEquals(formatExpectedTimeOfDay(32_533_000L), state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    @Test
    fun statusLabel_showsWaitingForTimeSync_whenUpstreamUnsynced() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            activeLap = null
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = false)

        assertEquals("等待协议时间同步", state.statusLabel)
    }

    @Test
    fun statusLabel_showsWaitingForStart_whenSyncedButNoActiveLap() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            activeLap = null
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("等待起点", state.statusLabel)
    }

    @Test
    fun statusLabel_showsInLap_regardlessOfTimeSync() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.000000, longitude = 116.000000)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 3_000L)
            ),
            activeLap = activeLap(startedAtMillis = 3_000L, sampleStartIndex = 0)
        )

        val stateSynced = rememberStartFinishTimingCardState(session, isTimeSynced = true)
        val stateUnsynced = rememberStartFinishTimingCardState(session, isTimeSynced = false)

        assertEquals("当前圈进行中", stateSynced.statusLabel)
        assertEquals("当前圈进行中", stateUnsynced.statusLabel)
    }

    private fun formatExpectedTimeOfDay(timestampMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

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

    private fun activeLap(startedAtMillis: Long, sampleStartIndex: Int) = ActiveLap(
        lapIndex = 0,
        startedAtMillis = startedAtMillis,
        passedGateIds = emptyList(),
        sectorEntries = emptyList(),
        sampleStartIndex = sampleStartIndex
    )
}
