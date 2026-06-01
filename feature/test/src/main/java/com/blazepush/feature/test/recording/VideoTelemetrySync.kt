// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

/**
 * 视频时间轴 ↔ 遥测时间轴同步纯函数（无副作用，可单测）。
 *
 * ## 时钟域说明
 *
 * - 遥测 absoluteTsMs = sessionStartTs + tsDeltaMs，其中 sessionStartTs = System.currentTimeMillis()（wallClock）。
 * - videoStartedAtWallClock = System.currentTimeMillis()（在 VideoRecordEvent.Start 回调取）。
 * - 两者均为 System.currentTimeMillis() 即 Linux epoch realtime clock → **同时钟域，差值有意义**。
 * - 视频帧的 presentationTimeUs（PTS）是相对录制开始的单调计时，需 +videoStartedAtWallClock 才转成 wallClock。
 *
 * ## 反查最近邻
 *
 * 给定 frameWallClock + 遥测样本的 absoluteTsMs 升序列表，返回最近邻 index。
 * 中点取前者（index 较小），边界外 clamp 到 0 / last。
 *
 * @author CC
 * @description 视频↔遥测同步纯函数（Phase 2 round 3 · camera-recording-and-gps-sync）
 * @date 2026-05-30
 */
object VideoTelemetrySync {

    /**
     * 给定录制开始 wallClock + 帧的 PTS 偏移（毫秒），返回该帧的绝对 wallClock。
     *
     * @param videoStartedAtWallClock VideoRecordEvent.Start 取到的 wallClock（毫秒）
     * @param framePtsMs              帧的 presentationTimeUs / 1000（相对录制开始，毫秒）
     * @return 该帧的绝对 wallClock（毫秒），与遥测 absoluteTsMs 同时钟域
     */
    fun frameWallClock(videoStartedAtWallClock: Long, framePtsMs: Long): Long =
        videoStartedAtWallClock + framePtsMs

