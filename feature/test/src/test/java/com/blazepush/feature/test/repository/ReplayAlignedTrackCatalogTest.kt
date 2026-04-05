package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TrackSource
import com.blazepush.feature.test.usecase.GateCrossingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAlignedTrackCatalogTest {

    @Test
    fun buildReplayAlignedTrack_withReplayAssets_doesNotThrow() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val method = ReplayAlignedTrackCatalog::class.java.getDeclaredMethod(
            "buildReplayAlignedTrack",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        method.invoke(catalog, replayJson, replayVbo)
    }

    @Test
    fun presetStartFinishGate_acceptsReplayOpeningCrossingSamples() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)

        val detection = GateCrossingDetector().detect(
            previous = crossing.first,
            current = crossing.second,
            gate = track.startFinishGate
        )

        assertEquals(true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    @Test
    fun generatedStartFinishGate_acceptsReplayOpeningCrossingSamples() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )
        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))
        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)

        val detection = GateCrossingDetector().detect(
            previous = crossing.first,
            current = crossing.second,
            gate = track.startFinishGate
        )

        assertEquals("accepted=${detection.accepted}, reason=${detection.reason}, score=${detection.directionScore}, gate=${track.startFinishGate}", true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    @Test
    fun presetTrack_matchesTficRczTrapGeometry() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))

        assertGateLine(
            gate = track.startFinishGate,
            startLatitude = 30.496167246506413,
            startLongitude = 104.43343794245452,
            endLatitude = 30.49619075349359,
            endLongitude = 104.43291739087881,
            passDirectionX = -0.0002602757878550089,
            passDirectionY = -0.000023506987175358924
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s1" },
            startLatitude = 30.49004451419976,
            startLongitude = 104.43252709154902,
            endLatitude = 30.48959781913357,
            endLongitude = 104.43258157511764,
            passDirectionX = -0.00002724178431097556,
            passDirectionY = -0.00044669506619011374
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s2" },
            startLatitude = 30.4957579139104,
            startLongitude = 104.4369620745035,
            endLatitude = 30.495765752756267,
            endLongitude = 104.43748325882984,
            passDirectionX = -0.0002605921631704301,
            passDirectionY = 0.000007838845867048829
        )
    }

    @Test
    fun generatedTrack_reusesCorrectedTficGateGeometry() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))

        assertGateLine(
            gate = track.startFinishGate,
            startLatitude = 30.496167246506413,
            startLongitude = 104.43343794245452,
            endLatitude = 30.49619075349359,
            endLongitude = 104.43291739087881,
            passDirectionX = -0.0002602757878550089,
            passDirectionY = -0.000023506987175358924
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s1" },
            startLatitude = 30.49004451419976,
            startLongitude = 104.43252709154902,
            endLatitude = 30.48959781913357,
            endLongitude = 104.43258157511764,
            passDirectionX = -0.00002724178431097556,
            passDirectionY = -0.00044669506619011374
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s2" },
            startLatitude = 30.4957579139104,
            startLongitude = 104.4369620745035,
            endLatitude = 30.495765752756267,
            endLongitude = 104.43748325882984,
            passDirectionX = -0.0002605921631704301,
            passDirectionY = 0.000007838845867048829
        )
    }

    @Test
    fun getTrack_buildsGeneratedTficTrackFromReplayAssets() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))

        assertEquals(TrackSource.Generated, track.source)
        assertEquals("REAL_TRACK_REPLAY", track.layoutName)
        assertTrue(track.referencePath.points.size > 100)
        assertEquals("起点", track.startFinishGate.name)
        assertEquals(listOf("s1", "s2"), track.sectorGates.map { it.name })
        assertEquals("start-finish", track.startFinishGate.id)
        assertNullMinDirectionalSpeed(track)
    }

    @Test
    fun getAllTracks_exposesReplayAlignedTrackToRuntimeSelection() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val track = catalog.getAllTracks().firstOrNull { it.id == "preset-tfic-lpcc" }

        assertNotNull(track)
        assertEquals(TrackSource.Generated, track?.source)
    }

    private fun assertNullMinDirectionalSpeed(track: com.blazepush.feature.test.model.track.Track) {
        assertEquals(null, track.startFinishGate.minDirectionalSpeedMps)
        assertTrue(track.sectorGates.all { it.minDirectionalSpeedMps == null })
    }

    private fun assertGateLine(
        gate: TimingGate,
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
        passDirectionX: Double,
        passDirectionY: Double
    ) {
        assertEquals(startLatitude, gate.line.start.latitude, 0.0000000001)
        assertEquals(startLongitude, gate.line.start.longitude, 0.0000000001)
        assertEquals(endLatitude, gate.line.end.latitude, 0.0000000001)
        assertEquals(endLongitude, gate.line.end.longitude, 0.0000000001)
        assertEquals(passDirectionX, gate.passDirection.x, 0.0000000001)
        assertEquals(passDirectionY, gate.passDirection.y, 0.0000000001)
    }

    private fun crossingSamples(gate: TimingGate, previousTimestamp: Long, currentTimestamp: Long): Pair<GpsSample, GpsSample> {
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offsetScale = 0.25
        return GpsSample(
            timestampMillis = previousTimestamp,
            latitude = centerLatitude - (gate.passDirection.x * offsetScale),
            longitude = centerLongitude - (gate.passDirection.y * offsetScale),
            speedKmh = 36.0
        ) to GpsSample(
            timestampMillis = currentTimestamp,
            latitude = centerLatitude + (gate.passDirection.x * offsetScale),
            longitude = centerLongitude + (gate.passDirection.y * offsetScale),
            speedKmh = 36.0
        )
    }

    private val replayJson = java.io.File(
        projectRoot(),
        "feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json"
    ).readText()

    private val replayVbo = java.io.File(
        projectRoot(),
        "feature/test/src/main/assets/replay/tianfu_track.vbo"
    ).readText()

    private fun projectRoot(): java.io.File {
        val classesDir = java.io.File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = java.io.File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { java.io.File(it, "settings.gradle").exists() || java.io.File(it, "settings.gradle.kts").exists() }
    }
}
