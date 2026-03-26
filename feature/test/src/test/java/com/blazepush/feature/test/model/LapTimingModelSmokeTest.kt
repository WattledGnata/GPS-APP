package com.blazepush.feature.test.model

import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath
import com.blazepush.feature.test.model.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LapTimingModelSmokeTest {

    @Test
    fun smoke_exposesMinimalTrackAndSessionContracts() {
        // View options contract (defaults)
        val options = LapViewOptions()
        assertTrue(options.showReferencePath)
        assertTrue(options.showTimingGates)
        assertTrue(options.showTrajectory)
        assertFalse(options.showCrossingDebug)

        // Run config contract
        val config = LapRunConfig(trackId = "preset-track")
        assertEquals("preset-track", config.trackId)
        assertEquals(options, config.viewOptions)

        // Track contract
        val startPoint = GeoPoint(latitude = 39.9042, longitude = 116.4074)
        val timingGate = TimingGate(
            id = "start-finish",
            name = "Start/Finish",
            type = TimingGateType.StartFinish,
            line = GeoLine(start = startPoint, end = startPoint),
            passDirection = GeoVector(x = 1.0, y = 0.0),
            sequenceIndex = 0
        )
        val track = Track(
            id = "preset-track",
            name = "Preset Track",
            referencePath = TrackPath(points = listOf(startPoint)),
            startFinishGate = timingGate
        )
        assertEquals(TrackSource.Preset, track.source)
        assertTrue(track.referencePath.closed)
        assertEquals(1, track.referencePath.points.size)
        assertTrue(track.sectorGates.isEmpty())

        // Session contract
        val session = LapSession(
            sessionId = "session-1",
            trackId = track.id,
            status = LapSessionStatus.Ready,
            startedAtMillis = 1234L
        )
        assertEquals(track.id, session.trackId)
        assertEquals("session-1", session.sessionId)
        assertEquals(LapSessionStatus.Ready, session.status)
        assertEquals(1234L, session.startedAtMillis)
        assertEquals(0, session.currentLapIndex)
        assertEquals(0, session.nextExpectedGateIndex)
        assertTrue(session.samples.isEmpty())
        assertTrue(session.crossingEvents.isEmpty())
        assertTrue(session.completedLaps.isEmpty())
        assertNull(session.activeLap)
    }
}
