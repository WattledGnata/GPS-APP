package com.blazepush.feature.test.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GForceChartScaleTest {
    @Test
    fun `0到100使用零到正峰值单向量程`() {
        val scale = calculateGForceChartScale(
            values = listOf(0.18, 0.42, 0.31, 0.78, 0.24),
            highlightedPeakG = 0.78,
            mode = GForceChartMode.ACCELERATION,
        )

        assertEquals(0.0, scale.minG, 1e-9)
        assertEquals(0.78, scale.maxG, 1e-9)
    }

    @Test
    fun `100到0使用负峰值到零单向量程`() {
        val scale = calculateGForceChartScale(
            values = listOf(-0.2, -0.65, -0.4),
            highlightedPeakG = 0.7,
            mode = GForceChartMode.BRAKING,
        )

        assertEquals(-0.7, scale.minG, 1e-9)
        assertEquals(0.0, scale.maxG, 1e-9)
    }
}
