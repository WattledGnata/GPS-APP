## Why

`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` 是加速/制动测试单次跑成绩的详情页，目前仍是 Track Tech V1 视觉（Material3 大圆角 Card + 默认主题色 + `Text(...) fontSize` 直写）。同一栋"楼"里的圈速 session 详情已经做完 V2 重做（`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt`：CutCornerPanel + MetricTile + TrackTechColors），形成视觉断层：用户从 Records tab 的 PERFORMANCE 列表（V2 已在 `split-records-tab-performance-and-laps` round 重做）点进单次成绩，会跌回 V1 风格的 Material 卡片。

本 round 把性能测试详情页对齐到 Track Tech V2 视觉语法，对齐 LapSessionDetailScreen 在 laps 侧的位置，让 Records → Performance 子链路视觉连贯。

## What Changes

- 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt` —— V2 风格性能测试详情页：
  - `DetailHeader`：cut-corner ← back 按钮 + `PERFORMANCE` 标题（同 LapSessionDetailScreen 的 header）
  - **Hero CutCornerPanel**：上半 `TEST TYPE` label（cyan）+ `0-100 km/h` / `100-0 km/h` 类型标题（RacingTitleMedium）；下半 `MetricNumber` Score Hero 主成绩数字（`valueColor = TrackTechColors.Purple`）+ unit；底部 `Date` / `Device` 两条 OverviewRow 副信息
  - **Hero 主成绩按 TestTemplate 分支**：`Acceleration0To100` → `String.format("%.2f", record.totalTime)` + unit `"s"`；`Braking100To0` → `String.format("%.1f", record.totalDistance)` + unit `"m"`（与 `TestHistoryScreen` line 164-166 现有显示保持一致：制动测试核心成绩是刹停距离）
  - **Metric Row**：3 个 MetricTile 等分（weight=1f），第 1 格按 template 分支避免与 hero 重复 —— 加速测试显 `DISTANCE` (totalDistance, m, Cyan, Score Medium)；制动测试显 `TIME` (totalTime, s, Cyan, Score Medium)。第 2 格固定 `PEAK G` (maxAcceleration, G, Red, Score Medium)；第 3 格固定 `AVG G` (avgAcceleration, G, TextSecondary, Score Small)
  - **SPEED CURVE 卡**：外层 `CutCornerPanel`（cut-corner 容器），内部调 `SpeedChart(..., wrapInCard = false)`，避免 V1 Card 嵌套 V2 cut-corner 的双层卡
  - **G-FORCE 卡**：外层 `CutCornerPanel`，内部调 `GForceChart(..., wrapInCard = false)`
  - **SPEED SEGMENTS 区段**：cyan section header + 每段一条 cut-corner row（仿 LapSessionDetail 的 LapRecordRow，但无 status chip：仅区间 label / 时间 / 距离）
- 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt` 与 `GForceChart.kt`：新增 `wrapInCard: Boolean = true` 参数（默认 true 保持向下兼容，所有现有调用方零改动）；当 `wrapInCard = false` 时跳过 V1 Material `Card` 容器，直接渲染内容（标题 Row + Canvas + 时间轴）。chart 内部 stroke / grid / axis 颜色不动（颜色对齐留下个 round）
- **V2 NavHost 注册新 route**：修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt`，在 `NavHost` 内新增 `composable("performance_result/{testId}", arguments = listOf(navArgument("testId") { type = NavType.StringType }))` 分支，wire 到 `PerformanceResultScreen(testId, onBack = { navController.popBackStack() })`。模式与现有 `lap_session_detail/{sessionId}` route 一致（TrackTechAppShell.kt:167-177）
- **入口 wire-up 由 F round（`wire-real-data-to-records-and-laps-tabs`）接管**：`RecordsHomeScreen.kt` PERFORMANCE 子页 RecentRuns 接真实 `TestRecordEntity` 数据 + onClick 跳转 `performance_result/${result.id}`，**不在本 redesign round 内做**；该工作天然属于 F round 的 scope（F 正在重写整个 RecordsHomeScreen PERFORMANCE/LAPS 接真实数据，删 placeholderRecentRuns / placeholderLapSessions / LapSessionRow）。本 round 仅在 V2 NavHost 注册好 `performance_result/{testId}` route，等 F round 接 RecentRuns 真实数据时只需补一行 `navController.navigate("performance_result/${result.id}")` 即可（已在 F round tasks.md 与 proposal Impact 加 follow-up task）
- **V1 `TestFlowNavigation.kt` 不在本 round 的验收范围**：MainActivity line 58 直接 `setContent { TrackTechAppShell() }`，全 app grep 确认 V1 `TestFlowNavigation` 已无任何调用方（dead code）。本 round MUST NOT 改它（改了不影响真实运行路径，反而误导 review）；V1 屏 cleanup（含 `TestFlowNavigation.kt` / `TestResultScreen.kt` / `TestHistoryScreen.kt`）作为独立 cleanup round 处理
- **去掉车型概念**：新页面不再展示 `TestRecordEntity.carModel`；`TestRecordEntity` schema 本身不动（避免 Room 迁移），仅 UI 层不取用
- **保留旧 `TestResultScreen.kt`**：本 round 不删，等 cleanup round 处理（V1 屏整组一起删）
- **新增轻量 contract test**：`PerformanceResultScreenContractTest.kt`，grep 锁死视觉关键字面量（`PERFORMANCE` / `TEST TYPE` / `DISTANCE` / `TIME` / `PEAK G` / `AVG G` / `SPEED CURVE` / `G-FORCE` / `SPEED SEGMENTS` / `0-100 km/h` / `100-0 km/h`）

不做的事（明确 out-of-scope）：

- **不**改 `TestRecordEntity` schema 或 Room migration（仅 UI 不取用 carModel）
- **不**改 `SpeedChart` / `GForceChart` 内部 stroke / grid / axis 颜色（颜色对齐留下个 round）—— 仅新增 `wrapInCard` 参数让 V2 详情页可以无 Card 嵌入
- **不**改 `PerformanceTestTelemetryReader` 二进制读取链路
- **不**改 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 数据派生函数（连同测试用法整体搬到新文件，语义不变）
- **不**改 BLE / GPS / RaceChrono 协议
- **不**改 `RecordsHomeScreen.kt`（RecentRuns 接真实数据 + onClick 跳转完全交给 F round `wire-real-data-to-records-and-laps-tabs`，避免与 F 的"PERFORMANCE 子页全量接真实数据 + 删 placeholder"工作 100% 重叠造成行为级覆盖）
- **不**做 V2 Test tab 跑完测试自动跳详情（`TrackTechTestExecutionScreen` 当前 `popBackStack` 行为保持不变；自动跳 vs Snackbar `View Result` action vs 完成页按钮是 UX 决策点，留给独立 round 拍板，不塞进本视觉重做 round）
- **不**做 V2 完整 Test History 列表屏（V2 当前没有这个屏；用户进入历史 record 全部走 `RecordsHomeScreen` PERFORMANCE 子页 RecentRuns 入口 —— 该入口由 F round 接入；完整列表是后续独立 round 的事）
- **不**改 V1 `TestFlowNavigation.kt`（dead code，改它无效）

## Capabilities

### New Capabilities

- `performance-result-screen-v2`: 加速/制动测试单次成绩详情页 V2 视觉契约 —— Hero 成绩数字 + 3 metric tile（DISTANCE/PEAK G/AVG G）+ SPEED CURVE/G-FORCE 卡 + SPEED SEGMENTS 列表的字面量与组件结构

### Modified Capabilities

无。`split-records-tab-performance-and-laps` round 引入的 `records-home-segmented-views` 只覆盖 Records home 的 PERFORMANCE/LAPS 子页本身，不涉及"点进单次成绩"详情页；本 round 是新增独立 capability，不修改既有 spec requirement。

## Impact

### 受影响代码

- **新建**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt`（独占）
  - `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreenContractTest.kt`（独占）
