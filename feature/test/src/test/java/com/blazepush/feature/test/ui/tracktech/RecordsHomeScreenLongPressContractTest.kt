package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 add-history-deletion round 在
 * `RecordsHomeScreen.kt` 内的长按删除入口接入约束（PERFORMANCE / LAPS 两路径）。
 *
 * 不依赖 Compose runtime / Robolectric / Android Context。仅读源文件文本断言。
 *
 * @author CC
 * @description Records 列表行长按删除接入 contract test
 * @date 2026-05-02
 */
class RecordsHomeScreenLongPressContractTest {

    @Test
    fun `records screen should import DeleteCandidate and DeleteHistoryDialog`() {
        val source = readSource(RECORDS_HOME_PATH)
        assertTrue(
            "RecordsHomeScreen.kt MUST import DeleteCandidate from components package",
            source.contains(
                "import com.blazepush.feature.test.ui.tracktech.components.DeleteCandidate"
            ),
        )
        assertTrue(
            "RecordsHomeScreen.kt MUST import DeleteHistoryDialog from components package",
            source.contains(
                "import com.blazepush.feature.test.ui.tracktech.components.DeleteHistoryDialog"
            ),
        )
    }

    @Test
    fun `records screen should wire onLongClick on PERFORMANCE row to TestRecord candidate`() {
        val source = readSource(RECORDS_HOME_PATH)
        REQUIRED_PERF_LITERALS.forEach { literal ->
            assertTrue(
                "RecordsHomeScreen.kt MUST contain `$literal` on PERFORMANCE long-press path",
                source.contains(literal),
            )
        }
    }

    @Test
    fun `records screen should wire onLongClick on LAPS row to LapSession candidate`() {
        val source = readSource(RECORDS_HOME_PATH)
        REQUIRED_LAP_LITERALS.forEach { literal ->
            assertTrue(
                "RecordsHomeScreen.kt MUST contain `$literal` on LAPS long-press path",
                source.contains(literal),
            )
        }
    }

    @Test
    fun `records screen should render DeleteHistoryDialog on candidate present`() {
        val source = readSource(RECORDS_HOME_PATH)
        assertTrue(
            "RecordsHomeScreen.kt MUST render DeleteHistoryDialog when deleteCandidate non-null",
            source.contains("DeleteHistoryDialog("),
        )
        // 两个 view 各自一个 dialog state，故应至少出现 2 次 mutableStateOf<DeleteCandidate?>
        val stateCount = Regex("mutableStateOf<DeleteCandidate\\?>").findAll(source).count()
        assertTrue(
            "RecordsHomeScreen.kt should declare deleteCandidate state in BOTH PerformanceView and LapsView (found $stateCount)",
            stateCount >= 2,
        )
    }

    @Test
    fun `records screen should NOT contain old delete-by-entity wiring`() {
        val source = readSource(RECORDS_HOME_PATH)
        FORBIDDEN_PATTERNS.forEach { pattern ->
            assertFalse(
                "RecordsHomeScreen.kt MUST NOT contain `$pattern` (banned by add-history-deletion contract)",
                source.contains(pattern),
            )
        }
    }

    @Test
    fun `track tech row should expose optional onLongClick parameter via combinedClickable`() {
        val source = readSource(TRACK_TECH_ROW_PATH)
        assertTrue(
            "TrackTechRow.kt MUST declare optional onLongClick: (() -> Unit)? = null",
            source.contains("onLongClick: (() -> Unit)? = null"),
        )
        assertTrue(
            "TrackTechRow.kt MUST use combinedClickable for click + long-press",
            source.contains("combinedClickable("),
        )
        assertFalse(
            "TrackTechRow.kt MUST NOT keep plain `clickable(onClick = onClick)` after long-press wiring",
            source.contains(".clickable(onClick = onClick)"),
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("source file not found via any candidate path: $path (tried ${candidates.map { it.absolutePath }})")
        return file.readText()
    }

    companion object {
        private const val RECORDS_HOME_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt"
        private const val TRACK_TECH_ROW_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechRow.kt"

        private val REQUIRED_PERF_LITERALS = listOf(
            // PerformanceView 长按 candidate 派生
            "DeleteCandidate.TestRecord(",
            // ViewModel 删除入口
            "testSessionViewModel.deleteTestRecord(",
            // PERFORMANCE 路径必须用 by-id 删除（不持 entity）
            "deleteTestRecord(candidate.id)",
        )

        private val REQUIRED_LAP_LITERALS = listOf(
            "DeleteCandidate.LapSession(",
            "testSessionViewModel.deleteLapSession(",
            "deleteLapSession(candidate.id)",
        )

        private val FORBIDDEN_PATTERNS = listOf(
            // ViewModel 不应直接接 entity / TestRecordEntity（DAO 边界泄漏到 UI）
            "testResultRepository.deleteResult(",
            // PERFORMANCE 不应用旧的 deleteResult(entity) by-entity 路径
            ".deleteResult(entity)",
        )
    }
}
