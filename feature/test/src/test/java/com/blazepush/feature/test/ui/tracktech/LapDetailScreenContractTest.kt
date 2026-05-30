// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 M2 lap-detail-screen 的视觉关键字面量、组件接线约束、
 * 路由注册、圈行 onClick 范围，以及 R1「accelerationG 在 UI 层不在 reader」的边界（#16 防护）。
 *
 * 不依赖 Compose runtime / Robolectric / Android Context。仅读源文件文本断言。
 * 文件读取用 projectRoot() helper（mirror CrossingWallClockEscapeContractTest），正确解析跨模块
 * 路径（core/data 在另一个 module，gradle test cwd = feature/test/）+ worktree 副本。
 *
 * @author CC
 * @description M2 LapDetailScreen visual & wire-up & R1-boundary contract test
 * @date 2026-05-30
 */
class LapDetailScreenContractTest {

    @Test
    fun `lap detail screen file should declare all required visual literals`() {
        val source = readSource(SCREEN_PATH)
        REQUIRED_LITERALS.forEach { literal ->
            assertTrue(
                "LapDetailScreen.kt MUST contain literal `$literal` (M2 visual / wire-up contract)",
                source.contains(literal),
            )
        }
    }

    @Test
    fun `lap detail screen file should NOT contain forbidden patterns`() {
        val source = readSource(SCREEN_PATH)
        FORBIDDEN_PATTERNS.forEach { pattern ->
            assertFalse(
                "LapDetailScreen.kt MUST NOT contain `$pattern` (forbidden by M2 contract)",
                source.contains(pattern),
            )
        }
    }

    @Test
    fun `every direct Text call in lap detail screen should have maxLines and ellipsis`() {
        val source = readSource(SCREEN_PATH)
        val problems = collectTextBlocksMissingMaxLines(source)
        assertTrue(
            "LapDetailScreen.kt 有 ${problems.size} 处 Text(...) 调用缺 maxLines = 1 / overflow = TextOverflow.Ellipsis：\n" +
                problems.joinToString("\n----\n") { it.take(160) },
            problems.isEmpty(),
        )
    }

    @Test
    fun `app shell should register lap_detail navhost route with int lapIndex`() {
        val source = readSource(APP_SHELL_PATH)
        assertTrue(
            "TrackTechAppShell.kt MUST register `lap_detail/{sessionId}/{lapIndex}` route",
            source.contains("\"lap_detail/{sessionId}/{lapIndex}\""),
        )
        assertTrue(
            "TrackTechAppShell.kt MUST instantiate LapDetailScreen in the new route",
            source.contains("LapDetailScreen("),
        )
        assertTrue(
            "TrackTechAppShell.kt route block MUST declare navArgument(\"lapIndex\") with NavType.IntType",
            source.contains("navArgument(\"lapIndex\")") && source.contains("NavType.IntType"),
        )
        assertTrue(
            "TrackTechAppShell.kt route block MUST declare navArgument(\"sessionId\") with NavType.StringType",
            source.contains("navArgument(\"sessionId\")") && source.contains("NavType.StringType"),
        )
    }

    @Test
    fun `lap session detail should navigate to lap_detail only for valid or best laps`() {
        val source = readSource(LAP_SESSION_DETAIL_PATH)
        assertTrue(
            "LapSessionDetailScreen.kt MUST navigate to lap_detail route with sessionId + lapIndex",
            source.contains("lap_detail/\$sessionId/"),
        )
        // onClick 只对 VALID/BEST 圈赋值；INVALID/INCOMPLETE 分支必须 null（grep 上下文锁定）
        assertTrue(
            "LapSessionDetailScreen.kt onClick MUST be gated on UiLapStatus.VALID / UiLapStatus.BEST",
            source.contains("UiLapStatus.VALID, UiLapStatus.BEST"),
        )
        assertTrue(
            "LapSessionDetailScreen.kt INVALID/INCOMPLETE 圈 MUST map to null onClick (不可点)",
            source.contains("UiLapStatus.INVALID, UiLapStatus.INCOMPLETE -> null"),
        )
        // Row 用 clickable(enabled = onClick != null) 守门
        assertTrue(
            "LapRecordRow MUST gate clickable on onClick != null",
            source.contains("clickable(enabled = onClick != null)"),
        )
    }

