## 1. 数据层（Repository + DAO 扩展）

- [x] 1.1 修改 `core/data/src/main/.../local/dao/TestRecordDao.kt`：追加 3 个 `@Query` 方法
  - `@Query("SELECT * FROM test_records WHERE testTemplateId = :testTemplateId ORDER BY totalTime ASC LIMIT 1") fun getBestByTemplate(testTemplateId: String): Flow<TestRecordEntity?>`（acc 取 totalTime 最小，brake 取 totalDistance 最小 —— 可能要拆 2 个 @Query 或者按 caller 决定排序字段；推荐拆两个：`getBestAcceleration0To100(): Flow<TestRecordEntity?>` 按 totalTime + `getBestBraking100To0(): Flow<TestRecordEntity?>` 按 totalDistance）
  - `@Query("SELECT COUNT(*) FROM test_records") fun getTotalCount(): Flow<Int>`
  - `@Query("SELECT * FROM test_records ORDER BY timestamp DESC LIMIT :limit") fun getRecentFlow(limit: Int): Flow<List<TestRecordEntity>>`
- [x] 1.2 修改 `core/data/src/main/.../local/dao/TelemetrySessionDao.kt`：追加 4 个 `@Query` 方法
  - **关键 schema 口径**（避开 Codex review v1 错位，详见 spec Requirement 末尾"关键 schema 口径"段）：列名 `sessionType`（不是 `type`），enum 值 `'LAP_SESSION'`（不是 `'LapSession'`）；endTs 非空、startSession 写 `endTs = startTs` 占位、所以 in-progress vs 闭环判定 MUST 用 `endTs > startTs`（不能 `endTs IS NOT NULL`）
  - `@Query("SELECT * FROM telemetry_sessions WHERE trackId = :trackId AND endTs > startTs AND bestLapMs IS NOT NULL AND sessionType = 'LAP_SESSION' ORDER BY bestLapMs ASC LIMIT 1") fun getBestLapForTrack(trackId: String): Flow<TelemetrySessionEntity?>`
  - `@Query("SELECT COUNT(*) FROM telemetry_sessions WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION'") fun getSessionCountForTrack(trackId: String): Flow<Int>`
  - `@Query("SELECT COALESCE(SUM(lapCount), 0) FROM telemetry_sessions WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION'") fun getTotalLapCountForTrack(trackId: String): Flow<Int>`
  - `@Query("SELECT * FROM telemetry_sessions WHERE trackId = :trackId AND endTs > startTs AND sessionType = 'LAP_SESSION' ORDER BY startTs DESC LIMIT :limit") fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySessionEntity>>`
- [x] 1.3 修改 `core/data/src/main/.../repository/TestResultRepository.kt`：追加 3 个公开方法（不动现有 `testResultsFlow` / `saveResult` / `getSegments` / `deleteResult` 签名）。**返回 `TestResultSummary` 而非 `TestResult`**（spec 已锁定：TestRecordEntity 不含 segments / dataPoints，无法无损构造 TestResult；UI 渲染只需轻量 summary）
  - 在文件内或新建 `core/domain/.../model/TestModels.kt` 末尾追加：`data class TestResultSummary(val id: String, val testTemplateId: String, val carModel: String, val timestamp: Long, val totalTime: Double, val totalDistance: Double)`
  - 内部加 entity → summary 转换：`private fun TestRecordEntity.toSummary() = TestResultSummary(id, testTemplateId, carModel, timestamp, totalTime, totalDistance)`
  - `fun getBestResult(template: TestTemplate): Flow<TestResultSummary?>` —— 内部根据 template 调 dao.getBestAcceleration0To100() 或 dao.getBestBraking100To0()，`.map { it?.toSummary() }`
  - `fun getTotalRunCount(): Flow<Int> = dao.getTotalCount()`
  - `fun getRecentResultsFlow(limit: Int): Flow<List<TestResultSummary>> = dao.getRecentFlow(limit).map { list -> list.map { e -> e.toSummary() } }`
