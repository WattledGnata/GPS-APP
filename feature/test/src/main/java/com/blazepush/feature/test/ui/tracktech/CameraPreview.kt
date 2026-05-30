// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.blazepush.feature.test.FileLogger

/**
 * 相机实时预览 Composable（camera-preview-in-laplivescreen round · Phase 2 第二刀）。
 *
 * 形态（Decision 1 + 3）：`AndroidView(PreviewView)` 当 LapLiveScreen 背景层，
 * 只绑 CameraX `Preview` use-case（`CameraSelector.DEFAULT_BACK_CAMERA`）。
 *
 * **本 round MUST NOT 绑录制 use-case / MUST NOT 任何文件写入**（录制 use-case 是 round 3）。
 * 契约测试 CameraPreviewContractTest grep 断言本文件不出现录制相关类名字面量。
 *
 * 生命周期（spec MUST 6）：`DisposableEffect` 内 `ProcessCameraProvider.getInstance` future
 * 回调（主线程 executor）里 `unbindAll()` + `bindToLifecycle(Preview)`；`onDispose` 调
 * `unbindAll()` 释放相机，避免离屏 / 关开关后相机被占用 + 耗电泄漏。
 *
 * bind 失败包 `runCatching` 不冒泡（相机被占 / 无相机 → 预览黑屏降级，不崩 LapLiveScreen）。
 *
 * @author CC
 * @description CameraX Preview-only 嵌入 LapLiveScreen 当背景取景层
 * @date 2026-05-30
 */
@Composable
fun CameraPreview(modifier: Modifier = Modifier) {
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

    DisposableEffect(lifecycleOwner, previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val bindResult = runCatching {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // 先解绑再绑，避免重复 bind 抛 already-bound（Decision 3 Alt A 教训）。
                cameraProvider.unbindAll()
                // 只绑 Preview use-case；不绑任何录制 use-case（spec MUST 5；录制 use-case 是 round 3）。
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
            }
            bindResult.onSuccess {
                FileLogger.d("CamPreview", "preview bound to back camera (Preview-only)")
            }.onFailure { t ->
                // 失败不冒泡：相机被占 / bind 异常 → 预览黑屏降级，不崩主屏（road-test-first 日志兜底）。
                FileLogger.e("CamPreview", "preview bind failed", t)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // 离开 LapLiveScreen / 关相机开关 / Composable dispose → 释放相机（spec MUST 6）。
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }.onFailure { t ->
                FileLogger.e("CamPreview", "preview unbind on dispose failed", t)
            }
            FileLogger.d("CamPreview", "preview disposed, camera unbound")
        }
    }
}
