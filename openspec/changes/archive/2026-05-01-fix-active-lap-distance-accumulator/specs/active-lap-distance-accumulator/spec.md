## ADDED Requirements

### Requirement: `ActiveLap.distanceMetersSinceStart` 字段契约

`ActiveLap` data class MUST 新增 `distanceMetersSinceStart: Double = 0.0` 字段（语义：从开圈点累计到当前帧的距离，单位米）。

- 类型 `Double`、默认值 `0.0`
- active lap 生命期内 MUST 单调不减
- 闭圈瞬间该 active lap 立即被新 `ActiveLap(distanceMetersSinceStart=0.0)` 替换；单调性契约**不**覆盖"闭圈替换"边界

#### Scenario: 字段类型与默认值

- **GIVEN** `ActiveLap(lapIndex = 1, startedAtMillis = 1000L, sampleStartIndex = 0)` 不显式传入 `distanceMetersSinceStart`
- **WHEN** 读取 `activeLap.distanceMetersSinceStart`
- **THEN** 类型为 `Double`，值为 `0.0`

#### Scenario: 字段在 active lap 生命期内单调不减

- **GIVEN** ActiveLap 已开圈（lap N），engine 接收若干合法 GpsSample 序列
- **WHEN** 每次 `processSample` 返回的 `session.activeLap?.distanceMetersSinceStart` 与前一次 session.activeLap 同 lap N 的值比较
- **THEN** 新值 ≥ 旧值（haversine 返回非负，单调不减）

#### Scenario: 闭圈瞬间该 active lap 被替换不在单调性契约范围内

- **GIVEN** ActiveLap (lap N) `distanceMetersSinceStart = 4500.0`，下一帧触发 start-finish accepted 闭圈
- **WHEN** `processSample` 返回新 session
- **THEN** `session.activeLap.lapIndex == N+1`、`session.activeLap.distanceMetersSinceStart == 0.0`
- **AND** 该重置**不**违反单调性契约：契约只在同一个 lap N 实例的生命期内成立

### Requirement: `LapTimingEngine` 是 `distanceMetersSinceStart` 的唯一 producer

`LapTimingEngine.processSample` MUST 是写入 `ActiveLap.distanceMetersSinceStart` 的**唯一代码路径**。UI 层、ViewModel 层、其他 use case MUST NOT 自行计算或写入该字段。

- 增量来源 MUST 是 `session.samples.lastOrNull()` 与 `currentSample`（**samples 流口径**），与旧 UI `samples.zipWithNext()` 同源；MUST NOT 改用 `processSample` 的 `previousSample` 参数（虽然此处常常指向同一 GpsSample，但语义来源应明示从 samples 流走，future 若 `previousSample` 因 ts 守卫等改变，distance 仍跟 samples 流）
- 增量算法 MUST 是 `haversineDistanceMeters(prev.lat, prev.lon, current.lat, current.lon)`（asset 经度差不做 ±180° wrap，本 round 不引入 A44 `wrappedDeltaLon`）

#### Scenario: ActiveLap 构造时显式 distanceMetersSinceStart = 0.0

- **GIVEN** 实施后 `LapTimingEngine.kt` 源码
- **WHEN** grep 所有 `ActiveLap(` 构造点（line 142-148 首次开圈、line 199-204 闭圈后新开）
- **THEN** 每处构造均**显式**写出 `distanceMetersSinceStart = 0.0`（依赖默认值是合法 Kotlin 但本契约要求显式以提升可读性 + 防御性）

#### Scenario: 增量来源是 session.samples.lastOrNull() 而非 previousSample 参数

- **GIVEN** 实施后 `LapTimingEngine.kt` 源码
- **WHEN** 在 `activeLapWithDistance` 构造代码段内 grep prev 引用
- **THEN** `prev` 来自 `session.samples.lastOrNull()`，**不**直接使用 `processSample` 函数参数 `previousSample`（即使两者通常指向同一对象，spec 锁住语义来源）

#### Scenario: UI / ViewModel / 其他 use case 不写入 distanceMetersSinceStart

- **GIVEN** 实施后 `feature/test/src/main` 目录所有 `.kt` 源码
- **WHEN** grep `\.copy\(.*distanceMetersSinceStart\s*=` 与 `distanceMetersSinceStart\s*=` 赋值表达式
- **THEN** 命中点**仅**位于 `LapTimingEngine.kt` 与 `ActiveLap.kt`（前者是 producer 写入路径，后者是 default = 0.0 字段定义）

### Requirement: `processSample` 5 类返回路径全部正确处理 distance（路径 (a)-(f) 行为契约）

`LapTimingEngine.processSample` 的所有合法返回路径 MUST 在路径行为契约下正确处理 `ActiveLap.distanceMetersSinceStart`：

