package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 enforce-portrait-orientation round 在
 * `LapLiveScreen.kt` 内的 orientation 切换语义防回退（圈速实时屏唯一 landscape 例外）。
 *
 * 不依赖 Robolectric / Compose runtime / Android Context。仅读源文件文本断言。
 *
 * @author CC
 * @description LapLiveScreen orientation lifecycle contract test
 * @date 2026-05-02
 */
class LapLiveScreenOrientationContractTest {

    @Test
    fun `lap live screen should use disposable effect for orientation lifecycle`() {
        val source = readSource(LAP_LIVE_PATH)
        assertTrue(
            "LapLiveScreen.kt MUST use DisposableEffect for orientation lifecycle anchor",
            source.contains("DisposableEffect"),
        )
    }

    @Test
    fun `lap live screen should request landscape on enter`() {
        val source = readSource(LAP_LIVE_PATH)
        assertTrue(
            "LapLiveScreen.kt MUST request SCREEN_ORIENTATION_LANDSCAPE on enter " +
                "(圈速实时屏唯一 landscape 例外，覆盖 manifest 默认 portrait)",
            source.contains("SCREEN_ORIENTATION_LANDSCAPE"),
        )
    }

    @Test
    fun `lap live screen should restore portrait on dispose for double safety`() {
        val source = readSource(LAP_LIVE_PATH)
        assertTrue(
            "LapLiveScreen.kt MUST restore SCREEN_ORIENTATION_PORTRAIT on dispose " +
                "(双重保险——若 manifest 被人误改回 unspecified，DisposableEffect onDispose 仍能恢复竖屏)",
            source.contains("SCREEN_ORIENTATION_PORTRAIT"),
        )
    }

    @Test
    fun `lap live screen should keep screen on during lap session`() {
        val source = readSource(LAP_LIVE_PATH)
        assertTrue(
            "LapLiveScreen.kt MUST contain `keepScreenOn = true` (圈速实时屏专属，防灭屏)",
            source.contains("keepScreenOn = true"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST contain `keepScreenOn = false` (onDispose 恢复正常灭屏行为)",
            source.contains("keepScreenOn = false"),
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("source file not found via any candidate path: $path (tried ${candidates.map { it.absolutePath }})")
        return file.readText()
    }

    companion object {
        private const val LAP_LIVE_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"
    }
}