- [x] 1.4 修改 `core/data/src/main/.../repository/TelemetryRepository.kt`：追加 4 个公开方法（不动现有签名）
  - `fun getBestLapForTrack(trackId: String): Flow<TelemetrySession?>` —— `dao.getBestLapForTrack(trackId).map { it?.toDomain() }`
  - `fun getSessionCountForTrack(trackId: String): Flow<Int> = dao.getSessionCountForTrack(trackId)`
  - `fun getTotalLapCountForTrack(trackId: String): Flow<Int> = dao.getTotalLapCountForTrack(trackId)`
  - `fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySession>> = dao.getRecentSessionsForTrack(trackId, limit).map { it.map { e -> e.toDomain() } }`
- [x] 1.5 ~~新增 `core/data/src/test/.../repository/TestResultRepositoryAggregateQueryTest.kt`~~ —— **本 round 跳过、标 follow-up**：测真 SQL 聚合（COUNT / MIN / ORDER BY ASC LIMIT 1）需要 Robolectric + `androidx.room:room-testing` in-memory database 基础设施，本 round 没引入新 testImplementation 依赖；fake DAO 模式无法验证 SQL 业务逻辑。SQL 正确性由 Codex review + 真机 §7.4 端到端覆盖（跑真 0-100 → 看 BEST 0-100 数字反映该条结果）。归档前在 follow-up 笔记记录。原 4 cases：
  - 测试 1：空 results → getBestResult / getTotalRunCount / getRecentResultsFlow 首次 emit 为 null/0/emptyList
  - 测试 2：best result 按 template 隔离（acc 4.5/5.0、brake 36.8 → getBestResult(Acc) = 4.5, getBestResult(Brake) = 36.8m）
  - 测试 3：Flow 自动响应新 saveResult（订阅 → saveResult 更小 → emit 新值）
  - 测试 4：getRecentResultsFlow(5) 返回最近 5 条按 timestamp DESC
- [x] 1.6 **同步现有 `FakeTelemetrySessionDao` 实现**（避免新增 abstract 方法后 `:core:data:testDebugUnitTest` 编译失败）：
  - 现役位置：`core/data/src/test/java/com/blazepush/core/data/repository/TelemetryRepositoryTest.kt` + `TelemetryRepositoryEndSessionPersistTest.kt` 内的 `class FakeTelemetrySessionDao : TelemetrySessionDao { ... }` 内联类
  - 同步加 4 个 override 方法（返回轻量空 flow）：
    - `override fun getBestLapForTrack(trackId: String): Flow<TelemetrySessionEntity?> = flowOf(null)`
    - `override fun getSessionCountForTrack(trackId: String): Flow<Int> = flowOf(0)`
    - `override fun getTotalLapCountForTrack(trackId: String): Flow<Int> = flowOf(0)`
    - `override fun getRecentSessionsForTrack(trackId: String, limit: Int): Flow<List<TelemetrySessionEntity>> = flowOf(emptyList())`
  - **MUST 两个 fake 类都同步加**（避免漏改导致编译错）；如果未来改造为公共 fake 抽到 testFixtures，本 round 不做（属 refactor scope）
  - 同样检查 `core/data/src/test` 下是否还有其他文件 `: TelemetrySessionDao` 实现（`grep -rn ": TelemetrySessionDao" core/data/src/test`），如有遗漏一并加
- [x] 1.7 ~~新增 `core/data/src/test/.../repository/TelemetryRepositoryTrackQueryTest.kt`~~ —— **本 round 跳过、标 follow-up**：同 §1.5 理由（测真 SQL 需 Robolectric + Room test runner、不引入新依赖）。SQL 正确性由 Codex review + 真机 §7.5/§7.6 端到端覆盖（跑真 lap session → Records LAPS BEST LAP 反映 + 切赛道行为正确）。归档前在 follow-up 笔记记录。原 5 cases：
  - 测试 1：trackId 过滤（TFIC 2 + Boyu 1 → getSessionCountForTrack 各自 2/1）
  - 测试 2：bestLapMs 取最小（92457/90000/95000 → getBestLapForTrack 返回 90000 那条）
  - 测试 3：排除 in-progress（`endTs == startTs`）和 bestLapMs=null（首圈未完成）；getSessionCountForTrack 包含 bestLapMs=null 的闭环 session、但排除 in-progress 的
  - 测试 4：getTotalLapCountForTrack 累加（5+8+12 → 25）
  - 测试 5：Flow 自动响应新 endSession（订阅 → endSession 写更小 bestLapMs → emit 新值）

## 2. ViewModel 层（暴露 8 个 StateFlow）

