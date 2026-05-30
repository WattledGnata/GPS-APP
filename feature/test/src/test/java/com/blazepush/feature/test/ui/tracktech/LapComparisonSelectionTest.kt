// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 LapComparisonScreen 圈选择纯函数单测。
 *
 * computeDefaultSelection：BEST + 升序最多 3 个其他 VALID（≤4），可比较圈 < 2 → emptyList。
 * assignLapColors：[Purple, Cyan, Green, Red] 按选中顺序分配。
 * toggleLapSelection：[2,4] 约束（剩 2 不减 / 满 4 不加）。
 *
 * @author CC
 * @description multi-lap comparison selection pure-function contract
 * @date 2026-05-30
 */
class LapComparisonSelectionTest {

    private fun record(lapNumber: Int, timeMs: Long?, status: UiLapStatus): UiLapRecord =
        UiLapRecord(lapNumber = lapNumber, timeMs = timeMs, diffMs = null, status = status, reason = null)

    @Test
    fun `default selection picks best plus up to 3 fastest valid laps capped at 4`() {
        val records = listOf(
            record(1, 62_000L, UiLapStatus.VALID),
            record(2, 60_000L, UiLapStatus.BEST),
            record(3, 61_000L, UiLapStatus.VALID),
            record(4, 63_000L, UiLapStatus.VALID),
            record(5, 64_000L, UiLapStatus.VALID),
        )
        val selected = computeDefaultSelection(records)

        // 合计 4 个；BEST 圈（lap 2）排首
        assertEquals(4, selected.size)
        assertEquals(2, selected.first())
        // 其余按圈时升序取 3 个最快 VALID：lap3(61_000) / lap1(62_000) / lap4(63_000)
        assertEquals(listOf(2, 3, 1, 4), selected)
    }

    @Test
    fun `default selection drops invalid incomplete and null-time laps`() {
        val records = listOf(
            record(1, 60_000L, UiLapStatus.BEST),
            record(2, null, UiLapStatus.INVALID),
            record(3, null, UiLapStatus.INCOMPLETE),
            record(4, 61_000L, UiLapStatus.VALID),
        )
        val selected = computeDefaultSelection(records)
        // 仅 BEST(lap1) + VALID(lap4) 可选
        assertEquals(listOf(1, 4), selected)
    }

    @Test
    fun `default selection with fewer than 2 valid laps returns empty (degrade)`() {
        val records = listOf(
            record(1, 60_000L, UiLapStatus.BEST),
            record(2, null, UiLapStatus.INVALID),
        )
        assertTrue(computeDefaultSelection(records).isEmpty())
    }

    @Test
    fun `assignLapColors maps selection order to palette`() {
        val colors = assignLapColors(listOf(2, 3, 1))
        assertEquals(3, colors.size)
        assertEquals(TrackTechColors.Purple, colors[0])
        assertEquals(TrackTechColors.Cyan, colors[1])
        assertEquals(TrackTechColors.Green, colors[2])
    }

    @Test
    fun `assignLapColors degrades to TextSecondary beyond 4 entries`() {
        val colors = assignLapColors(listOf(1, 2, 3, 4, 5))
        assertEquals(5, colors.size)
        assertEquals(TrackTechColors.Red, colors[3])
        assertEquals(TrackTechColors.TextSecondary, colors[4])
    }

    @Test
    fun `toggle adds when below cap and removes when above floor`() {
        // 加入：当前 2 个，加第 3 个 OK
        assertEquals(listOf(1, 2, 3), toggleLapSelection(listOf(1, 2), 3))
        // 取消：当前 3 个，去掉 2 → 剩 2（>= floor 2 允许）
        assertEquals(listOf(1, 3), toggleLapSelection(listOf(1, 2, 3), 2))
    }

    @Test
    fun `toggle respects upper bound 4 - cannot add 5th`() {
        val current = listOf(1, 2, 3, 4)
        // 已满 4，加第 5 个被拒（返回原列表）
        assertEquals(current, toggleLapSelection(current, 5))
    }

    @Test
    fun `toggle respects lower bound 2 - cannot remove below 2`() {
        val current = listOf(1, 2)
        // 剩 2，去掉 1 被拒（返回原列表，保证下限 2）
        assertEquals(current, toggleLapSelection(current, 1))
    }
}
