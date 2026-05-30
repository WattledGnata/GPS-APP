// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.TelemetryCrossingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ui-redo-lap-sector-table round · computeLapSectorTable 纯函数单测。
 *
 * 覆盖：(a) 两圈各取最快 sector 拼接（theoreticalTotalMs + bestLapPerSector 正确）
 * (b) rejected sector 排除 (c) 每圈 splits 对齐 sectorCount + 缺段补 null
 * (d) 无 sector 门 → null (e) <2 SF → null。
 *
 * @author CC
 * @description 验证圈 × sector 拆分表派生（与 deriveDetailMetrics/getLapTelemetry 同源 SF 配对）
 * @date 2026-05-30
 */
class LapSectorTableTest {

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
        // Lap1 splits [500,1300,1200] (lapTime=3000), Lap2 splits [400,1500,1100] (lapTime=3000)
        val crossings = listOf(
            sf(1000), sec(1500), sec(2800),
            sf(4000), sec(4400), sec(5900),
            sf(7000),
        )
        val table = computeLapSectorTable(crossings)!!
        assertEquals(3, table.sectorCount)
        // theoreticalTotalMs = 400(L2-S1) + 1300(L1-S2) + 1100(L2-S3) = 2800
        assertEquals(2800L, table.theoreticalTotalMs)
        assertEquals(listOf(400L, 1300L, 1100L), table.bestSplitPerSector)
        assertEquals(listOf(2, 1, 2), table.bestLapPerSector)
        // 两圈各一行，splits 完整
        assertEquals(2, table.laps.size)
        assertEquals(1, table.laps[0].lapNumber)
        assertEquals(3000L, table.laps[0].lapTimeMs)
        assertEquals(listOf<Long?>(500L, 1300L, 1200L), table.laps[0].splits)
        assertEquals(2, table.laps[1].lapNumber)
        assertEquals(3000L, table.laps[1].lapTimeMs)
        assertEquals(listOf<Long?>(400L, 1500L, 1100L), table.laps[1].splits)
    }

    @Test
    fun rejectedSectorCrossing_excluded() {
        // L2 加一个 rejected sector @4100；正确排除后 L2 仍为 3 段 [400,1500,1100]，与 L1 的 3 段一致，
        // 两圈皆完整 → bestSplitPerSector = 各 sector 跨圈最快 = [400(L2),1300(L1),1100(L2)] → 2800。
        // 反例锁死：若误纳入 rejected，L2 会变 4 段 → sectorCount=4 → 仅 L2 完整 → 结果会偏离 2800。
        val crossings = listOf(
            sf(1000), sec(1500), sec(2800),
            sf(4000), sec(4100, accepted = false), sec(4400), sec(5900),
            sf(7000),
        )
        val table = computeLapSectorTable(crossings)!!
        assertEquals(3, table.sectorCount)
        assertEquals(2800L, table.theoreticalTotalMs)
        assertEquals(listOf(400L, 1300L, 1100L), table.bestSplitPerSector)
        assertEquals(listOf(2, 1, 2), table.bestLapPerSector)
        // L2 的 splits 仍为 3 段（rejected sector 不计入边界）
        assertEquals(listOf<Long?>(400L, 1500L, 1100L), table.laps[1].splits)
    }

    @Test
    fun shortLap_splitsAlignedWithNullPadding() {
        // L1 有 2 个 sector 门（3 段），L2 只有 1 个 sector 门（2 段）→ sectorCount=3，
        // L2 末尾补 null；L2 非完整圈 → 不参与 bestSplitPerSector（仅 L1 完整）。
        val crossings = listOf(
            sf(1000), sec(1500), sec(2800),
            sf(4000), sec(4400),
            sf(7000),
        )
        val table = computeLapSectorTable(crossings)!!
        assertEquals(3, table.sectorCount)
        // L1 完整 3 段
        assertEquals(listOf<Long?>(500L, 1300L, 1200L), table.laps[0].splits)
        // L2 实际 2 段 [400, 2600]，末尾补 null 对齐 sectorCount=3
        assertEquals(listOf<Long?>(400L, 2600L, null), table.laps[1].splits)
        // 仅 L1 完整 → bestSplitPerSector = L1 各段
        assertEquals(listOf(500L, 1300L, 1200L), table.bestSplitPerSector)
        assertEquals(listOf(1, 1, 1), table.bestLapPerSector)
        assertEquals(3000L, table.theoreticalTotalMs)
    }

    @Test
    fun noSectorGates_returnsNull() {
        // 只有 StartFinish，每圈 1 段（无 sector 门）→ sectorCount < 2 → null（圈列表区 fallback）
        assertNull(computeLapSectorTable(listOf(sf(1000), sf(4000), sf(7000))))
    }

    @Test
    fun lessThanTwoStartFinish_returnsNull() {
        assertNull(computeLapSectorTable(listOf(sf(1000), sec(1500))))
    }

    @Test
    fun singleLapWithSectors_theoreticalEqualsThatLap() {
        val table = computeLapSectorTable(listOf(sf(1000), sec(1500), sec(2800), sf(4000)))!!
        assertEquals(3, table.sectorCount)
        assertEquals(3000L, table.theoreticalTotalMs)
        assertEquals(listOf(1, 1, 1), table.bestLapPerSector)
        assertEquals(listOf<Long?>(500L, 1300L, 1200L), table.laps[0].splits)
    }
}
