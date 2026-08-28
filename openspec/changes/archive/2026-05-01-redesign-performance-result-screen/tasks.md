## 1. 协同看板登记与 worktree 准备

- [x] 1.1 **scope 调整记录（路径 A，2026-05-01 apply 阶段）**：

  apply 启动时 task 1.x 看板核查发现 **`F. wire-real-data-to-records-and-laps-tabs` round 已在另一 worktree 推进**：

  - F worktree 路径：`.worktrees/wire-real-data-to-records-and-laps-tabs/`
  - F 已有 11 个文件未 commit 改动（DAO / Repository / TestModels / TestSessionViewModel + 测试）
  - F 暂未改 `RecordsHomeScreen.kt`，但看板 §5 line 131 明示 F round scope 包含"PERFORMANCE / LAPS 全部 mock 接真实，删 placeholderRecentRuns / placeholderLapSessions / LapSessionRow"
  - 这与本 redesign round 第二版 scope 中的"接 RecentRuns 真实数据 + onClick 跳转"100% 重叠

  **用户拍板路径 A**：scope 转移给 F round。redesign round 只保留：

  1. 新建 `PerformanceResultScreen.kt`
  2. V2 NavHost 注册 `performance_result/{testId}` route（独占 `TrackTechAppShell.kt`）
  3. SpeedChart / GForceChart 加 `wrapInCard` 开关
  4. Contract test（仅锁详情页字面量 + NavHost route 字面量）
  5. V1 `TestFlowNavigation` 不动

  **redesign round 不再改 `RecordsHomeScreen.kt`**；与 `split-records-tab-performance-and-laps` round 的串行依赖 gate **不再适用**（路径 A 后无文件交叉），原 task 1.1 的"路径 b 留痕"也不再适用。

  **task 1.1 当前验收**：

  - 在看板 `docs/implementation-design/parallel-change-collab.md` §5 追加备注：`"2026-05-01 apply：redesign-performance-result-screen 经 task 1.x 核查发现与 F round scope 重叠，走路径 A：scope 转移 — RecordsHomeScreen wire-up 由 F round 接管，redesign 只做详情页 + NavHost route + chart wrapInCard"`
  - 看板 §5 在 F round 行追加"依赖 redesign-performance-result-screen 合回（route 注册）"
  - F round 的 tasks.md / proposal Impact 加 follow-up：接 RecentRuns 真实数据时补 `navController.navigate("performance_result/${result.id}")`；F round 真机 gate 加"点击 RecentRuns 进入 V2 PerformanceResultScreen"
- [x] 1.2 阅读看板 §5（round 登记表）+ §6（共享文件占用），核对：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt` 是否已被其它并行 round 登记占用（NavHost 改动需要）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt` 是否被占用（新增 `wrapInCard` 参数）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/components/GForceChart.kt` 同上
  - `RecordsHomeScreen.kt` **不**进 redesign 占用清单（路径 A 后由 F round 接管）
- [x] 1.3 在看板 §5 登记本 round（名称 / 起止 / 主分支 `feature/track-tech-v2` / worktree 路径），同时按 task 1.1 在看板加 scope 调整备注 + F round 行加依赖
- [x] 1.4 在看板 §6 登记本 round 占用：
  - `TrackTechAppShell.kt`（NavHost 注册 `performance_result/{testId}` route，独占；与 F round 在主区无任何 NavHost 改动）
  - `SpeedChart.kt`（新增 `wrapInCard: Boolean = true` 参数）
  - `GForceChart.kt`（新增 `wrapInCard: Boolean = true` 参数）
  - **`RecordsHomeScreen.kt` 不在本 redesign round 的占用清单**
- [x] 1.4a F round 工件留账（路径 A 责任转移闭环，MUST 在 worktree 创建前完成，避免 ownership 漂移）：
  - 在 `openspec/changes/wire-real-data-to-records-and-laps-tabs/proposal.md` Impact 或 What Changes 节追加 follow-up：接 `recentRuns: StateFlow<List<TestResultSummary>>` 到 PERFORMANCE 子页 UI 时，RecentRuns row `onClick` MUST 调 `navController.navigate("performance_result/${result.id}")`；同条加进 `tasks.md` 对应 RecordsHomeScreen 改动 task 内
  - F round 真机 gate（其 tasks.md 真机验证节）加一行：点击真实 RecentRuns row MUST 进入 V2 `PerformanceResultScreen`（hero / metric tile / 曲线 / segments 视觉正确）
  - F round 工件改动 commit message 风格：`docs(openspec): 为 wire-real-data round 加 RecentRuns onClick → performance_result/{testId} navigate（redesign-performance-result-screen 路径 A scope 转移）`
- [x] 1.5 创建 worktree：`git worktree add .worktrees/redesign-performance-result-screen feature/track-tech-v2`，`cd` 进去；后续 task 全部在 worktree 内执行

## 2. 基础脚手架（新文件创建）

- [x] 2.1 在 worktree 内创建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt`，package 设为 `com.blazepush.feature.test.ui.tracktech`，加 `// @IgnoreFormatCheck` 标记（参考 `LapSessionDetailScreen.kt` 的做法）
- [x] 2.2 顶级 Composable 函数签名：`fun PerformanceResultScreen(testId: String, onBack: () -> Unit, testHistoryViewModel: TestHistoryViewModel = koinViewModel())`，与现 `TestResultScreen` 兼容
- [x] 2.3 复用 `TestResultScreen` 的数据加载逻辑：`testRecords.find { it.id == testId }` + `PerformanceTestTelemetryReader.read(record.dataFilePath)` → `dataPoints`
- [x] 2.4 加 record 为空时的 fallback（CircularProgressIndicator 居中），与 V1 保持一致

