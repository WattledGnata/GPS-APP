## ADDED Requirements

### Requirement: 详情页文件位置与 Composable 入口

`PerformanceResultScreen` MUST 位于 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt`，与 `LapSessionDetailScreen` 同包（`com.blazepush.feature.test.ui.tracktech`）。MUST 提供一个 public Composable 函数 `PerformanceResultScreen`，签名 MUST 兼容现 `TestResultScreen(testId: String, onBack: () -> Unit, ...)` 入参，使得 `TestFlowNavigation.kt` 的 `TestNavRoute.Result` 分支可以直接替换 Composable 引用。

#### Scenario: V2 详情页文件存在且包路径正确

- **WHEN** 检查仓库
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreen.kt` 文件存在
- **AND** 文件首行 package 为 `package com.blazepush.feature.test.ui.tracktech`

#### Scenario: 入口 Composable 签名与 V1 兼容

- **WHEN** `TestFlowNavigation.kt` 在 `TestNavRoute.Result` 分支调用 `PerformanceResultScreen(testId = route.testId, onBack = { ... })`
- **THEN** 编译通过，无需调整调用方签名

### Requirement: DetailHeader 必须复用 V2 视觉语法

`PerformanceResultScreen` 的顶部 header MUST 使用与 `LapSessionDetailScreen.DetailHeader` 相同的视觉语法：cut-corner 包裹的 ← back 按钮 + 标题文字。标题文字字面量 MUST 为 `"PERFORMANCE"`（全大写）。

#### Scenario: header 标题字面量正确

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找 header 区域
- **THEN** 字面量 `"PERFORMANCE"` 出现一次
- **AND** back 按钮使用 `CutCornerPanelShape` 与 `Icons.Filled.ArrowBack`

### Requirement: Hero 区块呈现测试类型与主成绩

页面首屏 MUST 包含一个 `CutCornerPanel` Hero 区块，从上到下顺序呈现：

1. `TEST TYPE` label（cyan 着色，UiTextLabel）
2. 测试类型大标题（`RacingTitleMedium`）—— 字面量来自 `TestTemplate`：`"0-100 km/h"`（加速）或 `"100-0 km/h"`（制动）
3. 主成绩数字 + unit 行 —— 数字使用 `MetricNumber(kind = MetricKind.Score, size = MetricSize.Hero, valueColor = TrackTechColors.Purple)`；unit 由 `MetricNumber` 内部自动渲染（传 `unit` 参数即可）。**注意**：`MetricNumber` 的强调色参数名是 `valueColor`，**不是** `accentColor`（`accentColor` 仅 `MetricTile` 有）
4. `Date` 与 `Device` 两条 OverviewRow（label-value 格式，与 `LapSessionDetailScreen.OverviewRow` 相同实现）

Hero 主成绩字段与 unit 必须按 `TestTemplate.fromId(record.testTemplateId)` 分支：

| Template | value | unit |
|---|---|---|
| `Acceleration0To100` | `String.format("%.2f", record.totalTime)` | `"s"` |
| `Braking100To0` | `String.format("%.1f", record.totalDistance)` | `"m"` |
| 未知 / null | `record.result` 原始字符串 | 不传 unit |

