# video-segment-model Delta Specification

> 修改 capability(change `video-segment-recording-rotation` ②b):录制端按圈轮换分段。

## ADDED Requirements

### Requirement: 按圈轮换分段(N=3)

录制中(`RecordingState.Recording`)每完成 **3 圈**(录制开始后计数,基线=进入 Recording 时的 completedLaps)系统 SHALL 自动切段:旧段 `stop()` 异步 finalize 入库(②a append 语义自动成段),立即起新段;录制状态 SHALL 保持 `Recording` 不经过 `Stopping`(用户视角连续录制)。段边界 SHALL 落在圈完成 crossing 通知时刻(memo M5)。非录制状态下圈完成通知 SHALL no-op。

#### Scenario: 第 3 圈完成触发轮换(正例)

- **WHEN** 录制中,录制开始后第 1、2 圈完成不切段,第 3 圈完成
- **THEN** 触发 rotateSegment,`video_segments` 最终出现 ≥2 行(旧段 + 新段),recordingState 全程 Recording

#### Scenario: 录制前已完成的圈不计数(正例,基线语义)

- **WHEN** session 已完成 2 圈后才开始录制,随后完成第 3 圈(录制后第 1 圈)
- **THEN** MUST NOT 触发轮换(计数=1,基线扣除录制前的 2 圈)

#### Scenario: 非录制状态通知 no-op(反例)

- **WHEN** recordingState=Idle/Stopping 时圈完成通知到达
- **THEN** 计数不变、不切段、不抛

### Requirement: per-recording 事件上下文隔离

每个 Recording 的 `outputFile/sessionId/wallClock` SHALL 由该 recording 的事件 listener 闭包持有;轮换时旧段 finalizing 与新段 starting 并存,新段 `VideoRecordEvent.Start` MUST NOT 污染旧段写库参数;旧段 `Finalize` 的 `activeRecording = null` 与状态机转移 MUST 仅在 `activeRecording` 仍指向本段时执行(轮换后旧段 Finalize 只写库)。

#### Scenario: 轮换期间旧段写库参数不被污染(反例锁,核心)

- **WHEN** 轮换发生:旧段(wallClock=W1)stop 后、其 Finalize 到达前,新段 Start 事件已写入 wallClock=W2
- **THEN** 旧段 Finalize 写库的 `videoStartedAtWallClock` MUST = W1——若实现仍读实例单字段(被 W2 覆盖),段时间轴错位,②c 选段/回放全错

#### Scenario: 旧段 Finalize 不误杀新段(反例)

- **WHEN** 轮换后新段已 Recording,旧段 Finalize 到达
- **THEN** `activeRecording` MUST 仍指向新段(不被置 null),recordingState 仍 Recording

#### Scenario: 最终 stop 行为不回归(正例)

- **WHEN** 用户停录(非轮换),Finalize OK
- **THEN** activeRecording 置 null + 状态转移 + attach 写库,与 ②b 前行为一致

### Requirement: 段时长硬上限兜底

单段录制时长达 **10 分钟**(`MAX_SEGMENT_DURATION_MS=600_000`,经 `VideoRecordEvent.Status` 的 recordedDuration 检查)SHALL 触发轮换(reason=duration-cap)——防单圈异常长/挂死不过线时单文件无限增长(memo M5 兜底)。

#### Scenario: 挂死不过线时段不无限长(正例)

- **WHEN** 录制中 10 分钟无任何圈完成
- **THEN** Status 检查触发轮换,旧段 ≤10min+1 个 Status 周期

### Requirement: 段间隙记录(memo M1)

轮换产生的新段 `Start` 时系统 SHALL 计算并以 FileLogger 落盘 `gap`(新段 wallClock − 旧段结束估计),供真机攒批量化单 Recorder 切段丢帧、决策双 Recorder 乒乓是否立项。**MUST NOT 静默轮换**(无 gap 日志 = M6 评估失去数据源)。

#### Scenario: 轮换 gap 落盘(反例锁)

- **WHEN** 任一轮换完成
- **THEN** debug_log 含 `segment gap=` 字样——grep 0 命中说明日志被删,M6 真机评估失效
