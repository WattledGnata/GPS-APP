package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Track Tech V2 App Shell HorizontalPager 静态契约测试。
 *
 * 仅覆盖 TabIndex 常量值与 DefaultTrackTechTabs 顺序对齐契约。
 * 真实滑动 / page 索引同步 / 子页跳转 bottom nav 隐藏等运行时行为
 * 由 tasks §8 真机 manual gate 兜底验证（参见 design D7 决策）。
 *
 * @author CC
 * @description Pager static contract unit tests
 * @date 2026-04-30
 */
class TrackTechAppShellPagerTest {

    @Test
    fun `TabIndex constants align with DefaultTrackTechTabs order`() {
        assertEquals(0, TabIndex.Test)
        assertEquals(1, TabIndex.Laps)
        assertEquals(2, TabIndex.Records)
        assertEquals(3, TabIndex.Device)
        assertEquals(4, TabIndex.Count)
    }

    @Test
    fun `DefaultTrackTechTabs has 4 items in TabIndex order`() {
        assertEquals(TabIndex.Count, DefaultTrackTechTabs.size)
        assertEquals("test", DefaultTrackTechTabs[TabIndex.Test].route)
        assertEquals("laps", DefaultTrackTechTabs[TabIndex.Laps].route)
        assertEquals("records", DefaultTrackTechTabs[TabIndex.Records].route)
        assertEquals("device", DefaultTrackTechTabs[TabIndex.Device].route)
    }
}