- [x] 2.1 修改 `feature/test/src/main/.../viewmodel/TestSessionViewModel.kt`：
  - 顶层加 4 个性能测试相关 StateFlow（直接 `.stateIn`）：
    ```kotlin
    val bestAcceleration0To100: StateFlow<TestResultSummary?> = testResultRepository.getBestResult(TestTemplate.Acceleration0To100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val bestBraking100To0: StateFlow<TestResultSummary?> = testResultRepository.getBestResult(TestTemplate.Braking100To0)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val totalRunCount: StateFlow<Int> = testResultRepository.getTotalRunCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val recentRuns: StateFlow<List<TestResultSummary>> = testResultRepository.getRecentResultsFlow(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    ```
  - 顶层加 4 个圈速 session 相关 StateFlow（用 `_currentSelectedTrack.filterNotNull().flatMapLatest`）：
    ```kotlin
    val bestLapForCurrentTrack: StateFlow<TelemetrySession?> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getBestLapForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sessionCountForCurrentTrack: StateFlow<Int> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getSessionCountForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val totalLapCountForCurrentTrack: StateFlow<Int> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getTotalLapCountForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getRecentSessionsForTrack(track.id, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    ```
  - 加 import：`kotlinx.coroutines.flow.filterNotNull`、`kotlinx.coroutines.flow.flatMapLatest`、`com.blazepush.core.domain.model.TestResultSummary`、`com.blazepush.core.domain.model.TelemetrySession`、`com.blazepush.core.domain.model.TestTemplate`（如果未 import）
- [x] 2.2 **新增 ViewModel test helper 全量同步**（避免 ViewModel 顶层 `.stateIn` 触发 `mock(Repository).getXxx()` 默认返回 null 导致 NPE）—— 用 `grep -rn "TestSessionViewModel(" feature/test/src/test` 列出全部直接构造点，**全部**更新：
  - `TestSessionViewModelTrackSelectionTest.kt:createViewModel(...)` —— `testResultRepository = mock(...)` 之外补全 stub 8 个新方法（见下）；`telemetryRepository = mock(...)` 之外补全 stub 4 个新方法
  - `TestSessionViewModelTrackLoadingTest.kt:createViewModel(...)` —— 同上
  - `TestSessionViewModelTrackLapTest.kt:createViewModel(...)` —— 同上
  - 任何遗漏的直接构造点 —— 编译错暴露后逐个修
- [x] 2.3 在 `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/RepositoryFlowStubs.kt` 新建测试支持文件（**MUST 放 feature/test 模块的 src/test，不能放 core/data 的 src/test** —— 跨模块 test source set 不可见、feature/test 测试无法消费）：
  - `fun mockTestResultRepositoryWithEmptyFlows(): TestResultRepository` —— 返回 mockito mock + stub 三个新 Flow 方法返回 `flowOf(null)` / `flowOf(0)` / `flowOf(emptyList())`
  - `fun mockTelemetryRepositoryWithEmptyFlows(): TelemetryRepository` —— 返回 mockito mock + stub 四个新 Flow 方法返回 `flowOf(null)` / `flowOf(0)` / `flowOf(0)` / `flowOf(emptyList())`
  - 也要保留对 telemetryRepository 现有 mock 行为兼容（startSession / endSession / writeSample / writeCrossing / flush 走 mockito 默认 stub 即可）
  - 所有 ViewModel test helper（§2.2 列出的 3 个文件）`createViewModel(...)` 的 `testResultRepository = mockTestResultRepositoryWithEmptyFlows()` + `telemetryRepository = mockTelemetryRepositoryWithEmptyFlows()` —— 让默认参数走"空数据"路径，单测专心验证 ViewModel 逻辑而不是 Repository
