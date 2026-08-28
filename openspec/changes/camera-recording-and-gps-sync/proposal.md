# Proposal：camera-recording-and-gps-sync

## 1. 问题溯源

Phase 2 round 2（session-video-metadata-persist）已打通：
- Room entity（TelemetrySessionEntity）新增 `videoFilePath` / `videoStartedAtWallClock` 字段
- `TelemetryRepository.attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock)` 可写库
- CameraX `Preview` use-case 已绑定到 LapLiveScreen 页 1（CameraPreview.kt）

但 App 目前**无录制能力**：只能预览，不能保存视频文件，无法建立视频时间轴与遥测时间轴的同步锚点。

## 2. 当前 Baseline

- `CameraPreview.kt`：只绑 `Preview` use-case，`onDispose` 时 `unbindAll()`。
- `LapLiveScreen.kt`：HorizontalPager 2 页，页 1 = `CameraPreviewPage` → `CameraPreview`（已有权限门、isCurrent gate）。
- `TelemetryRepository.activeSessionId`：private var，未对外暴露。`activeLapSessionId` 在 `TestSessionViewModel`，同样 private。
- 遥测时钟域：`startSession()` → `startTs = System.currentTimeMillis()`，binary 样本 `absoluteTsMs = startTs + tsDeltaMs`。**同时钟域：`System.currentTimeMillis()`（wallClock）**。
- `FileLogger` 异步批量落盘（debug_log.txt），API：`FileLogger.d/v/e(tag, msg, throwable?)`。

## 3. 用户场景

**核心场景**：赛道驾驶 → 横滑到相机页 → 点 REC 开始录制（手动触发，不随 session 自动开始）→ 完成圈速 → 点 STOP → 视频文件落盘 → 自动关联当前 active session → 回放时视频与遥测时间轴对齐。

**边缘场景**：
- 录制时无 active session（用户未开始 lap session）→ 视频存盘但不关联（孤立视频）。
- 录制中途跳回页 0 → 录制不中断（UI 离开不影响录制引擎）。
- 相机被占 / 设备过热 → VideoCapture bind 失败 → 降级 + FileLogger 记 error。
- 权限缺失 → 录制请求拒绝 + 提示。

## 4. 解决方案

1. **录制引擎** `CameraRecordingEngine`：放 `feature/test`（依赖 FileLogger + 可调 attachVideoToSession，无需跨 module 依赖纠缠）。
2. **状态机** `RecordingState` sealed class + `StateFlow<RecordingState>` 暴露给 UI。
3. **wallClock 锚点**：`VideoRecordEvent.Start` 回调中取 `System.currentTimeMillis()` → 与遥测 absoluteTsMs 同时钟域。
4. **UI 最小接入**：`CameraPreviewPage` 叠加 start/stop 按钮 + 录制状态文字。
5. **同步纯函数** `VideoTelemetrySync`：`frameWallClock = videoStartedAtWallClock + framePtsMs`；反查最近邻 sample index。完整单测。

## 5. 范围

**本 round 包含**：
- CameraRecordingEngine（VideoCapture + Recorder + 状态机）
- RecordingState / RecordingConfig data class
- VideoTelemetrySync 纯函数 + 单测
- LapLiveScreen / CameraPreviewPage 接入最小 start/stop 按钮
- TestSessionViewModel 加 `getActiveLapSessionId()` public accessor
- 所有 FileLogger 埋点

**本 round 不含**：
- 精致 REC 红点 UI（round 5）
- 录制设置屏（分辨率/帧率可配，round 后续）
- 视频回放播放器

## 6. 成功指标

1. 真机：点 start → 能录制 → 点 stop → `filesDir/video/` 下生成 .mp4 文件。
2. 真机：录制期间有 active lap session → stop 后 Room 里对应 session 的 `videoFilePath` / `videoStartedAtWallClock` 非空。
3. 单测：VideoTelemetrySync 全绿。
4. 编译：`gradle :feature:test:compileDebugKotlin --offline` 零错误。
