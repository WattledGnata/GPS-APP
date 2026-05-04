package com.blazepush.feature.test.ui.components

import com.blazepush.core.domain.model.LapTelemetrySample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class FakeLapTelemetry(
    val samples: List<LapTelemetrySample>,
    val sectorBoundaries: List<Long>,
    val lapStartWallClock: Long,
    val lapEndWallClock: Long,
)

private const val BASE_WALL_CLOCK = 1_700_000_000_000L
private const val CENTER_LAT = 31.0
private const val CENTER_LON = 121.0
private const val RADIUS = 0.005

internal fun mockSingleLap(n: Int = 100, lapDurationMs: Long = 60_000): FakeLapTelemetry {
    val lapStart = BASE_WALL_CLOCK
    val lapEnd = lapStart + lapDurationMs
    val intervalMs = if (n > 1) lapDurationMs / (n - 1).toLong() else lapDurationMs

    val samples = (0 until n).map { i ->
        val t = i.toDouble() / (n - 1).coerceAtLeast(1)
        val elapsedMs = i * intervalMs
        val speedKmh = 100.0 + 50.0 * sin(2.0 * PI * t)
        val lat = CENTER_LAT + RADIUS * sin(2.0 * PI * t)
        val lon = CENTER_LON + RADIUS * cos(2.0 * PI * t)

        val accelerationG = if (i == 0 || i == n - 1) null else {
            val prevSpeed = 100.0 + 50.0 * sin(2.0 * PI * (i - 1.0) / (n - 1).coerceAtLeast(1))
            val nextSpeed = 100.0 + 50.0 * sin(2.0 * PI * (i + 1.0) / (n - 1).coerceAtLeast(1))
            val dtSec = (intervalMs * 2) / 1000.0
            val accelMs2 = (nextSpeed / 3.6 - prevSpeed / 3.6) / dtSec
            accelMs2 / 9.81
        }

        val bearingDeg = if (i < n - 1) {
            val nextLat = CENTER_LAT + RADIUS * sin(2.0 * PI * (i + 1.0) / (n - 1).coerceAtLeast(1))
            val nextLon = CENTER_LON + RADIUS * cos(2.0 * PI * (i + 1.0) / (n - 1).coerceAtLeast(1))
            Math.toDegrees(atan2(nextLon - lon, nextLat - lat)).let { if (it < 0) it + 360 else it }
        } else null

        LapTelemetrySample(
            absoluteTsMs = lapStart + elapsedMs,
            elapsedMsInLap = elapsedMs,
            lat = lat,
            lon = lon,
            speedKmh = speedKmh,
            bearingDeg = bearingDeg,
            accelerationG = accelerationG,
        )
    }

    val sectorDuration = lapDurationMs / 3
    val sectorBoundaries = listOf(lapStart, lapStart + sectorDuration, lapStart + sectorDuration * 2)

    return FakeLapTelemetry(
        samples = samples,
        sectorBoundaries = sectorBoundaries,
        lapStartWallClock = lapStart,
        lapEndWallClock = lapEnd,
    )
}

internal fun mockMultiLap(n: Int = 3): List<FakeLapTelemetry> {
    val durations = listOf(60_000L, 62_000L, 58_000L).take(n)
    var currentStart = BASE_WALL_CLOCK
    return durations.map { duration ->
        val lap = mockSingleLap(n = 100, lapDurationMs = duration).let {
            val offset = currentStart - it.lapStartWallClock
            FakeLapTelemetry(
                samples = it.samples.map { s ->
                    s.copy(absoluteTsMs = s.absoluteTsMs + offset)
                },
                sectorBoundaries = it.sectorBoundaries.map { b -> b + offset },
                lapStartWallClock = currentStart,
                lapEndWallClock = currentStart + duration,
            )
        }
        currentStart = lap.lapEndWallClock + 1000L
        lap
    }
}
