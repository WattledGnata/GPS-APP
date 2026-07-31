package com.blazepush.feature.test.export

import com.blazepush.feature.test.overlay.VideoOverlayStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoExportOverlayStyleTest {
    @Test
    fun missingOrInvalidIntentValue_fallsBackToFlat() {
        assertEquals(VideoOverlayStyle.FLAT, VideoExportService.parseOverlayStyle(null))
        assertEquals(VideoOverlayStyle.FLAT, VideoExportService.parseOverlayStyle("INVALID"))
    }

    @Test
    fun frozenIntentValue_parsesWithoutConsultingPreferences() {
        assertEquals(
            VideoOverlayStyle.RAIL,
            VideoExportService.parseOverlayStyle(VideoOverlayStyle.RAIL.name),
        )
    }
}
