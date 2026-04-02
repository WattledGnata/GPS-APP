package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath
import com.blazepush.simulator.data.replay.RaceChronoGateType
import com.blazepush.simulator.data.replay.RaceChronoReplayParser
import com.blazepush.simulator.data.replay.ReplayAssetLoader
import com.blazepush.simulator.data.replay.ReplayGate
import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ReplayLapTimingIntegrationTest {

    @Test
    fun `replay session and vbo gates can drive one completed lap`() {
        val parser = RaceChronoReplayParser()
        val replay = ReplayAssetLoader().loadReplayJson(replayJson())
        val gates = parser.parseVboGates(trackVbo(), replay.samples.first())
        val track = gates.toTrack(referenceSamples = replay.samples)
        val engine = LapTimingEngine()

        var session = LapSession(
            sessionId = "replay-session",
            trackId = track.id,
            status = LapSessionStatus.Ready
        )

        replay.samples.zipWithNext().forEach { (previous, current) ->
            session = engine.processSample(
                session = session,
                track = track,
                previousSample = previous.toGpsSample(),
                currentSample = current.toGpsSample()
            )
        }

        assertEquals(session.crossingEvents.joinToString(separator = "\n") { "${it.gateId}:${it.reason}:${it.accepted}" }, 1, session.completedLaps.size)
        assertEquals(1, session.completedLaps.first().lapIndex)
        assertEquals(listOf(4_000L, 5_000L), session.completedLaps.first().sectorTimes)
        assertEquals(14_000L, session.completedLaps.first().durationMillis)
    }

    private fun List<ReplayGate>.toTrack(referenceSamples: List<ReplaySample>): Track {
        val startFinish = first { it.type == RaceChronoGateType.StartFinish }.toTimingGate(sequenceIndex = 0)
        val sectors = filter { it.type == RaceChronoGateType.Split }
            .sortedBy { it.name }
            .mapIndexed { index, gate -> gate.toTimingGate(sequenceIndex = index + 1) }

        return Track(
            id = "replay-track",
            name = "Replay Track",
            referencePath = TrackPath(
                points = referenceSamples.map { GeoPoint(it.latitude, it.longitude) }
            ),
            startFinishGate = startFinish,
            sectorGates = sectors
        )
    }

    private fun ReplayGate.toTimingGate(sequenceIndex: Int): TimingGate {
        val direction = when (name) {
            "Start/Finish", "起点" -> GeoVector(x = 1.0, y = 0.0)
            "S1", "s1" -> GeoVector(x = 0.0, y = 1.0)
            "S2", "s2" -> GeoVector(x = -1.0, y = 0.0)
            else -> GeoVector(
                x = line.end.latitude - line.start.latitude,
                y = line.end.longitude - line.start.longitude
            )
        }
        return TimingGate(
            id = name,
            name = name,
            type = if (type == RaceChronoGateType.StartFinish) TimingGateType.StartFinish else TimingGateType.Sector,
            line = GeoLine(
                start = GeoPoint(line.start.latitude, line.start.longitude),
                end = GeoPoint(line.end.latitude, line.end.longitude)
            ),
            passDirection = direction,
            sequenceIndex = sequenceIndex,
            minDirectionalSpeedMps = null
        )
    }

    private fun ReplaySample.toGpsSample(): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speedKmh,
        bearingDegrees = bearingDegrees,
        altitudeMeters = altitudeMeters,
        accuracyMeters = hdop
    )

    private fun replayJson(): String = File(
        projectRoot(),
        "simulator/src/main/assets/replay/tianfu_track_replay_laps_2_4_5hz.json"
    ).readText()

    private fun trackVbo(): String = File(
        projectRoot(),
        "simulator/src/main/assets/replay/tianfu_track.vbo"
    ).readText()

    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start ->
                generateSequence(start) { current -> current.parentFile }.filterNotNull()
            }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }
}
