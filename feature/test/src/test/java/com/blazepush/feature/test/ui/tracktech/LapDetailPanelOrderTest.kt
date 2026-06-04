// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * @description lap-detail-triview-panel:面板顺序序列化/容错/移动纯函数单测(spec R3)。
 * @author CC
 * @date 2026-06-05
 */
class LapDetailPanelOrderTest {

    @Test
    fun `序列化反序列化往返`() {
        val order = listOf(
            LapDetailPanelId.TRACK, LapDetailPanelId.VIDEO, LapDetailPanelId.SPEED,
            LapDetailPanelId.OVERVIEW, LapDetailPanelId.ACCEL, LapDetailPanelId.SECTORS,
        )
        assertEquals(order, LapDetailPanelOrder.parse(LapDetailPanelOrder.serialize(order)))
    }

    @Test
    fun `空串与损坏值兜底默认顺序`() {
        assertEquals(LapDetailPanelOrder.DEFAULT, LapDetailPanelOrder.parse(""))
        assertEquals(LapDetailPanelOrder.DEFAULT, LapDetailPanelOrder.parse("garbage,123,!!"))
    }

    @Test
    fun `未知 id 丢弃且缺失项按默认补尾`() {
        // 升级场景:旧偏好只有部分面板 + 一个未来未知 id
        val parsed = LapDetailPanelOrder.parse("TRACK,FUTURE_PANEL,VIDEO")
        assertEquals(LapDetailPanelId.TRACK, parsed[0])
        assertEquals(LapDetailPanelId.VIDEO, parsed[1])
        // 其余按默认顺序补尾,总数完整
        assertEquals(LapDetailPanelId.entries.size, parsed.size)
        assertEquals(
            listOf(LapDetailPanelId.OVERVIEW, LapDetailPanelId.SPEED, LapDetailPanelId.ACCEL, LapDetailPanelId.SECTORS),
            parsed.drop(2),
        )
    }

    @Test
    fun `move 拖拽语义与越界 clamp`() {
        val moved = LapDetailPanelOrder.move(LapDetailPanelOrder.DEFAULT, LapDetailPanelId.TRACK, 0)
        assertEquals(LapDetailPanelId.TRACK, moved[0])
        assertEquals(LapDetailPanelId.VIDEO, moved[1])
        // 越界 clamp 到末位
        val clamped = LapDetailPanelOrder.move(LapDetailPanelOrder.DEFAULT, LapDetailPanelId.VIDEO, 99)
        assertEquals(LapDetailPanelId.VIDEO, clamped.last())
        // VIDEO 缺席圈不影响顺序键(spec R3):move 未知位置自保
        assertEquals(LapDetailPanelOrder.DEFAULT, LapDetailPanelOrder.move(LapDetailPanelOrder.DEFAULT, LapDetailPanelId.SPEED, 2))
    }
}
