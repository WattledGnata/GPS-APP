# Track Presentation Capability — wire real data to Records & Laps tabs

## ADDED Requirements

### Requirement: TestResultRepository 提供性能测试聚合查询

The system SHALL 在 `core/data/.../repository/TestResultRepository.kt` 追加（不改动现有 `saveResult` / `getRecentResults` / `getSegments` / `deleteResult` 签名）以下 Flow 查询方法：

- `fun getBestResult(template: TestTemplate): Flow<TestResultSummary?>` —— 按 template 类型返回最佳成绩；`Acceleration0To100` 取 `totalTime` 最小，`Braking100To0` 取 `totalDistance` 最小（通过 DAO `@Query MIN(...)`）；无对应 result 时返回 `null`
- `fun getTotalRunCount(): Flow<Int>` —— 全部 results 总条数（DAO `@Query COUNT(*)`）
- `fun getRecentResultsFlow(limit: Int): Flow<List<TestResultSummary>>` —— 现有 `suspend fun getRecentResults(limit)` 的 Flow 版本（DAO `@Query ORDER BY timestamp DESC LIMIT :limit`）

所有 Flow MUST 自动响应新 result 写入（DAO 返回 Flow 自带 Room invalidation）。

#### Scenario: 空 results 返回 null / 0

- **WHEN** Room test_records 表为空、订阅 `getBestResult(Acceleration0To100)` / `getTotalRunCount()` / `getRecentResultsFlow(5)`
- **THEN** 三者首次 emit 分别 MUST 为 `null` / `0` / `emptyList()`，不抛异常

#### Scenario: best result 按 template 隔离

- **WHEN** Room 含 2 条 acc_0_100 result（totalTime 4.5s 和 5.0s）+ 1 条 brake_100_0 result（totalDistance 36.8m）
- **THEN** `getBestResult(Acceleration0To100).first()?.totalTime` MUST = 4.5；`getBestResult(Braking100To0).first()?.totalDistance` MUST = 36.8

#### Scenario: Flow 自动响应新写入

- **WHEN** 订阅 `getBestResult(Acceleration0To100)` 后调 `saveResult(newAccResult with totalTime = 4.0)`
- **THEN** Flow MUST emit 新值 `newAccResult`（4.0s 比之前的 best 更小），无需手动刷新

### Requirement: TelemetryRepository 提供按 trackId 聚合查询

The system SHALL 在 `core/data/.../repository/TelemetryRepository.kt` 追加（不改动现有 `startSession` / `endSession` / `getRecentLapSessions` 等签名）以下 Flow 查询方法：

- `fun getBestLapForTrack(trackId: String): Flow<TelemetrySession?>` —— 该 trackId 的所有 sessionType='LAP_SESSION' + 已结束（`endTs > startTs`，因 entity.endTs 非空、startSession 写入 endTs=startTs 占位）+ bestLapMs IS NOT NULL 的 session 中 bestLapMs 最小那条；无对应 session 返回 `null`
- `fun getSessionCountForTrack(trackId: String): Flow<Int>` —— 该 trackId 的闭环 session（`sessionType='LAP_SESSION'` + `endTs > startTs`）总数
- `fun getTotalLapCountForTrack(trackId: String): Flow<Int>` —— 该 trackId 所有闭环 session 的 `lapCount` 之和
- `fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySession>>` —— 该 trackId 最近 N 条闭环 session（按 startTs 倒序）

所有 Flow MUST 自动响应新 endSession 写入。

**关键 schema 口径**（避开 Codex review v1 误用）：

- `TelemetrySessionEntity.sessionType: String`（**不是 `type`**），enum 写入值 `TelemetrySessionType.LAP_SESSION.name = "LAP_SESSION"`（**不是 `"LapSession"`**）
- `TelemetrySessionEntity.endTs: Long` 非空，`startSession()` 写入 `endTs = startTs` 作为 in-progress 占位、`endSession()` 写入真实 `endTs = currentMillis`。所以"已结束"判定 MUST 用 `endTs > startTs`（**不能**用 `endTs IS NOT NULL`，那样 in-progress session 也会算入）
- `bestLapMs: Long?` 可空（C round 写入侧在 lap 完整时才填），所以 best lap 查询 MUST 加 `bestLapMs IS NOT NULL` 排除

#### Scenario: trackId 过滤

- **WHEN** Room telemetry_sessions 含 2 条 trackId="preset-tfic-lpcc" + 1 条 trackId="preset-boyu-loop" 的闭环 session
- **THEN** `getSessionCountForTrack("preset-tfic-lpcc").first()` MUST = 2；`getSessionCountForTrack("preset-boyu-loop").first()` MUST = 1

