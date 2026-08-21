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
            derivedBestLapMs = 93_456L,
            persistedSessionBestLapMs = 90_000L,
            hasAnyLapEvidence = true,
            lapEvidenceLoaded = true,
            sectorTable = table,
        )

        assertEquals(93_456L, scores.sessionBestLapMs)
        assertEquals(91_234L, scores.theoreticalBestLapMs)
    }

    @Test
    fun `headline theoretical best stays absent without complete sector table`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = 93_456L,
            persistedSessionBestLapMs = null,
            hasAnyLapEvidence = true,
            lapEvidenceLoaded = true,
            sectorTable = null,
        )

        assertEquals(93_456L, scores.sessionBestLapMs)
        assertNull(scores.theoreticalBestLapMs)
    }

    @Test
    fun `legacy session without any evidence falls back to its persisted session best`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = null,
            persistedSessionBestLapMs = 151_074L,
            hasAnyLapEvidence = false,
            lapEvidenceLoaded = true,
            sectorTable = null,
        )

        assertEquals(151_074L, scores.sessionBestLapMs)
    }

    @Test
    fun `existing evidence forbids persisted fallback when derived best is absent`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = null,
            persistedSessionBestLapMs = 151_074L,
            hasAnyLapEvidence = true,
            lapEvidenceLoaded = true,
            sectorTable = null,
        )

        assertNull(scores.sessionBestLapMs)
    }

    @Test
    fun `legacy session without persisted best keeps headline absent`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = null,
            persistedSessionBestLapMs = null,
            hasAnyLapEvidence = false,
            lapEvidenceLoaded = true,
            sectorTable = null,
        )

        assertNull(scores.sessionBestLapMs)
    }

    @Test
    fun `legacy session ignores nonpositive persisted best`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = null,
            persistedSessionBestLapMs = 0L,
            hasAnyLapEvidence = false,
            lapEvidenceLoaded = true,
            sectorTable = null,
        )

        assertNull(scores.sessionBestLapMs)
    }

    @Test
    fun `unloaded evidence never enables legacy fallback`() {
        val scores = deriveSessionHeadlineScores(
            derivedBestLapMs = null,
            persistedSessionBestLapMs = 151_074L,
            hasAnyLapEvidence = false,
            lapEvidenceLoaded = false,
            sectorTable = null,
        )

        assertNull(scores.sessionBestLapMs)
    }

    @Test
    fun `session detail wires guarded legacy fallback from the same session`() {
        val source = readSource(SCREEN_PATH)

        assertTrue(source.contains("derivedBestLapMs = derived.bestLapMs"))
        assertTrue(source.contains("persistedSessionBestLapMs = session?.bestLapMs"))
        assertTrue(source.contains("hasAnyLapEvidence = evidenceByLap.isNotEmpty()"))
        assertTrue(source.contains("lapEvidenceLoaded = lapEvidenceLoaded"))
        assertTrue(source.contains("sectorTable = table"))
        assertTrue(source.contains("R.string.detail_session_best"))
        assertTrue(source.contains("R.string.detail_theoretical_best"))
        assertFalse(source.contains("bestLapForCurrentTrack"))
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
