// @IgnoreFormatCheck
package com.blazepush.core.domain.permission

/**
 * 相机录制所需运行时权限（camera-module-and-permission round · L0-2 录音频已定）。
 * CAMERA + RECORD_AUDIO 都是 API 1+ dangerous，无 SDK 版本分支（不像 BLE 的 S+ 分流）。
 * 复用 [PermissionRequestOutcome] 做 AllGranted / MissingPermissions 判定（与 BLE/Location 流并存）。
 *
 * @author CC
 * @description 相机+音频权限集（Phase 2 第一刀，懒请求，不在 app 启动强请求）
 * @date 2026-05-30
 */
object RequiredCameraPermissions {
    private const val CAMERA = "android.permission.CAMERA"
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"

    fun forSdk(@Suppress("UNUSED_PARAMETER") sdkInt: Int): List<String> {
        return listOf(CAMERA, RECORD_AUDIO)
    }
}
