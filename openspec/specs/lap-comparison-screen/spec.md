# lap-comparison-screen Specification

## Purpose
TBD - created by archiving change lap-comparison-screen-with-cursor. Update Purpose after archive.
## Requirements
### Requirement: MultiLapSpeedChart 按 elapsedMsInLap 时间轴叠加多圈 speed 曲线

`MultiLapSpeedChart`（`feature/test/.../ui/components/MultiLapSpeedChart.kt`，新建）SHALL 把传入的多个 `LapSeries` 的 speed vs `elapsedMsInLap` 折线叠加在同一 Canvas 上（各圈一色），X 轴用圈内流逝时间 `elapsedMsInLap`（time-axis），统一 X/Y 尺度使各圈可比。

实现 MUST 满足：

1. **入参契约**：组件签名 MUST 为 `MultiLapSpeedChart(series: List<LapSeries>, cursorElapsedMs: Long?, onCursorChange: (Long) -> Unit, modifier: Modifier)`；`LapSeries(lapNumber: Int, color: Color, samples: List<LapTelemetrySample>)`。
2. **time-axis**：每圈曲线 X 坐标 MUST 由 `sample.elapsedMsInLap` 派生（`x = elapsedMsInLap / maxElapsedMs × canvasWidth`），**MUST NOT** 用 `absoluteTsMs` 或 `LapAlignment.alignByDistance` / `gridIndex` 距离映射。
3. **统一尺度**：X 轴尺度 MUST 用所有 series 的全局 `elapsedMsInLap` 最大值（最长圈 lapDuration）；Y 轴尺度 MUST 用所有 series 全局 speedKmh min/max（+padding），使各圈在同一坐标系可比，**MUST NOT** 各圈独立归一。
4. **各圈一色**：每个 `LapSeries.color` MUST 用于其折线绘制（不同圈不同色）。
5. **MUST NOT 改单圈组件**：本 round **MUST NOT** 修改 `SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap` 的签名或行为（M2 单圈详情屏依赖它们）。

#### Scenario: 两圈 speed 曲线按 elapsedMsInLap 叠加

- **GIVEN** 两个 `LapSeries`（Lap1 color=Purple samples / Lap2 color=Cyan samples），各 samples 的 `elapsedMsInLap` 从 0 升序
- **WHEN** 渲染 `MultiLapSpeedChart(series=[lap1, lap2], cursorElapsedMs=null, ...)`
- **THEN** Canvas 画两条 polyline，X 坐标由各 sample 的 `elapsedMsInLap / maxElapsedMs × canvasWidth` 决定（time-axis）
- **AND** Lap1 用 Purple、Lap2 用 Cyan（各圈一色）
- **AND** 两圈共用同一 Y 尺度（全局 speedKmh min/max），速度高低直接可比

#### Scenario: 圈时不同时短圈曲线右端不到边（统一 X 尺度）

- **GIVEN** Lap1 lapDuration=90000ms、Lap2 lapDuration=95000ms（maxElapsedMs=95000）
- **WHEN** 渲染叠图
- **THEN** Lap2（最长圈）曲线右端到达 canvas 右边缘（elapsedMsInLap=95000 → x=canvasWidth）
- **AND** Lap1 曲线右端在 90000/95000 ≈ 0.947×canvasWidth 处提前结束（短圈先跑完，符合 time-axis 直觉）

#### Scenario: 反例——MultiLapSpeedChart MUST NOT 改单圈 SpeedTimeChart API 或用距离映射

- **GIVEN** M2 单圈详情屏 `LapDetailScreen.kt:141` 依赖 `SpeedTimeChart(samples, cursorAbsoluteTs, onCursorChange, modifier)` 现签名
- **WHEN** contract test 扫描 `SpeedTimeChart.kt` 签名 + `MultiLapSpeedChart.kt` 源文件
- **THEN** `SpeedTimeChart.kt` 的 `cursorAbsoluteTs: Long?` 参数签名 MUST 仍存在（未被本 round 改）
- **AND** `MultiLapSpeedChart.kt` **MUST NOT** 出现 `alignByDistance` / `gridIndexFor` / `LapAlignment` 字面量（time-axis 不用距离映射）
- **AND** 若实现改了 SpeedTimeChart 签名让它吃多圈，M2 详情屏调用点编译失败 / 行为回归 → 违反「不改单圈组件」契约，contract test fail

### Requirement: 共享 cursorElapsedMs 游标拖动时各圈按 elapsedMsInLap 最近邻同步读数

