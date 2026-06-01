// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import com.blazepush.feature.test.recording.VideoTelemetrySync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VideoExportClip 按圈裁剪 + 完整覆盖判定纯函数单测。
 *
 * 覆盖 spec "按圈裁剪导出" requirement 的 scenario：
 * - 圈完整落在视频覆盖段内 → 可导 + 裁剪范围正确
 * - 圈头早于视频起点（前导段超覆盖）→ 起点钳到 videoStart（position 0）
 * - 反例：圈完全落在视频覆盖段外 → EmptyClipException
 * - user 锁定口径：整圈被视频完整覆盖才可导（有黑帧段的圈禁用）
 *
 * @author CC
 * @description per-lap export clip pure-function tests
 * @date 2026-05-31
 */
class VideoExportClipTest {

    // 视频覆盖段：[1000, 1000+60000] = [1000, 61000]（wallClock 毫秒）
    private val videoStart = 1_000L
    private val videoDuration = 60_000L
    private val leadIn = VideoTelemetrySync.LAP_LEAD_IN_MS // 3000

    // ────────────────────────────────────────────────────────────────
    // isLapFullyCovered
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `圈本体完整落在视频覆盖段内 - 完整覆盖`() {
        // 圈 [10000, 40000] ⊂ [1000, 61000]
        assertTrue(
            VideoExportClip.isLapFullyCovered(10_000L, 40_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈尾晚于视频终点 - 非完整覆盖（禁用导出）`() {
        // lapEnd=65000 > videoEnd=61000
        assertFalse(
            VideoExportClip.isLapFullyCovered(10_000L, 65_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈头早于视频起点 - 非完整覆盖（圈本体超覆盖，禁用导出）`() {
        // lapStart=500 < videoStart=1000（这里 lapStart 是圈本体而非 leadIn 前导）
        assertFalse(
            VideoExportClip.isLapFullyCovered(500L, 40_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈本体边界恰好贴合视频起止 - 完整覆盖`() {
        // lapStart == videoStart, lapEnd == videoEnd
        assertTrue(
            VideoExportClip.isLapFullyCovered(1_000L, 61_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `视频时长未知（0）- 非完整覆盖`() {
        assertFalse(
            VideoExportClip.isLapFullyCovered(10_000L, 40_000L, videoStart, 0L),
        )
    }

    @Test
    fun `异常圈 lapEnd 小于 lapStart - 非完整覆盖`() {
        assertFalse(
            VideoExportClip.isLapFullyCovered(40_000L, 10_000L, videoStart, videoDuration),
        )
    }

    // ────────────────────────────────────────────────────────────────
    // lapCoverage（三态：NONE / PARTIAL / FULL）
    // round move-export-to-playback-and-relax-replay-gate
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `圈本体完整落在视频覆盖段内 - Coverage FULL`() {
        // 圈 [10000, 40000] ⊂ [1000, 61000]
        assertEquals(
            VideoExportClip.Coverage.FULL,
            VideoExportClip.lapCoverage(10_000L, 40_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈尾晚于视频终点但有重叠 - Coverage PARTIAL`() {
        // 圈 [10000, 65000]，videoEnd=61000：圈头在覆盖内、圈尾超出 → 部分覆盖
        assertEquals(
            VideoExportClip.Coverage.PARTIAL,
            VideoExportClip.lapCoverage(10_000L, 65_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈头早于视频起点但有重叠 - Coverage PARTIAL`() {
        // 圈 [500, 40000]，videoStart=1000：圈头超出、圈尾在覆盖内 → 部分覆盖
        assertEquals(
            VideoExportClip.Coverage.PARTIAL,
            VideoExportClip.lapCoverage(500L, 40_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈完全落在视频覆盖段之后（圈头晚于视频终点）- Coverage NONE`() {
        // 圈 [70000, 80000]，videoEnd=61000：lapStart > videoEnd → 无重叠
        assertEquals(
            VideoExportClip.Coverage.NONE,
            VideoExportClip.lapCoverage(70_000L, 80_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈完全落在视频覆盖段之前（圈尾早于视频起点）- Coverage NONE`() {
        // 圈 [-50000, -40000]，videoStart=1000：lapEnd < videoStart → 无重叠
        assertEquals(
            VideoExportClip.Coverage.NONE,
            VideoExportClip.lapCoverage(-50_000L, -40_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈头尾都超出视频两端但中段有覆盖 - Coverage PARTIAL`() {
        // 圈 [-5000, 70000] 完全包住视频 [1000, 61000]：圈头尾都超出，中段覆盖 → 部分覆盖
        assertEquals(
            VideoExportClip.Coverage.PARTIAL,
            VideoExportClip.lapCoverage(-5_000L, 70_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈本体边界恰好贴合视频起止 - Coverage FULL`() {
        assertEquals(
            VideoExportClip.Coverage.FULL,
            VideoExportClip.lapCoverage(1_000L, 61_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `圈尾恰好等于视频起点（单点接触）- Coverage PARTIAL`() {
        // lapEnd == videoStart：lapEnd < videoStart 为 false → 有重叠（边界点），但 lapStart < videoStart → PARTIAL
        assertEquals(
            VideoExportClip.Coverage.PARTIAL,
            VideoExportClip.lapCoverage(-5_000L, 1_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `视频时长未知（0）- Coverage NONE`() {
        assertEquals(
            VideoExportClip.Coverage.NONE,
            VideoExportClip.lapCoverage(10_000L, 40_000L, videoStart, 0L),
        )
    }

    @Test
    fun `异常圈 lapEnd 小于 lapStart - Coverage NONE`() {
        assertEquals(
            VideoExportClip.Coverage.NONE,
            VideoExportClip.lapCoverage(40_000L, 10_000L, videoStart, videoDuration),
        )
    }

    @Test
    fun `lapCoverage FULL 与 isLapFullyCovered 口径一致`() {
        // FULL 当且仅当 isLapFullyCovered 为 true
        val full = VideoExportClip.lapCoverage(10_000L, 40_000L, videoStart, videoDuration)
        assertEquals(VideoExportClip.Coverage.FULL, full)
        assertTrue(VideoExportClip.isLapFullyCovered(10_000L, 40_000L, videoStart, videoDuration))
    }

    // ────────────────────────────────────────────────────────────────
    // computeClipRange
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `圈完整覆盖且 leadIn leadOut 不超界 - 裁剪范围含前导与收尾余量`() {
        // 圈 [10000, 40000]，leadIn=3000、leadOut=3000 → playhead [7000, 43000]
        // 均在 [1000, 61000] 内 → 起点 position = 7000-1000=6000，终点 = 43000-1000=42000
        val clip = VideoExportClip.computeClipRange(10_000L, 40_000L, videoStart, videoDuration)
        assertEquals(6_000L, clip.startPositionMs)
        assertEquals(42_000L, clip.endPositionMs)
        assertEquals(36_000L, clip.durationMs)
    }

    @Test
    fun `圈头 leadIn 早于视频起点 - 起点钳到 videoStart（position 0）`() {
        // 圈 [2000, 40000]，leadIn=3000 → playheadStart=2000-3000=-1000 < videoStart=1000
        // 圈本体 lapStart=2000 >= videoStart=1000（完整覆盖），但 leadIn 前导超出
        // 交集起点 = max(-1000, 1000) = 1000 → position = 1000-1000 = 0
        assertTrue(
            VideoExportClip.isLapFullyCovered(2_000L, 40_000L, videoStart, videoDuration),
        )
        val clip = VideoExportClip.computeClipRange(2_000L, 40_000L, videoStart, videoDuration)
        assertEquals(0L, clip.startPositionMs)
        assertEquals(42_000L, clip.endPositionMs) // 圈尾 40000 + leadOut 3000 = 43000 → position 42000
    }

    @Test
    fun `圈完全落在视频覆盖段之前 - 抛 EmptyClipException`() {
        // 视频 [1000, 61000]，圈在视频之前 [70000, 80000]（含 leadIn 仍无交集）
        // 这里改成圈在 100000~110000，远晚于 videoEnd
        assertThrows(VideoExportClip.EmptyClipException::class.java) {
            VideoExportClip.computeClipRange(100_000L, 110_000L, videoStart, videoDuration)
        }
    }

    @Test
    fun `圈完全落在视频覆盖段之后（圈尾早于视频起点）- 抛 EmptyClipException`() {
        // 视频 [1000, 61000]，圈极早 [lapStart, lapEnd] 都 < videoStart 且 leadIn 也不交
        // 圈 [-50000, -40000]：playhead [-53000, -40000]，与 [1000,61000] 无交集
        assertThrows(VideoExportClip.EmptyClipException::class.java) {
            VideoExportClip.computeClipRange(-50_000L, -40_000L, videoStart, videoDuration)
        }
    }

    @Test
    fun `裁剪起点终点与 VideoTelemetrySync playheadToVideoPosition 同源`() {
        // 验证裁剪范围确实由 playheadToVideoPosition 派生（同一函数口径，不另起一套）
        val clip = VideoExportClip.computeClipRange(20_000L, 50_000L, videoStart, videoDuration)
        // playheadStart = 20000-3000 = 17000；playheadEnd = 50000+3000 = 53000
        val expectedStart = VideoTelemetrySync.playheadToVideoPosition(17_000L, videoStart, videoDuration)
        val expectedEnd = VideoTelemetrySync.playheadToVideoPosition(53_000L, videoStart, videoDuration)
        assertEquals(expectedStart, clip.startPositionMs)
        assertEquals(expectedEnd, clip.endPositionMs)
    }

    @Test
    fun `圈尾贴近视频终点 - leadOut 收尾段超视频终点时钳到视频末`() {
        // 圈 [10000, 60000]，lapEnd=60000 <= videoEnd=61000（完整覆盖、可导）
        // leadOut 尾 = 60000+3000 = 63000 > videoEnd 61000 → 终点钳到 videoEnd
        // 终点 position = min(63000,61000)-1000 = 60000
        val clip = VideoExportClip.computeClipRange(10_000L, 60_000L, videoStart, videoDuration)
        assertEquals(6_000L, clip.startPositionMs) // 10000-3000-1000
        assertEquals(60_000L, clip.endPositionMs)
    }
}
