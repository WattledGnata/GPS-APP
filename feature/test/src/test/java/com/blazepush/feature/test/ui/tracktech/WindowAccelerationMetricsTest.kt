package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.GpsDataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAccelerationMetricsTest {
    @Test
    fun `详情摘要从传入窗口派生`() {
        val points = (0..25).map { index ->
            GpsDataPoint(index * 0.04, index * 4.0, 0.0, 0.0, 0.0)
        }

        val metrics = deriveWindowAccelerationMetrics(points)

        assertTrue(metrics.avgG > 0.0)
        assertTrue(metrics.maxAccelerationG > 0.0)
        assertEquals(0.0, metrics.maxDecelerationG, 1e-9)
    }
}
