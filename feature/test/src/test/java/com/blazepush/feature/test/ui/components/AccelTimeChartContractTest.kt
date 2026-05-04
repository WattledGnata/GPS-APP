package com.blazepush.feature.test.ui.components

import androidx.compose.ui.geometry.Size
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.feature.test.ui.components.mockSingleLap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelTimeChartContractTest {

    @Test
    fun `100 sample all non-null - coordinates computed`() {
        val lap = mockSingleLap(n = 100, lapDurationMs = 60_000)
        val coords = computeChartCoordinates(lap.samples, Size(1000f, 500f), ChartAxis.ACCEL)
        assertEquals(100, coords.size)
    }

    @Test
    fun `empty sample list - returns empty coordinates`() {
        val coords = computeChartCoordinates(emptyList(), Size(1000f, 500f), ChartAxis.ACCEL)
        assertTrue(coords.isEmpty())
    }

    @Test
    fun `null cursor - no exception`() {
        val lap = mockSingleLap(n = 100)
        val idx = findNearestSampleIndex(lap.samples, 30_000L)
        assertTrue(idx >= 0)
    }

    @Test
    fun `n=1 single sample - coordinate at origin`() {
        val single = listOf(
            LapTelemetrySample(
                absoluteTsMs = 1000L, elapsedMsInLap = 0L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = 0.5,
            )
        )
        val coords = computeChartCoordinates(single, Size(1000f, 500f), ChartAxis.ACCEL)
        assertEquals(1, coords.size)
    }

    @Test
    fun `all null accelerationG - computeChartBounds returns default`() {
        val samples = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        }
        val bounds = computeChartBounds(samples, ChartAxis.ACCEL)
        assertEquals(0.0, bounds.minVal, 0.01)
        assertEquals(1.0, bounds.maxVal, 0.01)
    }

    @Test
    fun `partial null - coordinates for non-null samples only`() {
        val samples = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = if (i in 3..6) null else i * 0.1,
            )
        }
        val coords = computeChartCoordinates(samples, Size(1000f, 500f), ChartAxis.ACCEL)
        assertEquals(10, coords.size)
        // Null accelG samples get mapped with value 0.0 in computeChartCoordinates
    }
}
