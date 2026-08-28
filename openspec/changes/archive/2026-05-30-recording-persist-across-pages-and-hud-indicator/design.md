# Design: recording-persist-across-pages-and-hud-indicator

## 现状（Baseline）

`CameraRecordingEngine`（`feature/test/.../recording/CameraRecordingEngine.kt`）：
- `bindUseCases(previewView, lifecycleOwner, context, config)`：绑定 Preview+VideoCapture 双 use-case，`preview.setSurfaceProvider(previewView.surfaceProvider)` 硬耦在 bind 内。
- `unbindAll(context)`：解绑所有 use-case（含录制中自动 stop）。

`RecordableCameraPreview`（`feature/test/.../ui/tracktech/RecordableCameraPreview.kt`）：
- `DisposableEffect`：进 Composable → `engine.bindUseCases`，`onDispose` → `engine.unbindAll`。

`LapLiveScreen`（`feature/test/.../ui/tracktech/LapLiveScreen.kt`）：
- HorizontalPager 2 页：page 0 = `LapHudPage`，page 1 = `CameraPreviewPage`
- page 1 仅在 `isCurrent = settledPage==1` 时渲染 `RecordableCameraPreview`
- page 0 `LapHudPage` 无任何录制状态 UI

**缺陷路径**：横滑离开 page 1 → `RecordableCameraPreview` 退出 composition → `onDispose { engine.unbindAll() }` → `ProcessCameraProvider.unbindAll()` → VideoCapture stopped → 录制中断。

## Decision 1：绑定生命周期归属（Camera bind owner）

### Alternatives

**(a) 保持 page Composable 拥有绑定**（现状，round 3 方案）
- 绑定随 page 1 进入/离开，离页解绑省电。
- ❌ 无法支持录制中跨页持续。

**(b) Screen-level LifecycleOwner + 条件驱动 bind/unbind**（本 round 选择）
- LapLiveScreen 顶层 `LaunchedEffect` 持有 `screenLifecycleOwner`（= `LocalLifecycleOwner.current`，即 Activity lifecycle），条件 `settledPage==1 || isRecording` 主动驱动 `engine.bind` / `engine.unbind`。
- ✅ 录制中 VideoCapture 绑在 Activity lifecycle，页面切换不触发解绑。
- ✅ 未录制时页面切回 HUD → `engine.unbind` → 省电省热（完整保留 round 2 行为）。
- Activity lifecycle 在 LapLiveScreen 存活期与 screen 同生，无误差。

**(c) 独立 Service / ViewModel scope**
- 将录制与 Camera 绑在 Service 或 ViewModel coroutine scope 的虚拟 LifecycleOwner。
- ✅ 生命周期彻底独立于页面。
- ❌ CameraX `bindToLifecycle` 要求真实 LifecycleOwner（`LifecycleOwner` MUST have actual lifecycle events），Service 无 Lifecycle；ViewModel scope 无法直接作为 `LifecycleOwner`。实现需引入 `LifecycleService`，复杂度高。
- ❌ 超出本 round 范围（round 6 资源安全独立 service 留 deferred）。

**选 (b)**：以最小改动达成目标，复杂度可控，与现有 Activity lifecycle 自然对齐。

## Decision 2：surface 连接策略（PreviewView → Preview）

### Alternatives

**(a) 绑定时一并设置 surface，不再支持 detach**（round 3 方案）
- bind = `preview.setSurfaceProvider(previewView.surfaceProvider)` + `bindToLifecycle`。
- ❌ bind 和 surface 强耦合，无法做到"绑定存在但 surface 不可见"。

**(b) 引入 attachPreviewSurface / detachPreviewSurface，与 bind 解耦**（本 round 选择）
- `engine.bind(...)` 只绑定 use-case（`Preview.setSurfaceProvider(null)` 占位）。
- `engine.attachPreviewSurface(previewView)` 在 page 1 进入 composition 时调用，设置 `preview.setSurfaceProvider(previewView.surfaceProvider)`。
- `engine.detachPreviewSurface()` 在 page 1 离开 composition 时调用，设置 `preview.setSurfaceProvider(null)`。
- ✅ VideoCapture 不依赖 Preview surface，录制中 detach surface 不影响视频流。
- ✅ CameraX 文档确认：`Preview.setSurfaceProvider(null)` 是合法操作，相当于"挂起预览"；VideoCapture 独立管道，不受影响。回 page 1 重新 attach 预览立即恢复。

**选 (b)**：解耦是本 round 核心。CameraX Preview use-case 无 surface 时内部维持 no-op，VideoCapture 管道独立运行。

## Decision 3：HUD 录制时长刷新方式

### Alternatives

**(a) 引擎暴露 elapsedMs StateFlow，每秒 tick**
- 引擎内起 `CoroutineScope.launch { while(recording) { delay(1000); _elapsed.value = … } }`。
- ✅ UI 侧简单 `collectAsState()`。
- ❌ 引擎承担 UI tick 职责，越界；且录制中需要一直跑协程，scope 管理复杂。

**(b) UI 层 LaunchedEffect + delay(1000) ticker，从 startedAtWallClock 算 elapsed**（本 round 选择）
- `LaunchedEffect(recordingState)` 在 `is Recording` 时进入 `while(true) { delay(1000); elapsed = System.currentTimeMillis() - startedAtWallClock }` 循环。
- ✅ 引擎保持纯粹（只暴露状态，不做 UI 节拍）。
- ✅ 时钟源直接用 `startedAtWallClock`（round 3 已有字段，与遥测同时钟域）。
- `mmss` 格式化 `mm:ss`（`%02d:%02d`.format(elapsed/60000, elapsed/1000%60)）。

**选 (b)**：职责更清晰，ticker 完全在 UI 层，可复用既有 `LaunchedEffect` 模式（`HoldToEndButton` 同款）。

## Decision 4：screen 销毁资源安全

仅做**最小资源安全保障**（防泄漏）：

LapLiveScreen 顶层 `DisposableEffect(Unit) { onDispose { if (录制中) engine.stopRecording(); engine.unbind(context) } }`。

完整资源安全（后台录制、Service 托管）留 round 6 deferred。本 round 只保证"退出 screen 时录制句柄不泄漏"。

## Risks 与 Mitigation

| Risk | Mitigation |
|---|---|
| Preview use-case 无 surface 时 CameraX 报错 | CameraX 文档确认 `setSurfaceProvider(null)` 合法；Preview.Builder 无 surface 时 no-op；VideoCapture 管道独立 |
| `LaunchedEffect(settledPage, isRecording)` 多次快速触发导致 bind/unbind 竞态 | `engine.bind` / `engine.unbind` 设计为幂等（重复调用安全）；绑定前 `cameraProvider.unbindAll()` 保证一致性 |
| screen 销毁与录制 stop 竞态（onDispose 中 stop + unbind 顺序） | `stopRecording()` 仅发信号（`activeRecording.stop()`），VideoRecordEvent.Finalize 异步到来；`unbind` 后 Finalize 仍可回调（CameraX executor 独立）；`engineScope.launch` 内写库不依赖 camera 绑定 |
| HUD REC 时长刷新在 page 0 → recompose 触发频率（1次/s） | 1Hz ticker 影响极小；仅 `is Recording` 态才跑 ticker，其余态 `LaunchedEffect` 立即结束 |
| Activity rotation 导致 screen 重建 / LapLiveScreen 重组 | `DisposableEffect(Unit)` 随 screen 生命周期触发 onDispose + 重绑；`SCREEN_ORIENTATION_LANDSCAPE` 强制已锁定，重建风险极低 |
