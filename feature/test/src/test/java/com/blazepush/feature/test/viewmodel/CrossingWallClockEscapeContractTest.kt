package com.blazepush.feature.test.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-lap-crossing-clock-hygiene round case D' · 跨文件逃逸 grep gate（v3 review v3 §P1#1 修订）。
 *
 * 防止 `crossingWallClockTimestampMs` 字段误用扩散到 UI 显示文件 / state holder / 其他 viewmodel 包内文件。
 * 本 round 不引入 per-lap UI 消费方，未来 UI round 引入消费时单独立项放宽 grep gate 范围。
 *
 * 实现要点（v3 review v3 §C#2 修订）：
 * - **MUST 用 projectRoot() helper**（参 PresetTrackAssetTest.kt:26-32），禁止裸字面量相对路径
 *   （Gradle 跑 :feature:test:testDebugUnitTest 时 working dir = feature/test/，相对路径解析失败）
 * - 排除条件：(a) `/src/test/` 测试目录（避免 case 自身字符串 trip） (b) `/.worktrees/` 仓库内多 worktree 副本
 * - 三层断言：
 *   1. 下界：扫描到的 .kt 文件总数 ≥ 50（防 path 拼错或 worktree 内跑导致 0 文件假性绿）
 *   2. 命中文件恰好 1 个：TestSessionViewModel.kt
 *   3. 明示禁止文件不命中（任一命中即 fail）
 *
 * @author CC
 * @description cross-file grep gate — 锁死 crossingWallClockTimestampMs 仅在 TestSessionViewModel.kt 出现
 * @date 2026-05-03
 */
class CrossingWallClockEscapeContractTest {

    /**
     * §6.7 case D' · 跨文件逃逸 grep gate。
     */
    @Test
    fun `crossingWallClockTimestampMs only appears in TestSessionViewModel main src`() {
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

        // 第 2 层断言：命中文件恰好 1 个 = TestSessionViewModel.kt
        assertEquals(
            "crossingWallClockTimestampMs 在 feature/test/src/main 内必须恰好命中 1 个文件 " +
                "（TestSessionViewModel.kt）：实测命中 = $hitFileNames",
            1,
            hits.size,
        )
        val hitFile = hits.single()
        assertEquals(
            "唯一命中文件必须是 TestSessionViewModel.kt：实测 = ${hitFile.absolutePath}",
            "TestSessionViewModel.kt",
            hitFile.name,
        )

        // 第 3 层断言：明示禁止文件不命中（正向 sanity check，防 UI 显示路径误消费 wallClock）
        val forbiddenFiles = listOf(
            "LapDebugExecutionScreen.kt",
            "LapSessionDetailScreen.kt",
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