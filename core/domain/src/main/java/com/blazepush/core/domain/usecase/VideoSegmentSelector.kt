// @IgnoreFormatCheck
package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.VideoSegment

/**
 * 按 wallClock 窗口选视频段（video-segment-playback-export round ②c · design Decision 1）。
 * 回放（按圈窗口选段进 playlist）与导出（单段输入 / 跨段拒绝）共用，纯函数无依赖。
 *
 * @author CC
 * @description select video segments overlapping a wallClock window
 * @date 2026-06-07
 */
object VideoSegmentSelector {

    /**
     * 返回与窗口 [windowStartMs, windowEndMs] 有 wallClock 重叠的段，按 segmentIndex 升序。
     *
     * 段有效区间 = [startWallClock, endWallClock ?: +∞]：
     * `endWallClock == null`（ERROR 救援段时长未知）MUST 保守入选——漏选 = 救援段画面
     * 再次不可见（②a 修的 2026-06-03 事故复发）。误选代价仅为播放端多一个 playlist item，
     * 实际时长由容器决定，播完自动切下段，无功能损害。
     */
    fun selectForWindow(
        segments: List<VideoSegment>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<VideoSegment> =
        segments.filter { seg ->
            val segEnd = seg.endWallClock ?: Long.MAX_VALUE
            seg.startWallClock <= windowEndMs && segEnd >= windowStartMs
        }.sortedBy { it.segmentIndex }
}
