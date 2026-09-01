package com.blazepush.feature.test.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedChartScaleTest {
    @Test
    fun `IQR 上界约13时仍显示真实100并使用模板量程`() {
        val speeds = List(30) { 0.0 } + listOf(2.0, 4.0, 6.0, 10.0, 30.0, 60.0, 100.2)

        val scale = calculateSpeedChartScale(speeds, templateMaxSpeedKmh = 100.0)

        assertEquals(100.2, scale.actualMaxKmh, 1e-9)
        assertEquals(100.2, scale.axisMaxKmh, 1e-9)
    }

    @Test
    fun `样本未达模板上限时轴仍覆盖模板`() {
        val scale = calculateSpeedChartScale(listOf(0.0, 80.0), templateMaxSpeedKmh = 100.0)
        assertEquals(80.0, scale.actualMaxKmh, 1e-9)
        assertEquals(100.0, scale.axisMaxKmh, 1e-9)
    }
}
