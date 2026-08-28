package com.blazepush.feature.test.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-lap-crossing-clock-hygiene round case D' · 跨文件逃逸 grep gate（v3 review v3 §P1#1 修订）。
 *
 * 防止 `crossingWallClockTimestampMs` 字段误用扩散到无关 UI 显示文件 / state holder / 其他 viewmodel 包内文件。
 * 原始约定：本 round 不引入 per-lap UI 消费方，未来 UI round 引入消费时单独立项放宽 grep gate 范围。
 *
 * **unify-lap-count-pairing-semantics round 放宽（2026-05-30）**：该 round 让 LapSessionDetailScreen
 * 的 deriveDetailMetrics（站点 B）按 `crossingWallClockTimestampMs` 排序配对，使圈编号与 getLapTelemetry
 * （站点 C）lapIndex 同源（spec ADDED requirement「detail 屏圈列表按 wallClock 配对」normative）——
 * 这是原 KDoc 预言的"未来 UI round 引入消费时放宽"场景。故 LapSessionDetailScreen.kt 从禁止文件移到
 * 合法消费方白名单（其消费形态是 detail 屏圈列表排序 key，非 UI 显示数字）。其余 UI 显示文件仍禁止。
 *
 * **share-lap-and-session-vbo round 放宽（2026-08-26）**：整节 VBO 导出器需要用已接受的终点线
 * wallClock 与采样点时间配对，才能输出与详情页一致、可追溯的完整圈摘要。该字段仅作为导出配对 key，
 * 不参与 UI 数字展示，故 RaceLogicVboSessionExporter.kt 加入合法消费方白名单。
 *
 * 实现要点（v3 review v3 §C#2 修订）：
 * - **MUST 用 projectRoot() helper**（参 PresetTrackAssetTest.kt:26-32），禁止裸字面量相对路径
 *   （Gradle 跑 :feature:test:testDebugUnitTest 时 working dir = feature/test/，相对路径解析失败）
 * - 排除条件：(a) `/src/test/` 测试目录（避免 case 自身字符串 trip） (b) `/.worktrees/` 仓库内多 worktree 副本
 * - 三层断言：
 *   1. 下界：扫描到的 .kt 文件总数 ≥ 50（防 path 拼错或 worktree 内跑导致 0 文件假性绿）
 *   2. 命中文件必须 ⊆ 合法消费方白名单（写入端 + 详情配对端 + 整节 VBO 导出端）
 *   3. 明示禁止文件不命中（任一命中即 fail）
 *
 * @author CC
 * @description cross-file grep gate — 锁死 crossingWallClockTimestampMs 仅在合法消费方白名单出现
 * @date 2026-05-03
 */
class CrossingWallClockEscapeContractTest {

    /**
     * §6.7 case D' · 跨文件逃逸 grep gate。
     */
    @Test
    fun `crossingWallClockTimestampMs only appears in approved main consumers`() {
        val mainDir = File(projectRoot(), "feature/test/src/main")
        assertTrue(
            "feature/test/src/main 目录必须存在: ${mainDir.absolutePath}",
            mainDir.isDirectory,
        )

        // 扫描 .kt 文件清单（用 Kotlin walkTopDown 而非 java.nio.file.Files.walk —— 后者
        // 在 Kotlin stream 扩展下行为不稳，walkTopDown 是惰性 sequence，filter 自然兼容）。
        // 注：projectRoot() 找到当前 worktree / repo 根的 settings.gradle，walkTopDown 从
        // feature/test/src/main 起步只走该子树，**不需要**排除 .worktrees（cwd 在 worktree 内时
        // mainDir 路径本身就含 /.worktrees/，排除会把所有命中文件全排除）。
        val ktFiles = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        // 第 1 层断言：下界（防扫错路径假性绿）
        assertTrue(
            "feature/test/src/main 下扫描到的 .kt 文件总数必须 ≥ 50（防 path 拼错或 worktree 副本污染）：" +
                "实测 = ${ktFiles.size}, 路径 = ${mainDir.absolutePath}",
            ktFiles.size >= 50,
        )

        // 扫每个文件 grep crossingWallClockTimestampMs
        val pattern = Regex("""crossingWallClockTimestampMs""")
        val hits = ktFiles.filter { file -> pattern.containsMatchIn(file.readText()) }
        val hitFileNames = hits.map { it.name }

        // 第 2 层断言：命中文件必须 ⊆ 合法消费方白名单（unify-lap-count-pairing-semantics round 放宽）。
        // - TestSessionViewModel.kt：写入端（crossing wallClock 来源）
        // - LapSessionDetailScreen.kt：deriveDetailMetrics 配对消费端（圈编号与 getLapTelemetry 同源 key）
        // - RaceLogicVboSessionExporter.kt：整节 VBO 圈摘要与采样时间配对端（非 UI 展示）
        val allowedConsumers = setOf(
            "TestSessionViewModel.kt",
            "LapSessionDetailScreen.kt",
            "RaceLogicVboSessionExporter.kt",
        )
        val escaped = hitFileNames.filterNot { it in allowedConsumers }
        assertEquals(
            "crossingWallClockTimestampMs 在 feature/test/src/main 内仅允许出现于合法消费方白名单 " +
                "$allowedConsumers：逃逸命中 = $escaped（全部命中 = $hitFileNames）",
            emptyList<String>(),
            escaped,
        )
        // 写入端 TestSessionViewModel.kt MUST 仍命中（防意外删除 wallClock 写入路径）。
        assertTrue(
            "写入端 TestSessionViewModel.kt 必须命中 crossingWallClockTimestampMs：实测命中 = $hitFileNames",
            hitFileNames.contains("TestSessionViewModel.kt"),
        )

        // 第 3 层断言：明示禁止文件不命中（正向 sanity check，防无关 UI 显示路径误消费 wallClock）。
        // LapSessionDetailScreen.kt 已移出禁止列表（本 round 合法消费方），其余 UI 显示文件仍禁止。
        val forbiddenFiles = listOf(
            "LapDebugExecutionScreen.kt",
            "LapsHomeScreen.kt",
            "RecordsHomeScreen.kt",
        )
        forbiddenFiles.forEach { forbiddenName ->
            assertTrue(
                "禁止文件 $forbiddenName 不应命中 crossingWallClockTimestampMs（防 UI 显示路径误消费）",
                hits.none { it.name == forbiddenName },
            )
        }
    }

    /**
     * Gradle test cwd = feature/test/，往上找 settings.gradle 找 repo root。
     * 同 PresetTrackAssetTest.kt:26-32 模式。
     */
    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { File(it, "settings.gradle").exists() || File(it, "settings.gradle.kts").exists() }
    }
}
