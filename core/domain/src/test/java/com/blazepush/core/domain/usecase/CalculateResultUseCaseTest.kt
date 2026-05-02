package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TestTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CalculateResultUseCase max* 字段拆分单测。
 *
 * 锁定 spec.md `Requirement: TestResult / TestRecordEntity 区分 maxAcceleration 与 maxDeceleration`：
 * - 0-100 加速：maxAcceleration > 0、maxDeceleration == 0.0
 * - 100-0 制动：maxAcceleration == 0.0、maxDeceleration > 0
 *
 * @author CC
 * @description max* 字段拆分单测
 * @date 2026-05-03
 */
class CalculateResultUseCaseTest {

    private val useCase = CalculateResultUseCase()

    private fun buildSession(
        template: TestTemplate,
        speedSeriesKmh: List<Double>,
        dtMs: Long = 40L,
    ): TestSession {
        val session = TestSession(
            id = "test-session",
            template = template,
            carModel = "TestCar",
            startTime = 0L,
        )
        speedSeriesKmh.forEachIndexed { i, speed ->
            session.dataPoints.add(
                GpsDataPoint(
                    elapsedTime = i * dtMs / 1000.0,
                    speed = speed,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = 0.0,
                ),
            )
        }
        return session
    }

    @Test
    fun `0-100 加速 maxAcceleration 大于 0 maxDeceleration 等于 0`() {
        // 30 帧 25Hz 单调加速：0 → 116 km/h，约 7.5s（典型 0-100 测试时长）
        val series = (0..29).map { i -> i * 4.0 }
        val session = buildSession(TestTemplate.Acceleration0To100, series)

        val result = useCase(session, dataFilePath = "")

        assertTrue("maxAcceleration 应 > 0：${result.maxAcceleration}", result.maxAcceleration > 0.1)
        assertEquals("纯加速段 maxDeceleration 应为 0.0", 0.0, result.maxDeceleration, 1e-6)
    }

    @Test
    fun `100-0 制动 maxDeceleration 大于 0 maxAcceleration 等于 0`() {
        // 25 帧 25Hz 单调制动：100 → 0 km/h，约 4s
        val series = (0..24).map { i -> 100.0 - i * 4.0 }
        val session = buildSession(TestTemplate.Braking100To0, series)

        val result = useCase(session, dataFilePath = "")

        assertEquals("纯制动段 maxAcceleration 应为 0.0", 0.0, result.maxAcceleration, 1e-6)
        assertTrue("maxDeceleration 应 > 0：${result.maxDeceleration}", result.maxDeceleration > 0.1)
    }

    @Test
    fun `空 dataPoints 返回 empty result max 全 0`() {
        val session = TestSession(
            id = "empty",
            template = TestTemplate.Acceleration0To100,
            carModel = "TestCar",
            startTime = 0L,
        )
        val result = useCase(session, dataFilePath = "")

        assertEquals(0.0, result.maxAcceleration, 1e-6)
        assertEquals(0.0, result.maxDeceleration, 1e-6)
        assertEquals(0.0, result.avgAcceleration, 1e-6)
    }
}
