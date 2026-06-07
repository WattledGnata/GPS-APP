// @IgnoreFormatCheck
package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.VideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选段纯函数单测（video-segment-playback-export ②c · spec Req1 全 scenario + 边界）。
 *
 * @author CC
 * @description selectForWindow unit tests
 * @date 2026-06-07
 */
class VideoSegmentSelectorTest {

    private fun seg(index: Int, start: Long, end: Long?) = VideoSegment(
        id = index.toLong(),
        sessionId = "s",
        segmentIndex = index,
        filePath = "/video/$index.mp4",
        startWallClock = start,
        endWallClock = end,
        durationMs = end?.minus(start),
        playable = if (end == null) null else true,
    )

    private val segA = seg(0, 1000, 5000)
    private val segB = seg(1, 8000, 12000)

    /** spec Scenario 1：窗口在单段内。 */
    @Test
    fun `window inside single segment returns that segment`() {
        val r = VideoSegmentSelector.selectForWindow(listOf(segA, segB), 2000, 4000)
        assertEquals(listOf(segA), r)
    }

    /** spec Scenario 2：窗口跨两段，升序返回。 */
    @Test
    fun `window spanning two segments returns both ascending`() {
        val r = VideoSegmentSelector.selectForWindow(listOf(segB, segA), 4000, 9000) // 乱序输入
        assertEquals(listOf(segA, segB), r)
    }

    /** spec Scenario 3（反例锁）：救援段 null endWallClock 保守入选。 */
    @Test
    fun `rescue segment with null endWallClock is conservatively selected`() {
        val rescue = seg(0, 3000, null)
        val r = VideoSegmentSelector.selectForWindow(listOf(rescue), 100_000, 200_000)
        assertEquals(
            "null endWallClock MUST 保守入选——若实现把 null 当零长(endWallClock ?: startWallClock)本断言失败",
            listOf(rescue),
            r,
        )
    }

    /** spec Scenario 4：无覆盖返回空。 */
    @Test
    fun `no overlap returns empty`() {
        val r = VideoSegmentSelector.selectForWindow(listOf(segA), 6000, 7000)
        assertTrue(r.isEmpty())
    }

    /** 边界：窗口端点恰等于段端点（闭区间重叠，宽容入选）。 */
    @Test
    fun `window endpoint touching segment endpoint is selected`() {
        assertEquals(listOf(segA), VideoSegmentSelector.selectForWindow(listOf(segA), 5000, 7000))
        assertEquals(listOf(segA), VideoSegmentSelector.selectForWindow(listOf(segA), 0, 1000))
    }

    /** 救援段在窗口之后（start > windowEnd）不入选——保守只对 end 开放，不对 start。 */
    @Test
    fun `rescue segment starting after window is not selected`() {
        val rescue = seg(0, 9000, null)
        assertTrue(VideoSegmentSelector.selectForWindow(listOf(rescue), 1000, 5000).isEmpty())
    }
}
