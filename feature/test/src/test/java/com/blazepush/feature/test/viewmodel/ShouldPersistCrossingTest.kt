// @IgnoreFormatCheck
package com.blazepush.feature.test.viewmodel

import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.track.TimingGateType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @description fix-crossing-events-write-amplification:crossing_events 持久化过滤谓词单测
 *              (spec crossing-event-persistence-filter R1 三场景)。
 * @author CC
 * @date 2026-06-04
 */
class ShouldPersistCrossingTest {

    private fun event(accepted: Boolean, reason: CrossingReason) = CrossingEvent(
        gateId = "start-finish",
        gateType = TimingGateType.StartFinish,
        timestampMillis = 1780499135983L,
        sampleIndex = 0,
        accepted = accepted,
        reason = reason,
    )

    @Test
    fun `accepted 事件全部入库 真相源回归锁`() {
        assertTrue(shouldPersistCrossing(event(accepted = true, reason = CrossingReason.Accepted)))
    }

    @Test
    fun `NoIntersection 逐帧拒绝零入库 写放大回归锁`() {
        // 模拟 25Hz × 60s 常规帧:1500 个 NoIntersection 拒绝全部被滤
        repeat(1500) {
            assertFalse(
                "NoIntersection 拒绝 MUST NOT 入库(1.8 万行写放大回归)",
                shouldPersistCrossing(event(accepted = false, reason = CrossingReason.NoIntersection)),
            )
        }
    }

    @Test
    fun `有价值拒绝保留`() {
        listOf(
            CrossingReason.WrongDirection,
            CrossingReason.UnexpectedGateOrder,
            CrossingReason.TooSlow,
            CrossingReason.Cooldown,
        ).forEach { reason ->
            assertTrue(
                "$reason 拒绝(真实过线被拒)应保留诊断价值",
                shouldPersistCrossing(event(accepted = false, reason = reason)),
            )
        }
    }
}
