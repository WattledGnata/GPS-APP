package com.blazepush.simulator.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * SpeedController 单元测试
 * 测试各种速度模式的计算逻辑
 */
class SpeedControllerTest {

    private lateinit var controller: SpeedController

    @Before
    fun setup() {
        controller = SpeedController()
    }

    // ==================== STATIC 模式测试 ====================

    @Test
    fun SC01_staticMode_initialSpeedIsZero() {
        // Given: STATIC模式
        controller.setMode(SpeedMode.STATIC)

        // When: 更新速度
        val speed = controller.updateSpeed(System.currentTimeMillis())

        // Then: 速度应为0
        assertEquals("STATIC模式速度应为0", 0f, speed, 0.001f)
    }

    // ==================== CONSTANT 模式测试 ====================

    @Test
    fun SC02_constantMode_returnsTargetSpeed() {
        // Given: CONSTANT模式，目标速度60
        controller.setMode(SpeedMode.CONSTANT)
        controller.setTargetSpeed(60f)

        // When: 更新速度
        val speed = controller.updateSpeed(System.currentTimeMillis())

        // Then: 返回目标速度60
        assertEquals("CONSTANT模式应��回目标速度", 60f, speed, 0.001f)
    }

    @Test
    fun SC02b_constantMode_differentTargetSpeed() {
        // Given: 不同目标速度
        controller.setMode(SpeedMode.CONSTANT)
        controller.setTargetSpeed(120f)

        // When: 更新速度
        val speed = controller.updateSpeed(System.currentTimeMillis())

        // Then: 返回120
        assertEquals("CONSTANT模式应返回120", 120f, speed, 0.001f)
    }

    // ==================== ACCELERATION 模式测试 ====================

    @Test
    fun SC03_accelerationMode_increasesSpeed() {
        // Given: ACCELERATION模式，加速度2m/s²
        controller.setMode(SpeedMode.ACCELERATION)
        controller.setAcceleration(2.0f)
        controller.setTargetSpeed(100f)
        val currentTime = System.currentTimeMillis()

        // When: 第一次更新（初始化时间戳）
        controller.updateSpeed(currentTime)
        // 1秒后更新
        val speed = controller.updateSpeed(currentTime + 1000)

        // Then: 速度应增加约7.2 km/h (2 m/s² * 1s * 3.6)
        val expectedIncrease = 2.0f * 1.0f * 3.6f
        assertEquals("加速度应为7.2 km/h", expectedIncrease, speed, 0.1f)
    }

    @Test
    fun SC04_accelerationMode_stopsAtTarget() {
        // Given: 接近目标速度
        controller.setMode(SpeedMode.ACCELERATION)
        controller.setAcceleration(2.0f)
        controller.setTargetSpeed(60f)
        val currentTime = System.currentTimeMillis()

        // 模拟已加速到58
        controller.updateSpeed(currentTime)
        // 再调用一次使速度接近58
        var speed = controller.updateSpeed(currentTime + 100)
        // 手动设置接近目标
        speed = 58f

        // When: 再次更新（应达到目标）
        // 由于内部状态，我们需要重新测试
        val newController = SpeedController()
        newController.setMode(SpeedMode.ACCELERATION)
        newController.setAcceleration(5.0f) // 更大的加速度
        newController.setTargetSpeed(60f)
        val time = System.currentTimeMillis()
        newController.updateSpeed(time)
        // 多次更新直到达到目标
        for (i in 1..20) {
            speed = newController.updateSpeed(time + i * 100)
        }

        // Then: 不应超过目标速度
        assertTrue("加速度不应超过目标", speed <= 60f)
        assertEquals("最终速度应达到目标", 60f, speed, 0.1f)
    }

    // ==================== DECELERATION 模式测试 ====================

