package com.blazepush.core.domain.model

/**
 * Single telemetry sample within a lap.
 *
 * `LapTelemetry` / `PerformanceTelemetry` container classes will be appended by W1 round.
 * This file is landed early by W2 to unlock mock-driven development of chart components.
 */
data class LapTelemetrySample(
    val absoluteTsMs: Long,
    val elapsedMsInLap: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val accelerationG: Double?,
)
