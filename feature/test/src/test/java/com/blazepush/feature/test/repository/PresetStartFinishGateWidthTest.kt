package com.blazepush.feature.test.repository

import kotlin.math.cos
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定 main 预置赛道起终点门的 120m 覆盖及原计时平面中心。 */
class PresetStartFinishGateWidthTest {

    @Test
    fun mainPresetStartFinishGates_are120MetersAndKeepCalibratedCenters() {
        val expectedCenters = mapOf(
            "preset-tfic-lpcc" to (30.495686418192925 to 104.43313317880124),
            "preset-xic-lpcc" to (24.65470166666665 to 118.31545700000001),
            "preset-nic-full" to (29.76255631148434 to 121.86415001540229),
            "preset-v1-autoworld-full" to (39.382958302288564 to 116.99318810866356),
        )
        val catalog = PresetTrackCatalog()

        expectedCenters.forEach { (trackId, expectedCenter) ->
            val gate = requireNotNull(catalog.getTrack(trackId)).startFinishGate
            val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
            val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0

            assertEquals("$trackId gate width", 120.0, gateWidthMeters(gate.line), 0.2)
            assertEquals("$trackId center latitude", expectedCenter.first, centerLatitude, 1e-12)
            assertEquals("$trackId center longitude", expectedCenter.second, centerLongitude, 1e-12)
        }
    }

    private fun gateWidthMeters(line: com.blazepush.feature.test.model.track.GeoLine): Double {
        val centerLatitude = (line.start.latitude + line.end.latitude) / 2.0
        val northMeters = (line.end.latitude - line.start.latitude) * METERS_PER_DEGREE
        val eastMeters = (line.end.longitude - line.start.longitude) *
            METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))
        return hypot(northMeters, eastMeters)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
