package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.laptiming.SectorEntry
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.Track

class LapTimingEngine(private val detector: GateCrossingDetector = GateCrossingDetector()) {

    companion object {
        private const val TAG = "LapTimingEngine"
    }

    fun processSample(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        currentSample: GpsSample
    ): LapSession {
        val updatedSamples = session.samples + currentSample
        val startFinishDetection = detector.detect(previous = previousSample, current = currentSample, gate = track.startFinishGate)
        FileLogger.d(
            TAG,
            "targetGate=${track.startFinishGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${startFinishDetection.accepted}, reason=${startFinishDetection.reason}, directionScore=${startFinishDetection.directionScore}, directionalSpeed=${startFinishDetection.directionalSpeedMps}"
        )
        if (startFinishDetection.accepted) {
            return handleStartFinishCrossing(
                session = session,
                track = track,
                updatedSamples = updatedSamples,
                currentSample = currentSample,
                detection = startFinishDetection
            )
        }

        val targetGate = expectedGate(track, session.nextExpectedGateIndex) ?: return session.copy(samples = updatedSamples)

        return handleSectorCrossing(
            session = session,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample,
            updatedSamples = updatedSamples,
            targetGate = targetGate
        )
    }

    private fun handleStartFinishCrossing(
        session: LapSession,
        track: Track,
        updatedSamples: List<GpsSample>,
        currentSample: GpsSample,
        detection: GateCrossingDetection
    ): LapSession {
        val crossingEvent = CrossingEvent(
            gateId = track.startFinishGate.id,
            gateType = track.startFinishGate.type,
            timestampMillis = currentSample.timestampMillis,
            sampleIndex = updatedSamples.lastIndex,
            accepted = detection.accepted,
            reason = detection.reason,
            directionalSpeedMps = detection.directionalSpeedMps,
            directionScore = detection.directionScore
        )
        val updatedEvents = session.crossingEvents + crossingEvent

        if (session.activeLap == null) {
            return session.copy(
                status = LapSessionStatus.Recording,
                startedAtMillis = session.startedAtMillis ?: currentSample.timestampMillis,
                samples = updatedSamples,
                currentLapIndex = 1,
                nextExpectedGateIndex = 1,
                crossingEvents = updatedEvents,
                activeLap = ActiveLap(
                    lapIndex = 1,
                    startedAtMillis = currentSample.timestampMillis,
                    passedGateIds = listOf(track.startFinishGate.id),
                    sampleStartIndex = updatedSamples.lastIndex
                )
            )
        }

        val activeLap = session.activeLap
        val qualityFlags = if (activeLap.sectorEntries.size == track.sectorGates.size) {
            emptyList()
        } else {
            listOf(LapQualityFlag.IncompleteSectors)
        }
        val lapRecord = LapRecord(
            recordId = "${session.sessionId}-lap-${activeLap.lapIndex}",
            sessionId = session.sessionId,
            trackId = session.trackId,
            lapIndex = activeLap.lapIndex,
            startedAtMillis = activeLap.startedAtMillis,
            finishedAtMillis = currentSample.timestampMillis,
            durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis,
            sectorTimes = activeLap.sectorEntries.toSectorTimes(activeLap.startedAtMillis),
            trajectory = updatedSamples.drop(activeLap.sampleStartIndex),
            crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis },
            qualityFlags = qualityFlags
        )
        val nextLapIndex = activeLap.lapIndex + 1
        return session.copy(
            status = LapSessionStatus.Recording,
            samples = updatedSamples,
            currentLapIndex = nextLapIndex,
            nextExpectedGateIndex = 1,
            crossingEvents = updatedEvents,
            completedLaps = session.completedLaps + lapRecord,
            activeLap = ActiveLap(
                lapIndex = nextLapIndex,
                startedAtMillis = currentSample.timestampMillis,
                passedGateIds = listOf(track.startFinishGate.id),
                sampleStartIndex = updatedSamples.lastIndex
            )
        )
    }

    private fun handleSectorCrossing(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        currentSample: GpsSample,
        updatedSamples: List<GpsSample>,
        targetGate: TimingGate
    ): LapSession {
        val activeLap = session.activeLap ?: return session.copy(samples = updatedSamples)
        val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }

        val unexpectedGate = orderedSectorGates
            .asSequence()
            .filter { it.id != targetGate.id }
            .map { gate -> gate to detector.detect(previous = previousSample, current = currentSample, gate = gate) }
            .firstOrNull { (_, detection) -> detection.accepted }

        if (unexpectedGate != null) {
            val (gate, detection) = unexpectedGate
            val crossingEvent = CrossingEvent(
                gateId = gate.id,
                gateType = gate.type,
                timestampMillis = currentSample.timestampMillis,
                sampleIndex = updatedSamples.lastIndex,
                accepted = false,
                reason = CrossingReason.UnexpectedGateOrder,
                directionalSpeedMps = detection.directionalSpeedMps,
                directionScore = detection.directionScore
            )
            return session.copy(
                samples = updatedSamples,
                crossingEvents = session.crossingEvents + crossingEvent
            )
        }

        val detection = detector.detect(previous = previousSample, current = currentSample, gate = targetGate)
        FileLogger.d(
            TAG,
            "targetGate=${targetGate.id}, prev=(${previousSample.latitude},${previousSample.longitude},ts=${previousSample.timestampMillis}), current=(${currentSample.latitude},${currentSample.longitude},ts=${currentSample.timestampMillis}), accepted=${detection.accepted}, reason=${detection.reason}, directionScore=${detection.directionScore}, directionalSpeed=${detection.directionalSpeedMps}"
        )

        val crossingEvent = CrossingEvent(
            gateId = targetGate.id,
            gateType = targetGate.type,
            timestampMillis = currentSample.timestampMillis,
            sampleIndex = updatedSamples.lastIndex,
            accepted = detection.accepted,
            reason = detection.reason,
            directionalSpeedMps = detection.directionalSpeedMps,
            directionScore = detection.directionScore
        )

        val updatedEvents = session.crossingEvents + crossingEvent

        if (!detection.accepted) {
            return session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
        }

        return session.copy(
            samples = updatedSamples,
            nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
            crossingEvents = updatedEvents,
            activeLap = activeLap.copy(
                passedGateIds = activeLap.passedGateIds + targetGate.id,
                sectorEntries = activeLap.sectorEntries + SectorEntry(
                    gateId = targetGate.id,
                    crossedAtMillis = currentSample.timestampMillis
                )
            )
        )
    }

    private fun expectedGate(track: Track, nextExpectedGateIndex: Int): TimingGate? =
        track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(nextExpectedGateIndex - 1)

    private fun List<SectorEntry>.toSectorTimes(startedAtMillis: Long): List<Long> {
        var previousTimestamp = startedAtMillis
        return map { entry ->
            val duration = entry.crossedAtMillis - previousTimestamp
            previousTimestamp = entry.crossedAtMillis
            duration
        }
    }
}
