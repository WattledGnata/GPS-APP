package com.blazepush.feature.test.repository

import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定天津 V1 4.29 km 完整布局的身份、拟合起终点与自有 GPS referencePath。 */
class V1AutoworldPresetTrackTest {

    @Test
    fun getTrack_locksV1AutoworldFullPresetAndFittedStartFinish() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-v1-autoworld-full"))

        assertEquals("天津V1国际赛车场", track.name.zh)
        assertEquals("V1 Autoworld Circuit", track.name.en)
        assertEquals("V1", track.name.abbr)
        assertEquals(4.290, track.lengthKm, 1e-9)
        assertEquals(TrackSource.Preset, track.source)
        assertNull(track.thumbnailAssetPath)
        assertEquals(R.drawable.track_preview_v1_autoworld, track.thumbnailDrawableResId)

        assertEquals(143, track.referencePath.points.size)
        assertEquals(track.referencePath.points.first(), track.referencePath.points.last())
        assertTrue(track.referencePath.points.all { it.latitude in 39.37..39.40 })
        assertTrue(track.referencePath.points.all { it.longitude in 116.98..117.00 })

        val startFinish = track.startFinishGate
        assertEquals(TimingGateType.StartFinish, startFinish.type)
        assertEquals(0, startFinish.sequenceIndex)
        assertEquals(39.38262471396142, startFinish.line.start.latitude, 1e-12)
        assertEquals(116.99312745213942, startFinish.line.start.longitude, 1e-12)
        assertEquals(39.38329189061571, startFinish.line.end.latitude, 1e-12)
        assertEquals(116.99324876518770, startFinish.line.end.longitude, 1e-12)
        assertEquals(0.00003618500624961702, startFinish.passDirection.x, 1e-15)
        assertEquals(-0.00033311199329986985, startFinish.passDirection.y, 1e-15)

        val centerLatitude = (startFinish.line.start.latitude + startFinish.line.end.latitude) / 2.0
        val centerLongitude = (startFinish.line.start.longitude + startFinish.line.end.longitude) / 2.0
        assertEquals(39.382958302288564, centerLatitude, 1e-12)
        assertEquals(116.99318810866356, centerLongitude, 1e-12)

        assertTrue(track.sectorGates.isEmpty())
        assertTrue(track.orderedSectorGates.isEmpty())
    }
}
