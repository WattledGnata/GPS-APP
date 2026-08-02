package com.blazepush.feature.test.export

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定 UI 与后台 Service 共同消费统一 chapter bridge gate，防止只放开其中一端。 */
class ChapterBridgeExportContractTest {

    @Test
    fun `回放页与 Service 都使用 timeline isExportable`() {
        val playback = source("src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt")
        val service = source("src/main/java/com/blazepush/feature/test/export/VideoExportService.kt")

        assertTrue(playback.contains("timelinePlan?.isExportable == true"))
        assertTrue(playback.contains("if (!isExportable)"))
        assertTrue(playback.contains("enabled = isExportable"))
        assertTrue(playback.contains("分段衔接"))
        assertTrue(service.contains("if (!timeline.isExportable)"))
        assertTrue(service.contains("chapter-bridge export"))
    }

    @Test
    fun `旧的 FULL-only gate 不得残留在 UI 或 Service`() {
        val playback = source("src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt")
        val service = source("src/main/java/com/blazepush/feature/test/export/VideoExportService.kt")

        assertFalse(playback.contains("if (coverage != VideoExportClip.Coverage.FULL)"))
        assertFalse(playback.contains("enabled = coverage == VideoExportClip.Coverage.FULL"))
        assertFalse(service.contains("if (timeline.coverage != VideoExportClip.Coverage.FULL)"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("feature/test/$relative"), File("../feature/test/$relative"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("source file not found: $relative")
    }
}
