# Spec Delta: laptime-clock-source

> Capability: **圈速时间戳的单源可信性** —— 发射端 → BLE 协议 → 接收端 → LapTimingEngine 整条链路中，圈速计算使用的时间戳必须来自同一个时钟基，且在该时钟基不可用时**显式**传递"未同步"状态，而不是静默伪造。
>
> 本 spec 按 2026-04-22 `docs/superpowers/reviews/2026-04-22-opsx-fix-laptime-clock-source-review.md` 的 P0/P1/P2 修订落地：
> - P0.1 未同步时 `timestamp = Long.MIN_VALUE` sentinel → Requirement 2 Scenario
> - P0.2 分层守卫（而非只 gate bridge） → 新增 Requirement 3.5
> - P0.3 删除 `GpsSample.isTimeSynced` 字段 → Requirement 3 / 5 的契约改由 `GpsDataViewModel.gpsData.isTimeSynced` 驱动
> - P1.4 失联恢复重置前驱 → Requirement 3 Scenario
> - P1.5 `LapQualityFlag.ProtocolDesyncGap` 新增 + engine 闭圈扫描 → Requirement 3 Scenario
> - P2.6 跨小时契约 → Non-goals 明确"仅保证 < 1 小时会话"
> - P2.7 接收端不假设真实日历 → Requirement 2 Scenario

## ADDED Requirements

### Requirement: 发射端时间戳必须来自会话相对单调时钟或 replay 样本时间戳

发射端 simulator（`GpsDataGenerator`）MUST 使用以下两种时钟源之一生成协议主包和时间包中编码的时间值：

- **Replay 模式**（`scenario == REAL_TRACK_REPLAY`）：MUST 使用 `ReplaySample.timestampMillis`。若当前 `replayTimestampMillis` 为 null（未调用 `applyReplaySample`），MUST 抛出 `IllegalStateException`，**不允许** fallback 到任何本地时钟。
- **非 Replay 模式**（`scenario` 为任何其他值）：MUST 使用 `android.os.SystemClock.elapsedRealtime()` 派生的"会话相对时钟"——即发射会话启动时刻的 `elapsedRealtime()` 值与当前 `elapsedRealtime()` 的差值。

发射端 MUST NOT 调用 `System.currentTimeMillis()` 作为编入协议字段的时间源。
发射端 MUST 通过构造函数参数注入时钟函数 `clock: () -> Long`，以便单元测试替换为 `FakeClock`。

#### Scenario: 非 replay 模式按会话相对单调时钟编码 timeSinceHourStart

- **GIVEN** `GpsDataGenerator(scenario = STATIC, clock = fakeClock)`，`fakeClock` 在构造时返回 `500L`
- **AND** 会话启动后 `fakeClock` 返回值递增到 `500L + 2000L`
- **WHEN** 调用 `generator.generateGpsMainData()` 并解析 `data[0..2]` 中的 `timeSinceHourStart`
- **THEN** 解出的 `timeSinceHourStart` 等于 `2000ms`（即 `(2000 % 3_600_000) / 2 × 2 = 2000`）
- **AND** 整个调用路径上未访问 `System.currentTimeMillis()`

#### Scenario: Replay 模式缺样本时立即抛异常而不是 fallback

- **GIVEN** `GpsDataGenerator(scenario = REAL_TRACK_REPLAY)`
- **AND** 从未调用 `applyReplaySample()`
- **WHEN** 调用 `generator.generateGpsMainData()`
- **THEN** 抛出 `IllegalStateException`
- **AND** 异常消息包含 "Replay sample missing timestamp"
- **AND** 未向 BLE 特征值写入任何伪数据

#### Scenario: Replay 模式正常路径使用 sample 时间戳

