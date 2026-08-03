package com.blazepush.feature.test.ui.tracktech

import com.blazepush.feature.test.repository.PresetTrackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapsHomeStartPolicyTest {
    @Test
    fun `selected track starts lap mode and navigates without GPS readiness input`() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val selectedTrackIds = mutableListOf<String>()
        var navigationCount = 0

        val started = startLapSession(
            track = track,
            selectLapDebugMode = selectedTrackIds::add,
            navigateToLapLive = { navigationCount++ },
        )

        assertTrue(started)
        assertEquals(listOf(track.id), selectedTrackIds)
        assertEquals(1, navigationCount)
    }

    @Test
    fun `missing selected track performs no partial start or navigation`() {
        var selectCount = 0
        var navigationCount = 0

        val started = startLapSession(
            track = null,
            selectLapDebugMode = { selectCount++ },
            navigateToLapLive = { navigationCount++ },
        )

        assertFalse(started)
        assertEquals(0, selectCount)
        assertEquals(0, navigationCount)
    }

    @Test
    fun `START action delegates directly without tab readiness gate`() {
        val source = readSource()
        val startAction = source.substringAfter("title = \"START LAP SESSION\"")
            .substringBefore("SecondaryActionPanel(")

        assertTrue(startAction.contains("startLapSession("))
        assertFalse(startAction.contains("canEnterTestFlow"))
        assertFalse(startAction.contains("computeTabReadiness"))
        assertFalse(startAction.contains("requestShowScanSheet"))
        assertFalse(startAction.contains("onTabSelected"))
    }

    private fun readSource(): String {
        val userDir = File(requireNotNull(System.getProperty("user.dir")))
        val source = generateSequence(userDir) { current -> current.parentFile }
            .map { root -> File(root, LAPS_HOME_PATH) }
            .firstOrNull(File::exists)
            ?: error("source file not found from ${userDir.absolutePath}: $LAPS_HOME_PATH")
        return source.readText()
    }

    private companion object {
        const val LAPS_HOME_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt"
    }
}