此分支与 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestHistoryScreen.kt` line 164-166 现有显示约定一致。

Hero MUST NOT 展示 `TestRecordEntity.carModel` 字段。

#### Scenario: TEST TYPE label 与 Hero 数字字面量出现

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找
- **THEN** 字面量 `"TEST TYPE"` 出现
- **AND** 字面量 `"Date"` 与 `"Device"` 出现（OverviewRow label）
- **AND** 字面量 `"carModel"` **不**出现（车型字段不取用）

#### Scenario: 加速测试 Hero 主成绩使用时间

- **WHEN** record 的 `testTemplateId` 解析为 `TestTemplate.Acceleration0To100`
- **THEN** Hero 大标题渲染 `"0-100 km/h"`
- **AND** Hero 主成绩数字渲染 `record.totalTime` 格式化后的字符串
- **AND** Hero 主成绩 unit 渲染 `"s"`

#### Scenario: 制动测试 Hero 主成绩使用距离

- **WHEN** record 的 `testTemplateId` 解析为 `TestTemplate.Braking100To0`
- **THEN** Hero 大标题渲染 `"100-0 km/h"`
- **AND** Hero 主成绩数字渲染 `record.totalDistance` 格式化后的字符串
- **AND** Hero 主成绩 unit 渲染 `"m"`

#### Scenario: MetricNumber 调用使用 valueColor 而非 accentColor

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找 `MetricNumber(` 调用
- **THEN** 调用块中出现 `valueColor = TrackTechColors.Purple`
- **AND** 调用块中**不**出现 `accentColor =`（避免编译错误）

### Requirement: Metric Row 呈现 3 个等分指标

Hero 区块下方 MUST 紧随一个 horizontal Row，包含 3 个 `MetricTile`，每个 tile 占 `Modifier.weight(1f)`。第 1 格按 `TestTemplate` 分支以避免与 hero 重复；第 2 / 3 格固定。

**第 1 格（按 template 分支）**：

| Template | label | value 来源 | unit | accentColor | valueSize |
|---|---|---|---|---|---|
| `Acceleration0To100` | `DISTANCE` | `record.totalDistance` 格式 `%.1f` | `m` | `TrackTechColors.Cyan` | `MetricSize.Medium` |
| `Braking100To0` | `TIME` | `record.totalTime` 格式 `%.2f` | `s` | `TrackTechColors.Cyan` | `MetricSize.Medium` |

**第 2 / 3 格（固定）**：

| index | label | value 来源 | unit | accentColor | valueSize |
|---|---|---|---|---|---|
| 1 | `PEAK G` | `record.maxAcceleration` 格式 `%.2f` | `G` | `TrackTechColors.Red` | `MetricSize.Medium` |
| 2 | `AVG G` | `record.avgAcceleration` 格式 `%.2f` | `G` | `TrackTechColors.TextSecondary` | `MetricSize.Small` |

所有 tile MUST 使用 `valueKind = MetricKind.Score`（结果数字非仪表瞬时，按 CLAUDE.md "DSEG7 七段数字字体禁止滥用" 规则）。

#### Scenario: 加速测试第 1 格显示 DISTANCE

- **WHEN** record 的 `testTemplateId` 解析为 `TestTemplate.Acceleration0To100`
- **THEN** Metric Row 第 1 格 label 渲染 `"DISTANCE"`
- **AND** value 渲染 `record.totalDistance` 格式化字符串
- **AND** unit 渲染 `"m"`

#### Scenario: 制动测试第 1 格显示 TIME

- **WHEN** record 的 `testTemplateId` 解析为 `TestTemplate.Braking100To0`
- **THEN** Metric Row 第 1 格 label 渲染 `"TIME"`
- **AND** value 渲染 `record.totalTime` 格式化字符串
- **AND** unit 渲染 `"s"`

#### Scenario: 第 2 / 3 格固定显示 PEAK G / AVG G

- **WHEN** 任意 record 进入详情页
- **THEN** Metric Row 第 2 格 label 渲染 `"PEAK G"`，accent 红色
- **AND** Metric Row 第 3 格 label 渲染 `"AVG G"`，accent muted
- **AND** 没有 `MetricKind.Mechanical` 出现（本页全 Score）

#### Scenario: 关键字面量出现

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找
- **THEN** 字面量 `"DISTANCE"`、`"TIME"`、`"PEAK G"`、`"AVG G"` 各至少出现一次（DISTANCE 与 TIME 是 if-else 分支两侧）

### Requirement: SpeedChart / GForceChart 增加 wrapInCard 开关

`feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedChart.kt` 与 `GForceChart.kt` MUST 各自新增一个 `wrapInCard: Boolean = true` 参数（默认 true，所有现有调用方零改动）。

行为约束：

- `wrapInCard = true`（默认）：行为与改造前完全一致 —— 渲染 Material3 `Card { Column { 标题 Row + Canvas + 时间轴 } }`
- `wrapInCard = false`：跳过 `Card` 容器，直接渲染 `Column { 标题 Row + Canvas + 时间轴 }`，让外部容器（如 V2 详情页的 `CutCornerPanel`）成为唯一卡容器，避免双层卡

chart 内部 stroke / grid / axis / 标题字体颜色与字号 MUST 保持现状（颜色对齐 V2 token 留下个 round）。

#### Scenario: SpeedChart 默认行为不变

- **WHEN** 在 `SpeedChart.kt` 中查找函数签名
- **THEN** 出现参数 `wrapInCard: Boolean = true`
- **AND** 现有调用方（任何不传 `wrapInCard` 的调用）行为不变（仍渲染 Material3 Card）

#### Scenario: GForceChart 默认行为不变

- **WHEN** 在 `GForceChart.kt` 中查找函数签名
- **THEN** 出现参数 `wrapInCard: Boolean = true`

#### Scenario: V2 详情页传 wrapInCard = false

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找 `SpeedChart(` / `GForceChart(` 调用
- **THEN** 调用块中出现 `wrapInCard = false` 字面量

### Requirement: SPEED CURVE 与 G-FORCE 曲线卡

Metric Row 下方 MUST 依次包含两个 `CutCornerPanel`：

1. **SPEED CURVE 卡** —— 外层 `CutCornerPanel`；顶部 cyan section header `"SPEED CURVE"`（UiTextLabel）；内容区域 MUST 调用 `SpeedChart(dataPoints = ..., modifier = Modifier.fillMaxWidth(), wrapInCard = false)`，让 V2 cut-corner 成为唯一卡容器，避免双层卡
2. **G-FORCE 卡** —— 外层 `CutCornerPanel`；顶部 cyan section header `"G-FORCE"`；内容区域 MUST 调用 `GForceChart(dataPoints = ..., maxAcceleration = record.maxAcceleration, ..., wrapInCard = false)`

如果 `dataPoints.isEmpty()`（binary 文件读取失败 / 空记录），曲线卡 MUST 优雅退化（不渲染整个卡片，或渲染卡片但内容是 muted 文字 `"No data"`）。

#### Scenario: 两个曲线卡 section header 字面量出现

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找
- **THEN** 字面量 `"SPEED CURVE"` 与 `"G-FORCE"` 各出现一次

#### Scenario: 曲线 Composable 来自现有 components 包，未重写

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找 import
- **THEN** 存在 `import com.blazepush.feature.test.ui.components.SpeedChart`
- **AND** 存在 `import com.blazepush.feature.test.ui.components.GForceChart`

### Requirement: SPEED SEGMENTS 区段

页面底部 MUST 包含 `SPEED SEGMENTS` 区段：

1. cyan section header 字面量 `"SPEED SEGMENTS"`（UiTextLabel）
2. 紧随每段一条 `SegmentRow`（cut-corner 容器，仿 `LapSessionDetail.LapRecordRow` 但**无** status chip）
3. 每行 3 列：区间 label（`<from>–<to> km/h`）/ 时间（`%.3f s`）/ 距离（`%.1f m`）

分段数据派生 MUST 使用从 `TestResultScreen.kt` 整体搬迁的 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 私有函数，语义不变（10 km/h step 等分 + 加速最后一段 90→100、制动 100→...→10）。

#### Scenario: SPEED SEGMENTS section header 字面量出现

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找
- **THEN** 字面量 `"SPEED SEGMENTS"` 出现一次

#### Scenario: 派生函数已搬迁

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找 private 函数
- **THEN** 存在 `calculateSegmentsFromPoints` / `calculateSegment` / `calculateSegmentDistance` 三个私有函数（顶层或文件内 helper）
- **AND** 函数签名与 `TestResultScreen.kt` 中的语义一致

### Requirement: 严格单行 Ellipsis（V2 视觉硬约束）

V2 视觉规则要求所有 metric / row / label / 卡片标题类 `Text(...)` 调用 MUST 显式带 `maxLines = 1, overflow = TextOverflow.Ellipsis`。本页面所有自定义 `Text` 调用（不包括通过 `MetricTile` / `MetricNumber` / `OverviewRow` 内部已封装的部分）MUST 满足此约束。包含可变长度文本的水平 Row（如 SegmentRow）MUST 在文本子元素加 `Modifier.weight(1f)` 与 `Modifier.weight(1f, fill = false)` 让 ellipsis 真正生效（不能用 `Arrangement.SpaceBetween`）。

#### Scenario: 自定义 Text 全部带 maxLines

- **WHEN** 在 `PerformanceResultScreen.kt` 中查找直接调用的 `Text(...)`
- **THEN** 每处 `Text` 调用块内出现 `maxLines = 1`
- **AND** 出现 `overflow = TextOverflow.Ellipsis`

#### Scenario: SegmentRow 不使用 SpaceBetween 布局

- **WHEN** 在 `PerformanceResultScreen.kt` 的 `SegmentRow` 实现中查找
- **THEN** 不出现 `horizontalArrangement = Arrangement.SpaceBetween`
- **AND** 至少一处 `Modifier.weight(1f)` 出现

### Requirement: V2 NavHost 必须注册 performance_result/{testId} route

`TrackTechAppShell.kt` 的 `NavHost` MUST 新增一个 `composable("performance_result/{testId}", arguments = listOf(navArgument("testId") { type = NavType.StringType }))` 分支，在分支内取出 `testId` 后调用 `PerformanceResultScreen(testId = testId, onBack = { navController.popBackStack() })`。route 注册位置紧随现有 `lap_session_detail/{sessionId}` 后（视觉相邻 + 模式相似）。

#### Scenario: V2 NavHost 已注册新 route

- **WHEN** 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt` 中查找
- **THEN** 出现 `composable(` 块包含字面量 `"performance_result/{testId}"`
- **AND** 出现 `navArgument("testId")` 与 `NavType.StringType`
- **AND** 出现 `PerformanceResultScreen(` 调用，传入解析后的 testId 参数
- **AND** 出现 `onBack = { navController.popBackStack() }` 或等价 lambda

### Requirement: RecordsHomeScreen RecentRuns wire-up 转移给 F round —— 本 round 不改该文件

本 redesign round MUST NOT 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt`（任何 diff 都视为越界）。

`RecordsHomeScreen.kt` 的 PERFORMANCE 子页 RecentRuns 接真实 `TestRecordEntity` 数据 + onClick 跳 `performance_result/${result.id}` SHALL 不在本 redesign round 的 scope 内。该工作 100% 重叠于 `wire-real-data-to-records-and-laps-tabs` round（F round）的"PERFORMANCE / LAPS 全量接真实数据 + 删 placeholderRecentRuns"工作；本 redesign round commit 任何对 `RecordsHomeScreen.kt` 的 diff 都会被 F round 后续覆盖。

F round 已被告知 follow-up task：在接 `recentRuns` 真实数据到 PERFORMANCE 子页 UI 时，RecentRuns row `onClick` MUST 调 `navController.navigate("performance_result/${result.id}")`；F round 真机 gate 加"点击真实 RecentRuns row 进入 V2 PerformanceResultScreen"。

#### Scenario: RecordsHomeScreen.kt 在本 round 不被改动

- **WHEN** 本 round 全部 commit 完成后查 git diff
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt` 文件 diff 为空（不在本 round 改动列表内）
- **AND** contract test 不对 `RecordsHomeScreen.kt` 做任何字面量断言

### Requirement: V1 TestFlowNavigation.kt 不在本 round 验收范围

`MainActivity.kt:58` 已直接 `setContent { TrackTechAppShell() }`，全 app grep 确认 V1 `TestFlowNavigation` 已无任何调用方（dead code）。本 round MUST NOT 修改 `TestFlowNavigation.kt`（含 `TestNavRoute.Result` 分支）—— 改了不影响真实运行路径，反而误导 review。V1 屏整组 cleanup（`TestFlowNavigation.kt` / `TestResultScreen.kt` / `TestHistoryScreen.kt`）作为独立 cleanup round 处理。

#### Scenario: TestFlowNavigation.kt 在本 round 不被改动

- **WHEN** 本 round 全部 commit 完成后查 git diff
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` 文件 diff 为空（不在本 round 改动列表内）

### Requirement: V1 旧详情页保留作为兜底

`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` 文件 MUST 保留（不在本 round 删除），用于：

1. 回滚兜底：本 round 视觉问题严重时可 revert + restore 路由
2. 派生函数代码同源参照：cleanup round 删除前可对比派生函数语义是否一致

#### Scenario: 旧 V1 文件存在

- **WHEN** 检查仓库
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt` 文件存在

### Requirement: Contract test 兜底视觉漂移

新增 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/PerformanceResultScreenContractTest.kt`，使用纯文本 grep 风格（读取 `PerformanceResultScreen.kt` 源文件字符串）锁定关键视觉字面量。MUST NOT 依赖 Compose runtime / Robolectric / Android Context。

锁定的字面量集合：`PERFORMANCE`、`TEST TYPE`、`Date`、`Device`、`DISTANCE`、`TIME`、`PEAK G`、`AVG G`、`SPEED CURVE`、`G-FORCE`、`SPEED SEGMENTS`、`0-100 km/h`、`100-0 km/h`、`wrapInCard = false`、`valueColor = TrackTechColors.Purple`。

反向断言（MUST NOT 出现）：

- `accentColor = TrackTechColors.Purple`（`MetricNumber` 没有 `accentColor` 参数，会编译失败）
- `MetricKind.Mechanical`（本页全 Score）
- `carModel`（车型字段不取用）

#### Scenario: contract test 文件存在并执行

- **WHEN** 运行 `./gradlew :feature:test:testDebugUnitTest --tests "*PerformanceResultScreenContractTest*"`
- **THEN** 测试全部通过
- **AND** 测试不依赖 Robolectric runner

#### Scenario: 误删字面量时 contract test 失败

- **WHEN** 有人误删字面量 `"PEAK G"`（替换为别的字符串）
- **THEN** contract test 该项断言失败，提示具体字面量名
