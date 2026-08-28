## ADDED Requirements

### Requirement: Segmented control 真实视图分发

`RecordsHomeScreen` 的 `SegmentedControl(options = listOf("PERFORMANCE", "LAPS"), ...)` MUST 通过 `selectedSegment` state 真实分发到两个独立的内嵌 Composable 视图（`PerformanceView` 与 `LapsView` 或等价命名的 `private @Composable fun`）。

`selectedSegment == "PERFORMANCE"` 时 MUST 渲染 `PerformanceView` 内容，**不**渲染 `LapsView`；`selectedSegment == "LAPS"` 时 MUST 渲染 `LapsView` 内容，**不**渲染 `PerformanceView`。

切换 segment 时视图 MUST 瞬时切换（不引入 `AnimatedContent` 或其他过渡动画）。

#### Scenario: when 表达式分发两个视图

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** 阅读主 `Column { ... }` body 在 `SegmentedControl(...)` 调用之后的内容
- **THEN** 含 `when (selectedSegment) { ... }` 表达式或等价 `if (selectedSegment == "PERFORMANCE") { ... } else { ... }` 分支
- **AND** `"PERFORMANCE"` 分支调用 `PerformanceView(...)` 或等价 `@Composable private fun`
- **AND** `"LAPS"` 分支调用 `LapsView(...)` 或等价 `@Composable private fun`

#### Scenario: 两个视图 Composable 独立定义

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** grep `private fun PerformanceView` 与 `private fun LapsView`
- **THEN** 两个 `@Composable private fun` 独立定义命中（函数名可微调，但 MUST 是两个不同函数，不共用同一函数体）

### Requirement: PERFORMANCE 视图骨架

`PerformanceView` MUST 按渲染图左半结构落地 3 个区块，从上到下依次：

1. **3 个 MetricTile 行**：`BEST 0-100` (value `4.21`, unit `s`, accent `Purple`) + `BEST BRAKE` (value `36.8`, unit `m`, accent `Red`) + `TOTAL RUNS` (value `24`, unit `null`, accent `Cyan`)，三个 tile 用 `Row` + `Modifier.weight(1f)` 等宽分布
2. **SPEED CURVE 卡片**：`CutCornerPanel` + 标题 `SPEED CURVE` (`UiTextLabel` + Cyan) + 副标题 `(0-100 km/h)` + Canvas 绘制坐标轴 + cyan 渐近曲线 + `100 km/h` 处水平虚线 + `4.21 s` 处垂直虚线 + 交点圆点 + `100 km/h\n4.21 s` 标注气泡
3. **RECENT RUNS 列表**：section 标题 `RECENT RUNS` (UiTextLabel + Cyan) + **3 条** `TrackTechRow`（前 2 条普通：`0-100 km/h` `4.58 s` `Today, 10:35` 与 `100-0 km/h` `38.2 m` `Today, 10:32`；第 3 条 PB 高亮：trophy icon + `0-100 km/h` `4.21 s` `May 18, 2024 · Personal Best` 副文 + 紫色 accent）

#### Scenario: PerformanceView 顶部 3 metric tile

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `PerformanceView` 函数
- **WHEN** grep 字符串字面量
- **THEN** 命中 `"BEST 0-100"` × 1
- **AND** 命中 `"BEST BRAKE"` × 1
- **AND** 命中 `"TOTAL RUNS"` × 1
- **AND** 命中 `"4.21"` × 1（BEST 0-100 的 value 字面量）
- **AND** 命中 `"36.8"` × 1（BEST BRAKE 的 value 字面量）
- **AND** 命中 `"24"` × 1（TOTAL RUNS 的 value 字面量）

#### Scenario: SPEED CURVE 卡片含 Canvas 绘制

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `PerformanceView` 函数
- **WHEN** 阅读 SPEED CURVE 卡片实现
- **THEN** 含 `CutCornerPanel(...)` 调用包装
- **AND** 含 `Canvas(modifier = ...) { ... }` 调用
- **AND** Canvas body 含 `drawLine` 或 `drawPath` 调用（坐标轴 + 曲线）
- **AND** Canvas 关联标注气泡含 `100 km/h` 与 `4.21 s` 字面量（显示在卡片内文字 / 气泡内）

#### Scenario: RECENT RUNS 列表含 3 条且第 3 条为 PB

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `PerformanceView` 函数与 `placeholderRecentRuns` 列表（或等价 hardcoded 数据集）
- **WHEN** 阅读列表数据源
- **THEN** 列表长度 == 3
- **AND** 第 3 项 `isPB == true`（或等价标记字段）
- **AND** PB 项 leading icon 用 `Icons.Filled.EmojiEvents`（trophy）或等价奖杯/PB 含义 icon
- **AND** PB 项副文字含 `"Personal Best"` 字面量
- **AND** 前 2 项副文字含 `"Today"` 字面量（与 PB 项的 `"May 18, 2024"` 区分）

