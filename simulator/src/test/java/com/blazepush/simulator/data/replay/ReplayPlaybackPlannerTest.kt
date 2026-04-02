package com.blazepush.simulator.data.replay

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayPlaybackPlannerTest {

    @Test
    fun `plan builds frames with first frame immediate and later frames using timestamp delta`() {
        val samples = listOf(
            ReplaySample(1000L, 30.0, 104.0, 10.0, 180.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(1200L, 30.1, 104.1, 20.0, 181.0, 10, 1, 0.8, 401.0, 0.0),
            ReplaySample(1600L, 30.2, 104.2, 30.0, 182.0, 10, 1, 0.8, 402.0, 0.0)
        )

        val frames = ReplayPlaybackPlanner().plan(samples)

        assertEquals(3, frames.size)
        assertEquals(0L, frames[0].delayMillis)
        assertEquals(200L, frames[1].delayMillis)
        assertEquals(400L, frames[2].delayMillis)
        assertEquals(30.2, frames[2].sample.latitude, 0.000001)
    }
}
