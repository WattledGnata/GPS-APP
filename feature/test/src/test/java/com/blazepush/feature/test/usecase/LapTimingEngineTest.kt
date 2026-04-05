package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


class LapTimingEngineTest {

    private val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
    private val detector = GateCrossingDetector()
    private val engine = LapTimingEngine(detector)

    @Test
    fun processSample_onJvm_doesNotCrashWhenAcceptedCrossingTriggersDebugLogging() {
        val startFinish = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = startFinish.first,
            currentSample = startFinish.second
        )

        assertEquals(LapSessionStatus.Recording, startedSession.status)
        assertEquals(1, startedSession.currentLapIndex)
        assertEquals(1, startedSession.nextExpectedGateIndex)
    }

    @Test
    fun processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        assertEquals(LapSessionStatus.Recording, startedSession.status)
        assertEquals(1, startedSession.currentLapIndex)
        assertEquals(0, startedSession.completedLaps.size)
        assertNotNull(startedSession.activeLap)
        assertEquals(listOf("start-finish"), startedSession.activeLap!!.passedGateIds)
    }

    @Test
    fun processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val finishedSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        val lap = finishedSession.completedLaps.first()
        assertEquals(1, lap.lapIndex)
        assertEquals(267_000L, lap.durationMillis)
        assertNotNull(finishedSession.activeLap)
        assertEquals(2, finishedSession.currentLapIndex)
    }

    @Test
    fun processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val sectorOneSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).first,
            currentSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).second
        )

        val sectorTwoSession = engine.processSample(
            session = sectorOneSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        val finishedSession = engine.processSample(
            session = sectorTwoSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        val lap = finishedSession.completedLaps.first()
        assertEquals(listOf(250_600L, 8_200L), lap.sectorTimes)
        assertTrue(lap.qualityFlags.isEmpty())
        assertEquals(2, finishedSession.currentLapIndex)
        assertEquals(1, finishedSession.completedLaps.size)
    }

    @Test
    fun processSample_missingSectorStillCompletesLapWithIncompleteFlag() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val sectorOneSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).first,
            currentSample = crossingSamples(track.sectorGates[0], 1773478127090L, 1773478127290L).second
        )

        val finishedSession = engine.processSample(
            session = sectorOneSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        val lap = finishedSession.completedLaps.first()
        assertEquals(listOf(250_600L), lap.sectorTimes)
        assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)
    }

    @Test
    fun processSample_outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val outOfOrderSession = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
        )

        assertEquals(0, outOfOrderSession.activeLap!!.sectorEntries.size)
        assertEquals(1, outOfOrderSession.nextExpectedGateIndex)

        val finishedSession = engine.processSample(
            session = outOfOrderSession,
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773478143490L, 1773478143690L).second
        )

        assertEquals(1, finishedSession.completedLaps.size)
        assertEquals(listOf(LapQualityFlag.IncompleteSectors), finishedSession.completedLaps.first().qualityFlags)
    }

    @Test
    fun processSample_unexpectedGateOrder_recordsRejectedEventAndDoesNotAdvanceSession() {
        val startedSession = engine.processSample(
            session = newSession(),
            track = track,
            previousSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).first,
            currentSample = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L).second
        )

        val unexpectedGate = engine.processSample(
            session = startedSession,
            track = track,
            previousSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).first,
            currentSample = crossingSamples(track.sectorGates[1], 1773478135290L, 1773478135490L).second
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

    private fun crossingSamples(gate: TimingGate, previousTimestamp: Long, currentTimestamp: Long): Pair<GpsSample, GpsSample> {
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offsetScale = 0.25
        return sample(
            timestampMillis = previousTimestamp,
            latitude = centerLatitude - (gate.passDirection.x * offsetScale),
            longitude = centerLongitude - (gate.passDirection.y * offsetScale)
        ) to sample(
            timestampMillis = currentTimestamp,
            latitude = centerLatitude + (gate.passDirection.x * offsetScale),
            longitude = centerLongitude + (gate.passDirection.y * offsetScale)
        )
    }

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
