package com.race.gps.domain.model

/**
 * 信号强度等级
 */
enum class SignalStrength {
    EXCELLENT,  // 极好
    GOOD,       // 良好
    FAIR,       // 一般
    POOR,       // 较差
    NONE        // 无信号
}

/**
 * 综合质量等级
 */
enum class QualityLevel {
    EXCELLENT,  // 优秀：所有指标良好
    GOOD,       // 良好：基本满足测试要求
    FAIR,       // 一般：部分指标不理想
    POOR        // 差：不建议测试
}

/**
 * 数据统计信息
 */
data class DataStats(
    val dataAge: Long = 0,              // 数据年龄（ms）
    val packetLossRate: Double = 0.0,   // 丢包率 (%)
    val frequency: Double = 0.0,        // 数据频率 (Hz)
    val signalStrength: SignalStrength = SignalStrength.NONE
)

/**
 * 数据质量评估
 */
data class DataQuality(
    val satelliteCount: Int,            // 卫星数量
    val signalStrength: SignalStrength, // 信号强度
    val hdop: Double,                   // 水平精度因子
    val vdop: Double,                   // 垂直精度因子
    val dataAge: Long,                  // 数据年���（ms）
    val packetLoss: Double,             // 丢包率 (%)
    val frequency: Double,              // 数据频率 (Hz)
    val overall: QualityLevel,          // 综合质量等级
    val overallScore: Int               // 综合分数 (0-100)
) {
    companion object {
        val Empty = DataQuality(
            satelliteCount = 0,
            signalStrength = SignalStrength.NONE,
            hdop = 0.0,
            vdop = 0.0,
            dataAge = 0,
            packetLoss = 0.0,
            frequency = 0.0,
            overall = QualityLevel.POOR,
            overallScore = 0
        )
    }
}
