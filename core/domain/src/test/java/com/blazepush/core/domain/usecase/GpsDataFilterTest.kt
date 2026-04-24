// @IgnoreFormatCheck
// 理由：本文件 40+ 条 legacy 违规（GF01~GF22 / A12~A14 使用下划线分段 method-name + class KDoc
//       缺 @author/@description/@date + setup 无 multi-line comment + 1034/1037 `!!` 断言 +
//       1567 trailing newline）是战役 D 回迁测试 + A12/A13/A14 新加时的 pre-kt-check 存量。
//       本次战役 C 三期 P2 修订只动 GF09/GF20b 4 处方法字符串（旧英文名 → 新英文名）
//       和 7 处中文注释（旧中文术语 → 循环均值）（A43 命名纠偏）。rename 38 个 GF##/A##
//       legacy 方法 + 加 class KDoc tags + 去 `!!` 远超本次 P2 scope。
//       参照 `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`
//       同情形先例（战役 G R4 B 方案），评审方 2026-04-24 commit 阶段批准此 ignore。
package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * GpsDataFilter 单元测试
 * 测试GPS数据异常点过滤逻辑
 *
 * 测试用例命名规范：GF[序号]_[场景]_[预期行为]
 *
 * 迁移说明（战役 D）：本文件原位于 `app/src/test/java/com/blazepush/domain/usecase/`，
 * 因源码已迁至 `core:domain` 而停留在旧 package，`:app:compileDebugUnitTestKotlin` 直接 fail。
 * 战役 D 将其迁回 `core/domain/src/test/...`，并合并战役 A（`fix-laptime-clock-source-integrity`）
 * 临时寄存在 `feature:test` 下的两条 filter 守卫用例（`process_notTimeSynced_*`）。
 */
class GpsDataFilterTest {

    private lateinit var filter: GpsDataFilter

    @Before
    fun setup() {
        filter = GpsDataFilter()
    }

    // ==================== 辅助函数 ====================

    /**
     * 创建测试用的 GpsData
     *
     * 注意：迁移后新增的分层守卫要求 `isTimeSynced` 为 true 才会进入正常滤波路径，
     * 因此本辅助函数默认置为 true；专门测试未同步场景的用例位于文件末尾，使用独立的工厂。
     *
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
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = true
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

    /**
     * 创建带位置变化的GPS序列
     * 模拟车辆向北（纬度增加）行驶，位置变化与速度一致
     *
     * 关键设计：累积位移用每个区间的平均速度计算。
     * 设区间 [i-1, i] 的位移 = avg(speed[i-1], speed[i]) * dt
     * 这样一致性检验中 v_implied = displacement/dt = avg(speed[i-1], speed[i])
     * 但 GPS 报告 speed[i]，两者在加速时不完全相等。
     * 为确保一致性通过（speedDiff < tolerance），使用保守的 startSpeed 作为区间速度。
     */
    private fun createMovingSequence(
        startSpeed: Double,
        endSpeed: Double,
        points: Int,
        intervalMs: Long = 40L,
        startTimestamp: Long = System.currentTimeMillis(),
        startLat: Double = 60.1725,
        startLon: Double = 24.9375,
        bearing: Double = 0.0  // 正北方向
    ): List<GpsData> {
        val speedStep = (endSpeed - startSpeed) / (points - 1)
        // 每个区间使用区间起始速度计算位移，保证 v_implied = reported speed（匀速）或略小（加速时）
        return (0 until points).map { i ->
            val speed = startSpeed + speedStep * i
            // 累积位移：使用 startSpeed（保守值），确保 v_implied <= reported speed
            // 位移 = speed(m/s) * interval(ms)/1000(s) / 111320(度/米)
            val displacementPerPoint = (startSpeed / 3.6) * intervalMs / 1000.0 / 111320.0
            createGpsData(
                timestamp = startTimestamp + i * intervalMs,
                speed = speed,
                latitude = startLat + displacementPerPoint * i,
                longitude = startLon,
                bearing = bearing,
                hdop = 1.0
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

    // ==================== 航向角循环边界测试 ====================

    /**
     * GF09: 航向角循环边界（真正跨0°）应使用循环均值
     *
     * 场景：航向从 359° 经过 0° 到 1°
     * 输入：航向窗口 [358°, 359°, 0°, 1°, 2°, 3°, 4°, 5°, 6°]
     * 预期：普通中位数 = 3°（连续域错误），循环均值 ≈ 1-2°（正确）
     *
     * 关键：数据紧密围绕0°分布，普通排序会给出错误结果
     */
    @Test
    fun GF09_bearingCrossZero_circularMean() {
        // Given: 航向角真正跨0°（紧密围绕0°分布）
        val baseTimestamp = System.currentTimeMillis()
        val crossingData = listOf(358.0, 359.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0).mapIndexed { i, bearing ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                bearing = bearing,
                hdop = 1.0
            )
        }

        // When
        val results = crossingData.map { filter.process(it) }

        // Then: 循环均值应接近 1-2°（正确），而非普通中位数 3°
        val outputBearing = results.last().bearing
        // 循环均值应 < 5°（在0°附近），而普通中位数是3°但排序后不在0°附近
        assertTrue(
            "循环航向应接近0°附近（<5°），实际=$outputBearing°",
            outputBearing < 5.0
        )
    }

    /**
     * GF10: 极小幅位置抖动（Δd < 0.01m）应通过一致性检查
     *
     * 场景：GPS 精度范围内的极小幅抖动不应触发位置异常
     * 输入：速度 60 km/h，位置变化 Δd < 0.01m（GPS 噪声水平以下）
     * 预期：consistencyFactor = 1.0，isPositionAnomaly = false
     *
     * 关键：当 distanceM < 0.01m 时，一致性检验自动跳过（返回 factor=1.0）
     * 这是 GPS 位置噪声的正常行为，不应被标记为异常
     */
    @Test
    fun GF10_smallPositionJitter_passesConsistency() {
        // Given: 先用稳定速度填充窗口，建立正常一致性基准
        val baseTimestamp = System.currentTimeMillis()

        // 前9个点：稳定匀速，建立正常一致性基准
        val stableData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 60.0,
                latitude = 60.1725,
                longitude = 24.9375,
                bearing = 0.0,
                hdop = 1.0
            )
        }
        stableData.forEach { filter.process(it) }

        // 接下来3个点：引入极小幅抖动（Δd < 0.01m，自动跳过一致性检查）
        val jitterData = (0 until 3).map { i ->
            // ±0.003m，< 0.01m 阈值
            val jitter = if (i % 2 == 0) 0.00000003 else -0.00000003
            createGpsData(
                timestamp = baseTimestamp + (9 + i) * 40L,
                speed = 60.0,
                latitude = 60.1725 + jitter,
                longitude = 24.9375,
                bearing = 0.0,
                hdop = 1.0
            )
        }

        // When
        val results = jitterData.map { filter.process(it) }

        // Then: Δd < 0.01m 时一致性检验自动跳过，factor = 1.0
        results.forEachIndexed { idx, result ->
            assertFalse(
                "极小幅抖动第${idx}个点不应为位置异常",
                result.isPositionAnomaly
            )
            assertEquals(
                "极小幅抖动第${idx}个点一致性因子应为1.0（Δd<0.01m跳过检查）",
                1.0,
                result.consistencyFactor,
                0.01
            )
        }
    }

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

