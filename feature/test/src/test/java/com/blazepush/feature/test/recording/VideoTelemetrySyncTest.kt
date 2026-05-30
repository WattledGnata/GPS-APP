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
}