## 3. DetailHeader 实现

- [x] 3.1 实现 private `@Composable PerformanceDetailHeader(onBack: () -> Unit)`，cut-corner 包裹的 ← back 按钮 + `"PERFORMANCE"` 标题；视觉对齐 `LapSessionDetailScreen.DetailHeader`
- [x] 3.2 标题 `Text` MUST `maxLines = 1, overflow = TextOverflow.Ellipsis`
- [x] 3.3 back 按钮使用 `CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll)` + `Icons.Filled.ArrowBack` + `TrackTechColors.TextPrimary`

## 4. Hero 区块实现

- [x] 4.1 实现 private `@Composable HeroSection(record: TestRecordEntity)`，最外层 `CutCornerPanel`（`cutSize = 8.dp, cutCorners = cutCornersAll, contentPadding = 16.dp`）
- [x] 4.2 上半：`"TEST TYPE"` UiTextLabel cyan + 测试类型大标题（`RacingTitleMedium`，`TextPrimary`）。类型字面量从 `TestTemplate.fromId(record.testTemplateId)` 派生：`Acceleration0To100` → `"0-100 km/h"`，`Braking100To0` → `"100-0 km/h"`，未知 → `"—"`
- [x] 4.3 派生 hero 主成绩字段（按 template 分支）：
  - `Acceleration0To100` → `value = String.format("%.2f", record.totalTime)`、`unit = "s"`
  - `Braking100To0` → `value = String.format("%.1f", record.totalDistance)`、`unit = "m"`
  - 未知 / null → `value = record.result`、`unit = null`（兜底）
  - 与 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestHistoryScreen.kt` line 164-166 现行业务约定保持一致
- [x] 4.4 主成绩数字行：`MetricNumber(value = <派生 value>, unit = <派生 unit>, kind = MetricKind.Score, size = MetricSize.Hero, valueColor = TrackTechColors.Purple)`。**注意**：`MetricNumber` 的强调色参数名是 `valueColor`，**不是** `accentColor`（`accentColor` 仅 `MetricTile` 有）。unit 由 `MetricNumber` 内部自动渲染（小灰），不要在外面再写一个独立的 unit `Text`
- [x] 4.5 副信息：两条 `OverviewRow`（"Date" → `dateFormat.format(Date(record.timestamp))`；"Device" → `record.deviceName`），复用 `LapSessionDetailScreen.OverviewRow` 的私有实现风格（label-value，weight 约束）
- [x] 4.6 验证：Hero 区块 MUST NOT 取用 `record.carModel`

## 5. Metric Row 实现

- [x] 5.1 紧随 Hero 实现 horizontal `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))`，3 个 MetricTile 等分（`Modifier.weight(1f)`）
- [x] 5.2 第 1 个 MetricTile **按 template 分支**（避免与 hero 重复）：
  - 加速测试 (`Acceleration0To100`)：`label = "DISTANCE"`、`value = String.format("%.1f", record.totalDistance)`、`unit = "m"`、`accentColor = TrackTechColors.Cyan`、`valueSize = MetricSize.Medium`、`valueKind = MetricKind.Score`
  - 制动测试 (`Braking100To0`)：`label = "TIME"`、`value = String.format("%.2f", record.totalTime)`、`unit = "s"`、`accentColor = TrackTechColors.Cyan`、`valueSize = MetricSize.Medium`、`valueKind = MetricKind.Score`
  - 实现方式：用 `when` 表达式派生 `(label, value, unit)` 三元组，再传给 `MetricTile`
- [x] 5.3 第 2 个 MetricTile（固定）：`label = "PEAK G"`、`value = String.format("%.2f", record.maxAcceleration)`、`unit = "G"`、`accentColor = TrackTechColors.Red`、`valueSize = MetricSize.Medium`、`valueKind = MetricKind.Score`
- [x] 5.4 第 3 个 MetricTile（固定）：`label = "AVG G"`、`value = String.format("%.2f", record.avgAcceleration)`、`unit = "G"`、`accentColor = TrackTechColors.TextSecondary`、`valueSize = MetricSize.Small`、`valueKind = MetricKind.Score`
- [x] 5.5 检查全文：MUST NOT 出现 `MetricKind.Mechanical`（本页全 Score）；MUST NOT 把 `accentColor` 误传给 `MetricNumber`（`MetricTile` 才有 `accentColor` 参数）

## 6. SpeedChart / GForceChart 增加 wrapInCard 开关

> 注意：本组任务在 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/` 下改动，看板 §6 需要登记这两个文件占用（也属共享文件）。