    /**
     * 给定 frameWallClock + 一组遥测样本的 absoluteTsMs 升序列表，返回最近邻的样本 index。
     *
     * 边界规则：
     * - 空列表 → 抛 [IllegalArgumentException]
     * - frameWallClock < 首样本 → 返回 0
     * - frameWallClock > 末样本 → 返回 last index
     * - 精确命中 → 返回命中 index
     * - 两样本等距（中点）→ 返回较小 index（取前者）
     * - 非等距 → 返回距离最近的 index
     *
     * @param frameWallClock   目标帧的绝对 wallClock（毫秒）
     * @param sampleWallClocks 遥测样本 absoluteTsMs 升序列表（MUST 升序）
     * @return 最近邻样本 index（0-based）
     * @throws IllegalArgumentException 若 sampleWallClocks 为空
     */
    fun findNearestSampleIndex(frameWallClock: Long, sampleWallClocks: List<Long>): Int {
        require(sampleWallClocks.isNotEmpty()) {
            "sampleWallClocks must not be empty"
        }
        if (frameWallClock <= sampleWallClocks.first()) return 0
        if (frameWallClock >= sampleWallClocks.last()) return sampleWallClocks.lastIndex

        // 二分查找最接近的 index
        var lo = 0
        var hi = sampleWallClocks.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            when {
                sampleWallClocks[mid] == frameWallClock -> return mid
                sampleWallClocks[mid] < frameWallClock -> lo = mid
                else -> hi = mid
            }
        }
        // lo 和 hi 是相邻的两个 index，比较距离
        val distLo = frameWallClock - sampleWallClocks[lo]
        val distHi = sampleWallClocks[hi] - frameWallClock
        // 相等时（中点）取较小 index（lo）
        return if (distLo <= distHi) lo else hi
    }

    // ────────────────────────────────────────────────────────────────
    // 按圈回放：圈时间轴主导播放模型纯函数
    // （round redo-video-playback-per-lap-with-blackout）
    // ────────────────────────────────────────────────────────────────

    /**
     * 按圈回放的圈时间轴范围（playhead wallClock 域），统一加"圈起点前导秒"。
     *
     * 起点 = lapStartWallClock - leadInMs（默认 3000ms，进圈前提前展示进弯准备）；
     * 终点 = lapEndWallClock + leadOut（圈尾后留收尾余量；播完即停，不自动续下一圈）。
     *
     * @param lapStartWallClock 该圈开圈 crossing 真壁钟
     * @param lapEndWallClock   该圈收圈 crossing 真壁钟
     * @param leadInMs          圈起点前导毫秒（默认 [LAP_LEAD_IN_MS]=3000）
     * @param leadOutMs         圈终点收尾毫秒（默认 [LAP_LEAD_OUT_MS]=3000；圈尾后多留这么久）
     * @return [startWallClock, endWallClock]（start 已减 leadIn、end 已加 leadOut；保证 start <= end）
     */
    fun lapPlayheadRange(
        lapStartWallClock: Long,
        lapEndWallClock: Long,
        leadInMs: Long = LAP_LEAD_IN_MS,
        leadOutMs: Long = LAP_LEAD_OUT_MS,
    ): LongRange {
        val start = lapStartWallClock - leadInMs
        val rawEnd = lapEndWallClock + leadOutMs
        // 防御：异常圈（rawEnd < start）退化成单点区间，避免空 range 让 ticker 立刻停。
        val end = if (rawEnd < start) start else rawEnd
        return start..end
    }

    /**
     * 判定 playheadWallClock 是否落在视频覆盖段内。
     *
     * 视频覆盖段 = [videoStartedAtWallClock, videoStartedAtWallClock + videoDurationMs]（闭区间，
     * 末帧含）。落段内 → ExoPlayer 自然时钟驱动 playhead；落段外（圈头早于视频起点 / 圈尾晚于
     * 视频终点）→ 黑屏 + ticker 以 1x 实时推进 playhead。
     *
     * videoDurationMs <= 0（READY 前 player.duration 未知，传 0）→ 视为无任何覆盖（恒返回 false）。
     *
     * @param playheadWallClock 当前 playhead 绝对 wallClock
     * @param videoStartedAtWallClock 视频录制开始 wallClock
     * @param videoDurationMs 视频时长毫秒（ExoPlayer READY 后 player.duration）
     * @return playhead 是否落在视频覆盖段内
     */
    fun isWithinVideoCoverage(
        playheadWallClock: Long,
        videoStartedAtWallClock: Long,
        videoDurationMs: Long,
    ): Boolean {
        if (videoDurationMs <= 0L) return false
        val coverageEnd = videoStartedAtWallClock + videoDurationMs
        return playheadWallClock in videoStartedAtWallClock..coverageEnd
    }

    /**
     * playheadWallClock → ExoPlayer 内 seek 位置（position，相对录制开始毫秒）。
     *
     * position = playheadWallClock - videoStartedAtWallClock，clamp 到 [0, videoDurationMs]。
     * 仅在覆盖段内 seek 才有物理意义；段外调用方应黑屏 ticker，不调用本函数。
     *
     * @param playheadWallClock 当前 playhead 绝对 wallClock
     * @param videoStartedAtWallClock 视频录制开始 wallClock
     * @param videoDurationMs 视频时长毫秒（用于 clamp 上界；<=0 时不设上界 clamp）
     * @return ExoPlayer seek 目标 position（毫秒，>= 0）
     */
    fun playheadToVideoPosition(
        playheadWallClock: Long,
        videoStartedAtWallClock: Long,
        videoDurationMs: Long,
    ): Long {
        val raw = playheadWallClock - videoStartedAtWallClock
        val lowerClamped = if (raw < 0L) 0L else raw
        return if (videoDurationMs > 0L && lowerClamped > videoDurationMs) videoDurationMs else lowerClamped
    }

    const val LAP_LEAD_IN_MS: Long = 3000L

    const val LAP_LEAD_OUT_MS: Long = 3000L
}
