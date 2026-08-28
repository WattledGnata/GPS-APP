# Spec Delta: lap-timing-engine

> Capability: **LapTimingEngine 与 bridgeGpsToLapTiming 的入口守卫层**。
> `processSample` 与 `bridgeGpsToLapTiming` 必须在喂样本 / 推进状态**之前**
> 通过"会话状态白名单"与"时间戳单调性"两重守卫；任一守卫未通过必须整帧丢弃，
> 且 **不得**让被丢弃的样本污染下次对比的参考状态。
>
> 依赖关系：
>
> - A38（bridge 层 ts 单调守卫） = 污染源截断层
> - A21（engine 层 ts 单调守卫 + `dropWhile → filter`） = engine 契约深度防御层
>   两者不是替代而是互补。单元测试或未来重构让 engine 被直接调用时，A21
>   保证契约独立成立；A19（白名单）与两层 ts 守卫正交。
>
> 顺手清理 A34：`bridgeGpsToLapTiming` 首样本分支的冗余赋值
> `_lapSession.value = currentSession` 属死码（StateFlow 相同引用不 emit），
> 与 A38 改造窗口交汇，一并删除。
>
> 不影响已闭环的 `fix-laptime-clock-source-integrity`（`!raw.isTimeSynced` 零 delta
> 快照 + `bridgeGpsToLapTiming` 未同步帧整帧跳过）：本 change 的 A38 守卫位于
> "已同步帧 + `previousSample` 非空"的分支之后，与未同步帧守卫正交。

## ADDED Requirements

### Requirement: engine 入口 `LapSessionStatus` 白名单守卫（A19）

`LapTimingEngine.processSample` MUST 在任何样本处理前检查 `session.status`，仅
`LapSessionStatus.Ready` 与 `LapSessionStatus.Recording` 放行；其他状态（当前
枚举中的 `Idle` / `Finished` / `Cancelled`，以及未来任何新增状态）MUST 返回
原 `session`（引用相等即可），不得修改 `samples` / `crossingEvents` /
`completedLaps` / `activeLap` / `currentLapIndex` / `nextExpectedGateIndex` /
`status` / `startedAtMillis` 任一字段。

关键属性：

- **白名单语义**：拦截策略不是"拦 Idle/Finished/Cancelled 三个值"，而是"只放行
  Ready/Recording 两个值"。未来 `LapSessionStatus` 新增枚举（`Paused` / `Interrupted`
  等）**默认被拦**，除非实施方显式决定接受。防御"开放默认不安全"反模式。
- **与外层守卫的关系**：生产链路靠 `TestSessionViewModel.isLapRecording` 布尔
  守卫挡住 Finished/Cancelled/Idle，但 engine 契约本身 MUST 独立自洽，不依赖
  外层状态字段。

#### Scenario: Finished 状态被静默改写的 v1 行为必须被 v2 拦下

- **GIVEN** `session = LapSession(status = Finished, samples = [...前 100 帧...])`
- **WHEN** 调用 `engine.processSample(session, track, prevSample, currentSample)`
- **THEN** 返回 session 的 `status == Finished`（未被改写为 Recording）
- **AND** 返回 session 的 `samples.size == 100`（currentSample 未被追加）
- **AND** 返回 session 的 `completedLaps` / `activeLap` / `crossingEvents` 全部
  保持原值不变

#### Scenario: Cancelled 状态也 MUST 被白名单拦下

- **GIVEN** `session = LapSession(status = Cancelled, samples = [...])`
- **WHEN** `engine.processSample(...)` 被调用
- **THEN** 返回 session 字段全部不变，含 `samples.size` 不增长

#### Scenario: Idle 状态也 MUST 被白名单拦下

- **GIVEN** `session = LapSession(status = Idle)`（sessionId 仍存在但未开圈）
- **WHEN** `engine.processSample(...)` 被调用
- **THEN** 返回 session `status == Idle`，`samples` / `crossingEvents` / `activeLap`
  全部保持原值

#### Scenario: Ready 状态放行，首次起终点过线推进到 Recording

- **GIVEN** `session = LapSession(status = Ready, activeLap = null)` 且
  `(previousSample, currentSample)` 在 `track.startFinishGate` 上构成 accepted
  的过线（`GateCrossingDetector.detect(...).accepted == true`）
- **WHEN** `engine.processSample(...)` 被调用
- **THEN** 返回 session 的 `status == Recording`
- **AND** 返回 session 的 `activeLap.lapIndex == 1`
- **AND** 返回 session 的 `samples.size` 增长 1（currentSample 被追加）

#### Scenario: Recording 状态放行，继续 sector / 闭圈路径

- **GIVEN** `session = LapSession(status = Recording, activeLap = ActiveLap(lapIndex=1, ...))`
  且 `(previousSample, currentSample)` 构成有效 sector 过线
- **WHEN** `engine.processSample(...)` 被调用
- **THEN** 返回 session 的 `samples.size` 增长 1
- **AND** `crossingEvents` / `activeLap.sectorEntries` 按正常路径更新

