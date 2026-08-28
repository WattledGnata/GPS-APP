## Context

`RecordsHomeScreen` 226 行，baseline 已有 SegmentedControl 切换 PERFORMANCE/LAPS、3 个 MetricTile（BEST 0-100 / BEST 100-0 / RUNS）、SpeedCurvePlaceholder（Canvas 内只画了文字 "Cyan speed curve · future round"）、5 个 RECENT RUNS TrackTechRow。

但 baseline 的 `Column { ... }` body 里的 metric tile + speed curve + recent runs 在 `selectedSegment` 切换时完全没有条件分支——LAPS 选中时仍显示这些 PERFORMANCE 内容。

用户提供的渲染图把两个视图视觉上拆得很清楚，本 round 把 baseline 的"占位 + 空切换"提升为"两个完整视图骨架（仍占位数据）+ 真实分发"。

约束：
- 不接 TestResultRepository / Telemetry / Lap session repository（数据层尚未提供查询 API）
- 不引入新依赖
- 不改 SegmentedControl 视觉
- 不改其他 home screen / Shell / Pager 逻辑

## Goals / Non-Goals

**Goals:**

- Segmented control 在 PERFORMANCE / LAPS 之间切换时，下方内容真实分发（两个视图各自独立的 Column 结构）
- PERFORMANCE 视图：3 metric tile + SPEED CURVE 卡片（Canvas 绘制坐标轴 + cyan 曲线 + 100 km/h 标注）+ 3 条 RECENT RUNS（含 PB 高亮项）
- LAPS 视图：CURRENT TRACK RECORD 卡片（含赛道 cyan 简笔预览 stub） + Track 信息行 + 3 metric tile + 3 条 SESSION HISTORY
- 标题栏右侧 filter icon 占位（Toast 占位回调）
- 数据全部 hardcoded，private data class 文件内消化
- 真机视觉与渲染图对比偏差作为 follow-up backlog 记录

**Non-Goals:**

- 不接真实数据（`TestResultRepository.testResultsFlow` / Lap session 查询 / Track catalog 信息）
- 不实现 filter 功能、收藏功能、列表项点击进入详情
- 不实现真实赛道几何渲染（用 stub 闭合曲线代替）
- 不写单元测试（外部测试无可观察契约，靠 grep + 真机视觉）
- 不动其他 home screen
- 不动 SegmentedControl 视觉（继续用 baseline 已有实现）

## Decisions

### D1：PERFORMANCE / LAPS 视图分发用 `when` 表达式

**决定**：在 `RecordsHomeScreen` 的 `Column { ... }` body 内，标题栏 + Segmented 之后，用 `when (selectedSegment) { "PERFORMANCE" -> PerformanceView(); "LAPS" -> LapsView() }` 单层分发到两个内嵌 Composable。

**为什么不用 `if (selectedSegment == "PERFORMANCE") { ... } else { ... }`**：`when` 在视图数 = 2 时与 `if` 等价，但未来扩展（加 SETTINGS / EXPORT 等 segment）时 when 更直观。本 round 实际只 2 项，但保持可扩展形态。

**为什么不抽公共结构**：两个视图除了"标题 + segmented + filter icon"共享外，下半部分结构完全不同。强行抽公共结构会引入无用抽象。

**替代方案考虑**：
- ❌ HorizontalPager 内嵌（PERFORMANCE / LAPS 也作为 page 滑动切换）：与 Tab 层级 Pager 嵌套不一致，且 segmented control 与滑动手势重复
- ❌ AnimatedContent 渐变切换：本 round 不引入动画，瞬切即可
- ✅ 直接 when 分发（本方案）：最简单

### D2：SPEED CURVE Canvas stub 形态

**决定**：用 `Canvas` 绘制：

- 内边距留 32dp 给坐标轴标签
- 横轴：`0 / 1 / 2 / 3 / 4 / 5 s` 6 个等距 tick + 文字
- 纵轴：`0 / 50 / 100 / 150 km/h` 4 个 tick + 文字
- cyan 渐近曲线：用 quadraticBezierTo 或 cubicTo 模拟 0→100 km/h 渐近增长（5 秒内到 100，然后慢慢逼近 150）
- 100 km/h 处水平虚线 + 4.21 s 处垂直虚线，交点画 cyan 圆点
- 圆点上方画一个圆角矩形气泡 + 文字 `100 km/h\n4.21 s`

