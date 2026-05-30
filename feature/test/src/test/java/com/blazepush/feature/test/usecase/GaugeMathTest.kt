// @IgnoreFormatCheck
package com.blazepush.feature.test.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * [GaugeMath] 纯函数单测：速度指针角度映射 + G 球摩擦圆坐标映射（含 clamp 边界 + 反例）。
 */
class GaugeMathTest {

    private val eps = 1e-9

    // ── speedToNeedleAngle ───────────────────────────────────────────

    @Test
    fun `Scenario 1 - 速度 0 时指针在量程起点`() {
        val a = GaugeMath.speedToNeedleAngle(0.0)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG, a, eps)
    }

    @Test
    fun `Scenario 2 - 速度等于量程上界时指针在量程终点（start+sweep）`() {
        val a = GaugeMath.speedToNeedleAngle(GaugeMath.SPEEDO_MAX_KMH)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG + GaugeMath.SPEEDO_SWEEP_ANGLE_DEG, a, eps)
    }

    @Test
    fun `Scenario 3 - 半程速度时指针在中点`() {
        val a = GaugeMath.speedToNeedleAngle(GaugeMath.SPEEDO_MAX_KMH / 2.0)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG + GaugeMath.SPEEDO_SWEEP_ANGLE_DEG / 2.0, a, eps)
    }

    @Test
    fun `反例 - 超过量程上界 clamp 不越界（不超过 start+sweep）`() {
        val a = GaugeMath.speedToNeedleAngle(999.0)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG + GaugeMath.SPEEDO_SWEEP_ANGLE_DEG, a, eps)
    }

    @Test
    fun `反例 - 负速度 clamp 到起点（不回卷到 start 以下）`() {
        val a = GaugeMath.speedToNeedleAngle(-50.0)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG, a, eps)
    }

    @Test
    fun `反例 - 非法量程 maxKmh=0 返回起始角不除零`() {
        val a = GaugeMath.speedToNeedleAngle(100.0, maxKmh = 0.0)
        assertEquals(GaugeMath.SPEEDO_START_ANGLE_DEG, a, eps)
    }

    @Test
    fun `自定义量程与扫掠角参数生效`() {
        // max=200, start=90, sweep=180 → 100km/h 在 90+90=180
        val a = GaugeMath.speedToNeedleAngle(100.0, maxKmh = 200.0, startAngleDeg = 90.0, sweepAngleDeg = 180.0)
        assertEquals(180.0, a, eps)
    }

    // ── gForceToBallOffset ───────────────────────────────────────────

    @Test
    fun `Scenario 4 - 零 G 时点在圆心`() {
        val (x, y) = GaugeMath.gForceToBallOffset(0.0, 0.0)
        assertEquals(0.0, x, eps)
        assertEquals(0.0, y, eps)
    }

    @Test
    fun `Scenario 5 - 满量程加速（纵向 +maxG）点在正上方（y=-1）`() {
        val (x, y) = GaugeMath.gForceToBallOffset(latG = 0.0, lonG = GaugeMath.GBALL_MAX_G)
        assertEquals(0.0, x, eps)
        assertEquals(-1.0, y, eps) // 加速向上：Canvas y 负
    }

    @Test
    fun `Scenario 6 - 满量程制动（纵向 -maxG）点在正下方（y=+1）`() {
        val (x, y) = GaugeMath.gForceToBallOffset(latG = 0.0, lonG = -GaugeMath.GBALL_MAX_G)
        assertEquals(0.0, x, eps)
        assertEquals(1.0, y, eps) // 制动向下：Canvas y 正
    }

    @Test
    fun `Scenario 7 - 满量程右弯（横向 +maxG）点在正右方（x=+1）`() {
        val (x, y) = GaugeMath.gForceToBallOffset(latG = GaugeMath.GBALL_MAX_G, lonG = 0.0)
        assertEquals(1.0, x, eps)
        assertEquals(0.0, y, eps)
    }

    @Test
    fun `Scenario 8 - 半量程横向 G 时 x 等于 0点5`() {
        val (x, _) = GaugeMath.gForceToBallOffset(latG = GaugeMath.GBALL_MAX_G / 2.0, lonG = 0.0)
        assertEquals(0.5, x, eps)
    }

    @Test
    fun `反例 - 合成 G 超量程时等比 clamp 到单位圆边界（保方向）`() {
        // 横向 + 纵向各满量程 → 合成 sqrt(2) > 1 → clamp 到边界，|v|==1，方向 45°
        val (x, y) = GaugeMath.gForceToBallOffset(
            latG = GaugeMath.GBALL_MAX_G,
            lonG = -GaugeMath.GBALL_MAX_G, // 制动 → y 正
        )
        val len = sqrt(x * x + y * y)
        assertEquals(1.0, len, 1e-6)
        // 方向保持：x>0（右）、y>0（制动向下），且等比 → x≈y
        assertTrue(x > 0.0 && y > 0.0)
        assertEquals(x, y, 1e-6)
    }

    @Test
    fun `反例 - 量程内合成 G 不被 clamp（保留原比例）`() {
        // 横向 0.75G、纵向 0.6G、量程 1.5 → nx=0.5, ny=-0.4，|v|<1 不 clamp
        val (x, y) = GaugeMath.gForceToBallOffset(latG = 0.75, lonG = 0.6)
        assertEquals(0.5, x, eps)
        assertEquals(-0.4, y, eps)
    }

    @Test
    fun `反例 - 非法量程 maxG=0 返回圆心不除零`() {
        val (x, y) = GaugeMath.gForceToBallOffset(latG = 1.0, lonG = 1.0, maxG = 0.0)
        assertEquals(0.0, x, eps)
        assertEquals(0.0, y, eps)
    }
}