- 路径 (a) ts 回跳早退：return session 不入 samples 不累距
- 路径 (b) start-finish accepted 首次开圈：新 ActiveLap distanceMetersSinceStart=0.0
- 路径 (c) start-finish accepted 闭圈：closing active lap **不显式累入闭圈帧**（design D3 决策：closing lap 立即被替换，避免任何 observer 读到瞬时终值），闭圈后新 ActiveLap distanceMetersSinceStart=0.0
- 路径 (d) no target gate：`session.copy(samples, activeLap = activeLapWithDistance)` 携带累距
- 路径 (e) sector rejected（期待门拒绝）：`session.copy(samples, crossingEvents, activeLap = activeLapWithDistance)` 携带累距
- 路径 (f) sector accepted（期待门通过）：`activeLapWithDistance!!.copy(passedGateIds, sectorEntries)` 派生（**不**走 `session.activeLap.copy(...)`，避免覆盖 distance）

`handleSectorCrossing` 函数签名 MUST 加 `activeLapWithDistance: ActiveLap?` 参数（由 `processSample` 集中构造后传入）；`handleStartFinishCrossing` 签名**不变**（路径 (b)/(c) 都是新 ActiveLap = 0.0，无需消费 activeLapWithDistance）。

#### Scenario: 路径 (a) ts 回跳早退不累距

- **GIVEN** ActiveLap 已开圈，`session.samples.last().timestampMillis = 5000`，`session.activeLap.distanceMetersSinceStart = 100.0`
- **WHEN** 调用 `engine.processSample(session, track, previousSample, currentSample = GpsSample(timestampMillis = 4000, ...))`（ts 回跳）
- **THEN** 返回 session 与入参完全相等（`return session`，不入 samples）
- **AND** `session.activeLap.distanceMetersSinceStart == 100.0`（不变）

#### Scenario: 路径 (b) start-finish 首次开圈 distanceMetersSinceStart = 0.0

- **GIVEN** `session.activeLap == null`（未开圈状态）
- **WHEN** `processSample` 接收 start-finish accepted detection 帧
- **THEN** 返回 session 的 `activeLap.lapIndex == 1`、`activeLap.distanceMetersSinceStart == 0.0`

#### Scenario: 路径 (c) 闭圈不累入闭圈帧 + 新开 lap distanceMetersSinceStart = 0.0

- **GIVEN** ActiveLap (lap 1) `distanceMetersSinceStart = 4500.0`，下一帧触发 start-finish accepted（闭圈）
- **WHEN** `processSample` 处理该帧
- **THEN** 返回 session 的 `activeLap.lapIndex == 2`（已开新圈）、`activeLap.distanceMetersSinceStart == 0.0`（重置）
- **AND** session.completedLaps 含一条 LapRecord（lap 1）；该 LapRecord **不**含 `distanceMeters` 字段（A56 边界，schema 不变）
- **AND** closing active lap (lap 1) 的 distance 在 LapRecord 构造时**未**被读取（spec 不要求暴露闭圈帧累入与否，design D3 决策为不显式累入避免瞬时终值歧义）

#### Scenario: 路径 (d) no target gate 携带累距

- **GIVEN** ActiveLap 已开圈 `distanceMetersSinceStart = 100.0`，`session.nextExpectedGateIndex` 超出 `track.sectorGates` 范围（无下一门）
- **WHEN** `processSample` 接收一帧合法 sample（非 start-finish accepted），prev 与 current 之间的 haversine 距离 = 5.0 米
- **THEN** 返回 session 的 `activeLap.distanceMetersSinceStart == 105.0`
- **AND** session.samples 含 currentSample（已 append）

#### Scenario: 路径 (e) sector rejected 携带累距

- **GIVEN** ActiveLap 已开圈 `distanceMetersSinceStart = 200.0`
- **WHEN** `processSample` 接收一帧 prev→current 距离 = 3.5 米的 sample，detector 对 expected sector gate 给出 `accepted = false` 拒绝
- **THEN** 返回 session 的 `activeLap.distanceMetersSinceStart == 203.5`
- **AND** session.crossingEvents 含一条 `accepted=false` 的 expected gate event

#### Scenario: 路径 (f) sector accepted 累距 + sectorEntries 推进

- **GIVEN** ActiveLap 已开圈 `distanceMetersSinceStart = 300.0`、`passedGateIds = [start-finish]`、`sectorEntries = []`
- **WHEN** `processSample` 接收一帧 prev→current 距离 = 7.0 米的 sample，detector 对 expected sector gate s1 给出 `accepted = true`
- **THEN** 返回 session 的 `activeLap.distanceMetersSinceStart == 307.0`
- **AND** `activeLap.passedGateIds == [start-finish, s1]`
- **AND** `activeLap.sectorEntries.size == 1`
- **AND 实现细节锁定**：源码层面该路径 MUST 用 `activeLapWithDistance!!.copy(passedGateIds = ..., sectorEntries = ...)` 派生，**不**用 `session.activeLap.copy(...)`（grep 确认 `handleSectorCrossing` 内 sector accepted 分支无 `session.activeLap.copy(`）

### Requirement: UI 层是 `distanceMetersSinceStart` 的 consumer-only，源码零残留计算 pattern

