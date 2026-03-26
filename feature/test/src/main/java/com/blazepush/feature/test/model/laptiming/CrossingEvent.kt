package com.blazepush.feature.test.model.laptiming

import com.blazepush.feature.test.model.track.TimingGateType

data class CrossingEvent(
    val gateId: String,
    val gateType: TimingGateType,
    val timestampMillis: Long,
    val sampleIndex: Int,
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double? = null,
    val directionScore: Double? = null
)
