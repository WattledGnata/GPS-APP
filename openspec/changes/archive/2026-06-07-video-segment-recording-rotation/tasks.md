# Tasks: video-segment-recording-rotation

> worktree 内实施;gradle 8.9 `--offline`;road-test-first;新增 .kt 首行 `// @IgnoreFormatCheck`。
> CameraX Recording 不可 JVM 实例化——引擎纯逻辑(计数/轮换判定)抽函数单测 + grep contract;轮换时序为真机攒批 MUST。

## 1. 锚点 verify

- [x] 1.1 `grep -n '_capturedWallClock\|_capturedSessionId' CameraRecordingEngine.kt` 列全单字段读写点(预期 :108-109 声明 + Start/Finalize/ERROR 救援分支读写)。done:per-recording 改造面清单。
- [x] 1.2 `grep -n 'activeRecording = null\|activeRecording = recording' 同文件` 列引用更替点。done:误杀防护改造点。
- [x] 1.3 `grep -n 'completedLaps' LapLiveScreen.kt` 确认屏内现无圈完成观察(新增 LaunchedEffect 不与现有冲突)。done:触发通路锚定。

## 2. 引擎改造(per-recording + 轮换)

- [x] 2.1 `SegmentContext` 内部类(outputFile/sessionId/var wallClock)+ `startSegment(context, sessionId)` 私有方法(原 startRecording 主体,listener 闭包持 segCtx);`startRecording` 改薄壳(重置 lapsInCurrentSegment 基线 + 调 startSegment)。done:闭包上下文成立。
- [x] 2.2 `handleVideoRecordEvent(event, segCtx, recording)`:Start 写 segCtx.wallClock + gap 计算落盘(design D4);Finalize 读 segCtx 写库;`activeRecording === recording` 时才置 null/状态转移(D1);ERROR 救援分支同步闭包化。done:三分支隔离 + 最终 stop 行为不回归。
- [x] 2.3 `notifyLapCompleted(context)` + `rotateSegment(context, reason)`(D2:仅 Recording;stop 旧不置 Stopping;立即 startSegment;计数清零)+ `SEGMENT_MAX_LAPS=3` 常量。done:轮换主链。
- [x] 2.4 Status 分支加时长兜底(D3:`MAX_SEGMENT_DURATION_MS=600_000`,仅当前段)。done:兜底链。
- [x] 2.5 日志锚点全套(D5:notify 计数/rotate reason/gap/rotation finalize)。done:FileLogger 锚点 ≥4 处。

## 3. UI 触发桥

- [x] 3.1 `LapLiveScreen` 加 `LaunchedEffect(recordingState is Recording)`:进入 Recording 取 completedLaps.size 基线,collect lapSession 流增量调 `engine.notifyLapCompleted(context)`。done:通路接通。

## 4. 测试

- [x] 4.1 引擎可测纯逻辑抽出(如 `shouldRotate(lapsInSegment, maxLaps)` / gap 计算纯函数)+ 单测;难抽部分 grep contract:新建 `CameraRecordingRotationContractTest.kt` 断言源码含 `notifyLapCompleted`/`SEGMENT_MAX_LAPS = 3`/`segment gap=`/`activeRecording === `(身份比较防误杀)四锚 + **不含** `_capturedWallClock`(单字段已退役,反例锁)。done:contract 锁。
- [x] 4.2 全量:core/data + feature/test,除已知 case G 红外 0 fail。done:全绿。

## 5. 真机验证(攒批 MUST——轮换时序唯一验证手段)

- [ ] 5.1 攒批场景:录制中跑 4+ 圈(simulator replay 可用)→ 第 3 圈完成切段;sqlite3 查 `video_segments` ≥2 行;`adb pull` debug_log 读 `segment gap=` 毫秒数(决策双 Recorder);各段独立可播(②c 回放选段);停录最终段正常入库。

## 6. memo 回标 + 归档

- [x] 6.1 `video-segmentation-data-model-deferred.md` 状态块:②b 已消化(N=3 + 时长兜底 + gap 观测;双 Recorder/lapIndex 填充列 follow-up),memo 三子 round 全闭。done:memo 终态。
- [x] 6.2 看板 §5 登记 + ff-only 合回 + metrics.yaml + 归档(`--yes`)。
- [ ] 6.3 push 待 user 拍板(攒批)。

## 10. Follow-up backlog

- `wire-segment-lap-index`(startLapIndex/endLapIndex 填充——扩 attach 签名 + fake 连锁;按圈直索引段的回放优化前置)
- 双 Recorder 乒乓(真机 gap 读数超 user 容忍阈值时立项)