---

### Requirement: engine 入口 ts 单调守卫（A21 入口层）

`LapTimingEngine.processSample` MUST 在 A19 白名单守卫之后、任何样本处理之前，
检查 `currentSample.timestampMillis < previousSample.timestampMillis`；若成立，
MUST 返回原 `session`（引用相等即可），不修改任何字段。

对比基准 MUST 使用方法参数 `previousSample.timestampMillis`（而非
`session.samples.lastOrNull()?.timestampMillis`）：

1. engine 契约本身就是"对比 previousSample 与 currentSample"，守卫直接对应契约；
2. `previousSample` 是方法参数永远非空，无需 null 兜底；
3. 避免"`previousSample` 与 `session.samples.last()` 不同步"（单元测试构造 /
   未来调用方重构）场景下基准错位；
4. 与 A38（bridge 层守卫）语义对称。

#### Scenario: ts 回跳样本直接返回原 session

- **GIVEN** `previousSample.timestampMillis = 500L`，`currentSample.timestampMillis = 400L`
- **AND** `session.status = Recording`（通过 A19 白名单）
- **WHEN** `engine.processSample(session, track, previousSample, currentSample)`
- **THEN** 返回 session 的 `samples.size` 不增长
- **AND** 返回 session 的 `crossingEvents.size` 不增长
- **AND** 日志中有 "processSample: ts regression" 条目（便于追溯）

#### Scenario: 首次起圈 session.samples 为空时守卫不误拦

- **GIVEN** `session.samples = emptyList()`，`session.status = Ready`，
  `previousSample.timestampMillis = 100L`，`currentSample.timestampMillis = 140L`
- **WHEN** `engine.processSample(...)` 被调用
- **THEN** 守卫基准是 `previousSample.timestampMillis = 100L`（方法参数，非空），
  `140 < 100` 为 false，样本被放行
- **AND** 返回 session 的 `samples.size == 1`（样本被追加，起圈正常）

---

### Requirement: 闭圈 crossingEvents 裁剪必须用 filter 严格语义（A21 裁剪层）

闭圈时的 crossingEvents 裁剪 MUST 对每一个事件独立判断 timestamp 是否大于等于
`activeLap.startedAtMillis`，逐元素 filter 保留或丢弃，不得依赖事件序列时间戳
单调递增这一假设。实现上 MUST 使用 `filter { it.timestampMillis >= activeLap.startedAtMillis }`，
MUST NOT 使用 `dropWhile { it.timestampMillis < activeLap.startedAtMillis }`。

关键属性：

- 即使 A21 / A38 的入口守卫生效，`crossingEvents` 集合内部可能因历史调用路径
  残留 `ts < startedAtMillis` 的事件（例如前一轮回跳样本产生、被入口守卫拦掉
  之前已经被 `session.crossingEvents + crossingEvent` 加入）。`filter` 严格
  语义 MUST 保证 LapRecord 归属纯洁。
- `filter` 是"逐元素"，`dropWhile` 是"序列前缀"，语义不等价；两者在事件流严格
  单调时结果相同，但在非单调场景下 `filter` 正确、`dropWhile` 漏拦。

#### Scenario: 单调正常序列 filter 保留所有 ts >= startedAtMillis 的事件（正向语义）

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=200), event(ts=300), event(ts=400)]`（严格单调递增）
- **AND** 当前即将闭圈的 `activeLap.startedAtMillis = 200L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** `LapRecord.crossingEvents == [event(ts=200), event(ts=300), event(ts=400)]`
  （逐元素 filter 保留所有 `ts >= 200` 的事件；边界值 ts=200 也保留，锁定 `>=` 非 `>`）

#### Scenario: 非单调序列含 ts < startedAt 夹后，filter 拒收历史事件硬区分 v1/v2

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=250), event(ts=150), event(ts=400)]`
  （其中 ts=150 是夹在 ts=250 之后、ts=400 之前的历史事件，序列非单调）
- **AND** 当前即将闭圈的 `activeLap.startedAtMillis = 200L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** `LapRecord.crossingEvents == [event(ts=250), event(ts=400)]`（长度 2，ts=150 被 filter 拒收）
- **AND 硬区分反证**：若实现回退到 v1 `dropWhile { it.timestampMillis < activeLap.startedAtMillis }`：
  - 扫描 ts=100 → `100<200` drop
  - 扫描 ts=250 → `250<200` 为 false **停止 dropWhile**
  - 输出 `[event(ts=250), event(ts=150), event(ts=400)]`（长度 3，ts=150 的历史事件被误留）
  - 该断言 `== [ts=250, ts=400]` 能硬区分 v1（失败）vs v2（通过）

