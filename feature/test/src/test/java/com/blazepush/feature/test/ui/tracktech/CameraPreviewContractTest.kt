// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 camera-preview-in-laplivescreen round v2
 * （横滑独立预览页返工）的核心契约：
 *
 * 1. `CameraPreview.kt` 只绑 CameraX `Preview` use-case（`bindToLifecycle` + `Preview`），
 *    MUST NOT 绑 `VideoCapture` / `Recorder` / 任何文件写入（spec MUST 5 反例——录制是 round 3）。
 * 2. `LapLiveScreen.kt` 包成 `HorizontalPager(2 页)`：页 0 = 纯 HUD 驾驶页（不含 CameraPreview），
 *    页 1 = 相机取景页（仅 currentPage==1 时绑相机 = 省电）。
 * 3. 相机 gating MUST 收到 `pagerState.settledPage == 1`（驾驶页绝不绑相机 = 省电核心）。
 * 4. 权限复用 `RequiredCameraPermissions` + `PermissionRequestOutcome.from`（round 1 复用，不重造）。
 * 5. `LapLiveScreen.kt` 同样 MUST NOT 引入 VideoCapture / 录制路径（spec MUST 5）。
 * 6. `DisposableEffect` + `unbindAll` 解绑释放相机锚点存在（spec MUST 6）。
 *
 * 不依赖 Robolectric / Compose runtime / Android Context。仅读源文件文本断言（参
 * CrossingWallClockEscapeContractTest.kt:106 的 projectRoot 范式避 working dir 坑）。
 *
 * @author CC
 * @description camera preview as horizontal pager page + currentPage gating + no-recording contract test
 * @date 2026-05-30
 */
class CameraPreviewContractTest {

    @Test
    fun `camera preview binds only Preview use-case`() {
        val source = readSource(CAMERA_PREVIEW_PATH)
        assertTrue(
            "CameraPreview.kt MUST bind via bindToLifecycle (CameraX lifecycle 绑定锚点)",
            source.contains("bindToLifecycle"),
        )
        assertTrue(
            "CameraPreview.kt MUST bind the Preview use-case (CameraSelector.DEFAULT_BACK_CAMERA)",
            source.contains("Preview.Builder()") && source.contains("DEFAULT_BACK_CAMERA"),
        )
    }

    @Test
    fun `camera preview must NOT bind VideoCapture or write files (recording is round 3)`() {
        val source = readSource(CAMERA_PREVIEW_PATH)
        // spec MUST 5 反例：本 round 引入 VideoCapture / Recorder / 文件写入 → 该测试 fail。
        assertFalse(
            "CameraPreview.kt MUST NOT bind VideoCapture (录制是 round 3，本 round 仅预览)",
            source.contains("VideoCapture"),
        )
        assertFalse(
            "CameraPreview.kt MUST NOT reference Recorder (录制是 round 3)",
            source.contains("Recorder"),
        )
        assertFalse(
            "CameraPreview.kt MUST NOT reference Recording (录制是 round 3)",
            source.contains("Recording"),
        )
        assertFalse(
            "CameraPreview.kt MUST NOT write mp4 / 文件输出（本 round 无任何文件输出路径）",
            source.contains(".mp4") || source.contains("MediaStoreOutputOptions") ||
                source.contains("FileOutputOptions"),
        )
    }

    @Test
    fun `camera preview must unbind on dispose to release camera`() {
        val source = readSource(CAMERA_PREVIEW_PATH)
        // spec MUST 6：DisposableEffect onDispose 解绑释放相机。
        assertTrue(
            "CameraPreview.kt MUST use DisposableEffect for camera lifecycle",
            source.contains("DisposableEffect"),
        )
        assertTrue(
            "CameraPreview.kt MUST call unbindAll to release camera (避免相机被占 + 耗电泄漏)",
            source.contains("unbindAll"),
        )
        assertTrue(
            "CameraPreview.kt MUST have onDispose block",
            source.contains("onDispose"),
        )
    }

    @Test
    fun `lap live screen wraps content in a 2-page HorizontalPager`() {
        val source = readSource(LAP_LIVE_PATH)
        // v2 spec MUST：横滑 2 页（页 0 HUD / 页 1 相机），不再全屏垫底 + toggle。
        assertTrue(
            "LapLiveScreen.kt MUST use HorizontalPager (横滑独立预览页返工)",
            source.contains("HorizontalPager("),
        )
        assertTrue(
            "LapLiveScreen.kt MUST create a 2-page pager (pageCount = { 2 })",
            source.contains("rememberPagerState(pageCount = { 2 })"),
        )
    }

