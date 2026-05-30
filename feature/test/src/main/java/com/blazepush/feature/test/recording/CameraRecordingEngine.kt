// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import android.content.Context
import androidx.annotation.MainThread
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.feature.test.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 相机录制引擎（CameraX VideoCapture + Recorder）。
 *
 * ## 职责
 * - 管理 Preview + VideoCapture 双 use-case 的 bindToLifecycle。
 * - 暴露 [recordingState] StateFlow 给 UI 读取录制状态。
 * - 录制 Start → VideoRecordEvent.Start 取 wallClock 锚点 → 写入 [RecordingState.Recording]。
 * - 录制 Stop → VideoRecordEvent.Finalize 落盘 → 调 [TelemetryRepository.attachVideoToSession]。
 * - 无 active session（sessionId == null）时允许录制但不关联，FileLogger WARN。
 * - 密集 FileLogger 埋点（road-test-first 模式唯一事后诊断手段）。
 *
 * ## fps 控制
 * CameraX 1.3.4 的 `Recorder.Builder` 不实现 `ExtendableBuilder`，无法用 Camera2Interop 设 fps hint。
 * 帧率由 QualitySelector（FHD_1080P → Quality.FHD）和设备 Camera HAL 自行决定，通常为 30fps。
 * Risk 已在 design.md Decision 3 透明声明。config.targetFps 字段保留作扩展点，本 round 不实际控制。
 *
 * ## 时钟域
 * wallClock = System.currentTimeMillis()（与遥测 absoluteTsMs 同时钟域）。
 * 在 VideoRecordEvent.Start 回调取 wallClock，不在 startRecording 调用处取
 * （避免 bind + 首帧延迟引入偏差；Start 事件是首帧捕获后的最早确认点）。
 *
 * ## wallClock 持久化（重要）
 * [_capturedWallClock] 在 VideoRecordEvent.Start 时写入，在 Finalize 时读取。
 * 即使状态已从 Recording 变为 Stopping，wallClock 仍可通过此字段获取。
 *
 * ## 线程安全
 * [startRecording] / [stopRecording] MUST 在主线程调用（CameraX 要求主线程操作）。
 * [_recordingState] 更新通过 StateFlow 传播；[_capturedWallClock] / [_capturedSessionId]
 * 仅在主线程回调（VideoRecordEvent listener 使用 MainExecutor）里读写，无竞态。
 *
 * @author CC
 * @description CameraX 录制引擎（Phase 2 round 3 · camera-recording-and-gps-sync）
 * @date 2026-05-30
 */
