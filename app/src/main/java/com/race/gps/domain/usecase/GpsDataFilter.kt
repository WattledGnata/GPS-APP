package com.race.gps.domain.usecase

import com.race.gps.domain.model.GpsData
import kotlin.math.abs

/**
 * GPS数据过滤器
 * 用于检测和修正GPS数据中的异常点
 *
 * 设计规范：docs/superpowers/specs/2026-03-21-gps-data-filter-design.md
 */
class GpsDataFilter(
    private val windowSize: Int = 9,
    private val maxAcceleration: Double = 15.0,  // 1.5G ≈ 15 m/s²
    private val maxDeceleration: Double = 20.0   // 2.0G ≈ 20 m/s²
) {
    // 滚动窗口：存储最近的原始速度用于中位数滤波
    private val speedWindow = mutableListOf<Double>()

    // 上一个原始数据点（用于计算加速度）
    private var previousRaw: GpsData? = null

    /**
     * 处理单个GPS数据点
     */
    fun process(raw: GpsData): FilteredGpsData {
        // 1. 计算加速度
        val acceleration = calculateAcceleration(raw)

        // 2. 物理约束检查：检测速度跳变
        val isAnomaly = isPhysicalConstraintViolation(raw)

        // 3. 添加到窗口
        speedWindow.add(raw.speed)
        if (speedWindow.size > windowSize) {
            speedWindow.removeAt(0)
        }

        // 4. 计算输出速度
        val outputSpeed = when {
            isAnomaly && speedWindow.size >= 3 -> {
                // 异常点：用窗口内非异常点的中位数（这里简化为用中位数）
                speedWindow.median()
            }
            speedWindow.size >= 3 -> {
                // 正常点：也用中位数滤波平滑
                speedWindow.median()
            }
            else -> raw.speed
        }

        // 5. 计算置信度
        val confidence = calculateConfidence(isAnomaly, raw.hdop)

        // 6. 更新previousRaw
        previousRaw = raw

        return FilteredGpsData(
            speed = outputSpeed,
            latitude = raw.latitude,
            longitude = raw.longitude,
            altitude = raw.altitude,
            bearing = raw.bearing,
            acceleration = acceleration,
            confidence = confidence,
            isAnomaly = isAnomaly,
            timestamp = raw.timestamp,
            raw = raw
        )
    }

    /**
     * 重置内部状态
     */
    fun reset() {
        speedWindow.clear()
        previousRaw = null
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
        // 加速度上限 1.5G = 15 m/s² → 54 km/h/s
        // 减速度上限 2.0G = 20 m/s² → 72 km/h/s
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
    private fun calculateConfidence(isAnomaly: Boolean, hdop: Double): Double {
        var confidence = if (isAnomaly) 0.5 else 1.0

        // HDOP 因子
        val hdopFactor = when {
            hdop < 1.0 -> 1.0
            hdop < 2.0 -> 0.9
            hdop < 5.0 -> 0.6
            else -> 0.3
        }
        confidence *= hdopFactor

        return confidence.coerceIn(0.0, 1.0)
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
    val timestamp: Long,            // 原始时间戳
    val raw: GpsData                // 原始数据引用
)
