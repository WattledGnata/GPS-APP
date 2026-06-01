// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.core.content.ContextCompat
import com.blazepush.feature.test.FileLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 进设置屏时查询的设备录制能力（spec Decision 6）。
 *
 * @param supportedResolutions 设备支持的分辨率集合（用于 4K 灰显 + [resolveEffectiveResolution] 降级）
 * @param evRange              曝光补偿 index 范围（用于 EV 滑块范围 + [clampEv]）。不支持曝光补偿时为 0..0
 */
data class RecordingCapabilities(
    val supportedResolutions: Set<RecordingResolution>,
    val evRange: IntRange,
    /** 每个曝光 index 对应的 EV 档（如 1/6≈0.167）；用于把 index 换算成直观 EV 档显示。不支持时 0f。 */
    val evStep: Float = 0f,
) {
    companion object {
        /** 探测失败兜底：只认 1080p、无曝光补偿。 */
        val FALLBACK = RecordingCapabilities(setOf(RecordingResolution.FHD_1080P), 0..0, 0f)
    }
}

/**
 * 设备录制能力探测器。免绑定查询：`cameraProvider.availableCameraInfos` +
 * `CameraSelector.filter` 取对应朝向 CameraInfo，再查 supported qualities + 曝光范围。
 *
 * recording-params-config-screen round · spec Decision 6。前后摄能力不同 → 按 facing 分别查。
 */
class RecordingCapabilityDetector {

    private companion object {
        const val TAG = "RecCapDetect"
    }

    suspend fun detect(context: Context, facing: CameraFacing): RecordingCapabilities {
        val provider = runCatching { awaitProvider(context) }.getOrNull()
            ?: run {
                FileLogger.e(TAG, "detect: cameraProvider 获取失败，返回 FALLBACK")
                return RecordingCapabilities.FALLBACK
            }

        val selector = when (facing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val info = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
            ?: run {
                FileLogger.d(TAG, "detect: facing=$facing 无对应相机，返回 FALLBACK")
                return RecordingCapabilities.FALLBACK
            }

        val qualities = runCatching {
            Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        }.getOrDefault(emptyList())
        val resolutions = qualities.mapNotNull { it.toRecordingResolutionOrNull() }.toSet()
            .ifEmpty { setOf(RecordingResolution.FHD_1080P) }

        val expState = info.exposureState
        val evSupported = expState.isExposureCompensationSupported
        val evRange = if (evSupported) {
            val r = expState.exposureCompensationRange
            r.lower..r.upper
        } else {
            0..0
        }
        val evStep = if (evSupported) {
            val s = expState.exposureCompensationStep
            s.numerator.toFloat() / s.denominator.toFloat()
        } else {
            0f
        }

        val caps = RecordingCapabilities(resolutions, evRange, evStep)
        FileLogger.d(TAG, "detect: facing=$facing caps=$caps")
        return caps
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.cancel(it) }
            }, ContextCompat.getMainExecutor(context))
        }
}