- [x] 2.4 ~~扩展 `TestSessionViewModelTrackSelectionTest.kt`~~ —— **本 round 跳过、标 follow-up**：与 §1.5/§1.7 同理（covers ViewModel flatMapLatest + Mockito stub 行为），价值边际；真行为正确性由真机 §7.5/§7.6 端到端覆盖（跑真 lap session → Records LAPS BEST LAP 反映 + 切赛道行为正确）。归档前在 follow-up 笔记记录。原 2 cases：
  - 新增测试 10：`currentSelectedTrack` 切换 → `bestLapForCurrentTrack` / `sessionCountForCurrentTrack` 切到新 trackId 的 query 结果。**MUST 用工程现役 Mockito 风格**（`doReturn(...).` `` `when` `` `(mock).method(...)`，与 `TestSessionViewModelTrackSelectionTest.kt:163` / `FileLoggerTest.kt:66` 一致；**禁止**用 `whenever(...)` —— 工程无 mockito-kotlin 依赖、unresolved reference）：
    ```kotlin
    doReturn(flowOf(sessionForA)).`when`(repo).getBestLapForTrack("trackA-id")
    doReturn(flowOf(sessionForB)).`when`(repo).getBestLapForTrack("trackB-id")
    ```
    断言 selectTrack 切换后 StateFlow 切到对应 session
  - 新增测试 11：性能测试 4 个 StateFlow 初始值正确（null/null/0/emptyList，验证 stub helper 行为）

## 3. 工具函数 MetricFormatter

- [x] 3.1 新建 `feature/test/src/main/.../ui/tracktech/format/MetricFormatter.kt`：
  - `fun formatLapMs(ms: Long): String` —— 按 `M:SS.mmm` 格式输出（如 92457 → "1:32.457"，0 → "0:00.000"）
  - `fun formatDate(epochMs: Long, locale: Locale = Locale.ENGLISH): String` —— `SimpleDateFormat("MMM d, yyyy", locale).format(Date(epochMs))`
  - `fun formatRunTimestamp(epochMs: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.ENGLISH): String` —— 按距今时长分级（today/yesterday/within 7 days weekday/older absolute date）
  - 全部纯函数、无副作用
- [x] 3.2 新建 `feature/test/src/test/.../ui/tracktech/format/MetricFormatterTest.kt`（普通 JUnit）：
  - formatLapMs 测试：92457 → "1:32.457"，0 → "0:00.000"，60000 → "1:00.000"，3600000 → "60:00.000"
  - formatDate 测试：固定 epochMs（如 2024-05-18 10:35 UTC 对应 ms）→ "May 18, 2024"
  - formatRunTimestamp 测试：now - 1 hour → "Today, HH:mm"；now - 1 day → "Yesterday, HH:mm"；now - 8 days → 绝对日期格式

## 4. UI 接入（LapsHomeScreen + RecordsHomeScreen）

- [x] 4.1 修改 `LapsHomeScreen.kt` RECENT BEST 区块（L160-183）：
  - 主 Composable 顶部加 `val bestLapForCurrent by testSessionViewModel.bestLapForCurrentTrack.collectAsState()`
  - `MetricTile.value` 改 `bestLapForCurrent?.bestLapMs?.let { formatLapMs(it) } ?: "--"`
  - `MetricTile.status` 改 `bestLapForCurrent?.let { "Personal Best · ${formatDate(it.startTs)}" } ?: "暂无成绩"`
  - import `MetricFormatter` 函数
- [x] 4.2 修改 `RecordsHomeScreen.kt` PERFORMANCE 区块（L135-201）：
  - `PerformanceView` 函数签名加 `testSessionViewModel: TestSessionViewModel = koinViewModel()` 默认参数（与 LapsView 同 pattern）
  - 内部加：
    ```kotlin
    val bestAcc by testSessionViewModel.bestAcceleration0To100.collectAsState()
    val bestBrake by testSessionViewModel.bestBraking100To0.collectAsState()
    val totalRuns by testSessionViewModel.totalRunCount.collectAsState()
    val recent by testSessionViewModel.recentRuns.collectAsState()
    ```
  - BEST 0-100 / BEST BRAKE / TOTAL RUNS 三个 MetricTile.value 改成 stateflow 派生（含 "--" / "0" fallback）
  - RECENT RUNS 区块的 `placeholderRecentRuns.forEach { run -> ... }` 改 `recent.forEach { result -> ... }`，row content 派生函数加新版（输入 `TestResultSummary` 而非 `RecentRun`）
  - 派生函数 `fun recentRunRowContent(result: TestResultSummary, isPB: Boolean): Triple<ImageVector, String, String>`：根据 `testTemplateId`（`"acc_0_100"` / `"brake_100_0"`）决定 type 字符串和 leading icon、value 字符串（acc 用 totalTime / brake 用 totalDistance）、time 字符串（`formatRunTimestamp(result.timestamp)`）；isPB 通过和 bestAcc?.id / bestBrake?.id 比对判定
