package com.blazepush.simulator.data

import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar

class GpsDataGeneratorReplayClockTest {

    @Test
    fun `replay sample drives main packet time bits`() {
        val generator = GpsDataGenerator(scenario = TestScenario.REAL_TRACK_REPLAY)
        val sample = ReplaySample(
            timestampMillis = 1773478969360L,
            latitude = 30.49697,
            longitude = 104.43317,
            speedKmh = 89.0712,
            bearingDegrees = 182.31,
            satellites = 11,
            fixType = 1,
            hdop = 0.8,
            altitudeMeters = 444.2,
            altitudePrecisionMeters = 0.0
        )

        generator.applyReplaySample(sample)
        val data = generator.generateGpsMainData()
        val encodedHalfMillis =
            ((data[0].toInt() and 0x1F) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)

        val expectedHalfMillis = ((sample.timestampMillis % 3_600_000L).toInt()) / 2
        assertEquals(expectedHalfMillis, encodedHalfMillis)
    }

    @Test
    fun `replay sample drives gps time packet date and hour`() {
        val generator = GpsDataGenerator(scenario = TestScenario.REAL_TRACK_REPLAY)
        val sample = ReplaySample(
            timestampMillis = 1773478969360L,
            latitude = 30.49697,
            longitude = 104.43317,
            speedKmh = 89.0712,
            bearingDegrees = 182.31,
            satellites = 11,
            fixType = 1,
            hdop = 0.8,
            altitudeMeters = 444.2,
            altitudePrecisionMeters = 0.0
        )

        generator.applyReplaySample(sample)
        val data = generator.generateGpsTimeData()
        val encodedDateAndHour =
            ((data[0].toInt() and 0x1F) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)

        val calendar = Calendar.getInstance().apply {
            timeInMillis = sample.timestampMillis
        }
        val expected =
            (calendar.get(Calendar.YEAR) - 2000) * 8928 +
                calendar.get(Calendar.MONTH) * 744 +
                (calendar.get(Calendar.DAY_OF_MONTH) - 1) * 24 +
                calendar.get(Calendar.HOUR_OF_DAY)

        assertEquals(expected, encodedDateAndHour)
    }

    @Test
    fun `reset clears replay timestamp clock source`() {
        val generator = GpsDataGenerator(scenario = TestScenario.REAL_TRACK_REPLAY)
        val sample = ReplaySample(
            timestampMillis = 1773478969360L,
            latitude = 30.49697,
            longitude = 104.43317,
            speedKmh = 89.0712,
            bearingDegrees = 182.31,
            satellites = 11,
            fixType = 1,
            hdop = 0.8,
            altitudeMeters = 444.2,
            altitudePrecisionMeters = 0.0
        )

        generator.applyReplaySample(sample)
        generator.reset()
        val data = generator.generateGpsTimeData()
        val encodedDateAndHour =
            ((data[0].toInt() and 0x1F) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)

        val replayCalendar = Calendar.getInstance().apply {
            timeInMillis = sample.timestampMillis
        }
        val replayDateAndHour =
            (replayCalendar.get(Calendar.YEAR) - 2000) * 8928 +
                replayCalendar.get(Calendar.MONTH) * 744 +
                (replayCalendar.get(Calendar.DAY_OF_MONTH) - 1) * 24 +
                replayCalendar.get(Calendar.HOUR_OF_DAY)

        assertNotEquals(replayDateAndHour, encodedDateAndHour)
    }
}
