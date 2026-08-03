package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.BatteryCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryCapabilityPresentationTest {
    @Test
    fun `four capability states have distinct user facing labels`() {
        val labels = listOf(
            BatteryCapabilityState.Pending,
            BatteryCapabilityState.Available(85),
            BatteryCapabilityState.Unsupported,
            BatteryCapabilityState.Failed,
        ).map { it.displayLabel() }

        assertEquals(listOf("检测中", "85%", "不支持", "读取失败"), labels)
        assertEquals(4, labels.toSet().size)
    }
}
