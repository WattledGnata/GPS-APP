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
        val previousSide = signedSide(previous.latitude, previous.longitude, gate)
        val currentSide = signedSide(current.latitude, current.longitude, gate)
        val crossedGateLine =
            previousSide == 0.0 ||
                currentSide == 0.0 ||
                (previousSide < 0.0 && currentSide > 0.0) ||
                (previousSide > 0.0 && currentSide < 0.0)

        if (!crossedGateLine) {
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

    private fun signedSide(latitude: Double, longitude: Double, gate: TimingGate): Double {
        val start = gate.line.start
        val end = gate.line.end
        val gateVectorX = end.latitude - start.latitude
        val gateVectorY = end.longitude - start.longitude
        val pointVectorX = latitude - start.latitude
        val pointVectorY = longitude - start.longitude
        return (gateVectorX * pointVectorY) - (gateVectorY * pointVectorX)
    }
}
