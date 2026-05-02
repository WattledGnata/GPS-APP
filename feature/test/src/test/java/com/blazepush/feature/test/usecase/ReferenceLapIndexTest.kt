package com.blazepush.feature.test.usecase

import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * @author CC
 * @description verify ReferenceLapIndex builder pre-computes meter coordinates and elapsed offsets correctly
 * @date 2026-05-02
 */
class ReferenceLapIndexTest {

    @Test
    fun `single point trajectory returns null`() {
        val lap = makeLap(
            startedAtMillis = 1000,
            samples = listOf(GpsSample(timestampMillis = 1020, latitude = 30.0, longitude = 120.0)),
        )
        assertNull(buildReferenceLapIndex(lap))
    }

    @Test
    fun `empty trajectory returns null`() {
        val lap = makeLap(startedAtMillis = 1000, samples = emptyList())
        assertNull(buildReferenceLapIndex(lap))
    }

    @Test
    fun `100 sample trajectory builds correct sizes and base values`() {
        val samples = (0 until 100).map { i ->
            GpsSample(
                timestampMillis = 1020L + i * 40L,
                latitude = 30.0 + i * 0.0001,
                longitude = 120.0,
            )
        }
        val lap = makeLap(startedAtMillis = 1000, samples = samples, durationMillis = 5000)
        val ref = buildReferenceLapIndex(lap)
        requireNotNull(ref)

        assertEquals(100, ref.xs.size)
        assertEquals(100, ref.ys.size)
        assertEquals(100, ref.cumDistanceM.size)
        assertEquals(100, ref.elapsedMs.size)

        assertEquals(30.0, ref.refLat, 1e-9)
        assertEquals(120.0, ref.refLon, 1e-9)

        // P1-2 修订核心：lapStartTsMs 用 startedAtMillis，**不**是 trajectory.first().ts
        assertEquals(1000L, ref.lapStartTsMs)
        // 因此 elapsedMs[0] 反映首点滞后 crossing 20ms，**不**是 0
        assertEquals(20L, ref.elapsedMs[0])

        assertEquals(0f, ref.cumDistanceM[0], 1e-3f)
        assertEquals(5000L, ref.lapDurationMs)
    }

    @Test
    fun `elapsed and cumulative distance are monotonic non decreasing`() {
        val samples = (0 until 50).map { i ->
            GpsSample(
                timestampMillis = 1000L + i * 40L,
                latitude = 30.0 + i * 0.0001,
                longitude = 120.0,
            )
        }
        val ref = buildReferenceLapIndex(makeLap(startedAtMillis = 1000, samples = samples, durationMillis = 2000))
        requireNotNull(ref)
        for (i in 1 until ref.elapsedMs.size) {
            assertTrue("elapsedMs not monotonic at $i", ref.elapsedMs[i] >= ref.elapsedMs[i - 1])
            assertTrue("cumDistanceM not monotonic at $i", ref.cumDistanceM[i] >= ref.cumDistanceM[i - 1])
        }
    }

    @Test
    fun `toLocalMeters at reference origin returns zero zero`() {
        val samples = listOf(
            GpsSample(timestampMillis = 1000, latitude = 30.0, longitude = 120.0),
            GpsSample(timestampMillis = 1040, latitude = 30.0001, longitude = 120.0),
        )
        val ref = buildReferenceLapIndex(makeLap(startedAtMillis = 1000, samples = samples))
        requireNotNull(ref)
        val (x, y) = ref.toLocalMeters(ref.refLat, ref.refLon)
        assertEquals(0f, x, 1e-3f)
        assertEquals(0f, y, 1e-3f)
    }

    @Test
    fun `straight line trajectory produces near-equal cum distance increments`() {
        // 等距纬度递增 → 米空间内每段距离应近似常数
        val samples = (0 until 20).map { i ->
            GpsSample(
                timestampMillis = 1000L + i * 40L,
                latitude = 30.0 + i * 0.0001,
                longitude = 120.0,
            )
        }
        val ref = buildReferenceLapIndex(makeLap(startedAtMillis = 1000, samples = samples))
        requireNotNull(ref)
        val firstStep = ref.cumDistanceM[1] - ref.cumDistanceM[0]
        for (i in 2 until ref.cumDistanceM.size) {
            val step = ref.cumDistanceM[i] - ref.cumDistanceM[i - 1]
            assertTrue("step delta should be near constant, idx=$i firstStep=$firstStep step=$step",
                abs(step - firstStep) < 0.05f)
        }
    }

    private fun makeLap(
        startedAtMillis: Long,
        samples: List<GpsSample>,
        durationMillis: Long = 5000L,
    ): LapRecord = LapRecord(
        recordId = "test-lap",
        sessionId = "test-session",
        trackId = "test-track",
        lapIndex = 0,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = startedAtMillis + durationMillis,
        durationMillis = durationMillis,
        trajectory = samples,
    )
}