- [x] 4.3 修改 `RecordsHomeScreen.kt` LAPS 区块（L383+ `LapsView`）：
  - 函数顶部新增 4 个 collectAsState：bestLap / sessionCount / totalLapCount / recentSessions
  - `record = remember(currentTrack, bestLap, sessionCount, totalLapCount) { CurrentTrackRecord(...) }` 派生改：
    - `bestLapTime = bestLap?.bestLapMs?.let { formatLapMs(it) } ?: "--"`
    - `bestLapDate = bestLap?.startTs?.let { formatDate(it) } ?: "暂无"`
    - `sessions = sessionCount`
    - `totalLaps = totalLapCount`
    - `direction` mock "Clockwise" 保留
  - SESSION HISTORY 区块的 `placeholderLapSessions.forEach { session -> ... }` 改 `recentSessions.forEach { session -> ... }`，row title 改 `"${formatDate(session.startTs)} · ${session.lapCount} Laps · Best ${session.bestLapMs?.let { formatLapMs(it) } ?: "--"}"`（**禁止 `?: 0` fallback** —— 见 spec 同处说明：null bestLapMs 应显示 `"--"`，不能假显示 `"0:00.000"`）
- [x] 4.4 删除 `RecordsHomeScreen.kt` 末尾的 `private val placeholderRecentRuns: List<RecentRun>` + `private val placeholderLapSessions: List<LapSessionRow>` + `private data class LapSessionRow(...)` 三个 mock 顶层定义（注：`placeholderLapSessions` / `LapSessionRow` 在本 round 开始前已由先前 round 移除，仅 `placeholderRecentRuns` + `RecentRun` 实际删除）
- [x] 4.5 检查并删除 RecordsHomeScreen 内已不用的 import（清理掉 `TelemetryRepository` / `koinInject` / `LaunchedEffect` / `SimpleDateFormat` / `Date` / `Locale` 6 个 LapsView 内部直读 repository 的 import；新增 `TestResultSummary` + 3 个 MetricFormatter 函数 import）

- [ ] 4.6 **RecentRuns onClick 接 V2 PerformanceResultScreen**（路径 A scope 转移自 `redesign-performance-result-screen` round，2026-05-01 立项）：

  现状（§4.2 完成后但本任务前）：`RecordsHomeScreen.kt` PERFORMANCE 子页 RecentRuns 行 onClick 仍是 `Toast.makeText(context, "Run detail placeholder", ...)`，没有跳详情页。redesign round 已经做完 V2 详情页 + 在 `TrackTechAppShell.kt` 注册 `composable("performance_result/{testId}", ...)` route，等本 task 把 onClick 接上即可入端。

  实施：

  - 编辑 `RecordsHomeScreen.kt` PERFORMANCE 子页 RecentRuns 渲染处（§4.2 改造的 `recent.forEach { result -> ... TrackTechRow(... onClick = { Toast(...) }) }` 块）
  - `onClick` lambda 改为 `navController.navigate("performance_result/${result.id}")`，删除 Toast 调用与对应 `Toast` import（如该 import 不再被其它代码使用）
  - 该 `PerformanceView` Composable 需增加 `navController: NavController` 参数（与 LapsView 一致）；调用方 `RecordsHomeScreen` 顶层向下传 navController
  - 不动 LAPS 子页（已用 `lap_session_detail/{sessionId}` 跳转）

  前置依赖（MUST 验证后再实施）：

  - 主区 / worktree 内 `grep -n '"performance_result/{testId}"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt` 应至少返回 1 行
  - 若 grep 返回空 → STOP，等 redesign round commit + ff-only 合回主区 → rebase 本 worktree → 重跑 grep；不能在 NavHost 没注册 route 的状态下加 navigate（运行时 NavHost 抛 IllegalArgumentException）

  验证（commit 前必跑）：

  - `./gradlew :feature:test:assembleDebug` 通过
  - `./gradlew :feature:test:testDebugUnitTest` 通过（无新增测试，但确认现有不回归）
  - `grep -n 'Toast.makeText(context, "Run detail placeholder"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` 应返回空（确认 Toast 占位被删干净）

## 5. 验证（local）

