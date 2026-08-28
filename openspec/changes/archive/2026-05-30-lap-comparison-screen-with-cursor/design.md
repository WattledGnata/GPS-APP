# Design: lap-comparison-screen-with-cursor

## Context

M3 多圈比较屏组屏（time-axis 第一刀）。底座（reader / 单圈组件画法范本 / 圈源 deriveDetailMetrics / 调色板 TrackTechColors）全部就绪。本 round 新建一个**多圈 speed 叠加组件** + **比较屏** + **路由** + **COMPARE 入口**，不改任何单圈组件 API。执行模式 = road-test-first（去 Codex/Opus 多轮对抗 review；CC 主会话单遍自审 + FileLogger 持久日志 + 真机攒批兜底）。

### 已核实的关键 baseline（#3 grep 锚点对齐，2026-05-30 实跑 grep / read 确认）

- **reader**：`TelemetryRepository.getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?`（`core/data/.../repository/TelemetryRepository.kt:291`）。lapIndex 越界 / wallClock null / binary 缺失/空 → 返回 null（不抛异常）。
- **LapTelemetry**（`core/domain/.../model/LapTelemetry.kt:32`）字段：`sessionId / lapIndex / lapStartWallClock / lapEndWallClock / lapDurationMs / samples / sectorBoundaries / trackId / trackNameSnapshot`。
- **LapTelemetrySample**（同文件 L13）字段：`absoluteTsMs:Long(L14) / elapsedMsInLap:Long(L15) / lat / lon / speedKmh:Double(L18) / bearingDeg / accelerationG=null / flags=0`。**`elapsedMsInLap` 注释（L6）= `absoluteTsMs - lapStartWallClock`（LAP_SESSION 场景圈内流逝时间）**——这是 time-axis 叠加的天然 X 轴键。
- **单圈 SpeedTimeChart 画法范本（只读不改，`SpeedTimeChart.kt:96`）**：
  - `computeChartCoordinates`（L57-78）X 轴用 `elapsedMsInLap`：`x = (sample.elapsedMsInLap / lapDurationMs) × canvasWidth`（L66-67），lapDurationMs = `samples.last().elapsedMsInLap - samples.first().elapsedMsInLap`（L51-52）。Y 轴用 speedKmh min/max + 5% padding（`computeChartBounds` L42-55）。
  - `findNearestSampleIndex(samples, targetElapsedMs)`（L80-93）二分最近邻 `elapsedMsInLap`——本 round 的多圈最近邻逻辑照此思路。
  - 游标 identity 是 `absoluteTsMs`（L121/L140/L168）——**单圈内可命中；跨圈不同圈 absoluteTsMs 完全不同**，故本 round MUST 改用 `elapsedMsInLap` 作跨圈共享游标 identity。
