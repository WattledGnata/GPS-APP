package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contract for keeping the observability panel on the driver HUD page. */
class LapLiveGpsHeartbeatContractTest {

    @Test
    fun `driver HUD uses filtered speed and monotonic Main age`() {
        val source = readSource()

        assertTrue(source.contains("sessionViewModel.filteredSpeedKmh.collectAsState()"))
        assertTrue(source.contains("LapGpsHeartbeatPresentationMapper.present("))
        assertTrue(source.contains("SystemClock.elapsedRealtime()"))
        assertTrue(source.contains("LapGpsSpeedIsland("))
        assertTrue(source.contains("cutSize = 16.dp"))
        assertTrue(source.contains("TrackTechTypography.MechanicalLarge"))
        assertTrue("speed island MUST omit km/h unit", !speedIslandSource(source).contains("km/h"))
    }

    @Test
    fun `driver HUD keeps all four lap metrics with heartbeat`() {
        val source = readSource()

        assertTrue(source.contains("R.string.live_delta"))
        assertTrue(source.contains("R.string.live_current"))
        assertTrue(source.contains("R.string.live_last"))
        assertTrue(source.contains("R.string.live_best"))
        assertTrue(source.contains("status.frequencyHz"))
        assertTrue(source.contains("status.satelliteCount"))
        assertTrue(source.contains("status.ageMs"))
        assertTrue(source.contains("R.string.live_satellite_count_compact"))
    }

    @Test
    fun `BLE hard interrupt camera pager recording and hold end remain wired`() {
        val source = readSource()

        assertTrue(source.contains("AbnormalState.BLE_DISCONNECTED"))
        assertTrue(source.contains("AbnormalBanner("))
        assertTrue(source.contains("HorizontalPager("))
        assertTrue(source.contains("RecIndicator("))
        assertTrue(source.contains("HoldToEndButton("))
    }

    private fun readSource(): String {
        val candidates = listOf(File(PATH), File("../$PATH"), File("../../$PATH"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("source file not found: $PATH")
    }

    private fun speedIslandSource(source: String): String = source.substring(
        startIndex = source.indexOf("private fun LapGpsSpeedIsland("),
        endIndex = source.indexOf("/**\n * HUD 页录制状态指示器"),
    )

    private companion object {
        const val PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"
    }
}
