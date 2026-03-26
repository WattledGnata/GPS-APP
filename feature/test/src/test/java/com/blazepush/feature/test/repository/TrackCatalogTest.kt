package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCatalogTest {

    @Test
    fun presetCatalog_containsDemoCircuitWithExpectedMinimalContracts() {
        val catalog = PresetTrackCatalog()

        val demo = requireNotNull(catalog.getTrack("preset-demo-circuit"))

        assertEquals("preset-demo-circuit", demo.id)
        assertEquals("Demo Circuit", demo.name)
        assertEquals("Forward", demo.layoutName)

        // Reference path contract
        assertTrue(demo.referencePath.closed)
        assertTrue(demo.referencePath.points.size >= 2)

        // Start/finish gate contract
        assertEquals(TimingGateType.StartFinish, demo.startFinishGate.type)
        assertEquals(0, demo.startFinishGate.sequenceIndex)
        assertEquals(2.0, demo.startFinishGate.minDirectionalSpeedMps ?: -1.0, 1e-9)

        // Sector gates contract (phase 1 demo has at least one sector)
        assertFalse(demo.sectorGates.isEmpty())
        assertTrue(demo.sectorGates.all { it.type == TimingGateType.Sector })
        assertTrue(demo.sectorGates.all { it.sequenceIndex > demo.startFinishGate.sequenceIndex })
    }

    @Test
    fun getAllTracks_includesDemoCircuit() {
        val catalog = PresetTrackCatalog()

        val ids = catalog.getAllTracks().map { it.id }

        assertTrue(ids.contains("preset-demo-circuit"))
    }

    @Test
    fun getTrack_returnsNullForUnknownTrackId() {
        val catalog = PresetTrackCatalog()

        val track = catalog.getTrack("missing-track")

        assertNull(track)
    }
}