- **圈源**：`LapSessionDetailScreen.deriveDetailMetrics(crossings): DetailMetrics`（`LapSessionDetailScreen.kt:789`，`internal`）。`DetailMetrics.lapRecords: List<UiLapRecord>`（L567），`UiLapRecord(lapNumber, timeMs, diffMs, status, reason)`（L552），`UiLapStatus { BEST, VALID, INVALID, INCOMPLETE }`（L560）。VALID/BEST 圈 `lapNumber = idx+1` 严格对应 `getLapTelemetry(sessionId, lapNumber-1)`（L155 已坐实 `lapIndex = record.lapNumber - 1`）。`bestLapMs`（L808）= durations.min。
- **调色板**：`TrackTechColors`（`TrackTechColors.kt:13`）有 `Purple(0xFF9B5CFF)` / `Cyan(0xFF67E8F9)` / `Green(0xFF76D05E)` / `Red(0xFFF25F5C)` 4 个强调色 + DeepPurple——给 ≤4 圈分色够用。
- **导航**：`TrackTechAppShell.kt:167-203` NavHost；已有 `lap_session_detail/{sessionId}`（L168）/ `lap_detail/{sessionId}/{lapIndex}`（L189）路由范本。`composable(route, arguments=listOf(navArgument(...){type=NavType.X}))`。
- **结构范本（只读）**：`LapDetailScreen.kt`（M2 单圈详情屏：Column + DetailHeader + LaunchedEffect 加载 + null→loaded 用 **if/else 不用 return@Column**——M2 路测 crash 教训 commit 65d6ada：Compose early-return 致重组 group stack 失衡 `IndexOutOfBoundsException at Stack.pop`，本 round MUST 沿用 if/else 分支）。ChartCard / OverviewRow / CutCornerPanel pattern 复用。
- **MetricKind / 字体**：`MetricKind { Mechanical, Score }`（`MetricNumber.kt:18`）。游标瞬时 speed 读数（纯数字仪表）可用 `MetricNumber(kind=MetricKind.Mechanical)`；圈时 / 圈号是时间/文字字符串 MUST Score（`TrackTechTypography.ScoreSmall` 等，`TrackTechTypography.kt:45-54`）。
- **FileLogger**（`feature/test/.../FileLogger.kt`）：`d(tag, message)` / `v(tag, message)` / `e(tag, message, throwable?)`，落 filesDir/debug_log.txt。

## Goals / Non-Goals

**Goals**：用户从 session 详情屏点 COMPARE → 多圈比较屏 → 选 2-4 圈 → 多圈 speed 曲线按 `elapsedMsInLap` 叠加（各圈一色）+ 共享 elapsed-time 游标拖动各圈同步取最近邻读数 + 图例（圈号色块 + 圈时）；不足 2 圈 / null 降级；全程 V2 视觉。

**Non-Goals**：accel/sector/map 多圈叠加（follow-up）；距离轴 alignByDistance / gridIndex（follow-up）；预测 time-delta（follow-up）；改 4 个单圈组件公共 API（MUST NOT）。

## Decisions

### Decision X-Axis: 比较屏 X 轴 = 圈内流逝时间 elapsedMsInLap（time-axis），非距离轴（user 拍板）

**问题**：多圈叠加需要一个跨圈可对齐的 X 轴。两条路线互斥：(a) **时间轴**——各圈按各自圈内流逝时间 `elapsedMsInLap` 对齐叠加，游标是一个 elapsedMs 值，每圈取最近邻；(b) **距离轴**——用 W3 `LapAlignment.alignByDistance` 把各圈按累计距离等距重采样到统一 grid，游标是一个 gridIndex，跨圈走 `gridIndexFor` 距离映射。

**决策（user 拍板）**：选 **(a) 时间轴 `elapsedMsInLap`**。多圈按各自圈内流逝时间叠加；游标 identity 是一个 `cursorElapsedMs: Long`；每圈取 `elapsedMsInLap` 最近邻 sample 高亮。本 round **MUST NOT** 引入 `LapAlignment` / `alignByDistance` / `gridIndex` / 距离重采样。

**Rationale**：
1. **零额外算法依赖**：`LapTelemetrySample.elapsedMsInLap` 已天然带圈内流逝时间（reader L308 `elapsedMsInLap = entity.startTs + sample.tsDeltaMs - lapStartWallClock`）；时间轴叠加直接读字段，不需要 W3 距离重采样。
2. **与既有单圈画法一致**：单圈 `SpeedTimeChart.computeChartCoordinates`（L66-67）本就用 `elapsedMsInLap` 做 X 轴；新组件沿用同一坐标思路，行为可预测、复用现成最近邻二分逻辑。
3. **第一刀最核心价值**：用户最想要的是「这一圈比上一圈在哪一段慢」——速度曲线按圈内时间叠加已足够回答；距离轴的额外精度（弯道位置精确对齐）是增量，不该阻塞第一刀交付。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：距离轴 alignByDistance + gridIndex（旧草案路线）**。拒绝理由：(1) `alignByDistance` 需要 GPS 轨迹累计距离投影 + 等距重采样，是独立算法管线，引入会让组屏 round 体量爆炸（路线图旧草案因此把比较屏标 large 且「X 轴语义未决」）；(2) gridIndex 跨圈映射需改 4 个单圈组件公共 API（路线图 §1.2 已核实组件 cursor 入参只吃 absoluteTsMs），触发 M2 回归 + v3 #16——本 round 既定要避开；(3) user 已拍板时间轴第一刀。距离轴留 §10 follow-up。
- **Alt B（采纳）：时间轴 elapsedMsInLap**。采纳。

