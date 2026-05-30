// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.TelemetryCrossingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * lap-session-theoretical-best round · computeTheoreticalBest 纯函数单测。
 *
 * @author CC
 * @description 验证跨圈各 sector 最快段拼接的理论最优圈 + 每 sector 最快圈号
 * @date 2026-05-30
 */
class LapSessionTheoreticalBestTest {

    private fun cross(gateType: String, wc: Long, accepted: Boolean = true) = TelemetryCrossingEvent(
        sessionId = "s",
        lapIndex = 0,
        crossingTimestampMs = wc,
        crossingWallClockTimestampMs = wc,
        speedKmh = 100.0,
        gateId = gateType,
        gateType = gateType,
        accepted = accepted,
        reason = "",
        directionScore = null,
    )

    private fun sf(wc: Long, accepted: Boolean = true) = cross("StartFinish", wc, accepted)
    private fun sec(wc: Long, accepted: Boolean = true) = cross("Sector", wc, accepted)

    @Test
    fun twoLaps_stitchesFastestSectorPerLap() {
        // Lap1 splits [500,1300,1200] (=3000), Lap2 splits [400,1500,1100] (=3000)
        val crossings = listOf(
            sf(1000), sec(1500), sec(2800),
            sf(4000), sec(4400), sec(5900),
            sf(7000),
        )
        val tb = computeTheoreticalBest(crossings)!!
        assertEquals(2800L, tb.totalMs) // 400(L2)+1300(L1)+1100(L2)
        assertEquals(3, tb.sectors.size)
        assertEquals(400L, tb.sectors[0].bestMs); assertEquals(2, tb.sectors[0].lapNumber)
        assertEquals(1300L, tb.sectors[1].bestMs); assertEquals(1, tb.sectors[1].lapNumber)
        assertEquals(1100L, tb.sectors[2].bestMs); assertEquals(2, tb.sectors[2].lapNumber)
    }

    @Test
    fun rejectedSectorCrossing_excluded() {
        // L2 加一个 rejected sector @4100；若误纳入 L2 会变 4 段 → 与 L1 的 3 段不一致 → L2 非完整圈 →
        // 仅 L1 完整 → 理论最佳退化成 L1=3000。断言 2800 锁死 rejected 被排除。
        val crossings = listOf(
            sf(1000), sec(1500), sec(2800),
            sf(4000), sec(4100, accepted = false), sec(4400), sec(5900),
            sf(7000),
        )
        assertEquals(2800L, computeTheoreticalBest(crossings)!!.totalMs)
    }

    @Test
    fun singleLapWithSectors_theoreticalEqualsThatLap() {
        val tb = computeTheoreticalBest(listOf(sf(1000), sec(1500), sec(2800), sf(4000)))!!
        assertEquals(3000L, tb.totalMs)
        assertEquals(listOf(1, 1, 1), tb.sectors.map { it.lapNumber })
    }

    @Test
    fun noSectorGates_returnsNull() {
        // 只有 StartFinish，每圈 1 段（无 sector 门）→ sectorCount < 2 → null（不显示理论最佳）
        assertNull(computeTheoreticalBest(listOf(sf(1000), sf(4000), sf(7000))))
    }

    @Test
    fun lessThanTwoStartFinish_returnsNull() {
        assertNull(computeTheoreticalBest(listOf(sf(1000), sec(1500))))
    }
}
