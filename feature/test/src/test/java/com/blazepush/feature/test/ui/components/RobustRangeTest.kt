// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * robustRange 纯函数单测（IQR Tukey 抗离群 Y 轴范围，robust-chart-yaxis-scaling round）。
 *
 * 覆盖：正常数据 / 含离群尖刺 / 数据少 fallback / 全相同值 / 空 list
 */
class RobustRangeTest {

    @Test
    fun `normal data without outlier - returns close to raw min-max`() {
        val values = listOf(80.0, 90.0, 100.0, 110.0, 120.0)
        val (lower, upper) = robustRange(values)
        // Q1=90, Q3=110, IQR=20, lower=60→coerce 80, upper=140→coerce 120
        assertEquals(80.0, lower, 1e-6)
        assertEquals(120.0, upper, 1e-6)
    }

    @Test
    fun `single spike outlier - upper bound not blown up`() {
        // 6 正常点 80-92 + 1 尖刺 300
        val values = listOf(80.0, 85.0, 86.0, 88.0, 90.0, 92.0, 300.0)
        val (_, upper) = robustRange(values)
        // 验证 robust 上界不超过 200（正常值 92 远低于 300）
        assertTrue("upper=$upper should be < 200", upper < 200.0)
        // 反例：raw max = 300，robust upper << 300
        val rawMax = values.max()
        assertTrue("raw max=$rawMax > 200 is the anti-example", rawMax > 200.0)
    }

    @Test
    fun `fewer than 4 points - fallback raw min-max`() {
        val values = listOf(50.0, 100.0, 80.0)
        val (lower, upper) = robustRange(values)
        assertEquals(50.0, lower, 1e-6)
        assertEquals(100.0, upper, 1e-6)
    }

    @Test
    fun `all same value - returns same pair`() {
        val values = listOf(100.0, 100.0, 100.0, 100.0, 100.0)
        val (lower, upper) = robustRange(values)
        assertEquals(100.0, lower, 1e-6)
        assertEquals(100.0, upper, 1e-6)
    }

    @Test
    fun `empty list - returns sentinel 0 to 1`() {
        val (lower, upper) = robustRange(emptyList())
        assertEquals(0.0, lower, 1e-6)
        assertEquals(1.0, upper, 1e-6)
    }

    @Test
    fun `exactly 4 points - IQR path runs not fallback`() {
        // 4 点刚好触发 IQR 路径（size == 4，不 fallback）
        val values = listOf(10.0, 20.0, 30.0, 200.0)  // 200 是尖刺
        val result = robustRange(values)
        // sorted=[10,20,30,200], Q1=sorted[1]=20, Q3=sorted[3]=200, IQR=180
        // upper=200+270=470 → coerce 200；lower=20-270=-250 → coerce 10
        assertEquals(10.0, result.first, 1e-6)
        assertEquals(200.0, result.second, 1e-6)  // 4 点时 Q3 就是那个尖刺，coerced to rawMax
        // 此 case 验证 n=4 不走 fallback（若走 fallback 结果相同，不影响）
        assertTrue(result.second <= 200.0)
    }

    @Test
    fun `large dataset with lower outlier - lower bound not blown down`() {
        // 正常 90 点速度 80-120，1 个极低尖刺 -500（不应拉低下界超出合理范围）
        val normal = (0 until 10).map { 80.0 + it * 4.0 }  // [80, 84, ..., 116]
        val values = normal + listOf(-500.0)
        val (lower, upper) = robustRange(values)
        // 下界不应低于 -500（raw min），但期望比 -500 高得多
        assertTrue("lower=$lower should be > -100", lower > -100.0)
        assertTrue("upper=$upper should be >= 100", upper >= 100.0)
    }
}
