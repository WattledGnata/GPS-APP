// @IgnoreFormatCheck
package com.blazepush.core.domain.model

import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.FilteredGpsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @description markStarted 的 pre-trigger buffer 并入回归测试(2026-06-04 修复:
 *              原实现 buffer 只进 filteredDataPoints 不进 dataPoints,成绩链路丢失
 *              静止起步锚点 → 0-100 结构性 DNF)。
 * @author CC
 * @date 2026-06-04
 */
class TestSessionMarkStartedTest {

    private fun filtered(tsMs: Long, speedKmh: Double) = FilteredGpsData(
        speed = speedKmh,
        latitude = 30.0,
        longitude = 104.0,
        altitude = 500.0,
        bearing = 0.0,
        acceleration = 0.0,
        confidence = 1.0,
        isAnomaly = false,
        timestamp = tsMs,
        raw = GpsData.Empty,
    )

    private fun newSession() = TestSession(
        id = "s",
        template = TestTemplate.Acceleration0To100,
        carModel = "car",
        startTime = 10_000L,
    )

    @Test
    fun `buffer 帧必须进入 dataPoints 且 elapsed 为负单调`() {
        val session = newSession()
        val buffer = (0 until 10).map { filtered(tsMs = 10_000L + it * 200L, speedKmh = 0.3) } // 5Hz×2s 静止
        val trigger = filtered(tsMs = 12_000L, speedKmh = 5.0)

        session.markStarted(trigger, buffer)

        assertEquals("buffer(10)+触发点(1) 全进 dataPoints(旧 bug:只有触发点)", 11, session.dataPoints.size)
        assertEquals("首帧 elapsed = (10000-12000)/1000 = -2.0s", -2.0, session.dataPoints.first().elapsedTime, 1e-9)
        assertEquals("触发点 elapsed = 0", 0.0, session.dataPoints.last().elapsedTime, 1e-9)
        session.dataPoints.zipWithNext().forEach { (a, b) ->
            assertTrue("elapsed 单调递增", b.elapsedTime > a.elapsedTime)
        }
    }

    @Test
    fun `端到端 静止buffer提供起步锚点 成绩不再DNF`() {
        // 还原 2026-06-04 23:38 模拟器形态:armed 静止段在 buffer 里,触发时速度已爬高
        val session = newSession()
        val buffer = (0 until 10).map { filtered(10_000L + it * 200L, 0.3) } // 静止 2s(锚点之源)
        session.markStarted(trigger = filtered(12_000L, 30.0), preTriggerBuffer = buffer)
        // Running 帧:30 → 110(过 100)
        (1..9).forEach { i -> session.addFilteredDataPoint(filtered(12_000L + i * 200L, 30.0 + i * 10.0)) }

        val result = CalculateResultUseCase()(session, dataFilePath = "")

        assertTrue(
            "buffer 提供上穿 1.0 锚点后必须产出正成绩(旧 bug:锚点丢失恒 DNF),实际 totalTime=${result.totalTime}",
            result.totalTime > 0.1,
        )
    }

    private fun TestSession.markStarted(trigger: FilteredGpsData, preTriggerBuffer: List<FilteredGpsData>) =
        markStarted(trigger, preTriggerBuffer)
}
