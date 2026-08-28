## Why

Records tab 和 Laps tab 上 6 处 mock 数据点是 V2 视觉重构期留下的占位，当前已有真实数据源（`TestResultRepository` 存性能测试结果、`TelemetryRepository` + Room schema v4 字段存 lap session 元数据）但未接通 UI。结果：

- 用户跑完一次圈速 / 加减速测试，回到 Laps / Records tab 看到的仍然是 hardcode "1:32.457" / "4.21 s" / "Personal Best · placeholder"，**真实数据不可见**
- 切换赛道 / 多次 session 后，PB / sessions 计数 / RECENT RUNS 等都是 mock 静态值，**用户行为对 UI 状态无影响**
- C round（`persist-session-summary-fields`）已加 Room schema v4 的 `topSpeedKmh` / `lapCount` / `bestLapMs` / `trackId` / `trackNameSnapshot` 字段，**写入侧已通**，但读取侧没消费

本 round 把 6 处 mock 全部接真实 Repository 查询，让 Laps + Records tab 显示用户实际跑过的数据。

## What Changes

### Capability 1：`track-presentation`（modified）—— UI 数据真实化

#### 数据查询层（Repository 扩展）

- **新增 domain DTO** `TestResultSummary`（在 `core/domain/.../model/TestModels.kt` 追加），只含 UI 需要的轻量字段（id / testTemplateId / carModel / timestamp / totalTime / totalDistance），**不含** segments / dataPoints —— 这两个需要 join SpeedSegment 表 + 解析 binary file，本 round UI 列表 / metric tile 渲染不需要
- **`TestResultRepository`** 加聚合查询（**返回 `Summary` 而非 `TestResult`**，避免 entity → 完整 domain 无损转换问题）：
  - `getBestResult(template: TestTemplate): Flow<TestResultSummary?>` —— 按 template 类型找 totalTime 最小（0-100）/ totalDistance 最小（brake）的最佳成绩
  - `getTotalRunCount(): Flow<Int>` —— 全部 results 总条数
  - `getRecentResultsFlow(limit: Int): Flow<List<TestResultSummary>>` —— 最近 N 条结果（自动响应新 result 写入）
  - 现有 `testResultsFlow: Flow<List<TestRecordEntity>>` / `saveResult` / `getSegments` / `deleteResult` 不动
- **`TelemetryRepository`** 加按 trackId 聚合查询：
  - `getBestLapForTrack(trackId: String): Flow<TelemetrySession?>` —— 该赛道 `sessionType='LAP_SESSION'` + 闭环 (`endTs > startTs`) + `bestLapMs IS NOT NULL` 的所有 session 中 bestLapMs 最小（C round 已写入 Room 字段，直接 query；详细 schema 口径见本文末尾"TelemetrySession 查询关键 schema 口径"段）
  - `getSessionCountForTrack(trackId: String): Flow<Int>` —— 该赛道 session 总数
  - `getTotalLapCountForTrack(trackId: String): Flow<Int>` —— 该赛道所有 session 的 lapCount 之和
  - `getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySession>>` —— 该赛道最近 N 条 session（已有 `getRecentLapSessions(limit)` 是全部赛道，本 round 加按 trackId 过滤版）

#### ViewModel 层（暴露统计 flow）

- `TestSessionViewModel` 暴露：
  - `bestAcceleration0To100: StateFlow<TestResultSummary?>`
  - `bestBraking100To0: StateFlow<TestResultSummary?>`
  - `totalRunCount: StateFlow<Int>`
  - `recentRuns: StateFlow<List<TestResultSummary>>`（最近 5 条）
  - `bestLapForCurrentTrack: StateFlow<TelemetrySession?>`（跟随 currentSelectedTrack 切换）
  - `sessionCountForCurrentTrack: StateFlow<Int>`
  - `totalLapCountForCurrentTrack: StateFlow<Int>`
  - `recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>>`
- 这些 StateFlow 用 `combine` + `flatMapLatest` 与 `currentSelectedTrack` 联动：currentTrack 变 → flatMapLatest 切到新 trackId 的 query flow

#### UI 层接入

- **`LapsHomeScreen.kt`** RECENT BEST 区块（L160-183）：
  - `MetricTile` value 改 `bestLapForCurrentTrack.value?.bestLapMs?.let { formatLapMs(it) } ?: "--"`
  - status 改 `bestLapForCurrentTrack.value?.let { "Personal Best · ${formatDate(it.startTs)}" } ?: "暂无成绩"`
  - 删除 hardcode `"1:32.457"` + `"Personal Best · placeholder"`
