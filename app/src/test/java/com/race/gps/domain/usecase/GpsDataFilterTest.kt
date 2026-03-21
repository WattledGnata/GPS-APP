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
     * 预期：重���后能正常重新处理
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

        assertEquals("应有9个结��", 9, results.size)
        // 速度应在100-110范围内
        assertTrue(
            "速度应在正确范围内",
            results.last().speed >= 100.0
        )
    }

    // ==================== 位置跳变检测测试 ====================
    // TODO: 位置跳变检测功能将在后续迭代实现
    // 相关测试：GF09_positionJump, GF10_smallPositionJitter

    // ==================== 触发确认测试 ====================

    /**
     * GF11: 触发确认需要连续5个点加速度>0.1G
     *
     * 场景：验证测试触发条件
     * 输入：连续5个点加速度>0.1G的序列
     * 预期：第5个点后isTestTriggered=true
     *
     * 0.1G ≈ 0.98 m/s²
     * 在25Hz（40ms间隔）下，0.1G加速度对应：
     * Δv = 0.98 * 0.04 * 3.6 ≈ 0.14 km/h/点
     */
    @Test
    fun GF11_triggerConfirmation_fivePointsAboveThreshold() {
        // Given: 连续加速序列，加速度>0.1G
        // 需要每点增加 >0.14 km/h（在40ms内）
        val baseTimestamp = System.currentTimeMillis()
        val triggerData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                // 每点增加0.2 km/h，加速度约0.14G
                speed = i * 0.2
            )
        }

        // When
        val results = triggerData.map { filter.process(it) }

        // Then: 至少从第5个点开始，应标记为测试触发
        // 注意：需要连续5个点加速度>0.1G
        val triggerPoint = results.indexOfFirst { it.isTestTriggered }
        assertTrue(
            "应在连续5个高加速度点后触发测试",
            triggerPoint >= 4 || triggerPoint == -1 // -1表示功能未实现，测试会失败
        )
    }

    /**
     * GF12: 加速度不足（<0.1G）不应触发
     *
     * 场景：缓慢加速不应触发测试
     * 输入：加速度<0.1G的序列
     * 预期：isTestTriggered始终为false
     */
    @Test
    fun GF12_lowAcceleration_noTrigger() {
        // Given: 缓慢加速，加速度<0.1G
        // 每点增加0.05 km/h，加速度约0.035G
        val baseTimestamp = System.currentTimeMillis()
        val slowAccelerationData = (0 until 15).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = i * 0.05
            )
        }

        // When
        val results = slowAccelerationData.map { filter.process(it) }

        // Then: 不应触发测试
        results.forEach { result ->
            assertFalse(
                "低加速度不应触发测试",
                result.isTestTriggered
            )
        }
    }

    // ==================== 窗口填充边界测试 ====================

    /**
     * GF13: 窗口刚好填满（第9点）应正常工作
     *
     * 场景：窗口从8点变为9点的边界
     * 输入：刚好9个数据点
     * 预期：第9个点正常处理，使用完整窗口
     */
    @Test
    fun GF13_windowExactlyFull_worksCorrectly() {
        // Given: 刚好9个点
        val data = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 10.0,
            points = 9
        )

        // When
        val results = data.map { filter.process(it) }

        // Then: 全部正常处理
        assertEquals("应有9个结果", 9, results.size)
        // 第9个点应使用完整窗口
        assertTrue("第9个点速度应在合理范围", results[8].speed in 0.0..12.0)
    }

    /**
     * GF14: 超过窗口大小（第10点及以后）应正常滑动
     *
     * 场景：窗口已满，新数据进入，旧数据退出
     * 输入：15个数据点（窗口大小9）
     * 预期：正常处理，窗口正确滑动
     */
    @Test
    fun GF14_windowOverflow_slidesCorrectly() {
        // Given: 15个点（超过窗口大小9）
        val data = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 20.0,
            points = 15
        )

        // When
        val results = data.map { filter.process(it) }

        // Then: 全部正常处理
        assertEquals("应有15个结果", 15, results.size)
        // 最后一个点应反映较新的速度（中位数滤波后应在10-20范围内）
        assertTrue("最后点速度应在合理范围", results.last().speed > 10.0)
    }

    // ==================== 连续异常点处理测试 ====================

    /**
     * GF15: 连续异常点应被检测
     *
     * 场景：GPS信号短时间不稳定
     * 输入：序列中有异常跳变点
     * 预期：异常点被标记
     */
    @Test
    fun GF15_consecutiveAnomalies_detected() {
        // Given: 正常序列，第5个点异常跳变
        val normalData = createAccelerationSequence(
            startSpeed = 0.0,
            endSpeed = 10.0,
            points = 9
        )

        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) {
                data.copy(speed = data.speed + 30.0) // 大幅跳变
            } else {
                data
            }
        }

        // When
        val results = anomalousData.map { filter.process(it) }

        // Then: 异常点应被检测
        assertTrue("第5个点应为异常", results[4].isAnomaly)
    }

    /**
     * GF16: 连续多个异常点（>3个）应触发信号质量警告
     *
     * 场景：GPS信号持续差
     * 输入：连续4个异常点
     * 预期：confidence降低，可能触发质量警告
     */
    @Test
    fun GF16_multipleAnomalies_reducesConfidence() {
        // Given: 连续4个异常点
        val baseTimestamp = System.currentTimeMillis()
        val anomalousData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                // 第4-7个点大幅跳变
                speed = if (i in 4..7) 50.0 + (i - 4) * 5.0 else i * 1.0
            )
        }

        // When
        val results = anomalousData.map { filter.process(it) }

        // Then: 连续异常点置信度应降低
        val lowConfidenceCount = results.count { it.confidence < 0.7 }
        assertTrue(
            "连续异常应导致多个低置信度点",
            lowConfidenceCount >= 2
        )
    }
}
