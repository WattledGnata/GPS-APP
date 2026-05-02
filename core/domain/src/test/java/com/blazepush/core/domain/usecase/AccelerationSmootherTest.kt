package com.blazepush.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AccelerationSmoother 5 点 Savitzky-Golay 单测。
 *
 * 锁定 spec.md `Requirement: AccelerationSmoother 实现 5 点 Savitzky-Golay 中心差分` 的全部 scenarios。
 *
 * @author CC
 * @description SG 平滑差分单测
 * @date 2026-05-03
 */
class AccelerationSmootherTest {

    // ====== 构造工具 ======

    private fun uniformAccelerationSamples(
        startSpeedKmh: Double,
        accelKmhPerFrame: Double,
        n: Int,
        dtMs: Long = 40L,
    ): List<TimedSpeedSample> = List(n) { i ->
        TimedSpeedSample(
            timestamp = i * dtMs,
            speedKmh = startSpeedKmh + accelKmhPerFrame * i,
        )
    }

    // ====== 1.4 等间隔 25Hz 匀加速序列 ======

    @Test
    fun `等间隔 25Hz 匀加速 10 点中心区间偏差小于 0_5`() {
        // 每帧 +10 km/h, dt=40ms => a = 10/3.6/0.04 ≈ 69.444 m/s²
        val samples = uniformAccelerationSamples(0.0, 10.0, 10, 40L)
        val expected = 10.0 / 3.6 / 0.04

        val out = AccelerationSmoother.compute(samples)

        assertEquals(10, out.size)
        for (i in 2..7) {
            assertTrue("中心点 i=$i 偏差过大: ${out[i]} vs $expected", abs(out[i] - expected) < 0.5)
        }
    }

    // ====== 1.5 SG 高频噪声压制 ======

    @Test
    fun `合成匀加速含高频噪声 SG_RMSE 较朴素差分降幅 ge 70percent`() {
        val baseAccelMs2 = 5.0
        val baseAccelKmhPerFrame = baseAccelMs2 * 0.04 * 3.6
        val n = 200
        val rnd = Random(42)
        val samples = List(n) { i ->
            val noise = (rnd.nextDouble() - 0.5) * 0.2
            TimedSpeedSample(
                timestamp = i * 40L,
                speedKmh = baseAccelKmhPerFrame * i + noise,
            )
        }

        val out = AccelerationSmoother.compute(samples)
        val sgRmse = rmseAgainstTruth(out, baseAccelMs2)

        val naive = computeNaiveDiff(samples)
        val naiveRmse = rmseAgainstTruth(naive, baseAccelMs2)

        val drop = 1.0 - sgRmse / naiveRmse
        assertTrue("SG RMSE 降幅 $drop < 70% (sg=$sgRmse, naive=$naiveRmse)", drop >= 0.70)
    }

    private fun rmseAgainstTruth(values: List<Double>, truth: Double): Double {
        val inner = values.subList(2, values.size - 2)
        val sumSq = inner.sumOf { (it - truth) * (it - truth) }
        return sqrt(sumSq / inner.size)
    }

