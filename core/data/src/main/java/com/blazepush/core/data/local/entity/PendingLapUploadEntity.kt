// @IgnoreFormatCheck
package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * livetiming 上报失败的待传圈（schema v7，livetiming-lap-upload round）。
 *
 * 这是**持久化 Entity**，独立于 domain `LapTelemetry`（in-memory）与网络 `LapUploadDto`——
 * 三类不混并（盲点 #5）。`clientLapId` 作主键 = 天然唯一约束：同圈失败重复入队被 PK 挡
 * （OnConflict.IGNORE 保留首条 + retryCount，spec R3 反例锁）。
 *
 * `clientLapId` 入队时生成一次、持久化、flush 重试复用同一个（命门 Decision 2）。
 * `sectorsMsCsv` 把 List<Long> 逗号拼接持久化（null = 无分段）；retryCount 默认 0（非哨兵）。
 */
@Entity(tableName = "pending_lap_uploads")
data class PendingLapUploadEntity(
    @PrimaryKey val clientLapId: String,
    val trackId: String,
    val driver: String,
    val lapNo: Int,
    val lapTimeMs: Long,
    val sectorsMsCsv: String? = null,
    val lappedAtRfc3339: String? = null,
    val createdAtMs: Long,
    val retryCount: Int = 0,
    /** Null on v9 rows: maps to Reviewed + LegacyUnknown and is not sent automatically. */
    val quality: String? = null,
    val qualityFlagsCsv: String? = null,
    val evidenceVersion: Int? = null,
)