`LapComparisonScreen` SHALL hoist 单一 `cursorElapsedMs: Long?` state，使在叠图上拖动游标时，每个选中圈按其自己的 samples 求 `elapsedMsInLap` 最近邻 sample，同步高亮 + 图例区显示各圈在该流逝时间处的 speed。

实现 MUST 满足：

1. **state hoisting**：MUST 在 `LapComparisonScreen` 持有 `var cursorElapsedMs by remember { mutableStateOf<Long?>(null) }` 作为 single source of truth。
2. **发起方**：`MultiLapSpeedChart` 的 `onCursorChange` MUST 回写同一 hoisted state（`onCursorChange = { cursorElapsedMs = it }`）；触摸 X 坐标 MUST 映射回 elapsedMs（`touchX / canvasWidth × maxElapsedMs`）。
3. **每圈各自最近邻**：MUST 对每个 `LapSeries` 用其自己 samples 求 `elapsedMsInLap` 最近邻（二分），不同圈在同一 cursorElapsedMs 下各取各圈内最近的帧，**MUST NOT** 用 `absoluteTsMs` 精确相等（跨圈不同域永远 miss）。
4. **图例读数**：游标非 null 时图例区 MUST 对每圈显示「Lap N 色块 + 该圈最近邻 sample 的 speed（瞬时数字可 Mechanical）+ 圈时（Score）」。
5. **抽纯函数可测**：最近邻逻辑 MUST 抽 internal 纯函数（如 `nearestSampleByElapsed(samples, targetElapsedMs): LapTelemetrySample?`），便于 JVM 单测断言。

#### Scenario: 拖动游标各圈按 elapsedMsInLap 最近邻同步

- **GIVEN** `LapComparisonScreen` 加载 2 圈，cursorElapsedMs 初值 null
- **WHEN** 用户在叠图上拖动到对应 elapsedMs=30000ms 处，触发 `onCursorChange(30000)`
- **THEN** hoisted `cursorElapsedMs` 更新为 30000
- **AND** Lap1 取其 samples 中 `elapsedMsInLap` 最近 30000 的 sample、Lap2 取其 samples 中 `elapsedMsInLap` 最近 30000 的 sample（两圈各自最近邻，可能是不同 absoluteTsMs / 不同 index 的帧）
- **AND** 图例区显示两圈在 30000ms 处各自的 speed

#### Scenario: 两圈采样密度不同时各取各圈最近邻

- **GIVEN** Lap1 在 30000ms 附近有 sample @29960/30000、Lap2 因掉帧在 30000ms 附近只有 @29800/30200
- **WHEN** cursorElapsedMs=30000
- **THEN** Lap1 命中 @30000（最近）、Lap2 命中 @30200（|30200-30000|=200 < |29800-30000|=200，相等取后者或前者由实现定，但 MUST 是该圈内最近邻，非跨圈精确相等）
- **AND** 不因 Lap2 没有恰好 30000 的 sample 而漏标（精确相等会 miss，最近邻不会）

#### Scenario: 反例——游标 MUST NOT 用 absoluteTsMs 精确相等做跨圈匹配

- **GIVEN** `LapComparisonScreen.kt` / `MultiLapSpeedChart.kt` 渲染多圈游标联动
- **WHEN** contract test 扫描源文件
- **THEN** 跨圈游标匹配 MUST 基于 `elapsedMsInLap` 最近邻（源文件含 `nearestSampleByElapsed` / `elapsedMsInLap` 匹配逻辑）
- **AND** **MUST NOT** 出现 `samples.find { it.absoluteTsMs == cursor` 形式的跨圈精确相等匹配（不同圈 absoluteTsMs 不同域，会让其他圈永不联动）
- **AND** 若实现误用 absoluteTsMs 精确相等，拖一圈别的圈不动 → 违反「多圈同步」契约，真机路测暴露 + contract test（无 absoluteTsMs 精确相等跨圈匹配）fail

### Requirement: 圈选择 chips 多选 2-4 圈，默认最快 + 最多 3 个其他 valid 圈

`LapComparisonScreen` SHALL 从 session 的 VALID/BEST 圈提供可多选 chips（2-4 圈），默认选 BEST 圈 + 按圈时升序最多 3 个其他 VALID 圈，并按选中顺序分配调色板颜色。

实现 MUST 满足：