- **GIVEN** `GpsDataGenerator(scenario = REAL_TRACK_REPLAY)`
- **AND** 已调用 `applyReplaySample(ReplaySample(timestampMillis = 1_234_567L, ...))`
- **WHEN** 调用 `generator.generateGpsMainData()`
- **THEN** `data[0..2]` 中的 `timeSinceHourStart` 解出值为 `1_234_567 % 3_600_000` 毫秒
- **AND** 同帧 `generateGpsTimeData()` 编码的 `dateAndHour` 与同一 `1_234_567L` 派生一致

---

### Requirement: 接收端 parser 在协议未对齐时不得 fallback 到系统时钟

接收端 `RaceChronoParser.parseGpsData` MUST 在下列任一条件下拒绝使用 `System.currentTimeMillis()` 伪造 `GpsData.timestamp`：

1. `protocolTimeReference == null`（从未收到过时间包）
2. `protocolTimeReference.syncBits != 当前主包的 syncBits`

在上述条件下，parser MUST 返回的 `GpsData.copy(...)` 中：
- **写入 `timestamp = Long.MIN_VALUE`**（sentinel 值，区别于任何可能的协议时间戳）
- 设置 `isTimeSynced = false`

仅当 `protocolTimeReference != null && syncBits 匹配` 时，parser MUST：
- 写入 `timestamp = protocolTimeReference.hourStartMillis + timeSinceHourStart`
- 设置 `isTimeSynced = true`

`parser.reset()` MUST 将内部"当前是否已同步"状态重置为 false，下一次 `parseGpsData` 调用输出 `timestamp = Long.MIN_VALUE, isTimeSynced = false`。

接收端 `RaceChronoParser.parseGpsTimeData` MUST NOT 调用 `Date(...).year` / `Calendar.get(YEAR/MONTH/DAY)` 做任何业务判断。解析 `dateAndHour` 只用于拼出 `protocolTimeReference.hourStartMillis` 作为单调 delta 计算基准。

#### Scenario: 首个主包到达时时间包尚未到达

- **GIVEN** 新建 `RaceChronoParser` 实例（`protocolTimeReference == null`）
- **WHEN** 喂入一个合法的 20 字节主包 `parser.parseGpsData(mainBytes, GpsData.Empty)`
- **THEN** 返回的 `GpsData.isTimeSynced == false`
- **AND** 返回的 `GpsData.timestamp == Long.MIN_VALUE`
- **AND** **不得**读取 `System.currentTimeMillis()`（可由测试 mock 静态方法验证）

#### Scenario: 时间包到达后主包 syncBits 不匹配

- **GIVEN** 已喂入 `syncBits = 3` 的时间包，`protocolTimeReference` 已就绪
- **WHEN** 喂入 `syncBits = 5` 的主包
- **THEN** 返回的 `GpsData.isTimeSynced == false`
- **AND** 返回的 `GpsData.timestamp == Long.MIN_VALUE`（不沿用上次、不 fallback）
- **AND** 未调用 `System.currentTimeMillis()`

#### Scenario: 时间包到达且主包 syncBits 匹配

- **GIVEN** 已喂入 `syncBits = 3` 的时间包，生成 `protocolTimeReference`
- **WHEN** 喂入 `syncBits = 3` 的主包，携带 `timeSinceHourStart = 1_500_000 ms`
- **THEN** 返回的 `GpsData.isTimeSynced == true`
- **AND** 返回的 `GpsData.timestamp == protocolTimeReference.hourStartMillis + 1_500_000`

#### Scenario: 协议恢复后下一帧切回 isTimeSynced=true

- **GIVEN** 连续 3 帧主包 syncBits 与 time 包不匹配，`isTimeSynced` 变为 false，`timestamp == Long.MIN_VALUE`
- **WHEN** 第 4 帧主包 syncBits 与最近一次 time 包匹配
- **THEN** 返回的 `GpsData.isTimeSynced == true`
- **AND** `timestamp` 为当前帧的协议还原值

#### Scenario: 接收端不假设 protocolTimestamp 对应真实日历

