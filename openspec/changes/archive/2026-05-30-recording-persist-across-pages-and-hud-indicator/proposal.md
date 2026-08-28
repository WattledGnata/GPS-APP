# Proposal: recording-persist-across-pages-and-hud-indicator

## Why

Phase 2 round 3 `camera-recording-and-gps-sync`（commit `8b83de7`）真机路测暴露两个缺陷：

**缺陷 1：录制中横滑回 HUD 页，录制即断**

根因：`RecordableCameraPreview` 的 `DisposableEffect` 持有 `engine.bindUseCases` 和 `engine.unbindAll` 的完整生命周期控制权。HorizontalPager 离开 page 1 → page 1 Composable 销毁 → `onDispose { engine.unbindAll() }` 执行 → `ProcessCameraProvider.unbindAll()` → CameraX 自动停止所有 use-case 包括 VideoCapture → 录制中断落盘。

核心设计错误：**录制生命周期被错绑在"预览页是否可见"上**，应跟"录制 session"走，独立于页面可见性。

**缺陷 2：录制中回 HUD 页，没有任何录制状态 UI**

用户回到 HUD 专注驾驶，不知道录制是否还在，且无法在 HUD 页停止录制（需要再滑回 page 1 操作）。

## What Changes

1. **CameraRecordingEngine 重构**：新增 `bind(lifecycleOwner, context, config)` / `unbind(context)` / `attachPreviewSurface(previewView)` / `detachPreviewSurface()` 四个方法，把"绑定 use-case"与"连接 PreviewView surface"解耦。旧 `bindUseCases(previewView, lifecycleOwner, context, config)` 保留兼容不删。

2. **LapLiveScreen 顶层控制绑定条件**：`LaunchedEffect(settledPage, isRecording)` 驱动 `engine.bind` / `engine.unbind`，条件 `settledPage == 1 || isRecording`。`DisposableEffect(Unit)` 在 screen 销毁时安全收尾（止录 + unbind）。

3. **RecordableCameraPreview 改为仅 attach/detach surface**：`DisposableEffect` 内改为 `engine.attachPreviewSurface(previewView)` / `engine.detachPreviewSurface()`，不再 unbindAll。

4. **HUD page 0 加 RecIndicator**：仅 `Recording` 态显示，红点 + 录制时长 + 整体可点停止。

## Scope

- 修改文件：`CameraRecordingEngine.kt`、`LapLiveScreen.kt`、`RecordableCameraPreview.kt`
- 不改动：GPS 接收链路、replay 协议、binary writer、gpsData.timestamp、其余 round 代码
