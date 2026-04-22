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
    val directionScore: Double?
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

        // 2. 米空间线段相交
        val crossedGateSegment = segmentsIntersectMeters(
            ax = prevN, ay = prevE,
            bx = currN, by = currE,
            cx = gateStartN, cy = gateStartE,
            dx = gateEndN, dy = gateEndE
        )

        if (!crossedGateSegment) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.NoIntersection,
                directionalSpeedMps = null,
                directionScore = null
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
                directionScore = 0.0
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
                directionScore = directionScore
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
                directionScore = directionScore
            )
        }

        return GateCrossingDetection(
            accepted = true,
            reason = CrossingReason.Accepted,
            directionalSpeedMps = directionalSpeedMps,
            directionScore = directionScore
        )
    }

    private fun segmentsIntersectMeters(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        dx: Double,
        dy: Double
    ): Boolean {
        val abx = bx - ax
        val aby = by - ay
        val cdx = dx - cx
        val cdy = dy - cy
        val denominator = (abx * cdy) - (aby * cdx)
        if (denominator == 0.0) {
            // 严格平行（浮点等于 0 几乎不会发生，但保持防御性）
            return false
        }

        val acx = cx - ax
        val acy = cy - ay
        val t = ((acx * cdy) - (acy * cdx)) / denominator
        val u = ((acx * aby) - (acy * abx)) / denominator
        return t in 0.0..1.0 && u in 0.0..1.0
    }

    companion object {
        /** 赤道附近 1° 纬度 ≈ 111320 米（真实值 110.57–111.69 km 之间，工程近似）。 */
        private const val METERS_PER_DEGREE_LAT = 111320.0
    }
}
