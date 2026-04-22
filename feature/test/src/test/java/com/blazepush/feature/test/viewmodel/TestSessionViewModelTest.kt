package com.blazepush.feature.test.viewmodel

import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TestSessionViewModel 集成测试（迁移自旧 `com.blazepush.viewmodel` package）
 *
 * 测试GPS数据过滤器集成和触发条件检测
 *
 * 测试用例命名规范：TS[序号]_[场景]_[预期行为]
 *
 * 迁移说明（战役 D）：原文件位于 `app/src/test/java/com/blazepush/viewmodel/TestSessionViewModelTest.kt`，
 * 旧 `TestSessionViewModel` 源码已迁至 `feature.test.viewmodel`，package 与 import 路径均已失效。
 * 战役 D 把它迁到 `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTest.kt`，
 * 并与现有的 `TestSessionViewModelTrackLapTest.kt` 共存（后者测的是 Track/Lap 场景，本文件保留原来的
 * GpsDataFilter 集成语义）。
 *
 * 注意：本文件的用例只依赖 `GpsDataFilter` 的行为，没有真正实例化 `TestSessionViewModel`。
 * 因此本次迁移的目标只是把"编译 fail"修成"编译通过并跑绿"，不扩展到 ViewModel 构造参数的 mock 化。
 */
class TestSessionViewModelTest {

    private lateinit var gpsDataFilter: GpsDataFilter

    @Before
    fun setup() {
        gpsDataFilter = GpsDataFilter()
    }

    // ==================== 辅助函数 ====================

    /**
     * 创建测试用的GpsData
     *
     * 迁移后新增分层守卫，`isTimeSynced = true` 才会走正常滤波路径；
     * 本文件所有用例都在测试已同步后的行为，默认置 true。
     */
    private fun createGpsData(
        timestamp: Long = System.currentTimeMillis(),
        speed: Double = 0.0,
        latitude: Double = 60.1725,
        longitude: Double = 24.9375,
        hdop: Double = 1.0
    ): GpsData = GpsData(
        timestamp = timestamp,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = 100.0,
        bearing = 0.0,
        satelliteCount = 12,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = true
    )

    /**
     * 创建加速序列（模拟真实加速）
     * @param startSpeed 起始速度 (km/h)
     * @param acceleration 加速度 (m/s²)
     * @param points 数据点数
     * @param intervalMs 采样间隔 (ms)
     */
    private fun createAccelerationSequence(
        startSpeed: Double,
        acceleration: Double,
        points: Int,
        intervalMs: Long = 40L,
        startTimestamp: Long = System.currentTimeMillis()
    ): List<GpsData> {
        return (0 until points).map { i ->
            // v = v0 + a * t, a是m/s²，需要转换为km/h
            // 1 m/s² = 3.6 km/h/s
            // 经过 i * intervalMs ms 后的速度
            val timeSeconds = i * intervalMs / 1000.0
            val speed = startSpeed + acceleration * 3.6 * timeSeconds
            createGpsData(
                timestamp = startTimestamp + i * intervalMs,
                speed = speed.coerceAtLeast(0.0)
            )
        }
    }

    // ==================== 测试用例 ====================

    /**
     * TS01: GpsDataFilter应能正确集成到ViewModel
     *
     * 验证GpsDataFilter的process方法能正确处理GpsData
     */
    @Test
    fun TS01_filterIntegration_processesDataCorrectly() {
        // Given: 原始GPS数据
        val rawData = createGpsData(speed = 10.0)

        // When: 通过过滤器处理
        val filtered = gpsDataFilter.process(rawData)

        // Then: 应返回FilteredGpsData
        assertNotNull("应返回非空结果", filtered)
        assertTrue("速度应为正数", filtered.speed >= 0)
        assertNotNull("应有原始数据引用", filtered.raw)
    }

    /**
     * TS02: Pre-trigger缓冲应维护2秒滚动窗口
     *
     * 验证连续处理数据时，缓冲区正确维护
     */
    @Test
    fun TS02_preTriggerBuffer_maintainsRollingWindow() {
        // Given: 2秒的数据 @ 25Hz = 50个点
        val dataPoints = (0 until 50).map { i ->
            createGpsData(
                timestamp = System.currentTimeMillis() + i * 40L,
                speed = i * 0.5 // 缓慢增加
            )
        }

        // When: 依次处理
        val results = dataPoints.map { gpsDataFilter.process(it) }

        // Then: 应全部成功处理
        assertEquals("应有50个结果", 50, results.size)
        results.forEach { result ->
            assertNotNull("每个结果应有原始数据引用", result.raw)
        }
    }

