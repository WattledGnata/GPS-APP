# Specs：camera-recording-and-gps-sync

## MUST 1：RecordingConfig data class

SHALL 定义 `RecordingConfig(resolution: RecordingResolution, targetFps: Int)` 和 `RecordingResolution.FHD_1080P` enum。

Scenario 1（正常）：`RecordingConfig(RecordingResolution.FHD_1080P, 30)` 构造不抛。  
Scenario 2（扩展点）：将来 `RecordingResolution.HD_720P` 加入 enum 不破坏现有代码。  
Scenario 3（反例）：引擎内部绝不出现魔数 `Quality.FHD` 字面量——SHALL 通过 config.resolution 分发。

## MUST 2：RecordingState sealed class

SHALL 定义：
- `Idle`
- `Recording(startedAtWallClock: Long, sessionId: String?)`（sessionId null = 孤立录制）
- `Stopping`（可选，stop 请求已发但 Finalize 未完成）
- `Error(message: String)`

Scenario 1（正常流）：Idle → start → Recording → stop → Idle 状态转移不抛异常。  
Scenario 2（错误流）：VideoCapture bind 失败 → RecordingState.Error（含错误描述）。  
Scenario 3（反例）：stop 请求在 Idle 状态下 SHALL 无副作用（不抛不崩）。

## MUST 3：CameraRecordingEngine 行为

SHALL 暴露 `val recordingState: StateFlow<RecordingState>`。  
SHALL 在 `VideoRecordEvent.Start` 回调取 `System.currentTimeMillis()` 为 `videoStartedAtWallClock`。  
SHALL 在 `VideoRecordEvent.Finalize` 成功后调 `attachVideoToSession(sessionId, path, wallClock)` （若 sessionId 非 null）。  
SHALL 输出文件路径格式：`<filesDir>/video/<timestamp_ms>.mp4`。  
SHALL 在录制 start 前确保 `filesDir/video/` 目录存在。

Scenario 1（正常有 session）：start（sessionId=X）→ VideoRecordEvent.Start → 取 wallClock → Finalize 成功 → attachVideoToSession(X, path, wallClock) 被调用。  
Scenario 2（孤立录制）：start（sessionId=null）→ Finalize 成功 → attachVideoToSession 不被调用 → FileLogger WARN "no active session, video orphaned"。  
Scenario 3（反例 bind 失败）：bindToLifecycle 抛异常 → state = Error → attachVideoToSession 不被调用。

## MUST 4：Preview + VideoCapture 双 use-case 绑定

SHALL 在 `RecordableCameraPreview` Composable 内同时绑 `Preview` + `VideoCapture<Recorder>`。  
绑定失败（设备不支持双 use-case）SHALL 降级：FileLogger.e + state = Error，不崩 UI。

Scenario 1（正常）：双 use-case 绑定成功，预览可见且可录制。  
Scenario 2（降级）：bindToLifecycle 抛 `IllegalArgumentException`（use-case 冲突）→ Error 状态，预览黑屏。  
Scenario 3（反例）：旧 `CameraPreview.kt` SHALL NOT 被修改（原合约测试 grep 断言不变）。

## MUST 5：wallClock 同步纯函数

`VideoTelemetrySync.frameWallClock(videoStartedAtWallClock: Long, framePtsMs: Long): Long` SHALL 返回 `videoStartedAtWallClock + framePtsMs`。

`VideoTelemetrySync.findNearestSampleIndex(frameWallClock: Long, sampleWallClocks: List<Long>): Int` SHALL：
- 空列表返回 0（或抛 IllegalArgumentException，由实现选择，spec 锁定反例）。
- frameWallClock < sampleWallClocks.first() → 返回 0。
- frameWallClock > sampleWallClocks.last() → 返回 last index。
- 精确命中 → 返回命中 index。
- 两样本中点（等距）→ 返回较小 index（最近邻取前者）。
- 非等距 → 返回距离最近的 index。

