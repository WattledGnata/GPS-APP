// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.LapConfidence
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapGapInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * unify-lap-count-pairing-semantics round 测试：deriveDetailMetrics（站点 B）纯函数验证。
 *
 * 锁死契约：detail 屏圈列表（UiLapRecord.lapNumber）MUST 按 crossingWallClockTimestampMs 排序配对，
 * 与 endSession（站点 A）/ getLapTelemetry（站点 C）同源。
 *
 * 覆盖：
 * 1. wallClock 升序 → 圈编号 1/2/3
 * 2. 跨时钟域分歧（GPS 序 ≠ wallClock 序）→ 按 wallClock 序配对（反例锁死 MUST NOT 用 GPS 时钟）
 * 3. 含 null wallClock → 有效圈列表 == 非空 wallClock 配对数
 *
 * 测试边界（v3 盲点 #11）：deriveDetailMetrics 在 feature/test module、getLapTelemetry 在 core/data
 * module，跨 module 不能同一 test class 同时引用两边。B 与 C 同源由"B/A 同排序键（本 case 验 B 排序键）
 * + A/C 同源（LapPairingCrossSiteConsistencyTest 验，core/data test source）"传递保证。
 *
 * @author CC
 * @description deriveDetailMetrics wallClock pairing tests
 * @date 2026-05-30
 */
class LapDetailMetricsDeriveTest {

    @Test
    fun `estimated faster lap remains comparable but cannot replace clean best`() {
        val crossings = listOf(
            acceptedSf(gpsTs = 100L, wallTs = 1_000L, idx = 0),
            acceptedSf(gpsTs = 200L, wallTs = 2_000L, idx = 1),
            acceptedSf(gpsTs = 300L, wallTs = 2_900L, idx = 2),
        )
        val clean = LapEvidence(
            startCrossingTimestampMillis = 100L,
            finishCrossingTimestampMillis = 200L,
            requiredGateIds = setOf("SF"),
            acceptedGateIds = setOf("SF"),
        )
        val estimated = LapEvidence(
            startCrossingTimestampMillis = 200L,
            finishCrossingTimestampMillis = 300L,
            requiredGateIds = setOf("SF"),
            acceptedGateIds = setOf("SF"),
            gaps = listOf(LapGapInterval(220L, 260L, setOf("SF"))),
        )

        val metrics = deriveDetailMetrics(crossings, mapOf(1 to clean, 2 to estimated))

        assertEquals(1_000L, metrics.bestLapMs)
        assertEquals(UiLapStatus.BEST, metrics.lapRecords[0].status)
        assertEquals(LapConfidence.Estimated, metrics.lapRecords[1].confidence)
        assertEquals(UiLapStatus.VALID, metrics.lapRecords[1].status)
    }

    private fun acceptedSf(
        gpsTs: Long,
        wallTs: Long?,
        idx: Int,
    ): TelemetryCrossingEvent = TelemetryCrossingEvent(
        sessionId = "s1",
        lapIndex = idx,
        crossingTimestampMs = gpsTs,
        speedKmh = 100.0,
        gateId = "sf",
        gateType = "StartFinish",
        accepted = true,
        reason = "Accepted",
        directionScore = null,
        crossingWallClockTimestampMs = wallTs,
    )

    private fun validLapNumbers(metrics: DetailMetrics): List<Int> =
        metrics.lapRecords
            .filter { it.status == UiLapStatus.VALID || it.status == UiLapStatus.BEST }
            .map { it.lapNumber }

    @Test
    fun `lap numbers use wallClock ordering`() {
        // 4 个 accepted SF，wallClock 升序 → 3 个有效相邻对 → Lap 1/2/3
        val crossings = listOf(
            acceptedSf(gpsTs = 100L, wallTs = 1000L, idx = 0),
            acceptedSf(gpsTs = 200L, wallTs = 2200L, idx = 1),
            acceptedSf(gpsTs = 300L, wallTs = 3300L, idx = 2),
            acceptedSf(gpsTs = 400L, wallTs = 4400L, idx = 3),
        )

        val metrics = deriveDetailMetrics(crossings)

        assertEquals(3, metrics.validLaps)
        assertEquals(listOf(1, 2, 3), validLapNumbers(metrics))
        // durations [1200, 1100, 1100] → best=1100
        assertNull("v9 history is Reviewed LegacyUnknown, never unconditional best", metrics.bestLapMs)
    }

    @Test
    fun `gps clock divergence does not change lap pairing`() {
        // GPS 序 c1<c2<c3，wallClock 序 c2<c3<c1（整点回绕模拟）。
        // 按 wallClock 排序 (c2,c3,c1) → durations [c3.wall-c2.wall, c1.wall-c3.wall]。
        // c2.wall=1700000000100, c3.wall=1700000000200, c1.wall=1700000000300。
        // durations = [100, 100] → 2 个有效圈，best=100。
        // 若误用 GPS 序 (c1,c2,c3)：durations 会含负值（c2.wall-c1.wall=-200）→ best 错。
        val crossings = listOf(
            acceptedSf(gpsTs = 100L, wallTs = 1700000000300L, idx = 0), // c1
            acceptedSf(gpsTs = 200L, wallTs = 1700000000100L, idx = 1), // c2
            acceptedSf(gpsTs = 300L, wallTs = 1700000000200L, idx = 2), // c3
        )

        val metrics = deriveDetailMetrics(crossings)

        assertEquals(2, metrics.validLaps)
        assertEquals(listOf(1, 2), validLapNumbers(metrics))
        // 锁死 MUST 用 wallClock：best=100（非负，非 GPS 序的 -200）
        assertNull("legacy crossings have no v1 evidence", metrics.bestLapMs)
    }

    @Test
    fun `null wallClock pairs excluded from lap list`() {
        // 5 个 accepted SF：前 2 个 wallClock=null（旧 row），后 3 个非空 5000/6100/7200。
        // wallClock 排序后非空 3 在前、null 2 排末尾 → 有效相邻对 (5000,6100),(6100,7200) = 2。
        val crossings = listOf(
            acceptedSf(gpsTs = 1000L, wallTs = null, idx = 0),
            acceptedSf(gpsTs = 2000L, wallTs = null, idx = 1),
            acceptedSf(gpsTs = 3000L, wallTs = 5000L, idx = 2),
            acceptedSf(gpsTs = 4000L, wallTs = 6100L, idx = 3),
            acceptedSf(gpsTs = 5000L, wallTs = 7200L, idx = 4),
        )

        val metrics = deriveDetailMetrics(crossings)

        assertEquals(2, metrics.validLaps)
        assertEquals(listOf(1, 2), validLapNumbers(metrics))
        assertNull("legacy crossings have no v1 evidence", metrics.bestLapMs)
    }
}
