package com.race.gps.domain.model

/**
 * 异常类型
 */
enum class AnomalyType {
    MISSING_DATA,      // 数据缺失
    OUT_OF_RANGE,      // 数值超出范围
    SUDDEN_JUMP,       // 突变
    INCONSISTENT,      // 数据不一致
    STALE_DATA         // 数据过期
}

/**
 * 异常严重程度
 */
enum class Severity {
    INFO,     // 信息
    WARNING,  // 警告
    ERROR     // 错误
}

/**
 * 数据异常
 */
data class DataAnomaly(
    val type: AnomalyType,
    val severity: Severity,
    val description: String,
    val timestamp: Long,
    val value: Any? = null  // 相关值
)
