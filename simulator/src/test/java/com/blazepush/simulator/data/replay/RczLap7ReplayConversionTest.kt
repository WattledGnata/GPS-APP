package com.blazepush.simulator.data.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class RczLap7ReplayConversionTest {

    @Test
    fun `lap7 rcz maps to replay samples with corrected units`() {
        val path = locateLap7Rcz()
        assertNotNull("Missing lap7 RCZ fixture in simulator/src/test/resources/replay/", path)

        ZipFile(path!!.toFile()).use { zip ->
            val timestamps = zip.readLongs("channel_1_200_0_1_1")
            val coords = zip.readInts("channel_1_200_0_3_1")
            val speeds = zip.readInts("channel_1_200_0_4_0")
            val altitudes = zip.readInts("channel_1_200_0_5_0")
            val bearings = zip.readInts("channel_1_200_0_6_0")
            val satellites = zip.readInts("channel_1_200_0_30002_0")
            val fixTypes = zip.readInts("channel_1_200_0_30003_0")
            val hdops = zip.readInts("channel_1_200_0_30004_0")

            assertFalse(timestamps.isEmpty())
            assertEquals(timestamps.size * 2, coords.size)
            assertEquals(timestamps.size, speeds.size)
            assertEquals(timestamps.size, altitudes.size)
            assertEquals(timestamps.size, bearings.size)
            assertEquals(timestamps.size, satellites.size)
            assertEquals(timestamps.size, fixTypes.size)
            assertEquals(timestamps.size, hdops.size)

            val samples = timestamps.indices.map { index ->
                ReplaySample(
                    timestampMillis = timestamps[index],
                    latitude = coords[index * 2] / 6000000.0,
                    longitude = coords[index * 2 + 1] / 6000000.0,
                    speedKmh = speeds[index] / 1000.0 * 3.6,
                    bearingDegrees = bearings[index] / 1000.0,
                    satellites = satellites[index],
                    fixType = fixTypes[index],
                    hdop = hdops[index] / 1000.0,
                    altitudeMeters = altitudes[index] / 1000.0,
                    altitudePrecisionMeters = 0.0
                )
            }

            assertEquals(2820, samples.size)
            assertEquals(1773478969360L, samples.first().timestampMillis)
            assertEquals(1773479082120L, samples.last().timestampMillis)
            assertEquals(30.49697, samples.first().latitude, 0.0000001)
            assertEquals(104.43317, samples.first().longitude, 0.0000001)
            assertEquals(89.0712, samples.first().speedKmh, 0.0001)
            assertEquals(164.17, samples.first().bearingDegrees, 0.001)
            assertEquals(12, samples.first().satellites)
            assertEquals(1, samples.first().fixType)
            assertEquals(0.6, samples.first().hdop, 0.0001)
            assertEquals(424.5, samples.first().altitudeMeters, 0.0001)
            assertTrue(samples.maxOf { it.speedKmh } > 170.0)
            assertTrue(samples.minOf { it.speedKmh } > 50.0)
        }
    }

    private fun locateLap7Rcz(): Path? {
        val resource = javaClass.classLoader?.getResource("replay/session_20260314_170249_天府赛道_lap7.rcz")
            ?: return null
        val path = Path.of(resource.toURI())
        return path.takeIf(Files::exists)
    }

    private fun ZipFile.readInts(name: String): List<Int> =
        getInputStream(requireNotNull(getEntry(name)) { "Missing RCZ entry: $name" }).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / Int.SIZE_BYTES) { buffer.int }
        }

    private fun ZipFile.readLongs(name: String): List<Long> =
        getInputStream(requireNotNull(getEntry(name)) { "Missing RCZ entry: $name" }).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / Long.SIZE_BYTES) { buffer.long }
        }
}