**Canvas 函数 API**：用 `drawLine` / `drawPath` / `drawCircle` / `drawRoundRect`；文字用 `drawIntoCanvas { drawText(...) }` + `Paint.measureText` 居中。

**为什么不用 `vico` / `MPAndroidChart`**：引入新依赖，违反"不引入依赖"原则。stub 用 Canvas 60 行内写完，足够渲染图层级。

**为什么不真接历史数据**：本 round 数据层没暴露"按时间序列拿 0-100 加速曲线"的 API；硬接需要新建 use case + DAO 查询，超 scope。

### D3：CURRENT TRACK RECORD 赛道简笔预览 stub

**决定**：用 `Canvas` 绘制一个变形闭合曲线代替真实赛道几何：

- 起点 `(0.2W, 0.5H)`，控制点用 4 个 cubicTo 段连成一个不规则闭合环（类似上海天马的 8 字结构简化）
- stroke width 2.dp，cyan 色
- 一个 cyan 小圆点表示 start/finish 位置
- 整体留 8dp 内边距

**为什么不用 SVG**：项目当前 assets 没赛道 SVG，未来真实数据接入后用 LineString 坐标序列绘制更合适，stub 不值得引 SVG 依赖。

**为什么不画一个圆**：圆形太规则，不像赛道；不规则闭合曲线视觉上更接近渲染图意图。

### D4：PERFORMANCE RECENT RUNS 列表 PB 高亮形态

**决定**：第 3 条 PB 项与前两条普通项视觉差异：

| 项 | 普通 (前 2 条) | PB (第 3 条) |
|---|---|---|
| leadingIcon | `Icons.Filled.Speed` (0-100) / `Icons.Outlined.DoNotDisturbOn` (100-0) | `Icons.Filled.EmojiEvents` (trophy，紫色) |
| title | `0-100 km/h` / `100-0 km/h` | `PB 0-100 km/h`（紫色 `PB` 前缀，文字主色 white） |
| subtitle | `4.58 s · Today, 10:35` | `4.21 s · May 18, 2024 · Personal Best`（紫色 `Personal Best` 后缀） |
| accentColor | 默认 (cyan) | 紫色 |

**为什么不为 PB 单独设计一个 Composable**：复用 `TrackTechRow`，仅通过参数变化（icon / title / subtitle / accent）渲染差异，避免重复组件。如果 baseline `TrackTechRow` 不支持 accent 参数，不强求扩展，PB 视觉差异就用 leading icon + 副文字差异化即可。

**注意**：`TrackTechRow` baseline API 需 grep 确认接受哪些参数；缺什么参数就用 leadingIcon + subtitle 文字差异化兜底。

### D5：filter icon 占位

**决定**：标题栏 `Records` 文字右侧加一个 `Icons.Filled.FilterAlt`（或 `Icons.Filled.Tune`），尺寸 24dp，主色 `TextSecondary`，clickable lambda 触发 `Toast.makeText(context, "Filter coming next round", Toast.LENGTH_SHORT).show()`。

**为什么不实现**：filter UI 设计未定（按时间 / 按测试类型 / 按赛道），需要单独 round 评审。本 round 仅暴露入口 + Toast 引导用户预期。

### D6：数据 hardcoded 形态

**决定**：所有占位数据用 `private val` 顶层 list / value 定义在 RecordsHomeScreen.kt 文件末尾：

```kotlin
private data class RecentRun(
    val type: String,    // "0-100 km/h" / "100-0 km/h"
    val value: String,   // "4.58 s" / "38.2 m"
    val time: String,    // "Today, 10:35"
    val isPB: Boolean,   // true → 第 3 条
)

private val placeholderRecentRuns: List<RecentRun> = listOf(
    RecentRun("0-100 km/h", "4.58 s", "Today, 10:35", false),
    RecentRun("100-0 km/h", "38.2 m", "Today, 10:32", false),
    RecentRun("0-100 km/h", "4.21 s", "May 18, 2024", true),
)

private data class LapSessionRow(
    val date: String,
    val laps: Int,
    val bestLap: String,
)

private val placeholderLapSessions: List<LapSessionRow> = listOf(
    LapSessionRow("May 18, 2024", 4, "1:32.457"),
    LapSessionRow("May 12, 2024", 6, "1:33.884"),
    LapSessionRow("Apr 29, 2024", 5, "1:34.210"),
)

private data class CurrentTrackRecord(
    val trackName: String,    // "Shanghai Tianma"
    val bestLapTime: String,  // "1:32.457"
    val bestLapDate: String,  // "May 18, 2024"
    val length: String,       // "3.063 km"
    val direction: String,    // "Clockwise"
    val sessions: Int,        // 8
    val totalLaps: Int,       // 56
)

private val placeholderTrackRecord = CurrentTrackRecord(
    trackName = "Shanghai Tianma",
    bestLapTime = "1:32.457",
    bestLapDate = "May 18, 2024",
    length = "3.063 km",
    direction = "Clockwise",
    sessions = 8,
    totalLaps = 56,
)
```

