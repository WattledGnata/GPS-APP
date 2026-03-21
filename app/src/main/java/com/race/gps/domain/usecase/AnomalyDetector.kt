package com.race.gps.domain.usecase

import com.race.gps.domain.model.DataAnomaly
import com.race.gps.domain.model.DataStats
import com.race.gps.domain.model.AnomalyType
import com.race.gps.domain.model.GpsData
import com.race.gps.domain.model.Severity
import kotlin.math.abs

/**
 * 异常检测器
 * 检测GPS数据中的异常情况
 */
class AnomalyDetector(
    private val config: Config = Config()
) {

    data class Config(
        val maxDataAge: Long = 2000,           // 最大数据年龄（ms）
        val maxSpeedJump: Double = 20.0,       // 最大速度突变（km/h）
        val minSatelliteCount: Int = 4,        // 最小卫星数
        val maxSpeed: Double = 500.0,          // 最大合理速度（km/h）
        val minSpeed: Double = -1.0            // 最小合理速度（km/h，允许轻微负值）
    )

    /**
     * 检测异常
     * @param current 当前GPS数据
     * @param previous 上一次GPS数据
     * @param stats 数据统计信息
     * @return 检测到的异常列表
     */
    fun detect(
        current: GpsData,
        previous: GpsData?,
        stats: DataStats
    ): List<DataAnomaly> {
        val anomalies = mutableListOf<DataAnomaly>()
        val now = System.currentTimeMillis()

        // 检查数据年龄
        if (stats.dataAge > config.maxDataAge) {
            anomalies.add(
                DataAnomaly(
                    type = AnomalyType.STALE_DATA,
                    severity = if (stats.dataAge > 5000) Severity.ERROR else Severity.WARNING,
                    description = "数据过期: ${stats.dataAge}ms",
                    timestamp = now,
                    value = stats.dataAge
                )
            )
        }

        // 检查速度突变
        if (previous != null) {
            val speedDiff = abs(current.speed - previous.speed)
            if (speedDiff > config.maxSpeedJump) {
                anomalies.add(
                    DataAnomaly(
                        type = AnomalyType.SUDDEN_JUMP,
                        severity = Severity.WARNING,
                        description = "速度突变: %.1f → %.1f km/h".format(previous.speed, current.speed),
                        timestamp = now,
                        value = speedDiff
                    )
                )
            }
        }

        // 检查速度范围
        if (current.speed < config.minSpeed || current.speed > config.maxSpeed) {
            anomalies.add(
                DataAnomaly(
                    type = AnomalyType.OUT_OF_RANGE,
                    severity = Severity.ERROR,
                    description = "速度异常: %.1f km/h".format(current.speed),
                    timestamp = now,
                    value = current.speed
                )
            )
        }

        // 检查卫星数
        if (current.satelliteCount < config.minSatelliteCount) {
            anomalies.add(
                DataAnomaly(
                    type = AnomalyType.INCONSISTENT,
                    severity = if (current.satelliteCount == 0) Severity.ERROR else Severity.WARNING,
                    description = "卫星数过少: ${current.satelliteCount}",
                    timestamp = now,
                    value = current.satelliteCount
                )
            )
        }

        // 检查坐标合理性（非零）
        if (current.latitude == 0.0 && current.longitude == 0.0) {
            anomalies.add(
                DataAnomaly(
                    type = AnomalyType.MISSING_DATA,
                    severity = Severity.WARNING,
                    description = "坐标数据缺失",
                    timestamp = now
                )
            )
        }

        return anomalies
    }

    /**
     * 快速检查是否有严重异常
     */
    fun hasSevereAnomaly(
        current: GpsData,
        previous: GpsData?,
        stats: DataStats
    ): Boolean {
        return detect(current, previous, stats).any { it.severity == Severity.ERROR }
    }
}