Scenario 1（首帧 pts=0）：`frameWallClock(1000L, 0L)` == 1000L。  
Scenario 2（中间帧偏移）：`frameWallClock(1000L, 500L)` == 1500L。  
Scenario 3（早于首样本）：`findNearestSampleIndex(50L, [100L, 200L, 300L])` == 0。  
Scenario 4（晚于末样本）：`findNearestSampleIndex(400L, [100L, 200L, 300L])` == 2。  
Scenario 5（精确命中）：`findNearestSampleIndex(200L, [100L, 200L, 300L])` == 1。  
Scenario 6（两样本中点）：`findNearestSampleIndex(150L, [100L, 200L])` == 0（取前者）。  
Scenario 7（反例，空列表）：`findNearestSampleIndex(100L, emptyList())` 抛 `IllegalArgumentException` 或返回 0——单测必须覆盖此边界，锁定实现选择。

## MUST 6：TestSessionViewModel 公开 activeLapSessionId

SHALL 加 `fun getActiveLapSessionId(): String?` 或等价 public accessor，供 `CameraRecordingEngine` 录制 start 时读取。

Scenario 1（有 active session）：lap session 进行中 → `getActiveLapSessionId()` 返回非 null。  
Scenario 2（无 active session）：lap session 未开始 / 已结束 → 返回 null。  
Scenario 3（反例）：SHALL NOT 破坏现有 `activeLapSessionId` 的私有写（仍由 VM 内部赋值）。

## MUST 7：FileLogger 密集埋点

以下 call site SHALL 有 FileLogger 埋点（tag 以 "CamRec" 为前缀）：

1. `startRecording()` 请求进入（INFO：sessionId + config）
2. VideoCapture bindToLifecycle 成功（DEBUG）
3. VideoCapture bindToLifecycle 失败（ERROR + throwable）
4. 权限缺失（WARN）
5. `VideoRecordEvent.Start`：记录 `videoStartedAtWallClock` 值 + `sessionId`（INFO）
6. `VideoRecordEvent.Status`：记录已录时长（VERBOSE，可被过滤）
7. `stopRecording()` 请求（DEBUG）
8. `VideoRecordEvent.Finalize` 成功：记录文件路径 + 文件大小（INFO）
9. `VideoRecordEvent.Finalize` 失败：记录 error code（ERROR）
10. `attachVideoToSession` 调用（DEBUG：sessionId + path + wallClock）
11. 无 active session 孤立录制（WARN）
12. `filesDir/video/` 目录创建（DEBUG）

Scenario 1（正常流）：完整录制一次，FileLogger 中按序出现 MUST 7 条目 1、2、5、7、8、10。  
Scenario 2（孤立录制）：条目 11 出现，条目 10 不出现。  
Scenario 3（反例 25Hz status 不刷爆日志）：Status 事件用 FileLogger.v()（VERBOSE 级别），默认 DEBUG level 下不写入文件。

## MUST 8：Compose UI 最小接入

`CameraPreviewPage` 内（有相机且有权限 + isCurrent 分支）SHALL：
- 渲染 `RecordableCameraPreview`（替代 `CameraPreview`）。
- 叠加 start/stop 按钮。
- 叠加录制状态文字（"REC" / "● RECORDING" / 空）。
- 所有新增 Text 遵守 `maxLines = 1, overflow = TextOverflow.Ellipsis`。
- 所有分支 SHALL 用 `if/else`，绝不 `return@Box`/`return@Column`（M2 崩溃教训）。

Scenario 1（Idle → 按 start）：按钮文字由"REC ▶"变为"STOP ■"，状态文字出现"● RECORDING"。  
Scenario 2（Recording → 按 stop）：文字恢复 Idle 状态。  
Scenario 3（反例 return）：代码中绝不出现 `return@CameraPreviewPage` / `return@Box` 等 early return。
