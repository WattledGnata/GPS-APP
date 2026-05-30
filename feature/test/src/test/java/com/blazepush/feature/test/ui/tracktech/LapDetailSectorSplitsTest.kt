// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * lap-detail-sector-split-times round · computeSectorSplits 纯函数单测。
 *
 * @author CC
 * @description 验证本圈各 sector 耗时派生（sectorBoundaries + lapEnd 相邻差）
 * @date 2026-05-30
 */
class LapDetailSectorSplitsTest {

    @Test
    fun twoSectorGates_yieldsThreeSplits() {
        // sectorBoundaries = [lapStart=1000, s1=2500, s2=3800], lapEnd=5000
        val splits = computeSectorSplits(listOf(1000L, 2500L, 3800L), 5000L)
        assertEquals(listOf(1500L, 1300L, 1200L), splits)
    }

    @Test
    fun oneSectorGate_yieldsTwoSplits() {
        val splits = computeSectorSplits(listOf(1000L, 2500L), 5000L)
        assertEquals(listOf(1500L, 2500L), splits)
    }

    @Test
    fun noSectorGate_singleBoundary_yieldsWholeLapSplit() {
        // 无 sector 门：sectorBoundaries=[lapStart] → 1 split = 全圈（UI 会以"无 sector 分段"提示）
        val splits = computeSectorSplits(listOf(1000L), 5000L)
        assertEquals(listOf(4000L), splits)
    }

    @Test
    fun emptyBoundaries_yieldsEmpty() {
        assertEquals(emptyList<Long>(), computeSectorSplits(emptyList(), 5000L))
    }

    @Test
    fun splitsSumEqualsLapDuration() {
        // 不变式：各 split 之和 == lapEnd - lapStart（无丢段）
        val boundaries = listOf(1000L, 2500L, 3800L)
        val lapEnd = 5000L
        val splits = computeSectorSplits(boundaries, lapEnd)
        assertEquals(lapEnd - boundaries.first(), splits.sum())
    }
}
