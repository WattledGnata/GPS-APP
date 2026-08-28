# Design: video-segment-recording-rotation

## Context

`CameraRecordingEngine`(696 行,Koin single)现状:

- `startRecording(context, activeSessionId)`(`:370-421`):建 outputFile(`filesDir/video/<ts>.mp4`)→ `prepareRecording().start(executor) { event -> handleVideoRecordEvent(event, outputFile) }`;**Recording/Stopping 状态拒绝重入**
- `stopRecording(onFinalized)`(`:434-458`):置 `Stopping` → `rec.stop()` → Finalize 异步回
- `handleVideoRecordEvent`:`Start`(`:504`)写实例字段 `_capturedWallClock/_capturedSessionId`;`Status`(`:526`)降频日志;`Finalize`(`:534`)`activeRecording = null` + OK 分支 attach 写库 + 状态转移
- attach 已是 ②a append 语义(`playable/durationMs` 参数),每段 Finalize 调一次即自动成段 ✅
- 触发数据源:`TestSessionViewModel.lapSession.completedLaps`(StateFlow);LapLiveScreen 同持 engine + viewModel(`:151`,录制按钮 `:707`)
- CameraX 能力:同一 `Recorder` 同时仅一个 active recording;旧 recording `stop()` 后可立即 `start()` 新 recording(旧的 finalize 异步进行),每个 recording 事件流走自己 start 时传入的 listener

## Goals / Non-Goals

**Goals:**

- 录制中每完成 N=3 圈自动切段,段边界落圈终点 crossing 时刻(memo M5)
- 切段对用户透明(状态恒 Recording,UI 无感)
- 旧段/新段事件上下文完全隔离(修单字段并发污染)
- 段间隙可观测(memo M1:gap 毫秒数落盘,真机攒批读数)

**Non-Goals:**

- lapIndex 字段填充(follow-up `wire-segment-lap-index`)
- 双 Recorder 乒乓(等真机 gap 实测;预期单 Recorder gap 百 ms 级可接受)
- 录制参数/绑定生命周期改动

## Decisions

### Decision 1: per-recording 事件上下文 = 闭包局部对象,实例字段仅指"当前段"

`startSegment` 内部:

```kotlin
class SegmentContext(val outputFile: File, val sessionId: String?) { var wallClock = 0L }
val segCtx = SegmentContext(outputFile, activeSessionId)
val recording = pending.start(executor) { event -> handleVideoRecordEvent(event, segCtx, recordingRef) }
```

`Start` 写 `segCtx.wallClock`(非实例字段);`Finalize` 读 `segCtx.*` 写库。`activeRecording = null` 与状态机转移**仅当本段仍是当前段**(`activeRecording === 本段 recording`)时执行——轮换后旧段 Finalize 不碰新段状态。实例字段 `_capturedWallClock/_capturedSessionId` 退役(或仅留兼容读点,apply 实测后删)。

- 替代:轮换时先等旧段 Finalize 再 start 新段 → 拒绝:Finalize 耗时秒级(moov 写盘),gap 从百 ms 涨到秒级,M6 直接劣化
- 替代:实例字段加 per-segment map → 拒绝:闭包天然隔离,map 还要清理

### Decision 2: 轮换触发链 = LapLiveScreen 观察圈完成 → engine 计数 → 自轮换

LapLiveScreen 加 `LaunchedEffect`:观察 `lapSession.completedLaps.size` 增量,每 +1 调 `engine.notifyLapCompleted(context)`(主线程,Compose LaunchedEffect 天然主线程)。engine 内:

```kotlin
@MainThread
fun notifyLapCompleted(context: Context) {
    if (_recordingState.value !is RecordingState.Recording) return
    lapsInCurrentSegment++
    if (lapsInCurrentSegment >= SEGMENT_MAX_LAPS) rotateSegment(context, "lap-count")
}
```

`rotateSegment`:`activeRecording?.stop()`(不置 Stopping,状态保持 Recording)→ `lapsInCurrentSegment = 0` → `startSegment(context, 同 sessionId)`。新段 sessionId 沿用当前段 SegmentContext 的(录制中 session 不变)。

- 替代:engine 注入 ViewModel 观察流 → 拒绝:engine(recordingModule single)依赖 ViewModel 是反向依赖;UI 层桥接零新依赖
- 替代:ViewModel 注入 engine 调 notify → 可行但 ViewModel 构造再 +1 参波及测试 helper 全家;LapLiveScreen 桥接改动面最小
- N=3 计数语义:**录制开始后**完成的圈(不含录制前已完成的)——LaunchedEffect 以进入 Recording 时刻的 completedLaps.size 为基线计增量

### Decision 3: 时长硬上限挂既有 Status 事件

`Status` 分支(已存在,降频日志处)加:`event.recordingStats.recordedDurationNanos / 1_000_000 >= MAX_SEGMENT_DURATION_MS(600_000)` 且本段仍是当前段 → `rotateSegment(context, "duration-cap")`。零新定时器。

- 替代:engine 内协程 ticker → 拒绝:Status 事件 CameraX 原生周期回调,免费且与录制生命周期天然绑定

### Decision 4: 段间隙观测(memo M1)

engine 记 `lastSegmentEndEstimate = 旧段 segCtx.wallClock + durationMs(Finalize stats)`;新段 `Start` 事件时 `gap = 新段 wallClock - lastSegmentEndEstimate`,`FileLogger.d(TAG, "segment gap=${gap}ms (rotation)")`——真机攒批读这个数定双 Recorder 乒乓是否立项。注意 Finalize(旧段)与 Start(新段)事件顺序不保证,gap 计算用"旧段 stop() 时刻的 Status 最近时长估计"兜底(apply 实测取简单可行形态,透明声明)。

### Decision 5: 日志锚点(road-test-first MANDATORY)

| 位置 | 内容 |
|---|---|
| `notifyLapCompleted` | 计数值/是否触发轮换 |
| `rotateSegment` | reason(lap-count/duration-cap)+ 旧段 file + 新段 file |
| 新段 `Start` | `segment gap=Nms` |
| 旧段 `Finalize`(轮换路径) | "rotation finalize"(区分最终 stop) |

## Risks / Trade-offs

- [轮换 stop→start 间隙丢帧] → 单 Recorder 立即 start 预期百 ms 级;gap 日志量化,超预期(>500ms?user 真机拍板)再立双 Recorder follow-up
- [旧段 Finalize ERROR(轮换路径)] → per-recording 上下文下 ERROR 救援分支照常 attach(playable=null),不影响新段;既有救援逻辑闭包化后语义不变
- [Status 事件频率不可控(可能数百 ms 一次)] → 时长兜底精度 ±1 个 Status 周期,10min 上限下可忽略
- [LaunchedEffect 基线竞态(录制开始瞬间圈完成)] → 基线取进入 Recording 时的 size,增量判定 `>` 严格;漏一圈的代价仅是段多 1 圈,无数据损失
- [stopRecordingAndAwait(停圈退出)与轮换并发] → rotateSegment 仅在 Recording 状态执行;stopRecording 置 Stopping 后 notify/rotate 全部 no-op(状态 gate 既有)

## Migration Plan

无 schema/无消费方改动(②a attach 即成段,②c 选段即消费)。回滚 revert 后退回单文件长录,子表数据兼容。

## Open Questions

(无——gap 阈值是否立 双 Recorder 由真机数据决定,非本 round 阻塞项。)