- **`RecordsHomeScreen.kt` PERFORMANCE 区块**（L135-201）：
  - BEST 0-100 MetricTile value 改 `bestAcceleration0To100.value?.totalTime?.let { "%.2f".format(it) } ?: "--"`，unit "s" 不变
  - BEST BRAKE MetricTile value 改 `bestBraking100To0.value?.totalDistance?.let { "%.1f".format(it) } ?: "--"`，unit "m" 不变
  - TOTAL RUNS MetricTile value 改 `totalRunCount.value.toString()`
  - RECENT RUNS 列表 改消费 `recentRuns.value`，每条 `recentRunRowContent` 派生自 `TestResultSummary`（`testTemplateId` 决定 type 字符串、totalTime/totalDistance 决定 value、timestamp 决定 time 字符串、isPB 通过和 bestAcc?.id / bestBrake?.id 比对决定）
  - **删除** `placeholderRecentRuns` top-level mock val
  - **SpeedCurveStub**：本 round 暂不接真实数据（标 follow-up，需要解决"画哪一次 run 的速度曲线"产品决策）
- **`RecordsHomeScreen.kt` LAPS 区块**（L383+）：
  - `LapsView` 内部 `record = remember(currentTrack, bestLap, sessionCount, totalLapCount) { CurrentTrackRecord(...) }` 派生：
    - `trackName` 仍从 currentTrack.name.zh
    - `bestLapTime` 从 `bestLapForCurrentTrack.bestLapMs.let { formatLapMs(it) }` 或 "--"
    - `bestLapDate` 从 `bestLapForCurrentTrack.startTs.let { formatDate(it) }` 或 "暂无"
    - `length` 仍从 currentTrack.lengthKm
    - `direction` 暂保留 mock "Clockwise"（赛道方向是 Track 静态属性，未来加 Track.direction 字段时再接）
    - `sessions` 从 `sessionCountForCurrentTrack`
    - `totalLaps` 从 `totalLapCountForCurrentTrack`
  - SESSION HISTORY 列表改消费 `recentSessionsForCurrentTrack`，每条 row 显示 `${formatDate(session.startTs)} · ${session.lapCount} Laps · Best ${formatLapMs(session.bestLapMs)}`
  - **删除** `placeholderLapSessions` top-level mock val

#### 工具函数

- 新建 `feature/test/src/main/.../ui/tracktech/format/MetricFormatter.kt`：`formatLapMs(ms: Long): String`（输出 "1:32.457" 格式）+ `formatDate(epochMs: Long): String`（输出 "May 18, 2024" 格式或本地化）+ `formatRunTimestamp(epochMs: Long): String`（输出 "Today, 10:35" 或 "May 18, 2024"，根据距今时长）—— 单一 source 防止散落

### Non-goals（明确划出本 change 之外）

- **不做 SpeedCurveStub 真实化**：曲线数据"画哪次 run"是产品决策（最近一次 / PB 那次 / 用户选择），开新 round
- **不动赛道方向（direction）**：mock "Clockwise" 保留；属于 Track 数据契约扩展，未来 round
- **不做 Records tab 全 tab 维度筛选**：目前 PERFORMANCE 区块的统计是"全部赛道全部车型聚合"（与 mock 行为一致），按车型 / 日期 / 赛道筛选属于未来 Records filter round
- **不动 Records tab CurrentTrackRecordCard 的 ★ 收藏图标**：与 enhance-track-presentation 收藏 Non-goal 一致
- **不改 Repository 已有方法签名**：仅追加新查询方法，避免与 A round（同改 TelemetryRepository）冲突
- **不做新 V2 性能测试详情页 Composable / NavHost route 注册** —— 那是 `redesign-performance-result-screen` round 范围。但本 round 在 §4.6 task 接它的入端：RecentRuns onClick 调 `navController.navigate("performance_result/${result.id}")`，进入 redesign 已注册的 `performance_result/{testId}` route。这是 redesign round 路径 A scope 转移的责任接收点（2026-05-01 立项）
- **不动 Room schema**：本 round 仅消费 C round 已加的 v4 字段，不再迁移

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `track-presentation`：把 Records tab PERFORMANCE / LAPS + Laps tab RECENT BEST 6 处 mock 数据点接真实 Repository 查询。包括 TestResultRepository / TelemetryRepository 加 4+4 个聚合查询方法、TestSessionViewModel 暴露 8 个新 StateFlow、UI 消费侧改 6 处 + 删 2 处 placeholder mock val + 新建 MetricFormatter

