package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.TimingGate
import kotlin.math.cos
import kotlin.math.sqrt

data class GateCrossingDetection(
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double?,
    val directionScore: Double?,
    /**
     * 过线插值参数 t ∈ [0, 1]（`prev → current` 线段上过线点的归一化位置）。
     *
     * 契约（openspec `fix-lap-timing-closure-and-precision-contract` R1）：
     * - `accepted == true` 时 MUST 非 null 且经 `coerceIn(0.0, 1.0)` clamp（防
     *   `segmentsIntersectMeters` 浮点边界越界：实际内部计算的 `t` 可能微越界，
     *   例如 `-1e-16` 或 `1.0000001`）
     * - `accepted == false` 时 MUST 为 null（`NoIntersection` / `WrongDirection` /
     *   `TooSlow` 均 null）
     * - 取值范围：`[0.0, 1.0]`，单位：无（归一化参数）
     *
     * 插值时刻 = `previousSample.timestampMillis + crossingProgress × (currentSample.timestampMillis - previousSample.timestampMillis)`
     */
    val crossingProgress: Double? = null
)

/**
 * 几何约定：
 * - 所有输入坐标都是 WGS84 经纬度（度）。
 * - detector 内部以 gate 线中点为原点做本地 ENU（东-北）投影到米，
 *   线段相交、方向点积、速度投影全部在米空间进行。
 * - `TimingGate.passDirection` 的幅值在度空间无实际意义，detector
 *   只使用其**方向**：内部投影到米空间后归一化为单位向量。因此 preset
 *   不需要手工归一化，只要方向正确即可。
 * - `directionScore` 单位是米（沿 passDirection 方向的位移投影）；
 *   `directionalSpeedMps` 单位是 m/s（字段名实至名归，不是度²/秒）。
 *
 * 对应对抗 review 1.1（量纲错位）+ 1.7（近平行数值不稳）。
 */
class GateCrossingDetector {