    @Test
    fun `hud page (page 0) must NOT render CameraPreview (driving page is pure HUD)`() {
        val source = readSource(LAP_LIVE_PATH)
        // v2 省电核心：页 0 = 纯 HUD 驾驶页，绝不含相机预览（驾驶时不开相机 = 省电省热）。
        // LapHudPage 组合 top strip / abnormal / dashboard / HOLD TO END，body 中不得出现 CameraPreview(。
        val hudPageStart = source.indexOf("private fun LapHudPage(")
        assertTrue("LapLiveScreen.kt MUST define LapHudPage (页 0 HUD 抽出)", hudPageStart >= 0)
        // LapHudPage 到下一个 @Composable 之间不得出现 CameraPreview(。
        val afterHud = source.substring(hudPageStart)
        val hudBodyEnd = afterHud.indexOf("private fun CameraPreviewPage(")
        assertTrue("CameraPreviewPage 必须定义在 LapHudPage 之后（结构锚点）", hudBodyEnd >= 0)
        val hudBody = afterHud.substring(0, hudBodyEnd)
        assertFalse(
            "LapHudPage (页 0 驾驶页) MUST NOT render CameraPreview (省电核心：驾驶页纯 HUD)",
            hudBody.contains("CameraPreview("),
        )
        // 页 0 仍保留 HUD 三件套锚点。
        assertTrue("LapHudPage MUST keep LapLiveTopStrip", hudBody.contains("LapLiveTopStrip("))
        assertTrue("LapHudPage MUST keep HoldToEndButton", hudBody.contains("HoldToEndButton("))
    }

    @Test
    fun `camera preview is gated on settled current page 1 (off-page does not bind)`() {
        val source = readSource(LAP_LIVE_PATH)
        // v2 省电核心：CameraPreview 仅当 settledPage == 1 时渲染/绑定；回页 0 → 不在 composition → onDispose 释放。
        assertTrue(
            "LapLiveScreen.kt MUST gate CameraPreview on pagerState.settledPage == 1 (省电核心)",
            source.contains("pagerState.settledPage == 1"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST still render CameraPreview on the preview page (spec MUST 4)",
            source.contains("CameraPreview("),
        )
        // 反例：MUST NOT 再保留旧的全屏垫底 cameraEnabled toggle gate（已废弃）。
        assertFalse(
            "LapLiveScreen.kt MUST NOT keep old cameraEnabled && hasCamera full-screen gate (返工已删 toggle)",
            source.contains("cameraEnabled && hasCamera"),
        )
        assertFalse(
            "LapLiveScreen.kt MUST NOT keep old camera toggle button (Videocam icon / onToggleCamera)",
            source.contains("onToggleCamera") || source.contains("Videocam"),
        )
    }

    @Test
    fun `lap live screen reuses RequiredCameraPermissions and PermissionRequestOutcome (round 1 reuse)`() {
        val source = readSource(LAP_LIVE_PATH)
        // 权限复用（不重造）：RequiredCameraPermissions + PermissionRequestOutcome.from + 懒请求 launcher。
        assertTrue(
            "LapLiveScreen.kt MUST reuse RequiredCameraPermissions (round 1 复用，不重造)",
            source.contains("RequiredCameraPermissions"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST reuse PermissionRequestOutcome.from (round 1 三态分流)",
            source.contains("PermissionRequestOutcome.from"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST use RequestMultiplePermissions launcher (懒请求范式)",
            source.contains("RequestMultiplePermissions"),
        )
        // 无相机降级 gate 仍在（页 1 显示“无可用相机”）。
        assertTrue(
            "LapLiveScreen.kt MUST query CameraAvailability.hasCamera (round 1 复用，降级 gate)",
            source.contains("CameraAvailability.hasCamera"),
        )
    }

    @Test
    fun `lap live screen must NOT introduce VideoCapture or recording (recording is round 3)`() {
        val source = readSource(LAP_LIVE_PATH)
        // spec MUST 5 反例：本 round LapLiveScreen 也 MUST NOT 触碰录制路径。
        assertFalse(
            "LapLiveScreen.kt MUST NOT introduce VideoCapture (录制是 round 3)",
            source.contains("VideoCapture"),
        )
        assertFalse(
            "LapLiveScreen.kt MUST NOT introduce Recorder (录制是 round 3)",
            source.contains("Recorder"),
        )
    }

    @Test
    fun `lap live screen must NOT touch orientation lock or back handler`() {
        val source = readSource(LAP_LIVE_PATH)
        // 横屏锁 + BackHandler 不动（landscape + portrait 锚点都仍在）。
        assertTrue(
            "LapLiveScreen.kt MUST keep SCREEN_ORIENTATION_LANDSCAPE lock (本 round 不动横屏锁)",
            source.contains("SCREEN_ORIENTATION_LANDSCAPE"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST keep SCREEN_ORIENTATION_PORTRAIT restore (本 round 不动横屏锁)",
            source.contains("SCREEN_ORIENTATION_PORTRAIT"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST keep BackHandler (back→结束确认，本 round 不动)",
            source.contains("BackHandler"),
        )
    }

    private fun readSource(path: String): String {
        val file = File(projectRoot(), path)
        if (!file.exists()) {
            error("source file not found: ${file.absolutePath}")
        }
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
        private const val CAMERA_PREVIEW_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/CameraPreview.kt"
        private const val LAP_LIVE_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt"
    }
}
