// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import androidx.camera.video.Quality

/**
 * 视频录制分辨率枚举。
 *
 * recording-params-config-screen round：扩展 UHD_4K / HD_720P（原仅 FHD_1080P）。
 * 4K 不是所有设备都支持 → 选 4K 时引擎走 [resolveEffectiveResolution] 降级（spec Decision 5 / memo M2）。
 *
 * @author CC
 * @date 2026-05-30 / 扩展 2026-06-01
 */
enum class RecordingResolution {
    UHD_4K,
    FHD_1080P,
    HD_720P,
}

/** 摄像头朝向。 */
enum class CameraFacing {
    BACK,
    FRONT,
}

/**
 * 对焦模式。
 * - [CONTINUOUS_AUTO]：HAL 默认连续自动对焦（不附加 Camera2Interop）。
 * - [LOCKED_INFINITY]：锁定无限远（Camera2Interop `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE=0f`），
 *   赛道远景拍摄避免来回拉焦（用户路测核心诉求）。
 */
enum class FocusMode {
    CONTINUOUS_AUTO,
    LOCKED_INFINITY,
}

/**
 * 视频录制配置。
 *
 * recording-params-config-screen round：从「仅 resolution+targetFps」扩展到 5 类可配参数。
 * 全部带默认值 → 旧调用 [RecordingConfig.DEFAULT] 不破（向后兼容）。
 *
 * @param resolution            录制分辨率（4K 设备不支持时引擎降级）
 * @param targetFps             目标帧率（本 round UI 不暴露 60fps，字段保留作扩展点；memo M3/M6）
 * @param audioEnabled          是否录音频（false → 不调 withAudioEnabled，文件无音轨）
 * @param cameraFacing          前/后置摄像头
 * @param focusMode             对焦模式（连续自动 / 锁无限远）
 * @param exposureCompensationEv 曝光补偿 index（引擎下发前经 [clampEv] 夹到设备范围内）
 *
 * @author CC
 * @date 2026-05-30 / 扩展 2026-06-01
 */
data class RecordingConfig(
    val resolution: RecordingResolution = RecordingResolution.FHD_1080P,
    val targetFps: Int = 30,
    val audioEnabled: Boolean = true,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val focusMode: FocusMode = FocusMode.CONTINUOUS_AUTO,
    val exposureCompensationEv: Int = 0,
) {
    companion object {
        /** 默认配置：1080p / 30fps / 开麦 / 后置 / 连续自动对焦 / EV 0 */
        val DEFAULT = RecordingConfig()
    }
}

/**
 * 把 [RecordingResolution] 映射到 CameraX [Quality]（穷举 when，扩枚举漏分支编译即断）。
 * 仅在引擎 bind 时用；纯函数 [resolveEffectiveResolution] 不依赖 CameraX，便于单测。
 */
fun RecordingResolution.toQuality(): Quality = when (this) {
    RecordingResolution.UHD_4K -> Quality.UHD
    RecordingResolution.FHD_1080P -> Quality.FHD
    RecordingResolution.HD_720P -> Quality.HD
}

/** 把 CameraX [Quality] 反映射到 [RecordingResolution]（只认 UHD/FHD/HD，其余 SD/LOWEST 等忽略）。 */
internal fun Quality.toRecordingResolutionOrNull(): RecordingResolution? = when (this) {
    Quality.UHD -> RecordingResolution.UHD_4K
    Quality.FHD -> RecordingResolution.FHD_1080P
    Quality.HD -> RecordingResolution.HD_720P
    else -> null
}

/**
 * 纯函数：按设备实际支持的分辨率集合，决定 [requested] 的实际生效分辨率（降级）。
 *
 * 偏好顺序：请求档优先，不支持则向下降级（UHD→FHD→HD），都不支持取 supported 中任一档，
 * supported 为空（理论不会）兜底 FHD。CameraX-free → 可 JVM 单测穷举（spec Decision 5 / memo M2）。
 *
 * @param requested  用户请求分辨率
 * @param supported  设备支持的分辨率集合（由 RecordingCapabilityDetector 从 Quality 列表映射）
 */
fun resolveEffectiveResolution(
    requested: RecordingResolution,
    supported: Set<RecordingResolution>,
): RecordingResolution {
    val preference = when (requested) {
        RecordingResolution.UHD_4K -> listOf(RecordingResolution.UHD_4K, RecordingResolution.FHD_1080P, RecordingResolution.HD_720P)
        RecordingResolution.FHD_1080P -> listOf(RecordingResolution.FHD_1080P, RecordingResolution.HD_720P, RecordingResolution.UHD_4K)
        RecordingResolution.HD_720P -> listOf(RecordingResolution.HD_720P, RecordingResolution.FHD_1080P, RecordingResolution.UHD_4K)
    }
    return preference.firstOrNull { it in supported }
        ?: supported.firstOrNull()
        ?: RecordingResolution.FHD_1080P
}

/**
 * 纯函数：把请求的曝光 EV index 夹到设备支持范围内（spec 曝光 Requirement / memo M5）。
 * 越界请求绝不直接下发 setExposureCompensationIndex（部分设备会抛异常）。
 */
fun clampEv(requested: Int, range: IntRange): Int =
    requested.coerceIn(range.first, range.last)
