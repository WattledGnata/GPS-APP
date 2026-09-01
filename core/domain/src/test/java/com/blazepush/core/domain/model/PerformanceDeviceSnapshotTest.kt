package com.blazepush.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceDeviceSnapshotTest {
    @Test
    fun `名称优先级为别名真实名地址`() {
        assertEquals(
            "张豪",
            PerformanceDeviceSnapshot.resolve(" 张豪 ", "BlazePush-Gen2-0003", "AA:BB").displayName,
        )
        assertEquals(
            "BlazePush-Gen2-0003",
            PerformanceDeviceSnapshot.resolve(" ", "BlazePush-Gen2-0003", "AA:BB").displayName,
        )
        assertEquals(
            "AA:BB",
            PerformanceDeviceSnapshot.resolve(null, "", "AA:BB").displayName,
        )
    }
}
