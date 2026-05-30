// @IgnoreFormatCheck
package com.blazepush.core.camera

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * 相机可用性查询（camera-module-and-permission round）：仅查 hasCamera，**不开相机/不预览/不录制**。
 * 无相机机型 → false（录制功能 disabled）。回调在主线程。
 *
 * @author CC
 * @description CameraX ProcessCameraProvider 可用性查询骨架（Phase 2 第一刀）
 * @date 2026-05-30
 */
object CameraAvailability {
    fun hasCamera(context: Context, onResult: (Boolean) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val available = runCatching {
                future.get().availableCameraInfos.isNotEmpty()
            }.getOrDefault(false)
            onResult(available)
        }, ContextCompat.getMainExecutor(context))
    }
}
