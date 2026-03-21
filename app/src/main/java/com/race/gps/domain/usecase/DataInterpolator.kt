package com.race.gps.domain.usecase

import com.race.gps.domain.model.GpsData

/**
 * 数据插值器
 * 当检测到数据丢失时，通过插值生成缺失的数据点
 */
class DataInterpolator {

    /**
     * 插值生成缺失的数据点
     *
     * @param previous 前一个数据点
     * @param current 当前数据点
     * @param expectedIntervalMs 期望的数据间隔（ms）
     * @return 插值后的数据点列表（包含当前点）
     */
    fun interpolate(
        previous: GpsData,
        current: GpsData,
        expectedIntervalMs: Long = DEFAULT_INTERVAL_MS
    ): List<GpsData> {
        val timeDiff = current.timestamp - previous.timestamp

        // 计算缺失的数据点数量
        val missingDataPoints = (timeDiff / expectedIntervalMs).toInt() - 1

        // 无需插值
        if (missingDataPoints <= 0) {
            return listOf(current)
        }

        val result = mutableListOf<GpsData>()
        val timeStep = timeDiff / (missingDataPoints + 1)

        for (i in 1..missingDataPoints) {
            val ratio = i.toDouble() / (missingDataPoints + 1)
            val interpolatedData = GpsData(
                timestamp = previous.timestamp + timeStep * i,
                speed = interpolateValue(previous.speed, current.speed, ratio),
                latitude = interpolateValue(previous.latitude, current.latitude, ratio),
                longitude = interpolateValue(previous.longitude, current.longitude, ratio),
                altitude = interpolateValue(previous.altitude, current.altitude, ratio),
                bearing = interpolateValue(previous.bearing, current.bearing, ratio),
                satelliteCount = current.satelliteCount,
                hdop = interpolateValue(previous.hdop, current.hdop, ratio),
                vdop = interpolateValue(previous.vdop, current.vdop, ratio),
                frequency = current.frequency,
                isConnected = true,
                isTestReady = current.isTestReady,
                errorMessage = null
            )
            result.add(interpolatedData)
        }

        result.add(current)
        return result
    }

    /**
     * 线性插值
     */
    private fun interpolateValue(start: Double, end: Double, ratio: Double): Double {
        return start + (end - start) * ratio
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L  // 默认期望100ms间隔（10Hz）
    }
}
