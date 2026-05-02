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
            prevMatchedIdx = 49,
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
            prevMatchedIdx = 49,
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
            prevMatchedIdx = 49,
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
            prevMatchedIdx = 49,
        )
        assertNull(proj)
    }

    @Test
    fun `prevMatchedIdx negative one searches from start without crash`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[5],
            currentX = ref.xs[5],
            currentY = ref.ys[5],
            prevMatchedIdx = -1,
        )
        val p = requireNotNull(proj)
        assertTrue("matched in [0, 200] window", p.matchedIdx in 0..199)
    }

    @Test
    fun `prevMatchedIdx near end does not throw IOOB`() {
        val ref = makeStraightReference()
        val proj = projectDelta(
            reference = ref,
            currentLapElapsedMs = ref.elapsedMs[ref.xs.size - 1],
            currentX = ref.xs[ref.xs.size - 1],
            currentY = ref.ys[ref.xs.size - 1],
            prevMatchedIdx = ref.xs.size - 1,
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
            prevMatchedIdx = -1,
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
            prevMatchedIdx = 0,
        )
        val p = requireNotNull(proj)
        // bestElapsed 应在 [0, 80] 中点 ≈ 40，delta ≈ 0
        assertTrue("midpoint delta should be ~0, got ${p.deltaMs}", abs(p.deltaMs) <= 5)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}