#### Scenario: best lap 取 bestLapMs 最小

- **WHEN** trackId="X" 有 3 条 session，bestLapMs 分别 92457 / 90000 / 95000（ms）
- **THEN** `getBestLapForTrack("X").first()?.bestLapMs` MUST = 90000

#### Scenario: 排除 in-progress 与 invalid

- **WHEN** trackId="X" 有 1 条 in-progress（`endTs == startTs` 占位、未调 endSession）+ 1 条 bestLapMs=null（首圈未完成）+ 2 条正常 session（endTs > startTs + bestLapMs 非空）
- **THEN** `getSessionCountForTrack("X").first()` MUST = 3（含 bestLapMs=null 的闭环 session、不含 in-progress 的）；`getBestLapForTrack("X").first()` MUST 排除 bestLapMs=null 那条 + 排除 in-progress 那条，从剩余 2 条取最小

#### Scenario: totalLapCount 累加

- **WHEN** trackId="X" 的 3 条闭环 session 的 lapCount 分别 5 / 8 / 12
- **THEN** `getTotalLapCountForTrack("X").first()` MUST = 25

### Requirement: TestSessionViewModel 暴露真实统计 StateFlow

The system SHALL 在 `TestSessionViewModel` 追加 8 个 StateFlow 暴露 Repository 聚合查询，4 个性能测试相关 + 4 个圈速 session 相关（后者随 currentSelectedTrack 自动 flatMapLatest 切换）：

```kotlin
val bestAcceleration0To100: StateFlow<TestResultSummary?>
val bestBraking100To0: StateFlow<TestResultSummary?>
val totalRunCount: StateFlow<Int>
val recentRuns: StateFlow<List<TestResultSummary>>
val bestLapForCurrentTrack: StateFlow<TelemetrySession?>
val sessionCountForCurrentTrack: StateFlow<Int>
val totalLapCountForCurrentTrack: StateFlow<Int>
val recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>>
```

实施 MUST 满足：

- 性能测试 4 个 StateFlow 由 Repository Flow 直接 `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 初始值)`
- 圈速 4 个 StateFlow 由 `_currentSelectedTrack.filterNotNull().flatMapLatest { track -> repository.getXxxForTrack(track.id) }.stateIn(...)` —— currentSelectedTrack 变化自动切到新 trackId 的 query 流
- `recentRuns` 上限 5 条（与 mock 一致）；`recentSessionsForCurrentTrack` 上限 5 条
- 初始值：性能测试 `null` / `0` / `emptyList()`；圈速 `null` / `0` / `0` / `emptyList()`

#### Scenario: currentSelectedTrack 切换 → 圈速 StateFlow 切流

- **WHEN** ViewModel 的 `currentSelectedTrack` 从 trackA 切到 trackB（user `selectTrack(trackB)`）
- **THEN** `bestLapForCurrentTrack` / `sessionCountForCurrentTrack` / `totalLapCountForCurrentTrack` / `recentSessionsForCurrentTrack` MUST 自动切到 trackB.id 的 query 结果（旧 trackA 的 Flow 订阅 cancel）

#### Scenario: 新 lap session 完成自动反映到 currentTrack StateFlow

- **WHEN** currentSelectedTrack = trackA，订阅 `bestLapForCurrentTrack` 后，user 跑完 trackA 的一次 lap session 触发 endSession（写入 bestLapMs 比之前更小）
- **THEN** `bestLapForCurrentTrack.value` MUST 自动更新为新 session

### Requirement: LapsHomeScreen RECENT BEST 接真实当前赛道 best lap

The system SHALL 在 `LapsHomeScreen.kt` 中 RECENT BEST 区块（当前 L160-183 `MetricTile`）改消费 `testSessionViewModel.bestLapForCurrentTrack` StateFlow：

- `MetricTile.value` MUST 改为 `bestLapForCurrentTrack.collectAsState().value?.bestLapMs?.let { formatLapMs(it) } ?: "--"`
- `MetricTile.status` MUST 改为 `bestLapForCurrentTrack.collectAsState().value?.let { "Personal Best · ${formatDate(it.startTs)}" } ?: "暂无成绩"`
- `MetricTile.label` 仍为 `currentTrackLabel.uppercase()`（与 enhance-track-presentation 一致）
- 删除 hardcode `value = "1:32.457"` + `status = "Personal Best · placeholder"`

#### Scenario: 当前赛道有 best lap

- **WHEN** currentSelectedTrack = trackA，trackA 的 best session bestLapMs = 92457 + startTs 对应 "May 18, 2024"
- **THEN** RECENT BEST `MetricTile` MUST 显示 value `"1:32.457"` + status `"Personal Best · May 18, 2024"`