- **GIVEN** simulator 端以 `yearOffset = 0`（对应 2000-01-01 起点）编码 `dateAndHour`
- **WHEN** 接收端 parser 解析该帧
- **THEN** 接收端 **不得** 因"timestamp 对应 2000 年"而触发任何诊断报警
- **AND** 接收端 **不得** 调用 `Date(gpsData.timestamp).year`、`Calendar.get(YEAR/MONTH/DAY)` 等真实日历 API 做业务分支
- **AND** `gpsData.timestamp` 仅用作单调 delta 计算

---

### Requirement: 圈速链路必须拒收 isTimeSynced=false 的帧

`TestSessionViewModel.bridgeGpsToLapTiming(gpsData)` MUST 在 `gpsData.isTimeSynced == false` 时：

- 立即返回，**不**调用 `lapTimingEngine.processSample(...)`
- **必须**把 `lastLapGpsSample` 置为 `null`（失联恢复后首个同步帧走首样本分支，不让 detector 对"跨几秒的超长位移"做线段相交判定，避免伪造过线）
- **不**修改 `_lapSession.value`
- MAY 记录诊断日志（非高频）

`isTimeSynced = false → true` 的过渡帧 MUST 被 engine 当作首样本处理（`previousSample == null` 分支），即：只更新 `lastLapGpsSample`，不调 engine。
`activeLap` 在 `isTimeSynced` 短暂变为 false 时 MUST NOT 被重置或取消——等同于"协议失联暂停计数，不杀圈"。

`LapQualityFlag` 枚举 MUST 新增成员 `ProtocolDesyncGap`。

`LapTimingEngine.handleStartFinishCrossing` 在闭圈构造 `LapRecord` 时，MUST 扫描本圈 `trajectory` 相邻样本 ts 差，若存在任意相邻差值 `> expectedIntervalMillis × 5`（5 倍预期采样间隔，允许 4 帧抖动），则在 `LapRecord.qualityFlags` 追加 `ProtocolDesyncGap`。

**阈值参数化**（v2 修订，A7 / 对抗 review C.2）：
- `LapTimingEngine` 构造 MUST 接受 `expectedIntervalMillis: Long` 参数（默认 `40L` = 25Hz，ESP32 标配）。
- 阈值 `desyncGapThresholdMillis = expectedIntervalMillis × 5L`（公共常量 `DESYNC_GAP_FACTOR = 5L`）。
- 原版 v1 阈值硬编码 `200L` 在 5Hz replay 场景（真实采样间隔就是 200ms）会因浮点舍入抖动产生假阳性；v2 参数化后 5Hz 调用方可传入 `200L` → 阈值 `1000ms`，容忍 4 帧抖动。
- 25Hz 默认路径：阈值 `40 × 5 = 200ms`，行为与 v1 一致。
- 调用方（当前 DI 层 `LapTimingEngine(get())`）目前用默认 25Hz；需要支持 5Hz 时按频率显式传入。

#### Scenario: isTimeSynced=false 时整帧跳过，lastLapGpsSample 被重置

- **GIVEN** `TestSessionViewModel` 处于 LapDebug 模式，`isLapRecording = true`，`activeLap = null`
- **AND** 此前已有 `lastLapGpsSample = 某个已同步样本`
- **WHEN** 向 `gpsDataFlow` emit `GpsData(isTimeSynced = false, timestamp = Long.MIN_VALUE, ...)`
- **THEN** `viewModel.lapSession.value.samples` 未增长
- **AND** `lapTimingEngine.processSample` 在本次回调中未被调用
- **AND** 内部字段 `lastLapGpsSample == null`

#### Scenario: 失联恢复后的首个同步帧不喂 detector

- **GIVEN** 圈速会话处于 Recording 状态，`activeLap != null`
- **AND** 连续 3 帧 `isTimeSynced = false` 后 `lastLapGpsSample == null`
- **WHEN** 收到一帧 `isTimeSynced = true, timestamp = T_resume` 且位置恰好在远离上次同步位置的某点
- **THEN** `lapTimingEngine.processSample` **未**被调用（走首样本分支）
- **AND** `viewModel.lapSession.value.samples.size` 未因本帧增长（首样本不入列，等第二帧）
- **AND** 内部字段 `lastLapGpsSample` 被更新为本帧

