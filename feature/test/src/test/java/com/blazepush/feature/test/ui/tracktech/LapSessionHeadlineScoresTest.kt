package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapSessionHeadlineScoresTest {

    @Test
    fun `headline keeps current session best and uses current session theoretical total`() {
        val table = LapSectorTable(
            sectorCount = 3,
            laps = emptyList(),
            theoreticalTotalMs = 91_234L,
            bestSplitPerSector = listOf(30_000L, 31_000L, 30_234L),
            bestLapPerSector = listOf(1, 2, 1),
        )

        val scores = deriveSessionHeadlineScores(
            sessionBestLapMs = 93_456L,
            sectorTable = table,
        )

        assertEquals(93_456L, scores.sessionBestLapMs)
        assertEquals(91_234L, scores.theoreticalBestLapMs)
    }

    @Test
    fun `headline theoretical best stays absent without complete sector table`() {
        val scores = deriveSessionHeadlineScores(
            sessionBestLapMs = 93_456L,
            sectorTable = null,
        )

        assertEquals(93_456L, scores.sessionBestLapMs)
        assertNull(scores.theoreticalBestLapMs)
    }

    @Test
    fun `session detail wires headline only from derived session evidence`() {
        val source = readSource(SCREEN_PATH)

        assertTrue(source.contains("sessionBestLapMs = derived.bestLapMs"))
        assertTrue(source.contains("sectorTable = table"))
        assertTrue(source.contains("R.string.detail_session_best"))
        assertTrue(source.contains("R.string.detail_theoretical_best"))
        assertFalse(
            "Session detail must not display persisted or historical best as its current-session headline",
            source.contains("session?.bestLapMs") || source.contains("session.bestLapMs"),
        )
    }

    private fun readSource(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue("source file must exist: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }

    companion object {
        private const val SCREEN_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt"
    }
}