#### Scenario: 单调正常序列 filter 与 dropWhile 输出完全等价（防退化回归保护）

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=200), event(ts=300), event(ts=400)]`
  （严格单调）
- **AND** `activeLap.startedAtMillis = 150L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** filter 输出 `[event(ts=200), event(ts=300), event(ts=400)]`
- **AND** 同一输入下 v1 `dropWhile` 语义的输出也是 `[event(ts=200), event(ts=300), event(ts=400)]`
- **AND 防退化断言**：测试 MUST 同时断言 filter 输出和 dropWhile 输出相等 —— 防御
  "未来有人把 `>=` 误写为 `>`" 或"把 filter 退回 dropWhile" 导致单调场景也偏离契约

---

### Requirement: `bridgeGpsToLapTiming` 时间单调守卫 + 三段式 `lastLapGpsSample` 契约（A38 + A34 顺手清理）

`TestSessionViewModel.bridgeGpsToLapTiming(gpsData)` MUST 在已同步帧
（`gpsData.isTimeSynced == true`）的分支内按**三段式**组织，每段对
`lastLapGpsSample` 的动作有明确语义：

1. **首样本分支**：`previousSample == null || currentSample.timestampMillis <= 0L`
   - MUST 赋值 `lastLapGpsSample = currentSample`（为下一帧准备 previousSample）
   - MUST **不**执行 `_lapSession.value = currentSession`（A34 死码，StateFlow
     相同引用不 emit，删除）
   - early return
2. **ts 回跳分支**：`currentSample.timestampMillis < previousSample.timestampMillis`
   - MUST **不**赋值 `lastLapGpsSample`（保持前帧作为下一帧基准）
   - MUST 记录日志 `bridgeGpsToLapTiming: ts regression, drop sample prevTs=... curTs=...`
   - early return（不调 `lapTimingEngine.processSample`）
3. **正常推进分支**：以上都不成立
   - MUST 赋值 `lastLapGpsSample = currentSample`
   - MUST 调用 `lapTimingEngine.processSample(...)` 并将结果写入 `_lapSession.value`
   - MUST 更新 `_latestLapRecords.value = updatedSession.completedLaps`

三个分支对 `lastLapGpsSample` 的动作：

| 分支 | 条件 | `lastLapGpsSample` 动作 | 理由 |
|---|---|---|---|
| 首样本 | `previousSample == null` | **赋 currentSample** | 为下一帧准备 previousSample，不赋会永远起不了圈 |
| ts 回跳 | `current.ts < previous.ts` | **不赋**（保持前帧） | 回跳帧是污染源，不让它成为下一帧基准 |
| 正常推进 | 以上都不成立 | **赋 currentSample** | 推进正常路径 |

与 A13 "异常帧不更新 `previousRaw`" 模式一致：异常来源必须在赋值层被显式隔离。

#### Scenario: 首样本 MUST 赋值 lastLapGpsSample，下一帧能正常起圈

- **GIVEN** `lastLapGpsSample == null` 且 `_lapSession.value.status = Ready`
- **WHEN** 喂入首帧 `gpsData(ts=100, isTimeSynced=true)`
- **THEN** `lastLapGpsSample` 被赋值为 `currentSample(ts=100)`
- **AND** `lapTimingEngine.processSample` 未被调用（首样本 early return）
- **AND** `_lapSession.value` 保持 `Ready`（A34 死码删除后无 StateFlow emit）
- **WHEN** 再喂入下一帧 `gpsData(ts=140, isTimeSynced=true)` 且该帧与首帧构成
  起终点过线
- **THEN** `lapTimingEngine.processSample(previousSample=首帧, currentSample=本帧)` 被调用
- **AND** 返回 session 的 `status == Recording`（起圈成功）

#### Scenario: ts 回跳帧整帧丢弃，`lastLapGpsSample` 保持前帧

- **GIVEN** `lastLapGpsSample = sample(ts=500)` 且 `_lapSession.value.status = Recording`
- **WHEN** 喂入一帧 `gpsData(ts=400, isTimeSynced=true)`（ts 回跳）
- **THEN** `lastLapGpsSample` **仍是** `sample(ts=500)`（未被覆盖）
- **AND** `lapTimingEngine.processSample` 未被调用
- **AND** `_lapSession.value.samples.size` 不增长
- **AND** 日志中有 `bridgeGpsToLapTiming: ts regression` 条目

#### Scenario: 回跳帧被丢弃后，下一帧 ts 前进时 engine 以回跳**前**的帧为 previousSample 处理

- **GIVEN** `lastLapGpsSample = sample(ts=500)`
- **WHEN** 喂入回跳帧 `gpsData(ts=400, isTimeSynced=true)` → 整帧丢弃，
  `lastLapGpsSample` 仍是 `sample(ts=500)`
- **AND** 再喂入下一帧 `gpsData(ts=520, isTimeSynced=true)`（ts 前进）
- **THEN** `lapTimingEngine.processSample(previousSample=sample(ts=500), currentSample=sample(ts=520))` 被调用
  （而非以回跳帧 `sample(ts=400)` 为 previousSample）
- **AND** 回跳帧**永不**出现在 engine 调用链路中
