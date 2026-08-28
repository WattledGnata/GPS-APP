# Design：camera-recording-and-gps-sync

## 现状（Baseline）

### core/camera
- `CameraAvailability.kt`：仅查 hasCamera。
- `build.gradle.kts`：CameraX 1.3.4，`api(camera-lifecycle, camera-view)`；`implementation(camera-core, camera-camera2, camera-video)`。camera-video 已在 core:camera 依赖中，但 **feature:test 通过 `implementation(core:camera)` 只透出 `api` 层**（camera-lifecycle/camera-view），无法直接 import `VideoCapture`/`Recorder`。

### feature/test UI
- `CameraPreview.kt`：只绑 Preview use-case，`onDispose` `unbindAll()`。
- `LapLiveScreen.kt`：`CameraPreviewPage` 三态（无相机/无权限/有相机），`isCurrent` gate 控制 `CameraPreview` 是否在组合树。

### 数据层
- `TelemetryRepository.attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)`：签名已存在，round 2 打通。
- `TestSessionViewModel.activeLapSessionId`：**private var**，外部无法读取 → 需要加 public accessor。

### 时钟域
- `startSession()` → `startTs = System.currentTimeMillis()`（wallClock）
- binary 样本 `absoluteTsMs = startTs + tsDeltaMs`（wallClock，同源）
- 视频录制锚点 `videoStartedAtWallClock` 需与上述同时钟域 → MUST 用 `System.currentTimeMillis()`。

---

## Decision 1：录制引擎放哪个模块

### Alternatives
- **A（选定）**：引擎放 `feature/test`（包 `com.blazepush.feature.test.recording`）
  - 优点：FileLogger（feature/test 的）、TelemetryRepository（core/data）、TestSessionViewModel（feature/test）都在可访问范围内；不需要跨 module 解耦。
  - 缺点：引擎含 Android 依赖（Context、CameraX），无法纯 JVM 单测（但录制引擎本身也无法 mock CameraX，单测价值低；ValueObject/纯函数部分分开放）。
- **B**：引擎放 `core/camera`
  - 优点：CameraX 依赖本来就在 core/camera。
  - 缺点：core/camera 无法依赖 feature/test 的 FileLogger；core/camera 无法调 TelemetryRepository（core/data）（core 模块间循环依赖）；更深的依赖解耦工作超出本 round scope。
- **C**：新建 feature/camera module
  - 优点：职责清晰。
  - 缺点：新 module 引入需写 build.gradle.kts + 配置 settings.gradle.kts + 调整 Koin 模块，工作量超出本 round scope，且 feature/test 已是大模块，内部包分组即可管理。

**选 A：feature/test 内新增 `recording` 包，无需新 module。**

---

## Decision 2：VideoCapture 类在 feature/test 的依赖获取方式

### Alternatives
- **A（选定）**：把 `core/camera` 的 `camera-video` 从 `implementation` 改为 `api`（仅此一个 lib），让 feature/test 能直接 import `VideoCapture`/`Recorder`/`QualitySelector`。
  - 理由：最小改动；camera-video API 只有录制引擎需要，其他 feature/test 代码不会误用。
- **B**：feature/test build.gradle.kts 直接加 `implementation(libs.androidx.camera.video)`（重复声明）
  - 问题：与 core/camera 的 implementation 版本一致时可行，但冗余且违反单一来源。
- **C**：录制引擎移到 core/camera，只暴露接口给 feature/test
  - 依赖图问题（Decision 1 已分析）。

**选 A：core/camera build.gradle.kts 将 camera-video 改为 `api`。**

---

## Decision 3：fps 控制手段（CameraX 1.3.4）

### Alternatives
- **A（选定）**：`QualitySelector.from(Quality.FHD)` 锁 1080p，fps 通过 Camera2 Interop 尝试设定 30fps，但在 risks 中透明声明：CameraX 1.3.4 不保证 fps 精确锁定，实际帧率由设备和 QualitySelector 决定。不阻塞本 round 功能验证。
  - 具体：`Camera2Interop.Extender(Recorder.Builder()).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))`。
- **B**：仅用 `QualitySelector.from(Quality.FHD)`，不设 fps（由设备决定）。
  - 优点：最简单，零风险。
  - 缺点：帧率可能 24/25/30 不确定，路测数据不一致。
- **C**：`FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)` 降级策略
  - 不适用：本 round 只要 1080p，降级策略是 round 5 设置屏的事。

**选 A：尝试 Camera2Interop 设 30fps，risks 声明不保证精确控制。**

---

## Decision 4：无 active session 时的录制行为

