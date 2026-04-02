package com.blazepush.simulator.data.replay

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaySampleReducerTest {

    @Test
    fun `reduceToTargetHz keeps first sample and limits output by target frequency`() {
        val samples = listOf(
            ReplaySample(0L, 30.0, 104.0, 10.0, 180.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(40L, 30.0001, 104.0001, 11.0, 181.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(80L, 30.0002, 104.0002, 12.0, 182.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(120L, 30.0003, 104.0003, 13.0, 183.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(160L, 30.0004, 104.0004, 14.0, 184.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(200L, 30.0005, 104.0005, 15.0, 185.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(240L, 30.0006, 104.0006, 16.0, 186.0, 10, 1, 0.8, 400.0, 0.0)
        )

        val reduced = ReplaySampleReducer().reduceToTargetHz(samples, targetHz = 5)

        assertEquals(listOf(0L, 200L), reduced.map { it.timestampMillis })
    }
}
