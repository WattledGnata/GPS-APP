package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Records -> LAPS 长列表滚动容器契约。 */
class RecordsLapSessionHistoryContractTest {

    @Test
    fun `records page should remain vertically scrollable for long histories`() {
        val source = readSource(RECORDS_HOME_PATH)

        assertTrue(
            "RecordsHomeScreen must keep a vertical scroll container",
            source.contains(".verticalScroll(rememberScrollState())"),
        )
        assertTrue(
            "LAPS history must render every session supplied by the ViewModel",
            source.contains("recentSessions.forEach { session ->"),
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("source file not found: $path")
    }

    private companion object {
        const val RECORDS_HOME_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt"
    }
}
