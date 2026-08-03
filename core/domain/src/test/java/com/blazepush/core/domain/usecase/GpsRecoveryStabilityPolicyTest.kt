package com.blazepush.core.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsRecoveryStabilityPolicyTest {
    private val policy = GpsRecoveryStabilityPolicy(
        minimumReliableFrames = 3,
        minimumStableDurationMs = 1_000L,
        defaultCadenceMs = 100L,
    )

    @Test
    fun `high and low cadence require different frame counts for the same duration`() {
        assertEquals(26, policy.requiredFrames(cadenceMs = 40L))
        assertEquals(6, policy.requiredFrames(cadenceMs = 200L))
    }

    @Test
    fun `frame count alone cannot arm before minimum stable duration`() {
        val tracker = GpsRecoveryStabilityTracker(policy)
        var snapshot = tracker.emptySnapshot()
        repeat(10) { index ->
            snapshot = tracker.onMainFrame(1L, index * 100L, true, maximumGapMs = 400L)
        }
        snapshot = tracker.onMainFrame(1L, 900L, true, maximumGapMs = 400L)
        assertTrue(snapshot.consecutiveReliableFrames >= snapshot.requiredReliableFrames)
        assertFalse(snapshot.stableDurationMs >= 1_000L)
        assertFalse(snapshot.isStable)
    }

    @Test
    fun `high cadence becomes stable only after dynamic count and duration`() {
        val tracker = GpsRecoveryStabilityTracker(policy)
        var snapshot = tracker.emptySnapshot()
        repeat(26) { index ->
            snapshot = tracker.onMainFrame(1L, index * 40L, true, maximumGapMs = 400L)
        }
        assertEquals(26, snapshot.requiredReliableFrames)
        assertEquals(1_000L, snapshot.stableDurationMs)
        assertTrue(snapshot.isStable)
    }

    @Test
    fun `gap no fix and generation change each reset the window`() {
        val tracker = GpsRecoveryStabilityTracker(policy)
        tracker.onMainFrame(1L, 0L, true, maximumGapMs = 400L)
        tracker.onMainFrame(1L, 100L, true, maximumGapMs = 400L)

        assertEquals(1, tracker.onMainFrame(1L, 500L, true, 400L).consecutiveReliableFrames)
        assertEquals(0, tracker.onMainFrame(1L, 600L, false, 400L).consecutiveReliableFrames)
        assertEquals(1, tracker.onMainFrame(1L, 700L, true, 400L).consecutiveReliableFrames)
        assertEquals(1, tracker.onMainFrame(2L, 800L, true, 400L).consecutiveReliableFrames)
    }
}