    @Test
    fun `R1 accelerationG derive lives in UI layer not in reader (#16 boundary)`() {
        // reader 端：getLapTelemetry 仍硬编码 accelerationG = null（语义未被本 round 改）
        val readerSource = readSource(TELEMETRY_REPOSITORY_PATH)
        assertTrue(
            "TelemetryRepository.kt getLapTelemetry MUST still hardcode `accelerationG = null` (R1 不改 reader 填充语义)",
            readerSource.contains("accelerationG = null"),
        )
        // reader 端 MUST NOT import / 引用 AccelerationSmoother（保持数据层纯净，不耦合 UI 平滑算法）
        assertFalse(
            "TelemetryRepository.kt MUST NOT reference AccelerationSmoother (reader 层保持纯净，R1 在 UI 层)",
            readerSource.contains("AccelerationSmoother"),
        )
        // UI 端：LapDetailScreen MUST import 并用 AccelerationSmoother 派生（R1 在 UI 层）
        val screenSource = readSource(SCREEN_PATH)
        assertTrue(
            "LapDetailScreen.kt MUST import AccelerationSmoother (R1 在 UI 数据准备层反算 accelerationG)",
            screenSource.contains("import com.blazepush.core.domain.usecase.AccelerationSmoother"),
        )
        assertTrue(
            "LapDetailScreen.kt MUST call AccelerationSmoother.compute in deriveAccelerationG",
            screenSource.contains("AccelerationSmoother.compute("),
        )
        assertTrue(
            "LapDetailScreen.kt MUST divide by GRAVITY_MS2 to convert m/s² → G",
            screenSource.contains("/ GRAVITY_MS2"),
        )
    }

    @Test
    fun `reader and lap telemetry model untouched contract anchors present`() {
        // #16 透明声明锚点：本 round 不改 LapTelemetry / LapTelemetrySample 共享 entity 字段。
        // 锁定字段签名（accelerationG nullable default null + flags default 0）仍在，防误改。
        val modelSource = readSource(LAP_TELEMETRY_MODEL_PATH)
        assertTrue(
            "LapTelemetry.kt LapTelemetrySample MUST keep accelerationG: Double? = null (共享字段语义未改)",
            modelSource.contains("val accelerationG: Double? = null"),
        )
        assertTrue(
            "LapTelemetry.kt MUST keep sectorBoundaries: List<Long> (R2 消费多段)",
            modelSource.contains("val sectorBoundaries: List<Long>"),
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
     * mirror PerformanceResultScreenContractTest.collectTextBlocksMissingMaxLines。
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
     * 同 CrossingWallClockEscapeContractTest / PresetTrackAssetTest.kt:26-32 模式。
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
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapDetailScreen.kt"
        private const val APP_SHELL_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt"
        private const val LAP_SESSION_DETAIL_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt"
        private const val TELEMETRY_REPOSITORY_PATH =
            "core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt"
        private const val LAP_TELEMETRY_MODEL_PATH =
            "core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt"

        private val REQUIRED_LITERALS = listOf(
            // Visual literals
            "\"LAP DETAIL\"",
            "\"NO LAP DATA\"",
            "\"SPEED\"",
            "\"ACCEL G\"",
            "\"SECTORS\"",
            "\"TRACK\"",
            // 4 组件接线
            "SpeedTimeChart(",
            "AccelTimeChart(",
            "SectorBar(",
            "TrackPolylineMap(",
            // R1 派生
            "deriveAccelerationG(",
            // reader 加载
            "getLapTelemetry(",
            // R2 多段 sector 消费
            "telemetry.sectorBoundaries",
            // Cursor 共享 hoisting：chart 发起变更回写同一 state
            "onCursorChange = { cursorAbsoluteTs = it }",
        )

        private val FORBIDDEN_PATTERNS = listOf(
            // 圈时是时间字符串 MUST 用 Score，禁止 Mechanical（DSEG7 七段）
            "MetricKind.Mechanical",
            // R2 不允许硬编码单元素 sector 覆盖（MUST 消费 reader 多段）
            "sectorBoundaries = listOf(",
        )
    }
}
