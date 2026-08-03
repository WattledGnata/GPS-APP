package com.blazepush.feature.test.viewmodel

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LapTelemetryFlushSchedulerTest {
    @Test
    fun `repeated crossings conflate to one flush`() = runTest {
        val flushed = mutableListOf<String>()
        val scheduler = LapTelemetryFlushScheduler(this, delayMs = 5_000L, flushed::add)

        scheduler.schedule("session-a")
        advanceTimeBy(4_000L)
        scheduler.schedule("session-a")
        advanceUntilIdle()

        assertEquals(listOf("session-a"), flushed)
    }

    @Test
    fun `session switch cancels old delayed flush`() = runTest {
        val flushed = mutableListOf<String>()
        val scheduler = LapTelemetryFlushScheduler(this, delayMs = 5_000L, flushed::add)

        scheduler.schedule("old-session")
        advanceTimeBy(4_999L)
        scheduler.cancel()
        scheduler.schedule("new-session")
        advanceUntilIdle()

        assertEquals(listOf("new-session"), flushed)
    }
}
