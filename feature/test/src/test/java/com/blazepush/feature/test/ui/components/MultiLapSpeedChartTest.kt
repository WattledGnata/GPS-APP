// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.components

import androidx.compose.ui.graphics.Color
import com.blazepush.core.domain.model.LapTelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 MultiLapSpeedChart 纯函数单测（time-axis 多圈叠加尺度 + 跨圈最近邻）。
 *
 * computeMultiLapBounds：统一 X（最长圈 maxElapsedMs）+ 统一 Y（全局 speed min/max）。
 * nearestSampleByElapsed：每圈各自 elapsedMsInLap 二分最近邻（不同采样密度各取各圈）。
 *
 * @author CC
 * @description multi-lap speed chart pure-function contract
 * @date 2026-05-30
 */
class MultiLapSpeedChartTest {

    private fun sample(elapsedMs: Long, speed: Double): LapTelemetrySample =
        LapTelemetrySample(
            absoluteTsMs = 1_700_000_000_000L + elapsedMs,
            elapsedMsInLap = elapsedMs,
            lat = 31.0,
            lon = 121.0,
            speedKmh = speed,
            bearingDeg = null,
            accelerationG = null,
        )

    private fun lapSeries(lapNumber: Int, samples: List<LapTelemetrySample>): LapSeries =
        LapSeries(lapNumber = lapNumber, color = Color.Red, samples = samples)

    @Test
    fun `case A - two laps different duration - maxElapsedMs is longest lap + global speed bounds`() {
        val lapA = lapSeries(
            1,
            listOf(sample(0L, 80.0), sample(30_000L, 120.0), sample(60_000L, 100.0)),
        )
        val lapB = lapSeries(
            2,
            listOf(sample(0L, 60.0), sample(31_000L, 140.0), sample(62_000L, 90.0)),
        )

        val bounds = computeMultiLapBounds(listOf(lapA, lapB))

        // maxElapsedMs = 最长圈（lapB 的 62_000）
        assertEquals(62_000L, bounds.maxElapsedMs)
        // 全局 speedMin/Max + 5% padding：全局 min=60, max=140, range=80 → pad=4
        assertEquals(60.0 - 4.0, bounds.speedMin, 1e-6)
        assertEquals(140.0 + 4.0, bounds.speedMax, 1e-6)
    }

    @Test
    fun `case A2 - empty series degrades to safe bounds`() {
        val bounds = computeMultiLapBounds(emptyList())
        assertEquals(1L, bounds.maxElapsedMs)
        assertEquals(0.0, bounds.speedMin, 1e-6)
        assertEquals(1.0, bounds.speedMax, 1e-6)
    }

    @Test
    fun `case B - nearest sample between two samples takes the closer one`() {
        val samples = listOf(
            sample(0L, 80.0),
            sample(1_000L, 100.0),
            sample(2_000L, 120.0),
        )
        // target 1_400 落在 1_000 与 2_000 之间，更近 1_000
        val near = nearestSampleByElapsed(samples, 1_400L)
        assertNotNull(near)
        assertEquals(1_000L, near!!.elapsedMsInLap)

        // target 1_600 更近 2_000
        val near2 = nearestSampleByElapsed(samples, 1_600L)
        assertEquals(2_000L, near2!!.elapsedMsInLap)
    }

    @Test
    fun `case B2 - target out of range clamps to endpoints`() {
        val samples = listOf(
            sample(1_000L, 80.0),
            sample(2_000L, 100.0),
            sample(3_000L, 120.0),
        )
        // target 远小于首 sample → clamp 到首
        assertEquals(1_000L, nearestSampleByElapsed(samples, 0L)!!.elapsedMsInLap)
        // target 远大于末 sample → clamp 到末
        assertEquals(3_000L, nearestSampleByElapsed(samples, 99_000L)!!.elapsedMsInLap)
    }

    @Test
    fun `case C - empty samples returns null, single sample returns that sample`() {
        assertNull(nearestSampleByElapsed(emptyList(), 1_000L))

        val single = listOf(sample(500L, 90.0))
        val near = nearestSampleByElapsed(single, 99_000L)
        assertNotNull(near)
        assertEquals(500L, near!!.elapsedMsInLap)
    }

    @Test
    fun `case D - different sampling density - each lap picks its own nearest at same target`() {
        // 圈 A 密采样（每 100ms），圈 B 掉帧（每 500ms）
        val lapA = (0..20).map { sample(it * 100L, 100.0 + it) }
        val lapB = listOf(
            sample(0L, 60.0),
            sample(500L, 80.0),
            sample(1_000L, 110.0),
            sample(1_500L, 130.0),
            sample(2_000L, 95.0),
        )
        val target = 1_240L
        val nearA = nearestSampleByElapsed(lapA, target)!!
        val nearB = nearestSampleByElapsed(lapB, target)!!

        // 圈 A 最近邻 = 1_200（密采样命中更近）
        assertEquals(1_200L, nearA.elapsedMsInLap)
        // 圈 B 最近邻 = 1_000（掉帧 → 1_000 比 1_500 更近 1_240）
        assertEquals(1_000L, nearB.elapsedMsInLap)
        // 验证非跨圈精确相等：两圈在同 target 下取的 elapsedMsInLap 不一定相同
        assertTrue(nearA.elapsedMsInLap != nearB.elapsedMsInLap)
    }

    // ---- robust-chart-yaxis-scaling: 多圈 Y 轴抗离群单测 ----

    @Test
    fun `case E - cross-series spike - speedMax not blown up`() {
        // series[0] 含一个 400 km/h 尖刺，其余 50 点速度 80-130
        val normalSamples = (0 until 50).map { i -> sample(i * 1000L, 80.0 + i * 1.0) }
        val spikeSeriesSamples = listOf(sample(0L, 400.0)) + (1 until 50).map { i -> sample(i * 1000L, 110.0) }
        val series = listOf(
            lapSeries(1, spikeSeriesSamples),
            lapSeries(2, normalSamples),
        )
        val bounds = computeMultiLapBounds(series)

        // robust 模式：speedMax 不被 400 撑满
        assertTrue("speedMax=${bounds.speedMax} should be < 250", bounds.speedMax < 250.0)
        // 反例：raw max = 400 > 250
        assertTrue("raw max 400 > 250 is the anti-example", 400.0 > 250.0)
    }

    @Test
    fun `case E2 - normal multi-lap - Y range covers all normal speeds`() {
        // 所有圈速度在 60-140，无离群点，Y 轴应覆盖正常数据
        val lapA = lapSeries(1, listOf(sample(0L, 60.0), sample(30_000L, 140.0), sample(60_000L, 100.0)))
        val lapB = lapSeries(2, listOf(sample(0L, 70.0), sample(30_000L, 130.0), sample(60_000L, 90.0)))
        val bounds = computeMultiLapBounds(listOf(lapA, lapB))
        // 正常数据不被截断：范围覆盖 [60, 140]
        assertTrue("speedMin=${bounds.speedMin} should be <= 60", bounds.speedMin <= 60.0)
        assertTrue("speedMax=${bounds.speedMax} should be >= 140", bounds.speedMax >= 140.0)
    }
}
