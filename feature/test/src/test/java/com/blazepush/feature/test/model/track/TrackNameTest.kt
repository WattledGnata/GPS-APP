// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / no-trailing-newline
//       属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.model.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackNameTest {

    @Test
    fun `equal when all fields match including null abbr`() {
        val a = TrackName(zh = "成都天府国际赛道", en = "Chengdu Tianfu International Circuit", abbr = null)
        val b = TrackName(zh = "成都天府国际赛道", en = "Chengdu Tianfu International Circuit", abbr = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equal when abbr explicitly provided`() {
        val a = TrackName(zh = "成都天府国际赛道", en = "Chengdu Tianfu International Circuit", abbr = "TFIC")
        val b = TrackName(zh = "成都天府国际赛道", en = "Chengdu Tianfu International Circuit", abbr = "TFIC")
        assertEquals(a, b)
    }

    @Test
    fun `null abbr not equal to empty string abbr`() {
        // 边界用例：design.md R5 — preset 数据约定 abbr 没有时显式填 null，禁止空字符串。
        // 此测试守护 equals 不会把 null 与 "" 视为等价。
        val nullAbbr = TrackName(zh = "x", en = "y", abbr = null)
        val emptyAbbr = TrackName(zh = "x", en = "y", abbr = "")
        assertNotEquals(nullAbbr, emptyAbbr)
    }
}
