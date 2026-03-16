package com.race.gps.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "acceleration_data_points",
    foreignKeys = [ForeignKey(
        entity = TestRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["testRecordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("testRecordId")]
)
data class AccelerationDataPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val testRecordId: String,
    val time: Double,
    val speed: Double
)
