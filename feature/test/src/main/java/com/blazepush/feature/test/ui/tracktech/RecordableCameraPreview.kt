// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.blazepush.feature.test.recording.CameraRecordingEngine
import com.blazepush.feature.test.recording.RecordingConfig

/**
 * 可录制相机预览 Composable（Preview + VideoCapture 双 use-case）。
 *
 * 与原 [CameraPreview] 的区别：
 * - 同时绑 `Preview` + `VideoCapture<Recorder>` 两个 use-case（录制能力）。
 * - 需要传入 [CameraRecordingEngine]，由引擎管理 use-case 绑定 + 录制状态。
 * - `onDispose` 调用 `engine.unbindAll()`（含进行中录制的自动 stop 逻辑）。
 *
 * **原 [CameraPreview] 保持不变**（契约测试 grep 断言不含录制类名字面量）。
 *
 * 生命周期：DisposableEffect 内调用 `engine.bindUseCases`；
 * 离开页面 / Composable dispose → `engine.unbindAll()` 释放相机。
 *
 * @param engine  录制引擎实例（由 Koin inject 或传入）
 * @param config  录制配置（默认 1080p30）
 * @param modifier Compose modifier
 *
 * @author CC
 * @description 可录制相机预览 Composable（Phase 2 round 3 · camera-recording-and-gps-sync）
 * @date 2026-05-30
 */
@Composable
fun RecordableCameraPreview(
    engine: CameraRecordingEngine,
    config: RecordingConfig = RecordingConfig.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView },
    )

    DisposableEffect(lifecycleOwner, previewView, engine) {
        // 绑定 Preview + VideoCapture 双 use-case
        engine.bindUseCases(
            previewView = previewView,
            lifecycleOwner = lifecycleOwner,
            context = context,
            config = config,
        )

        onDispose {
            // 离开页面 / Composable dispose → 解绑相机（含进行中录制的自动 stop）
            engine.unbindAll(context)
        }
    }
}
