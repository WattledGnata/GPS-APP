package com.blazepush.feature.test.export

import com.blazepush.core.domain.model.VideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelinePlanTest {

    private fun segment(
        id: Long,
        index: Int,
        start: Long,
        duration: Long,
    ) = VideoSegment(
        id = id,
        sessionId = "s",
        segmentIndex = index,
        filePath = "/video/$index.mp4",
        startWallClock = start,
        endWallClock = start + duration,
        durationMs = duration,
        playable = true,
    )

    @Test
    fun `单段完整覆盖圈 - FULL 且输出切片正确`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(segment(1, 0, 5_000, 20_000)),
        )

        assertEquals(VideoExportClip.Coverage.FULL, plan.coverage)
        assertEquals(1, plan.slices.size)
        assertEquals(2_000L, plan.slices.single().sourceStartMs)
        assertEquals(18_000L, plan.slices.single().sourceEndMs)
        assertFalse(plan.isCrossSegment)
        assertTrue(plan.isExportable)
    }

    @Test
    fun `两段无缝覆盖圈 - FULL 且输出连续`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(
                segment(1, 0, 5_000, 10_000),
                segment(2, 1, 15_000, 10_000),
            ),
        )

        assertEquals(VideoExportClip.Coverage.FULL, plan.coverage)
        assertEquals(2, plan.slices.size)
        assertTrue(plan.isCrossSegment)
        assertEquals(plan.slices[0].outputEndMs, plan.slices[1].outputStartMs)
        assertEquals(15_000L, plan.wallClockForOutputPosition(plan.slices[1].outputStartMs))
    }

    @Test
    fun `短轮换 gap 被视为完整覆盖并在输出中压缩`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(
                segment(1, 0, 5_000, 10_000),
                segment(2, 1, 15_300, 10_000),
            ),
        )

        assertEquals(VideoExportClip.Coverage.FULL, plan.coverage)
        assertEquals(300L, plan.gaps.single().durationMs)
        assertTrue(plan.gaps.single().isShortTechnicalGap)
        assertEquals(plan.slices[0].outputEndMs, plan.slices[1].outputStartMs)
        assertEquals(
            "输出时长应压掉 300ms 技术 gap",
            plan.slices.sumOf { it.durationMs },
            plan.outputDurationMs,
        )
        assertEquals(
            "第二段首帧仍映射到原始 wall-clock，overlay 不随压缩漂移",
            15_300L,
            plan.wallClockForOutputPosition(plan.slices[1].outputStartMs),
        )
    }

    @Test
    fun `明显 gap 降级为 PARTIAL`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(
                segment(1, 0, 5_000, 9_000),
                segment(2, 1, 16_000, 10_000),
            ),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertTrue(plan.gaps.any { it.durationMs == 2_000L && !it.isShortTechnicalGap })
        assertTrue("两侧都有相邻段的 2 秒 chapter gap 应可桥接", plan.isExportable)
        assertEquals(2_000L, plan.bridgeableLapGaps.single().durationMs)
    }

    @Test
    fun `圈头缺失但后半圈有画面 - PARTIAL`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(segment(1, 0, 12_000, 12_000)),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertFalse("只有后半圈画面不是 chapter gap", plan.isExportable)
    }

    @Test
    fun `圈尾缺失 - PARTIAL`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(segment(1, 0, 5_000, 13_000)),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertFalse("只有前半圈画面不是 chapter gap", plan.isExportable)
    }

    @Test
    fun `完全无覆盖 - NONE`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(segment(1, 0, 30_000, 5_000)),
        )

        assertEquals(VideoExportClip.Coverage.NONE, plan.coverage)
        assertTrue(plan.slices.isEmpty())
        assertFalse(plan.isExportable)
    }

    @Test
    fun `未知 duration 可使用 endWallClock fallback`() {
        val segment = VideoSegment(
            id = 8,
            sessionId = "legacy",
            segmentIndex = 0,
            filePath = "/video/legacy.mp4",
            startWallClock = 5_000,
            endWallClock = 25_000,
            durationMs = null,
        )
        val plan = VideoTimelinePlan.build(10_000, 20_000, listOf(segment))

        assertEquals(VideoExportClip.Coverage.FULL, plan.coverage)
        assertEquals(1, plan.slices.size)
        assertTrue(plan.isExportable)
    }

    @Test
    fun `三圈轮换后第四圈圈头 1200ms gap - PARTIAL 但可桥接导出`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(
                // 前段在第四圈起点结束；仍落入 lead-in 窗口，证明 gap 左侧有真实 chapter。
                segment(1, 0, 5_000, 5_000),
                segment(2, 1, 11_200, 12_000),
            ),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertTrue(plan.isCrossSegment)
        assertTrue(plan.isExportable)
        val bridge = plan.bridgeableLapGaps.single()
        assertEquals(1_200L, bridge.durationMs)
        assertTrue(bridge.isBetweenSegments)
        assertTrue(bridge.isExportBridgeable)
    }

    @Test
    fun `圈头晚开 2 秒且无前段 - 不可伪装为 chapter`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 20_000,
            segments = listOf(segment(1, 0, 12_000, 12_000)),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertFalse(plan.isExportable)
        assertTrue(plan.bridgeableLapGaps.isEmpty())
        assertFalse(plan.blockingLapGaps.single().isBetweenSegments)
    }

    @Test
    fun `相邻段 5001ms gap 超过桥接上限 - 不可导出`() {
        val plan = VideoTimelinePlan.build(
            lapStartWallClock = 10_000,
            lapEndWallClock = 25_000,
            segments = listOf(
                segment(1, 0, 5_000, 8_000),
                segment(2, 1, 18_001, 10_000),
            ),
        )

        assertEquals(VideoExportClip.Coverage.PARTIAL, plan.coverage)
        assertFalse(plan.isExportable)
        val gap = plan.blockingLapGaps.single()
        assertEquals(5_001L, gap.durationMs)
        assertTrue(gap.isBetweenSegments)
        assertFalse(gap.isExportBridgeable)
    }

}
