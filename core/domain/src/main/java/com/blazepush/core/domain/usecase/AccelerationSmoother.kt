package com.blazepush.core.domain.usecase

/**
 * 加速度信号平滑差分单一入口（性能测试 / 离线 G 值统计 / UI G 值曲线共用）。
 *
 * 算法：5 点 Savitzky-Golay 一阶导数中心差分（Press et al., Numerical Recipes §14.8），
 * 等间距假设；非均匀 dt 自动退化到 3 点 SG。
 *
 * 设计参见 `openspec/changes/smooth-perftest-acceleration-curve/`：
 * - spec.md Requirement: AccelerationSmoother 实现 5 点 Savitzky-Golay 中心差分
 * - design.md Decisions D1
 *
 * @author CC
 * @description SG 5 点平滑差分纯函数
 * @date 2026-05-03
 */
object AccelerationSmoother {

    /** 相对 dt_median 的最大允许偏差比例（20% = 0.2）。超过即整段输入退化到 3 点 SG 路径。 */
    private const val MAX_DT_DEVIATION_RATIO = 0.20

    fun compute(samples: List<TimedSpeedSample>): List<Double> {
        val n = samples.size
        if (n <= 1) return List(n) { 0.0 }

        val v = DoubleArray(n) { samples[it].speedKmh / 3.6 }
        val dts = DoubleArray(n - 1) { (samples[it + 1].timestamp - samples[it].timestamp) / 1000.0 }

        if (n == 2) return computeDegenerateN2(v, dts[0])
        if (n == 3) return computeDegenerateN3(v, medianDt(dts))
        if (n == 4) return computeDegenerateN4(v, medianDt(dts))

        val dtMedian = medianDt(dts)
        val nonUniform = dts.any { isOutOfTolerance(it, dtMedian) }
        return if (nonUniform) computeNonUniform3PointSg(v, dts) else compute5PointSg(v, dtMedian)
    }

    private fun isOutOfTolerance(dt: Double, dtMedian: Double): Boolean {
        if (dtMedian <= 0.0) return true
        val ratio = dt / dtMedian
        return ratio < (1.0 - MAX_DT_DEVIATION_RATIO) || ratio > (1.0 + MAX_DT_DEVIATION_RATIO)
    }

    private fun medianDt(dts: DoubleArray): Double {
        if (dts.isEmpty()) return 0.0
        val sorted = dts.sortedArray()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
    }

    private fun compute5PointSg(v: DoubleArray, dt: Double): List<Double> {
        val n = v.size
        val out = DoubleArray(n)
        out[0] = (-25.0 * v[0] + 48.0 * v[1] - 36.0 * v[2] + 16.0 * v[3] - 3.0 * v[4]) / (12.0 * dt)
        out[1] = (-3.0 * v[0] - 10.0 * v[1] + 18.0 * v[2] - 6.0 * v[3] + 1.0 * v[4]) / (12.0 * dt)
        for (i in 2..(n - 3)) {
            out[i] = (-2.0 * v[i - 2] - 1.0 * v[i - 1] + 0.0 * v[i] + 1.0 * v[i + 1] + 2.0 * v[i + 2]) / (10.0 * dt)
        }
        out[n - 2] = (-1.0 * v[n - 5] + 6.0 * v[n - 4] - 18.0 * v[n - 3] + 10.0 * v[n - 2] + 3.0 * v[n - 1]) / (12.0 * dt)
        out[n - 1] = (3.0 * v[n - 5] - 16.0 * v[n - 4] + 36.0 * v[n - 3] - 48.0 * v[n - 2] + 25.0 * v[n - 1]) / (12.0 * dt)
        return out.toList()
    }

    /**
     * 非均匀 dt 退化路径：边界用前/后向一阶差分，内部用跨两段 dt 的中心差分
     * `(v[i+1] - v[i-1]) / (dt[i-1] + dt[i])`。spec 称之为「3 点 SG」（与等间距
     * 3 点 SG 数学等价，但适配非均匀 dt）。
     *
     * dt = 0 / dt 负值 / span = 0 等病理输入下输出该位 0.0 而不是 NaN/Infinity，
     * 避免 NaN 污染下游 Compose Canvas 绘图。
     */
    private fun computeNonUniform3PointSg(v: DoubleArray, dts: DoubleArray): List<Double> {
        val n = v.size
        val out = DoubleArray(n)
        out[0] = if (dts[0] > 0.0) (v[1] - v[0]) / dts[0] else 0.0
        for (i in 1..(n - 2)) {
            val span = dts[i - 1] + dts[i]
            out[i] = if (span > 0.0) (v[i + 1] - v[i - 1]) / span else 0.0
        }
        out[n - 1] = if (dts[n - 2] > 0.0) (v[n - 1] - v[n - 2]) / dts[n - 2] else 0.0
        return out.toList()
    }

    private fun computeDegenerateN2(v: DoubleArray, dt: Double): List<Double> {
        if (dt <= 0.0) return listOf(0.0, 0.0)
        val a = (v[1] - v[0]) / dt
        return listOf(a, a)
    }

    private fun computeDegenerateN3(v: DoubleArray, dt: Double): List<Double> {
        if (dt <= 0.0) return listOf(0.0, 0.0, 0.0)
        val a0 = (-3.0 * v[0] + 4.0 * v[1] - v[2]) / (2.0 * dt)
        val a1 = (v[2] - v[0]) / (2.0 * dt)
        val a2 = (v[0] - 4.0 * v[1] + 3.0 * v[2]) / (2.0 * dt)
        return listOf(a0, a1, a2)
    }

    private fun computeDegenerateN4(v: DoubleArray, dt: Double): List<Double> {
        if (dt <= 0.0) return listOf(0.0, 0.0, 0.0, 0.0)
        val a0 = (-11.0 * v[0] + 18.0 * v[1] - 9.0 * v[2] + 2.0 * v[3]) / (6.0 * dt)
        val a1 = (-2.0 * v[0] - 3.0 * v[1] + 6.0 * v[2] - v[3]) / (6.0 * dt)
        val a2 = (v[0] - 6.0 * v[1] + 3.0 * v[2] + 2.0 * v[3]) / (6.0 * dt)
        val a3 = (-2.0 * v[0] + 9.0 * v[1] - 18.0 * v[2] + 11.0 * v[3]) / (6.0 * dt)
        return listOf(a0, a1, a2, a3)
    }
}

data class TimedSpeedSample(
    val timestamp: Long,
    val speedKmh: Double,
)

/** 标准重力加速度（m/s²）。加速度 → G 单位转换的统一入口。 */
const val GRAVITY_MS2 = 9.81
