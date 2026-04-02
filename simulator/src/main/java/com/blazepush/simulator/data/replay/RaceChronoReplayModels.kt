package com.blazepush.simulator.data.replay

data class ReplaySample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val satellites: Int,
    val fixType: Int,
    val hdop: Double,
    val altitudeMeters: Double,
    val altitudePrecisionMeters: Double
)

data class ReplaySession(
    val sessionTitle: String,
    val samples: List<ReplaySample>
)

enum class RaceChronoGateType {
    StartFinish,
    Split
}

data class ReplayGateLine(
    val start: ReplayGeoPoint,
    val end: ReplayGeoPoint
)

data class ReplayGeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class ReplayGate(
    val type: RaceChronoGateType,
    val name: String,
    val line: ReplayGateLine
)
