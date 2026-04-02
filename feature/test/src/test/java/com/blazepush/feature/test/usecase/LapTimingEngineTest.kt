package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


class LapTimingEngineTest {

    private val track = PresetTrackCatalog().getAllTracks().first()
    private val detector = GateCrossingDetector()
    private val engine = LapTimingEngine(detector)

    @Test
    fun processSample_startsLapAdvancesSectorAndCompletesLap() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = sample(timestampMillis = 999L, latitude = 39.8980, longitude = 116.3999),
            currentSample = sample(timestampMillis = 1_000L, latitude = 39.9020, longitude = 116.4001)
        )

        assertEquals(LapSessionStatus.Recording, startedSession.status)
        assertNotNull(startedSession.activeLap)
        assertEquals(1, startedSession.currentLapIndex)
        assertEquals(1, startedSession.nextExpectedGateIndex)

        val sectorSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = sample(timestampMillis = 5_999L, latitude = 39.9006, longitude = 119.0000),
            currentSample = sample(timestampMillis = 6_000L, latitude = 39.9007, longitude = 122.0000)
        )

        assertEquals(2, sectorSession.nextExpectedGateIndex)
        assertEquals(1, sectorSession.activeLap!!.sectorEntries.size)
        assertEquals(6_000L, sectorSession.activeLap!!.sectorEntries.first().crossedAtMillis)

        val finishedSession = engine.processSample(
            session = sectorSession,
            track = track,
            previousSample = sample(timestampMillis = 10_999L, latitude = 39.8980, longitude = 116.3998),
            currentSample = sample(timestampMillis = 11_000L, latitude = 39.9020, longitude = 116.4002)
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertEquals(1, lap.lapIndex)
        assertEquals(10_000L, lap.durationMillis)
        assertEquals(listOf(5_000L), lap.sectorTimes)
        assertTrue(lap.crossingEvents.isNotEmpty())
        assertNotNull(finishedSession.activeLap)
    }

    @Test
    fun processSample_unexpectedGateOrder_recordsRejectedEventAndDoesNotAdvanceSession() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = sample(timestampMillis = 999L, latitude = 39.8980, longitude = 116.3999),
            currentSample = sample(timestampMillis = 1_000L, latitude = 39.9020, longitude = 116.4001)
        )

        // After start/finish, the next expected gate is Sector 1.
        // Here we cross the start/finish gate line again immediately (wrong order).
        val unexpectedGate = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = sample(timestampMillis = 1_999L, latitude = 39.8980, longitude = 116.3998),
            currentSample = sample(timestampMillis = 2_000L, latitude = 39.9020, longitude = 116.4002)
        )

        assertEquals(1, unexpectedGate.nextExpectedGateIndex)
        assertEquals(0, unexpectedGate.activeLap!!.sectorEntries.size)
        assertEquals(0, unexpectedGate.completedLaps.size)

        val lastEvent = unexpectedGate.crossingEvents.last()
        assertEquals(false, lastEvent.accepted)
        assertEquals(CrossingReason.UnexpectedGateOrder, lastEvent.reason)
    }

    private fun newSession(): LapSession = LapSession(
        sessionId = "session-1",
        trackId = track.id,
        status = LapSessionStatus.Ready
    )

    private fun sample(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double
    ): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = 36.0
    )
}
