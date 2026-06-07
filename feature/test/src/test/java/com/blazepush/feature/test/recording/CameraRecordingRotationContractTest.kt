// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ②b 按圈轮换源码契约锁(video-segment-recording-rotation · tasks 4.1)。
 * CameraX Recording 不可 JVM 实例化,轮换时序由真机攒批验证;本测试锁实现形态防回退:
 *   - 轮换 API 与 N=3 常量存在
 *   - per-recording 上下文(单字段退役,spec Req2 反例锁)
 *   - 段感知身份比较与 gap 日志(memo M1/M6 观测不可静默删)
 *
 * @author CC
 * @description recording rotation source contract
 * @date 2026-06-07
 */
class CameraRecordingRotationContractTest {

    private fun engineSource(): String {
        val relPath = "feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt"
        val candidates = listOf(
            File("src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt"),
            File("../$relPath"), File("../../$relPath"), File(relPath),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `rotation api and constants exist`() {
        val src = engineSource()
        assertTrue("notifyLapCompleted MUST 存在", src.contains("fun notifyLapCompleted(context: Context)"))
        assertTrue("N=3 常量(user 2026-06-07 拍板)", src.contains("SEGMENT_MAX_LAPS = 3"))
        assertTrue("时长兜底常量(memo M5)", src.contains("MAX_SEGMENT_DURATION_MS = 600_000L"))
        assertTrue("rotateSegment 存在", src.contains("private fun rotateSegment(context: Context, reason: String)"))
    }

    @Test
    fun `per-recording context replaces instance fields`() {
        val src = engineSource()
        assertTrue("SegmentContext 闭包上下文 MUST 存在", src.contains("private class SegmentContext("))
        // spec Req2 反例锁:实例单字段退役——残留即轮换并发污染(旧段写库读到新段 wallClock)
        assertFalse(
            "_capturedWallClock 单字段 MUST 已退役(残留=轮换写库时间轴污染)",
            src.contains("_capturedWallClock"),
        )
        assertFalse("_capturedSessionId 单字段 MUST 已退役", src.contains("_capturedSessionId"))
    }

    @Test
    fun `segment identity comparison and gap log locked`() {
        val src = engineSource()
        assertTrue(
            "Finalize MUST 用 === 身份比较判当前段(防旧段误杀新段)",
            src.contains("activeSegmentCtx === segCtx"),
        )
        assertTrue(
            "segment gap 日志 MUST 存在(memo M1/M6 真机观测数据源,spec Req4 反例锁)",
            src.contains("segment gap="),
        )
    }

    @Test
    fun `lap live screen bridges lap completion to engine`() {
        val relPath = "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"
        val candidates = listOf(
            File("src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"),
            File("../$relPath"), File("../../$relPath"), File(relPath),
        )
        val src = candidates.first { it.exists() }.readText()
        assertTrue("LapLiveScreen MUST 桥接圈完成通知", src.contains("recordingEngine.notifyLapCompleted(context)"))
    }
}
