# Spec: recording-lifecycle (recording-persist-across-pages-and-hud-indicator)

## Delta

这是对 `camera-recording-and-gps-sync` round spec 的**增量修订**，仅覆盖生命周期绑定、跨页持续与 HUD 指示器三个新能力。

---

## MUST 1：录制中横滑回 HUD 页，录制继续

**SHALL** 在录制状态（`RecordingState.Recording`）下，用户横滑回 page 0（HUD），`VideoCapture` 使用者**不被解绑**，录制文件继续写入。

**场景 A（正例）**：用户点击 REC → 状态变 Recording → 横滑回 page 0 → 等待 3 秒 → 滑回 page 1 → STOP → Finalize 落盘，视频时长 ≥ 3 秒，文件存在且非空。

**场景 B（正例）**：用户录制中横滑回 page 0 → 点击 HUD 角落的 RecIndicator → `engine.stopRecording()` 触发 → 等待 Finalize → 状态回 Idle。

**场景 C（反例 · 回归保障）**：未录制（Idle）时横滑回 HUD → 相机**必须**释放（`engine.unbind` 执行，`ProcessCameraProvider.unbindAll()` 被调用），FileLogger 写入"unbind: 省电释放"。

---

## MUST 2：CameraRecordingEngine 新增 bind / unbind / attachPreviewSurface / detachPreviewSurface

**SHALL** 引擎提供幂等的 `bind(lifecycleOwner, context, config)` 方法，行为与原 `bindUseCases` 相同但不接受 `previewView`（surface 独立管理）。重复调用 bind **SHALL** 安全（内部 `cameraProvider.unbindAll()` 先清旧再绑新）。

**SHALL** 提供 `attachPreviewSurface(previewView)` 方法，调用 `preview.setSurfaceProvider(previewView.surfaceProvider)`；提供 `detachPreviewSurface()` 方法，调用 `preview.setSurfaceProvider(null)`。

**场景 A（正例）**：`engine.bind(...)` 后 `engine.detachPreviewSurface()` → VideoCapture 状态不受影响，`recordingState` 保持 Recording。

**场景 B（正例）**：`engine.attachPreviewSurface(previewView)` 后预览画面恢复（PreviewView 开始渲染帧）。

**场景 C（反例）**：在 `bind` 调用之前调用 `attachPreviewSurface` → `preview` 字段为 null → 操作静默 no-op（不抛异常），FileLogger 记录 WARN。

---

## MUST 3：LapLiveScreen 顶层绑定条件 = `settledPage==1 || isRecording`

**SHALL** 在 LapLiveScreen 顶层有 `LaunchedEffect(settledPage, isRecording)`，当条件 `settledPage == 1 || isRecording` 为真时调用 `engine.bind(screenLifecycleOwner, context, config)`，为假时调用 `engine.unbind(context)`。

**场景 A（正例 · 录制中保持绑定）**：settledPage=1, isRecording=true → bind。 settledPage 变为 0，isRecording 仍 true → 再次执行 LaunchedEffect → 条件仍 true → 调用 bind（幂等，无副作用）。

**场景 B（正例 · 未录制省电）**：settledPage=0, isRecording=false → 调用 unbind。FileLogger 写入"unbind: 省电释放 reason=settledPage=0 && !isRecording"。

**场景 C（反例 · 防止绑定泄漏）**：LapLiveScreen 退出 composition（用户按 Back 结束 session） → `DisposableEffect(Unit).onDispose` 触发 → 若录制中先 `engine.stopRecording()` 再 `engine.unbind(context)` → FileLogger 写入"screen 销毁资源安全收尾"。若未录制，直接 `engine.unbind(context)`。

---

## MUST 4：HUD page 0 显示 RecIndicator（仅 Recording 态）

**SHALL** 在 `LapHudPage` 中，`recordingState is RecordingState.Recording` 时，在角落显示 RecIndicator Composable，包含：
- 闪烁红点（`animateFloatAsState` 或 `InfiniteTransition` 驱动透明度 1→0.3 循环，周期 ~800ms）
- 录制时长（格式 `mm:ss`，每秒刷新，从 `startedAtWallClock` 算 elapsed）
- 整体 clickable → `engine.stopRecording()`

**SHALL** 在非 Recording 态，RecIndicator **不显示**（条件 if/else，不用 early return）。

**场景 A（正例）**：录制中切到 page 0 → RecIndicator 显示，时长数字每秒递增。

**场景 B（正例）**：点击 RecIndicator → `stopRecording()` → 状态变 Stopping → RecIndicator 消失（非 Recording），Stopping/Idle 态不显示指示器。

**场景C（反例）**：非录制（Idle/Stopping/Error）时 page 0，RecIndicator **不得**渲染。

---

## MUST 5：V2 视觉约束合规

**SHALL** RecIndicator 内录制时长 `Text` 遵守 `maxLines = 1, overflow = TextOverflow.Ellipsis`。时长显示用 `Score` 字体（含冒号分隔符，不可用 Mechanical）。红点用 `CircleShape` + `TrackTechColors.Red`，直径 8.dp。

**场景 A（正例）**：编译期检查 RecIndicator Text 调用含 `maxLines = 1`。

**场景 B（反例）**：时长文字使用 `MetricKind.Mechanical` → 时间格式含 `:` 冒号，Mechanical 字体无法显示冒号（变形）→ MUST 使用 Score 字体。
