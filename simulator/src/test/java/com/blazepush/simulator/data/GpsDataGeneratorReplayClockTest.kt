package com.blazepush.simulator.data

import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        // Spec 要求：`yearOffset` 固定为 0（虚拟 2000-01-01 起点，不反映真实日历）。
        // 期望值只含 (month-1) * 744 + (day-1) * 24 + hour。
        val calendar = Calendar.getInstance().apply {
            timeInMillis = sample.timestampMillis
        }
        val expected =
            calendar.get(Calendar.MONTH) * 744 +
                (calendar.get(Calendar.DAY_OF_MONTH) - 1) * 24 +
                calendar.get(Calendar.HOUR_OF_DAY)

        assertEquals(expected, encodedDateAndHour)
    }

    /**
     * Spec 锁定：`reset()` 后 `replayTimestampMillis` 被清零，replay 模式下
     * 再次 generate 前必须重新 [GpsDataGenerator.applyReplaySample]，否则立即抛
     * [IllegalStateException]（不得 fallback 到任何本地时钟）。
     */
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

        try {
            generator.generateGpsTimeData()
            org.junit.Assert.fail("Expected IllegalStateException after reset clears replayTimestampMillis")
        } catch (e: IllegalStateException) {
            assertTrue(
                "exception message should contain 'Replay sample missing timestamp'",
                e.message?.contains("Replay sample missing timestamp") == true
            )
        }
    }
}