- **修改（独占）**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt`（NavHost 注册 `performance_result/{testId}` route，无并行 round 在动）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt`（新增 `wrapInCard: Boolean = true` 参数，默认行为不变）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/components/GForceChart.kt`（新增 `wrapInCard: Boolean = true` 参数，默认行为不变）
- **不修改（scope 转移给 F round）**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` —— RecentRuns 接真实数据 + onClick 跳 `performance_result/${result.id}` 由 F round（`wire-real-data-to-records-and-laps-tabs`）接管。本 redesign round MUST NOT 在该文件 commit 任何 diff
- **F round 端补任务（已记录到 F round 工件）**：F round 在接 `recentRuns: StateFlow<List<TestResultSummary>>` 到 PERFORMANCE 子页 UI 时，RecentRuns row `onClick` MUST 调 `navController.navigate("performance_result/${result.id}")`；F round 真机 gate 增加"点击真实 RecentRuns row 能进入 V2 PerformanceResultScreen"
- **保留不删（V1 dead code，cleanup round 处理）**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt`
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`（已无任何调用方，但 cleanup 一并删）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestHistoryScreen.kt`

### 不受影响

- `core/*`、`simulator/*` 全部模块
- `app/*`、其它 home screen（`TestHomeScreen` / `LapsHomeScreen` / `RecordsHomeScreen` / `DeviceHomeScreen`）
- `LapSessionDetailScreen` / `LapLiveScreen` / `TrackTechAppShell` / `TrackTechBottomNav`
- `TestRecordEntity` schema、`AppDatabase`、`TestRecordDao` migration
- `SpeedChart` / `GForceChart` 内部 Canvas / stroke / grid / axis 颜色（仅在文件外层加 `wrapInCard` 开关，原 Card 路径默认仍然激活）
- 现有 `SpeedChart` / `GForceChart` 调用方（无需任何改动，新参数有默认值）
- BLE / GPS 数据链路、RaceChrono BLE 协议（不动）

### 协议兼容性

无协议改动。本 round 是 UI 层视觉重做，不触及 RaceChrono BLE protocol、binary telemetry format、Room schema、JSON replay schema。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

按 `CLAUDE.md "多 change 并行协同（本地专属约定）"` 走：

- 新开 git worktree：`.worktrees/redesign-performance-result-screen/`
- 看板登记 `docs/implementation-design/parallel-change-collab.md` §5 + §6 —— 主要独占 `PerformanceResultScreen.kt` 新文件；唯一共享文件是 `TestFlowNavigation.kt`，登记前 MUST 在看板 §6 检查无并行 round 在改
- 编译里程碑达成后 ff-only 合回 `feature/track-tech-v2`

### 依赖

- 已有 V2 组件（`CutCornerPanel` / `MetricTile` / `TrackTechColors` / `TrackTechTypography` / `MetricKind` / `MetricSize`）—— 已在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 下，无需新增
- Material Icons 默认包（`Icons.Filled.ArrowBack`）—— 已用
- 无需新增 gradle 依赖

### 测试影响

- 不新增 UI 单元测试（详情页是 placeholder + Compose，外部测试不可见，参考 RecordsHomeScreen 策略）
- 新增 `PerformanceResultScreenContractTest` —— grep 字面量保护（不依赖 Robolectric / Android Context，纯静态字符串扫描）
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归
- 真机验证：华为 `8KE0219522008434` + 小屏机型（V2 视觉 round MUST 走小屏，按 CLAUDE.md "UI 视觉约束 §4 真机验证 gate"）
