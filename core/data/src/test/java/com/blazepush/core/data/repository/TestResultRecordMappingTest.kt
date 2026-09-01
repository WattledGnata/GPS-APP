package com.blazepush.core.data.repository

import com.blazepush.core.domain.model.PerformanceDeviceSnapshot
import com.blazepush.core.domain.model.PerformanceResultWindow
import com.blazepush.core.domain.model.TestResult
import com.blazepush.core.domain.model.TestTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

class TestResultRecordMappingTest {
    @Test
    fun `保存设备快照和最终窗口且不使用硬编码名称`() {
        val result = TestResult(
            id = "result",
            sessionId = "session",
            template = TestTemplate.Acceleration0To100,
            carModel = "car",
            timestamp = 1L,
            totalTime = 8.23,
            totalDistance = 133.1,
            avgAcceleration = 0.4,
            maxAcceleration = 0.7,
            segments = emptyList(),
            dataPoints = emptyList(),
            dataFilePath = "raw.bin",
            window = PerformanceResultWindow(800, 1000, 30_000, 38_230),
            deviceSnapshot = PerformanceDeviceSnapshot("张豪", "3C:DC:75:8A:6B:22"),
        )

        val entity = result.toRecordEntity()

        assertEquals("张豪", entity.deviceName)
        assertEquals("3C:DC:75:8A:6B:22", entity.deviceAddress)
        assertEquals(800, entity.windowStartSampleIndex)
        assertEquals(1000, entity.windowEndSampleIndex)
        assertEquals(30_000L, entity.windowStartDeltaMs)
        assertEquals(38_230L, entity.windowEndDeltaMs)
        assertEquals(1, entity.windowAlgorithmVersion)
    }
}
