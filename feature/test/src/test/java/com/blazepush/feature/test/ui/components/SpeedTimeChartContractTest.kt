package com.blazepush.feature.test.ui.components

import androidx.compose.ui.geometry.Size
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.components.mockSingleLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTimeChartContractTest {

    @Test
    fun `100 sample normal path - coordinates span full canvas width`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        val canvasSize = Size(1000f, 500f)
        val coords = computeChartCoordinates(lap.samples, canvasSize, ChartAxis.SPEED)

        assertEquals(100, coords.size)
        assertEquals(0f, coords[0].x, 1f)
        assertEquals(canvasSize.width, coords.last().x, 1f)
    }

    @Test
    fun `empty sample list - returns empty coordinates`() {
        val coords = computeChartCoordinates(emptyList(), Size(1000f, 500f), ChartAxis.SPEED)
        assertTrue(coords.isEmpty())
    }

    @Test
    fun `null cursor - findNearestSampleIndex still works`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        val idx = findNearestSampleIndex(lap.samples, 30_000L)
        assertTrue(idx in 40..60)
    }

    @Test
    fun `n=1 single sample - coordinates at origin`() {
        val single = listOf(
            LapTelemetrySample(
                absoluteTsMs = 1000L, elapsedMsInLap = 0L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        )
        val coords = computeChartCoordinates(single, Size(1000f, 500f), ChartAxis.SPEED)
        assertEquals(1, coords.size)
        assertEquals(0f, coords[0].x, 1f)
    }

    @Test
    fun `n=1 single sample - findNearestSampleIndex returns 0`() {
        val single = listOf(
            LapTelemetrySample(
                absoluteTsMs = 1000L, elapsedMsInLap = 0L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        )
        assertEquals(0, findNearestSampleIndex(single, 5000L))
    }

    @Test
    fun `findNearestSampleIndex at start - returns 0`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        assertEquals(0, findNearestSampleIndex(lap.samples, 0L))
    }

    @Test
    fun `findNearestSampleIndex at end - returns last index`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        assertEquals(99, findNearestSampleIndex(lap.samples, 60_000L))
    }
}
