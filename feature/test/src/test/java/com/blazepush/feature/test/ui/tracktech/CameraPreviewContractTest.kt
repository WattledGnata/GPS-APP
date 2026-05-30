// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 纯文本 grep 风格 contract test —— 锁定 camera-preview-in-laplivescreen round 的核心契约：
 *
 * 1. `CameraPreview.kt` 只绑 CameraX `Preview` use-case（`bindToLifecycle` + `Preview`），
 *    MUST NOT 绑 `VideoCapture` / `Recorder` / 任何文件写入（spec MUST 5 反例——录制是 round 3）。
 * 2. `LapLiveScreen.kt` 正确接入 camera toggle：`cameraEnabled` 默认关 + `RequiredCameraPermissions`
 *    懒请求 + `CameraAvailability.hasCamera` 降级 gate + `CameraPreview` 预览层（spec MUST 1/2/4/7）。
 * 3. `LapLiveScreen.kt` 同样 MUST NOT 引入 VideoCapture / 录制路径（spec MUST 5）。
 * 4. `DisposableEffect` + `unbindAll` 解绑释放相机锚点存在（spec MUST 6）。
 *
 * 不依赖 Robolectric / Compose runtime / Android Context。仅读源文件文本断言（参
 * LapLiveScreenOrientationContractTest + PresetTrackAssetTest 的 projectRoot 范式避 working dir 坑）。
 *
 * @author CC
 * @description camera preview wiring + no-recording contract test
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
    fun `lap live screen wires camera toggle with opt-in default and lazy permission`() {
        val source = readSource(LAP_LIVE_PATH)
        // spec MUST 1：默认关 opt-in。
        assertTrue(
            "LapLiveScreen.kt MUST default cameraEnabled to false (opt-in，spec MUST 1)",
            source.contains("cameraEnabled by remember { mutableStateOf(false) }"),
        )
        // spec MUST 2：懒请求复用 RequiredCameraPermissions + PermissionRequestOutcome。
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
    }

    @Test
    fun `lap live screen uses hasCamera gate and renders CameraPreview layer`() {
        val source = readSource(LAP_LIVE_PATH)
        // spec MUST 7：无相机机型降级 gate。
        assertTrue(
            "LapLiveScreen.kt MUST query CameraAvailability.hasCamera (round 1 复用，降级 gate)",
            source.contains("CameraAvailability.hasCamera"),
        )
        // spec MUST 4：渲染 CameraPreview 预览层。
        assertTrue(
            "LapLiveScreen.kt MUST render CameraPreview as background layer (spec MUST 4)",
            source.contains("CameraPreview("),
        )
        // 预览层 gate 在 cameraEnabled && hasCamera（双 gate 防无相机/未授权时 bind）。
        assertTrue(
            "LapLiveScreen.kt MUST gate CameraPreview on cameraEnabled && hasCamera",
            source.contains("cameraEnabled && hasCamera"),
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
    fun `lap live screen must NOT touch orientation lock or lap timing logic`() {
        val source = readSource(LAP_LIVE_PATH)
        // spec MUST 4：横屏锁不动（landscape + portrait 锚点都仍在）。
        assertTrue(
            "LapLiveScreen.kt MUST keep SCREEN_ORIENTATION_LANDSCAPE lock (本 round 不动横屏锁)",
            source.contains("SCREEN_ORIENTATION_LANDSCAPE"),
        )
        assertTrue(
            "LapLiveScreen.kt MUST keep SCREEN_ORIENTATION_PORTRAIT restore (本 round 不动横屏锁)",
            source.contains("SCREEN_ORIENTATION_PORTRAIT"),
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
