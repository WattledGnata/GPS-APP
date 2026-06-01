// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * VideoTelemetrySync 同步纯函数完整单测。
 *
 * 覆盖 specs.md MUST 5 Scenario 1-7：
 *   Scenario 1：首帧 pts=0 → frameWallClock = videoStartedAtWallClock
 *   Scenario 2：中间帧偏移
 *   Scenario 3：早于首样本 → index 0
 *   Scenario 4：晚于末样本 → last index
 *   Scenario 5：精确命中
 *   Scenario 6：两样本中点 → 取前者（较小 index）
 *   Scenario 7：空列表 → IllegalArgumentException
 *
 * 额外：非等距最近邻（偏前 / 偏后）+ 单样本边界。
 *
 * @author CC
 * @description VideoTelemetrySync 完整单测（Phase 2 round 3）
 * @date 2026-05-30
 */
class VideoTelemetrySyncTest {

    // ────────────────────────────────────────────────────────────────
    // frameWallClock
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `Scenario 1 - 首帧 pts=0 时 frameWallClock 等于 videoStartedAtWallClock`() {
        val wallClock = VideoTelemetrySync.frameWallClock(
            videoStartedAtWallClock = 1_000L,
            framePtsMs = 0L,
        )
        assertEquals(1_000L, wallClock)
    }

    @Test
    fun `Scenario 2 - 中间帧偏移 500ms 时 frameWallClock 正确偏移`() {
        val wallClock = VideoTelemetrySync.frameWallClock(
            videoStartedAtWallClock = 1_000L,
            framePtsMs = 500L,
        )
        assertEquals(1_500L, wallClock)
    }

    @Test
    fun `frameWallClock - 大数值偏移不溢出`() {
        val wallClock = VideoTelemetrySync.frameWallClock(
            videoStartedAtWallClock = 1_700_000_000_000L,
            framePtsMs = 60_000L,
        )
        assertEquals(1_700_000_060_000L, wallClock)
    }

