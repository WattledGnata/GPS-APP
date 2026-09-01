package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceResultWindowReaderTest {
    @Test
    fun `旧 38 秒遥测只读识别最后约 8 秒 0到100窗口`() {
        val speeds = List(750) { 0.0 } + (0..200).map { it * 0.5 }
        val samples = speeds.mapIndexed { index, speed -> sample(index * 40L, speed) }

        val points = PerformanceTestTelemetryReader.toResultPoints(
            samples,
            TestTemplate.Acceleration0To100,
            window = null,
        )

        assertEquals(951, samples.size)
        assertEquals(0.0, points.first().elapsedTime, 1e-9)
        assertEquals(100.0, points.last().speed, 1e-9)
        assertTrue(points.last().elapsedTime in 7.8..8.1)
        assertEquals(100.0, points.maxOf { it.speed }, 1e-9)
    }

    @Test
    fun `持久化窗口按索引校验并按精确时间插值归零`() {
        val samples = listOf(
            sample(0, 0.0),
            sample(40, 4.0),
            sample(80, 50.0),
            sample(120, 96.0),
            sample(160, 104.0),
        )
        val window = com.blazepush.core.domain.model.PerformanceResultWindow(
            startSampleIndex = 0,
            endSampleIndex = 4,
            startDeltaMs = 10,
            endDeltaMs = 140,
        )

        val points = PerformanceTestTelemetryReader.toResultPoints(
            samples,
            TestTemplate.Acceleration0To100,
            window,
        )

        assertEquals(0.0, points.first().elapsedTime, 1e-9)
        assertEquals(1.0, points.first().speed, 1e-9)
        assertEquals(100.0, points.last().speed, 1e-9)
        assertEquals(0.13, points.last().elapsedTime, 1e-9)
    }

    private fun sample(ts: Long, speed: Double) = TelemetrySample(
        tsDeltaMs = ts,
        lat = 30.0,
        lon = 104.0,
        speedKmh = speed,
        bearingDeg = 0.0,
    )
}