### Alternatives
- **A（选定）**：**允许录制但视频为孤立视频**——录制正常进行，stop 落盘后不调 `attachVideoToSession`（无关联），FileLogger 记 WARN 级别的 "no active session, video orphaned"。
  - 理由：用户手动触发录制，不强制依赖 lap session 存在；孤立视频将来可在回放屏做手动关联（后续 round）；禁止录制更反直觉（用户手动按了 REC 却提示"不能录制"）。
- **B**：禁止录制（无 active session 时 start 按钮 disabled）
  - 缺点：与"录制触发与 session 解耦"原则矛盾；UI 逻辑复杂；录制功能的验证依赖先开 lap session。
- **C**：自动触发新 session
  - 缺点：违反"录制触发 = 手动"的产品决策；session 生命周期边界混乱。

**选 A：允许孤立录制，WARN 级别日志。**

---

## Decision 5：wallClock 锚点取点

### 分析

CameraX 录制事件序列：
```
startRecording()
  → VideoRecordEvent.Start（首帧已捕获，录制引擎已启动）
  → VideoRecordEvent.Status（周期性，含已录时长）
  → VideoRecordEvent.Finalize（录制结束，含最终文件信息）
```

`VideoRecordEvent.Start` 是首帧捕获后的最早回调。此时取 `System.currentTimeMillis()` 作为 `videoStartedAtWallClock`。

**时钟域论证**：
- 遥测 binary：`absoluteTsMs = sessionStartTs + tsDeltaMs`，其中 `sessionStartTs = System.currentTimeMillis()`。
- 视频锚点：`videoStartedAtWallClock = System.currentTimeMillis()`（在 `VideoRecordEvent.Start` 回调取）。
- 两者均为 `System.currentTimeMillis()`（Linux epoch, realtime clock）→ **同时钟域**，差值有意义。
- 视频帧的 `presentationTimeUs`（PTS）是相对录制开始的单调时钟，需 +`videoStartedAtWallClock` 才转成 wallClock。

**已知偏差**：`VideoRecordEvent.Start` 取的 wallClock 比真实首帧写入时刻有 ~1-5ms 的回调延迟（取决于 Main thread 调度延迟）。本 round 接受此偏差（< GPS 采样间隔 40ms @25Hz），risks 声明。

---

## Decision 6：RecordingConfig 扩展点

`RecordingConfig(resolution: RecordingResolution, targetFps: Int)` data class，本 round 硬默认 `RecordingResolution.FHD_1080P + targetFps = 30`。引擎内部按 config 选 QualitySelector，不用魔数。

---

## 架构图

```
LapLiveScreen
  └── CameraPreviewPage
        ├── CameraPreview（Preview use-case，已有）
        ├── RecordButton（新）→ viewModel.cameraRecordingEngine
        └── RecordingStateText（新）→ collectAsState

TestSessionViewModel
  ├── cameraRecordingEngine: CameraRecordingEngine（新，注入 telemetryRepository）
  ├── getActiveLapSessionId(): String?（新 public accessor）
  └── ...

CameraRecordingEngine（feature/test/recording）
  ├── _recordingState: MutableStateFlow<RecordingState>
  ├── startRecording(context, lifecycleOwner, config)
  ├── stopRecording()
  └── VideoTelemetrySync（纯函数，独立 object）

VideoTelemetrySync（feature/test/recording）
  ├── frameWallClock(videoStartedAtWallClock, framePtsMs) → Long
  └── findNearestSampleIndex(frameWallClock, samples: List<Long>) → Int
```

---

## Risks + Mitigations

| Risk | 影响 | Mitigation |
|---|---|---|
| fps 实际无法精确锁 30fps | 回放同步略偏差 | risks 透明声明；wallClock 锚点是同步真相源，fps 不影响正确性 |
| VideoRecordEvent.Start wallClock 有 ~1-5ms 回调延迟 | 同步偏差 < 5ms | GPS 25Hz = 40ms/帧，偏差在采样间隔内；路测可用 FileLogger 记录 wallClock 值验证 |
| Preview + VideoCapture 双 use-case 设备兼容性 | 某些低端设备不支持同时绑两个 use-case | runCatching 包 bindToLifecycle；bind 失败降级预览黑屏 + FileLogger.e |
| 录制发热耗电 | 赛道长时录制设备过热 | Road-test-first 真机路测观察；本 round 不添加降级逻辑（后续 round）|
| CameraPreview.kt 当前只绑 Preview，改为 Preview+VideoCapture 会 unbindAll | CameraPreview.kt 的 onDispose 需重构 | 新建 RecordableCameraPreview.kt 替代原 CameraPreview；原 CameraPreview.kt 保持不变（录制页面用新的）|
| Camera2Interop 在 CameraX 1.3.4 的稳定性 | 设备厂商实现差异 | runCatching 包设置；失败时 fallback 无 fps hint（仍可录制）|
