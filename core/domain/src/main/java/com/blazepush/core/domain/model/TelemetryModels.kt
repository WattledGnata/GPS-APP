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
    val topSpeedKmh: Double? = null,
    val trackId: String? = null,
    val trackNameSnapshot: String? = null,
    // schema v6 起：视频文件 absolute path（由 round 3 录制引擎写入）；null = 无视频
    // ②a 起为"最新段"双写镜像（消费侧应优先 video_segments 子表，见 VideoSegment）
    val videoFilePath: String? = null,
    // schema v6 起：录制首帧 wallClock（与 binary absoluteTsMs 同时钟域）；null = 无视频
    val videoStartedAtWallClock: Long? = null,
)

/**
 * 视频段领域模型（对应 Room VideoSegmentEntity，schema v9 / video-segment-schema ②a）。
 * 一个 session 一对多段：停录再录 / ERROR 救援重录 / ②b 按圈轮换分段。
 * endWallClock/durationMs null = ERROR 救援段时长未知（选段时 MUST 保守入选，见 VideoSegmentSelector）；
 * playable 三态：true=Finalize OK / null=未知（首播回写）/ false=首播失败已证损坏。
 *
 * @author CC
 * @description video segment domain model (one-to-many per session)
 * @date 2026-06-07
 */
data class VideoSegment(
    val id: Long,
    val sessionId: String,
    val segmentIndex: Int,
    val filePath: String,
    val startWallClock: Long,
    val endWallClock: Long? = null,
    val durationMs: Long? = null,
    val playable: Boolean? = null,
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
    // fix-lap-crossing-clock-hygiene round 加：接收侧真壁钟（与 binary samples absoluteTs 同源），
    // 供未来 per-lap segment readLapSamples 窗口截取使用。nullable —— 旧数据 row（v4→v5
    // migration 之前写入）该字段为 NULL，调用方 MUST 显式判 null fallback 到全 session 路径。
    // 与现有 crossingTimestampMs（GPS 协议时间）双时钟域共存，UI 显示仍用 protocol time。
    val crossingWallClockTimestampMs: Long? = null,
    val speedKmh: Double,
    val gateId: String,
    val gateType: String,
    val accepted: Boolean,
    val reason: String,
    val directionScore: Double?,
)