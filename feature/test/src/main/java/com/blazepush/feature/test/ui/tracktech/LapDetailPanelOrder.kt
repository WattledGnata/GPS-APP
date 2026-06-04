// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

/**
 * 单圈详情面板标识与顺序序列化(lap-detail-triview-panel round design Decision 3)。
 *
 * - 默认顺序:视频置顶(有视频的圈),其余沿既有屏序。
 * - 持久化:逗号分隔 name 存 DataStore(per-app);[parse] 对缺值/未知 id/缺项容错——
 *   未知 id 丢弃,缺失的 id 按默认顺序补尾(升级加新面板时旧偏好仍可用)。
 * - 无视频圈渲染时跳过 VIDEO 项,但顺序键保留(spec R3:下次有视频按偏好呈现)。
 *
 * @author CC
 * @description lap detail tri-view panel ids + order (de)serialization
 * @date 2026-06-05
 */
enum class LapDetailPanelId {
    VIDEO, OVERVIEW, SPEED, ACCEL, SECTORS, TRACK
}

object LapDetailPanelOrder {
    val DEFAULT: List<LapDetailPanelId> = listOf(
        LapDetailPanelId.VIDEO,
        LapDetailPanelId.OVERVIEW,
        LapDetailPanelId.SPEED,
        LapDetailPanelId.ACCEL,
        LapDetailPanelId.SECTORS,
        LapDetailPanelId.TRACK,
    )

    fun serialize(order: List<LapDetailPanelId>): String = order.joinToString(",") { it.name }

    fun parse(serialized: String): List<LapDetailPanelId> {
        val known = serialized.split(',')
            .mapNotNull { token -> LapDetailPanelId.entries.firstOrNull { it.name == token.trim() } }
            .distinct()
        val missing = DEFAULT.filterNot { it in known }
        return known + missing
    }

    /** 把 [id] 从当前位置移到 [toIndex](拖拽落定语义);越界 clamp。 */
    fun move(order: List<LapDetailPanelId>, id: LapDetailPanelId, toIndex: Int): List<LapDetailPanelId> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val target = toIndex.coerceIn(0, order.lastIndex)
        if (target == from) return order
        return order.toMutableList().apply {
            removeAt(from)
            add(target, id)
        }
    }
}