- [x] 6.1 编辑 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt`：
  - 函数签名增加参数 `wrapInCard: Boolean = true`（放在 `lineColor` 之后）
  - 在函数体内：当 `dataPoints.isEmpty()` 分支与正常分支都按 `if (wrapInCard) Card(modifier = modifier) { Column(...) { content } } else Column(modifier = modifier.padding(16.dp)) { content }` 包裹（推荐做法：抽出 `@Composable private fun SpeedChartContent(...)` 把内容部分独立，`SpeedChart` 主函数 if-else 二选一调用 Content）
  - 不动内部 stroke / grid / axis / 标题字体颜色
- [x] 6.2 编辑 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/GForceChart.kt`：同 6.1 模式（新增 `wrapInCard: Boolean = true` 参数 + if-else 包裹 Card）
- [x] 6.3 验证：本地 grep `SpeedChart(` / `GForceChart(` 调用方，确认所有现有调用方（`TestResultScreen.kt` / 任何 LapDebug* / TestExecution*）**不传** `wrapInCard` 参数 → 默认 true → 渲染行为完全不变
- [x] 6.4 在 `PerformanceResultScreen.kt` 实现 private `@Composable SpeedCurveCard(dataPoints: List<GpsDataPoint>)`：外层 `CutCornerPanel` 包裹；顶部 `"SPEED CURVE"` UiTextLabel cyan section header；内容区域调用 `SpeedChart(dataPoints = dataPoints, modifier = Modifier.fillMaxWidth(), wrapInCard = false)`
- [x] 6.5 在 `PerformanceResultScreen.kt` 实现 private `@Composable GForceCurveCard(dataPoints: List<GpsDataPoint>, maxAcceleration: Double)`：外层 `CutCornerPanel` 包裹；顶部 `"G-FORCE"` UiTextLabel cyan section header；内容区域调用 `GForceChart(dataPoints = dataPoints, maxAcceleration = maxAcceleration, modifier = Modifier.fillMaxWidth(), wrapInCard = false)`
- [x] 6.6 加空数据保护：`if (dataPoints.isEmpty())` 时不渲染整个 SpeedCurveCard / GForceCurveCard（或渲染 `"No data"` muted 文字 placeholder，二选一）
- [x] 6.7 import：`import com.blazepush.feature.test.ui.components.SpeedChart` 与 `import com.blazepush.feature.test.ui.components.GForceChart`

## 7. SPEED SEGMENTS 区段实现

- [x] 7.1 在 `PerformanceResultScreen.kt` 文件顶层添加 private `"SPEED SEGMENTS"` UiTextLabel cyan section header
- [x] 7.2 整体搬迁 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 三个 private 顶层函数（从 `TestResultScreen.kt` 复制，语义不变；保持私有可见性；改 `import` 路径为 fully qualified 避免依赖 V1 文件）
- [x] 7.3 实现 private `@Composable SegmentRow(segment: SpeedSegment)`：cut-corner 包裹（`CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll)`）+ 3 个 Text（区间 label / 时间 / 距离），仿 `LapSessionDetailScreen.LapRecordRow` 但**无** status chip
- [x] 7.4 SegmentRow 内 Row MUST NOT 用 `Arrangement.SpaceBetween`；区间 label 用 `Modifier.width(96.dp)`，时间 + 距离 column 加 `Modifier.weight(1f)` 让 ellipsis 生效
- [x] 7.5 LazyColumn `items(segments)` 渲染列表