    private fun computeNaiveDiff(samples: List<TimedSpeedSample>): List<Double> {
        val out = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            val dt = (samples[i].timestamp - samples[i - 1].timestamp) / 1000.0
            if (dt > 0) {
                val dv = (samples[i].speedKmh - samples[i - 1].speedKmh) / 3.6
                out[i] = dv / dt
            }
        }
        return out.toList()
    }

    // ====== 1.6 退化系数表 N=0/1/2/3/4 ======

    @Test
    fun `长度 0 返回空列表`() {
        assertEquals(emptyList<Double>(), AccelerationSmoother.compute(emptyList()))
    }

    @Test
    fun `长度 1 返回单个 0_0`() {
        val out = AccelerationSmoother.compute(listOf(TimedSpeedSample(0L, 50.0)))
        assertEquals(listOf(0.0), out)
    }

    @Test
    fun `长度 2 等加速度两点偏差小于 0_5`() {
        // a = 5 m/s², dt=40ms => Δv = 5*0.04*3.6 = 0.72 km/h
        val truth = 5.0
        val samples = uniformAccelerationSamples(10.0, 0.72, 2, 40L)
        val out = AccelerationSmoother.compute(samples)
        assertEquals(2, out.size)
        assertTrue("a[0] 偏差: ${out[0]}", abs(out[0] - truth) < 0.5)
        assertTrue("a[1] 偏差: ${out[1]}", abs(out[1] - truth) < 0.5)
    }

    @Test
    fun `长度 3 等加速度各点偏差小于 0_5`() {
        val truth = 5.0
        val samples = uniformAccelerationSamples(10.0, 0.72, 3, 40L)
        val out = AccelerationSmoother.compute(samples)
        assertEquals(3, out.size)
        for (i in 0..2) {
            assertTrue("N=3 a[$i] 偏差: ${out[i]}", abs(out[i] - truth) < 0.5)
        }
    }

    @Test
    fun `长度 4 等加速度各点偏差小于 0_5`() {
        val truth = 5.0
        val samples = uniformAccelerationSamples(10.0, 0.72, 4, 40L)
        val out = AccelerationSmoother.compute(samples)
        assertEquals(4, out.size)
        for (i in 0..3) {
            assertTrue("N=4 a[$i] 偏差: ${out[i]}", abs(out[i] - truth) < 0.5)
        }
    }

    // ====== 1.7 SG 自身的单点跳变压制（不级联 9 点 median） ======

    @Test
    fun `匀速 21 帧第 10 帧注入跳变 SG 自身可压低`() {
        val n = 21
        val samples = List(n) { i ->
            val speed = if (i == 10) 55.0 else 50.0
            TimedSpeedSample(timestamp = i * 40L, speedKmh = speed)
        }
        val out = AccelerationSmoother.compute(samples)

        val rawSpike = (5.0 / 3.6 / 0.04)
        for (i in 8..12) {
            assertTrue("跳变邻近 i=$i |a|=${abs(out[i])} 应 < 8 m/s² (rawSpike=$rawSpike)", abs(out[i]) < 8.0)
        }
        for (i in listOf(0, 1, 5, 15, 19, 20)) {
            assertTrue("窗外 i=$i |a|=${abs(out[i])} 应 < 0.5 m/s²", abs(out[i]) < 0.5)
        }
    }

    // ====== 1.8 边界系数（forward/backward）二次多项式精度 ======

    @Test
    fun `边界系数对二次多项式速度序列精度 lt 0_5`() {
        // v(t) = at + bt²，则 a(t) = a + 2bt，每点 a 值可解析求出
        val a = 4.0
        val b = 0.5
        val n = 10
        val dtS = 0.04
        val samples = List(n) { i ->
            val t = i * dtS
            val speedMs = a * t + b * t * t
            TimedSpeedSample(timestamp = i * 40L, speedKmh = speedMs * 3.6)
        }
        val out = AccelerationSmoother.compute(samples)

        for (i in listOf(0, 1, n - 2, n - 1)) {
            val truth = a + 2.0 * b * (i * dtS)
            assertTrue("边界 i=$i 偏差: out=${out[i]} truth=$truth", abs(out[i] - truth) < 0.5)
        }
    }

    // ====== 1.9 反例 — 非均匀 dt 拒绝走 5 点 SG 中心系数 ======

    @Test
    fun `非均匀 dt 单帧偏差 ge 20percent 不直接套 5 点中心系数`() {
        val samples = listOf(
            TimedSpeedSample(0L, 10.0),
            TimedSpeedSample(40L, 20.0),
            TimedSpeedSample(240L, 30.0),
            TimedSpeedSample(280L, 40.0),
            TimedSpeedSample(320L, 50.0),
        )
        val v = samples.map { it.speedKmh / 3.6 }
        val dtMedianSec = 0.04
        val centerScalarValueAtI2 = (-2.0 * v[0] - 1.0 * v[1] + 0.0 * v[2] + 1.0 * v[3] + 2.0 * v[4]) / (10.0 * dtMedianSec)

        val out = AccelerationSmoother.compute(samples)

        assertNotEquals("非均匀 dt MUST NOT 直接套 5 点中心系数", centerScalarValueAtI2, out[2], 1e-6)
        assertEquals(5, out.size)
        out.forEach { assertTrue("不抛异常且数值有限: $it", it.isFinite()) }

        // 退化路径数值正确性（解析真值，容差 0.05 m/s²）：
        // i=0 边界 forward: (v[1]-v[0])/0.04 = (20-10)/3.6/0.04 ≈ 69.44
        // i=1 中心: (v[2]-v[0])/(0.04+0.20) = (30-10)/3.6/0.24 ≈ 23.15
        // i=2 中心: (v[3]-v[1])/(0.20+0.04) = (40-20)/3.6/0.24 ≈ 23.15
        // i=3 中心: (v[4]-v[2])/(0.04+0.04) = (50-30)/3.6/0.08 ≈ 69.44
        // i=4 边界 backward: (v[4]-v[3])/0.04 = (50-40)/3.6/0.04 ≈ 69.44
        assertEquals(69.44, out[0], 0.05)
        assertEquals(23.15, out[1], 0.05)
        assertEquals(23.15, out[2], 0.05)
        assertEquals(69.44, out[3], 0.05)
        assertEquals(69.44, out[4], 0.05)
    }

    // ====== P2-6 防御性 testcase：timestamp 重复 (dt=0) 不输出 NaN/Infinity ======

    @Test
    fun `timestamp 重复 dt 等于 0 全段返回 finite 不抛异常`() {
        // 6 帧 timestamp 全部相同（dt=0），dts 全 0 → medianDt=0 → isOutOfTolerance 全部 true
        // → 走退化 computeNonUniform3PointSg → 边界 / span 全 0 → 该位输出 0.0
        val samples = List(6) { TimedSpeedSample(timestamp = 100L, speedKmh = 50.0) }
        val out = AccelerationSmoother.compute(samples)
        assertEquals(6, out.size)
        out.forEachIndexed { i, a ->
            assertTrue("i=$i 输出非 finite: $a", a.isFinite())
            assertEquals("i=$i dt=0 应输出 0.0", 0.0, a, 1e-9)
        }
    }
}