**Trade-off（透明声明）**：时间轴下，两圈在同一 elapsedMs 不一定在赛道同一物理位置（圈 A 某弯慢 0.5s 后，后续 elapsedMs 处圈 A 已落后于圈 B 的赛道位置）。这是时间轴比较的固有语义（RaceChrono 的 "time" 模式同理），用户可理解为「同样跑了 N 秒时各圈速度」。距离轴的「同物理位置对比」是 follow-up 增量，本 round 不承诺。

### Decision New-Component: 新建 MultiLapSpeedChart，MUST NOT 改单圈组件 API

**问题**：多圈叠加是新画法（吃 `List<LapSeries>` + cursorElapsedMs）。是改造单圈 `SpeedTimeChart` 让它吃多圈，还是新建独立组件？

**决策**：**新建** `MultiLapSpeedChart`（`feature/test/.../ui/components/MultiLapSpeedChart.kt`），独立 Composable + 独立入参契约 `List<LapSeries>` + `cursorElapsedMs: Long?` + `onCursorChange: (Long) -> Unit`。**MUST NOT** 修改 `SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap` 的任何签名或行为。

**`LapSeries` data class**（新建，放 MultiLapSpeedChart.kt）：
```kotlin
data class LapSeries(
    val lapNumber: Int,
    val color: Color,       // 调色板分配
    val samples: List<LapTelemetrySample>,
)
```

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：改造 SpeedTimeChart 吃 `List<List<LapTelemetrySample>>` + 可变 cursor 类型**。拒绝理由：(1) M2 单圈详情屏的 4 组件依赖 `SpeedTimeChart(samples, cursorAbsoluteTs, onCursorChange)` 现签名——改签名直接回归 M2 详情屏（`LapDetailScreen.kt:141` 调用点）；(2) 游标 identity 从 `absoluteTsMs` 改 `elapsedMsInLap` 是行为级变更，单圈/多圈语义不同，硬塞一个组件会让两边都别扭；(3) 触发 v3 #16（共享组件 API 被跨 round 消费方依赖，扩字段/改签名须 drift 检查 + 升级 medium）。CLAUDE.md M3 任务卡明确「不改 M2 单圈组件」。
- **Alt B（采纳）：新建独立组件**。采纳。零回归风险，两组件各自演进。单圈最近邻二分逻辑（`findNearestSampleIndex`）思路可在新组件内重新实现（不跨文件复用 internal，避免耦合）。

### Decision Cursor: 共享 cursorElapsedMs（Long）state hoisting + 每圈各自最近邻

**问题**：游标要让多圈在「同一圈内流逝时间」同步高亮。游标 identity 必须是跨圈通用的标量。

**决策**：在 `LapComparisonScreen` hoist `var cursorElapsedMs by remember { mutableStateOf<Long?>(null) }` 作为 single source of truth：
- `MultiLapSpeedChart` 的 `onCursorChange = { cursorElapsedMs = it }`（用户在叠图上拖动 → 把触摸点映射到 elapsedMs 回写）。
- `MultiLapSpeedChart` 的 `cursorElapsedMs` 入参传同一 hoisted state。
- **每圈各自最近邻**：组件内对每个 `LapSeries`，用其自己的 samples 求 `elapsedMsInLap` 最近邻 sample（二分，mirror `findNearestSampleIndex` 思路）。不同圈在同一 cursorElapsedMs 下取各自圈内最近的那一帧——这正是 time-axis 比较语义。
- 图例 / 读数区也读同一 cursorElapsedMs，对每圈展示其最近邻 sample 的 speed。