#### Scenario: isTimeSynced 恢复后第二帧正常进入 engine

- **GIVEN** 上一条场景刚结束，`lastLapGpsSample` 为首个恢复帧
- **WHEN** 收到下一帧 `isTimeSynced = true, timestamp = T_resume + 40`
- **THEN** `lapTimingEngine.processSample(previousSample = 上一帧, currentSample = 本帧)` 被调用
- **AND** 若位置过起终点 gate，正常触发闭圈或开圈

#### Scenario: 已开圈期间出现短暂 isTimeSynced=false 不杀圈

- **GIVEN** `activeLap != null`，`currentLapIndex == 1`
- **WHEN** 收到一帧 `isTimeSynced = false`
- **THEN** `viewModel.lapSession.value.activeLap` 与上一帧相同（未被重置）
- **AND** `viewModel.lapSession.value.currentLapIndex == 1`
- **AND** `viewModel.lapSession.value.status == LapSessionStatus.Recording`

#### Scenario: 圈内短暂失联后恢复累计，LapRecord 打 ProtocolDesyncGap 标记

- **GIVEN** 圈速会话处于 Recording 状态，engine 按默认 `expectedIntervalMillis = 40L`（25Hz）构造
- **AND** 本圈内 `trajectory` 采样间隔正常（40ms）
- **WHEN** 圈内发生一段 `isTimeSynced = false` 持续超过阈值 `40 × 5 = 200ms`（至少跨越 5 帧间距）
- **AND** 协议恢复后车辆继续推进并最终闭圈
- **THEN** 生成的 `LapRecord.qualityFlags` 包含 `LapQualityFlag.ProtocolDesyncGap`
- **AND** `LapRecord.durationMillis` 为起止 ts 差（不扣除失联段）

#### Scenario: 25Hz 默认间隔下圈内无失联时 LapRecord 不带 ProtocolDesyncGap 标记

- **GIVEN** 圈速会话处于 Recording 状态，engine 按默认 `expectedIntervalMillis = 40L`（25Hz）构造
- **AND** 本圈内所有 `trajectory` 样本相邻 ts 差均 ≤ 200ms
- **WHEN** 正常闭圈
- **THEN** `LapRecord.qualityFlags` **不**包含 `ProtocolDesyncGap`

#### Scenario: 5Hz replay 采样（间隔 200ms）不产生假阳性 ProtocolDesyncGap（A7 回归）

- **GIVEN** engine 按 `expectedIntervalMillis = 200L`（5Hz）构造，阈值变为 `200 × 5 = 1000ms`
- **AND** 本圈内 `trajectory` 相邻 ts 差在 199–201ms（5Hz 正常采样含浮点抖动）
- **WHEN** 正常闭圈
- **THEN** `LapRecord.qualityFlags` **不**包含 `ProtocolDesyncGap`
- **AND** engine 行为与 v1 硬编码 `200L` 阈值不同（v1 会对 201ms 差值触发假阳性）

---

### Requirement: 分层守卫 — 时间 delta 类消费者必须守卫，纯数值消费者不得影响

`GpsData.timestamp` 的下游消费者分为两类，MUST 分别处理 `isTimeSynced = false` 的场景：

**(a) 时间 delta 计算类** — `isTimeSynced == false` 时 MUST **跳过**本次计算、**不**更新内部时间相关状态：

