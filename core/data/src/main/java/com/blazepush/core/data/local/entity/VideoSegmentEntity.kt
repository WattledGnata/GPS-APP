// @IgnoreFormatCheck
package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 视频段 Room Entity（video-segment-schema round ②a，schema v9 引入）。
 * 一个 session 一对多视频段：停录再录 / ERROR 救援重录 / ②b 按圈轮换分段都 append 不覆盖。
 * 统一两份 deferred memo（multi-video-per-session + video-segmentation-data-model）的字段超集。
 *
 * 与 session 的旧字段 videoFilePath/videoStartedAtWallClock 双写并存（②a 向后兼容期），
 * ②c 消费方切到本表后废弃旧字段写入。
 *
 * @author CC
 * @description one-to-many video segment entity (schema v9)
 * @date 2026-06-07
 */
@Entity(
    tableName = "video_segments",
    foreignKeys = [
        ForeignKey(
            entity = TelemetrySessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class VideoSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    // 段序（0 基，录制顺序；append 时取该 session 现有 max+1）
    val segmentIndex: Int,
    val filePath: String,
    // 本段首帧 wallClock（VideoRecordEvent.Start 取 System.currentTimeMillis，与遥测 absoluteTsMs 同时钟域）
    val startWallClock: Long,
    // 正常 Finalize = startWallClock + durationMs；ERROR 救援段时长未知 → null
    val endWallClock: Long? = null,
    val durationMs: Long? = null,
    // ②b 按圈轮换分段时填；本 round 恒 null
    val startLapIndex: Int? = null,
    val endLapIndex: Int? = null,
    // true = Finalize OK；null = ERROR 救援未知（②c 首播回写）；不用 false 哨兵
    val playable: Boolean? = null,
)