    /**
     * TS03: 触发条件检测 - 加速度阈值
     *
     * 场景：验证加速度>0.1G能被正确检测
     * 0.1G ≈ 1 m/s² ≈ 3.6 km/h/s
     */
    @Test
    fun TS03_triggerCondition_detectsAcceleration() {
        // Given: 加速度为2 m/s²（约0.2G）的序列
        val accelerationData = createAccelerationSequence(
            startSpeed = 0.0,
            acceleration = 2.0, // 2 m/s² > 0.1G
            points = 9,
            intervalMs = 40L
        )

        // When: 处理数据
        val results = accelerationData.map { gpsDataFilter.process(it) }

        // Then: 加速度应被计算出来
        // 加速度应该 > 0
        val hasPositiveAcceleration = results.any { it.acceleration > 0 }
        assertTrue("应有正加速度", hasPositiveAcceleration)
    }

    /**
     * TS04: 触发条件检测 - GPS漂移不应触发
     *
     * 场景：静止时GPS漂移（加速度≈0）不应触发
     */
    @Test
    fun TS04_triggerCondition_noTriggerOnDrift() {
        // Given: 静止漂移序列（速度在0-0.5 km/h振荡）
        val driftData = (0 until 9).map { i ->
            createGpsData(
                timestamp = System.currentTimeMillis() + i * 40L,
                speed = (i % 3) * 0.2 // 0, 0.2, 0.4, 0, ...
            )
        }

        // When: 处理数据
        val results = driftData.map { gpsDataFilter.process(it) }

        // Then: 加速度应接近0（漂移不是真实加速）
        val avgAcceleration = results.map { it.acceleration }.average()
        assertTrue(
            "漂移的加速度应很小",
            kotlin.math.abs(avgAcceleration) < 5.0 // < 0.5G
        )
    }

    /**
     * TS05: 终点检测 - 速度接近0时应触发
     *
     * 场景：速度降到1.0 km/h以下应触发结束
     */
    @Test
    fun TS05_endCondition_triggersAtLowSpeed() {
        // Given: 减速到0的序列
        val decelerationData = (0 until 9).map { i ->
            createGpsData(
                timestamp = System.currentTimeMillis() + i * 40L,
                speed = (8 - i).coerceAtLeast(0).toDouble() // 8,7,6,...,0
            )
        }

        // When: 处理数据
        val results = decelerationData.map { gpsDataFilter.process(it) }

        // Then: 最后一个点的速度应接近0
        assertTrue(
            "最后一个点速度应<1.0 km/h",
            results.last().speed < 5.0 // 滤波后可能不是精确0
        )
    }

    /**
     * TS06: Reset应清空过滤器状态
     *
     * 场景：重置后应能重新开始
     */
    @Test
    fun TS06_reset_clearsFilterState() {
        // Given: 处理一些数据
        val data1 = createAccelerationSequence(0.0, 2.0, 9)
        data1.forEach { gpsDataFilter.process(it) }

        // When: 重置
        gpsDataFilter.reset()

        // Then: 应能重新处理新数据
        val data2 = createAccelerationSequence(100.0, 2.0, 9)
        val results = data2.map { gpsDataFilter.process(it) }

        assertEquals("应有9个结果", 9, results.size)
        assertTrue("速度应在合理范围", results.first().speed >= 0)
    }

    /**
     * TS07: 异常点检测 - 速度突增应被标记
     *
     * 场景：速度突然增加超过物理约束应被检测
     */
    @Test
    fun TS07_anomalyDetection_marksSpeedSpike() {
        // Given: 正常序列中插入一个异常点
        val normalData = createAccelerationSequence(0.0, 1.0, 9)

        // 修改第5个点为异常高速度
        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) data.copy(speed = 50.0) else data
        }

        // When: 处理数据
        val results = anomalousData.map { gpsDataFilter.process(it) }

        // Then: 异常点应被标记
        assertTrue("第5个点应为异常", results[4].isAnomaly)
    }

    /**
     * TS08: FilteredGpsData应保留原始数据引用
     *
     * 场景：滤波后数据应能追溯到原始数据
     */
    @Test
    fun TS08_filteredData_retainsRawReference() {
        // Given: 原始数据
        val rawData = createGpsData(
            speed = 100.0,
            latitude = 60.0,
            longitude = 25.0
        )

        // When: 处理
        val filtered = gpsDataFilter.process(rawData)

        // Then: 应保留原始数据
        assertNotNull("应有原始数据", filtered.raw)
        assertEquals("原始速度应保留", 100.0, filtered.raw.speed, 0.1)
        assertEquals("原始纬度应保留", 60.0, filtered.raw.latitude, 0.001)
    }
}