- `GpsDataFilter.process`：返回"零时间 delta 快照" `FilteredGpsData(speed = raw.speed, latitude = raw.latitude, longitude = raw.longitude, altitude = raw.altitude, bearing = raw.bearing, acceleration = 0.0, confidence = 0.0, isAnomaly = false, timestamp = raw.timestamp, raw = raw, consistencyFactor = 1.0, isPositionAnomaly = false)`；内部状态 `previousRaw / previousPosition / speedWindow / latWindow / lonWindow / bearingWindow` MUST NOT 更新。
- `TestSessionViewModel.updatePreTriggerBuffer`：`isTimeSynced == false` 时 MUST NOT append 该帧到 `preTriggerBuffer`。
- `TestSessionViewModel.processFilteredData`：**Preparing 与 Running 两个分支都**必须对称守卫 `filteredData.raw.isTimeSynced == false`：
  - `Preparing` 分支 MUST NOT 调用 `checkTriggerCondition` / 转 `TestState.Running`
  - `Running` 分支 MUST NOT 调用 `state.session.addFilteredDataPoint(...)` / MUST NOT 判定 `template.shouldEnd(...)`
  - 任一状态下未同步帧 MUST NOT 改变 session 的 `dataPoints` 或 `_testState.value`。
  - （v2 修订：原版仅要求 Preparing 守卫 → 对抗 review C.1 / backlog A6 指出 Running 分支失联时会把 sentinel `timestamp = Long.MIN_VALUE` + 零加速度快照 `addFilteredDataPoint` 进去，`elapsedTime = Long.MIN_VALUE − startTime` 溢出污染 0-100 结果计算。）
- `TestSessionViewModel.startTest`：`isTimeSynced == false` 时 MUST NOT 被调用（由上一条保证）。
- `TestSessionViewModel.bridgeGpsToLapTiming`：按 Requirement 3 处理。

**(b) 纯数值消费类** — `isTimeSynced == false` 时 MUST **继续正常工作**，不因时间戳缺失而中断：

- UI 实时遥测（显示 `speed / latitude / longitude / satelliteCount / hdop`）
- `SmartTestLauncher.checkLaunchConditions`：只消费速度区间 + 连接状态 + 卫星数 + hdop 判定，**不得**依赖 `gpsData.timestamp`
- `GpsDataViewModel.gpsData` StateFlow 自身的发射（所有帧，不论同步状态）

**`TestSessionViewModel.updateLaunchStatus` 的 `lastDataAge`** MUST 通过独立的 `SystemClock.elapsedRealtime()` 时间戳计算，与 `gpsData.timestamp` 解耦。即：
- `TestSessionViewModel` 在 `gpsDataFlow.collect` 回调的每一帧都更新内部变量 `lastReceivedAtElapsed = SystemClock.elapsedRealtime()`（无论 `isTimeSynced` 真假）
- `updateLaunchStatus` 计算 `lastDataAge = SystemClock.elapsedRealtime() - lastReceivedAtElapsed`

#### Scenario: GpsDataFilter 在未同步时不做时间 delta 计算

- **GIVEN** `GpsDataFilter` 实例
- **WHEN** 喂入 `GpsData(isTimeSynced = false, timestamp = Long.MIN_VALUE, speed = 50.0, latitude = 30.5, longitude = 104.4, ...)`
- **THEN** 返回的 `FilteredGpsData.speed == 50.0`
- **AND** `FilteredGpsData.latitude == 30.5`
- **AND** `FilteredGpsData.acceleration == 0.0`
- **AND** `FilteredGpsData.isAnomaly == false`
- **AND** `FilteredGpsData.isPositionAnomaly == false`
- **AND** filter 内部 `previousRaw == null`（未更新）
- **AND** filter 内部 `speedWindow.size == 0`（未更新）

#### Scenario: 未同步时预触发 buffer 不累积

- **GIVEN** `TestSessionViewModel` 处于 `TestState.Preparing`
- **WHEN** 喂入 `FilteredGpsData(raw = GpsData(isTimeSynced = false, ...), ...)`
- **THEN** `preTriggerBuffer.size` 不变

#### Scenario: 未同步时 Running 分支不消费污染帧（v2 修订，A6）

