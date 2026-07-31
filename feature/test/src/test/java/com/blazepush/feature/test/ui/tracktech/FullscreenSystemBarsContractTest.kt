package com.blazepush.feature.test.ui.tracktech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenSystemBarsContractTest {
    @Test
    fun playbackOwnsImmersiveSystemBarsAndRestoresThem() {
        val source = source("src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt")
        assertTrue(source.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
        assertTrue(source.contains("insetsController.hide(WindowInsetsCompat.Type.systemBars())"))
        assertTrue(source.contains("insetsController.show(WindowInsetsCompat.Type.systemBars())"))
        assertTrue(source.contains("WindowCompat.setDecorFitsSystemWindows(window, true)"))
    }

    @Test
    fun appTheme_hasDarkStatusBarFallback() {
        val source = source("../../app/src/main/res/values/themes.xml")
        assertTrue(source.contains("android:statusBarColor\">@android:color/black"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("feature/test/$relative"),
            File("../$relative"),
            File("../../$relative"),
        )
        return candidates.first { it.exists() }.readText()
    }
}
