package com.blazepush.feature.test.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class MechanicalSpeedNeedleTest {
    @Test
    fun speedMapsAcrossFullMechanicalSweep() {
        assertEquals(145f, mechanicalSpeedNeedleAngle(0.0, 200.0), 0.001f)
        assertEquals(270f, mechanicalSpeedNeedleAngle(100.0, 200.0), 0.001f)
        assertEquals(395f, mechanicalSpeedNeedleAngle(200.0, 200.0), 0.001f)
    }

    @Test
    fun speedOutsideRange_isClamped() {
        assertEquals(145f, mechanicalSpeedNeedleAngle(-20.0, 200.0), 0.001f)
        assertEquals(395f, mechanicalSpeedNeedleAngle(280.0, 200.0), 0.001f)
        assertEquals(145f, mechanicalSpeedNeedleAngle(null, 200.0), 0.001f)
    }
}
