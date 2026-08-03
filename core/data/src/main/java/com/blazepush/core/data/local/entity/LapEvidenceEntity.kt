package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "lap_evidence",
    primaryKeys = ["sessionId", "lapIndex"],
    foreignKeys = [ForeignKey(
        entity = TelemetrySessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class LapEvidenceEntity(
    val sessionId: String,
    val lapIndex: Int,
    val evidenceVersion: Int,
    val startCrossingTimestampMillis: Long,
    val finishCrossingTimestampMillis: Long,
    val requiredGateIdsCsv: String,
    val acceptedGateIdsCsv: String,
    val gapIntervalsJson: String,
    val qualityFlagsCsv: String,
    val reviewProvenance: String,
)
