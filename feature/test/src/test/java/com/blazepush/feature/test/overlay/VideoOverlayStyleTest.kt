package com.blazepush.feature.test.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOverlayStyleTest {
    @Test
    fun missingOrUnknownValue_fallsBackToFlat() {
        assertEquals(VideoOverlayStyle.FLAT, VideoOverlayStyle.fromStored(null))
        assertEquals(VideoOverlayStyle.FLAT, VideoOverlayStyle.fromStored("LEGACY_UNKNOWN"))
    }

    @Test
    fun stableNames_roundTrip() {
        VideoOverlayStyle.entries.forEach { style ->
            assertEquals(style, VideoOverlayStyle.fromStored(style.name))
        }
    }
}