## 8. V2 入口接入（仅 NavHost route 注册；RecordsHomeScreen wire-up 转给 F round）

> **路径 A 修订（apply task 1.x 阶段）**：原 task 8b（RecordsHomeScreen wire-up）整组移除 —— scope 转给 F round（`wire-real-data-to-records-and-laps-tabs`）。redesign 仅做 8a（NavHost route 注册）+ 8c（V1 dead code 验收）。详见 design.md Decision 5（再次替换）。

### 8a. V2 NavHost 注册 performance_result/{testId} route（独占）

- [x] 8a.1 编辑 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt`：在 NavHost 内（紧随现有 `composable("lap_session_detail/{sessionId}", ...)` 之后），新增：
  ```kotlin
  composable(
      route = "performance_result/{testId}",
      arguments = listOf(navArgument("testId") { type = NavType.StringType }),
  ) { backStackEntry ->
      val testId = backStackEntry.arguments?.getString("testId").orEmpty()
      PerformanceResultScreen(
          testId = testId,
          onBack = { navController.popBackStack() },
      )
  }
  ```
- [x] 8a.2 import：新增 `import com.blazepush.feature.test.ui.tracktech.PerformanceResultScreen`（同包不需要，但若需要请按现有 import 风格补）
- [x] 8a.3 验证：`grep -n "performance_result" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt` 应至少返回 1 行（route 字面量）
- [x] 8a.4 验证：`./gradlew :feature:test:assembleDebug` 通过（NavHost 改动是热点，编译失败立即停）

### 8b. RecordsHomeScreen wire-up（DELETED — 路径 A scope 转移给 F round）

> 原 task 8b.1-8b.5 全部移除。redesign round MUST NOT 在 `RecordsHomeScreen.kt` 写任何 diff。F round 在接 `recentRuns: StateFlow<List<TestResultSummary>>` 真实数据到 PERFORMANCE 子页时一并加 onClick `navController.navigate("performance_result/${result.id}")`，已在 task 1.4a 留账到 F round 工件。

### 8c. V1 TestFlowNavigation.kt 保持不动（验收）

- [x] 8c.1 验证 `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` 在本 round 全部 commit 完成后 git diff 为空（不进 V1 dead code）
- [x] 8c.2 验证 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` 在本 round 全部 commit 完成后 git diff 为空（路径 A scope 转移已生效，redesign 不动该文件）
- [x] 8c.3 V1 屏 cleanup（`TestFlowNavigation.kt` / `TestResultScreen.kt` / `TestHistoryScreen.kt` 整组删除）作为后续独立 cleanup round 处理（task 14.1 backlog）

## 9. Contract test

- [x] 9.1 新建 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreenContractTest.kt`
- [x] 9.2 实现纯文本 grep contract：读取 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt` 文件文本，断言关键字面量出现：
  - 视觉：`PERFORMANCE`、`TEST TYPE`、`Date`、`Device`、`DISTANCE`、`TIME`、`PEAK G`、`AVG G`、`SPEED CURVE`、`G-FORCE`、`SPEED SEGMENTS`、`0-100 km/h`、`100-0 km/h`
  - chart 调用约束：`wrapInCard = false`（确认 V2 详情页传了正确参数）
  - hero color 约束：`valueColor = TrackTechColors.Purple`（确认 MetricNumber 用对参数名）
- [x] 9.3 反向断言（MUST NOT 出现）：
  - `accentColor = TrackTechColors.Purple`（MetricNumber 没有 accentColor 参数，会编译失败）
  - `MetricKind.Mechanical`（本页全 Score）
  - `carModel`（车型字段不取用）
  - `fontSize = 48.sp`（V1 残留信号）
