// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

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
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Bug A 修复：attach/bind 时序解耦。attachPreviewSurface 可能先于 bind 异步回调到来。
    // 先存引用，bind 成功后补连；若 preview 已就绪则立即连。
    private var pendingPreviewView: PreviewView? = null

    // Bug B 修复：bind/unbind 幂等守卫，防 isRecording key 抖动触发重复 bind 打断录制。
    private var isBound = false
    private var boundLifecycleOwner: LifecycleOwner? = null

    // recording-params-config-screen round：
    // - boundConfig 记当前生效 config，供 startRecording 读 audioEnabled + 幂等守卫比对（config 变才 rebind）
    // - camera 句柄供曝光控制（cameraControl.setExposureCompensationIndex）
    private var boundConfig: RecordingConfig? = null
    private var camera: Camera? = null

    // Start 事件时持久化，供 Finalize 分支读取（即使 state 已从 Recording 变 Stopping）
    // 仅在 MainExecutor 回调内读写，无竞态
    private var _capturedWallClock: Long = 0L
    private var _capturedSessionId: String? = null

    // stopRecording 落盘完成回调（onDispose 路径延迟 unbind 用）。
    // 在 VideoRecordEvent.Finalize（OK 或 ERROR 两分支）末尾 invoke，然后清空。
    // 仅在 MainExecutor 回调内读写，无竞态。
    private var pendingOnFinalized: (() -> Unit)? = null

    // =========================================================================
    // 新 API（recording-persist-across-pages-and-hud-indicator round）
    // 把"绑定 use-case"与"连接 PreviewView surface"解耦：
    //   bind()                  — 绑定 Preview+VideoCapture（不设 surface，由调用方 attach）
    //   unbind()                — 解绑（含录制中先 stop）
    //   attachPreviewSurface()  — 设置 Preview.setSurfaceProvider(previewView.surfaceProvider)
    //   detachPreviewSurface()  — 设置 Preview.setSurfaceProvider(null)（录制中 detach 安全）
    // =========================================================================

    /**
     * 绑定 Preview + VideoCapture 双 use-case（screen-level lifecycle owner）。
     *
     * 本方法由 LapLiveScreen 顶层 LaunchedEffect 驱动，条件 settledPage==1 || isRecording。
     * 不接受 previewView：surface 连接由 [attachPreviewSurface] 独立管理。
     * 幂等：内部 cameraProvider.unbindAll() + 重新绑定，重复调用安全。
     *
     * @param lifecycleOwner screen 级 LifecycleOwner（Activity lifecycle，非 page Composable）
     * @param context        Application / Activity context
     * @param config         录制配置
     */
    @MainThread
    fun bind(
        lifecycleOwner: LifecycleOwner,
        context: Context,
        config: RecordingConfig = RecordingConfig.DEFAULT,
    ) {
        // Bug B 修复：幂等守卫——同一 lifecycleOwner + 同 config 已绑定时直接 no-op，防 isRecording key 抖动重复 bind 打断录制。
        // config 变化（用户改录制参数返回）时不 no-op，落到下方重新 bind 应用新参数（design Decision 7）。
        if (isBound && boundLifecycleOwner === lifecycleOwner && boundConfig == config) {
            FileLogger.d(TAG, "bind: no-op（已绑定同一 lifecycleOwner + 同 config），幂等跳过")
            return
        }
        FileLogger.d(TAG, "bind: lifecycleOwner=${lifecycleOwner::class.simpleName} config=$config isBound=$isBound")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val bindResult = runCatching {
                val cameraProvider = cameraProviderFuture.get()

                // 摄像头朝向（recording-params-config-screen round · spec 前后置 Requirement）
                val selector = when (config.cameraFacing) {
                    CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                }

                // 设备能力 → 实际生效分辨率（4K 不支持降级；spec Decision 5 / memo M2）
                val supportedRes = selector.filter(cameraProvider.availableCameraInfos).firstOrNull()
                    ?.let { runCatching { Recorder.getVideoCapabilities(it).getSupportedQualities(DynamicRange.SDR) }.getOrDefault(emptyList()) }
                    ?.mapNotNull { q -> q.toRecordingResolutionOrNull() }
                    ?.toSet()
                    ?.ifEmpty { setOf(RecordingResolution.FHD_1080P) }
                    ?: setOf(RecordingResolution.FHD_1080P)
                val effectiveRes = resolveEffectiveResolution(config.resolution, supportedRes)

                // 对焦：LOCKED_INFINITY → Camera2Interop 锁无限远；CONTINUOUS_AUTO 不附加（走 HAL 默认）
                val previewBuilder = Preview.Builder()
                applyFocusInterop(previewBuilder, config.focusMode)
                val newPreview = previewBuilder.build()
                // 不设 surface：surface 由 attachPreviewSurface 独立驱动
                // 若已有 pendingPreviewView（attach 先于 bind 到达），bind 后补连（Bug A 修复路径）

                // 清晰度：fromOrderedList + FallbackStrategy（绝不 from(UHD)，spec 反例 / memo M2）
                val recorder = Recorder.Builder()
                    .setQualitySelector(buildQualitySelector(effectiveRes))
                    .build()
                val vc = VideoCapture.withOutput(recorder)

                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    newPreview,
                    vc,
                )
                // 曝光：clamp 到设备范围再下发（spec 曝光反例 / memo M5）
                applyExposure(cam, config.exposureCompensationEv)

                FileLogger.d(
                    TAG,
                    "bind apply: req=${config.resolution} effective=$effectiveRes facing=${config.cameraFacing} " +
                        "audio=${config.audioEnabled} focus=${config.focusMode} ev=${config.exposureCompensationEv}",
                )
                Triple(newPreview, vc, cam)
            }

            bindResult.onSuccess { (newPreview, vc, cam) ->
                preview = newPreview
                videoCapture = vc
                camera = cam
                boundConfig = config
                isBound = true
                boundLifecycleOwner = lifecycleOwner
                // Bug A 修复：bind 成功后检查 pendingPreviewView，若 attach 已先行到达则补连 surface。
                val pv = pendingPreviewView
                if (pv != null) {
                    newPreview.setSurfaceProvider(pv.surfaceProvider)
                    FileLogger.d(TAG, "bind: 补连 pendingPreviewView surface（attach 先于 bind 到达）previewView=$pv")
                } else {
                    FileLogger.d(TAG, "bind: Preview+VideoCapture 双 use-case 绑定 OK（surface 待 attachPreviewSurface）")
                }
            }.onFailure { t ->
                preview = null
                videoCapture = null
                camera = null
                boundConfig = null
                isBound = false
                boundLifecycleOwner = null
                val msg = "bind 失败: ${t.message}"
                _recordingState.value = RecordingState.Error(msg)
                FileLogger.e(TAG, msg, t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 解绑所有 use-case（幂等）。若录制进行中先 stop 再 unbind。
     *
     * 由 LapLiveScreen 顶层 LaunchedEffect（省电路径）或 DisposableEffect.onDispose（screen 销毁）调用。
     *
     * @param context Application / Activity context
     * @param reason  日志原因描述，供路测 FileLogger 诊断
     */
    @MainThread
    fun unbind(context: Context, reason: String = "省电释放") {
        // Bug B 修复：幂等守卫——未绑定时直接 no-op，防重复 unbind。
        if (!isBound) {
            FileLogger.d(TAG, "unbind: no-op（未绑定），幂等跳过 reason=$reason")
            return
        }
        if (_recordingState.value is RecordingState.Recording) {
            FileLogger.d(TAG, "unbind: reason=$reason 录制中，先 stopRecording")
            stopRecording()
        }
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }.onFailure { t ->
            FileLogger.e(TAG, "unbind failed: reason=$reason", t)
        }
        preview = null
        videoCapture = null
        camera = null
        boundConfig = null
        isBound = false
        boundLifecycleOwner = null
        FileLogger.d(TAG, "unbind: camera 已释放 reason=$reason")
    }

    /**
     * 连接 PreviewView surface 到 Preview use-case（page 1 进入 composition 时调用）。
     *
     * 录制中亦可 attach（VideoCapture 管道独立，attach surface 仅恢复预览画面）。
     * 若 [bind] 尚未调用（preview=null），记 WARN 并 no-op（不抛异常）。
     *
     * @param previewView 目标 PreviewView
     */
    @MainThread
    fun attachPreviewSurface(previewView: PreviewView) {
        // Bug A 修复：无论 preview 是否就绪，先存 pendingPreviewView。
        // 若 preview 已就绪（bind 已完成）则立即连接；否则等 bind 的 onSuccess 补连。
        pendingPreviewView = previewView
        val p = preview
        if (p == null) {
            FileLogger.d(TAG, "attachPreviewSurface: preview=null（bind 尚未完成），暂存 pendingPreviewView，等 bind 后补连")
            return
        }
        p.setSurfaceProvider(previewView.surfaceProvider)
        FileLogger.d(TAG, "attachPreviewSurface: surface 已连接（preview 已就绪）previewView=$previewView")
    }

    /**
     * 断开 PreviewView surface（page 1 离开 composition 时调用）。
     *
     * 录制中调用：VideoCapture 继续录制，仅预览画面停止渲染（setSurfaceProvider(null) 合法）。
     * 未绑定（preview=null）时为 no-op。
     */
    @MainThread
    fun detachPreviewSurface() {
        // Bug A 修复：清除 pendingPreviewView，防止 bind 后错误补连已离开 composition 的 PreviewView。
        pendingPreviewView = null
        val p = preview
        if (p == null) {
            FileLogger.d(TAG, "detachPreviewSurface: preview=null，no-op（pendingPreviewView 已清除）")
            return
        }
        p.setSurfaceProvider(null)
        val isRec = _recordingState.value is RecordingState.Recording
        FileLogger.d(TAG, "detachPreviewSurface: surface 已断开（isRecording=$isRec，VideoCapture 继续）pendingPreviewView 已清除")
    }

    // =========================================================================
    // 旧 API（Deprecated - 由上方新 API 替代）
    // =========================================================================

    /**
     * 绑定 Preview + VideoCapture 双 use-case。
     *
     * @deprecated 使用 [bind] + [attachPreviewSurface] / [detachPreviewSurface] 替代。
     *             本方法保留仅防旧调用方编译断，新代码不得使用。
     */
    @Deprecated(
        message = "使用 bind(lifecycleOwner, context, config) + attachPreviewSurface(previewView) 替代",
        replaceWith = ReplaceWith("bind(lifecycleOwner, context, config)"),
    )
    @MainThread
    fun bindUseCases(
        previewView: androidx.camera.view.PreviewView,
        lifecycleOwner: LifecycleOwner,
        context: Context,
        config: RecordingConfig = RecordingConfig.DEFAULT,
    ) {
        FileLogger.d(TAG, "bindUseCases: DEPRECATED，转发到 bind + attachPreviewSurface")
        bind(lifecycleOwner, context, config)
        // 异步 bind 完成后 preview 可能尚未就绪；这里直接 attach 会 no-op（WARN log）。
        // 保持旧行为：调用方进 DisposableEffect 时同步执行，bind 异步回调后 preview 才有值。
        // DEPRECATED 方法不保证 surface 即时连接，新代码请用 attachPreviewSurface。
        attachPreviewSurface(previewView)
    }

    /**
     * 解绑所有 use-case（Composable onDispose 调用）。
     *
     * @deprecated 使用 [unbind] 替代。保留防旧调用方编译断。
     */
    @Deprecated(
        message = "使用 unbind(context, reason) 替代",
        replaceWith = ReplaceWith("unbind(context)"),
    )
    @MainThread
    fun unbindAll(context: Context) {
        FileLogger.d(TAG, "unbindAll: DEPRECATED，转发到 unbind")
        unbind(context, reason = "unbindAll（旧 API）")
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

        // 麦克风：按 bind 时生效 config 的 audioEnabled 条件调用（spec 麦克风 Requirement）
        val audioEnabled = boundConfig?.audioEnabled ?: true
        val pendingRecording = vc.output
            .prepareRecording(context, outputOptions)
            .let { if (audioEnabled) it.withAudioEnabled() else it }
        FileLogger.d(TAG, "startRecording: audioEnabled=$audioEnabled")

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
     *
     * @param onFinalized 落盘完成（VideoRecordEvent.Finalize OK 或 ERROR）后回调。
     *   null = 不需要回调（手动 STOP 按钮路径）。
     *   非 null = onDispose 路径：等落盘完成后才执行 unbind，避免 VideoCapture 管道被提前拆断。
     *   无论 Finalize OK/ERROR 都会 invoke，保证 onDispose 路径 camera 不泄漏。
     */
    @MainThread
    fun stopRecording(onFinalized: (() -> Unit)? = null) {
        FileLogger.d(TAG, "stopRecording: request state=${_recordingState.value::class.simpleName} hasCallback=${onFinalized != null}")

        if (_recordingState.value !is RecordingState.Recording) {
            FileLogger.d(TAG, "stopRecording: 非 Recording 状态，忽略")
            // 非录制中时 onFinalized 不会通过 Finalize 触发，直接 invoke 防调用方 camera 泄漏
            onFinalized?.invoke()
            return
        }

        val rec = activeRecording
        if (rec == null) {
            FileLogger.d(TAG, "stopRecording: activeRecording 为 null，重置到 Idle")
            _recordingState.value = RecordingState.Idle
            onFinalized?.invoke()
            return
        }

        // 存储回调；Finalize OK/ERROR 两分支都会 invoke + 清空
        pendingOnFinalized = onFinalized
        _recordingState.value = RecordingState.Stopping
        rec.stop()
        FileLogger.d(TAG, "stopRecording: stop() 已发出，等待 VideoRecordEvent.Finalize（callback=${onFinalized != null}）")
    }

    /**
     * 停止录制并挂起等待 Finalize 落盘完成（停圈速退出路径核心）。
     *
     * ## 真根因背景（修 SOURCE_INACTIVE）
     * camera 绑在 screen 级 LifecycleOwner（NavBackStackEntry）。停圈速退出若立即 popBackStack，
     * NavBackStackEntry 进 DESTROYED → CameraX lifecycle-aware 自动停 camera → 正在录的 VideoCapture
     * 源失活 → Finalize ERROR_SOURCE_INACTIVE（code=4），moov 未写完视频损坏 + attachVideoToSession 不调。
     *
     * 修复主路径：onConfirmEnd 退出前先 await 本方法落盘完成，**期间不 popBackStack**（screen/
     * NavBackStackEntry 保持存活 → camera lifecycle active → 落盘正常 OK + attachVideoToSession 写库），
     * 落盘完成后才退出。
     *
     * 复用 [stopRecording] 的 onFinalized 回调（OK / ERROR / 非录制态三分支都触发），用
     * [suspendCancellableCoroutine] 桥接成 suspend。
     *
     * MUST 在主线程调用（内部 stopRecording 走 CameraX，要求主线程）。
     *
     * @param timeoutMs Finalize 超时上限（默认 8s）。超时也返回（继续退出），FileLogger WARN。
     *   防 Finalize 始终不回调时永久挂起。
     */
    @MainThread
    suspend fun stopRecordingAndAwait(timeoutMs: Long = 8000L) {
        FileLogger.d(TAG, "stopRecordingAndAwait: 进入 state=${_recordingState.value::class.simpleName} timeoutMs=$timeoutMs")
        val finished = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                // stopRecording 的 onFinalized 在 Finalize OK/ERROR 两分支 + 非 Recording 直接路径都会 invoke
                stopRecording {
                    FileLogger.d(TAG, "stopRecordingAndAwait: onFinalized 触发，resume")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        if (finished == null) {
            FileLogger.e(TAG, "stopRecordingAndAwait: WARN Finalize 在 ${timeoutMs}ms 内未回调，超时返回，继续退出")
        } else {
            FileLogger.d(TAG, "stopRecordingAndAwait: 落盘完成（或非录制态直返），返回")
        }
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
                    // ERROR 分支也必须 invoke onFinalized，防 onDispose 路径 camera 永不解绑泄漏
                    val cb = pendingOnFinalized
                    pendingOnFinalized = null
                    FileLogger.d(TAG, "VideoRecordEvent.Finalize ERROR: invoke pendingOnFinalized=${cb != null}")
                    cb?.invoke()
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
                        // video-storage-cleanup round：无 active session = UI 不可达孤儿 → 删（不堆垃圾，Decision 4）。
                        // 白名单防御：只删 filesDir/video/ 下文件（outputFile 由引擎生成，必在此目录）。
                        val deleted = outputFile.absolutePath.contains("/video/") && outputFile.delete()
                        FileLogger.d(
                            TAG,
                            "Finalize: 无 session 孤儿 sessionId=null → ${if (deleted) "已删" else "未删(白名单不符/删失败)"} path=$filePath",
                        )
                    }

                    // 清空临时状态字段
                    _capturedWallClock = 0L
                    _capturedSessionId = null
                    _recordingState.value = RecordingState.Idle
                    // OK 分支：invoke onFinalized（onDispose 路径在此触发延迟 unbind）
                    val cb = pendingOnFinalized
                    pendingOnFinalized = null
                    FileLogger.d(TAG, "VideoRecordEvent.Finalize OK: invoke pendingOnFinalized=${cb != null}")
                    cb?.invoke()
                }
            }

            else -> {
                FileLogger.v(TAG, "VideoRecordEvent: 未处理事件类型 ${event::class.simpleName}")
            }
        }
    }

    // =========================================================================
    // recording-params-config-screen round：config 参数 → CameraX 应用
    // =========================================================================

    /**
     * 按生效分辨率构造 QualitySelector。fromOrderedList + FallbackStrategy，
     * 设备不支持首选档时自动降级，绝不用 from(UHD)（在不支持设备上直接崩，spec 反例 / memo M2）。
     */
    private fun buildQualitySelector(res: RecordingResolution): QualitySelector {
        val primary = res.toQuality()
        val ordered = listOf(primary, Quality.FHD, Quality.HD, Quality.SD).distinct()
        return QualitySelector.fromOrderedList(ordered, FallbackStrategy.lowerQualityOrHigherThan(primary))
    }

    /**
     * 对焦：LOCKED_INFINITY 时对 Preview.Builder 附加 Camera2Interop（AF_MODE_OFF + LENS_FOCUS_DISTANCE=0f）。
     * CONTINUOUS_AUTO 不附加，走 HAL 默认连续自动对焦。设备不支持 manual AF 时 HAL 忽略 option，安全退化不崩。
     */
    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun applyFocusInterop(builder: Preview.Builder, mode: FocusMode) {
        if (mode != FocusMode.LOCKED_INFINITY) return
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        FileLogger.d(TAG, "applyFocusInterop: LOCKED_INFINITY → AF_MODE_OFF + LENS_FOCUS_DISTANCE=0f（无限远）")
    }

    /**
     * 曝光：clamp 到设备范围再下发（越界 index 部分设备会抛，spec 曝光反例 / memo M5）。
     * 设备不支持曝光补偿时跳过。
     */
    private fun applyExposure(cam: Camera, requestedEv: Int) {
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            FileLogger.d(TAG, "applyExposure: 设备不支持曝光补偿，跳过 requested=$requestedEv")
            return
        }
        val r = state.exposureCompensationRange
        val clamped = clampEv(requestedEv, r.lower..r.upper)
        cam.cameraControl.setExposureCompensationIndex(clamped)
        FileLogger.d(TAG, "applyExposure: requested=$requestedEv clamped=$clamped range=${r.lower}..${r.upper}")
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
