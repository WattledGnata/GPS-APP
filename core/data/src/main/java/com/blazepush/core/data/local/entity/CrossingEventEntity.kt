package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "crossing_events",
    foreignKeys = [ForeignKey(
        entity = TelemetrySessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
/**
 * 圈速过线事件 Room Entity（A56 引入）。
 * 与 TelemetrySession 1:N 关联，session 删除时级联清理；按 sessionId 建索引便于查询。
 *
 * @author CC
 * @description crossing event Room entity
 * @date 2026-04-30
 */
data class CrossingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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