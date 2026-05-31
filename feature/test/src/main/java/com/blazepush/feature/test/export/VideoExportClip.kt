// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import com.blazepush.feature.test.recording.VideoTelemetrySync

/**
 * 按圈导出的裁剪 + 完整覆盖判定纯函数（无副作用，可单测）。
 *
 * round video-export-burned-overlay · Round B。把"圈是否被视频完整覆盖"+"导出裁剪范围（视频内起止
 * position）"抽成纯函数，与导出管线（[VideoExportPipeline]）和详情屏导出入口 gate 共享同一口径，
 * 杜绝两处各算导致 gate 与实际裁剪不一致。
 *
 * ## 决策口径（user 2026-05-31 锁定）
 *
 * **整圈被视频完整覆盖才可导**：圈本体 [lapStart, lapEnd]（不含 leadIn 前导）MUST 完全落在视频覆盖段
 * [videoStart, videoStart + videoDuration] 内（`lapStart >= videoStart && lapEnd <= videoEnd`）。
 * 有黑帧段（圈头/尾超覆盖）的圈禁用导出，不导半段（Decision 5 + user 拍板）。
 *
 * ## 裁剪范围
 *
 * 导出段 = 圈时间轴 [lapStart - leadIn, lapEnd]（[VideoTelemetrySync.lapPlayheadRange]）与视频覆盖段的
 * 交集，转成视频内 position（[VideoTelemetrySync.playheadToVideoPosition]）：
 * - 起点 position = max(playheadStart, videoStart) - videoStart（前导 leadIn 段超覆盖时钳到视频起点 0）
 * - 终点 position = min(playheadEnd, videoEnd) - videoStart
 *
 * 注意：完整覆盖 gate 只约束圈本体；leadIn 前导段允许超出视频起点被钳（spec "圈头早于视频起点" scenario）。
 * 因此完整覆盖的圈，起点 position 可能因 leadIn 被钳到 0，终点 position = lapEnd 对应位置。
 *
 * @author CC
 * @description per-lap export clip + full-coverage pure functions
 * @date 2026-05-31
 */
object VideoExportClip {

    /**
     * 视频内裁剪范围（单位：毫秒 position，相对录制开始）。
     *
     * @property startPositionMs 导出起点（视频内 position，>= 0）
     * @property endPositionMs   导出终点（视频内 position，> startPositionMs）
     */
    data class ClipRange(
        val startPositionMs: Long,
        val endPositionMs: Long,
    ) {
        /** 导出时长（毫秒），用于进度/总帧估算。 */
        val durationMs: Long get() = endPositionMs - startPositionMs
    }

    /** 交集为空（圈完全落在视频覆盖段外，极罕见）时由 [computeClipRange] 抛出。 */
    class EmptyClipException(message: String) : IllegalStateException(message)

    /**
     * 圈本体相对视频覆盖段的覆盖程度（三态）。
     *
     * round move-export-to-playback-and-relax-replay-gate：回放入口放宽到"有任何覆盖段即可进"，
     * 导出入口仍要求完整覆盖。故需区分三态：
     * - [NONE]：圈本体与视频覆盖段无任何重叠 → 纯黑（无可叠真实画面）→ 回放禁入 + 导出禁用。
     * - [PARTIAL]：圈本体与视频覆盖段有重叠但非完整覆盖（圈头/尾有黑帧段）→ 可回放（半圈也能看 + 将来导某几秒），导出禁用。
     * - [FULL]：圈本体完整落在视频覆盖段内 → 可回放 + 可导出。
     */
    enum class Coverage { NONE, PARTIAL, FULL }

    /**
     * 判定圈本体 [lapStart, lapEnd]（不含 leadIn 前导）相对视频覆盖段的覆盖程度。
     *
     * 重叠判定基于圈本体与视频覆盖段 [videoStart, videoEnd] 的交集是否非空：
     * - 无重叠（`lapEnd < videoStart || lapStart > videoEnd`）→ [Coverage.NONE]
     * - 完整覆盖（`lapStart >= videoStart && lapEnd <= videoEnd`，复用 [isLapFullyCovered] 口径）→ [Coverage.FULL]
     * - 否则（有重叠但非完整）→ [Coverage.PARTIAL]
     *
     * 防御：videoDurationMs <= 0（duration 未知）→ [Coverage.NONE]；lapEnd < lapStart（异常圈）→ [Coverage.NONE]。
     *
     * @param lapStartWallClock 圈开圈 crossing 真壁钟
     * @param lapEndWallClock   圈收圈 crossing 真壁钟
     * @param videoStartedAtWallClock 视频录制开始 wallClock（与样本同时钟域）
     * @param videoDurationMs   视频时长毫秒
     * @return 覆盖程度三态
     */
    fun lapCoverage(
        lapStartWallClock: Long,
        lapEndWallClock: Long,
        videoStartedAtWallClock: Long,
        videoDurationMs: Long,
    ): Coverage {
        if (videoDurationMs <= 0L) return Coverage.NONE
        if (lapEndWallClock < lapStartWallClock) return Coverage.NONE
        val videoEnd = videoStartedAtWallClock + videoDurationMs
        // 无交集：圈尾早于视频起点 或 圈头晚于视频终点
        if (lapEndWallClock < videoStartedAtWallClock || lapStartWallClock > videoEnd) {
            return Coverage.NONE
        }
        return if (lapStartWallClock >= videoStartedAtWallClock && lapEndWallClock <= videoEnd) {
            Coverage.FULL
        } else {
            Coverage.PARTIAL
        }
    }

