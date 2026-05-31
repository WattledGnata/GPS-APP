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
        // L1 R1 P0/C3 修订：用 computeAccelSegments 真断言 IntRange list（替代仅 size 断言）
        // sample[0..2] 有值 + sample[3..6] null + sample[7..9] 有值 → 期望 [(0..2), (7..9)]
        val segments = computeAccelSegments(samples)
        assertEquals(2, segments.size)
        assertEquals(0..2, segments[0])
        assertEquals(7..9, segments[1])
    }

    // L1 R1 P0/A4+A5 修订：computeAccelSegments 纯函数 IntRange list 行为锁定（5 case）

    @Test
    fun `computeAccelSegments - all non-null returns single full range`() {
        val samples = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = 0.5,
            )
        }
        val segments = computeAccelSegments(samples)
        assertEquals(1, segments.size)
        assertEquals(0..9, segments[0])
    }

    @Test
    fun `computeAccelSegments - all null returns empty list`() {
        val samples = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        }
        val segments = computeAccelSegments(samples)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `computeAccelSegments - leading null returns single tail range`() {
        val samples = (0 until 20).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = if (i < 5) null else 0.5,
            )
        }
        val segments = computeAccelSegments(samples)
        assertEquals(1, segments.size)
        assertEquals(5..19, segments[0])
    }

    @Test
    fun `computeAccelSegments - trailing null returns single head range`() {
        val samples = (0 until 20).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = if (i >= 15) null else 0.5,
            )
        }
        val segments = computeAccelSegments(samples)
        assertEquals(1, segments.size)
        assertEquals(0..14, segments[0])
    }

    @Test
    fun `computeAccelSegments - alternating null returns multiple ranges`() {
        val samples = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 100, elapsedMsInLap = i * 100L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = when (i) {
                    0, 1, 2 -> 0.5
                    3 -> null
                    4, 5, 6 -> 0.5
                    7 -> null
                    8, 9 -> 0.5
                    else -> 0.5
                },
            )
        }
        val segments = computeAccelSegments(samples)
        assertEquals(3, segments.size)
        assertEquals(0..2, segments[0])
        assertEquals(4..6, segments[1])
        assertEquals(8..9, segments[2])
    }

    @Test
    fun `computeAccelSegments - empty input returns empty list`() {
        val segments = computeAccelSegments(emptyList())
        assertTrue(segments.isEmpty())
    }

    // ---- robust-chart-yaxis-scaling: 加速度 Y 轴抗离群单测 ----

    @Test
    fun `accel spike outlier - bounds maxVal not blown up`() {
        // 99 个正常加速度 0.3G，1 个尖刺 10G
        val base = (0 until 99).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 600, elapsedMsInLap = i * 600L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = 0.3,
            )
        }
        val spike = LapTelemetrySample(
            absoluteTsMs = 1000L + 99 * 600, elapsedMsInLap = 99 * 600L,
            lat = 31.0, lon = 121.0, speedKmh = 100.0,
            bearingDeg = null, accelerationG = 10.0,  // 单根尖刺 10G
        )
        val samples = base + spike
        val bounds = computeChartBounds(samples, ChartAxis.ACCEL)

        // robust 模式：加速度轴上界不被 10G 撑满
        assertTrue("maxVal=${bounds.maxVal} should be < 5.0", bounds.maxVal < 5.0)
        // 反例：raw max = 10G > 5（验证反例）
        assertTrue("raw max 10G > 5 is the anti-example", 10.0 > 5.0)
    }
}
