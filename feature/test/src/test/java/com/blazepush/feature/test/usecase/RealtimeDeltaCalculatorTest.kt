// @IgnoreFormatCheck
package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/**
 * @author CC
 * @description verify polyline projection delta calculation across same-trajectory / slower / faster / failover scenarios
 * @date 2026-05-02
 */
class RealtimeDeltaCalculatorTest {

    /** 直线 best 圈：纬度等距递增、共 100 点、25Hz（40ms/点）→ 总长 100 × 0.0001° ≈ 11m，4 秒 */
    private fun makeStraightReference(): ReferenceLapIndex {
        val samples = (0 until 100).map { i ->
            GpsSample(
                timestampMillis = 1000L + i * 40L,
                latitude = 30.0 + i * 0.0001,
                longitude = 120.0,
            )
        }
        return requireNotNull(
            buildReferenceLapIndex(
                LapRecord(
                    recordId = "test-lap",
                    sessionId = "test-session",
                    trackId = "test-track",
                    lapIndex = 0,
                    startedAtMillis = 1000,
                    finishedAtMillis = 5000,
                    durationMillis = 4000,
                    trajectory = samples,
                ),
            ),
        )
    }

    @Test
    fun `same trajectory point gives near-zero delta`() {
        val ref = makeStraightReference()
        // 当前点 = best 圈第 50 帧位置 + currentLapElapsedMs = best 圈第 50 帧 elapsedMs
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[50],
            currentX = ref.xs[50],
            currentY = ref.ys[50],
        )
        val p = requireNotNull(proj)
        assertTrue("expected |delta| ≤ 5ms, got ${p.deltaMs}", abs(p.deltaMs) <= 5)
    }

    @Test
    fun `current 1s slower yields positive delta around 1000ms`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[50] + 1000,
            currentX = ref.xs[50],
            currentY = ref.ys[50],
        )
        val p = requireNotNull(proj)
        assertTrue("expected delta ≈ +1000ms, got ${p.deltaMs}", abs(p.deltaMs - 1000) <= 5)
    }

    @Test
    fun `current 1s faster yields negative delta around -1000ms`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[50] - 1000,
            currentX = ref.xs[50],
            currentY = ref.ys[50],
        )
        val p = requireNotNull(proj)
        assertTrue("expected delta ≈ -1000ms, got ${p.deltaMs}", abs(p.deltaMs - (-1000)) <= 5)
    }

    @Test
    fun `gps offset beyond failover distance returns null`() {
        val ref = makeStraightReference()
        // best 圈在 lon=120 一条线上；当前点偏到 lon=120.001（≈ 96m，远超 50m 阈值）
        val (curX, curY) = ref.toLocalMeters(30.005, 120.001)
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[50],
            currentX = curX,
            currentY = curY,
        )
        assertNull(proj)
    }

    @Test
    fun `stateless search finds segment near start without crash`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[5],
            currentX = ref.xs[5],
            currentY = ref.ys[5],
        )
        val p = requireNotNull(proj)
        assertTrue("matched near start in [0, 10]", p.matchedIdx in 0..10)
    }

    @Test
    fun `stateless search handles point near end without IOOB`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[ref.xs.size - 1],
            currentX = ref.xs[ref.xs.size - 1],
            currentY = ref.ys[ref.xs.size - 1],
        )
        assertNotNull(proj)
    }

    @Test
    fun `single-point reference returns null`() {
        val ref = ReferenceLapIndex(
            refLat = 30.0,
            refLon = 120.0,
            xs = floatArrayOf(0f),
            ys = floatArrayOf(0f),
            cumDistanceM = floatArrayOf(0f),
            elapsedMs = longArrayOf(0L),
            lapStartTsMs = 1000L,
            lapDurationMs = 0L,
        )
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = 100,
            currentX = 0f,
            currentY = 0f,
        )
        assertNull(proj)
    }

    @Test
    fun `current point at segment midpoint yields midpoint elapsed`() {
        // 构造两点 reference：[ts=1000, ts=1080]（startedAtMillis=1000 → elapsedMs=[0, 80]）
        val samples = listOf(
            GpsSample(timestampMillis = 1000, latitude = 30.0, longitude = 120.0),
            GpsSample(timestampMillis = 1080, latitude = 30.0001, longitude = 120.0),
        )
        val ref = requireNotNull(
            buildReferenceLapIndex(
                LapRecord(
                    recordId = "mid-test",
                    sessionId = "s",
                    trackId = "t",
                    lapIndex = 0,
                    startedAtMillis = 1000,
                    finishedAtMillis = 1080,
                    durationMillis = 80,
                    trajectory = samples,
                ),
            ),
        )
        // 当前点取两端中点（米空间中点 = lat 30.00005）
        val (midX, midY) = ref.toLocalMeters(30.00005, 120.0)
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = 40, // 与 bestElapsed 中点 = 40 同步
            currentX = midX,
            currentY = midY,
        )
        val p = requireNotNull(proj)
        // bestElapsed 应在 [0, 80] 中点 ≈ 40，delta ≈ 0
        assertTrue("midpoint delta should be ~0, got ${p.deltaMs}", abs(p.deltaMs) <= 5)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4 边界 case（Alt B 核心反例锁死）
    // ──────────────────────────────────────────────────────────────────────

    /**
     * case: lap 切换瞬间不再 -lapDuration
     *
     * 构造 10 点 reference（elapsedMs 0..9000），当前点在 reference 起点附近（idx 0），
     * currentLapElapsedMs ≈ 0（新 lap 刚开圈）。
     * Alt A 若 prevMatchedIdx 卡末段会返回 ≈ -9000；Alt B 全量扫一定找 idx 0 附近。
     */
    @Test
    fun `lap switch instant - no minus lapDuration regression`() {
        val lapDurationMs = 9000L
        val samples = (0 until 10).map { i ->
            GpsSample(
                timestampMillis = 1000L + i * 1000L,
                latitude = 30.0 + i * 0.0001,
                longitude = 120.0,
            )
        }
        val ref = requireNotNull(
            buildReferenceLapIndex(
                LapRecord(
                    recordId = "lap-switch",
                    sessionId = "s",
                    trackId = "t",
                    lapIndex = 0,
                    startedAtMillis = 1000L,
                    finishedAtMillis = 1000L + lapDurationMs,
                    durationMillis = lapDurationMs,
                    trajectory = samples,
                ),
            ),
        )
        // 新圈刚开始 50ms，物理位置在 reference 起点（idx 0）
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = 50L,
            currentX = ref.xs[0],
            currentY = ref.ys[0],
        )
        val p = requireNotNull(proj)
        // deltaMs 应 ≈ +50（在起点附近 bestElapsed ≈ 0），绝对不是 ≈ -9000
        assertTrue(
            "lap switch: deltaMs must be near 0, not -lapDuration; got ${p.deltaMs}",
            abs(p.deltaMs) < 1000L,
        )
        assertTrue(
            "lap switch: matchedIdx MUST be near start (≤ 2), not stuck at end; got ${p.matchedIdx}",
            p.matchedIdx <= 2,
        )
    }

    /**
     * case: 全量扫描找全局最近——当前点在末段附近，matchedIdx 应接近 size-2
     */
    @Test
    fun `stateless scan finds globally nearest segment near end`() {
        val ref = makeStraightReference()
        val lastIdx = ref.xs.size - 1
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[lastIdx],
            currentX = ref.xs[lastIdx],
            currentY = ref.ys[lastIdx],
        )
        val p = requireNotNull(proj)
        // 全量扫描应找到末段附近（size-2 = 98），不被起点卡住
        assertTrue(
            "end-of-lap: matchedIdx should be near end (≥ 90), got ${p.matchedIdx}",
            p.matchedIdx >= 90,
        )
    }

    /**
     * case: off-track 失效返回 null
     *
     * 当前点距 reference 所有 segment > failoverDistanceM(50m)，断言返回 null。
     */
    @Test
    fun `off-track beyond failover returns null`() {
        val ref = makeStraightReference()
        // 偏移到 lon=120.001（≈ 96m，远超 50m）
        val (offX, offY) = ref.toLocalMeters(30.005, 120.001)
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = 2000L,
            currentX = offX,
            currentY = offY,
        )
        assertNull("off-track >50m should return null", proj)
    }

    /**
     * case: track 切换（完全不同坐标）返回 null
     *
     * 当前点坐标偏移 +10000m（约 0.09° lat），远超 50m failover 阈值。
     */
    @Test
    fun `track switch - completely different coordinate returns null`() {
        val ref = makeStraightReference()
        // +0.09° lat ≈ 10_000m，远超 50m 阈值
        val (offX, offY) = ref.toLocalMeters(30.0 + 0.09, 120.0)
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = 500L,
            currentX = offX,
            currentY = offY,
        )
        assertNull("track switch: completely different coord should return null (failover)", proj)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}
