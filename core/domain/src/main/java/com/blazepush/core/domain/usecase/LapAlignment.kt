package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapTelemetrySample
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6378137.0
private const val DEG_TO_RAD = PI / 180.0

data class LapAlignmentResult(
    val samplesPerLap: List<List<LapTelemetrySample>>,
    val distanceStepMeters: Double,
    val refTotalDistMeters: Double,
    val gridSize: Int,
    val referenceLapIndex: Int,
) {
    init {
        require(gridSize == 0 || distanceStepMeters > 0.0) {
            "Non-EMPTY LapAlignmentResult requires distanceStepMeters > 0"
        }
        require(gridSize == 0 || samplesPerLap.isNotEmpty()) {
            "Non-EMPTY LapAlignmentResult requires non-empty samplesPerLap"
        }
    }

    fun gridIndexFor(distanceMeters: Double): Int = when {
        gridSize == 0 -> -1
        else -> (distanceMeters / distanceStepMeters).toInt().coerceIn(0, gridSize - 1)
    }

    fun distanceAtGridIndex(gridIndex: Int): Double = gridIndex * distanceStepMeters

    companion object {
        val EMPTY = LapAlignmentResult(
            samplesPerLap = emptyList(),
            distanceStepMeters = 0.0,
            refTotalDistMeters = 0.0,
            gridSize = 0,
            referenceLapIndex = -1,
        )
    }
}

object LapAlignment {

    fun alignByDistance(laps: List<LapTelemetry>, referenceLapIndex: Int, distanceStepMeters: Double = 5.0): LapAlignmentResult {
        if (laps.isEmpty()) return LapAlignmentResult.EMPTY
        if (referenceLapIndex !in laps.indices) return LapAlignmentResult.EMPTY
        if (distanceStepMeters <= 0.0) return LapAlignmentResult.EMPTY

        val refLap = laps[referenceLapIndex]
        if (refLap.samples.size < 2) return LapAlignmentResult.EMPTY

        val cosLat0 = cos(refLap.samples[0].lat * DEG_TO_RAD)
        val refCumulative = computeCumulativeDistances(refLap.samples, cosLat0)
        val refTotalDist = refCumulative[refCumulative.size - 1]
        if (refTotalDist == 0.0) return LapAlignmentResult.EMPTY

        val gridSize = (refTotalDist / distanceStepMeters).toInt() + 1
        val grid = DoubleArray(gridSize) { it * distanceStepMeters }

        val refResampled = resampleByGrid(
            refLap.samples, refCumulative, grid, refLap.lapStartWallClock, null
        )

        val samplesPerLap = ArrayList<List<LapTelemetrySample>>(laps.size)
        for (i in laps.indices) {
            val lap = laps[i]
            samplesPerLap.add(
                when {
                    i == referenceLapIndex -> refResampled
                    lap.samples.size == 1 -> List(gridSize) { lap.samples[0] }
                    lap.samples.isEmpty() -> resampleByGridFallback(
                        refResampled, lap.lapStartWallClock
                    )
                    else -> {
                        val cumulative = computeCumulativeDistances(lap.samples, cosLat0)
                        resampleByGrid(
                            lap.samples, cumulative, grid, lap.lapStartWallClock, null
                        )
                    }
                }
            )
        }

        return LapAlignmentResult(
            samplesPerLap = samplesPerLap,
            distanceStepMeters = distanceStepMeters,
            refTotalDistMeters = refTotalDist,
            gridSize = gridSize,
            referenceLapIndex = referenceLapIndex,
        )
    }

    private fun computeCumulativeDistances(
        samples: List<LapTelemetrySample>,
        cosLat0: Double,
    ): DoubleArray {
        val result = DoubleArray(samples.size)
        result[0] = 0.0
        for (i in 1 until samples.size) {
            val s0 = samples[i - 1]
            val s1 = samples[i]
            val dx = (s1.lon - s0.lon) * cosLat0 * DEG_TO_RAD * EARTH_RADIUS_M
            val dy = (s1.lat - s0.lat) * DEG_TO_RAD * EARTH_RADIUS_M
            result[i] = result[i - 1] + sqrt(dx * dx + dy * dy)
        }
        return result
    }

