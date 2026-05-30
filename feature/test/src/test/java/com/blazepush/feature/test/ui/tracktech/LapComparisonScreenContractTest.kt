// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 M3 lap-comparison-screen 的视觉关键字面量、组件接线约束、
 * 路由注册、COMPARE 入口、time-axis（elapsedMsInLap 非距离轴）边界，以及「不改单圈组件 + 不改
 * LapTelemetry 共享字段」的 #16 防护。
 *
 * 不依赖 Compose runtime / Robolectric / Android Context，仅读源文件文本断言。
 * mirror LapDetailScreenContractTest（readSource + projectRoot + collectTextBlocksMissingMaxLines）。
 *
 * @author CC
 * @description M3 LapComparisonScreen visual & wire-up & time-axis & no-touch contract test
 * @date 2026-05-30
 */
class LapComparisonScreenContractTest {

    @Test
    fun `lap comparison screen file should declare all required visual literals`() {
        val source = readSource(SCREEN_PATH)
        REQUIRED_LITERALS.forEach { literal ->
            assertTrue(
                "LapComparisonScreen.kt MUST contain literal `$literal` (M3 visual / wire-up contract)",
                source.contains(literal),
            )
        }
    }

    @Test
    fun `lap comparison screen MUST be time-axis (no distance alignment) and MUST NOT early-return`() {
        val screenSource = readSource(SCREEN_PATH)
        val chartSource = readSource(MULTI_CHART_PATH)
        // time-axis 锁：MultiLapSpeedChart + LapComparisonScreen 不得引入距离轴重采样 API
        TIME_AXIS_FORBIDDEN.forEach { pattern ->
            assertFalse(
                "MultiLapSpeedChart.kt MUST NOT contain `$pattern` (time-axis 第一刀，距离轴留 follow-up)",
                chartSource.contains(pattern),
            )
            assertFalse(
                "LapComparisonScreen.kt MUST NOT contain `$pattern` (time-axis 第一刀)",
                screenSource.contains(pattern),
            )
        }
        // time-axis 正锚点：组件以 elapsedMsInLap 为 X 轴键
        assertTrue(
            "MultiLapSpeedChart.kt MUST use elapsedMsInLap (time-axis X 轴键)",
            chartSource.contains("elapsedMsInLap"),
        )
        // Risk 3：null/不足/loaded 分支 MUST NOT early-return（M2 crash 教训 65d6ada）
        assertFalse(
            "LapComparisonScreen.kt MUST NOT use return@Column (Compose 重组 stack crash)",
            screenSource.contains("return@Column"),
        )
        assertFalse(
            "LapComparisonScreen.kt MUST NOT use return@LazyColumn (Compose 重组 stack crash)",
            screenSource.contains("return@LazyColumn"),
        )
    }

    @Test
    fun `every direct Text call in lap comparison screen should have maxLines and ellipsis`() {
        val source = readSource(SCREEN_PATH)
        val problems = collectTextBlocksMissingMaxLines(source)
        assertTrue(
            "LapComparisonScreen.kt 有 ${problems.size} 处 Text(...) 调用缺 maxLines = 1 / overflow = TextOverflow.Ellipsis：\n" +
                problems.joinToString("\n----\n") { it.take(160) },
            problems.isEmpty(),
        )
    }

    @Test
    fun `app shell should register lap_comparison navhost route with string sessionId`() {
        val source = readSource(APP_SHELL_PATH)
        assertTrue(
            "TrackTechAppShell.kt MUST register `lap_comparison/{sessionId}` route",
            source.contains("\"lap_comparison/{sessionId}\""),
        )
        assertTrue(
            "TrackTechAppShell.kt MUST instantiate LapComparisonScreen in the new route",
            source.contains("LapComparisonScreen("),
        )
        assertTrue(
            "TrackTechAppShell.kt lap_comparison route MUST declare navArgument(\"sessionId\") with NavType.StringType",
            source.contains("navArgument(\"sessionId\")") && source.contains("NavType.StringType"),
        )
    }

    @Test
    fun `lap session detail should expose COMPARE entry navigating to lap_comparison`() {
        val source = readSource(LAP_SESSION_DETAIL_PATH)
        assertTrue(
            "LapSessionDetailScreen.kt MUST navigate to lap_comparison route with sessionId",
            source.contains("lap_comparison/\$sessionId"),
        )
        assertTrue(
            "LapSessionDetailScreen.kt COMPARE entry MUST be gated on validLaps >= 2 (enabled)",
            source.contains("derived.validLaps >= 2"),
        )
        assertTrue(
            "LapSessionDetailScreen.kt MUST render COMPARE LAPS label",
            source.contains("\"COMPARE LAPS\""),
        )
    }

