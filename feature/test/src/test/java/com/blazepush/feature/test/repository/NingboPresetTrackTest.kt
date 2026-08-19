package com.blazepush.feature.test.repository

import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定宁波完整布局预置的身份、校准门点与自有 GPS referencePath。 */
class NingboPresetTrackTest {

    @Test
    fun getTrack_locksNingboFullPresetAndOfficialTimingGeometry() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-nic-full"))

        assertEquals("宁波国际赛道", track.name.zh)
        assertEquals("Ningbo International Circuit", track.name.en)
        assertEquals("NIC", track.name.abbr)
        assertEquals(4.010, track.lengthKm, 1e-9)
        assertEquals(TrackSource.Preset, track.source)
        assertNull(track.thumbnailAssetPath)
        assertEquals(R.drawable.track_preview_ningbo, track.thumbnailDrawableResId)

        assertEquals(132, track.referencePath.points.size)
        assertEquals(track.referencePath.points.first(), track.referencePath.points.last())
        assertTrue(track.referencePath.points.all { it.latitude in 29.75..29.77 })
        assertTrue(track.referencePath.points.all { it.longitude in 121.86..121.88 })

        val startFinish = track.startFinishGate
        assertEquals(TimingGateType.StartFinish, startFinish.type)
        assertEquals(0, startFinish.sequenceIndex)
        assertEquals(29.762591563248883, startFinish.line.start.latitude, 1e-12)
        assertEquals(121.86376365210256, startFinish.line.start.longitude, 1e-12)
        assertEquals(29.762521059719795, startFinish.line.end.latitude, 1e-12)
        assertEquals(121.86453637870201, startFinish.line.end.longitude, 1e-12)
        assertEquals(-0.0006707962710479513, startFinish.passDirection.x, 1e-15)
        assertEquals(-0.00008121683830545618, startFinish.passDirection.y, 1e-15)

        val centerLatitude = (startFinish.line.start.latitude + startFinish.line.end.latitude) / 2.0
        val centerLongitude = (startFinish.line.start.longitude + startFinish.line.end.longitude) / 2.0
        assertEquals(29.76255631148434, centerLatitude, 1e-12)
        assertEquals(121.86415001540229, centerLongitude, 1e-12)

        assertEquals(listOf("s1", "s2"), track.orderedSectorGates.map { it.id })
        assertEquals(listOf(1, 2), track.orderedSectorGates.map { it.sequenceIndex })

        val s1 = track.orderedSectorGates[0]
        assertEquals(29.761411437942016, s1.line.start.latitude, 1e-12)
        assertEquals(121.86779418310418, s1.line.start.longitude, 1e-12)
        assertEquals(29.761111659974652, s1.line.end.latitude, 1e-12)
        assertEquals(121.86818147939583, s1.line.end.longitude, 1e-12)
        assertEquals(0.0003362124010139615, s1.passDirection.x, 1e-15)
        assertEquals(0.0003453260341463347, s1.passDirection.y, 1e-15)

        val s2 = track.orderedSectorGates[1]
        assertEquals(29.762671248169372, s2.line.start.latitude, 1e-12)
        assertEquals(121.86941885144977, s2.line.start.longitude, 1e-12)
        assertEquals(29.76295444766396, s2.line.end.latitude, 1e-12)
        assertEquals(121.8698223714669, s2.line.end.longitude, 1e-12)
        assertEquals(0.00035029081428956217, s2.passDirection.x, 1e-15)
        assertEquals(-0.00032623368996926303, s2.passDirection.y, 1e-15)
    }
}
