package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.TimingGate

data class GateCrossingDetection(
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double?,
    val directionScore: Double?
)

class GateCrossingDetector {

    fun detect(
        previous: GpsSample,
        current: GpsSample,
        gate: TimingGate
    ): GateCrossingDetection {
        val crossedGateSegment = segmentsIntersect(
            ax = previous.latitude,
            ay = previous.longitude,
            bx = current.latitude,
            by = current.longitude,
            cx = gate.line.start.latitude,
            cy = gate.line.start.longitude,
            dx = gate.line.end.latitude,
            dy = gate.line.end.longitude
        )

        if (!crossedGateSegment) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.NoIntersection,
                directionalSpeedMps = null,
                directionScore = null
            )
        }

        val movementX = current.latitude - previous.latitude
        val movementY = current.longitude - previous.longitude
        val directionScore =
            (movementX * gate.passDirection.x) + (movementY * gate.passDirection.y)

        if (directionScore <= 0.0) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.WrongDirection,
                directionalSpeedMps = null,
                directionScore = directionScore
            )
        }

        val dtSeconds = (current.timestampMillis - previous.timestampMillis) / 1000.0
        val directionalSpeedMps = if (dtSeconds > 0.0) directionScore / dtSeconds else Double.POSITIVE_INFINITY

        if (gate.minDirectionalSpeedMps != null && directionalSpeedMps < gate.minDirectionalSpeedMps) {
            return GateCrossingDetection(
                accepted = false,
                reason = CrossingReason.TooSlow,
                directionalSpeedMps = directionalSpeedMps,
                directionScore = directionScore
            )
        }

        return GateCrossingDetection(
            accepted = true,
            reason = CrossingReason.Accepted,
            directionalSpeedMps = directionalSpeedMps,
            directionScore = directionScore
        )
    }

    private fun segmentsIntersect(
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
        cx: Double,
        cy: Double,
        dx: Double,
        dy: Double
    ): Boolean {
        val abx = bx - ax
        val aby = by - ay
        val cdx = dx - cx
        val cdy = dy - cy
        val denominator = (abx * cdy) - (aby * cdx)
        if (denominator == 0.0) {
            return false
        }

        val acx = cx - ax
        val acy = cy - ay
        val t = ((acx * cdy) - (acy * cdx)) / denominator
        val u = ((acx * aby) - (acy * abx)) / denominator
        return t in 0.0..1.0 && u in 0.0..1.0
    }
}