class CameraRecordingEngine(
    private val telemetryRepository: TelemetryRepository,
) {
    companion object {
        private const val TAG = "CamRec"
        private const val VIDEO_DIR = "video"
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState

    // CameraX 句柄（bind 成功后赋值，unbind / Error 时清空）
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Start 事件时持久化，供 Finalize 分支读取（即使 state 已从 Recording 变 Stopping）
    // 仅在 MainExecutor 回调内读写，无竞态
    private var _capturedWallClock: Long = 0L
    private var _capturedSessionId: String? = null

    /**
     * 绑定 Preview + VideoCapture 双 use-case。
     *
     * 注意：此方法由 RecordableCameraPreview Composable 在 DisposableEffect 内调用（主线程）。
     * bind 失败 → state = Error，UI 降级（预览黑屏）。
     *
     * fps 控制：CameraX 1.3.4 的 Recorder.Builder 不支持 Camera2Interop（不实现 ExtendableBuilder）。
     * 帧率由 QualitySelector + 设备 HAL 决定，通常 30fps。
     *
     * @param previewView    CameraX PreviewView（已在 Composable 创建）
     * @param lifecycleOwner Composable 的 LocalLifecycleOwner
     * @param context        用于获取 ProcessCameraProvider + MainExecutor
     * @param config         录制配置（分辨率；fps 字段本 round 不实际控制，留扩展点）
     */
    @MainThread
    fun bindUseCases(
        previewView: androidx.camera.view.PreviewView,
        lifecycleOwner: LifecycleOwner,
        context: Context,
        config: RecordingConfig = RecordingConfig.DEFAULT,
    ) {
        FileLogger.d(TAG, "bindUseCases: config=$config (fps hint 在 1.3.4 Recorder.Builder 上不可用，由设备决定)")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val bindResult = runCatching {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Recorder：按 config.resolution 选 QualitySelector（FHD_1080P → Quality.FHD）
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        when (config.resolution) {
                            RecordingResolution.FHD_1080P -> QualitySelector.from(Quality.FHD)
                        }
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)

                // unbindAll 再 bind，避免重复 bind 或旧 use-case 冲突（CameraPreview 也会 unbindAll，此处覆盖）
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    vc,
                )
                vc
            }

            bindResult.onSuccess { vc ->
                videoCapture = vc
                FileLogger.d(TAG, "bindUseCases: Preview+VideoCapture 双 use-case 绑定 OK")
            }.onFailure { t ->
                videoCapture = null
                val msg = "bindUseCases 失败: ${t.message}"
                _recordingState.value = RecordingState.Error(msg)
                FileLogger.e(TAG, msg, t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 解绑所有 use-case（Composable onDispose 调用）。
     * 若录制进行中，先 stop 再 unbind。
     */
    @MainThread
    fun unbindAll(context: Context) {
        if (_recordingState.value is RecordingState.Recording) {
            FileLogger.d(TAG, "unbindAll: active recording detected, requesting stop first")
            stopRecording()
        }
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }.onFailure { t ->
            FileLogger.e(TAG, "unbindAll failed", t)
        }
        videoCapture = null
        FileLogger.d(TAG, "unbindAll: camera unbound")
    }

    /**
     * 开始录制。MUST 在主线程调用。
     * 权限检查由调用方完成（LapLiveScreen 的 cameraPermissionGranted gate）。
     *
     * @param context         Application / Activity context（用于 filesDir + MainExecutor）
     * @param activeSessionId 当前 active lap session id；null = 无 active session（孤立录制）
     */
    @MainThread
    fun startRecording(
        context: Context,
        activeSessionId: String?,
    ) {
        FileLogger.d(TAG, "startRecording: request sessionId=$activeSessionId state=${_recordingState.value::class.simpleName}")

        val vc = videoCapture
        if (vc == null) {
            val msg = "startRecording: videoCapture 未绑定，请先调 bindUseCases"
            FileLogger.e(TAG, msg)
            _recordingState.value = RecordingState.Error(msg)
            return
        }

        if (_recordingState.value is RecordingState.Recording || _recordingState.value is RecordingState.Stopping) {
            FileLogger.d(TAG, "startRecording: 已在录制中，忽略重复请求")
            return
        }

        if (activeSessionId == null) {
            FileLogger.d(TAG, "startRecording: WARN 无 active lap session → 孤立视频，录制完成不关联 session")
        }

        // 确保 video 目录存在
        val videoDir = File(context.filesDir, VIDEO_DIR)
        if (!videoDir.exists()) {
            val created = videoDir.mkdirs()
            FileLogger.d(TAG, "startRecording: video 目录创建 ${videoDir.absolutePath} created=$created")
        }

        val outputFile = File(videoDir, "${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        // 将 sessionId 持久化到实例字段，供 Finalize 分支读取（不依赖 state 值）
        _capturedSessionId = activeSessionId

        val pendingRecording = vc.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()

        val recording = pendingRecording.start(
            ContextCompat.getMainExecutor(context),
        ) { event ->
            handleVideoRecordEvent(event, outputFile)
        }
        activeRecording = recording
        FileLogger.d(TAG, "startRecording: Recording 启动，等待 VideoRecordEvent.Start 回调")
    }

    /**
     * 停止录制。MUST 在主线程调用。
     * Idle 状态下调用为 no-op。
     */
    @MainThread
    fun stopRecording() {
        FileLogger.d(TAG, "stopRecording: request state=${_recordingState.value::class.simpleName}")

        if (_recordingState.value !is RecordingState.Recording) {
            FileLogger.d(TAG, "stopRecording: 非 Recording 状态，忽略")
            return
        }

        val rec = activeRecording
        if (rec == null) {
            FileLogger.d(TAG, "stopRecording: activeRecording 为 null，重置到 Idle")
            _recordingState.value = RecordingState.Idle
            return
        }

        _recordingState.value = RecordingState.Stopping
        rec.stop()
        FileLogger.d(TAG, "stopRecording: stop() 已发出，等待 VideoRecordEvent.Finalize")
    }

    /**
     * 处理 CameraX VideoRecordEvent 回调（运行在 MainExecutor）。
     */
    private fun handleVideoRecordEvent(
        event: VideoRecordEvent,
        outputFile: File,
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                // ★ wallClock 锚点核心（同时钟域关键时刻）：
                //   VideoRecordEvent.Start = 首帧捕获后的最早回调，此时取 System.currentTimeMillis()。
                //   与遥测 absoluteTsMs = sessionStartTs + tsDeltaMs 同为 wallClock（Linux epoch）。
                val wallClock = System.currentTimeMillis()
                _capturedWallClock = wallClock
                val sessionId = _capturedSessionId

                _recordingState.value = RecordingState.Recording(
                    startedAtWallClock = wallClock,
                    sessionId = sessionId,
                )
                FileLogger.d(
                    TAG,
                    "VideoRecordEvent.Start: ★ wallClock=$wallClock sessionId=$sessionId " +
                        "(同时钟域: System.currentTimeMillis, 与遥测 absoluteTsMs 可直接差值对齐)",
                )
                if (sessionId == null) {
                    FileLogger.d(TAG, "VideoRecordEvent.Start: WARN 孤立录制，无 active session")
                }
            }

            is VideoRecordEvent.Status -> {
                // 高频状态更新，VERBOSE 级别，默认 DEBUG level 下不写入文件（不刷爆日志）
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                FileLogger.v(TAG, "VideoRecordEvent.Status: elapsed=${durationMs}ms")
            }

            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                if (event.hasError()) {
                    val errMsg = "VideoRecordEvent.Finalize ERROR: code=${event.error} cause=${event.cause?.message}"
                    FileLogger.e(TAG, errMsg)
                    _recordingState.value = RecordingState.Error(errMsg)
                } else {
                    val filePath = outputFile.absolutePath
                    val fileSize = outputFile.length()
                    val wallClock = _capturedWallClock
                    val sessionId = _capturedSessionId
                    FileLogger.d(TAG, "VideoRecordEvent.Finalize: OK path=$filePath size=${fileSize}B wallClock=$wallClock")

                    if (sessionId != null) {
                        FileLogger.d(TAG, "attachVideoToSession: 调用 sessionId=$sessionId path=$filePath wallClock=$wallClock")
                        engineScope.launch {
                            runCatching {
                                telemetryRepository.attachVideoToSession(
                                    sessionId = sessionId,
                                    videoFilePath = filePath,
                                    videoStartedAtWallClock = wallClock,
                                )
                            }.onSuccess {
                                FileLogger.d(TAG, "attachVideoToSession: 写库 OK")
                            }.onFailure { t ->
                                FileLogger.e(TAG, "attachVideoToSession: 写库失败", t)
                            }
                        }
                    } else {
                        FileLogger.d(TAG, "attachVideoToSession: SKIP（孤立视频 sessionId=null，不写库）")
                    }

                    // 清空临时状态字段
                    _capturedWallClock = 0L
                    _capturedSessionId = null
                    _recordingState.value = RecordingState.Idle
                }
            }

            else -> {
                FileLogger.v(TAG, "VideoRecordEvent: 未处理事件类型 ${event::class.simpleName}")
            }
        }
    }

    /**
     * 重置 Error 状态到 Idle（UI 重试入口）。
     */
    fun resetError() {
        if (_recordingState.value is RecordingState.Error) {
            _recordingState.value = RecordingState.Idle
            FileLogger.d(TAG, "resetError: state reset to Idle")
        }
    }
}
