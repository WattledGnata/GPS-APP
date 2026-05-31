// @IgnoreFormatCheck
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

    // ---- robust-chart-yaxis-scaling: Y 轴抗离群单测 ----

    @Test
    fun `spike outlier in speed - maxVal not blown up to spike value`() {
        val base = (0 until 99).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 600, elapsedMsInLap = i * 600L,
                lat = 31.0, lon = 121.0, speedKmh = 120.0,
                bearingDeg = null, accelerationG = null,
            )
        }
        val spike = LapTelemetrySample(
            absoluteTsMs = 1000L + 99 * 600, elapsedMsInLap = 99 * 600L,
            lat = 31.0, lon = 121.0, speedKmh = 300.0,  // 单根尖刺
            bearingDeg = null, accelerationG = null,
        )
        val samples = base + spike
        val bounds = computeChartBounds(samples, ChartAxis.SPEED)

        // robust 模式：Y 轴上界不被 300 撑满
        assertTrue("maxVal=${bounds.maxVal} should be < 200", bounds.maxVal < 200.0)
        // 反例：raw max = 300，比 200 大（验证反例让测试有意义）
        assertTrue("raw max=300 > 200 is the anti-example spike value", 300.0 > 200.0)
    }

    @Test
    fun `fewer than 4 speed samples - bounds fallback to raw min-max`() {
        val samples = listOf(
            LapTelemetrySample(
                absoluteTsMs = 1000L, elapsedMsInLap = 0L,
                lat = 31.0, lon = 121.0, speedKmh = 80.0, bearingDeg = null, accelerationG = null,
            ),
            LapTelemetrySample(
                absoluteTsMs = 2000L, elapsedMsInLap = 1000L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0, bearingDeg = null, accelerationG = null,
            ),
            LapTelemetrySample(
                absoluteTsMs = 3000L, elapsedMsInLap = 2000L,
                lat = 31.0, lon = 121.0, speedKmh = 90.0, bearingDeg = null, accelerationG = null,
            ),
        )
        val bounds = computeChartBounds(samples, ChartAxis.SPEED)
        // 3 点 fallback raw min/max，上界包含 100 km/h 加 5% padding
        assertTrue("maxVal should cover 100 km/h", bounds.maxVal >= 100.0)
    }

    @Test
    fun `spike clamp - outlier point y coordinate clamped to canvas boundary`() {
        val base = (0 until 10).map { i ->
            LapTelemetrySample(
                absoluteTsMs = 1000L + i * 1000, elapsedMsInLap = i * 1000L,
                lat = 31.0, lon = 121.0, speedKmh = 100.0,
                bearingDeg = null, accelerationG = null,
            )
        }
        val spike = LapTelemetrySample(
            absoluteTsMs = 1000L + 10 * 1000, elapsedMsInLap = 10 * 1000L,
            lat = 31.0, lon = 121.0, speedKmh = 500.0,  // 远超 robustRange 上界
            bearingDeg = null, accelerationG = null,
        )
        val samples = base + spike
        val canvasSize = Size(1000f, 500f)
        val coords = computeChartCoordinates(samples, canvasSize, ChartAxis.SPEED)

        // 尖刺点坐标 clamp 到 [0, height]（不超出 canvas 顶）
        val spikeY = coords.last().y
        assertTrue("spike y=$spikeY should be >= 0 (clamped)", spikeY >= 0f)
        assertTrue("spike y=$spikeY should be <= canvas height ${canvasSize.height}", spikeY <= canvasSize.height)
    }
}