- [x] 5.1 跑 `./gradlew :core:data:testDebugUnitTest` 全部 PASS（§1.5 + §1.6 新增 SQL/Flow 测试因需 Robolectric 基础设施延后，由 §7 真机端到端覆盖）
- [x] 5.2 跑 `./gradlew :feature:test:testDebugUnitTest` 全部 PASS（含 §3.2 MetricFormatterTest 6 用例 + §2.4 ViewModel 派生 stateflow 集成依赖 Robolectric 同样延后，仅 stub helper 与 §3 单测覆盖）
- [x] 5.3 跑 `./gradlew :app:assembleDebug` 编译通过
- [x] 5.4 全局 grep 边界清零：
  - `grep -rn '"Personal Best · placeholder"' feature/test/src/main` 无结果 ✓
  - `grep -rn '"4.21"\|"36.8"\|"24"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` 无 mock 数据残留 ✓（`SpeedCurveStub` 内 `"4.21 s"` / `"100 km/h"` 文本为视觉常量，依 task 注脚不在范围）
  - `grep -rn 'placeholderRecentRuns\|placeholderLapSessions\|LapSessionRow' feature/test/src/main` 无残留 ✓（仅 `formatLapSessionRowTitle` 函数名子串误命中，非 data class）
  - `grep -n '"1:32.457"' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapsHomeScreen.kt` 无残留 ✓

## 6. 合回主干（按看板 §3 checklist）

> 顺序不可乱。每步完成后才能开下一步。MUST 取得 user 授权 commit + push；MUST NOT --amend / --no-verify。

- [ ] 6.1 worktree 内独立 commit：建议拆 4 commit（4.6 单独成一 commit）
  - commit 1: `feat(data): TestResultRepository + TelemetryRepository 加聚合查询 Flow + DAO @Query`（§1 全部）
  - commit 2: `feat(viewmodel): TestSessionViewModel 暴露 8 个统计 StateFlow + flatMapLatest 跟随 currentTrack`（§2 全部 + §3 MetricFormatter）
  - commit 3: `feat(ui): RecordsHomeScreen + LapsHomeScreen 接真实 Repository 数据 · 移除 mock placeholder`（§4.1-4.5）
  - commit 4: `feat(ui): RecordsHomeScreen RecentRuns onClick → V2 PerformanceResultScreen（path-A scope 转移自 redesign-performance-result-screen round）`（§4.6）
  - 取得 user 授权
- [ ] 6.2 worktree 内 `git fetch origin && git rebase feature/track-tech-v2`
- [ ] 6.3 rebase 后再次跑编译 + 单测
- [ ] 6.4 切回主区 ff-only merge
- [ ] 6.5 主区编译确认合回态通过
- [ ] 6.6 `git diff --stat HEAD~3..HEAD -- feature/ core/` 验证 diff 边界（feature/test + core/data）
- [ ] 6.7 更新看板 §5 round F 状态字段
- [ ] 6.8 更新看板 §6 共享文件占用 `TelemetryRepository.kt` + `TestSessionViewModel.kt` 状态：ongoing → done

## 7. 真机 manual gate（按看板 §4.2 串行规则）

- [ ] 7.1 告知 user "round F 准备装机验证（华为 8KE0219522008434）"，等 user 授权
- [ ] 7.2 `ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug`
- [ ] 7.3 真机首次启动场景（cold start，无历史数据）：
  - Laps tab RECENT BEST MUST 显示 `"--"` + `"暂无成绩"`
  - Records tab PERFORMANCE：BEST 0-100 / BEST BRAKE / TOTAL RUNS MUST 显示 `"--"` / `"--"` / `"0"`，RECENT RUNS 区块 MUST 不渲染任何 row
  - Records tab LAPS：BEST LAP / bestLapDate / SESSIONS / TOTAL LAPS MUST 显示 `"--"` / `"暂无"` / `"0"` / `"0"`，SESSION HISTORY MUST 不渲染任何 row
