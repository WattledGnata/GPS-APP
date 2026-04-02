package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.laptiming.SectorEntry
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.Track

class LapTimingEngine(private val detector: GateCrossingDetector = GateCrossingDetector()) {

    fun processSample(
        session: LapSession,
        track: Track,
        previousSample: GpsSample,
        currentSample: GpsSample
    ): LapSession {
        val updatedSamples = session.samples + currentSample
        val orderedGates = orderedGates(track)
        val targetGate = expectedGate(track, session.nextExpectedGateIndex) ?: return session.copy(samples = updatedSamples)

        val unexpectedGate = orderedGates
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

        return when {
            targetGate == track.startFinishGate && session.activeLap == null -> {
                session.copy(
                    status = LapSessionStatus.Recording,
                    startedAtMillis = session.startedAtMillis ?: currentSample.timestampMillis,
                    samples = updatedSamples,
                    currentLapIndex = 1,
                    nextExpectedGateIndex = 1,
                    crossingEvents = updatedEvents,
                    activeLap = ActiveLap(
                        lapIndex = 1,
                        startedAtMillis = currentSample.timestampMillis,
                        passedGateIds = listOf(targetGate.id),
                        sampleStartIndex = updatedSamples.lastIndex
                    )
                )
            }

            targetGate != track.startFinishGate && session.activeLap != null -> {
                val activeLap = session.activeLap
                session.copy(
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

            targetGate == track.startFinishGate && session.activeLap != null -> {
                val activeLap = session.activeLap
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
                    qualityFlags = emptyList()
                )
                val nextLapIndex = activeLap.lapIndex + 1
                session.copy(
                    status = LapSessionStatus.Recording,
                    samples = updatedSamples,
                    currentLapIndex = nextLapIndex,
                    nextExpectedGateIndex = 1,
                    crossingEvents = updatedEvents,
                    completedLaps = session.completedLaps + lapRecord,
                    activeLap = ActiveLap(
                        lapIndex = nextLapIndex,
                        startedAtMillis = currentSample.timestampMillis,
                        passedGateIds = listOf(targetGate.id),
                        sampleStartIndex = updatedSamples.lastIndex
                    )
                )
            }

            else -> session.copy(samples = updatedSamples, crossingEvents = updatedEvents)
        }
    }

    private fun expectedGate(track: Track, nextExpectedGateIndex: Int): TimingGate? {
        val orderedGates = orderedGates(track)
        return if (nextExpectedGateIndex <= track.sectorGates.size) {
            orderedGates.getOrNull(nextExpectedGateIndex)
        } else {
            track.startFinishGate
        }
    }

    private fun orderedGates(track: Track): List<TimingGate> =
        listOf(track.startFinishGate) + track.sectorGates.sortedBy { it.sequenceIndex }

    private fun List<SectorEntry>.toSectorTimes(startedAtMillis: Long): List<Long> {
        var previousTimestamp = startedAtMillis
        return map { entry ->
            val duration = entry.crossedAtMillis - previousTimestamp
            previousTimestamp = entry.crossedAtMillis
            duration
        }
    }
}