## Impact

### 受影响模块路径

- `core/data/src/main/.../repository/TestResultRepository.kt`（加 3 个 Flow 查询方法）
- `core/data/src/main/.../repository/TelemetryRepository.kt`（加 4 个 Flow 查询方法）—— **与 round A 共享此文件，函数级不重叠**（A 改 startSession/endSession 加 `activeSessionStartTs` property；本 round 加新 query 方法）。看板 §6 共享文件登记
- `core/data/src/main/.../local/dao/TestRecordDao.kt`（如果需要加 @Query 方法）
- `core/data/src/main/.../local/dao/TelemetrySessionDao.kt`（如果需要加 @Query 方法）
- `feature/test/src/main/.../viewmodel/TestSessionViewModel.kt`（加 8 个 StateFlow）—— **与 round A 共享此文件，函数级不重叠**（A 改 bridgeGpsToLapTiming）
- `feature/test/src/main/.../ui/tracktech/LapsHomeScreen.kt`（RECENT BEST 区块接 ViewModel）
- `feature/test/src/main/.../ui/tracktech/RecordsHomeScreen.kt`（PERFORMANCE + LAPS 区块全部接 ViewModel + 删 placeholder val）
- `feature/test/src/main/.../ui/tracktech/format/MetricFormatter.kt`（新建工具函数）

### 测试

- 新增 `core/data/src/test/.../repository/TestResultRepositoryAggregateQueryTest.kt`（4 cases：空 results / 单类型 best / 多类型 best 互不干扰 / Flow 自动响应新写入）
- 新增 `core/data/src/test/.../repository/TelemetryRepositoryTrackQueryTest.kt`（4 cases：trackId 过滤 / best lap 取最小 bestLapMs / sessionCount 排除其他 trackId / Flow 自动响应新 endSession）
- 扩展 `TestSessionViewModelTrackSelectionTest.kt`：currentSelectedTrack 切换后，bestLapForCurrentTrack / sessionCountForCurrentTrack / totalLapCountForCurrentTrack StateFlow MUST 切到新 trackId 的 query 结果
- 新建 `MetricFormatterTest.kt`（lap ms / date / run timestamp 格式输出契约）

### 协议兼容性

- 不涉及 RaceChrono BLE 协议
- 不涉及 replay JSON / VBO 协议
- 不动 Room schema（消费 C round 已加 v4 字段）

### 双端任务划分

- 仅接收端 gps-app 改动，simulator 不涉及

### 并行 round 协同

- **本 round** = round F（新登记）
- 共享文件 `TelemetryRepository.kt` + `TestSessionViewModel.kt` 在 §6 登记 ongoing
- 与 round A `fix-lap-binary-ts-hygiene` 函数级不重叠：
  - A 改 `TelemetryRepository.startSession/endSession`（加 property）+ `TestSessionViewModel.bridgeGpsToLapTiming`（line 596 公式）
  - F 加 `TelemetryRepository.getBestLapForTrack` 等新方法 + `TestSessionViewModel` 顶层 StateFlow

**TelemetrySession 查询关键 schema 口径**（避开 Codex review v1 错位）：

- `TelemetrySessionEntity.sessionType: String`（**不是 `type`**）；enum 写入值 `TelemetrySessionType.LAP_SESSION.name = "LAP_SESSION"`（**不是 `"LapSession"`**）
- `TelemetrySessionEntity.endTs: Long` **非空**，`startSession()` 写入 `endTs = startTs` 占位、`endSession()` 才写真实 endTs；闭环判定 MUST 用 `endTs > startTs`（**不能** `endTs IS NOT NULL`）
- `bestLapMs: Long?` 可空（lap 完整时才填），best lap 查询 MUST 加 `bestLapMs IS NOT NULL` 排除
- `TestRecordEntity` 列名是 `testTemplateId`（不是 `templateId`）
- 与用户另一 session 的 `redesign-performance-result-screen` 不交叉（那个改 TestResultScreen / TestExecutionScreen / 新建 PerformanceResultScreen，本 round 改 Records / Laps tab）
- 真机验证按 §4.2 串行规则
- push 顺序由 user 拍板（§4.1）
