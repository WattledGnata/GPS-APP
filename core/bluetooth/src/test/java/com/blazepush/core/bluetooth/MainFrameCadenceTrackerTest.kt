package com.blazepush.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class MainFrameCadenceTrackerTest {

    @Test
    fun beforeCadenceEstablished_usesOneSecondFallback() {
        val tracker = MainFrameCadenceTracker()

        tracker.onMainFrame(40L)
        tracker.onMainFrame(80L)
        tracker.onMainFrame(120L)

        assertEquals(1_000L, tracker.currentSilenceTimeoutMs())
    }

    @Test
    fun at25Hz_usesFourHundredMillisecondLowerBound() {
        val tracker = MainFrameCadenceTracker()

        repeat(10) { index -> tracker.onMainFrame((index + 1) * 40L) }

        assertEquals(400L, tracker.currentSilenceTimeoutMs())
    }

    @Test
    fun at20Hz_usesTenExpectedFramesAsDeadline() {
        val tracker = MainFrameCadenceTracker()

        repeat(10) { index -> tracker.onMainFrame((index + 1) * 50L) }

        assertEquals(500L, tracker.currentSilenceTimeoutMs())
    }

    @Test
    fun at5Hz_usesOneSecondUpperBound() {
        val tracker = MainFrameCadenceTracker()

        repeat(10) { index -> tracker.onMainFrame((index + 1) * 200L) }

        assertEquals(1_000L, tracker.currentSilenceTimeoutMs())
    }

    @Test
    fun oneRecoveryGap_doesNotDestroyMedianCadence() {
        val tracker = MainFrameCadenceTracker()
        listOf(40L, 80L, 118L, 160L, 1_160L).forEach(tracker::onMainFrame)

        assertEquals(420L, tracker.currentSilenceTimeoutMs())
    }
}
