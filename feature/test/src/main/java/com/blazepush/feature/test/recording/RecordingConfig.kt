// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

/**
 * 视频录制分辨率枚举。
 *
 * 本 round 仅定义 FHD_1080P；后续设置屏 round 按需扩展（如 HD_720P）。
 *
 * @author CC
 * @description 录制分辨率枚举（Phase 2 round 3 · camera-recording-and-gps-sync）
 * @date 2026-05-30
 */
enum class RecordingResolution {
    FHD_1080P
}

/**
 * 视频录制配置（扩展点）。
 *
 * 引擎内部通过 [resolution] + [targetFps] 选 QualitySelector，不写魔数。
 * 本 round 默认值：[RecordingResolution.FHD_1080P] + 30fps。
 * 后续设置屏 round 由外部传入不同 config，引擎 API 不变。
 *
 * @param resolution 录制分辨率
 * @param targetFps  目标帧率（Camera2 Interop hint，不保证精确锁定）
 *
 * @author CC
 * @description 录制配置 data class（扩展点，本 round 硬默认 1080p30）
 * @date 2026-05-30
 */
data class RecordingConfig(
    val resolution: RecordingResolution = RecordingResolution.FHD_1080P,
    val targetFps: Int = 30,
) {
    companion object {
        /** 本 round 硬默认配置：1080p + 30fps */
        val DEFAULT = RecordingConfig(RecordingResolution.FHD_1080P, 30)
    }
}
