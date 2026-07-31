package com.blazepush.feature.test.ui.tracktech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOverlayStyleContractTest {
    @Test
    fun styleSwitch_updatesCanvasWithoutRecreatingPlayer() {
        val source = source(
            "src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt",
        )
        assertTrue(source.contains("val overlayStyle by overlayStylePreferences.style.collectAsState"))
        assertTrue(source.contains("ExoPlayer.Builder(context).build()"))
        assertFalse(source.contains("remember(overlayStyle)"))
        assertTrue(source.contains("style = overlayStyle"))
    }

    @Test
    fun playbackAndExport_useSameFullFramePainter() {
        val playback = source(
            "src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt",
        )
        val export = source(
            "src/main/java/com/blazepush/feature/test/export/ExportOverlayRenderer.kt",
        )
        assertTrue(playback.contains("OverlayCanvasPainter.drawHud("))
        assertTrue(export.contains("OverlayCanvasPainter.drawHud("))
    }

    @Test
    fun bothPipelines_receiveFrozenStyle() {
        val single = source(
            "src/main/java/com/blazepush/feature/test/export/VideoExportPipeline.kt",
        )
        val multi = source(
            "src/main/java/com/blazepush/feature/test/export/MultiSegmentVideoExportPipeline.kt",
        )
        assertTrue(single.contains("private val overlayStyle: VideoOverlayStyle"))
        assertTrue(multi.contains("private val overlayStyle: VideoOverlayStyle"))
        assertTrue(single.contains("ExportOverlayRenderer(ctx, srcWidth, srcHeight, overlayStyle)"))
        assertTrue(multi.contains("ExportOverlayRenderer(ctx, width, height, overlayStyle)"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("feature/test/$relative"),
            File("../feature/test/$relative"),
        )
        return candidates.first { it.exists() }.readText()
    }
}
