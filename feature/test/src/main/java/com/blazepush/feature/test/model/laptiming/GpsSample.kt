package com.blazepush.feature.test.model.laptiming

data class GpsSample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val bearingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Double? = null
)
