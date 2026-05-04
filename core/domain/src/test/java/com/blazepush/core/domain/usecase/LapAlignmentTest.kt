package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class LapAlignmentTest {

    private fun mockLap(
        sampleCount: Int,
        startLat: Double = 0.0,
        endLat: Double = 0.009,
        startLon: Double = 0.0,
        endLon: Double = 0.0,
        lapDurationMs: Long = 60000L,
        accelerationG: Double? = null,
        lapStartWallClock: Long = 0L,
        fixedLat: Double? = null,
        fixedLon: Double? = null,
    ): LapTelemetry {
        val samples = (0 until sampleCount).map { i ->
            val frac = if (sampleCount > 1) i.toDouble() / (sampleCount - 1) else 0.0
            LapTelemetrySample(
                absoluteTsMs = lapStartWallClock + (frac * lapDurationMs).toLong(),
                elapsedMsInLap = (frac * lapDurationMs).toLong(),
                lat = fixedLat ?: (startLat + (endLat - startLat) * frac),
                lon = fixedLon ?: (startLon + (endLon - startLon) * frac),
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = accelerationG,
            )
        }
        return LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = lapStartWallClock,
            lapEndWallClock = lapStartWallClock + lapDurationMs,
            lapDurationMs = lapDurationMs,
            samples = samples,
            sectorBoundaries = listOf(lapStartWallClock),
            trackId = null,
            trackNameSnapshot = null,
        )
    }

    // ---- Case A: Three laps different pace ----

    @Test
    fun caseA_threeLapsDifferentPace() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L).copy(lapIndex = 0)
        val lap1 = mockLap(sampleCount = 1625, lapDurationMs = 65000L).copy(
            lapIndex = 1,
            samples = mockLap(sampleCount = 1625, lapDurationMs = 65000L).samples.map {
                it.copy(speedKmh = 74.0)
            }
        )
        val lap2 = mockLap(sampleCount = 1550, lapDurationMs = 62000L).copy(
            lapIndex = 2,
            samples = mockLap(sampleCount = 1550, lapDurationMs = 62000L).samples.map {
                it.copy(speedKmh = 77.0)
            }
        )
        val laps = listOf(lap0, lap1, lap2)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Assert 1: 3 laps output
        assertEquals(3, result.samplesPerLap.size)

        // Assert 2: all inner lists same size == gridSize
        val gs = result.gridSize
        assertEquals(gs, result.samplesPerLap[0].size)
        assertEquals(gs, result.samplesPerLap[1].size)
        assertEquals(gs, result.samplesPerLap[2].size)

        // Assert 3: gridSize ≈ floor(1000/5) + 1 = 201 (1km straight line)
        val expectedGridSize = floor(1000.0 / 5.0).toInt() + 1
        assertEquals(expectedGridSize, gs)

        // Assert 4: lap0 speed at grid 100 (distance=500m) ≈ 80.0
        assertEquals(80.0, result.samplesPerLap[0][100].speedKmh, 1.0)

        // Assert 5: lap1/lap2 speed at grid 100
        assertEquals(74.0, result.samplesPerLap[1][100].speedKmh, 1.0)
        assertEquals(77.0, result.samplesPerLap[2][100].speedKmh, 1.0)

        // Assert 6: first grid point lat == first sample lat
        assertEquals(laps[0].samples[0].lat, result.samplesPerLap[0][0].lat, 1e-9)

        // Assert 7: gridIndexFor reverse lookup
        assertEquals(100, result.gridIndexFor(500.0))

        // Assert 8: distanceAtGridIndex
        assertEquals(500.0, result.distanceAtGridIndex(100), 1e-9)

        // Assert 9: referenceLapIndex preserved
        assertEquals(0, result.referenceLapIndex)
    }

    // ---- Case B: Single lap input ----

    @Test
    fun caseB_singleLap() {
        val lap = mockLap(sampleCount = 1000, lapDurationMs = 48000L, endLat = 0.0072)
        val laps = listOf(lap)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        assertEquals(1, result.samplesPerLap.size)
        val expectedGridSize = floor(800.0 / 5.0).toInt() + 1 // 800m straight
        assertEquals(expectedGridSize, result.gridSize)

        // Grid 50 lat should fall within mock data range
        val lat50 = result.samplesPerLap[0][50].lat
        assertTrue(lat50 in laps[0].samples.first().lat..laps[0].samples.last().lat)
    }

    // ---- Case C: Distance too short / invalid step ----

    @Test
    fun caseC1_stepZero() {
        val laps = listOf(mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 0, 0.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertTrue(result.samplesPerLap.isEmpty())
    }

    @Test
    fun caseC2_stepNegative() {
        val laps = listOf(mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 0, -5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    @Test
    fun caseC3_stepLargerThanTotalDist() {
        // 1500m total distance → step=3000 → gridSize = floor(1500/3000)+1 = 1
        val lap = mockLap(sampleCount = 500, endLat = 0.0135) // ~1500m
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 3000.0)
        assertEquals(1, result.gridSize)
        assertEquals(1, result.samplesPerLap[0].size)
    }

    // ---- Case D: Reference index out of bounds + empty laps ----

    @Test
    fun caseD1_refIdxNegative() {
        val laps = listOf(mockLap(sampleCount = 100), mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, -1, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.gridIndexFor(100.0))
    }

    @Test
    fun caseD2_refIdxTooLarge() {
        val laps = listOf(mockLap(sampleCount = 100), mockLap(sampleCount = 100))
        val result = LapAlignment.alignByDistance(laps, 5, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.gridIndexFor(0.0))
    }

    @Test
    fun caseD3_emptyLaps() {
        val result = LapAlignment.alignByDistance(emptyList(), 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
        assertEquals(-1, result.referenceLapIndex)
        assertEquals(-1, result.gridIndexFor(50.0))
        assertEquals(0.0, result.distanceAtGridIndex(0), 1e-9)
    }

    @Test
    fun caseD4_refLapSingleSample() {
        val lap = mockLap(sampleCount = 1)
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    @Test
    fun caseD5_refLapAllSamePosition() {
        val lap = mockLap(sampleCount = 100, fixedLat = 31.0, fixedLon = 121.0)
        val laps = listOf(lap)
        val result = LapAlignment.alignByDistance(laps, 0, 5.0)
        assertEquals(LapAlignmentResult.EMPTY, result)
    }

    // ---- Case E: Cumulative distance with duplicates (stationary car) ----

    @Test
    fun caseE_cumulativeDistanceDuplicates() {
        // 300 frames: 100 moving (0→100m), 100 stationary (at 100m), 100 moving (100→200m)
        // endLat chosen so total first-section distance = exactly 100m
        val endLat1 = 100.0 / (Math.PI / 180.0 * 6378137.0) // ≈ 0.000898315284°
        val movingSamples1 = (0 until 100).map { i ->
            val frac = i.toDouble() / 99.0
            LapTelemetrySample(
                absoluteTsMs = (frac * 4000).toLong(),
                elapsedMsInLap = (frac * 4000).toLong(),
                lat = frac * endLat1,
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val stationarySamples = (0 until 100).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 4000L + i * 40L,
                elapsedMsInLap = 4000L + i * 40L,
                lat = endLat1,
                lon = 0.0,
                speedKmh = 0.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val movingSamples2 = (0 until 100).map { i ->
            val frac = i.toDouble() / 99.0
            LapTelemetrySample(
                absoluteTsMs = 8000L + (frac * 4000).toLong(),
                elapsedMsInLap = 8000L + (frac * 4000).toLong(),
                lat = endLat1 + frac * endLat1,
                lon = 0.0,
                speedKmh = 80.0,
                bearingDeg = 0.0,
                accelerationG = null,
            )
        }
        val allSamples = movingSamples1 + stationarySamples + movingSamples2
        val lap = LapTelemetry(
            sessionId = "test",
            lapIndex = 0,
            lapStartWallClock = 0L,
            lapEndWallClock = 12000L,
            lapDurationMs = 12000L,
            samples = allSamples,
            sectorBoundaries = listOf(0L),
            trackId = null,
            trackNameSnapshot = null,
        )

        val result = LapAlignment.alignByDistance(listOf(lap), 0, 5.0)

        // Assert 1: gridSize = floor(200/5)+1 = 41
        assertEquals(41, result.gridSize)

        // Assert 2: grid point at d=100m (gridIdx=20) falls in stationary zone
        // → elapsedMsInLap == 4000L (earliest sample in duplicate range)
        val gridIdx20 = result.gridIndexFor(100.0)
        assertEquals(20, gridIdx20)
        assertEquals(4000L, result.samplesPerLap[0][gridIdx20].elapsedMsInLap)

        // Assert 3: no NaN at stationary grid point
        val sampleAtStationary = result.samplesPerLap[0][gridIdx20]
        assertTrue(!sampleAtStationary.lat.isNaN())
        assertTrue(!sampleAtStationary.lon.isNaN())
        assertTrue(!sampleAtStationary.speedKmh.isNaN())

        // Assert 4: all samples have no NaN
        assertTrue(result.samplesPerLap[0].all {
            !it.lat.isNaN() && !it.lon.isNaN() && !it.speedKmh.isNaN()
        })
    }

    // ---- Case F: Comparison lap sample degradation (fallback) ----

    @Test
    fun caseF1_comparisonLapSingleSample() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L, endLat = 0.009)
        val lap1SingleSample = LapTelemetrySample(
            absoluteTsMs = 30000L,
            elapsedMsInLap = 30000L,
            lat = lap0.samples[500].lat,
            lon = lap0.samples[500].lon,
            speedKmh = 99.9,
            bearingDeg = 0.0,
            accelerationG = 0.7,
        )
        val lap1 = LapTelemetry(
            sessionId = "test",
            lapIndex = 1,
            lapStartWallClock = 0L,
            lapEndWallClock = 30000L,
            lapDurationMs = 30000L,
            samples = listOf(lap1SingleSample),
            sectorBoundaries = listOf(0L),
            trackId = null,
            trackNameSnapshot = null,
        )
        val laps = listOf(lap0, lap1)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Assert 1: output has 2 laps, inner size == gridSize
        assertEquals(2, result.samplesPerLap.size)
        assertEquals(result.gridSize, result.samplesPerLap[1].size)

        // Assert 2: every element == lap1.samples[0] (direct copy)
        assertTrue(result.samplesPerLap[1].all { it == lap1SingleSample })
    }

    @Test
    fun caseF2_comparisonLapEmptySamples() {
        val lap0 = mockLap(sampleCount = 1500, lapDurationMs = 60000L, endLat = 0.009,
            lapStartWallClock = 2000000L)
        val lap1 = LapTelemetry(
            sessionId = "test",
            lapIndex = 1,
            lapStartWallClock = 1000000L,
            lapEndWallClock = 1000000L,
            lapDurationMs = 0L,
            samples = emptyList(),
            sectorBoundaries = listOf(1000000L),
            trackId = null,
            trackNameSnapshot = null,
        )
        val laps = listOf(lap0, lap1)

        val result = LapAlignment.alignByDistance(laps, 0, 5.0)

        // Derived reference: refSample10 = result.samplesPerLap[0][10]
        val refSample10 = result.samplesPerLap[0][10]

        // Assert 1: non-empty list
        assertEquals(result.gridSize, result.samplesPerLap[1].size)

        // Assert 2: 4 fields match reference
        assertEquals(refSample10.lat, result.samplesPerLap[1][10].lat, 1e-9)
        assertEquals(refSample10.lon, result.samplesPerLap[1][10].lon, 1e-9)
        assertEquals(refSample10.speedKmh, result.samplesPerLap[1][10].speedKmh, 1e-9)
        assertEquals(refSample10.elapsedMsInLap, result.samplesPerLap[1][10].elapsedMsInLap)

        // Assert 3: absoluteTsMs re-derived with lap1's lapStartWallClock
        assertEquals(
            laps[1].lapStartWallClock + refSample10.elapsedMsInLap,
            result.samplesPerLap[1][10].absoluteTsMs
        )

        // Assert 4: cross-clock-domain inequality (ref uses 2000000L, lap1 uses 1000000L)
        assertTrue(result.samplesPerLap[1][10].absoluteTsMs != refSample10.absoluteTsMs)

        // Assert 5: accelerationG forced null
        assertTrue(result.samplesPerLap[1].all { it.accelerationG == null })

        // Assert 6: all fallback samples satisfy re-derivation invariant
        assertTrue(result.samplesPerLap[1].all {
            it.absoluteTsMs == laps[1].lapStartWallClock + it.elapsedMsInLap
        })
    }
}
