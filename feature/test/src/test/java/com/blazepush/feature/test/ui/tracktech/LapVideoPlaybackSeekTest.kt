package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Test

class LapVideoPlaybackSeekTest {
    private val range = 1_000L..11_000L

    @Test
    fun fastRewind_clampsAtLapStart() {
        assertEquals(1_000L, seekBy(range, 2_000L, -5_000L))
    }

    @Test
    fun fastForward_clampsAtLapEnd() {
        assertEquals(11_000L, seekBy(range, 9_000L, 5_000L))
    }

    @Test
    fun relativeSeek_movesWithinLapRange() {
        assertEquals(6_000L, seekBy(range, 4_000L, 2_000L))
    }
}