**为什么用 elapsedMsInLap 而非 absoluteTsMs（关键差异 vs M2）**：M2 单圈内 4 组件吃同一份 samples，absoluteTsMs 唯一一致，精确相等可命中。M3 跨圈 samples 是不同圈、不同 absoluteTsMs 域，精确相等永远 miss。`elapsedMsInLap`（圈内归零的流逝时间）才是跨圈可对齐的公共坐标。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：复用 absoluteTsMs 游标 + 各圈独立 state**。拒绝理由：跨圈 absoluteTsMs 不同域，精确相等 miss → 拖一圈别的圈不动，破坏「多圈同步」契约。
- **Alt B（采纳）：cursorElapsedMs + 每圈最近邻**。采纳。

### Decision Lap-Selection: chips 多选 2-4 圈，默认最快 + 最多 3 个其他 valid 圈

**问题**：一个 session 可能有很多圈，叠太多曲线会糊（颜色不够 + 视觉拥挤）。需要圈选择 UI + 默认值。

**决策**：
- **圈选择 UI**：从 `deriveDetailMetrics(crossings).lapRecords` 过滤出 VALID/BEST 圈（有 `lapNumber` + 非 null `timeMs`），渲染为可多选 chips（每 chip：Lap N + 圈时 + 选中态色块）。
- **选择约束**：MUST 选 2-4 圈（下限 2 才有比较意义，上限 4 对应调色板 4 色 + 避免拥挤）。
- **默认选择**：BEST 圈（`UiLapStatus.BEST`）+ 按圈时升序的最多 3 个其他 VALID 圈，合计 ≤4。若 VALID/BEST 圈 < 2，进降级态（"SELECT 2+ LAPS TO COMPARE" / session 圈不足）。
- **调色板分配**：选中圈按选中顺序分配 `[Purple, Cyan, Green, Red]`（BEST 圈优先 Purple，与圈列表 BEST 紫色语义一致）。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：固定对比 BEST vs 上一圈两圈**。拒绝理由：用户常想看 3-4 圈趋势（如「连续 3 圈是否稳定」），固定 2 圈太死。
- **Alt B（拒绝）：无上限全选**。拒绝理由：>4 圈调色板不够 + 叠图糊 + 25Hz×多圈渲染压力。
- **Alt C（采纳）：2-4 圈多选 + 默认最快+3**。采纳。覆盖主场景且控制复杂度。

### Decision X-Y-Scale: 多圈统一 X/Y 尺度（叠图可比）

**问题**：各圈圈时不同（lapDuration 不同）、速度范围不同。叠图要让曲线可比，X/Y 必须统一尺度而非各圈自归一。

**决策**：
- **X 轴尺度**：`maxElapsedMs = 所有选中圈 samples 的 elapsedMsInLap 最大值`（= 最长圈的 lapDuration）。每圈曲线 `x = elapsedMsInLap / maxElapsedMs × canvasWidth`——短圈曲线右端不到边（提前结束，符合「短圈先跑完」直觉）。
- **Y 轴尺度**：`speedMin/speedMax = 所有选中圈所有 sample 的 speedKmh 全局 min/max`（+5% padding，mirror 单圈 computeChartBounds）。各圈用同一 Y 尺度——速度高低直接可比。
- 抽 internal 纯函数 `computeMultiLapBounds(series: List<LapSeries>): MultiLapBounds`（含 maxElapsedMs / speedMin / speedMax），便于 JVM 单测断言尺度统一。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：各圈独立归一**。拒绝理由：各圈 X 撑满 canvas 宽 → 圈时差异被抹平（短圈和长圈一样宽），失去时间轴比较意义；各圈 Y 独立 → 速度高低不可比。
- **Alt B（采纳）：统一 X（最长圈）+ 统一 Y（全局）**。采纳。

