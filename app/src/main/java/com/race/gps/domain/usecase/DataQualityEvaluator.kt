package com.race.gps.domain.usecase

import com.race.gps.domain.model.DataQuality
import com.race.gps.domain.model.DataStats
import com.race.gps.domain.model.GpsData
import com.race.gps.domain.model.QualityLevel
import com.race.gps.domain.model.SignalStrength

/**
 * 数据质量评估器
 * 实时评估GPS数据质量，用于监控和展示
 */
class DataQualityEvaluator {

    /**
     * 计算数据质量
     */
    fun calculateQuality(gpsData: GpsData, stats: DataStats): DataQuality {
        // 卫星数评分 (0-100)
        val satelliteScore = when {
            gpsData.satelliteCount >= 12 -> 100
            gpsData.satelliteCount >= 8 -> 80
            gpsData.satelliteCount >= 6 -> 60
            gpsData.satelliteCount >= 4 -> 40
            else -> 20
        }

        // HDOP评分 (0-100)
        val hdopScore = when {
            gpsData.hdop < 1.0 -> 100
            gpsData.hdop < 2.0 -> 80
            gpsData.hdop < 5.0 -> 50
            gpsData.hdop > 0 -> 30
            else -> 0
        }

        // 数据年龄评分 (0-100)
        val ageScore = when {
            stats.dataAge < 500 -> 100
            stats.dataAge < 1000 -> 80
            stats.dataAge < 2000 -> 50
            stats.dataAge < 5000 -> 30
            else -> 10
        }

        // 丢包率评分 (0-100)
        val packetLossScore = when {
            stats.packetLossRate < 1 -> 100
            stats.packetLossRate < 5 -> 80
            stats.packetLossRate < 10 -> 60
            stats.packetLossRate < 20 -> 40
            else -> 20
        }

        // 数据频率评分 (0-100)
        val frequencyScore = when {
            stats.frequency >= 10 -> 100  // 10Hz以上
            stats.frequency >= 5 -> 80    // 5Hz以上
            stats.frequency >= 1 -> 60    // 1Hz以上
            stats.frequency > 0 -> 30     // 低于1Hz
            else -> 0
        }

        // 综合分数计算（加权平均）
        val overallScore = (
            satelliteScore * SATELLITE_WEIGHT +
            hdopScore * HDOP_WEIGHT +
            ageScore * AGE_WEIGHT +
            packetLossScore * PACKET_LOSS_WEIGHT +
            frequencyScore * FREQUENCY_WEIGHT
        ) / 100.0

        // 确定综合质量等级
        val overall = when {
            overallScore >= 80 -> QualityLevel.EXCELLENT
            overallScore >= 60 -> QualityLevel.GOOD
            overallScore >= 40 -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }

        // 信号强度评估
        val signalStrength = evaluateSignalStrength(gpsData.satelliteCount, gpsData.hdop)

        return DataQuality(
            satelliteCount = gpsData.satelliteCount,
            signalStrength = signalStrength,
            hdop = gpsData.hdop,
            vdop = gpsData.vdop,
            dataAge = stats.dataAge,
            packetLoss = stats.packetLossRate,
            frequency = stats.frequency,
            overall = overall,
            overallScore = overallScore.toInt()
        )
    }

    /**
     * 评估信号强度
     */
    private fun evaluateSignalStrength(satelliteCount: Int, hdop: Double): SignalStrength {
        return when {
            satelliteCount >= 10 && hdop < 1.0 -> SignalStrength.EXCELLENT
            satelliteCount >= 8 && hdop < 2.0 -> SignalStrength.GOOD
            satelliteCount >= 6 && hdop < 5.0 -> SignalStrength.FAIR
            satelliteCount >= 4 -> SignalStrength.POOR
            else -> SignalStrength.NONE
        }
    }

    /**
     * 获取质量等级的颜色
     */
    fun getQualityColor(level: QualityLevel): ULong = when (level) {
        QualityLevel.EXCELLENT -> 0xFF00FF78u  // 绿色
        QualityLevel.GOOD -> 0xFF4CAF50u       // 亮绿
        QualityLevel.FAIR -> 0xFFFF9800u       // 橙色
        QualityLevel.POOR -> 0xFFF44336u       // 红色
    }

    /**
     * 获取质量等级的中文描述
     */
    fun getQualityDescription(level: QualityLevel): String = when (level) {
        QualityLevel.EXCELLENT -> "优秀"
        QualityLevel.GOOD -> "良好"
        QualityLevel.FAIR -> "一般"
        QualityLevel.POOR -> "较差"
    }

    companion object {
        // 权重配置
        private const val SATELLITE_WEIGHT = 25
        private const val HDOP_WEIGHT = 25
        private const val AGE_WEIGHT = 20
        private const val PACKET_LOSS_WEIGHT = 15
        private const val FREQUENCY_WEIGHT = 15
    }
}