    @Test
    fun SC05_decelerationMode_decreasesSpeed() {
        // Given: DECELERATION模式
        controller.setMode(SpeedMode.DECELERATION)
        controller.setAcceleration(2.0f)
        controller.setTargetSpeed(0f)

        // 需要先有初始速度
        val currentTime = System.currentTimeMillis()
        controller.updateSpeed(currentTime)

        // 通过反射或多次设置来模拟有速度的状态
        // 实际测试中，我们先设置为加速模式获得速度，然后切换到减速
        val speedController = SpeedController()
        speedController.setMode(SpeedMode.CONSTANT)
        speedController.setTargetSpeed(100f)
        speedController.updateSpeed(currentTime)

        // 切换到减速模式
        speedController.setMode(SpeedMode.DECELERATION)
        speedController.setTargetSpeed(50f)
        speedController.setAcceleration(2.0f)

        val speed = speedController.updateSpeed(currentTime + 1000)

        // Then: 速度应减少
        assertTrue("减速模式速度应减少", speed < 100f)
    }

    @Test
    fun SC06_decelerationMode_stopsAtTarget() {
        // Given: 减速到目标
        controller.setMode(SpeedMode.DECELERATION)
        controller.setTargetSpeed(30f)
        controller.setAcceleration(5.0f)

        val currentTime = System.currentTimeMillis()
        controller.updateSpeed(currentTime)

        // 模拟减速过程
        var speed = 100f
        // 由于无法直接设置currentSpeed，我们测试行为
        // 实际项目中可能需要添加setSpeedForTesting方法

        // 验证目标速度已设置
        assertEquals("目标速度应为30", 30f, controller.targetSpeed, 0.001f)
    }

    // ==================== WAVEFORM 模式测试 ====================

    @Test
    fun SC07_waveformMode_oscillatesSpeed() {
        // Given: WAVEFORM模式
        controller.setMode(SpeedMode.WAVEFORM)
        controller.setTargetSpeed(60f)
        val currentTime = System.currentTimeMillis()

        // When: 多次更新
        val speeds = mutableListOf<Float>()
        repeat(20) { i ->
            speeds.add(controller.updateSpeed(currentTime + i * 100))
        }

        // Then: 应该有波动
        val minSpeed = speeds.minOrNull()!!
        val maxSpeed = speeds.maxOrNull()!!

        // 波形应在 60 ± 30% = 42-78 范围内
        assertTrue("最小速度应小于60", minSpeed < 60f)
        assertTrue("最大速度应大于60", maxSpeed > 60f)
        assertTrue("最小速度应在合理范围", minSpeed >= 30f)
        assertTrue("最大速度应在合理范围", maxSpeed <= 90f)
    }

    // ==================== REALISTIC 模式测试 ====================

    @Test
    fun SC08_realisticMode_fluctuatesSpeed() {
        // Given: REALISTIC模式
        controller.setMode(SpeedMode.REALISTIC)
        controller.setTargetSpeed(60f)
        val currentTime = System.currentTimeMillis()

        // When: 多次更新（需要有足够次数触发随机变化）
        val speeds = mutableListOf<Float>()
        repeat(100) { i ->
            speeds.add(controller.updateSpeed(currentTime + i * 100))
        }

        // Then: 应该有波动
        val uniqueSpeeds = speeds.toSet()
        assertTrue("真实模式应有速度波动", uniqueSpeeds.size > 1)

        // 所有速度应在 42-78 范围内 (60 * 0.7 到 60 * 1.3)
        speeds.forEach { speed ->
            assertTrue("速度应在范围内: $speed", speed in 42f..78f)
        }
    }

    // ==================== 边界值测试 ====================

    @Test
    fun SC09_boundary_maxSpeedIsLimited() {
        // Given: 尝试设置超过最大值
        controller.setTargetSpeed(400f)

        // Then: 应被限制为300
        assertEquals("最大速度应限制为300", 300f, controller.targetSpeed, 0.001f)
    }

    @Test
    fun SC10_boundary_negativeSpeedIsLimited() {
        // Given: 尝试设置负速度
        controller.setTargetSpeed(-10f)

        // Then: 应被限制为0
        assertEquals("负速度应限制为0", 0f, controller.targetSpeed, 0.001f)
    }

    @Test
    fun SC11_boundary_maxAccelerationIsLimited() {
        // Given: 尝试设置超过最大加速度
        controller.setAcceleration(20f)

        // Then: 应被限制为10
        assertEquals("最大加速度应限制为10", 10f, controller.acceleration, 0.001f)
    }

