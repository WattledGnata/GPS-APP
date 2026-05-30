// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.recording.CameraRecordingEngine
import com.blazepush.feature.test.recording.RecordingConfig

/**
 * 可录制相机预览 Composable（Preview + VideoCapture 双 use-case）。
 *
 * ## recording-persist-across-pages-and-hud-indicator round 改造
 *
 * 本 Composable **不再**管理 Camera use-case 的绑定生命周期。
 * 绑定/解绑由 LapLiveScreen 顶层 LaunchedEffect 统一控制（screen-level lifecycle owner）。
 *
 * 本 Composable 只负责：
 * - 进入 composition → [CameraRecordingEngine.attachPreviewSurface]（连接预览画面）
 * - 离开 composition → [CameraRecordingEngine.detachPreviewSurface]（断开预览画面）
 *
 * 录制中横滑离开 page 1：`detachPreviewSurface` 执行，VideoCapture 继续录制，
 * 仅预览画面停止渲染（CameraX `setSurfaceProvider(null)` 合法 no-op for VideoCapture pipeline）。
 * 回到 page 1：`attachPreviewSurface` 恢复预览。
 *
 * @param engine  录制引擎实例（由 Koin inject 或传入）
 * @param config  录制配置（参数保留向后兼容，此 Composable 不再使用）
 * @param modifier Compose modifier
 *
 * @author CC
 * @description 相机预览 Composable - 仅管理 surface 连接（round 4 改造）
 * @date 2026-05-30
 */
@Composable
fun RecordableCameraPreview(
    engine: CameraRecordingEngine,
    config: RecordingConfig = RecordingConfig.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView },
    )

    DisposableEffect(previewView, engine) {
        // 仅连接 surface：VideoCapture 绑定由 LapLiveScreen 顶层 LaunchedEffect 管理
        FileLogger.d("CamRec", "RecordableCameraPreview: attachPreviewSurface（page 1 进入 composition）")
        engine.attachPreviewSurface(previewView)

        onDispose {
            // 仅断开 surface：录制中 VideoCapture 继续，不 unbindAll
            FileLogger.d("CamRec", "RecordableCameraPreview: detachPreviewSurface（page 1 离开 composition）")
            engine.detachPreviewSurface()
        }
    }
}