#### Scenario: 当前赛道暂无 best lap

- **WHEN** currentSelectedTrack = trackB，trackB 无任何 lap session 历史
- **THEN** RECENT BEST `MetricTile` MUST 显示 value `"--"` + status `"暂无成绩"`，不抛异常

### Requirement: RecordsHomeScreen PERFORMANCE 区块接真实 TestResult

The system SHALL 在 `RecordsHomeScreen.kt` PERFORMANCE 区块（当前 L135-201）改消费真实 ViewModel StateFlow：

- BEST 0-100 `MetricTile.value` MUST 改 `bestAcceleration0To100.value?.totalTime?.let { "%.2f".format(it) } ?: "--"`
- BEST BRAKE `MetricTile.value` MUST 改 `bestBraking100To0.value?.totalDistance?.let { "%.1f".format(it) } ?: "--"`
- TOTAL RUNS `MetricTile.value` MUST 改 `totalRunCount.value.toString()`
- RECENT RUNS 区块的 `placeholderRecentRuns.forEach { ... }` MUST 改为 `recentRuns.value.forEach { ... }`，每条 row 数据由 `TestResultSummary` 派生：
  - `type` 字符串：`Acceleration0To100` → `"0-100 km/h"`、`Braking100To0` → `"100-0 km/h"`
  - `value` 字符串：acceleration 用 `"%.2f s".format(totalTime)`，braking 用 `"%.1f m".format(totalDistance)`
  - `time` 字符串：`formatRunTimestamp(timestamp)` → `"Today, 10:35"` / `"May 18, 2024"`（按距今判定）
  - `isPB`：判断该条 result 是否等于 `bestAcceleration0To100` / `bestBraking100To0`（按 template 类型）
- **删除** top-level `private val placeholderRecentRuns: List<RecentRun>`
- **保留** `private data class RecentRun` + `recentRunRowContent` 派生函数（数据结构和派生逻辑复用）

#### Scenario: 全空状态

- **WHEN** Room test_records 表为空
- **THEN** Records tab PERFORMANCE 区块 MetricTile 三项 MUST 显示 `"--"` / `"--"` / `"0"`；RECENT RUNS 区块 MUST 不渲染任何 row（List 为空 forEach 不执行）

#### Scenario: 多条 result 累加 + PB 标识

- **WHEN** Room 含 5 条 acc_0_100 result + 3 条 brake_100_0 result，最佳 acc 是 4.21s（May 18），最佳 brake 是 36.8m（Apr 29）
- **THEN** BEST 0-100 显示 `"4.21"` + unit `"s"`；BEST BRAKE 显示 `"36.8"` + unit `"m"`；TOTAL RUNS 显示 `"8"`；RECENT RUNS 列表中包含最近 5 条，最佳 acc / 最佳 brake 那两条 MUST `isPB = true`（视觉显示金牌图标）

### Requirement: RecordsHomeScreen LAPS 区块接真实当前赛道 session 数据

The system SHALL 在 `RecordsHomeScreen.kt` `LapsView`（当前 L383+）改造 `record` 派生：

- `bestLapTime` MUST 改 `bestLapForCurrentTrack.value?.bestLapMs?.let { formatLapMs(it) } ?: "--"`
- `bestLapDate` MUST 改 `bestLapForCurrentTrack.value?.startTs?.let { formatDate(it) } ?: "暂无"`
- `sessions` MUST 改 `sessionCountForCurrentTrack.value`
- `totalLaps` MUST 改 `totalLapCountForCurrentTrack.value`
- `trackName` 仍 `currentTrack?.name?.zh ?: "—"`（不动）
- `length` 仍 `currentTrack?.let { "%.3f km".format(it.lengthKm) } ?: "—"`（不动）
- `direction` 仍 `"Clockwise"` mock 保留（Non-goal NG2）

SESSION HISTORY 区块的 `placeholderLapSessions.forEach { ... }` MUST 改为 `recentSessionsForCurrentTrack.value.forEach { session -> ... }`，row title 字符串改 `"${formatDate(session.startTs)} · ${session.lapCount} Laps · Best ${session.bestLapMs?.let { formatLapMs(it) } ?: "--"}"`（**`bestLapMs` nullable，禁止用 `?: 0` fallback —— 那样会假显示 `"0:00.000"`，与"无成绩"语义混淆**）。

**删除** top-level `private val placeholderLapSessions: List<LapSessionRow>` 与 `private data class LapSessionRow`（不再使用）。**保留** `private data class CurrentTrackRecord`（仍承载 record 派生结果给 CurrentTrackRecordCard）。