## Risks / Trade-offs

### Risk 1: 25Hz × 多圈全量渲染性能

**数据**：25Hz × 典型圈 1-3min ≈ 1500-4500 samples/圈 × 最多 4 圈 ≈ 最多 ~18000 sample 点一次性 Canvas 画 4 条 polyline。

**评估**：Compose Canvas 画 4 条各数千 lineTo 的 Path 是一次性 draw（非逐帧重建）；游标拖动重组时每圈 O(n) 二分最近邻（log n 实际）。现代真机（华为 8KE0219522008434 / vivo V2405A）首版可接受。M2 单圈已验证单条 polyline 流畅；多圈是常数倍（≤4）。

**Mitigation / Defer**：首版**不做**降采样。复用 M2 已立的 future round `chart-downsample-virtualization`（archive/2026-05-30-lap-detail-screen-with-cursor tasks §10.1）——多圈是同一降采样课题的放大，本 round §10 backlog link 该 round 并注明「多圈放大了触发条件」。若真机攒批路测发现 4 圈长圈滑动卡顿/OOM 则启动。**不留悬空 risk**（§5.3）。

### Risk 2: 选中圈 < 2 / getLapTelemetry 返回 null 降级

**描述**：session VALID/BEST 圈 < 2（无法比较）；或某选中圈 `getLapTelemetry` 返回 null（数据竞态 / 越界）。

**Mitigation**：
- 圈源 < 2 VALID/BEST 圈：COMPARE 入口本身 disabled（第一层）；进屏后渲染显式降级占位 "SELECT 2+ LAPS TO COMPARE"，不崩溃。
- 某选中圈加载 null：从 `LapSeries` 列表里 skip 该圈（`mapNotNull`）+ `FileLogger.e` 记 sessionId + lapNumber + null 原因；若 skip 后剩余 < 2 圈则回降级态。

### Risk 3: Compose early-return 重组 crash（M2 路测教训复发）

**描述**：M2 路测崩溃 root cause（commit 65d6ada）= null→loaded 分支用 `return@Column` 致重组 Compose group stack 失衡 `IndexOutOfBoundsException at Stack.pop`。

**Mitigation**：`LapComparisonScreen` 的 null/不足/loaded 分支 **MUST 用 if/else 而非 early-return**（沿用 M2 修复后的 `LapDetailScreen.kt:117-207` if/else 结构）。本 round design 显式锁此约束 + tasks done condition 核对。

### Risk 4: 跨 phase / 跨 round 文件占用

**描述**：本 round 碰共享文件 `TrackTechAppShell.kt`（加 route）+ `LapSessionDetailScreen.kt`（加 COMPARE 入口）。

**Mitigation**：看板 §5 确认 M2 已合回、当前无并行 round 占用这两文件。本 round **不碰** `LapLiveScreen.kt`（无 redesign-delta / Phase 2 camera 冲突）。合回时 §6 登记这两个共享文件。新组件 `MultiLapSpeedChart.kt` + 新屏 `LapComparisonScreen.kt` 是独占新建。

## Migration Plan

无 schema 改动 / 无数据迁移。纯新增组件 + 新增屏 + 路由 + 入口。向后兼容：现有 `lap_session_detail` / `lap_detail` 路由 + 单圈组件行为 0 变化（只是 session 详情屏多了 COMPARE 入口）。

## Open Questions

无。X-Axis（时间轴）/ New-Component（新建不改单圈）/ Cursor（cursorElapsedMs 最近邻）/ Lap-Selection（2-4 圈默认最快+3）/ X-Y-Scale（统一尺度）决策均已由 user 拍板（time-axis 第一刀）+ road-test-first 下 CC 拍板。
