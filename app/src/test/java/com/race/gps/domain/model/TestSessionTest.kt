package com.race.gps.domain.model

import com.race.gps.domain.usecase.FilteredGpsData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TestSession 数据结构测试
 * 测试preTriggerData字段和集成GpsDataFilter
 *
 * 测试用例命名规范：TM[序号]_[场景]_[预期行为]
 */
class TestSessionTest {

    // ==================== 辅助函数 ====================

    private fun createGpsData(
        timestamp: Long = System.currentTimeMillis(),
        speed: Double = 10.0
    ): GpsData = GpsData(
        timestamp = timestamp,
        speed = speed,
        latitude = 60.1725,
        longitude = 24.9375,
        altitude = 100.0,
        bearing = 0.0,
        satelliteCount = 12,
        hdop = 1.0,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null
    )

    private fun createFilteredGpsData(
        timestamp: Long = System.currentTimeMillis(),
        speed: Double = 10.0,
        acceleration: Double = 0.0
    ): FilteredGpsData {
        val raw = createGpsData(timestamp, speed)
        return FilteredGpsData(
            speed = speed,
            latitude = 60.1725,
            longitude = 24.9375,
            altitude = 100.0,
            bearing = 0.0,
            acceleration = acceleration,
            confidence = 1.0,
            isAnomaly = false,
            timestamp = timestamp,
            raw = raw
        )
    }

    // ==================== 测试用例 ====================

    /**
     * TM01: TestSession应能存储preTriggerData
     *
     * 场景：触发时应能锁定并存储触发前的滤波数据
     */
    @Test
    fun TM01_preTriggerData_canStorePreTriggerBuffer() {
        // Given: 触发前的2秒数据（约50个点 @ 25Hz）
        val preTriggerData = (0 until 50).map { i ->
            createFilteredGpsData(
                timestamp = System.currentTimeMillis() + i * 40L,
                speed = i * 0.1
            )
        }

        // When: 创建带有preTriggerData的TestSession
        val session = TestSession(
            id = "test-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "Test Car",
            startTime = System.currentTimeMillis(),
            preTriggerData = preTriggerData
        )

        // Then: preTriggerData应被正确存储
        assertEquals("应有50个pre-trigger数据点", 50, session.preTriggerData.size)
    }

    /**
     * TM02: TestSession默认preTriggerData为空列表
     *
     * 场景：不提供preTriggerData时应为空
     */
    @Test
    fun TM02_preTriggerData_defaultEmpty() {
        // When: 创建不带preTriggerData的TestSession
        val session = TestSession(
            id = "test-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "Test Car",
            startTime = System.currentTimeMillis()
        )

        // Then: preTriggerData应为空列表
        assertTrue("preTriggerData应默认为空", session.preTriggerData.isEmpty())
    }

    /**
     * TM03: markStarted应记录��发时间
     *
     * 场景：触发时应正确记录triggerTime
     */
    @Test
    fun TM03_markStarted_recordsTriggerTime() {
        // Given: 一个新的TestSession
        val session = TestSession(
            id = "test-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "Test Car",
            startTime = System.currentTimeMillis()
        )
        val triggerData = createFilteredGpsData(speed = 10.0)
        val preTriggerBuffer = listOf(createFilteredGpsData(speed = 5.0))

        // When: 调用markStarted
        session.markStarted(triggerData, preTriggerBuffer)

        // Then: triggerTime应被设置
        assertNotNull("triggerTime应被设置", session.triggerTime)
        assertEquals("triggerTime应为数据时间戳", triggerData.timestamp, session.triggerTime)
    }

    /**
     * TM04: addFilteredDataPoint应正确计算elapsedTime
     *
     * 场景：添加数据点时应正确计算相对时间
     */
    @Test
    fun TM04_addFilteredDataPoint_calculatesElapsedTime() {
        // Given: 已触发的TestSession
        val baseTime = System.currentTimeMillis()
        val session = TestSession(
            id = "test-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "Test Car",
            startTime = baseTime
        )
        val triggerData = createFilteredGpsData(timestamp = baseTime, speed = 5.0)
        session.markStarted(triggerData, emptyList())

        // When: 添加数据点
        val laterData = createFilteredGpsData(timestamp = baseTime + 1000, speed = 20.0) // 1秒后
        session.addFilteredDataPoint(laterData)

        // Then: elapsedTime应为1.0秒
        assertEquals("elapsedTime应为1.0秒", 1.0, session.dataPoints.last().elapsedTime, 0.01)
    }

    /**
     * TM05: TestSession应能存储filteredDataPoints
     *
     * 场景：测试过程中应能存储滤波后的数据
     */
    @Test
    fun TM05_filteredDataPoints_canStoreFilteredData() {
        // Given: 触发后的TestSession
        val baseTime = System.currentTimeMillis()
        val session = TestSession(
            id = "test-1",
            template = TestTemplate.Acceleration0To100,
            carModel = "Test Car",
            startTime = baseTime
        )
        val triggerData = createFilteredGpsData(timestamp = baseTime, speed = 5.0)
        session.markStarted(triggerData, emptyList())

        // When: 添加滤波数据点
        val filteredData = createFilteredGpsData(timestamp = baseTime + 100, speed = 50.0, acceleration = 5.0)
        session.addFilteredDataPoint(filteredData)

        // Then: 数据应被存储
        assertEquals("应有2个滤波数据点（包含触发点）", 2, session.filteredDataPoints.size)
        assertEquals("最新点的加速度应被保留", 5.0, session.filteredDataPoints.last().acceleration, 0.1)
    }
}