- [ ] 7.4 跑一次真实 0-100 加速测试 → 完成后回到 Records tab：
  - BEST 0-100 MUST 显示真实 totalTime（"%.2f s" 格式）
  - TOTAL RUNS MUST = `1`
  - RECENT RUNS 列表 MUST 显示该条 result（type "0-100 km/h" + value 真实 + time "Today, HH:mm" + isPB true 显示金牌图标）
  - **§4.6 入端验证**：点击 RECENT RUNS 列表第一行 MUST 跳转到 V2 `PerformanceResultScreen`：
    - 顶部 ← back + `"PERFORMANCE"` 标题
    - Hero `TEST TYPE` cyan label + `"0-100 km/h"` 大标题 + 紫色 Score Hero 主成绩数字 + `"s"` unit + Date / Device 副信息
    - Metric Row 第 1 格 `DISTANCE`（cyan，totalDistance + m）/ 第 2 格 `PEAK G`（红）/ 第 3 格 `AVG G`（muted）
    - SPEED CURVE / G-FORCE 卡（cut-corner，**无** V1 双层 Card）
    - SPEED SEGMENTS 列表
    - ← back 按钮 popBackStack 回 Records tab，无闪屏
  - 跑一次真实 100-0 制动测试，重复以上路径，验证 V2 详情页 hero 主成绩字段切换：制动测试 hero 主成绩 MUST 是距离（米），第 1 个 metric tile MUST 是 `TIME`（不是 DISTANCE）
- [ ] 7.5 跑一次真实 lap session（TFIC，至少 1 圈）→ 完成后回到 Laps tab + Records tab：
  - Laps tab RECENT BEST MUST 显示该 session bestLap（formatLapMs 格式）+ status `"Personal Best · 今天日期"`
  - Records tab LAPS BEST LAP MUST 同步；SESSIONS MUST = `1`；TOTAL LAPS MUST = 该 session lapCount
  - SESSION HISTORY MUST 显示该 session 一条 row
- [ ] 7.6 切赛道（TFIC ↔ Boyu）：
  - Records tab LAPS 全部数据 MUST 切到新 trackId 的 query 结果（Boyu 无历史 → 显示 "--" / "暂无" / "0" / "0" / 空 SESSION HISTORY）
  - Laps tab RECENT BEST MUST 同步切
- [ ] 7.7 跨进程持久化：杀进程重启 → Repository Flow 自动恢复 → UI 数据不变
- [ ] 7.8 V2 §4 小屏 vivo V2405A 重复 7.3-7.7 视觉检查（数字单行不换行 / metric tile 布局正常）

## 8. Codex review（user 触发）

- [ ] 8.1 全部本地与真机验证通过后，提醒 user 触发 Codex review

## 9. 归档

- [ ] 9.1 Codex review 通过 + 真机 7.3-7.8 全 PASS 后，调 `/opsx:archive wire-real-data-to-records-and-laps-tabs`
- [ ] 9.2 清理 worktree：`git worktree remove .worktrees/wire-real-data-to-records-and-laps-tabs`（取得 user 授权）
- [ ] 9.3 更新看板 §5 round F 状态：done；§6 共享文件占用全部 done

## 10. Follow-up backlog（延期 / 协同立项）

- [ ] 10.1 round `persist-test-result-data-points`（由 parallel session `redesign-performance-result-screen` 顺手做 Phase 1）+ `wire-records-performance-real-curve`（Phase 2 接入 Records SpeedCurve）
  - 触发原因：round F §7.4 真机验证发现 `RecordsHomeScreen.SpeedCurveStub` 仍是硬编码假曲线（`speed = 150f * (1f - exp(-1.4 * t * 5))` + hardcoded `4.21 s` / `100 km/h`），用户视觉上以为是真实图表
  - 根因：`TestSession.dataPoints` 仅运行时驻留内存，`TestRecordEntity` / `SpeedSegmentEntity` 都不持久化原始 GPS 采样序列；本 round F 的 spec 边界仅"接已存在数据"，无法在 scope 内修复
  - **本 round F 不做降级**（user 2026-05-01 拍板）：完整方案 + memo 转交 perf-result session 统一推进；round F 闭环时保留现有 `SpeedCurveStub` 不动，避免临时方案再加一道删除 commit 拖慢节奏
  - 完整 9 章设计 memo：[docs/design/speed-curve-real-data-persistence-deferred.md](../../../docs/design/speed-curve-real-data-persistence-deferred.md)
  - **跨 session 数据契约（MUST 共识）**：perf-result session 暴露 `TestResultRepository.getDataPointsForResult(id: String): Flow<List<GpsDataPoint>>` —— Records 这边复用同一接口，禁止另立别名