**为什么不用 sealed class / 领域模型**：本 round 不接真实数据，私有数据类即可。后续接 ViewModel 时领域模型再上提。

**为什么不放在外部 stub 文件**：私有数据集中在使用方文件，别处不会误用。

### D7：真机视觉验证 + follow-up backlog

**决定**：本 round 验证完全靠真机肉眼对比渲染图，不写 ComposeRule 测试。验收点：

- PERFORMANCE 视图视觉与渲染图左半 5 区块（标题 / segmented / 3 metric tile / speed curve / recent runs）对齐
- LAPS 视图视觉与渲染图右半 5 区块（标题 / segmented / current track record 大卡 / track info row / 3 metric tile / session history）对齐
- segmented 切换瞬切（不需要动画过渡）

视觉偏差点（如曲线弯度、字体粗细、色彩比例、CutCorner 切角大小）作为 follow-up backlog 记录到 commit message body，不在本 round 内修补。

## Risks / Trade-offs

[**Canvas stub 视觉与真实数据接入后差异大**] → SPEED CURVE 真接历史数据时，曲线形状、坐标轴范围、标注点都会按数据动态计算；当前 stub 是固定形状 + 固定标注。Mitigation：把 Canvas 抽到独立 `@Composable fun SpeedCurveStub()` 而非内联，未来替换为 `SpeedCurveChart(data: List<SpeedSample>)` 时只动调用方一行。

[**赛道 stub 与真实赛道几何不像**] → 真接 TFIC LPCC 等真实赛道时几何形状有具体方向。Mitigation：把赛道预览抽到独立 `@Composable fun TrackPreviewStub()` 而非内联；后续 round 替换为 `TrackPreview(geometry: TrackGeometry)`。

[**"Today, 10:35" 时间戳过期**] → hardcoded 时间字符串后续改成相对时间格式化。Mitigation：本 round 接受占位字符串，commit message 里标注 follow-up。

[**第 3 条 PB 项的 trophy icon 与 baseline `TrackTechRow` API 兼容性**] → 不确定 `TrackTechRow` 是否支持自定义 accent 颜色。Mitigation：实施时 grep 确认，缺参数就用 leadingIcon + subtitle 文字差异化兜底，不扩 baseline API。

[**filter icon Toast 文案被本地化扫描漏掉**] → 项目目前没多语言基础设施，全英文字面量已在 baseline 普遍使用。Mitigation：与 baseline 一致即可，本 round 不引入新约束。

## Migration Plan

无运行时迁移（纯 UI 重构，无数据格式变更）。

实施顺序：

1. 在 RecordsHomeScreen.kt 文件末尾加 `private data class` × 3 + `private val placeholder*` × 3
2. 把现有 `Column { ... }` 内的 PERFORMANCE 内容（3 metric tile + Speed Curve + 5 recent runs）抽到 `@Composable private fun PerformanceView()` 函数，并按 D2/D4 升级 SPEED CURVE Canvas + 改为 3 条 RECENT RUNS（含 PB 高亮）
3. 新建 `@Composable private fun LapsView()`：CURRENT TRACK RECORD 卡片 + Track 信息行 + 3 metric tile + SESSION HISTORY
4. 标题栏右加 filter icon 占位（Row + Spacer + Icon clickable）
5. 主 `Column` body 用 `when (selectedSegment)` 分发到 PerformanceView / LapsView
6. 编译 + grep 自检
7. 真机装机 + 视觉对比

回滚：本 change 是纯 Compose UI 重构，回滚 = 恢复 RecordsHomeScreen.kt 单文件即可。

## Open Questions

无。本 round 决策点已全部在 D1-D7 中拍板。
