package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapSessionResponsivePolicyTest {
    @Test
    fun normalPhoneWidthAndFont_keepsTwoColumnMetrics() {
        assertFalse(shouldStackOverviewMetrics(393f, 1f))
    }

    @Test
    fun largeFontOrNarrowWidth_stacksMetricCards() {
        assertTrue(shouldStackOverviewMetrics(393f, 1.20f))
        assertTrue(shouldStackOverviewMetrics(359f, 1f))
    }

    @Test
    fun sectorColumnsGrowWithFontScaleAndRemainBounded() {
        assertEquals(1f, sectorColumnScale(0.85f), 0f)
        assertEquals(1.3f, sectorColumnScale(1.3f), 0f)
        assertEquals(1.6f, sectorColumnScale(2f), 0f)
    }
}
