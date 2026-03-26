package com.blazepush.feature.test.model.laptiming

data class LapRecord(
    val recordId: String,
    val sessionId: String,
    val trackId: String,
    val lapIndex: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val durationMillis: Long,
    val sectorTimes: List<Long> = emptyList(),
    val trajectory: List<GpsSample> = emptyList(),
    val crossingEvents: List<CrossingEvent> = emptyList(),
    val qualityFlags: List<LapQualityFlag> = emptyList()
)
