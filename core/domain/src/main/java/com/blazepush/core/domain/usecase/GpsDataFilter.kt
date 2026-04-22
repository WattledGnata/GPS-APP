package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsData
import kotlin.math.abs

/**
 * GPS数据过滤器
 * 用于检测和修正GPS数据中的异常点
 *
 * 设计规范：docs/superpowers/specs/2026-03-21-gps-data-filter-design.md
 */
class GpsDataFilter(
    private val windowSize: Int = 9,
    private val maxAcceleration: Double = 25.0,  // 2.5G ≈ 25 m/s²
    private val maxDeceleration: Double = 30.0   // 3.0G ≈ 30 m/s²
) {
    // 滚动窗口：存储最近的原始速度用于中位数滤波
    private val speedWindow = mutableListOf<Double>()
    private val latWindow = mutableListOf<Double>()       // 纬度中位数滤波窗口
    private val lonWindow = mutableListOf<Double>()       // 经度中位数滤波窗口
    private val bearingWindow = mutableListOf<Double>()  // 航向角中位数滤波窗口

    // 上一个原始数据点（用于计算加速度）
    private var previousRaw: GpsData? = null
    private var previousPosition: Pair<Double, Double>? = null  // lat, lon

    /**
     * 处理单个GPS数据点
     */
    fun process(raw: GpsData): FilteredGpsData {
        // 0. 分层守卫（时间 delta 计算类）：协议时间未同步时跳过本次计算，
        //    内部状态（previousRaw / previousPosition / 四个窗口）一律不更新。
        //    参见 openspec/changes/fix-laptime-clock-source-integrity/specs/laptime-clock-source/spec.md
        //    Requirement: 分层守卫 — Scenario "GpsDataFilter 在未同步时不做时间 delta 计算"。
        if (!raw.isTimeSynced) {
            return FilteredGpsData(
                speed = raw.speed,
                latitude = raw.latitude,
                longitude = raw.longitude,
                altitude = raw.altitude,
                bearing = raw.bearing,
                acceleration = 0.0,
                confidence = 0.0,
                isAnomaly = false,
                timestamp = raw.timestamp,
                raw = raw,
                consistencyFactor = 1.0,
                isPositionAnomaly = false
            )
        }

        // === A12：信号丢失重置必须在加速度 / 物理约束判定之前 ===
        // （openspec/changes/fix-gps-data-filter-signal-loss-and-anomaly-hygiene,
        //  Requirement "信号丢失重置必须在加速度 / 物理约束判定之前完成"）
        // v1 顺序 (calculateAcceleration → isPhysicalConstraintViolation → 重置) 让
        // 失联首帧用旧 previousRaw 计算 dt 巨大的 maxDelta = 90 × dt，真实跳变被误判为
        // "非异常"。修后先重置：previousRaw = null 时，下面三个判定函数都走早退分支
        // （0.0 / false / (1.0, false)），把本帧作为"新基准"。
        val dtFromPrevious = previousRaw?.let { (raw.timestamp - it.timestamp) / 1000.0 } ?: 0.0
        if (dtFromPrevious > 0.2) {
            previousRaw = null
            previousPosition = null
        }

        // 1. 计算加速度（previousRaw == null 时早退返回 0.0）
        val acceleration = calculateAcceleration(raw)

        // 2. 物理约束检查：检测速度跳变（previousRaw == null 时早退返回 false）
        val isAnomaly = isPhysicalConstraintViolation(raw)

        // 3. 位置-速度一致性检验（previousPosition == null 时早退返回 (1.0, false)）
        val (consistencyFactor, isPositionAnomaly) = checkPositionVelocityConsistency(raw)

        // === A14：异常帧不进滤波窗口 ===
        // （Requirement "异常帧不得进入滤波窗口，isAnomaly 分支简化"）
        // v1 所有帧无条件 add，异常帧把 median 拉偏；isAnomaly 分支两个 when 体等价
        // 是语义冗余。修后：speed/lat/lon 窗口按异常类型条件隔离；bearing 不受影响；
        // outputSpeed / outputLat / outputLon 统一 `if window.size >= 3 median else raw`。
        if (!isAnomaly) {
            speedWindow.add(raw.speed)
        }
        if (!isPositionAnomaly) {
            latWindow.add(raw.latitude)
            lonWindow.add(raw.longitude)
        }
        bearingWindow.add(raw.bearing)
        if (speedWindow.size > windowSize) {
            speedWindow.removeAt(0)
        }
        if (latWindow.size > windowSize) {
            latWindow.removeAt(0)
            lonWindow.removeAt(0)
        }
        if (bearingWindow.size > windowSize) {
            bearingWindow.removeAt(0)
        }

        // 4. 计算输出（简化分支：不再按 isAnomaly 分流）
        val outputSpeed = if (speedWindow.size >= 3) speedWindow.median() else raw.speed
        val outputLat = if (latWindow.size >= 3) latWindow.median() else raw.latitude
        val outputLon = if (lonWindow.size >= 3) lonWindow.median() else raw.longitude
        val outputBearing = if (bearingWindow.size >= 3) bearingWindow.circularMedian() else raw.bearing

        // 5. 计算置信度
        val confidence = calculateConfidence(isAnomaly, raw.hdop, consistencyFactor)

        // === A13：异常帧不更新 previousRaw / previousPosition ===
        // （Requirement "异常帧 MUST NOT 更新 previousRaw / previousPosition"）
        // 依赖 A12 的 dt > 200ms 重置兜底：若连续 N 帧异常，previousRaw 保持旧值不变
        // 直到下一帧相对 previousRaw 的 dt 超过 200ms，A12 自动重置，避免永久锁死。
        if (!isAnomaly && !isPositionAnomaly) {
            previousRaw = raw
            previousPosition = raw.latitude to raw.longitude
        }

        return FilteredGpsData(
            speed = outputSpeed,
            latitude = outputLat,
            longitude = outputLon,
            altitude = raw.altitude,
            bearing = outputBearing,
            acceleration = acceleration,
            confidence = confidence,
            isAnomaly = isAnomaly,
            timestamp = raw.timestamp,
            raw = raw,
            consistencyFactor = consistencyFactor,
            isPositionAnomaly = isPositionAnomaly
        )
    }

    /**
     * 重置内部状态
     */
    fun reset() {
        speedWindow.clear()
        latWindow.clear()
        lonWindow.clear()
        bearingWindow.clear()
        previousRaw = null
        previousPosition = null
    }

    // ==================== 私有方法 ====================

    /**
     * 计算纵向加速度 (m/s²)
     */
    private fun calculateAcceleration(current: GpsData): Double {
        val prev = previousRaw ?: return 0.0

        val dt = (current.timestamp - prev.timestamp) / 1000.0 // 秒
        if (dt <= 0) return 0.0

        // 速度差 (m/s) = Δv(km/h) / 3.6
        val dv = (current.speed - prev.speed) / 3.6

        return dv / dt // m/s²
    }

    /**
     * 检查物理约束是否被违反
     */
    private fun isPhysicalConstraintViolation(current: GpsData): Boolean {
        val prev = previousRaw ?: return false

        val dt = (current.timestamp - prev.timestamp) / 1000.0
        if (dt <= 0) return false

        val speedDelta = abs(current.speed - prev.speed)

        // 计算允许的最大速度变化 (km/h)
        // 加速度上限 2.5G = 25 m/s² → 90 km/h/s
        // 减速度上限 3.0G = 30 m/s² → 108 km/h/s
        val isAccelerating = current.speed > prev.speed
        val maxDeltaPerSecond = if (isAccelerating) {
            maxAcceleration * 3.6 // m/s² → km/h/s
        } else {
            maxDeceleration * 3.6
        }
        val maxDelta = maxDeltaPerSecond * dt

        return speedDelta > maxDelta
    }

    /**
     * 计算置信度
     */
    private fun calculateConfidence(isAnomaly: Boolean, hdop: Double, consistencyFactor: Double): Double {
        var confidence = if (isAnomaly) 0.5 else 1.0

        // HDOP 因子
        val hdopFactor = when {
            hdop < 1.0 -> 1.0
            hdop < 2.0 -> 0.9
            hdop < 5.0 -> 0.6
            else -> 0.3
        }
        confidence *= hdopFactor
        confidence *= consistencyFactor

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * 位置-速度一致性检验
     * 基于原始数据计算 v_implied = Δd / Δt，与 GPS 报告速度对比
     */
    private fun checkPositionVelocityConsistency(current: GpsData): Pair<Double, Boolean> {
        val prevPos = previousPosition ?: return 1.0 to false
        val prevData = previousRaw ?: return 1.0 to false

        val dt = (current.timestamp - prevData.timestamp) / 1000.0
        if (dt <= 0 || dt > 0.2) return 1.0 to false

        // 计算位移 Δd（简化平面近似）
        val latRad = Math.toRadians(current.latitude)
        val deltaLatM = abs(current.latitude - prevPos.first) * 111320.0
        val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)
        val distanceM = Math.sqrt(deltaLatM * deltaLatM + deltaLonM * deltaLonM)

        // Δd 过小时跳过一致性检查（0.5m 远小于 GPS 噪声，规避被淹没）
        if (distanceM < 0.5) return 1.0 to false

        // 计算 v_implied
        val vImpliedKmh = (distanceM / dt) * 3.6
        val speedDiff = abs(current.speed - vImpliedKmh)

        // 航向变化降权（>30°/s 时降权）
        val bearingDelta = abs(current.bearing - prevData.bearing)
        val normalizedBearingDelta = if (bearingDelta > 180) 360 - bearingDelta else bearingDelta
        val bearingPenalty = if (normalizedBearingDelta > 30.0) 0.8 else 1.0

        // HDOP 降权
        val hdopPenalty = if (current.hdop > 3.0) 0.5 else 1.0

        // 确定容差
        val tolerance = getConsistencyTolerance(current.speed)

        // 一致性因子
        val ratio = speedDiff / tolerance
        val baseFactor = when {
            ratio <= 1.0 -> 1.0
            ratio <= 2.0 -> 0.8
            ratio <= 3.0 -> 0.6
            else -> 0.3
        }

        val consistencyFactor = (baseFactor * bearingPenalty * hdopPenalty).coerceIn(0.0, 1.0)
        val isPositionAnomaly = ratio > 3.0

        return consistencyFactor to isPositionAnomaly
    }

    /**
     * 根据速度确定一致性容差
     */
    private fun getConsistencyTolerance(speed: Double): Double {
        return when {
            speed < 5.0 -> 3.0
            speed < 60.0 -> 5.0
            else -> 10.0
        }
    }

    /**
     * 扩展函数：计算中位数
     */
    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }

    /**
     * 扩展函数：计算循环中位数（用于航向角）
     * 将角度转换为单位向量后求平均角，正确处理 0°=360° 的循环问题
     * 场景：左转 350°→10°，窗口 [350°,10°,20°,30°,40°,50°,60°,70°,80°]
     * 普通中位数 = 40°（错误），循环中位数 ≈ 20°（正确）
     */
    private fun List<Double>.circularMedian(): Double {
        if (isEmpty()) return 0.0
        if (size == 1) return this[0]

        // 将所有角度转换为单位向量后求和
        var sumSin = 0.0
        var sumCos = 0.0
        for (angle in this) {
            sumSin += Math.sin(Math.toRadians(angle))
            sumCos += Math.cos(Math.toRadians(angle))
        }

        // 平均向量的角度即为循环中位数
        val meanAngle = Math.toDegrees(Math.atan2(sumSin, sumCos))

        // 规范化到 [0, 360) 范围
        return if (meanAngle < 0) meanAngle + 360 else meanAngle
    }
}

/**
 * 滤波后的GPS数据
 */
data class FilteredGpsData(
    val speed: Double,              // 滤波后速度 (km/h)
    val latitude: Double,           // 滤波后纬度
    val longitude: Double,          // 滤波后经度
    val altitude: Double,           // 滤波后海拔
    val bearing: Double,            // 滤波后航向角
    val acceleration: Double,       // 纵向加速度 (m/s²)
    val confidence: Double,         // 置信度 0.0 ~ 1.0
    val isAnomaly: Boolean,         // 是否被修正
    val isTestTriggered: Boolean = false, // 是否触发测试（连续5点加速度>0.1G）
    val timestamp: Long,            // 原始时间戳
    val raw: GpsData,                // 原始数据引用
    val consistencyFactor: Double = 1.0,  // 位置-速度一致性因子
    val isPositionAnomaly: Boolean = false  // 位置异常标记
)