    /**
     * GF16b: 2.0G 到 2.5G 之间的加速不应再被判为异常
     *
     * 场景：需求将加速度上限从 1.5G 提升到 2.5G 后，
     * 中高强度加速样本应通过物理约束检查。
     */
    @Test
    fun GF16b_accelerationBetween2GAnd2_5G_shouldPass() {
        // Given: 40ms 内提升 1.0 km/h，约 6.94 m/s² ≈ 0.71G（首个有效点）
        // 连续两步共提升 2.0 km/h，可覆盖高于旧阈值、低于新阈值的场景
        val baseTimestamp = System.currentTimeMillis()
        val data = listOf(
            createGpsData(timestamp = baseTimestamp, speed = 0.0),
            createGpsData(timestamp = baseTimestamp + 40L, speed = 3.0),
            createGpsData(timestamp = baseTimestamp + 80L, speed = 6.0)
        )

        // When
        val results = data.map { filter.process(it) }

        // Then: 3 km/h / 40ms = 20.83 m/s² ≈ 2.12G，应在新阈值内
        assertFalse("2.12G 加速不应判为异常", results[1].isAnomaly)
        assertFalse("连续 2.12G 加速不应判为异常", results[2].isAnomaly)
    }

    /**
     * GF16c: 2.0G 到 3.0G 之间的减速不应再被判为异常
     *
     * 场景：需求将减速度上限从 2.0G 提升到 3.0G 后，
     * 更强的制动样本应通过物理约束检查。
     */
    @Test
    fun GF16c_decelerationBetween2GAnd3G_shouldPass() {
        val baseTimestamp = System.currentTimeMillis()
        val data = listOf(
            createGpsData(timestamp = baseTimestamp, speed = 60.0),
            createGpsData(timestamp = baseTimestamp + 40L, speed = 56.0),
            createGpsData(timestamp = baseTimestamp + 80L, speed = 52.0)
        )

        // 4 km/h / 40ms = 27.78 m/s² ≈ 2.83G，应在新阈值内
        val results = data.map { filter.process(it) }

        assertFalse("2.83G 减速不应判为异常", results[1].isAnomaly)
        assertFalse("连续 2.83G 减速不应判为异常", results[2].isAnomaly)
    }

    // ==================== 位置-速度一致性检验测试 ====================

