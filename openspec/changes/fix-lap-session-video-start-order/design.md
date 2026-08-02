## Context

旧时间线是：进页只建内存 `LapSession.Ready` → 第一个同步 GPS 帧只建 previous → 第二帧才 `TelemetryRepository.startSession()`。但 Camera 页在这之前已允许 REC，引擎又接受 `sessionId = null`，因此正常主路径存在无主录像。

## Goals / Non-Goals

**Goals:**

- 以“进入圈速页”为 Session 唯一创建时点。
- Session Room 行必须先于 CameraX start 存在。
- Back/Home/Finalize/强杀各有明确可恢复状态。
- 既有 Session 和已绑定视频不变；新视频可在冷启动恢复。

**Non-Goals:**

- 不自动删除或隐藏短 Session。
- 不猜测旧版纯时间戳孤儿 MP4 的 Session 归属。
- 不引入前台服务或保证 Home 后无限后台录像。
- 不改分段导出、播放时间线或多段 schema。

## Decisions

### Decision 1：进页即启动持久化

`selectLapDebugMode()` 在设置内存状态后以 `CoroutineStart.UNDISPATCHED` 启动唯一 Session 创建任务。这保证在同步导航返回前已经进入 `startSession()`；Room IO 可异步完成，但 REC/GPS 必须等同一 Mutex。

快速退出先 join 启动任务，再 `endSession()`，所以短 Session 是合法历史，不是清理对象。

### Decision 2：禁止无 Session 录像

`CameraRecordingEngine.startRecording()` 的 `sessionId` 为非空类型。UI 先 await ViewModel 持久化，再做一次无挂起 active 复核；退出已开始时 fail closed，不启动 CameraX。

### Decision 3：`Starting` 是一等状态

CameraX `pendingRecording.start()` 与 `VideoRecordEvent.Start` 之间不再伪装为 Idle。`Starting` 期间拒绝重复 REC，离页或 END 可 stop，迟到 Start 不得把已经 Stopping 的引擎改回 Recording。

### Decision 4：Finalize 完成包含绑定持久化

Finalize 事件只证明 CameraX 停止写媒体。对 UI 的“保存完成”必须同时包含 `attachVideoToSession()` 成功。因此引擎在 IO 落库后才回主线程转 Idle/Error 并触发离页回调。

### Decision 5：新文件名是强杀恢复证据

输出名为 `<session UUID>_<request wallClock>.mp4`。冷启动只恢复 Session 行存在、文件非空、路径尚未绑定的文件，并以 `playable = null` 保留首播验证语义。恢复先于 incomplete Session summary 收尾。

## State Timeline

- 正常：Enter → Session persisted → Starting → CameraX Start/Recording → Stopping → Finalize → binding persisted → Idle → endSession。
- 快速退出：Enter → Session persisted → endSession → 保留 0s/几秒历史。
- Home：Starting/Recording → CameraX lifecycle stop/Finalize → 有数据则救援绑定；容器可播性待首播验证。
- 强杀：无回调 → 下次启动扫描新命名文件 → 绑定 Session → 收尾 incomplete summary。

## Compatibility / Risks

- 旧版纯时间戳且从未入库的 MP4 不可靠恢复，保持不猜测。
- 强杀段的开始时间使用 request wallClock，可比真实首帧略早；正常 Finalize 仍用 Start 事件的真实 wallClock。
- Home/强杀后 MP4 是否有完整 moov 必须真机播放验证，自动化只能证明归属与幂等。

## Version / Rollout

1. `versionCode 8 / 1.0.7` 升级到 `versionCode 9 / 1.0.8`。
2. 覆盖安装保留 Room 和 `filesDir/video`，不得卸载/清数据作为升级验收。
3. 自动化通过后构建并验签 Release APK。
4. 真机覆盖快速进出、REC 后立即 Back、Home/恢复、录制中强杀/重启。
5. 未完成真机前不宣称生产可用；不自动 commit、tag、push 或上传分发平台。
