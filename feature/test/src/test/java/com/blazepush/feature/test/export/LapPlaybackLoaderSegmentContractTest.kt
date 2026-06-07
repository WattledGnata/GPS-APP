// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * loader ②c 选段切换的源码契约锁（video-segment-playback-export · tasks 5.2）。
 * LapPlaybackLoader 依赖 suspend repo 难以纯 JVM 实例化全链路，用 grep contract 锁实现形态：
 * 真实多段行为由真机攒批验证（录两段→按圈回放第一段画面可见）。
 *
 * @author CC
 * @description loader segment-selection source contract
 * @date 2026-06-07
 */
class LapPlaybackLoaderSegmentContractTest {

    private fun loaderSource(): String {
        val relPath = "feature/test/src/main/java/com/blazepush/feature/test/export/LapPlaybackLoader.kt"
        val candidates = listOf(File("src/main/java/com/blazepush/feature/test/export/LapPlaybackLoader.kt"), File("../$relPath"), File("../../$relPath"), File(relPath))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `loader selects segments via VideoSegmentSelector`() {
        val src = loaderSource()
        assertTrue("MUST 经 selectForWindow 选段", src.contains("VideoSegmentSelector.selectForWindow"))
        assertTrue("MUST 读子表 getVideoSegments", src.contains("repo.getVideoSegments(sessionId)"))
        assertTrue("窗口 MUST 用 lead 常量（与回放 playhead 窗口同语义）", src.contains("LAP_LEAD_IN_MS") && src.contains("LAP_LEAD_OUT_MS"))
    }

    @Test
    fun `legacy single-field guard removed`() {
        val src = loaderSource()
        assertFalse(
            "旧 guard 'session.videoStartedAtWallClock ?: return null' MUST 已移除（消费侧切子表）",
            src.contains("session.videoStartedAtWallClock ?: return null"),
        )
        assertTrue("ctx MUST 携带 segments 字段", src.contains("val segments: List<VideoSegment>"))
    }
}
