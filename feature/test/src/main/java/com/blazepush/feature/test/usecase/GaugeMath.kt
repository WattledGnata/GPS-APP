// @IgnoreFormatCheck
package com.blazepush.feature.test.usecase

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 视频 overlay 模拟仪表（速度指针表 + G 球摩擦圆）的角度/坐标映射纯函数（无副作用，可单测）。
 *
 * round redo-video-overlay-visual-gauges（overlay 视觉升级）：
 * - 速度由 DSEG7 数字角标 → 老式圆形指针速度表（[speedToNeedleAngle]）。
 * - G 值由数字 → 摩擦圆 / G-ball（[gForceToBallOffset]）。
 *
 * 角度约定（Compose Canvas）：0° = 3 点钟方向，顺时针为正（与 drawArc 的 startAngle 同源）。
 * 默认布局采用经典赛车 270° 表：起始 [SPEEDO_START_ANGLE_DEG]（左下）顺时针扫 [SPEEDO_SWEEP_ANGLE_DEG]
 * 至右下，量程 0..[SPEEDO_MAX_KMH]。这些常量抽出便于真机后按视觉口味调。
 *
 * @author CC
 * @description analog speedometer needle angle + friction-circle ball offset pure math
 * @date 2026-05-31
 */
object GaugeMath {

    // ── 速度指针表常量（便于调） ───────────────────────────────────────
    /** 速度表默认量程上界（km/h）。动态量程请用 [speedGaugeMax] 计算后传给 [speedToNeedleAngle]。 */
    const val SPEEDO_MAX_KMH: Double = 260.0

    /** 动态量程计算步长（km/h）：向上取整到此粒度。 */
    private const val SPEEDO_STEP_KMH = 20

    /** 动态量程下界（km/h）：防 topSpeed 很小时量程过小。 */
    private const val SPEEDO_MIN_MAX_KMH = 60

    /**
     * 根据最高尾速计算速度表量程上界（km/h）。
     *
     * 规则：ceil(topSpeedKmh / 20) * 20，并设下界 60。
     * 例：172 → 180；200 → 200；43 → 60；0 → 60。
     *
     * @param topSpeedKmh 本 session 最高尾速（km/h）；null 或 <=0 → 返回默认下界
     * @return 量程上界（km/h，≥60 且为 20 的整数倍）
     */
    fun speedGaugeMax(topSpeedKmh: Double?): Int {
        if (topSpeedKmh == null || topSpeedKmh <= 0.0) return SPEEDO_MIN_MAX_KMH
        val raw = ceil(topSpeedKmh / SPEEDO_STEP_KMH).toInt() * SPEEDO_STEP_KMH
        return max(raw, SPEEDO_MIN_MAX_KMH)
    }

    /** 指针起始角（度，Compose 角度系：0°=3点钟、顺时针为正）。135° = 左下。 */
    const val SPEEDO_START_ANGLE_DEG: Double = 135.0

    /** 指针扫掠角（度，顺时针）。270° → 终点 405°(=45°) 即右下，经典赛车表扇形。 */
    const val SPEEDO_SWEEP_ANGLE_DEG: Double = 270.0

    // ── G 球摩擦圆常量（便于调） ───────────────────────────────────────
    /** 摩擦圆量程（映射到圆半径边界的 G 值）。±1.5G 落到半径。 */
    const val GBALL_MAX_G: Double = 1.5

    /**
     * 速度 → 指针角度（Compose Canvas 度，0°=3点钟、顺时针为正）。
     *
     * 线性映射：angle = startAngle + clamp(speedKmh, 0, maxKmh) / maxKmh * sweepAngle。
     * 边界（design / spec 反例锁定）：
     * - speedKmh <= 0（含负/null 由调用方传 0）→ startAngle（量程起点，不越界回卷）。
     * - speedKmh >= maxKmh → startAngle + sweepAngle（量程终点，clamp 不越界）。
     * - maxKmh <= 0（非法量程）→ 直接返回 startAngle（避免除零）。
     *
     * @param speedKmh 当前速度（km/h）
     * @param maxKmh   量程上界（默认 [SPEEDO_MAX_KMH]）
     * @param startAngleDeg 起始角（默认 [SPEEDO_START_ANGLE_DEG]）
     * @param sweepAngleDeg 扫掠角（默认 [SPEEDO_SWEEP_ANGLE_DEG]）
     * @return 指针角度（度）
     */
    fun speedToNeedleAngle(
        speedKmh: Double,
        maxKmh: Double = SPEEDO_MAX_KMH,
        startAngleDeg: Double = SPEEDO_START_ANGLE_DEG,
        sweepAngleDeg: Double = SPEEDO_SWEEP_ANGLE_DEG,
    ): Double {
        if (maxKmh <= 0.0) return startAngleDeg
        val clamped = speedKmh.coerceIn(0.0, maxKmh)
        return startAngleDeg + (clamped / maxKmh) * sweepAngleDeg
    }

    /**
     * 摩擦圆动点归一化偏移（比例坐标，调用方乘以画布半径得像素坐标）。
     *
     * 坐标系（Compose Canvas，y 向下）：
     * - x = latG / maxG（横向 G / 过弯：右弯产生向右的向心 → 正 latG 在右；负在左）。
     * - y = -lonG / maxG（纵向 G：加速 lonG>0 → 点**向上**（y 负）；制动 lonG<0 → 点**向下**（y 正））。
     * - 向量长度超 1（即合成 G > maxG）→ 等比 clamp 到单位圆边界（保方向，落在圆周上不越界）。
     *
     * 返回的 (x, y) ∈ 单位圆（|v| <= 1）；调用方 center + Offset(x*r, y*r) 即像素位置。
     * 边界（design / spec 反例锁定）：
     * - maxG <= 0（非法量程）→ (0, 0)（点居中，避免除零/NaN）。
     * - latG=lonG=0 → (0, 0)（圆心）。
     *
     * @param latG 横向 G（过弯）
     * @param lonG 纵向 G（加速正 / 制动负）
     * @param maxG 量程（映射到半径边界的 G，默认 [GBALL_MAX_G]）
     * @return (x, y) 归一化偏移，|v| <= 1
     */
    fun gForceToBallOffset(
        latG: Double,
        lonG: Double,
        maxG: Double = GBALL_MAX_G,
    ): Pair<Double, Double> {
        if (maxG <= 0.0) return 0.0 to 0.0
        val nx = latG / maxG
        val ny = -lonG / maxG // 加速向上（Canvas y 负），制动向下
        val len = sqrt(nx * nx + ny * ny)
        return if (len <= 1.0) {
            nx to ny
        } else {
            // 等比 clamp 到单位圆边界（保方向）
            (nx / len) to (ny / len)
        }
    }
}