- **GIVEN** `_testState.value = TestState.Running(session)` 且 `session.dataPoints.size = N0`
- **WHEN** 收到一帧 `FilteredGpsData(raw = GpsData(isTimeSynced = false, timestamp = Long.MIN_VALUE, speed = 30.0, ...))`
- **THEN** `state.session.addFilteredDataPoint(filteredData)` MUST NOT 被调用
- **AND** `state.session.template.shouldEnd(filteredData.raw)` MUST NOT 被判定
- **AND** `session.dataPoints.size` 仍为 `N0`（未污染）
- **AND** `_testState.value` 仍为 `TestState.Running`（未误触发 `finishTest` 转 `Completed`）

#### Scenario: 未同步时不开始新测试

- **GIVEN** `_testState.value = TestState.Preparing(template, carModel)` 且倒计时已结束
- **WHEN** 喂入 `FilteredGpsData(raw = GpsData(isTimeSynced = false, ...), ...)`
- **THEN** `checkTriggerCondition` 不被调用
- **AND** `_testState.value` 保持 `Preparing`

#### Scenario: UI 遥测在未同步时仍显示速度/位置/卫星数

- **GIVEN** `gpsData.isTimeSynced == false`
- **AND** `gpsData.satelliteCount == 8`，`gpsData.speed == 42.0`，`gpsData.latitude == 30.495`
- **WHEN** UI 层观察 `gpsDataViewModel.gpsData`
- **THEN** 遥测卡片显示 `Speed = 42.0 km/h`、`Latitude = 30.495`、`Satellites = 8`
- **AND** `launchStatus` 的连接 / 卫星 / hdop 条件按常规判定，不因 `isTimeSynced` 短路

#### Scenario: launchStatus 的 lastDataAge 不依赖 GpsData.timestamp

- **GIVEN** `TestSessionViewModel.gpsDataFlow.collect` 在 `t0 = elapsedRealtime()` 时触发
- **AND** 内部变量 `lastReceivedAtElapsed = t0`（不论 `isTimeSynced` 真假）
- **WHEN** 经过 500ms 后调用 `updateLaunchStatus`
- **THEN** `lastDataAge = elapsedRealtime() - t0 ≈ 500ms`
- **AND** 计算过程中 **未**读取 `gpsData.timestamp`

---

### Requirement: 端到端圈时契约 — 发射端时钟与接收端圈时一致

从发射端 `GpsDataGenerator` 到接收端 `LapTimingEngine.LapRecord.durationMillis` 的完整链路 MUST 满足：**圈时误差 ≤ 20ms**（STATIC 模式）或 **≤ 5ms**（REPLAY 模式）。

该契约通过 JVM 端到端测试锁定，测试路径：
`generator.generateGps{Main,Time}Data → parser.parseGps{Time,Main}Data → GpsSample → engine.processSample`

发射端注入 fake clock 精确控制帧间隔，接收端不调用任何真实时钟 API。

#### Scenario: STATIC 模式 10 秒圈时误差 ≤ 20ms

- **GIVEN** `GpsDataGenerator(scenario = STATIC, clock = fakeClock)` 注入可递增 fake clock
- **AND** `RaceChronoParser` + `LapTimingEngine(TFIC preset track)`
- **WHEN** 发射端按 40ms/帧（25Hz）共 250 帧，在第 1 帧和第 250 帧位置穿起终点 gate
- **AND** 所有帧经 parser 解析后喂入 engine
- **THEN** `engine.session.completedLaps.size == 1`
- **AND** `completedLaps.first().durationMillis` 在 `[9_980, 10_020]` 毫秒区间内

#### Scenario: REPLAY 模式圈时与 JSON sample 时间戳差精确对齐

- **GIVEN** `GpsDataGenerator(scenario = REAL_TRACK_REPLAY)` 从 `feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json` 读取 sample 序列
- **AND** JSON 中第一次过起终点 sample 的 `timestampMillis = T_open`，下一次过起终点 sample 的 `timestampMillis = T_close`
- **WHEN** 逐帧 `applyReplaySample → generate → parse → process`
- **THEN** `completedLaps.first().durationMillis` 与 `T_close - T_open` 差值 **严格小于 5ms**
- **AND** `completedLaps.first().trajectory.size` 等于 `[T_open, T_close]` 区间内的 sample 数量