#### Scenario: 当前赛道有 session 历史

- **WHEN** currentSelectedTrack = TFIC，TFIC 含 8 个闭环 session（best bestLapMs = 92457，最近 session.lapCount = 5）
- **THEN** CurrentTrackRecordCard MUST 显示 BEST LAP `"1:32.457"` + bestLapDate；SESSIONS `"8"`；TOTAL LAPS 累加值；SESSION HISTORY 列表显示最近 5 条 session

#### Scenario: 切换赛道 → 区块数据切换

- **WHEN** 当前 trackA，user 切到 trackB（trackB 之前从未跑过）
- **THEN** CurrentTrackRecordCard 的 `trackName` 切到 trackB.name.zh，`bestLapTime` / `bestLapDate` / `sessions` / `totalLaps` MUST 全部切到 trackB 数据（`"--"` / `"暂无"` / `"0"` / `"0"`），SESSION HISTORY 列表 MUST 为空

#### Scenario: 移除 mock placeholder

- **WHEN** 本 round 全部 task 完成
- **THEN** `RecordsHomeScreen.kt` MUST NOT 包含 `placeholderRecentRuns` / `placeholderLapSessions` top-level val 定义；MUST NOT 包含 `LapSessionRow` data class 定义

### Requirement: MetricFormatter 工具集中格式化逻辑

The system SHALL 在 `feature/test/src/main/.../ui/tracktech/format/MetricFormatter.kt` 新建 3 个纯函数（无 Composable / 无 Context 依赖、易单元测试）：

- `fun formatLapMs(ms: Long): String` —— 输入毫秒，输出 `"M:SS.mmm"` 格式（如 92457 → `"1:32.457"`）
- `fun formatDate(epochMs: Long, locale: Locale = Locale.ENGLISH): String` —— 输入 epoch 毫秒，输出 `"MMM d, yyyy"` 格式（如 epochMs 对应 May 18, 2024 → `"May 18, 2024"`）
- `fun formatRunTimestamp(epochMs: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.ENGLISH): String` —— 输入 epoch 毫秒和当前时间，按距今时长判定输出："今天" 用 `"Today, HH:mm"`、"昨天" 用 `"Yesterday, HH:mm"`、"7 天内" 用 `"<weekday>, HH:mm"`、超过 7 天用 `"MMM d, yyyy"` 格式

所有函数 MUST 是纯函数（无副作用、无可变状态、无 Context 依赖），便于 JUnit 单元测试。

#### Scenario: lap ms 格式

- **WHEN** 调 `formatLapMs(92457)`
- **THEN** 返回 `"1:32.457"`

#### Scenario: lap ms 边界

- **WHEN** 调 `formatLapMs(0)` / `formatLapMs(60000)` / `formatLapMs(3600000)`
- **THEN** 分别返回 `"0:00.000"` / `"1:00.000"` / `"60:00.000"`（不强制小时位分割）

#### Scenario: date 格式

- **WHEN** 调 `formatDate(epochMs for 2024-05-18 10:35 UTC, Locale.ENGLISH)`
- **THEN** 返回 `"May 18, 2024"`

#### Scenario: run timestamp 按距今分级

- **WHEN** `epochMs` 是 `now - 1 hour`，`now` 是 today 14:35 → `formatRunTimestamp(epochMs, now)` 返回 `"Today, 13:35"`
- **WHEN** `epochMs` 是 7 天前 → 返回 `"MMM d, yyyy"` 格式（绝对日期）

### Requirement: 旧 mock 数据点从 UI 源码消失

The system SHALL 在本 round 全部 task 完成后，`RecordsHomeScreen.kt` 与 `LapsHomeScreen.kt` 内：

- MUST NOT 包含 hardcode 字符串 `"4.21"` / `"36.8"` / `"24"` / `"1:32.457"` / `"Personal Best · placeholder"` / `"4.58 s"` / `"38.2 m"` / `"May 18, 2024"`（作为 mock 数据值；如果是 unit test 文件或 spec/proposal 引用不算）
- MUST NOT 包含 `placeholderRecentRuns` / `placeholderLapSessions` 标识符
- 真机签收：跑完一次 0-100 测试后 Records tab BEST 0-100 数字 MUST 反映该条结果；跑完一次 lap session 后 Laps tab RECENT BEST 数字 MUST 反映该 session bestLap

#### Scenario: grep 自检

- **WHEN** 本 round 全部 task 完成
- **THEN** `grep -rn '"1:32.457"\|"Personal Best · placeholder"\|placeholderRecentRuns\|placeholderLapSessions' feature/test/src/main` MUST 返回 0 结果