1. **圈源**：MUST 从 `deriveDetailMetrics(crossings).lapRecords` 过滤 `UiLapStatus.VALID` / `UiLapStatus.BEST` 且 `timeMs != null` 的圈作为可选圈，每圈 `lapNumber` 对应 `getLapTelemetry(sessionId, lapNumber-1)`。
2. **选择约束**：选中圈数 MUST 在 [2, 4]；UI MUST 阻止选 < 2（无法比较）或 > 4（调色板 4 色 + 避免拥挤）。
3. **默认选择**：默认 MUST 选 BEST 圈 + 圈时升序最多 3 个其他 VALID 圈（合计 ≤4）；若可选圈 < 2 进降级态。
4. **调色板**：选中圈 MUST 按选中顺序分配 `[Purple, Cyan, Green, Red]`（BEST 圈优先 Purple，与圈列表 BEST 紫色语义一致）；MUST 抽 internal 纯函数 `assignLapColors(selectedLapNumbers): List<Color>` 便于单测。
5. **圈时字体**：chip 内圈时（时间字符串 `m:ss.SSS`）MUST 用 Score 字体，**MUST NOT** 用 Mechanical（DSEG7）。

#### Scenario: 默认选 BEST + 最多 3 个其他 valid 圈

- **GIVEN** session 有 5 个 VALID/BEST 圈（Lap2=BEST 88.5s / Lap1=89.1s / Lap4=89.8s / Lap3=90.2s / Lap5=91.0s）
- **WHEN** 进入 `LapComparisonScreen`，计算默认选择
- **THEN** 默认选中 Lap2(BEST) + 圈时升序前 3 个其他 valid（Lap1 / Lap4 / Lap3），合计 4 圈
- **AND** Lap2(BEST) 分配 Purple，其余按选中顺序 Cyan / Green / Red

#### Scenario: 圈数恰好 2 仍可比较

- **GIVEN** session 只有 2 个 VALID/BEST 圈
- **WHEN** 进入比较屏
- **THEN** 两圈都默认选中（满足下限 2）
- **AND** 叠图正常渲染 2 条曲线

#### Scenario: 反例——可选圈 < 2 走降级态不渲染叠图

- **GIVEN** session 只有 1 个 VALID 圈（其余 INVALID/INCOMPLETE）
- **WHEN** 进入 `LapComparisonScreen`
- **THEN** 屏幕渲染显式降级占位（"SELECT 2+ LAPS TO COMPARE" 或 session 圈不足提示），**MUST NOT** 渲染只有 1 条曲线的叠图（无比较意义）
- **AND** **MUST NOT** 崩溃、**MUST NOT** 白屏
- **AND** `FileLogger` 记录可选圈数 < 2 降级原因
- **AND** 若实现允许选 1 圈渲染单曲线叠图，违反「2-4 圈」约束，contract test（降级占位字面量存在 + 选择下限断言）fail

### Requirement: 比较屏加载、降级与 COMPARE 入口导航

`LapComparisonScreen` SHALL 用 `LaunchedEffect(sessionId, selectedLapNumbers)` 对每个选中圈调 `getLapTelemetry` 构造 `List<LapSeries>`；`LapSessionDetailScreen` SHALL 提供 COMPARE 入口导航到 `lap_comparison/{sessionId}`；`TrackTechAppShell` SHALL 注册该路由。

实现 MUST 满足：

1. **加载入口**：MUST 用 `LaunchedEffect(sessionId, selectedLapNumbers)` 对每个选中 lapNumber 调 `getLapTelemetry(sessionId, lapNumber-1)`，null 的圈 `mapNotNull` skip（+ FileLogger.e），构造 `List<LapSeries>`。
2. **路由注册**：`TrackTechAppShell.kt` MUST 注册 `composable("lap_comparison/{sessionId}", arguments = [navArgument("sessionId"){StringType}])`，实例化 `LapComparisonScreen(sessionId, ...)`。
3. **COMPARE 入口**：`LapSessionDetailScreen` MUST 加 COMPARE 入口（按钮/section），仅在有 ≥2 个 VALID/BEST 圈时可点，点击 navigate `lap_comparison/$sessionId`。
4. **降级不崩溃**：skip 后剩余 < 2 圈 MUST 渲染降级占位，不崩溃不白屏。null/不足/loaded 分支 **MUST 用 if/else 而非 early-return**（M2 路测 crash 教训 commit 65d6ada）。
5. **FileLogger 埋点**：各圈加载成功（含 samples 数）/ null skip（含 lapNumber + 原因）/ 圈选择变更 / 游标转移 MUST 各埋 `FileLogger.d/e/v`（road-test-first 兜底）。

#### Scenario: COMPARE 入口导航到 lap_comparison 路由

