package com.blazepush.feature.test.model.laptiming

data class LapSession(
    val sessionId: String,
    val trackId: String,
    val status: LapSessionStatus,
    val startedAtMillis: Long? = null,
    val samples: List<GpsSample> = emptyList(),
    val currentLapIndex: Int = 0,
    val nextExpectedGateIndex: Int = 0,
    val crossingEvents: List<CrossingEvent> = emptyList(),
    val completedLaps: List<LapRecord> = emptyList(),
    val activeLap: ActiveLap? = null
)