    fun detect(
        previous: GpsSample,
        current: GpsSample,
        gate: TimingGate
    ): GateCrossingDetection {
        // 1. 以 gate 线中点为投影原点
        //
        // 投影契约（A11 / backlog, opsx code review BC.3）：
        // `lonScale = cos(originLat)` 用 gate 中点单点计算，下游把它应用到 previous /
        // current 位置投影。这隐含假设：
        //   - gate 线长度 < 1 km（典型赛道起终点 ~60 m，远小于该阈值）
        //   - 调用方保证 previous / current 在 gate 附近 < 1 km 半径内（生产中由
        //     `bridgeGpsToLapTiming` + engine 的 activeLap 边界保证，单帧位移 < 10 m）
        //
        // 超出该半径会因 originLat 与实际纬度的差异引入 >0.01% 的经度投影形变。
        // 当前所有使用场景（TFIC preset 30.49°N，gate 60 m，帧间位移 1-10 m）
        // 形变 <1e-5，可忽略；未来长 gate / 远距 prev-curr 场景需重新评估。
        val originLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val originLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val lonScale = METERS_PER_DEGREE_LAT * cos(Math.toRadians(originLat))

        val prevN = (previous.latitude - originLat) * METERS_PER_DEGREE_LAT
        val prevE = (previous.longitude - originLon) * lonScale
        val currN = (current.latitude - originLat) * METERS_PER_DEGREE_LAT
        val currE = (current.longitude - originLon) * lonScale
        val gateStartN = (gate.line.start.latitude - originLat) * METERS_PER_DEGREE_LAT
        val gateStartE = (gate.line.start.longitude - originLon) * lonScale
        val gateEndN = (gate.line.end.latitude - originLat) * METERS_PER_DEGREE_LAT
        val gateEndE = (gate.line.end.longitude - originLon) * lonScale

        // 2. 米空间线段相交（R1：返回 Double? 带 t 参数，null 表示不相交）
        val intersectionT = segmentsIntersectMeters(
            ax = prevN, ay = prevE,
            bx = currN, by = currE,
            cx = gateStartN, cy = gateStartE,
            dx = gateEndN, dy = gateEndE
        )

        if (intersectionT == null) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.NoIntersection,
                directionalSpeedMps = null,
                directionScore = null,
                crossingProgress = null
            )
        }

        // 3. passDirection 投影到米空间 + 归一化为单位向量（只取方向，忽略幅值）
        val passDirN = gate.passDirection.x * METERS_PER_DEGREE_LAT
        val passDirE = gate.passDirection.y * lonScale
        val passDirLen = sqrt(passDirN * passDirN + passDirE * passDirE)
        if (passDirLen == 0.0) {
            // 零向量 passDirection 无法判定方向，视为几何上不可穿越
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.WrongDirection,
                directionalSpeedMps = null,
                directionScore = 0.0,
                crossingProgress = null
            )
        }
        val passUnitN = passDirN / passDirLen
        val passUnitE = passDirE / passDirLen

        // 4. movement 点积 passUnit = 沿 passDirection 方向的位移投影（米）
        val movementN = currN - prevN
        val movementE = currE - prevE
        val directionScore = movementN * passUnitN + movementE * passUnitE

        if (directionScore <= 0.0) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.WrongDirection,
                directionalSpeedMps = null,
                directionScore = directionScore,
                crossingProgress = null
            )
        }

        // 5. directionalSpeedMps = directionScore / dt（米/秒）
        val dtSeconds = (current.timestampMillis - previous.timestampMillis) / 1000.0
        val directionalSpeedMps = if (dtSeconds > 0.0) directionScore / dtSeconds else Double.POSITIVE_INFINITY

        if (gate.minDirectionalSpeedMps != null && directionalSpeedMps < gate.minDirectionalSpeedMps) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.TooSlow,
                directionalSpeedMps = directionalSpeedMps,
                directionScore = directionScore,
                crossingProgress = null
            )
        }

        // R1：accepted 分支填 crossingProgress，clamp [0.0, 1.0] 防浮点边界越界
        return GateCrossingDetection(
            accepted = true,
            reason = CrossingReason.Accepted,
            directionalSpeedMps = directionalSpeedMps,
            directionScore = directionScore,
            crossingProgress = intersectionT.coerceIn(0.0, 1.0)
        )
    }

    /**
     * 米空间线段相交（R1 返回 Double? 带 t 参数）。
     *
     * - 几何相交时返回 `t ∈ [-tolerance, 1 + tolerance]`（prev→current 线段上过线点的归一化位置）
     * - 不相交（t/u 超出容差范围）返回 null
     * - `denominator == 0`（严格平行，浮点等于 0 几乎不会发生）返回 null（保留 v1 防御性语义）
     *
     * **浮点边界容差**（P1-1 修订）：`t` / `u` 经浮点运算可能微越界（如 `-1e-16` /
     * `1.0000001`），严格 `in 0.0..1.0` 会把本应相交的边界线段误判为不相交。改用
     * `[-tolerance, 1 + tolerance]` 容差接受微越界，然后由 [detect] 的 `coerceIn(0.0, 1.0)`
     * 做最终 clamp。`tolerance` 参数 default 为 [FLOAT_BOUNDARY_TOLERANCE]，测试可传
     * 大 tolerance 验证容差机制。
     *
     * Visibility：`internal` 供本包 + `@VisibleForTesting` 测试直接调用（R1 Scenario 5
     * 直接断言本函数返回值契约）。生产代码仍只通过 `detect` 间接使用。
     */
    @androidx.annotation.VisibleForTesting
    internal fun segmentsIntersectMeters(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        dx: Double,
        dy: Double,
        tolerance: Double = FLOAT_BOUNDARY_TOLERANCE
    ): Double? {
        val abx = bx - ax
        val aby = by - ay
        val cdx = dx - cx
        val cdy = dy - cy
        val denominator = (abx * cdy) - (aby * cdx)
        if (denominator == 0.0) {
            // 严格平行（浮点等于 0 几乎不会发生，但保持防御性）
            return null
        }

        val acx = cx - ax
        val acy = cy - ay
        val t = ((acx * cdy) - (acy * cdx)) / denominator
        val u = ((acx * aby) - (acy * abx)) / denominator
        // P1-1：浮点边界容差接受 t / u 微越界，由 detect 做最终 clamp
        return if (
            t >= -tolerance && t <= 1.0 + tolerance &&
            u >= -tolerance && u <= 1.0 + tolerance
        ) t else null
    }

    companion object {
        /**
         * 浮点边界容差常量（P1-1）：`segmentsIntersectMeters` 接受 `t / u` 在
         * `[-FLOAT_BOUNDARY_TOLERANCE, 1 + FLOAT_BOUNDARY_TOLERANCE]` 内的微越界值，
         * 然后由 [detect] 的 `coerceIn(0.0, 1.0)` 做最终 clamp。取值依据：
         * - `1e-9` 对 IEEE 754 Double 机器 epsilon（~2.2e-16）留 7 个数量级余量
         * - 对"显著不相交"的线段（`t > 1.5`）仍返回 null，不影响正常几何判定
         */
        internal const val FLOAT_BOUNDARY_TOLERANCE = 1e-9

        /**
         * 赤道附近 1° 纬度 ≈ 111320 米。精确值随纬度变化（110.57–111.69 km）。
         *
         * 精度契约（A10 / backlog, opsx code review BC.2）：
         * - **accept/reject 判定**：误差可忽略（detector 只看相对几何关系，跨纬度一致的
         *   常量放大因子不影响线段相交、方向点积、单位向量归一化的定性结果）。
         * - **directionalSpeedMps 读数**：在 ±45° 纬度内偏差 ±0.5%；赤道/极地各走一端
         *   偏差接近 ±0.4%。TFIC 30.49°N 真实约 110,856 m/deg，本常量 111,320 m/deg
         *   偏差 +0.42%，120 km/h 读数误差约 ±0.5 km/h —— 够用。
         * - **不够用的场景**：若未来派生 <0.1% 精度的距离/速度指标（累计里程、分段
         *   平均车速、起终点插值的圈时毫秒级对齐），必须换 WGS84 椭球大地测量公式
         *   （geodesic / Vincenty），并把本常量降级为"粗粒度几何判定专用"。
         */
        private const val METERS_PER_DEGREE_LAT = 111320.0
    }
}
