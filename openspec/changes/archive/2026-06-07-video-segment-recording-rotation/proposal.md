# Proposal: video-segment-recording-rotation

## Why

②a/②c 已交付多段数据模型与按段消费,但**录制端仍是单文件一录到底**:`CameraRecordingEngine.startRecording`(`:370`)一个 `Recording` 从 Start 到 Finalize。单文件长录的两个实证风险(memo `video-segmentation-data-model-deferred.md` §2):1080p/30 一小时 ~7.2GB 巨文件;Finalize ERROR 时 moov 未写完**整段报废**(Phase 2 已踩,2026-06-03 1.15GB 救援段就是这么来的)。按圈轮换分段(user 2026-06-07 拍板 **N=3 圈/段**,对标 RaceChrono)把爆炸半径限制在一段——某段坏,其余段照播(②c 的多段回放已就绪消费)。

引擎现状(2026-06-07 grep 核实)暴露两个轮换前提缺陷:per-recording 状态(`_capturedWallClock:108`/`_capturedSessionId:109`)是**实例单字段**,轮换时旧段 finalizing 与新段 starting 并存,新段 `VideoRecordEvent.Start` 会覆盖字段污染旧段写库;`Finalize → activeRecording = null`(`:534`)会误杀已指向新段的引用。

## What Changes

- **per-recording 事件上下文**(轮换前提):`startSegment` 内部以闭包/局部对象持有本段 `outputFile/sessionId/wallClock`,listener 不再读写实例单字段;Finalize 的 `activeRecording = null` 与状态机转移仅当 `activeRecording` 仍指向本段(或最终 stop)时执行——旧段 Finalize 只写库不动新段
- **`notifyLapCompleted(context)`**:LapLiveScreen 观察 `lapSession.completedLaps.size` 变化逐圈调用;引擎内 `lapsInCurrentSegment` 计数,达 **N=3** → `rotateSegment`(旧段 `stop()` 异步 finalize 入库 + 立即 `startSegment` 新段,状态保持 `Recording` 不过 Stopping——用户视角连续录制)
- **时长硬上限兜底**:既有 `VideoRecordEvent.Status` 分支(`:526`)检查 `recordedDurationNanos ≥ MAX_SEGMENT_DURATION_MS`(10 分钟)→ rotate(防单圈异常长/挂死不过线,memo M5 兜底条款)
- **段间隙日志**(memo M1):新段 Start wallClock 与旧段(start+duration)差值 FileLogger 落盘——②b 真机攒批的核心观测量(gap 过大再立 双 Recorder 乒乓 follow-up)
- **不做**:`startLapIndex/endLapIndex` 填充(②c 选段用 wallClock 不消费 lapIndex;填充需扩 attach 签名再波及 10+ fake,推 follow-up `wire-segment-lap-index`);双 Recorder 乒乓(等真机 gap 数据);孤儿判定/旧字段废弃(维持 ②c 决议)

## Capabilities

### Modified Capabilities

- `video-segment-model`: 新增录制端 requirement——按圈轮换(N=3)、per-recording 上下文隔离、时长兜底、段间隙记录。

## Impact

- `feature/test/.../recording/CameraRecordingEngine.kt` — per-recording 上下文重构 + notifyLapCompleted/rotateSegment + Status 兜底(~120 行)
- `feature/test/.../ui/tracktech/LapLiveScreen.kt` — LaunchedEffect 观察圈完成调 notify(~10 行)
- 测试:引擎纯逻辑抽函数(计数/轮换判定)单测 + grep contract;CameraX Recording 不可 JVM 实例化,轮换时序真机攒批 MUST

### 协议兼容性

不涉及公共协议;录制端本地。

### 复杂度与 review 模式

**medium**(单 module 但引擎状态机/并发改造 + 真机时序不确定)——road-test-first;真机攒批 MUST:3 圈轮换切段 + gap 日志读数 + 各段独立可播。
