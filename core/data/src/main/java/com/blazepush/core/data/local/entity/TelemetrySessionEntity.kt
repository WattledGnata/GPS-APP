package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Telemetry session metadata Room Entity（A56 引入）。
 * sessionType 存 enum 字符串，binaryFilePath 指向 17-byte/sample 二进制 chunk file。
 *
 * @author CC
 * @description telemetry session metadata Room entity
 * @date 2026-04-30
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
)