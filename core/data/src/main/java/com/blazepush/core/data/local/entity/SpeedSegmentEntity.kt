package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "speed_segments",
    foreignKeys = [ForeignKey(
        entity = TestRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["testRecordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("testRecordId")]
)
data class SpeedSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val testRecordId: String,
    val startSpeed: Int,
    val endSpeed: Int,
    val time: Double,
    val distance: Double
)
