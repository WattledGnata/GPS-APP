// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.feature.test.ui.tracktech.SectorColorClass.SectorColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SectorColorClass.sectorColorClass 分段配色纯函数单测。
 *
 * 覆盖三态 + 边界：
 * - 全场最快段 → PURPLE（优先级最高）
 * - 比 best lap 对应段快但非全场最快 → GREEN
 * - 既非全场最快也不快于 best lap → WHITE
 * - 并列最快（== overallBest）→ PURPLE
 * - == bestLap（不快不慢）→ WHITE（improved 要求严格快）
 * - 基准 null（无完整圈 / 无 best lap）降级
 *
 * @author CC
 * @description sector split coloring pure-function tests
 * @date 2026-05-31
 */
class SectorColorClassTest {

    @Test
    fun `全场最快段 - PURPLE`() {
        // sector=20000，overallBest=20000，bestLap=22000 → 紫（全场最快压过绿）
        assertEquals(
            SectorColor.PURPLE,
            SectorColorClass.sectorColorClass(20_000L, 20_000L, 22_000L),
        )
    }

    @Test
    fun `严格快于全场最快（理论上不会但容错）- PURPLE`() {
        // sector=19000 < overallBest=20000（数据异常时仍判紫，<= 容错）
        assertEquals(
            SectorColor.PURPLE,
            SectorColorClass.sectorColorClass(19_000L, 20_000L, 22_000L),
        )
    }

    @Test
    fun `比最快圈对应段快但非全场最快 - GREEN`() {
        // sector=21000，overallBest=20000（不是最快），bestLap=22000（比它快）→ 绿
        assertEquals(
            SectorColor.GREEN,
            SectorColorClass.sectorColorClass(21_000L, 20_000L, 22_000L),
        )
    }

    @Test
    fun `慢于最快圈对应段且非全场最快 - WHITE`() {
        // sector=23000，overallBest=20000，bestLap=22000（比它慢）→ 白
        assertEquals(
            SectorColor.WHITE,
            SectorColorClass.sectorColorClass(23_000L, 20_000L, 22_000L),
        )
    }

    @Test
    fun `等于最快圈对应段（不快不慢）且非全场最快 - WHITE`() {
        // sector == bestLap=22000：improved 要求严格快 → 不是绿；非全场最快 → 白
        assertEquals(
            SectorColor.WHITE,
            SectorColorClass.sectorColorClass(22_000L, 20_000L, 22_000L),
        )
    }

    @Test
    fun `并列全场最快（多圈同段同 ms）- PURPLE`() {
        // sector == overallBest=20000：并列最快也显紫
        assertEquals(
            SectorColor.PURPLE,
            SectorColorClass.sectorColorClass(20_000L, 20_000L, 21_000L),
        )
    }

    @Test
    fun `overallBest 为 null（无完整圈基准）但快于 bestLap - GREEN`() {
        assertEquals(
            SectorColor.GREEN,
            SectorColorClass.sectorColorClass(21_000L, null, 22_000L),
        )
    }

    @Test
    fun `overallBest 与 bestLap 均为 null - WHITE`() {
        // 无任何基准 → 全部白（降级）
        assertEquals(
            SectorColor.WHITE,
            SectorColorClass.sectorColorClass(21_000L, null, null),
        )
    }

    @Test
    fun `仅 overallBest 命中（bestLap null）- PURPLE`() {
        assertEquals(
            SectorColor.PURPLE,
            SectorColorClass.sectorColorClass(20_000L, 20_000L, null),
        )
    }
}