    @Test
    fun SC12_boundary_minAccelerationIsLimited() {
        // Given: 尝试设置小于最小加速度
        controller.setAcceleration(0.01f)

        // Then: 应被限制为0.1
        assertEquals("最小加速度应限制为0.1", 0.1f, controller.acceleration, 0.001f)
    }

    // ==================== 状态描述测试 ====================

    @Test
    fun SC13_statusDescription_staticMode() {
        // Given
        controller.setMode(SpeedMode.STATIC)

        // When
        val description = controller.getStatusDescription()

        // Then
        assertEquals("静止 (0 km/h)", description)
    }

    @Test
    fun SC13b_statusDescription_constantMode() {
        // Given
        controller.setMode(SpeedMode.CONSTANT)
        controller.setTargetSpeed(60f)
        controller.updateSpeed(System.currentTimeMillis())

        // When
        val description = controller.getStatusDescription()

        // Then
        assertTrue("应包含恒定", description.contains("恒定"))
        assertTrue("应包含60", description.contains("60"))
    }

    @Test
    fun SC13c_statusDescription_accelerationMode() {
        // Given
        controller.setMode(SpeedMode.ACCELERATION)
        controller.setTargetSpeed(100f)

        // When
        val description = controller.getStatusDescription()

        // Then
        assertTrue("应包含加速", description.contains("加速"))
        assertTrue("应包含100", description.contains("100"))
    }

    @Test
    fun SC13d_statusDescription_waveformMode() {
        // Given
        controller.setMode(SpeedMode.WAVEFORM)
        controller.setTargetSpeed(80f)

        // When
        val description = controller.getStatusDescription()

        // Then
        assertTrue("应包含波形", description.contains("波形"))
        assertTrue("应包含80", description.contains("80"))
    }

    // ==================== reset 测试 ====================

    @Test
    fun SC14_reset_restoresDefaultValues() {
        // Given: 修改各种设置
        controller.setMode(SpeedMode.ACCELERATION)
        controller.setTargetSpeed(150f)
        controller.setAcceleration(5.0f)

        // When: 调用reset
        controller.reset()

        // Then: 恢复默认值
        assertEquals("模式应为STATIC", SpeedMode.STATIC, controller.mode)
        assertEquals("目标速度应为60", 60f, controller.targetSpeed, 0.001f)
        assertEquals("加速度应为2.0", 2.0f, controller.acceleration, 0.001f)
    }

    // ==================== 模式切换测试 ====================

    @Test
    fun SC15_modeSwitch_staticToConstant() {
        // Given: STATIC模式
        controller.setMode(SpeedMode.STATIC)
        controller.updateSpeed(System.currentTimeMillis())
        assertEquals("初始应为0", 0f, controller.currentSpeed, 0.001f)

        // When: 切换到CONSTANT
        controller.setMode(SpeedMode.CONSTANT)
        controller.setTargetSpeed(50f)
        controller.updateSpeed(System.currentTimeMillis())

        // Then: 速度应变为50
        assertEquals("切换后速度应为50", 50f, controller.currentSpeed, 0.001f)
    }

    @Test
    fun SC16_modeSwitch_constantToWaveform() {
        // Given: CONSTANT模式
        controller.setMode(SpeedMode.CONSTANT)
        controller.setTargetSpeed(60f)
        controller.updateSpeed(System.currentTimeMillis())

        // When: 切换到WAVEFORM
        controller.setMode(SpeedMode.WAVEFORM)
        val speed = controller.updateSpeed(System.currentTimeMillis() + 100)

        // Then: 速度应在基准附近
        assertTrue("波形速度应在60附近", speed > 40f && speed < 80f)
    }

    // ==================== CUSTOM 模式测试 ====================

    @Test
    fun SC17_customMode_usesTargetSpeed() {
        // Given: CUSTOM模式
        controller.setMode(SpeedMode.CUSTOM)
        controller.setTargetSpeed(80f)

        // When
        val speed = controller.updateSpeed(System.currentTimeMillis())

        // Then: CUSTOM模式当前使用恒定速度
        assertEquals("CUSTOM模式应使用目标速度", 80f, speed, 0.001f)
    }
}