    // ────────────────────────────────────────────────────────────────
    // findNearestSampleIndex - 边界
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `Scenario 3 - 早于首样本时返回 index 0`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 50L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `Scenario 4 - 晚于末样本时返回 last index`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 400L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(2, idx)
    }

    @Test
    fun `Scenario 5 - 精确命中中间样本返回该 index`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 200L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(1, idx)
    }

    @Test
    fun `Scenario 5b - 精确命中首样本返回 0`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 100L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `Scenario 5c - 精确命中末样本返回 last index`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 300L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(2, idx)
    }

    @Test
    fun `Scenario 6 - 两样本中点（等距）取前者（较小 index）`() {
        // 100 和 200 中点 = 150，等距，取 index 0（100 那侧）
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 150L,
            sampleWallClocks = listOf(100L, 200L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `Scenario 7 - 空列表抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoTelemetrySync.findNearestSampleIndex(
                frameWallClock = 100L,
                sampleWallClocks = emptyList(),
            )
        }
    }

    // ────────────────────────────────────────────────────────────────
    // findNearestSampleIndex - 非等距最近邻
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `非等距 - 偏前侧更近时取前者`() {
        // samples: 100, 200, 300；target=120，距前(100)=20 < 距后(200)=80 → index 0
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 120L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `非等距 - 偏后侧更近时取后者`() {
        // samples: 100, 200, 300；target=180，距前(100)=80 > 距后(200)=20 → index 1
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 180L,
            sampleWallClocks = listOf(100L, 200L, 300L),
        )
        assertEquals(1, idx)
    }

    @Test
    fun `非等距 - 三样本中间区间靠近后一个`() {
        // samples: 0, 100, 400；target=270，距前(100)=170 > 距后(400)=130 → index 2
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 270L,
            sampleWallClocks = listOf(0L, 100L, 400L),
        )
        assertEquals(2, idx)
    }

    // ────────────────────────────────────────────────────────────────
    // findNearestSampleIndex - 单样本
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `单样本 - 精确命中返回 0`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 500L,
            sampleWallClocks = listOf(500L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `单样本 - 早于唯一样本返回 0`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 100L,
            sampleWallClocks = listOf(500L),
        )
        assertEquals(0, idx)
    }

    @Test
    fun `单样本 - 晚于唯一样本返回 0`() {
        val idx = VideoTelemetrySync.findNearestSampleIndex(
            frameWallClock = 900L,
            sampleWallClocks = listOf(500L),
        )
        assertEquals(0, idx)
    }

    // ────────────────────────────────────────────────────────────────
    // lapPlayheadRange - 按圈回放圈时间轴范围
    // （round redo-video-playback-per-lap-with-blackout）
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `lapPlayheadRange - 起点减默认前导3秒 终点加默认收尾3秒`() {
        val r = VideoTelemetrySync.lapPlayheadRange(
            lapStartWallClock = 100_000L,
            lapEndWallClock = 190_000L,
        )
        assertEquals(97_000L, r.first) // 100_000 - 3000
        assertEquals(193_000L, r.last) // 190_000 + 3000
    }

    @Test
    fun `lapPlayheadRange - 自定义前导秒`() {
        val r = VideoTelemetrySync.lapPlayheadRange(
            lapStartWallClock = 100_000L,
            lapEndWallClock = 190_000L,
            leadInMs = 5_000L,
        )
        assertEquals(95_000L, r.first)
        assertEquals(193_000L, r.last) // 默认收尾 3000：190_000 + 3000
    }

    @Test
    fun `lapPlayheadRange - 自定义收尾秒`() {
        val r = VideoTelemetrySync.lapPlayheadRange(
            lapStartWallClock = 100_000L,
            lapEndWallClock = 190_000L,
            leadOutMs = 5_000L,
        )
        assertEquals(97_000L, r.first) // 默认前导 3000
        assertEquals(195_000L, r.last) // 190_000 + 5000
    }

    @Test
    fun `lapPlayheadRange - 异常圈end加收尾后仍早于start减前导 退化为单点区间`() {
        // lapEnd + leadOut 仍比 (lapStart - leadIn) 还早（异常数据）→ end clamp 到 start，避免空 range
        // start = 100_000 - 3000 = 97_000；rawEnd = 90_000 + 3000 = 93_000 < 97_000 → clamp
        val r = VideoTelemetrySync.lapPlayheadRange(
            lapStartWallClock = 100_000L,
            lapEndWallClock = 90_000L,
        )
        assertEquals(97_000L, r.first)
        assertEquals(97_000L, r.last)
    }

    @Test
    fun `lapPlayheadRange - 默认前导秒常量为3000`() {
        assertEquals(3000L, VideoTelemetrySync.LAP_LEAD_IN_MS)
    }

    @Test
    fun `lapPlayheadRange - 默认收尾秒常量为3000`() {
        assertEquals(3000L, VideoTelemetrySync.LAP_LEAD_OUT_MS)
    }

    // ────────────────────────────────────────────────────────────────
    // isWithinVideoCoverage - 覆盖段判定
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `isWithinVideoCoverage - playhead落覆盖段内返回true`() {
        // 视频覆盖 [10_000, 10_000+60_000=70_000]
        val within = VideoTelemetrySync.isWithinVideoCoverage(
            playheadWallClock = 40_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(true, within)
    }

    @Test
    fun `isWithinVideoCoverage - playhead早于视频起点返回false（圈头早于视频 黑屏段）`() {
        val within = VideoTelemetrySync.isWithinVideoCoverage(
            playheadWallClock = 5_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(false, within)
    }

    @Test
    fun `isWithinVideoCoverage - playhead晚于视频终点返回false（圈尾晚于视频 黑屏段）`() {
        val within = VideoTelemetrySync.isWithinVideoCoverage(
            playheadWallClock = 80_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(false, within)
    }

    @Test
    fun `isWithinVideoCoverage - 边界视频起点含`() {
        assertEquals(
            true,
            VideoTelemetrySync.isWithinVideoCoverage(10_000L, 10_000L, 60_000L),
        )
    }

    @Test
    fun `isWithinVideoCoverage - 边界视频终点含`() {
        assertEquals(
            true,
            VideoTelemetrySync.isWithinVideoCoverage(70_000L, 10_000L, 60_000L),
        )
    }

    @Test
    fun `isWithinVideoCoverage - duration未知（0）恒返回false`() {
        // ExoPlayer READY 前 player.duration 可能为 0 / 负 → 视为无覆盖
        assertEquals(
            false,
            VideoTelemetrySync.isWithinVideoCoverage(40_000L, 10_000L, 0L),
        )
    }

    // ────────────────────────────────────────────────────────────────
    // playheadToVideoPosition - playhead → 视频 seek position
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `playheadToVideoPosition - 覆盖段内正常映射`() {
        val pos = VideoTelemetrySync.playheadToVideoPosition(
            playheadWallClock = 40_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(30_000L, pos) // 40_000 - 10_000
    }

    @Test
    fun `playheadToVideoPosition - 起点前导秒映射到position0（圈头早于视频时 clamp下界）`() {
        // playhead 早于 videoStart → raw 负 → clamp 到 0
        val pos = VideoTelemetrySync.playheadToVideoPosition(
            playheadWallClock = 5_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(0L, pos)
    }

    @Test
    fun `playheadToVideoPosition - 超视频终点clamp到duration上界`() {
        val pos = VideoTelemetrySync.playheadToVideoPosition(
            playheadWallClock = 90_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 60_000L,
        )
        assertEquals(60_000L, pos)
    }

    @Test
    fun `playheadToVideoPosition - duration未知时不设上界clamp`() {
        val pos = VideoTelemetrySync.playheadToVideoPosition(
            playheadWallClock = 90_000L,
            videoStartedAtWallClock = 10_000L,
            videoDurationMs = 0L,
        )
        assertEquals(80_000L, pos) // 仅下界 clamp，无上界
    }
}
