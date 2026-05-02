package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 V2 性能详情页的视觉关键字面量、入口接入约束以及反向禁忌项。
 *
 * 不依赖 Compose runtime / Robolectric / Android Context。仅读源文件文本断言。
 *
 * @author CC
 * @description V2 PerformanceResultScreen visual & wire-up contract test
 * @date 2026-05-01
 */
class PerformanceResultScreenContractTest {

    @Test
    fun `screen file should declare all required visual literals`() {
        val source = readSource(SCREEN_PATH)
        REQUIRED_LITERALS.forEach { literal ->
            assertTrue(
                "PerformanceResultScreen.kt MUST contain literal `$literal` (V2 visual contract)",
                source.contains(literal),
            )
        }
    }

    @Test
    fun `screen file should NOT contain forbidden patterns`() {
        val source = readSource(SCREEN_PATH)
        FORBIDDEN_PATTERNS.forEach { pattern ->
            assertFalse(
                "PerformanceResultScreen.kt MUST NOT contain `$pattern` (forbidden by V2 contract)",
                source.contains(pattern),
            )
        }
    }

    @Test
    fun `every direct Text call should have maxLines and ellipsis`() {
        val source = readSource(SCREEN_PATH)
        // 匹配以 `Text(` 开头到对应右括号的整块（简单括号配平，足够覆盖本文件代码风格）
        val problems = collectTextBlocksMissingMaxLines(source)
        assertTrue(
            "PerformanceResultScreen.kt 有 ${problems.size} 处 Text(...) 调用缺 maxLines = 1 / overflow = TextOverflow.Ellipsis：\n" +
                problems.joinToString("\n----\n") { it.take(160) },
            problems.isEmpty(),
        )
    }

    @Test
    fun `app shell should register performance_result navhost route`() {
        val source = readSource(APP_SHELL_PATH)
        assertTrue(
            "TrackTechAppShell.kt MUST register `performance_result/{testId}` route",
            source.contains("\"performance_result/{testId}\""),
        )
        assertTrue(
            "TrackTechAppShell.kt MUST instantiate PerformanceResultScreen in the new route",
            source.contains("PerformanceResultScreen("),
        )
        assertTrue(
            "TrackTechAppShell.kt route block MUST use navArgument with NavType.StringType for testId",
            source.contains("navArgument(\"testId\")") && source.contains("NavType.StringType"),
        )
    }

    @Test
    fun `v1 test flow navigation should not be touched`() {
        val source = readSource(V1_NAV_PATH)
        // V1 dead code 不能被本 round 误改成调 V2 详情页（避免误导 review）
        assertFalse(
            "V1 TestFlowNavigation.kt MUST NOT call PerformanceResultScreen (V1 is dead code, kept for cleanup round)",
            source.contains("PerformanceResultScreen("),
        )
    }

    private fun readSource(path: String): String {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("source file not found via any candidate path: $path (tried ${candidates.map { it.absolutePath }})")
        return file.readText()
    }

    /**
     * 找出文件内所有以 `Text(` 顶起的调用块，过滤掉块内含 `maxLines = 1` 的（合规）。
     *
     * 跳过：通过 `MetricTile` / `MetricNumber` / `OverviewRow` 等组件层封装调用 —— 它们内部已经强制 maxLines。
     * 这里的扫描只覆盖 `PerformanceResultScreen.kt` 内直接 `Text(...)` 调用。
     */
    private fun collectTextBlocksMissingMaxLines(source: String): List<String> {
        val problems = mutableListOf<String>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // 仅匹配本文件直接调用：行 trim 后以 "Text(" 开头（带 import 别名场景在此项目内不存在）
            if (line.trimStart().startsWith("Text(")) {
                // 收集到第一个对应右括号（按括号深度配平）
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

    companion object {
        private const val SCREEN_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt"
        private const val APP_SHELL_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt"
        private const val V1_NAV_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt"

        private val REQUIRED_LITERALS = listOf(
            // Visual literals
            "\"PERFORMANCE\"",
            "\"TEST TYPE\"",
            "\"Date\"",
            "\"Device\"",
            "\"DISTANCE\"",
            "\"TIME\"",
            "\"PEAK G\"",
            // round smooth-perftest-acceleration-curve §4：PEAK G 按 testTemplateId 二选一渲染
            "\"PEAK ACCEL G\"",
            "\"PEAK BRAKE G\"",
            "\"V1 record\"",
            "\"AVG G\"",
            "\"SPEED CURVE\"",
            "\"G-FORCE\"",
            "\"SPEED SEGMENTS\"",
            "\"0-100 km/h\"",
            "\"100-0 km/h\"",
            // Chart 调用约束
            "wrapInCard = false",
            // Hero color 约束（MetricNumber 用 valueColor 而非 accentColor）
            "valueColor = TrackTechColors.Purple",
        )

        private val FORBIDDEN_PATTERNS = listOf(
            // 编译错误信号：MetricNumber 没有 accentColor 参数
            "accentColor = TrackTechColors.Purple",
            // 本页全 Score，不允许 Mechanical（DSEG7 七段）
            "MetricKind.Mechanical",
            // 不取用 carModel 字段
            "carModel",
            // V1 残留信号：直写 fontSize sp
            "fontSize = 48.sp",
        )
    }
}