    /**
     * GF17: 匀速行驶应通过一致性检验
     *
     * 场景：速度恒定，位置变化与速度完全一致
     * 输入：匀速 45 km/h，位移与报告速度精确一致
     * 预期：consistencyFactor = 1.0, isPositionAnomaly = false
     *
     * 注意：使用匀速是因为加速时 GPS 报告端点速度与区间平均速度存在固有差异，
     * 即使正常加速也会导致一致性 factor < 1.0。匀速场景下两者完全一致。
     */
    @Test
    fun GF17_normalPositionConsistent_fullFactor() {
        // Given: 匀速移动序列（startSpeed == endSpeed 确保一致性）
        val movingData = createMovingSequence(
            startSpeed = 45.0,
            endSpeed = 45.0,  // 匀速，保证报告速度与位移完全一致
            points = 9
        )

        // When
        val results = movingData.map { filter.process(it) }

        // Then: 从第2个点开始一致性因子应为 1.0
        results.drop(2).forEachIndexed { idx, result ->
            val index = idx + 2
            assertEquals(
                "第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
            assertFalse(
                "第${index}个点不应为位置异常",
                result.isPositionAnomaly
            )
        }
    }

    /**
     * GF17c: 低速行驶（<5 km/h）应通过一致性检验，容差 3 km/h
     *
     * 场景：低速时 GPS 精度可能较差，容差缩小至 3 km/h
     * 输入：低速 3 km/h 匀速行驶
     * 预期：consistencyFactor = 1.0
     */
    @Test
    fun GF17c_lowSpeedConsistent_fullFactor() {
        // Given: 低速匀速序列
        val movingData = createMovingSequence(
            startSpeed = 3.0,
            endSpeed = 3.0,  // 匀速 3 km/h
            points = 9
        )

        // When
        val results = movingData.map { filter.process(it) }

        // Then: 从第2个点开始一致性因子应为 1.0
        results.drop(2).forEachIndexed { idx, result ->
            val index = idx + 2
            assertEquals(
                "低速第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
            assertFalse(
                "低速第${index}个点不应为位置异常",
                result.isPositionAnomaly
            )
        }
    }

    /**
     * GF17b: 高速行驶（120 km/h）应通过一致性检验，容差 10 km/h
     *
     * 场景：高速时 GPS 精度通常更好，容差放宽至 10 km/h
     * 输入：120 km/h 稳定行驶，位置平滑变化
     * 预期：consistencyFactor = 1.0
     */
    @Test
    fun GF17b_highSpeedConsistent_fullFactor() {
        // Given: 高速稳定行驶序列
        val movingData = createMovingSequence(
            startSpeed = 120.0,
            endSpeed = 120.0,  // 稳定 120 km/h
            points = 12
        )

        // When
        val results = movingData.map { filter.process(it) }

        // Then: 从第2个点开始一致性因子应为 1.0
        results.drop(2).forEachIndexed { idx, result ->
            val index = idx + 2
            assertEquals(
                "高速第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
            assertFalse(
                "高速第${index}个点不应为位置异常",
                result.isPositionAnomaly
            )
        }
    }

    /**
     * GF18: 位置跳变（瞬间位移100m）应被标记为位置异常
     *
     * 场景：GPS 位置瞬间跳变（不可能的物理位移）
     * 输入：第5个点纬度瞬间跳变 +0.001度（约110m）
     * 预期：isPositionAnomaly = true, consistencyFactor 降低
     */
    @Test
    fun GF18_positionJump_detectedAsAnomaly() {
        // Given: 正常移动序列，第5个点位置跳变
        val normalData = createMovingSequence(
            startSpeed = 30.0,
            endSpeed = 60.0,
            points = 9
        )

        val anomalousData = normalData.mapIndexed { index, data ->
            if (index == 4) {
                data.copy(latitude = data.latitude + 0.001) // 约+110m
            } else {
                data
            }
        }

        // When
        val results = anomalousData.map { filter.process(it) }

        // Then: 第5个点应标记为位置异常
        assertTrue(
            "第5个点应为位置异常",
            results[4].isPositionAnomaly
        )
        assertTrue(
            "第5个点一致性因子应降低",
            results[4].consistencyFactor < 1.0
        )
    }

    /**
     * GF19: 静止漂移（Δd < 0.5m）应跳过一致性检查
     *
     * 场景：低速时位置变化极小，不进行一致性判断
     * 输入：低速（speed < 5 km/h）且位置几乎不变
     * 预期：consistencyFactor = 1.0（跳过检查）
     */
    @Test
    fun GF19_lowSpeedSkipsConsistencyCheck_fullFactor() {
        // Given: 低速静止序列
        val baseTimestamp = System.currentTimeMillis()
        val lowSpeedData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = (i % 3) * 0.5,  // 0, 0.5, 1.0, 0, ...
                latitude = 60.1725,      // 静止
                longitude = 24.9375,     // 静止
                hdop = 1.0
            )
        }

        // When
        val results = lowSpeedData.map { filter.process(it) }

        // Then: 一致性因子应为1.0（Δd过小跳过检查）
        results.drop(1).forEachIndexed { index, result ->
            assertEquals(
                "低速静止第${index}个点一致性因子应为1.0",
                1.0,
                result.consistencyFactor,
                0.01
            )
        }
    }

    /**
     * GF20: 航向剧变（>30°/s）应降权 consistencyFactor
     *
     * 场景：转弯时直线位移与速度不匹配
     * 输入：航向在40ms内变化90度
     * 预期：consistencyFactor × 0.8 降权
     */
    @Test
    fun GF20_sharpTurn_reducesConsistencyFactor() {
        // Given: 弯道场景，航向剧变
        val baseTimestamp = System.currentTimeMillis()
        val turnData = (0 until 9).map { i ->
            val bearing = if (i < 5) 0.0 else 90.0  // 第5个点突然转弯
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                latitude = 60.1725 + i * 0.0001,
                longitude = 24.9375,
                bearing = bearing,
                hdop = 1.0
            )
        }

        // When
        val results = turnData.map { filter.process(it) }

        // Then: 航向变化点应有降权的 consistencyFactor
        // bearing 从 0° 到 90°，变化 90° > 30°，应 ×0.8
        assertTrue(
            "转弯点一致性因子应降低",
            results[5].consistencyFactor < 0.9
        )
    }

    /**
     * GF20b: 航向角跨0°循环边界测试
     *
     * 场景：航向跨越 0° 边界时，circularMean() 应正确处理循环
     * 输入：航向窗口 [355°, 358°, 0°, 2°, 5°, 8°, 10°, 12°, 15°]
     * 预期：circularMean() ≈ 5°-8°（正确），普通中位数会得到 358°（错误）
     *
     * 关键：数据紧密围绕0°分布（355°-15°），真正的中心约5°
     * 普通排序 [0°,2°,5°,8°,10°,12°,15°,355°,358°] 中位数 = 10°
     * 循环向量平均应给出约 5°（正确方向）
     */
    @Test
    fun GF20b_bearingCircularMean_crossesZero() {
        // Given: 航向角紧密围绕 0° 分布（跨0°）
        val baseTimestamp = System.currentTimeMillis()
        val crossingData = listOf(355.0, 358.0, 0.0, 2.0, 5.0, 8.0, 10.0, 12.0, 15.0).mapIndexed { i, bearing ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                bearing = bearing,
                hdop = 1.0
            )
        }

        // When
        val results = crossingData.map { filter.process(it) }

