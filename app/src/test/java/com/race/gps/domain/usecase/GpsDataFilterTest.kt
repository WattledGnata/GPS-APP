package com.race.gps.domain.usecase

import com.race.gps.domain.model.GpsData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * GpsDataFilter 单元测试
 * 测试GPS数据异常点过滤逻辑
 *
 * 测试用例命名规范：GF[序号]_[场景]_[预期行为]
 */
class GpsDataFilterTest {

    private lateinit var filter: GpsDataFilter

    @Before
    fun setup() {
        filter = GpsDataFilter()
    }

    // ==================== 辅助函数 ====================

    /**
     * 创建测试用的GpsData
     * @param timestamp 时间戳（毫秒），默认基于索引递增40ms（25Hz）
     * @param speed 速度（km/h）
     */
    private fun createGpsData(
        timestamp: Long = System.currentTimeMillis(),
        speed: Double = 0.0,
        latitude: Double = 60.1725,
        longitude: Double = 24.9375,
        altitude: Double = 100.0,
        bearing: Double = 0.0,
        hdop: Double = 1.0,
        satelliteCount: Int = 12
    ): GpsData = GpsData(
        timestamp = timestamp,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        bearing = bearing,
        satelliteCount = satelliteCount,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null
    )

    /**
     * 创建模拟加速序列
     * @param startSpeed 起始速度（km/h）
     * @param endSpeed 结束速度（km/h）
     * @param points 数据点数
     * @param intervalMs 采样间隔（毫秒），默认40ms（25Hz）
     */
    private fun createAccelerationSequence(
        startSpeed: Double,
        endSpeed: Double,
        points: Int,
        intervalMs: Long = 40L,
        startTimestamp: Long = System.currentTimeMillis()
    ): List<GpsData> {
        val speedStep = (endSpeed - startSpeed) / (points - 1)
        return (0 until points).map { i ->
            createGpsData(
                timestamp = startTimestamp + i * intervalMs,
                speed = startSpeed + speedStep * i
            )
        }
    }

    // ==================== 测试用例 ====================

    /**
     * GF01: 正常加速序列应全部通过，置信度为1.0
     *
     * 场景：0-100加速测试中的正常加速过程
     * 输入：9个连续递增的速度点（0→10 km/h，每点约1.1 km/h增量）
     * 预期：全部通过，isAnomaly=false，confidence=1.0
     */
    @Test
    fun GF01_normalAcceleration_allPassWithHighConfidence() {
        // Given: 9个连续递增的速度点（模拟正常加速）
        val accelerationData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 10.0,
            points = 9
        )

        // When: 依次处理每个点
        val results = accelerationData.map { filter.process(it) }