- [x] 9.4 新增入口接入断言（独立测试方法）：
  - 读 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt`，断言出现字面量 `"performance_result/{testId}"` 与 `PerformanceResultScreen(`
  - 读 `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`，断言**不**出现 `PerformanceResultScreen(` 调用（V1 dead code 保持不动验收）
  - **不**对 `RecordsHomeScreen.kt` 做任何字面量断言（路径 A scope 转移给 F round 后，本 round 不验收该文件状态）
- [ ] 9.4 测试 MUST NOT 加 `@RunWith(RobolectricTestRunner::class)`；MUST 是纯 JVM 单元测试
- [x] 9.5 加测试断言：`PerformanceResultScreen.kt` 中不存在不带 `maxLines = 1` 的 `Text(` 调用（用 regex 扫描，跳过通过组件层封装的 `MetricTile` / `MetricNumber` / `OverviewRow`）

## 10. 编译与本地单测里程碑

- [x] 10.1 在 worktree 内运行 `./gradlew :feature:test:assembleDebug` 验证编译通过
- [x] 10.2 在 worktree 内运行 `./gradlew :feature:test:testDebugUnitTest` 验证现有所有单测零回归 + 新增 `PerformanceResultScreenContractTest` 通过
- [x] 10.3 此为编译里程碑：到此为止可 ff-only 合回 `feature/track-tech-v2`（看板 §5 状态更新）

## 11. 真机视觉验证（华为 + 小屏）

- [x] 11.1 与用户确认当前可用真机（默认华为 `8KE0219522008434`），等用户授权后 `adb -s 8KE0219522008434 install -r` 安装
- [x] 11.2 真机验证 V2 详情页本身（本 round 范围；RecentRuns 入口验证由 F round 承担）：
  - 由于 redesign 不再改 `RecordsHomeScreen.kt`（RecentRuns onClick 仍是 placeholder Toast，等 F round 后续合回再接真实跳转），本 round 真机验证用 **dev backdoor 路径**进入 V2 详情页：
    - 选项 1（推荐）：在 `MainActivity` 或 `TrackTechAppShell` 临时加一个 dev-only 调试按钮（验证完后 revert，不进 commit），调用 `navController.navigate("performance_result/<某条已存在的 testId>")`；testId 取自 `adb shell run-as com.blazepush sqlite3 ... SELECT id FROM test_records LIMIT 1` 或类似的临时方式
    - 选项 2（无 backdoor）：先跑加速测试 / 制动测试至少各一次，让 Room 里有真实 record；然后用 `adb shell am start` 直接传 deeplink 进入 `performance_result/{testId}` route（如果 V2 NavHost 注册了 deeplink 支持；若无，回选项 1）
  - 跑过加速测试 + 制动测试，分别进 V2 详情页验证：
    - **加速 record**：hero "TEST TYPE 0-100 km/h" + 主成绩时间（紫色 Score Hero）+ Metric Row 第 1 格 `DISTANCE`（cyan）/ `PEAK G`（红）/ `AVG G`（muted）+ SPEED CURVE / G-FORCE 卡（cut-corner，无 V1 双层 Card）+ SPEED SEGMENTS 列表
    - **制动 record**：hero 主成绩应是距离（米，紫色 Score Hero）+ Metric Row 第 1 格应是 `TIME`（不是 DISTANCE）
  - back 按钮 → 应 popBackStack 回上一屏（首次回到 home tab；如果是从临时按钮进的就回到那个屏）
  - 空数据 fallback 由 F round 真机验证时一起验（F 改 RecordsHomeScreen 时 RecentRuns 真实数据 / placeholder 切换是它的责任）
- [x] 11.3 验证 Hero 主成绩数字（紫色 Score Hero）、3 个 metric tile 颜色（cyan / red / muted）、SPEED CURVE / G-FORCE 卡视觉、SPEED SEGMENTS 区段
- [x] 11.4 在小屏机型验证（按 CLAUDE.md "UI 视觉约束 §4 真机验证 gate"）：所有 metric label / Hero 数字单行不换行不截断
- [x] 11.5 截图归档（华为屏 + 小屏各一张），存到 `docs/design/visual-refs/round-evidence/redesign-performance-result-screen/`（如果该目录有约定）

## 12. Codex review 触发

- [x] 12.1 ~~提示用户：本 round 视觉与代码已落地，可触发 Codex review~~ —— **user 拍板跳过 Codex review**（路测签收 OK 后直接进入归档）
- [x] 12.2 ~~等用户给 Codex review 反馈~~ —— skip
- [x] 12.3 ~~review 反馈消化~~ —— skip

## 13. push 与归档（高风险，需用户显式确认）

- [x] 13.1 在 worktree 内 commit（按 Conventional Commits 风格）：commit `8a23aec` (RecentRuns onClick + SpeedCurveSection 真实化) + commit `8239421` (AVG G 高度对齐 + Test 首页 LATEST RESULT 接真实数据 follow-up)
- [x] 13.2 ff-only 合回 `feature/track-tech-v2`：commit `8a23aec` + `8239421` 已 ff-merge
- [ ] 13.3 **需用户显式确认才能 push**：`git push origin feature/track-tech-v2` —— user 暂未授权 push，归档时仅本地状态 done
- [x] 13.4 归档 round：`openspec archive redesign-performance-result-screen`
- [x] 13.5 看板 §5 标"完成"；§6 占用标 done；清理 worktree：`git worktree remove .worktrees/redesign-performance-result-screen`

## 14. follow-up backlog（延期立项的 deferred memo）

- [ ] 14.1 cleanup-v1-test-result-screens — 整组删除 V1 dead code 屏：`feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` + `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` + `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestHistoryScreen.kt`（确认无任何调用方后）。**触发条件**：本 round 上线 + V2 入口验证 OK 后 1-2 周
- [ ] 14.2 align-chart-colors-to-v2-tokens — 把 `SpeedChart` / `GForceChart` 内部 stroke / grid / axis 颜色对齐 V2 token（`TrackTechColors.Cyan` / `Red` / `Border`）；同时考虑标题字体（"速度曲线" / "G值曲线" 中文 → 是否替换为英文 `SPEED` / `G-FORCE`，与 V2 风格一致）。**触发条件**：本 round 真机视觉验证发现颜色刺眼、与 V2 token 偏离明显
- [ ] 14.3 test-completion-view-result-action — V2 Test tab 跑完测试后的"看成绩"路径设计。当前 `TrackTechTestExecutionScreen` 跑完只是 `popBackStack`，用户需要切到 Records tab 才能看详情。可选 UX 方向：
  - **a**：完成后 Snackbar `"Test complete · 4.21s"` + action button `"View Result"` → navigate `performance_result/{lastRecordId}`（参考 LapSessionSaveBus 模式）
  - **b**：完成后短暂自动跳详情（可能打断用户连续测试）
  - **c**：完成后专门一个 V2 "test_completion" 屏（带 PB 庆祝 + 主要成绩 hero + 操作按钮）
  - 这是 UX 决策点，需要先跟 user 拍板再立项。**触发条件**：本 round 上线后 user 反馈"做完测试看不到成绩"
- [ ] 14.4 v2-full-test-history-list — V2 当前没有"完整 Test History 列表"屏（V1 `TestHistoryScreen` 是 dead code）。本 round 用 RecordsHomeScreen RecentRuns 最近 3 条作为唯一入口，但用户做了 50+ records 后会有诉求看完整列表。**触发条件**：用户反馈或 records 数量阈值（如 > 20 条）
- [ ] 14.5 deprecate-car-model-field — 评估是否要从 `TestRecordEntity` schema 删除 `carModel` 字段（v4 → v5 migration），或在 UI 重新引入车型概念（从 settings / device profile 取）。**触发条件**：本 round 上线后用户验证"去车型"是否符合产品意图

- [ ] 14.6 follow-up-split-round-archive — 提醒催 `split-records-tab-performance-and-laps` round 闭环（task 8.5 勾选 + archive）。本 round 走路径 A 后已无文件交叉，但 OpenSpec 流程层面那个 round 仍是 in-progress 状态（29/30）。**触发条件**：本 round 真机验证 OK 后立刻提醒 user，避免 split round 无限期挂在 active 列表 → 干扰未来 round 的协同登记 + 看板信号失真

- [ ] 14.7 verify-f-round-recent-runs-onclick — F round（`wire-real-data-to-records-and-laps-tabs`）合回后验证 RecentRuns onClick 是否真的接了 `navController.navigate("performance_result/${result.id}")`，且能进入 V2 PerformanceResultScreen。**触发条件**：F round commit + ff-only 合回主区后立即；这是路径 A scope 转移的闭环检查 —— 防止 ownership 漂移导致两边都没做 wire-up

如果 14.x 任一项需要展开（特别是 14.2 颜色对齐 / 14.3 test 完成 UX），按 CLAUDE.md "延期立项的设计 memo 规矩" §1-3，沉淀 `docs/design/<topic>-deferred.md` 完整 memo。