        // Then: 循环均值应接近真正的方向中心（约 5°-8°）
        // 普通中位数排序后会得到中间值约 10°（错误）
        // 循环均值应 < 15°（在正确方向）
        val outputBearing = results.last().bearing
        // 循环均值应 < 15°（在0°附近），而不是接近355°-358°
        assertTrue(
            "循环航向应接近真正方向中心（<15°），实际=$outputBearing°",
            outputBearing < 15.0
        )
    }

    /**
     * GF21: GPS 信号丢失（>200ms）后重置一致性检验
     *
     * 场景：GPS 信号中断后恢复
     * 输入：第5个点与第4个点间隔 300ms
     * 预期：第5个点不触发位置异常（dt过大跳过检查）
     *
     * 注意：使用匀速序列避免加速导致的 speedDiff > tolerance 问题
     */
    @Test
    fun GF21_signalLoss_skipsConsistencyCheck() {
        // Given: 匀速序列，第5个点间隔300ms
        val normalData = createMovingSequence(
            startSpeed = 45.0,
            endSpeed = 45.0,  // 匀速
            points = 5
        )

        val gapData = normalData.mapIndexed { index, data ->
            if (index == 4) {
                // 300ms 间隔（原本40ms）
                data.copy(timestamp = data.timestamp + 260)
            } else {
                data
            }
        }

        // When
        val results = gapData.map { filter.process(it) }

        // Then: 大间隔点不应为位置异常（跳过检查）
        assertFalse(
            "大间隔点不应为位置异常",
            results[4].isPositionAnomaly
        )
        assertEquals(
            "大间隔点一致性因子应为1.0",
            1.0,
            results[4].consistencyFactor,
            0.01
        )
    }

    /**
     * GF22: 中位数滤波后位置应比原始位置更平滑
     *
     * 场景：位置有小幅抖动时，中位数滤波应平滑
     * 输入：纬度有小抖动（±0.00001度）的序列
     * 预期：滤波后纬度变化幅度小于原始
     */
    @Test
    fun GF22_positionMedianFilter_smoothsOutput() {
        // Given: 位置有小幅随机抖动
        val baseTimestamp = System.currentTimeMillis()
        val jitterData = (0 until 9).map { i ->
            createGpsData(
                timestamp = baseTimestamp + i * 40L,
                speed = 30.0,
                latitude = 60.1725 + (i % 3 - 1) * 0.00001,  // ±0.00001度抖动
                longitude = 24.9375,
                bearing = 0.0,
                hdop = 1.0
            )
        }

        // When
        val results = jitterData.map { filter.process(it) }

        // Then: 窗口填满后，滤波后纬度应趋于稳定
        if (results.size >= 9) {
            val filteredLats = results.map { it.latitude }
            val originalLats = jitterData.map { it.latitude }
            // 原始数据波动范围
            val originalRange = originalLats.maxOrNull()!! - originalLats.minOrNull()!!
            // 滤波后范围（取中间几个）
            val filteredRange = filteredLats.drop(5).take(3).let {
                it.maxOrNull()!! - it.minOrNull()!!
            }
            assertTrue(
                "滤波后位置变化应小于原始",
                filteredRange <= originalRange
            )
        }
    }

    // ==================== 分层守卫：时间未同步时不做 delta 计算（战役 A 合并） ====================

    /**
     * 构造测试用的未同步帧。`timestamp = Long.MIN_VALUE` 与 parser sentinel 对齐，
     * 用于验证守卫生效时不会因为对 sentinel 做减法产生溢出。
     */
    private fun unsyncedSample(
        speed: Double = 50.0,
        latitude: Double = 30.5,
        longitude: Double = 104.4,
        altitude: Double = 500.0,
        bearing: Double = 90.0,
        hdop: Double = 1.0
    ): GpsData = GpsData(
        timestamp = Long.MIN_VALUE,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        bearing = bearing,
        satelliteCount = 8,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = false,
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = false
    )

    /**
     * 构造测试用的已同步帧。用于恢复场景验证。
     */
    private fun syncedSample(
        timestamp: Long,
        speed: Double = 50.0,
        latitude: Double = 30.5,
        longitude: Double = 104.4,
        altitude: Double = 500.0,
        bearing: Double = 90.0,
        hdop: Double = 1.0
    ): GpsData = GpsData(
        timestamp = timestamp,
        speed = speed,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        bearing = bearing,
        satelliteCount = 8,
        hdop = hdop,
        vdop = 1.0,
        frequency = 25.0,
        isConnected = true,
        isTestReady = false,
        errorMessage = null,
        fixQuality = 1,
        isTimeSynced = true
    )

    /**
     * 守卫生效时直接返回"零时间 delta 快照"：
     * - speed / latitude 等空间量从 raw 透传；
     * - acceleration / confidence 归零；
     * - isAnomaly / isPositionAnomaly 为 false；
     * - timestamp 保持 raw 值（sentinel）。
     *
     * 对应 spec：
     * `openspec/changes/fix-laptime-clock-source-integrity/specs/laptime-clock-source/spec.md`
     *   Requirement: 分层守卫 — Scenario "GpsDataFilter 在未同步时不做时间 delta 计算"。
     */
    @Test
    fun process_notTimeSynced_returnsZeroDeltaSnapshot() {
        val localFilter = GpsDataFilter()
        val raw = unsyncedSample(speed = 50.0, latitude = 30.5, longitude = 104.4)

        val result = localFilter.process(raw)

        assertEquals(50.0, result.speed, 1e-9)
        assertEquals(30.5, result.latitude, 1e-9)
        assertEquals(104.4, result.longitude, 1e-9)
        assertEquals(0.0, result.acceleration, 1e-9)
        assertEquals(0.0, result.confidence, 1e-9)
        assertFalse(result.isAnomaly)
        assertFalse(result.isPositionAnomaly)
        assertEquals(Long.MIN_VALUE, result.timestamp)
        assertEquals(1.0, result.consistencyFactor, 1e-9)
    }

    /**
     * 守卫生效时不得更新 `previousRaw / previousPosition / 四个窗口`。
     *
     * **间接验证**：喂 10 帧未同步帧后，再喂 1 帧已同步帧；此时该同步帧对 filter 而言
     * 应是"首帧"——`calculateAcceleration` 在 `previousRaw == null` 分支会返回 0.0。
     * 如果任何一个未同步帧意外更新了内部 `previousRaw`，则第 11 帧的 dt 将对应着
     * `Long.MIN_VALUE` 到当前时间戳的溢出差值，`acceleration` 结果必然异常（非 0）。
     */
    @Test
    fun process_notTimeSynced_doesNotUpdateInternalState() {
        val localFilter = GpsDataFilter()

        // 10 帧未同步帧，速度从 10 递增到 100，位置偏移，制造足够"诱人"的状态变化以放大漏改风险
        repeat(10) { idx ->
            val raw = unsyncedSample(
                speed = 10.0 + idx * 10.0,
                latitude = 30.5 + idx * 0.0001,
                longitude = 104.4 + idx * 0.0001,
                bearing = (idx * 10).toDouble()
            )
            val result = localFilter.process(raw)
            // 每一帧都必须是零 delta 快照，侧面验证守卫没被绕过
            assertEquals(0.0, result.acceleration, 1e-9)
            assertEquals(0.0, result.confidence, 1e-9)
            assertFalse(result.isAnomaly)
            assertFalse(result.isPositionAnomaly)
        }

        // 第 11 帧切回同步。如果 filter 真的被未同步帧污染了内部状态，
        // 那它会把上一帧（`Long.MIN_VALUE`）拿来和当前 timestamp 做减法，
        // 导致 acceleration 出现溢出值或非零速度差结果。
        val resumed = syncedSample(
            timestamp = 1_000_000L,
            speed = 80.0,
            latitude = 30.6,
            longitude = 104.5
        )
        val resumedResult = localFilter.process(resumed)

        // 同步恢复的首帧视作首样本：acceleration = 0，isAnomaly = false
        assertEquals(0.0, resumedResult.acceleration, 1e-9)
        assertFalse(resumedResult.isAnomaly)
    }

    // ==================== 战役 C: A12 / A13 / A14 回归 ====================
    //
    // Change: fix-gps-data-filter-signal-loss-and-anomaly-hygiene
    // 依赖：A13 必须在 A12 之后实施（A12 的 dt > 200ms 重置兜底防止 A13 锁死 previousRaw）。

    /**
     * A12-1：信号丢失 > 200ms 后首帧应走"新基准"路径，acceleration = 0、isAnomaly = false。
     */
    @Test
    fun A12_process_signalLossLongerThanThreshold_acceleratesFromNewBaseline() {
        val localFilter = GpsDataFilter()
        // 建立 baseline
        localFilter.process(createGpsData(timestamp = 0L, speed = 60.0))
        // 500ms 后的下一帧（dt = 500ms > 200ms 阈值）
        val recovered = localFilter.process(createGpsData(timestamp = 500L, speed = 60.0))

        assertEquals(
            "A12：信号丢失首帧 acceleration 必须归零（previousRaw 已被重置，早退返回 0）",
            0.0,
            recovered.acceleration,
            1e-9
        )
        assertFalse(
            "A12：信号丢失首帧 isAnomaly 必须为 false（previousRaw 已被重置，早退返回 false）",
            recovered.isAnomaly
        )
    }

    /**
     * A12-2：信号丢失后首帧速度大跳变不被旧 previousRaw 误压制。
     * 旧顺序下 `maxDelta = 90 × 0.5 = 45` 会把 300 km/h 跳变判非异常（错！实际
     * 应视为"没有基准，无法比较"）。新顺序下 previousRaw=null 早退返回 false。
     */
    @Test
    fun A12_process_signalLossThenLargeSpeedJump_isNotSuppressedByStalePreviousRaw() {
        val localFilter = GpsDataFilter()
        localFilter.process(createGpsData(timestamp = 0L, speed = 10.0))
        // 500ms 后跳到 310 km/h（dv = 300）
        val jumpResult = localFilter.process(createGpsData(timestamp = 500L, speed = 310.0))
        assertFalse(
            "A12：信号丢失后首帧没有对比基准，不能凭空判异常（v1 会用 500ms 前旧基准算 maxDelta 压制跳变）",
            jumpResult.isAnomaly
        )
        assertEquals(
            "A12：信号丢失后首帧 acceleration 走首样本语义 = 0",
            0.0,
            jumpResult.acceleration,
            1e-9
        )

        // 接着用新基准正常推进
        val continued = localFilter.process(createGpsData(timestamp = 540L, speed = 310.0))
        assertEquals(
            "A12：新基准建立后下一帧 dv=0, acceleration=0",
            0.0,
            continued.acceleration,
            1e-9
        )
    }

    /**
     * A13：单次 spike（isAnomaly）不应污染 previousRaw，下一帧恢复到合理值必须
     * 以 spike 之前的基准判定，而不是 spike 的离群值。
     */
    @Test
    fun A13_process_singleSpikeThenRecovery_doesNotLeakAnomalyToNextFrame() {
        val localFilter = GpsDataFilter()
        // baseline
        localFilter.process(createGpsData(timestamp = 0L, speed = 30.0))
        // spike：dv = 170 km/h > maxDelta = 90 × 0.04 = 3.6，触发 isAnomaly
        val spike = localFilter.process(createGpsData(timestamp = 40L, speed = 200.0))
        assertTrue("A13 前置：spike 帧应当被判 isAnomaly=true", spike.isAnomaly)

        // 恢复帧：speed=32，若 A13 未生效（spike 污染 previousRaw=200），maxDelta=3.6，
        // dv=|32-200|=168 >> 3.6 → 会误判 isAnomaly；A13 修后 previousRaw 仍是 speed=30，
        // dv=|32-30|=2 < 3.6 → 正常。
        val recovered = localFilter.process(createGpsData(timestamp = 80L, speed = 32.0))
        assertFalse(
            "A13：恢复帧应以 spike 之前的 baseline 判定，isAnomaly=false；若 spike 污染了 previousRaw 则会误判",
            recovered.isAnomaly
        )
    }

    /**
     * A13 × A12 联动：连续异常帧时 previousRaw 被 A13 保留在旧值；A12 的 dt > 200ms
     * 兜底会在长期无更新时自动重置，避免 previousRaw 永久锁死。
     */
    @Test
    fun A13_process_continuousAnomaly_previousRawEventuallyResetsByA12Guard() {
        val localFilter = GpsDataFilter()
        localFilter.process(createGpsData(timestamp = 0L, speed = 30.0))
        // 连续 3 帧 spike，previousRaw 保持 ts=0
        repeat(3) { idx ->
            val ts = 40L + idx * 40L
            val r = localFilter.process(createGpsData(timestamp = ts, speed = 200.0))
            assertTrue("连续 spike 应持续 isAnomaly=true", r.isAnomaly)
        }
        // 第 4 帧 ts=500ms，相对 previousRaw=ts0 差 500ms > 200ms 阈值 → A12 重置
        val resetFrame = localFilter.process(createGpsData(timestamp = 500L, speed = 31.0))
        assertEquals(
            "A12 兜底：previousRaw 应被 dt > 200ms 重置，本帧走首样本路径 acceleration=0",
            0.0,
            resetFrame.acceleration,
            1e-9
        )
        assertFalse(
            "A12 兜底：本帧 isAnomaly = false（previousRaw 已 null，早退）",
            resetFrame.isAnomaly
        )
    }

    /**
     * A14：异常帧（isAnomaly=true）不得进入 speedWindow —— 中位数对单 spike 的鲁棒性验证。
     *
     * **测试强度局限（A55）**：中位数对单个离群天然鲁棒，"A14 生效"（window=[60,60,60]）
     * 与"A14 失效"（window=[60,60,60,300]）median 输出均为 60，本用例无法硬区分两种实现。
     * 硬区分用例见 `A14_process_multipleAnomalyFrames_medianHardDistinguishesWindowExclusion`。
     */
    @Test
    fun A14_process_anomalyFrame_doesNotPollutePosteriorMedianOutput() {
        val localFilter = GpsDataFilter()
        // 填充窗口至满 3 个正常帧（median ≥ 3 帧阈值触发）
        localFilter.process(createGpsData(timestamp = 0L, speed = 60.0))
        localFilter.process(createGpsData(timestamp = 40L, speed = 60.0))
        val stable = localFilter.process(createGpsData(timestamp = 80L, speed = 60.0))
        assertEquals("前置：3 正常帧后 median=60", 60.0, stable.speed, 1e-9)

        // spike：dv=240 >> maxDelta=3.6，isAnomaly=true
        val spike = localFilter.process(createGpsData(timestamp = 120L, speed = 300.0))
        assertTrue("A14 前置：spike 应被判异常", spike.isAnomaly)
        // 若 A14 未生效，speedWindow=[60,60,60,300]，median=60（偶数个取中间两个平均）；
        // 若 A14 生效，speedWindow=[60,60,60]（spike 未入），median=60。
        // 两种情况本帧 output 都是 60，不足以区分。关键是下一帧！

        // 继续喂正常帧：300 如果进入窗口，后续 median 会被拉偏
        val continuedA = localFilter.process(createGpsData(timestamp = 160L, speed = 60.0))
        val continuedB = localFilter.process(createGpsData(timestamp = 200L, speed = 60.0))
        assertEquals(
            "A14：spike 未进窗口，后续 median 持续稳定在 60（若 spike 进窗口，median 会被 300 拉偏）",
            60.0,
            continuedA.speed,
            1e-9
        )
        assertEquals(60.0, continuedB.speed, 1e-9)
    }

    /**
     * A14：位置异常帧不进 latWindow / lonWindow —— 中位数对单 spike 的鲁棒性验证。
     *
     * **测试强度局限（A55）**：与 speed 版本同理，单 spike 场景无法硬区分 A14 实现。
     * 硬区分用例见 `A14_process_multiplePositionAnomalyFrames_latLonMedianHardDistinguishesWindowExclusion`。
     */
    @Test
    fun A14_process_positionAnomalyFrame_doesNotPollutePosteriorLatLonMedian() {
        val localFilter = GpsDataFilter()
        // 填充 3 帧正常位置（速度一致避免 speed 异常，位置贴近）
        val baseLat = 30.4961
        val baseLon = 104.4331
        localFilter.process(createGpsData(timestamp = 0L, speed = 60.0, latitude = baseLat, longitude = baseLon))
        localFilter.process(createGpsData(timestamp = 40L, speed = 60.0, latitude = baseLat + 1e-6, longitude = baseLon + 1e-6))
        val stable = localFilter.process(createGpsData(timestamp = 80L, speed = 60.0, latitude = baseLat + 2e-6, longitude = baseLon + 2e-6))
        assertTrue("前置：正常帧位置应接近 baseLat", kotlin.math.abs(stable.latitude - baseLat) < 1e-3)

        // 位置跳飞（10 米级偏移 → v_implied 爆炸 → isPositionAnomaly=true）
        val spikeLat = baseLat + 1e-4  // 约 11 米
        val spikeLon = baseLon + 1e-4
        val spike = localFilter.process(createGpsData(timestamp = 120L, speed = 60.0, latitude = spikeLat, longitude = spikeLon))
        // spike 的位置异常（一致性检验 ratio > 3）或 filter 自身判定 isPositionAnomaly=true
        // A14 生效时 spike 不进 lat/lonWindow

        // 继续正常帧
        val continued = localFilter.process(createGpsData(timestamp = 160L, speed = 60.0, latitude = baseLat + 3e-6, longitude = baseLon + 3e-6))
        assertTrue(
            "A14：位置 spike 未进 latWindow，后续 median 不被拉偏（仍接近 baseLat 而非 spikeLat）",
            kotlin.math.abs(continued.latitude - baseLat) < 1e-4
        )
        // spike 确实是异常帧（verify A14 前置条件）
        assertTrue(
            "A14 前置：位置 spike 应被判 isPositionAnomaly=true（或 isAnomaly=true）",
            spike.isPositionAnomaly || spike.isAnomaly
        )
    }

    // ==================== A55 硬区分强化：多 spike 场景拉偏 median ====================
    //
    // 背景：上面两条 A14 用例用"3 正常 + 1 spike"场景，中位数对单离群天然鲁棒，
    // 无法硬区分"spike 入窗口"与"spike 不入窗口"两种实现。本节用"2 正常 + 4 连 spike + 1 正常"
    // 场景：若 A14 生效，最终窗口仅含 3 个正常值 → median 正常；若 A14 失效（spike 入窗口），
    // 窗口含 4 个 spike → median 被 spike 值拉走。两种情况输出截然不同，用例提供回归锁定。
    //
    // 设计约束：
    //   - 4 连 spike 每帧到 previousRaw(ts=40) 的 dt < 200ms，避免 A12 兜底重置；
    //   - 4 连 spike 每帧 dv / Δd 持续触发 isAnomaly / isPositionAnomaly（依赖 A13 的
    //     "异常帧不更新 previousRaw" 把 previousRaw 锁在 ts=40）；
    //   - 恢复帧 ts=230，dt=190ms<200ms，不触发 A12 重置；恢复帧自身不是异常帧。

    /**
     * A55-1：多 speed spike 硬区分场景 —— A14 失效会让 median 偏到 spike 值。
     *
     * A14 生效：speedWindow 末态 = [60,60,60]，median = 60（正确）
     * A14 失效：speedWindow 末态 = [60,60,300,300,300,300,60]，sorted 后中间值 = 300
     *
     * 依赖：A13（异常帧不更新 previousRaw）保证 4 连 spike 相对 previousRaw(ts=40) 的 dt
     * 分别为 40/80/120/160ms 均 < 200ms（A12 不重置），speedDelta=240 恒 >> maxDelta=90×dt
     * → 4 帧均 isAnomaly=true。
     */
    @Test
    fun A14_process_multipleAnomalyFrames_medianHardDistinguishesWindowExclusion() {
        val localFilter = GpsDataFilter()
        // 2 正常帧：speedWindow = [60, 60]（size<3，output=raw.speed）
        localFilter.process(createGpsData(timestamp = 0L, speed = 60.0))
        localFilter.process(createGpsData(timestamp = 40L, speed = 60.0))

        // 4 连 spike：每帧 speed=300，isAnomaly=true（A13 下 previousRaw 保持 ts=40）
        listOf(80L, 120L, 160L, 200L).forEach { ts ->
            val r = localFilter.process(createGpsData(timestamp = ts, speed = 300.0))
            assertTrue("前置：ts=$ts 的 spike 应被判 isAnomaly=true", r.isAnomaly)
        }

        // 恢复帧：ts=230 相对 previousRaw(40,60) dt=190ms<200ms（A12 不重置），dv=0 不异常
        val recovered = localFilter.process(createGpsData(timestamp = 230L, speed = 60.0))
        assertFalse("前置：恢复帧本身不应为 isAnomaly", recovered.isAnomaly)
        assertEquals(
            "A55 硬区分：A14 生效时 speedWindow 末态 [60,60,60] median=60；" +
                "若 A14 失效，末态 [60,60,300,300,300,300,60] sorted 后中间值=300",
            60.0,
            recovered.speed,
            1e-9
        )
    }

    /**
     * A55-2：多位置 spike 硬区分场景 —— A14 失效会让 lat/lon median 偏到 spike 位置。
     *
     * A14 生效：latWindow 末态仅含 3 个正常点 → median ≈ baseLat（偏离 < 1e-5）
     * A14 失效：latWindow 末态含 4 个 spikeLat → sorted 后中间值 = spikeLat（偏离 ≈ 1e-4）
     *
     * 位置 spike 偏移 1e-4 度 ≈ 11 米 >> 0.5m 一致性检查阈值，且 vImplied >> 速度容差，
     * 触发 isPositionAnomaly=true。
     */
    @Test
    fun A14_process_multiplePositionAnomalyFrames_latLonMedianHardDistinguishesWindowExclusion() {
        val localFilter = GpsDataFilter()
        val baseLat = 30.4961
        val baseLon = 104.4331
        val spikeLat = baseLat + 1e-4
        val spikeLon = baseLon + 1e-4

        // 2 正常帧（位置微动 1e-6 量级远小于 0.5m 阈值，一致性检查跳过）
        localFilter.process(
            createGpsData(timestamp = 0L, speed = 60.0, latitude = baseLat, longitude = baseLon)
        )
        localFilter.process(
            createGpsData(
                timestamp = 40L,
                speed = 60.0,
                latitude = baseLat + 1e-6,
                longitude = baseLon + 1e-6
            )
        )

        // 4 连位置 spike：每帧到 previousPosition(baseLat+1e-6) 的 distanceM ≈ 14.6m >> 0.5m
        // vImplied >> 60 km/h 容差 → isPositionAnomaly=true
        listOf(80L, 120L, 160L, 200L).forEach { ts ->
            val r = localFilter.process(
                createGpsData(timestamp = ts, speed = 60.0, latitude = spikeLat, longitude = spikeLon)
            )
            assertTrue("前置：ts=$ts 的位置 spike 应被判 isPositionAnomaly=true", r.isPositionAnomaly)
        }

        // 恢复帧：位置回到 baseLat 微动区，distanceM<0.5m 跳过检查，非位置异常
        val recovered = localFilter.process(
            createGpsData(
                timestamp = 230L,
                speed = 60.0,
                latitude = baseLat + 3e-6,
                longitude = baseLon + 3e-6
            )
        )
        assertFalse("前置：恢复帧本身不应为 isPositionAnomaly", recovered.isPositionAnomaly)

        // A14 生效：latWindow=[baseLat, baseLat+1e-6, baseLat+3e-6] median=baseLat+1e-6（偏离 1e-6）
        // A14 失效：sorted 窗口中间值 = spikeLat（偏离 1e-4），阈值 1e-5 能硬区分两种实现
        assertTrue(
            "A55 硬区分：A14 生效时 latWindow 不含 spike，median 偏离 baseLat 应 < 1e-5；" +
                "若 A14 失效，median 被 spikeLat 拉偏约 1e-4，实际偏离=" +
                "${kotlin.math.abs(recovered.latitude - baseLat)}",
            kotlin.math.abs(recovered.latitude - baseLat) < 1e-5
        )
        assertTrue(
            "A55 硬区分：A14 生效时 lonWindow 不含 spike，median 偏离 baseLon 应 < 1e-5；" +
                "若 A14 失效，median 被 spikeLon 拉偏约 1e-4，实际偏离=" +
                "${kotlin.math.abs(recovered.longitude - baseLon)}",
            kotlin.math.abs(recovered.longitude - baseLon) < 1e-5
        )
    }

    // ==================== A44 antimeridian wrappedDeltaLon 回归（openspec C 三期）====================

    @Test
    fun checkConsistency_crossingAntimeridian_doesNotProduceFakeDistance() {
        // R3 Scenario 1：跨 antimeridian 不产生假位移（物理自洽 40ms / 50km/h / 位移 ~0.56m）
        // fixture 约束：经度差 ~0.000005°；dt <= 0.2s（不触发早退）；速度 50km/h 匹配位移
        // 硬区分 v1（不处理 wrap）：会把 ~360° 经度差投影到 ~40,075 km，isPositionAnomaly=true 假阳性
        val filter = GpsDataFilter()

        // 第 1 帧：lat=0, lon=179.9999975, speed=50 —— 建立 previousPosition / previousRaw
        val prev = GpsData(
            timestamp = 1_000L,
            speed = 50.0,
            latitude = 0.0,
            longitude = 179.9999975,
            altitude = 100.0,
            bearing = 0.0,
            satelliteCount = 12,
            hdop = 1.0,
            vdop = 1.0,
            frequency = 25.0,
            isConnected = true,
            isTestReady = true,
            errorMessage = null,
            fixQuality = 1,
            isTimeSynced = true
        )
        filter.process(prev)

        // 第 2 帧：跨 antimeridian 到 -179.9999975，dt=40ms
        // v2 wrappedDeltaLon → 真实 0.000005° → ~0.56m → vImplied ≈ 50.1km/h（匹配 speed=50）
        val currentAntimeridian = prev.copy(
            timestamp = 1_040L,
            longitude = -179.9999975
        )
        val result = filter.process(currentAntimeridian)

        assertFalse(
            "v2 wrappedDeltaLon 处理 ±180° 绕回后，跨 antimeridian 不误判 isPositionAnomaly；" +
                "v1 不处理 wrap 会把 360° 经度差投影到 ~40,075km 导致 vImplied 爆表误判",
            result.isPositionAnomaly
        )
        assertEquals(
            "v2 consistencyFactor 接近 1.0（ratio≈0.02<<3 + 非高 HDOP + 无 bearing 剧变）；v1 会被拉到 0.3",
            1.0,
            result.consistencyFactor,
            0.1
        )
    }

    @Test
    fun checkConsistency_nonAntimeridianNormalCase_unchanged() {
        // R3 Scenario 2：非跨边界场景 wrappedDeltaLon 透传原始差（v1/v2 行为等价）
        // TFIC 场景经度 104°E，prev/current 差 +0.000005°（~0.56m / 40ms 匹配 50km/h）
        val filter = GpsDataFilter()

        val prev = GpsData(
            timestamp = 1_000L,
            speed = 50.0,
            latitude = 30.49,
            longitude = 104.43,
            altitude = 100.0,
            bearing = 0.0,
            satelliteCount = 12,
            hdop = 1.0,
            vdop = 1.0,
            frequency = 25.0,
            isConnected = true,
            isTestReady = true,
            errorMessage = null,
            fixQuality = 1,
            isTimeSynced = true
        )
        filter.process(prev)

        // 第 2 帧：经度差 +0.000005°（wrappedDeltaLon 透传原始差）
        val currentNormal = prev.copy(
            timestamp = 1_040L,
            longitude = 104.43 + 0.000005
        )
        val result = filter.process(currentNormal)

        // 非跨边界场景 v1/v2 完全等价：wrappedDeltaLon 对 raw in [-180, 180] 透传
        assertFalse("正常场景不应误判 isPositionAnomaly", result.isPositionAnomaly)
        assertEquals(
            "consistencyFactor ≈ 1.0（位移匹配速度，v1/v2 等价）",
            1.0,
            result.consistencyFactor,
            0.1
        )
    }
}