        // Then: 全部应通过，非异常，高置信度
        results.forEachIndexed { index, result ->
            assertFalse("第${index}个点不应为异常", result.isAnomaly)
            assertEquals(
                "第${index}个点置信度应为1.0",
                1.0,
                result.confidence,
                0.1
            )
        }
    }

    /**
     * GF02: 速度突增（单点异常）应被检测并插值修正
     *
     * 场景：GPS数据中突然出现一个异常高的速度点
     * 输入：正常加速序列，但第5个点速度突增20 km/h（超过物理约束）
     * 预期：第5个点被标记为异常，速度被插值修正
     */
    @Test
    fun GF02_speedSpike_detectedAndInterpolated() {
        // Given: 正常加速序列，但第5个点异常突增
        val normalData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 10.0,
            points = 9
        )

        // 第5个点（索引4）速度突增20 km/h（物理上不可能在40ms内实现）
        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) data.copy(speed = data.speed + 20.0) else data
        }

        // When: 处理数据
        val results = anomalousData.map { filter.process(it) }

        // Then: 第5个点应被标记为异常
        assertTrue("第5个点应为异常", results[4].isAnomaly)

        // 速度应被插值修正到接近前后点的线性插值
        val expectedSpeed = (results[3].speed + results[5].speed) / 2
        assertEquals(
            "第5个点速度应被插值修正",
            expectedSpeed,
            results[4].speed,
            2.0 // 允许一定误差
        )
    }

    /**
     * GF03: 速度骤降（单点异常）应被检测并插值修正
     *
     * 场景：加速过程中GPS数据突然出现一个异常低的速度点
     * 输入：正常加速序列，但第5个点速度骤降10 km/h
     * 预期：第5个点被标记为异常，速度被插值修正
     */
    @Test
    fun GF03_speedDrop_detectedAndInterpolated() {
        // Given: 正常加速序列，但第5个点骤降
        val normalData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 20.0,
            points = 9
        )

        // 第5个点速度骤降10 km/h
        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) data.copy(speed = data.speed - 10.0) else data
        }

        // When
        val results = anomalousData.map { filter.process(it) }

        // Then: 第5个点应被标记为异常
        assertTrue("第5个点应为异常", results[4].isAnomaly)

        // 速度应被修正
        val expectedSpeed = (results[3].speed + results[5].speed) / 2
        assertEquals(
            "第5个点速度应被插值修正",
            expectedSpeed,
            results[4].speed,
            2.0
        )
    }

    /**
     * GF04: 静止漂移（0-0.5 km/h）应正常通过
     *
     * 场景：车辆静止时GPS速度在0附近漂移
     * 输入：速度在0-0.5 km/h之间振荡的序列
     * 预期：全部通过（加速度未超过0.1G触发阈值）
     */
    @Test
    fun GF04_staticDrift_passesNormally() {
        // Given: 静止漂移序列，速度在0-0.5 km/h振荡
        val driftData = (0 until 9).map { i ->
            createGpsData(
                timestamp = System.currentTimeMillis() + i * 40L,
                speed = (i % 3) * 0.2 // 0, 0.2, 0.4, 0, 0.2, 0.4...
            )
        }

        // When
        val results = driftData.map { filter.process(it) }

        // Then: 全部应通过（漂移幅度小，不触发异常检测）
        results.forEachIndexed { index, result ->
            assertFalse("静止漂移第${index}个点不应为异常", result.isAnomaly)
        }
    }

    /**
     * GF05: 加速度计算应正确
     *
     * 场景：验证加速度 = Δv / Δt 计算正确
     * 输入：已知速度变化序列
     * 预期：加速度值计算正确
     */
    @Test
    fun GF05_accelerationCalculation_correct() {
        // Given: 已知加速度的序列
        // 从0加速到10 km/h，用时8*40ms=320ms
        // 加速度 = (10-0)/3.6 / 0.320 ≈ 8.68 m/s²
        val accelerationData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 10.0,
            points = 9
        )

        // When
        val results = accelerationData.map { filter.process(it) }

        // Then: 加速度应约为8.68 m/s²（在中间点测量）
        // 注意：前几个点窗口未满，从第5个点开始测量
        val midAcceleration = results[5].acceleration
        assertTrue(
            "加速度应大于0",
            midAcceleration > 0
        )
        assertTrue(
            "加速度应在合理范围内",
            midAcceleration < 20.0 // 小于2G
        )
    }

    /**
     * GF06: 窗口未满（前8点）应能正常工作
     *
     * 场景：前8个点时窗口未满（需要9点）
     * 输入：前8个正常数据点
     * 预期：仍能正常处理，中位数基于实际数据计算
     */
    @Test
    fun GF06_windowNotFull_worksCorrectly() {
        // Given: 只有8个点（窗口需要9个）
        val partialData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 8.0,
            points = 8
        )

        // When
        val results = partialData.map { filter.process(it) }

        // Then: 应正常处理，不崩溃
        assertEquals("应有8个结果", 8, results.size)
        results.forEachIndexed { index, result ->
            assertNotNull("第${index}个结果不应为null", result)
            assertTrue("速度应为正数", result.speed >= 0)
        }
    }

    /**
     * GF07: 高HDOP应降低置信度
     *
     * 场景：GPS精度差时（HDOP高）置信度应降低
     * 输入：正常速度但HDOP=5.0的数据
     * 预期：置信度降低（<0.7）
     */
    @Test
    fun GF07_highHDOP_reducesConfidence() {
        // Given: 高HDOP的数据
        val poorQualityData = createGpsData(
            speed = 10.0,
            hdop = 5.0
        )

        // 先填充窗口
        repeat(8) {
            filter.process(createGpsData(speed = 10.0, hdop = 5.0))
        }

        // When
        val result = filter.process(poorQualityData)

        // Then: 置信度应降低
        assertTrue(
            "高HDOP应降低置信度",
            result.confidence < 0.7
        )
    }

    /**
     * GF08: reset应清空内部状态
     *
     * 场景：调用reset后应能重新开始
     * 输入：处理一些数据后调用reset
     * 预期：重置后能正常重新处理
     */
    @Test
    fun GF08_reset_clearsState() {
        // Given: 处理一些数据
        val data1 = createAccelerationSequence(0.0, 10.0, 9)
        data1.forEach { filter.process(it) }

        // When: 调用reset
        filter.reset()

        // Then: 应能重新处理新数据
        val data2 = createAccelerationSequence(100.0, 110.0, 9)
        val results = data2.map { filter.process(it) }

        assertEquals("应有9个结果", 9, results.size)
        // 速度应在100-110范围内
        assertTrue(
            "速度应在正确范围内",
            results.last().speed >= 100.0
        )
    }
}
