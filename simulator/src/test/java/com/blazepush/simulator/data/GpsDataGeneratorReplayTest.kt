package com.blazepush.simulator.data

import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDataGeneratorReplayTest {

    @Test
    fun `applyReplaySample updates encoded gps fields`() {
        val generator = GpsDataGenerator()
        val sample = ReplaySample(
            timestampMillis = 1773477689648L,
            latitude = 30.4945735,
            longitude = 104.4332358,
            speedKmh = 123.45,
            bearingDegrees = 189.3,
            satellites = 11,
            fixType = 1,
            hdop = 0.8,
            altitudeMeters = 444.2,
            altitudePrecisionMeters = 0.0
        )

        generator.applyReplaySample(sample)
        val data = generator.generateGpsMainData()

        val fixAndSat = data[3].toInt() and 0xFF
        val fixQuality = (fixAndSat shr 6) and 0x03
        val satellites = fixAndSat and 0x3F
        val latInt = ((data[4].toInt() and 0xFF) shl 24) or
            ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[7].toInt() and 0xFF)
        val lonInt = ((data[8].toInt() and 0xFF) shl 24) or
            ((data[9].toInt() and 0xFF) shl 16) or
            ((data[10].toInt() and 0xFF) shl 8) or
            (data[11].toInt() and 0xFF)
        val lat = latInt / 10000000.0
        val lon = lonInt / 10000000.0
        val speedEncoded = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
        val speed = if (speedEncoded and 0x8000 != 0) {
            (speedEncoded and 0x7FFF) / 10.0
        } else {
            (speedEncoded and 0x7FFF) / 100.0
        }
        val bearingEncoded = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        val bearing = bearingEncoded / 100.0
        val hdop = (data[18].toInt() and 0xFF) / 10.0

        assertEquals(1, fixQuality)
        assertEquals(11, satellites)
        assertEquals(30.4945735, lat, 0.0000001)
        assertEquals(104.4332358, lon, 0.0000001)
        assertEquals(123.45, speed, 0.02)
        assertEquals(189.3, bearing, 0.01)
        assertEquals(0.8, hdop, 0.1)
    }

}