    /**
     * 圈本体 [lapStart, lapEnd] 是否被视频覆盖段完整覆盖。
     *
     * 完整覆盖 = `lapStart >= videoStart && lapEnd <= videoEnd`（videoEnd = videoStart + videoDuration）。
     * 满足才允许导出（详情屏入口 gate 用此判定 enable/disable）。
     *
     * 防御：videoDurationMs <= 0（duration 未知）→ 视为无覆盖（false）；lapEnd < lapStart（异常圈）→ false。
     *
     * @param lapStartWallClock 圈开圈 crossing 真壁钟
     * @param lapEndWallClock   圈收圈 crossing 真壁钟
     * @param videoStartedAtWallClock 视频录制开始 wallClock（与样本同时钟域）
     * @param videoDurationMs   视频时长毫秒
     * @return 圈本体是否完整落在视频覆盖段内
     */
    fun isLapFullyCovered(
        lapStartWallClock: Long,
        lapEndWallClock: Long,
        videoStartedAtWallClock: Long,
        videoDurationMs: Long,
    ): Boolean {
        if (videoDurationMs <= 0L) return false
        if (lapEndWallClock < lapStartWallClock) return false
        val videoEnd = videoStartedAtWallClock + videoDurationMs
        return lapStartWallClock >= videoStartedAtWallClock && lapEndWallClock <= videoEnd
    }

    /**
     * 计算导出裁剪范围（视频内起止 position）。
     *
     * 导出段 = [lapStart - leadIn, lapEnd] ∩ [videoStart, videoEnd]，转成视频内 position。
     * 调用前应已用 [isLapFullyCovered] gate（圈本体完整覆盖）；本函数仍做交集为空兜底（抛 [EmptyClipException]）。
     *
     * @param lapStartWallClock 圈开圈 wallClock
     * @param lapEndWallClock   圈收圈 wallClock
     * @param videoStartedAtWallClock 视频录制开始 wallClock
     * @param videoDurationMs   视频时长毫秒
     * @param leadInMs          圈起点前导毫秒（默认 [VideoTelemetrySync.LAP_LEAD_IN_MS]=3000）
     * @return [ClipRange]（start < end）
     * @throws EmptyClipException 交集为空（圈与视频覆盖段无重叠）
     */
    fun computeClipRange(
        lapStartWallClock: Long,
        lapEndWallClock: Long,
        videoStartedAtWallClock: Long,
        videoDurationMs: Long,
        leadInMs: Long = VideoTelemetrySync.LAP_LEAD_IN_MS,
    ): ClipRange {
        val range = VideoTelemetrySync.lapPlayheadRange(lapStartWallClock, lapEndWallClock, leadInMs)
        val playheadStart = range.first
        val playheadEnd = range.last
        val videoEnd = videoStartedAtWallClock + videoDurationMs

        // 交集（wallClock 域）
        val clipStartWall = maxOf(playheadStart, videoStartedAtWallClock)
        val clipEndWall = minOf(playheadEnd, videoEnd)
        if (clipEndWall <= clipStartWall) {
            throw EmptyClipException(
                "empty clip: clipStartWall=$clipStartWall clipEndWall=$clipEndWall " +
                    "(playhead=[$playheadStart,$playheadEnd] video=[$videoStartedAtWallClock,$videoEnd])",
            )
        }

        // 转成视频内 position（clamp 到 [0, videoDuration]）
        val startPos = VideoTelemetrySync.playheadToVideoPosition(
            clipStartWall, videoStartedAtWallClock, videoDurationMs,
        )
        val endPos = VideoTelemetrySync.playheadToVideoPosition(
            clipEndWall, videoStartedAtWallClock, videoDurationMs,
        )
        if (endPos <= startPos) {
            throw EmptyClipException("empty clip after position map: start=$startPos end=$endPos")
        }
        return ClipRange(startPositionMs = startPos, endPositionMs = endPos)
    }
}