#### Scenario: 冷启动仅发主包不发时间包时 engine 不开圈

- **GIVEN** 发射端仅调用 `generateGpsMainData()` 不调用 `generateGpsTimeData()`
- **WHEN** 连续 50 帧经 parser 解析后喂入 engine
- **THEN** 所有帧 `GpsData.isTimeSynced == false`
- **AND** 所有帧 `GpsData.timestamp == Long.MIN_VALUE`
- **AND** `engine.session.samples.size == 0`
- **AND** `engine.session.completedLaps` 为空
- **AND** `engine.session.activeLap == null`

#### Scenario: 中途 time 包丢失 3 帧后恢复

- **GIVEN** 已正常同步 5 帧并开圈
- **WHEN** 中间 3 帧强制 syncBits 不匹配（模拟 time 包丢失）
- **AND** 再恢复 5 帧 syncBits 匹配
- **THEN** `activeLap` 在失联期间保留不变
- **AND** `engine.session.samples.size == 9`（5 + 4，恢复后首帧走首样本守卫不入列）

#### Scenario: 端到端链路内不得访问 System.currentTimeMillis

- **GIVEN** 测试过程中 mock 或拦截 `System.currentTimeMillis()` 调用
- **WHEN** 发射端生成帧 → parser 解析 → engine 处理的完整闭环
- **THEN** 整个调用栈上 `System.currentTimeMillis()` 被调用次数 == 0

---

### Requirement: UI 层根据 `GpsDataViewModel.gpsData.isTimeSynced` 区分"等待协议时间同步"与"等待起点"

`LapDebugExecutionScreen` 的起终点计时卡片 MUST 按以下优先级决定 `statusLabel`（高到低）：

1. `activeLap != null` → `"当前圈进行中"`
2. `activeLap == null && gpsDataViewModel.gpsData.isTimeSynced == true` → `"等待起点"`
3. `activeLap == null && gpsDataViewModel.gpsData.isTimeSynced == false` → `"等待协议时间同步"`

UI 层 MUST NOT 通过读取 `lapSession.samples` 的 `GpsSample` 字段推断"是否已同步"，因为：
- 未同步帧被 `bridgeGpsToLapTiming` 拦住，根本不进入 `lapSession.samples`
- `GpsSample` 不得携带 `isTimeSynced` 字段（该字段在 `GpsSample` 上永远为 true，是死字段）

UI 层 MUST 通过 `GpsDataViewModel.gpsData.collectAsState()` 读上游实时同步状态。

#### Scenario: 未同步时 statusLabel 显示"等待协议时间同步"

- **GIVEN** `gpsDataViewModel.gpsData.value.isTimeSynced == false`
- **AND** `lapSession.activeLap == null`
- **WHEN** UI 组合 `statusLabel`
- **THEN** `statusLabel == "等待协议时间同步"`

#### Scenario: 已同步未开圈时 statusLabel 显示"等待起点"

- **GIVEN** `gpsDataViewModel.gpsData.value.isTimeSynced == true`
- **AND** `lapSession.activeLap == null`
- **WHEN** UI 组合 `statusLabel`
- **THEN** `statusLabel == "等待起点"`

#### Scenario: 开圈后 statusLabel 显示"当前圈进行中"

- **GIVEN** `lapSession.activeLap != null`
- **WHEN** UI 组合 `statusLabel`
- **THEN** `statusLabel == "当前圈进行中"`
- **AND** `statusLabel` **不依赖** `gpsData.isTimeSynced` 的值（`isTimeSynced=false` 的短暂失联期间显示仍为"当前圈进行中"）

#### Scenario: GpsSample 不得带 isTimeSynced 字段

- **GIVEN** `feature/test/.../model/laptiming/GpsSample.kt`
- **WHEN** 检查 data class 字段列表
- **THEN** 字段列表不包含 `isTimeSynced`
- **AND** `TestSessionViewModel.toLapGpsSample()` 不写 `isTimeSynced`
