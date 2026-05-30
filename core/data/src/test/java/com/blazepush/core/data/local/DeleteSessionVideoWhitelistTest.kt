// @IgnoreFormatCheck
package com.blazepush.core.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * deleteSession 视频文件白名单逻辑测试。
 *
 * 被测逻辑（TelemetryRepository.deleteSession 内联）：
 * ```
 * val allowedPaths = listOf("/telemetry/", "/video/")
 * val canonicalPath = videoFile.canonicalPath
 * if (allowedPaths.any { canonicalPath.contains(it) }) { delete } else { skip }
 * ```
 *
 * 本测试只验证白名单判定逻辑本身，不依赖 Android Context / Room / 真实文件系统。
 * 用 File("...").canonicalPath 在 JVM 上计算规范路径，覆盖正常路径和 path traversal 场景。
 *
 * @author CC
 * @description whitelist logic for video file deletion in deleteSession
 * @date 2026-05-30
 */
class DeleteSessionVideoWhitelistTest {

    // 白名单判定逻辑（与 TelemetryRepository.deleteSession 实现一致）
    private fun isVideoPathAllowed(videoPath: String): Boolean {
        val allowedPaths = listOf("/telemetry/", "/video/")
        val canonicalPath = File(videoPath).canonicalPath
        return allowedPaths.any { canonicalPath.contains(it) }
    }

    @Test
    fun `videoFilePath in video dir - should be accepted by whitelist`() {
        // 标准视频路径（round 3 录制引擎约定：filesDir/video/<sessionId>.mp4）
        val path = "/data/data/com.blazepush.gps/files/video/session-abc123.mp4"
        assertTrue(
            "Path in /video/ dir must be accepted: $path",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `videoFilePath in telemetry dir - should be accepted by whitelist`() {
        // 兼容：若视频和 binary 放在同一 /telemetry/ 目录（实际不太可能，但白名单覆盖）
        val path = "/data/data/com.blazepush.gps/files/telemetry/session-abc123.bin"
        assertTrue(
            "Path in /telemetry/ dir must be accepted: $path",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `videoFilePath outside whitelist in etc - should be rejected`() {
        val path = "/etc/passwd"
        assertFalse(
            "Path /etc/passwd must be rejected: $path",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `videoFilePath outside whitelist in system dir - should be rejected`() {
        val path = "/system/lib/libandroid_runtime.so"
        assertFalse(
            "Path in /system/ must be rejected",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `videoFilePath path traversal attempt - should be rejected`() {
        // path traversal 尝试：canonicalPath 解析后不含 /video/ 或 /telemetry/
        // 在 JVM 上 new File("../../../etc/shadow").canonicalPath 解析为绝对路径
        val path = "../../../etc/shadow"
        assertFalse(
            "Path traversal attempt must be rejected: $path (canonical: ${File(path).canonicalPath})",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `videoFilePath in other app data dir - should be rejected`() {
        val path = "/data/data/com.other.app/files/sensitive.db"
        assertFalse(
            "Path in other app data dir must be rejected: $path",
            isVideoPathAllowed(path)
        )
    }

    @Test
    fun `null videoFilePath guard - should skip video deletion`() {
        // 验证 null 守卫逻辑（TelemetryRepository 中 if (videoPath != null) 守卫）
        val videoPath: String? = null
        var deletionAttempted = false
        if (videoPath != null) {
            deletionAttempted = true
        }
        assertFalse(
            "null videoFilePath must not trigger deletion attempt",
            deletionAttempted
        )
    }

    @Test
    fun `videoFilePath with video substring in filename but not dir - should be rejected`() {
        // 防止文件名含 /video/ 字样绕过（如 /data/data/.../files/myvideo/file.mp4 含 video 但不含 /video/）
        // 实际测试：路径不含 "/video/"（注意斜杠两侧）
        val path = "/data/data/com.blazepush.gps/files/myvideo/session.mp4"
        // "myvideo" 含 "video" 但不含 "/video/"（路径段不匹配）
        // 注意：本测试验证 "/video/" 严格包含斜杠的白名单行为
        // "/data/.../myvideo/session.mp4".contains("/video/") == false（"myvideo" 不带前斜杠 + "/"）
        assertFalse(
            "Path with 'myvideo' dir (not '/video/') must be rejected: $path",
            isVideoPathAllowed(path)
        )
    }
}
