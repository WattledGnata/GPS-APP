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
}