### Requirement: LAPS 视图骨架

`LapsView` MUST 按渲染图右半结构落地 4 个区块，从上到下依次：

1. **CURRENT TRACK RECORD 大卡片**：`CutCornerPanel` 横向布局，左半含 `CURRENT TRACK RECORD` 标签（UiTextLabel + Purple）+ `Shanghai Tianma` 标题（RacingTitle 系列 + TextPrimary）+ `BEST LAP` 标签（Cyan/Purple）+ `1:32.457` 大字（MetricMedium）+ `May 18, 2024` 副文（TextSecondary）；右半含 Canvas 绘制赛道 cyan 简笔预览 + 右上角 `Icons.Filled.Star` 收藏星（仅渲染，不可交互）
2. **Track 信息行 TrackTechRow**：leading icon `Icons.Filled.LocationOn` + title `Shanghai Tianma` + subtitle `3.063 km · Clockwise` + chevron（点击触发 Toast 占位 `"Track detail coming next round"` 或等价文案）
3. **3 个 MetricTile 行**：`BEST LAP` (value `1:32.457`, unit `null`, accent `Purple`) + `SESSIONS` (value `8`, unit `null`, leading icon 可选 calendar, accent `Cyan`) + `TOTAL LAPS` (value `56`, unit `null`, accent `Cyan`)
4. **SESSION HISTORY 列表**：section 标题 `SESSION HISTORY` (UiTextLabel + Cyan/Purple) + **3 条** `TrackTechRow`：`May 18, 2024 · 4 Laps · Best 1:32.457` / `May 12, 2024 · 6 Laps · Best 1:33.884` / `Apr 29, 2024 · 5 Laps · Best 1:34.210`（点击触发 Toast 占位或保持当前 `Toast` 行为）

#### Scenario: LapsView CURRENT TRACK RECORD 卡片字面量

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数
- **WHEN** grep 字符串字面量
- **THEN** 命中 `"CURRENT TRACK RECORD"` × 1
- **AND** 命中 `"Shanghai Tianma"` × 1（CURRENT TRACK RECORD 卡内）
- **AND** 命中 `"1:32.457"`（CURRENT TRACK RECORD 卡内的 BEST LAP value）
- **AND** 命中 `"May 18, 2024"`（CURRENT TRACK RECORD 卡内的日期或 SESSION HISTORY 第 1 条）

#### Scenario: 赛道简笔预览 Canvas

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数（或它调用的 `TrackPreviewStub` 等独立 Composable）
- **WHEN** 阅读赛道预览实现
- **THEN** 含 `Canvas(...) { ... }` 调用
- **AND** Canvas body 含 `drawPath` 或多个 `drawLine` / `cubicTo` 调用（绘制不规则闭合曲线）
- **AND** stroke 颜色用 `TrackTechColors.Cyan` 或等价 cyan 系颜色

#### Scenario: 收藏星 icon 渲染（不可交互）

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数
- **WHEN** 阅读 CURRENT TRACK RECORD 卡片实现
- **THEN** 含 `Icons.Filled.Star` 或 `Icons.Outlined.Star` 引用
- **AND** Star icon **MUST NOT** 包裹 `Modifier.clickable { ... }`（本 round 仅渲染，不实现收藏交互）

#### Scenario: Track 信息行使用 TrackTechRow

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数
- **WHEN** 阅读 Track 信息行实现
- **THEN** 含 `TrackTechRow(...)` 调用
- **AND** 调用参数 `leadingIcon` 用 `Icons.Filled.LocationOn` 或等价定位/位置 icon
- **AND** 调用参数 `title` 含 `"Shanghai Tianma"` 字面量
- **AND** 调用参数 `subtitle` 含 `"3.063 km"` 与 `"Clockwise"` 字面量

#### Scenario: 3 metric tile 字面量

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数
- **WHEN** grep 字符串字面量
- **THEN** 命中 `"BEST LAP"` ≥ 1（卡内 + tile 共 2 处都可命中）
- **AND** 命中 `"SESSIONS"` × 1
- **AND** 命中 `"TOTAL LAPS"` × 1
- **AND** 命中 `"8"` 与 `"56"` 字面量（SESSIONS / TOTAL LAPS 的 value）

#### Scenario: SESSION HISTORY 列表 3 条

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 函数与 `placeholderLapSessions` 列表（或等价 hardcoded 数据集）
- **WHEN** 阅读列表数据源
- **THEN** 列表长度 == 3
- **AND** 命中 `"SESSION HISTORY"` 字面量（section 标题）
- **AND** 命中 `"4 Laps"` / `"6 Laps"` / `"5 Laps"` 三个字面量（每条 session 的圈数）

### Requirement: 标题栏 filter icon 占位