    private fun resampleByGrid(
        samples: List<LapTelemetrySample>,
        cumulative: DoubleArray,
        grid: DoubleArray,
        lapStartWallClock: Long,
        @Suppress("UNUSED_PARAMETER") fallbackRefSamples: List<LapTelemetrySample>?,
    ): List<LapTelemetrySample> {
        val result = ArrayList<LapTelemetrySample>(grid.size)
        for (d in grid) {
            result.add(findSampleAtDistance(samples, cumulative, d, lapStartWallClock))
        }
        return result
    }

    private fun resampleByGridFallback(
        refResampled: List<LapTelemetrySample>,
        lapStartWallClock: Long,
    ): List<LapTelemetrySample> {
        return refResampled.map { refSample ->
            LapTelemetrySample(
                absoluteTsMs = lapStartWallClock + refSample.elapsedMsInLap,
                elapsedMsInLap = refSample.elapsedMsInLap,
                lat = refSample.lat,
                lon = refSample.lon,
                speedKmh = refSample.speedKmh,
                bearingDeg = refSample.bearingDeg,
                accelerationG = null,
            )
        }
    }

    private fun findSampleAtDistance(
        samples: List<LapTelemetrySample>,
        cumulative: DoubleArray,
        targetDist: Double,
        lapStartWallClock: Long,
    ): LapTelemetrySample {
        val n = cumulative.size

        // Clamp to boundaries
        if (targetDist <= cumulative[0]) return samples[0]
        if (targetDist >= cumulative[n - 1]) return samples[n - 1]

        // Binary search for interval
        val idx = Arrays.binarySearch(cumulative, targetDist)
        val k = if (idx >= 0) {
            // Exact match — sweep left to find smallest index in duplicate range
            var kMin = idx
            while (kMin > 0 && cumulative[kMin - 1] == cumulative[idx]) kMin--
            return samples[kMin]
        } else {
            // Insertion point: -(insertionPoint + 1)
            val insertionPoint = -(idx + 1)
            insertionPoint - 1
        }

        // k is the left endpoint of the interval [k, k+1]
        val dK = cumulative[k]
        val dK1 = cumulative[k + 1]
        val alpha = if (dK == dK1) 0.0 else (targetDist - dK) / (dK1 - dK)

        return interpolate(samples[k], samples[k + 1], alpha, lapStartWallClock)
    }

    private fun interpolate(
        s0: LapTelemetrySample,
        s1: LapTelemetrySample,
        alpha: Double,
        lapStartWallClock: Long,
    ): LapTelemetrySample {
        val speedKmh = s0.speedKmh * (1 - alpha) + s1.speedKmh * alpha
        val lat = s0.lat * (1 - alpha) + s1.lat * alpha
        val lon = s0.lon * (1 - alpha) + s1.lon * alpha
        val elapsedMsInLap = round(s0.elapsedMsInLap * (1 - alpha) + s1.elapsedMsInLap * alpha).toLong()
        val bearingDeg = if (alpha < 0.5) s0.bearingDeg else s1.bearingDeg

        val accelerationG = interpolateNullable(s0.accelerationG, s1.accelerationG, alpha)

        return LapTelemetrySample(
            absoluteTsMs = lapStartWallClock + elapsedMsInLap,
            elapsedMsInLap = elapsedMsInLap,
            lat = lat,
            lon = lon,
            speedKmh = speedKmh,
            bearingDeg = bearingDeg,
            accelerationG = accelerationG,
        )
    }

    private fun interpolateNullable(
        v0: Double?,
        v1: Double?,
        alpha: Double,
    ): Double? = when {
        v0 != null && v1 != null -> v0 * (1 - alpha) + v1 * alpha
        alpha < 0.5 -> v0 ?: v1
        else -> v1 ?: v0
    }
}
