package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackCatalogTest {

    @Test
    fun getAllTracks_exposesOnlyTficLpccPreset() {
        val catalog = PresetTrackCatalog()

        val ids = catalog.getAllTracks().map { it.id }

        assertEquals(listOf("preset-tfic-lpcc"), ids)
    }

    @Test
    fun getTrack_locksTficLpccCoordinateContractWithReplayAlignedPresetConstants() {
        val catalog = PresetTrackCatalog()

        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))
        val representativePoint = track.referencePath.points.first()
        val startFinishLine = track.startFinishGate.line
        val sector1Line = track.sectorGates[0].line
        val sector2Line = track.sectorGates[1].line

        assertEquals("TFIC LPCC", track.name)
        assertEquals(TimingGateType.StartFinish, track.startFinishGate.type)
        assertEquals("起点", track.startFinishGate.name)
        assertEquals(listOf("s1", "s2"), track.sectorGates.map { it.name })
        assertEquals(listOf(1, 2), track.sectorGates.map { it.sequenceIndex })
        assertEquals(
            listOf(TimingGateType.Sector, TimingGateType.Sector),
            track.sectorGates.map { it.type }
        )
        assertFalse(track.referencePath.points.isEmpty())
        assertTrue(representativePoint.latitude in 30.0..31.0)
        assertTrue(representativePoint.longitude in 104.0..105.0)
        assertTrue(startFinishLine.start.latitude in 30.0..31.0)
        assertTrue(startFinishLine.start.longitude in 104.0..105.0)
        assertTrue(startFinishLine.end.latitude in 30.0..31.0)
        assertTrue(startFinishLine.end.longitude in 104.0..105.0)

        // Lock the full preset-tfic-lpcc referencePath contract to prevent accidental preset drift.
        assertEquals(13, track.referencePath.points.size)
        assertEquals(30.4945735, track.referencePath.points[0].latitude, 1e-6)
        assertEquals(104.4332358, track.referencePath.points[0].longitude, 1e-6)
        assertEquals(30.4927678, track.referencePath.points[1].latitude, 1e-6)
        assertEquals(104.4332757, track.referencePath.points[1].longitude, 1e-6)
        assertEquals(30.4914073, track.referencePath.points[2].latitude, 1e-6)
        assertEquals(104.4346407, track.referencePath.points[2].longitude, 1e-6)
        assertEquals(30.4903109, track.referencePath.points[3].latitude, 1e-6)
        assertEquals(104.4329748, track.referencePath.points[3].longitude, 1e-6)
        assertEquals(30.4905453, track.referencePath.points[4].latitude, 1e-6)
        assertEquals(104.4350638, track.referencePath.points[4].longitude, 1e-6)
        assertEquals(30.4920068, track.referencePath.points[5].latitude, 1e-6)
        assertEquals(104.4362783, track.referencePath.points[5].longitude, 1e-6)
        assertEquals(30.4937000, track.referencePath.points[6].latitude, 1e-6)
        assertEquals(104.4369812, track.referencePath.points[6].longitude, 1e-6)
        assertEquals(30.4955157, track.referencePath.points[7].latitude, 1e-6)
        assertEquals(104.4371740, track.referencePath.points[7].longitude, 1e-6)
        assertEquals(30.4969511, track.referencePath.points[8].latitude, 1e-6)
        assertEquals(104.4359008, track.referencePath.points[8].longitude, 1e-6)
        assertEquals(30.4977035, track.referencePath.points[9].latitude, 1e-6)
        assertEquals(104.4339855, track.referencePath.points[9].longitude, 1e-6)
        assertEquals(30.4978068, track.referencePath.points[10].latitude, 1e-6)
        assertEquals(104.4318679, track.referencePath.points[10].longitude, 1e-6)
        assertEquals(30.4963642, track.referencePath.points[11].latitude, 1e-6)
        assertEquals(104.4331724, track.referencePath.points[11].longitude, 1e-6)
        assertEquals(30.4945735, track.referencePath.points[12].latitude, 1e-6)
        assertEquals(104.4332358, track.referencePath.points[12].longitude, 1e-6)

        assertEquals(30.4961790, startFinishLine.start.latitude, 1e-6)
        assertEquals(104.43329413333335, startFinishLine.start.longitude, 1e-6)
        assertEquals(30.49662805, startFinishLine.end.latitude, 1e-6)
        assertEquals(104.43326681666667, startFinishLine.end.longitude, 1e-6)

        assertEquals(30.489821166666662, sector1Line.start.latitude, 1e-6)
        assertEquals(104.43391746666666, sector1Line.start.longitude, 1e-6)
        assertEquals(30.489774166666667, sector1Line.end.latitude, 1e-6)
        assertEquals(104.43443643333333, sector1Line.end.longitude, 1e-6)

        assertEquals(30.48948356944875, sector2Line.start.latitude, 1e-6)
        assertEquals(104.43221728363045, sector2Line.start.longitude, 1e-6)
        assertEquals(30.48992993055125, sector2Line.end.latitude, 1e-6)
        assertEquals(104.43227191636953, sector2Line.end.longitude, 1e-6)
    }

    @Test
    fun getTrack_returnsNullForUnknownTrackId() {
        val catalog = PresetTrackCatalog()

        val track = catalog.getTrack("missing-track")

        assertNull(track)
    }
}