`RecordsHomeScreen` 顶部标题行 MUST 在 `Records` 文字右侧加一个 filter icon（`Icons.Filled.FilterAlt` 或 `Icons.Filled.Tune` 任一），尺寸 `24.dp`，主色 `TrackTechColors.TextSecondary`。

filter icon MUST 包裹 `Modifier.clickable { ... }`，点击触发 `Toast.makeText(context, "Filter coming next round", Toast.LENGTH_SHORT).show()` 或等价占位回调（文案可微调但 MUST 提示用户预期）。

filter icon MUST 在 PERFORMANCE / LAPS 两个视图都显示（即位于 segment 切换之上的标题行内，不进 PerformanceView / LapsView 内部）。

#### Scenario: filter icon 与 Toast 占位

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** 阅读 `Records` 标题行实现
- **THEN** 含 `Icons.Filled.FilterAlt` 或 `Icons.Filled.Tune` 引用
- **AND** 含 `Modifier.clickable { ... }` 包裹
- **AND** clickable lambda body 含 `Toast.makeText(...).show()` 调用
- **AND** Toast 文案含 `"Filter"` 字面量（如 `"Filter coming next round"`）

#### Scenario: filter icon 不在视图分发分支内

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** 阅读 `when (selectedSegment) { ... }` 分支位置与 filter icon 渲染位置的相对关系
- **THEN** filter icon 渲染代码位于 `when` 分发**之外**（在 `Records` 标题 Row 内），即 PERFORMANCE / LAPS 视图都能看到 filter icon

### Requirement: Hardcoded 数据 hold 在文件内私有数据类

本 round 所有占位数据 MUST 用 **private** 数据类与 `private val` 顶层 list 集中在 `RecordsHomeScreen.kt` 文件内（不在领域模型层、不在 Repository 层、不在公开 API 内暴露）：

- `private data class RecentRun(...)` + `private val placeholderRecentRuns: List<RecentRun>`
- `private data class LapSessionRow(...)` + `private val placeholderLapSessions: List<LapSessionRow>`
- `private data class CurrentTrackRecord(...)` + `private val placeholderTrackRecord: CurrentTrackRecord`

字段名可调整，但每个数据类与对应 placeholder list / value MUST 是 **private** 可见性。

#### Scenario: 数据类全部 private

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** grep `data class` 与 `class ` 定义
- **THEN** 所有 `data class` 定义前都有 `private` 修饰符
- **AND** 所有 `placeholder` 开头的顶层 `val` 定义都是 `private val`

#### Scenario: 不暴露到外部命名空间

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包
- **WHEN** grep `RecentRun(` / `LapSessionRow(` / `CurrentTrackRecord(`（构造函数调用）
- **THEN** 命中位置仅限于 `RecordsHomeScreen.kt` 自身（其他 .kt 文件不引用这些数据类）

### Requirement: 不接真实数据层（MUST NOT 边界）

本 round MUST NOT 引入对以下真实数据层的依赖：

- `TestResultRepository` / `TestRecordDao` 调用
- `TelemetryRepository` / `TelemetrySessionDao` / `CrossingEventDao` 调用
- `TrackCatalog` / `PresetTrackCatalog` 调用
- `TestSessionViewModel` / `LapTimingEngine` 调用
- 任何 ViewModel injection（不通过 `koinInject` / `koinViewModel` / `viewModel()` 拿任何业务 ViewModel）

`RecordsHomeScreen` 的函数签名 MUST 不增加任何 ViewModel / Repository 类型参数（保持 baseline `(navController: NavController, onTabSelected: (Int) -> Unit, modifier: Modifier)` 三参数 + 默认值结构）。

#### Scenario: 不 import 数据层 / Repository / ViewModel

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 顶部 import 列表
- **WHEN** grep `TestResultRepository` / `TelemetryRepository` / `TrackCatalog` / `TestSessionViewModel`
- **THEN** 在 `RecordsHomeScreen.kt` 内零命中

#### Scenario: 函数签名零业务参数

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** 阅读 `@Composable fun RecordsHomeScreen(...)` 函数签名
- **THEN** 参数列表仅含 `navController: NavController` + `onTabSelected: (Int) -> Unit` + `modifier: Modifier` 三项及其默认值
- **AND** **不**含 `viewModel: ...ViewModel` / `repository: ...Repository` / `dao: ...Dao` 参数

### Requirement: SegmentedControl 视觉零回归

baseline `RecordsHomeScreen.kt` 内 `private fun SegmentedControl(options, selected, onSelect, modifier)` 实现 MUST 零改动（视觉 + API 不变）。

#### Scenario: SegmentedControl 函数体零改动

- **GIVEN** 实施前后 `RecordsHomeScreen.kt` 内 `SegmentedControl` 函数
- **WHEN** `git diff <baseline>..HEAD -- "feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/RecordsHomeScreen.kt"` 中 `SegmentedControl` 函数所在行
- **THEN** 该函数 body 零行改动（视觉契约保留）