    @Test
    fun `single-lap components untouched - signatures still take cursorAbsoluteTs (#16 boundary)`() {
        // 单圈 4 组件签名 cursorAbsoluteTs 仍在（本 round 不改单圈组件 API）
        listOf(SPEED_CHART_PATH, ACCEL_CHART_PATH, SECTOR_BAR_PATH, TRACK_MAP_PATH).forEach { path ->
            val source = readSource(path)
            assertTrue(
                "${File(path).name} MUST keep `cursorAbsoluteTs: Long?` 签名（本 round 不改单圈组件）",
                source.contains("cursorAbsoluteTs: Long?"),
            )
        }
        // SpeedTimeChart 仍用 absoluteTsMs 作游标 identity（未被改成 elapsedMs）
        val speedSource = readSource(SPEED_CHART_PATH)
        assertTrue(
            "SpeedTimeChart.kt MUST still match cursor by absoluteTsMs（单圈语义未被本 round 改）",
            speedSource.contains("it.absoluteTsMs == cursorAbsoluteTs"),
        )
    }

    @Test
    fun `lap telemetry model untouched contract anchors present (#16)`() {
        // 本 round 消费 elapsedMsInLap + speedKmh 但不改 LapTelemetry / LapTelemetrySample 字段。
        val modelSource = readSource(LAP_TELEMETRY_MODEL_PATH)
        assertTrue(
            "LapTelemetry.kt LapTelemetrySample MUST keep val elapsedMsInLap: Long（time-axis 消费字段未改）",
            modelSource.contains("val elapsedMsInLap: Long"),
        )
        assertTrue(
            "LapTelemetry.kt LapTelemetrySample MUST keep val speedKmh: Double（消费字段未改）",
            modelSource.contains("val speedKmh: Double"),
        )
    }

    private fun readSource(relativePath: String): String {
        val file = File(projectRoot(), relativePath)
        assertTrue(
            "source file MUST exist: ${file.absolutePath}",
            file.exists(),
        )
        return file.readText()
    }

    /**
     * 找出文件内所有以 `Text(` 顶起的调用块，过滤掉块内含 `maxLines = 1` + `TextOverflow.Ellipsis` 的（合规）。
     * mirror LapDetailScreenContractTest.collectTextBlocksMissingMaxLines。
     */
    private fun collectTextBlocksMissingMaxLines(source: String): List<String> {
        val problems = mutableListOf<String>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("Text(")) {
                var depth = 0
                val sb = StringBuilder()
                var j = i
                var closed = false
                while (j < lines.size) {
                    val l = lines[j]
                    sb.appendLine(l)
                    for (ch in l) {
                        when (ch) {
                            '(' -> depth++
                            ')' -> {
                                depth--
                                if (depth == 0) {
                                    closed = true
                                    break
                                }
                            }
                        }
                    }
                    if (closed) break
                    j++
                }
                val block = sb.toString()
                if (!block.contains("maxLines = 1") || !block.contains("TextOverflow.Ellipsis")) {
                    problems += block
                }
                i = j + 1
            } else {
                i++
            }
        }
        return problems
    }

    /**
     * Gradle test cwd = feature/test/，往上找 settings.gradle 找 repo root（worktree 内则找 worktree 根）。
     * 同 LapDetailScreenContractTest / PresetTrackAssetTest.kt:26-32 模式。
     */
    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }

    companion object {
        private const val SCREEN_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapComparisonScreen.kt"
        private const val MULTI_CHART_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/components/MultiLapSpeedChart.kt"
        private const val APP_SHELL_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt"
        private const val LAP_SESSION_DETAIL_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt"
        private const val SPEED_CHART_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt"
        private const val ACCEL_CHART_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/components/AccelTimeChart.kt"
        private const val SECTOR_BAR_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/components/SectorBar.kt"
        private const val TRACK_MAP_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/components/TrackPolylineMap.kt"
        private const val LAP_TELEMETRY_MODEL_PATH =
            "core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt"

        private val REQUIRED_LITERALS = listOf(
            // Visual literals
            "\"LAP COMPARE\"",
            "\"SELECT 2+ LAPS TO COMPARE\"",
            "\"SESSION HAS < 2 VALID LAPS\"",
            "\"SPEED OVERLAY\"",
            // 多圈组件接线
            "MultiLapSpeedChart(",
            // reader 加载
            "getLapTelemetry(",
            // 圈源派生（复用 deriveDetailMetrics）
            "deriveDetailMetrics(",
            // 默认选择 + 最近邻读数
            "computeDefaultSelection(",
            "nearestSampleByElapsed",
            // Cursor 共享 hoisting：chart 回写同一 state（elapsedMs，非 absoluteTsMs）
            "onCursorChange = { cursorElapsedMs = it }",
        )

        private val TIME_AXIS_FORBIDDEN = listOf(
            "alignByDistance",
            "gridIndexFor",
            "LapAlignment",
        )
    }
}
