package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Telemetry session metadata Room Entity（A56 引入；persist-session-summary-fields round 加 3 字段升至 schema v4）。
 * sessionType 存 enum 字符串，binaryFilePath 指向 17-byte/sample 二进制 chunk file。
 *
 * @author CC
 * @description telemetry session metadata Room entity
 * @date 2026-05-01
 */
@Entity(tableName = "telemetry_sessions")
data class TelemetrySessionEntity(
    @PrimaryKey val sessionId: String,
    val sessionType: String,
    val startTs: Long,
    val endTs: Long,
    val binaryFilePath: String,
    val lapCount: Int = 0,
    val bestLapMs: Long? = null,
    // schema v4 起：endSession 时扫 binary 算出后写入；历史 v3 row migration 后为 null
    val topSpeedKmh: Double? = null,
    // schema v4 起：startSession 时记录 session 启动时刻的 trackId（圈速场景）；历史 v3 row migration 后为 null
    val trackId: String? = null,
    // schema v4 起：startSession 时持久化 track display name 快照，detail 屏 D5 fallback 优先级 1
    // （多赛道扩展后 catalog 删除赛道时仍能正确显示当时赛道名，不 fallback currentSelectedTrack）
    val trackNameSnapshot: String? = null,
    // schema v6 起：视频文件 absolute path（round 3 camera-recording-and-gps-sync 录制结束后写入）
    // null = 无视频；不用空字符串哨兵，nullable 语义清晰
    val videoFilePath: String? = null,
    // schema v6 起：录制首帧回调时刻 System.currentTimeMillis()，与 binary absoluteTsMs 同时钟域
    // 供视频帧↔遥测对齐；null = 未开始录制或无视频
    val videoStartedAtWallClock: Long? = null,
)