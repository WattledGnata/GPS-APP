package com.blazepush.feature.test.model.track

data class TimingGate(
    val id: String,
    val name: String,
    val type: TimingGateType,
    val line: GeoLine,
    val passDirection: GeoVector,
    val sequenceIndex: Int,
    val minDirectionalSpeedMps: Double? = null
)
