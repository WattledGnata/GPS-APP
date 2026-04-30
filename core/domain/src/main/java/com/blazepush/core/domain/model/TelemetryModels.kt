package com.blazepush.core.domain.model

/**
 * Telemetry session 类型枚举：加减速测试 vs 圈速 session。
 *
 * @author CC
 * @description telemetry session type enum
 * @date 2026-04-30
 */
enum class TelemetrySessionType {
    PERFORMANCE_TEST,
    LAP_SESSION,
}

/**
 * Telemetry 单帧 sample 领域模型。
 * tsDeltaMs 是相对 session 起点的毫秒差，bearingDeg=null 表示无效（GPS 静止）。
 *
 * @author CC
 * @description single telemetry sample domain model
 * @date 2026-04-30
 */
data class TelemetrySample(
    val tsDeltaMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val flags: Int = 0,
)

/**
 * Telemetry session metadata 领域模型（对应 Room TelemetrySessionEntity）。
 *
 * @author CC
 * @description telemetry session metadata domain model
 * @date 2026-04-30
 */
data class TelemetrySession(
    val sessionId: String,
    val sessionType: TelemetrySessionType,
    val startTs: Long,
    val endTs: Long,
    val binaryFilePath: String,
    val lapCount: Int = 0,
    val bestLapMs: Long? = null,
)

/**
 * 圈速过线事件领域模型（对应 Room CrossingEventEntity）。
 * 是计时精度真相源，accepted=false 时 reason 字段说明丢弃原因。
 *
 * @author CC
 * @description crossing event domain model
 * @date 2026-04-30
 */
data class TelemetryCrossingEvent(
    val sessionId: String,
    val lapIndex: Int,
    val crossingTimestampMs: Long,
    val speedKmh: Double,
    val gateId: String,
    val gateType: String,
    val accepted: Boolean,
    val reason: String,
    val directionScore: Double?,
)