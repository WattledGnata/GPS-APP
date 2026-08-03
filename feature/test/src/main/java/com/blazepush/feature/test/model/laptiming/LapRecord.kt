package com.blazepush.feature.test.model.laptiming

import com.blazepush.core.domain.model.LapConfidencePolicy
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapQualityDecision

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
    val qualityFlags: List<LapQualityFlag> = emptyList(),
    /** Null only for legacy/reconstructed records that predate evidence schema v1. */
    val evidence: LapEvidence? = null,
) {
    val qualityDecision: LapQualityDecision get() = LapConfidencePolicy.evaluate(evidence)
}