`LapDebugExecutionScreen.rememberStartFinishTimingCardState` MUST 直接读 `lapSession.activeLap?.distanceMetersSinceStart ?: 0.0`，**不**自行计算距离。原 UI 层私有 `calculateDistanceSince` + `haversineDistanceMeters` MUST 整体删除。

#### Scenario: rememberStartFinishTimingCardState 读 engine 字段

- **GIVEN** `LapSession.activeLap = ActiveLap(distanceMetersSinceStart = 1234.5, ...)` 的 session
- **WHEN** 调用 `rememberStartFinishTimingCardState(lapSession, isTimeSynced = true)`
- **THEN** 返回的 `StartFinishTimingCardState.currentLapDistanceLabel` 等于 `formatDistanceMeters(1234.5)`

#### Scenario: UI 源码不再含 calculateDistanceSince / 私有 haversineDistanceMeters

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` 源码
- **WHEN** grep `calculateDistanceSince|private fun haversineDistanceMeters`
- **THEN** 零命中（两个函数完全删除）

#### Scenario: UI 源码不再做 distance pattern 计算

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` 源码
- **WHEN** grep `samples\.zipWithNext|samples\.filter \{ .*timestampMillis` （UI 层 O(N) 遍历 samples 计算距离 pattern）
- **THEN** 零命中（**注**：`samples.lastOrNull()?.timestampMillis` 用于 currentLapElapsedMillis 计算保留——这是 O(1) 单帧 ts 读取，不是距离 pattern）

### Requirement: 7500 samples 性能 smoke < 16ms（防波动）

UI state 函数 `rememberStartFinishTimingCardState` 在 7500 samples 输入下，单次调用 median 耗时 MUST < 16ms（60fps 帧预算）。性能 smoke 测试 MUST 用三层防抖设计避免 CI 抖动误报：

- warm-up 10 次（JIT 预热，丢弃测量）
- measure 5 次取中位数（min 太乐观、mean 受 outlier 污染）
- 内 loop 10 次取每次平均（单次 ns 太短不可测）
- 用 `System.nanoTime()` 而非 `currentTimeMillis()`（精度）

#### Scenario: 7500 samples median < 16ms

- **GIVEN** `LapSession` 含 7500 个 GpsSample（不必真实坐标）+ `activeLap.distanceMetersSinceStart = 任意预填值`
- **WHEN** warm-up 10 次后，5 次外层测量、每次内 loop 10 次调用 `rememberStartFinishTimingCardState(session, isTimeSynced=true)`，对每次外层测量取均值（ns / 10），最终取 5 个均值的中位数
- **THEN** 中位数 < 16,000,000 ns (= 16 ms)
- **AND 硬区分 v1**：v1 全量 haversine 7500 × 4 trig + filter ≈ 37,500 ops，预期单次 ≥ 5 ms 接近或超阈值；v2 单字段读 + 几条 O(N) 但 N ≪ samples 的 filter，预期 < 1 ms（留 16x 间隙）

### Requirement: A56 长期 GPS 点阵持久化边界

本 change MUST NOT 设计、实现长圈密集 GPS 点阵的持久化模型。`ActiveLap.distanceMetersSinceStart` 是**运行期 active-lap 派生状态**，**不**是长期 telemetry 真相源。

- 不新增数据库 schema / Room metadata / chunk 文件 / JSON telemetry payload
- 不修改 `LapRecord` schema（不新增 `distanceMeters` 字段，未来 A51 / A56 round 负责）
- closing active lap 的 distance 在 LapRecord 构造时**不**被读取，且立即被替换；future A56 round 的持久化路径与本字段独立并存

#### Scenario: LapRecord 不含 distance 字段

- **GIVEN** 实施后真实路径源码 `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`（**Review v2 P2-2 修补**：原 v1 误写为 `core/domain` 路径，已纠正；真实 LapRecord 位于 feature/test 模块下）
- **WHEN** 在该文件内 `data class LapRecord(...)` 字段列表中 grep `distanceMeters|distanceMetersSinceStart`
- **THEN** 零命中（LapRecord schema 不变，本 round 不新增 distance 类字段；A51 / A56 规划项负责）

#### Scenario: 不引入数据库 / chunk / 新 telemetry schema（diff 增量门槛）

- **Review v2 P2-1 修补**：v1 用全文 grep 会误伤现有代码（`LapDebugExecutionScreen.kt` 既有 telemetry 字样、`AppModule.kt` 既有 Room/database），改为**只扫本 change 新增 diff 行**
- **GIVEN** 本 change 的 `git diff <baseline>..HEAD` 输出
- **WHEN** 仅检查 diff **新增行**（`^\+` 前缀，排除 `^\+\+\+` 文件头），grep 收窄关键词集 `@Entity\b|@Dao\b|@Database\b|RoomDatabase\b|chunkWrite|persistDistance|@Insert\b|@Query\b`
- **THEN** 零命中（运行期派生状态边界，本 round 不新增持久化 / Room schema / 数据库写入路径）
- **AND 不扫**：宽泛词 `Room` / `database` / `telemetry` / `persistence`（项目存量已使用，全文扫会误伤）