- **GIVEN** `LapSessionDetailScreen` sessionId=S1 有 4 个 VALID/BEST 圈，COMPARE 入口可点
- **WHEN** 用户点 COMPARE 入口
- **THEN** 导航到 `lap_comparison/S1`
- **AND** `TrackTechAppShell` 路由解析 sessionId="S1"，实例化 `LapComparisonScreen("S1", ...)`
- **AND** `LapComparisonScreen` LaunchedEffect 对默认选中圈调 `getLapTelemetry("S1", lapNumber-1)`

#### Scenario: 某选中圈 getLapTelemetry 返回 null 时 skip 不崩溃

- **GIVEN** 选中 3 圈，其中 Lap5 因数据竞态 `getLapTelemetry(S1, 4)` 返回 null
- **WHEN** LaunchedEffect 构造 LapSeries
- **THEN** Lap5 被 `mapNotNull` skip，剩 2 圈仍 ≥2 → 正常渲染 2 条曲线
- **AND** `FileLogger.e` 记录 sessionId=S1 + lapNumber=5 + null 原因
- **AND** 不崩溃

#### Scenario: 反例——null/loaded 分支 MUST NOT 用 early-return（M2 crash 教训）

- **GIVEN** `LapComparisonScreen.kt` 的 series 为空/不足/loaded 分支渲染
- **WHEN** contract test 扫描 `LapComparisonScreen.kt`
- **THEN** 分支切换 MUST 用 if/else 结构，**MUST NOT** 在 `Column { ... }` 内用 `return@Column` 提前返回
- **AND** 若实现用 `return@Column`，Compose 重组时 group stack 失衡 → `IndexOutOfBoundsException at Stack.pop`（M2 commit 65d6ada 路测崩溃复发）→ 违反「不崩溃」契约，contract test（无 `return@Column` 在 Column 体内）+ 真机路测暴露

### Requirement: V2 视觉约束（单行 Ellipsis + DSEG7 仅仪表瞬时数字）

`LapComparisonScreen` 与 `MultiLapSpeedChart` SHALL 全程遵守 Track Tech V2 视觉约束。

实现 MUST 满足：

1. **单行 Ellipsis**：两文件内所有直接 `Text(...)` 调用（标题 / chip 标签 / 图例 label-value / 降级占位）MUST 加 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`。
2. **DSEG7 边界**：DSEG7（Mechanical）仅用于纯数字仪表瞬时读数（游标处各圈瞬时 speed 数字可 Mechanical）；圈时 / 圈号 / track name MUST NOT 用 Mechanical。
3. **布局 weight 约束**：含可变长度文本的 Row（如图例 Lap N + 圈时）MUST 配 weight 约束（不裸用 SpaceBetween 撑爆截断）。

#### Scenario: 所有直接 Text 单行 Ellipsis

- **GIVEN** `LapComparisonScreen.kt` + `MultiLapSpeedChart.kt` 内所有直接 `Text(...)` 调用
- **WHEN** contract test 用括号配平扫描每个 `Text(` 块（mirror LapDetailScreenContractTest.collectTextBlocksMissingMaxLines）
- **THEN** 每个块 MUST 同时含 `maxLines = 1` 与 `TextOverflow.Ellipsis`
- **AND** 缺任一的块数 == 0

#### Scenario: 游标瞬时 speed 数字可 Mechanical，圈时 MUST Score

- **GIVEN** 图例区显示某圈游标处瞬时 speed（纯数字仪表读数）+ 圈时（时间字符串）
- **WHEN** 渲染这两类字段
- **THEN** 瞬时 speed 数字可走 `MetricNumber(kind = MetricKind.Mechanical)`（DSEG7 纯数字 OK）
- **AND** 圈时（含冒号/小数点的 `m:ss.SSS`）MUST 用 Score 字体，**MUST NOT** 用 Mechanical（DSEG7 七段吃带冒号时间串会字符变形）

#### Scenario: 反例——长 track name / 多圈图例 MUST 触发 Ellipsis 而非换行

- **GIVEN** 图例一行含 Lap N 色块 + 圈时 + （可选）track name
- **WHEN** 该行在 bounded width（尤其 vivo V2405A 小屏）内测量
- **THEN** 该行布局 MUST 配 weight 约束（可变文本 `weight(1f, fill=false)` + 单行 Ellipsis），长内容截断为省略号
- **AND** 若实现裸用 `Arrangement.SpaceBetween` 无 weight，长内容撑爆/换行 → 违反 V2 严格单行约束，真机小屏 gate 暴露

