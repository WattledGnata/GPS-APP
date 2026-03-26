package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCrossingDetectorTest {

    private val detector = GateCrossingDetector()

    @Test
    fun crossingInPositiveDirection_isAccepted() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.1, longitude = 0.5),
            current = sample(timestampMillis = 1_100L, latitude = 0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
        assertNotNull(detection.directionalSpeedMps)
        assertNotNull(detection.directionScore)
        assertTrue(detection.directionalSpeedMps!! >= 1.0)
        assertTrue(detection.directionScore!! > 0.0)
    }

    @Test
    fun crossingInReverseDirection_isRejected() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = 0.1, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(false, detection.accepted)
        assertEquals(CrossingReason.WrongDirection, detection.reason)
        assertNotNull(detection.directionScore)
        assertTrue(detection.directionScore!! <= 0.0)
    }

    @Test
    fun movementWithoutIntersection_isRejected() {
        val detection = detector.detect(
            previous = sample(timestampMillis = 1_000L, latitude = -0.3, longitude = 0.5),
            current = sample(timestampMillis = 2_000L, latitude = -0.1, longitude = 0.5),
            gate = gate()
        )

        assertEquals(false, detection.accepted)
        assertEquals(CrossingReason.NoIntersection, detection.reason)
        assertEquals(null, detection.directionalSpeedMps)
        assertEquals(null, detection.directionScore)
    }

    private fun gate(): TimingGate = TimingGate(
        id = "start-finish",
        name = "Start/Finish",
        type = TimingGateType.StartFinish,
        line = GeoLine(
            start = GeoPoint(latitude = 0.0, longitude = 0.0),
            end = GeoPoint(latitude = 0.0, longitude = 1.0)
        ),
        passDirection = GeoVector(x = 1.0, y = 0.0),
        sequenceIndex = 0,
        minDirectionalSpeedMps = 1.0
    )

    private fun sample(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double
    ): GpsSample = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude,
        speedKmh = 10.0
    )
}
