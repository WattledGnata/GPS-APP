# Design: lap-detail-screen-with-cursor

## Context

M2 单圈详情屏组屏。底座（reader / 4 组件 / AccelerationSmoother / sector 多段派生）全部就绪，本 round 只做组屏 + 接线 + 导航。执行模式 = road-test-first（去 Codex/Opus 多轮对抗 review；CC 主会话单遍自审 + FileLogger 持久日志 + 真机攒批兜底）。

### 已核实的关键 baseline（#3 grep 锚点对齐，2026-05-30 实际跑 grep / read 确认）

- **reader**：`TelemetryRepository.getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?`（`core/data/.../repository/TelemetryRepository.kt:291`）。
  - L313 `accelerationG = null`（reader 硬编码，所有 sample 的 accelerationG 恒 null）。
  - L329-337 `sectorBoundaries = listOf(lapStartWallClock) + sectorWallClocks`（future-sector-derivation 已合回，多段）。
  - lapIndex 越界 / wallClock null / binary 缺失/空 → 返回 null（不抛异常）。
- **LapTelemetry**（`core/domain/.../model/LapTelemetry.kt:32`）字段：`sessionId / lapIndex / lapStartWallClock / lapEndWallClock / lapDurationMs / samples / sectorBoundaries / trackId / trackNameSnapshot`。
- **LapTelemetrySample**（同文件 L13）字段：`absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG=null / flags=0`。
- **组件签名**（实际 read 确认）：
  - `SpeedTimeChart(samples, cursorAbsoluteTs: Long?, onCursorChange: (Long) -> Unit, modifier)`（`SpeedTimeChart.kt:96`）——发起游标变更。
  - `AccelTimeChart(samples, cursorAbsoluteTs: Long?, onCursorChange: (Long) -> Unit, modifier)`（`AccelTimeChart.kt:29`）——发起游标变更。
  - `SectorBar(sectorBoundaries: List<Long>, lapStartWallClock: Long, lapEndWallClock: Long, cursorAbsoluteTs: Long?, modifier)`（`SectorBar.kt:38`）——**只消费游标，无 onCursorChange**。
  - `TrackPolylineMap(samples, cursorAbsoluteTs: Long?, modifier)`（`TrackPolylineMap.kt:63`）——**只消费游标，无 onCursorChange**。
- **游标命中**：`SpeedTimeChart.kt:121/168` / `AccelTimeChart.kt:50/93` / `TrackPolylineMap.kt:84` 用 `samples.find/indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }`；`SectorBar.kt:50` 用 `(cursorAbsoluteTs - lapStartWallClock)/lapDuration` 时间分数。单圈内 4 组件共享同一 samples list → 同一 `absoluteTsMs` 精确相等命中。
- **AccelerationSmoother**（`core/domain/.../usecase/AccelerationSmoother.kt:22`）：`compute(samples: List<TimedSpeedSample>): List<Double>`，输入 `TimedSpeedSample(timestamp: Long, speedKmh: Double)`，返回 m/s² 加速度数组（与输入索引一一对应，size == 输入 size）；`const GRAVITY_MS2 = 9.81`（同文件 L117，转 G 用）。n<=1 返回 `List(n){0.0}`。
- **导航**：`TrackTechAppShell.kt:167-187` NavHost；`LapSessionDetailScreen.LapRecordRow`（`LapSessionDetailScreen.kt:338`）无 onClick；`deriveDetailMetrics`（同文件 L488）VALID/BEST 圈 `lapNumber = idx+1`（idx == acceptedSF zipWithNext index == `getLapTelemetry` lapIndex），INVALID 圈 lapNumber 合成在 durations.size 之后且 timeMs=null。
- **结构范本**：`PerformanceResultScreen.kt`（V2 详情屏含 charts 的现成 pattern：Column + DetailHeader + CutCornerPanel hero + MetricRow + Card 包 chart + LazyColumn）。
- **FileLogger**（`feature/test/.../FileLogger.kt:42`）：`d(tag, message)` / `v(tag, message)` / `e(tag, message, throwable?)`，落 filesDir/debug_log.txt。

## Goals / Non-Goals

**Goals**：用户点圈 → 单圈详情屏 → 4 组件回放 + 共享游标拖动联动；accelerationG 在 UI 层从 speedKmh 反算非空喂 AccelTimeChart；sectorBoundaries 多段画多段；V2 视觉全程遵守。

**Non-Goals**：跨圈比较（M3）；降采样虚拟化（defer）；改组件公共 API；改 reader / 数据契约。

## Decisions

### Decision R1: accelerationG 在 UI 数据准备层用 AccelerationSmoother 反算（不改 reader）

**问题**：`getLapTelemetry` 返回的 sample.accelerationG 恒 null（reader L313 硬编码），`AccelTimeChart` 入口 `samples.all { it.accelerationG == null }` → 永远显示 "NO ACCEL DATA"（`AccelTimeChart.kt:35`）。要让 AccelTimeChart 有曲线，必须在某处填充 accelerationG。

**决策**：在 `LapDetailScreen` 的 UI 数据准备层（`remember(lapTelemetry)` 块内）用 `AccelerationSmoother.compute` 从 samples 的 `speedKmh` + `absoluteTsMs` 序列反算每个 sample 的加速度（m/s²），`/ GRAVITY_MS2` 转 G，构造新的 sample 列表（`sample.copy(accelerationG = gValue)`），把这份带 accelerationG 的列表只喂给 `AccelTimeChart`。SpeedTimeChart / SectorBar / TrackPolylineMap 用原始 samples（它们不读 accelerationG）。

**抽纯函数**：派生逻辑抽 internal 纯函数 `deriveAccelerationG(samples: List<LapTelemetrySample>): List<LapTelemetrySample>`（放 `LapDetailScreen.kt` internal，不引 androidx 依赖），便于 JVM 单测断言（spec scenario「accelerationG 非空喂 AccelTimeChart」可真断言，而非纯文本 grep）。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：在 reader `getLapTelemetry` 内填充 accelerationG**。拒绝理由：(1) `getLapTelemetry` 是公共 reader 契约，已被 W2/W3 消费方依赖（LapTelemetrySample.accelerationG 语义当前是「null = 未填」）；改填充语义会命中 v3 #16 跨 round 共享字段语义扩展 + 强制升级 medium 流程 + 触发 W2/W3 drift mini-review（CLAUDE.md `Round 复杂度分级` 强制升级 5 例外场景 #3 公共协议 / #5 派生 follow-up）。M2 既定目标是「纯组屏，不再触发 #16」（任务卡明确）。(2) reader 在 `core/data`，引入 `AccelerationSmoother`（`core/domain`）依赖会让数据层耦合 UI 平滑算法，违反「reader 只读不算」边界。
- **Alt B（拒绝）：每帧实时计算（不缓存）**。拒绝理由：accelerationG 派生是 O(n) SG 平滑，每次重组都跑会浪费；`remember(lapTelemetry)` 缓存一次即可（lapTelemetry 只在 LaunchedEffect 加载一次）。
- **Alt C（采纳的变体）：UI 层派生 + remember 缓存 + 抽纯函数单测**。采纳。

**协同关系**：与 `smooth-perftest-acceleration-curve`（archive/2026-05-03，建立 AccelerationSmoother）+ `PerformanceResultScreen` 的 GForceChart 一致——后者也是 UI 层（GForceChart 内部）接 smoother 从 speed 派生 G，本 round 复用同一模式但喂 W2 的 AccelTimeChart。

### Decision R2: sectorBoundaries 直接消费 getLapTelemetry 返回的多段（不改 reader）

**问题**：旧 baseline `getLapTelemetry` 曾写死 `sectorBoundaries = listOf(lapStartWallClock)`（单元素），SectorBar 只能画 1 段。

**决策**：`future-sector-derivation` round 已归档（archive/2026-05-29），`getLapTelemetry` 现返回多段 sectorBoundaries（L329-337：lapStart + 窗口内升序 accepted Sector wallClock）。`LapDetailScreen` 直接把 `lapTelemetry.sectorBoundaries` + `lapStartWallClock` + `lapEndWallClock` + `cursorAbsoluteTs` 传给 `SectorBar`，画多段。**本 round 不改 reader**。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：本 round 内做 sector 派生**。拒绝理由：会与 `future-sector-derivation` 重复（已归档）+ 改 reader 触发 #16。排序修正（路线图 §4 第二批）已要求 sector 派生先行/并入，现已先行归档，detail 屏直接消费即可。
- **Alt B（采纳）：消费已合回的多段**。采纳。无 sector 门赛道 / debug 宽容闭合时 reader 回退单段 `listOf(lapStartWallClock)`，SectorBar 拿单元素画 1 段全圈条（与 baseline 行为一致，不回归）。

### Decision Cursor: 共享 cursorAbsoluteTs state hoisting + 精确相等匹配

**问题**：4 个组件要在游标拖动时同步高亮同一时间点。其中 SpeedTimeChart / AccelTimeChart 发起游标变更（有 onCursorChange），SectorBar / TrackPolylineMap 只消费。

**决策**：在 `LapDetailScreen` hoist 一个 `var cursorAbsoluteTs by remember { mutableStateOf<Long?>(null) }`，作为 single source of truth：
- SpeedTimeChart / AccelTimeChart 的 `onCursorChange = { cursorAbsoluteTs = it }`。
- 4 组件的 `cursorAbsoluteTs` 入参都传同一个 hoisted state。
- 单圈内 4 组件共享同一 `LapTelemetry.samples` list（SpeedTimeChart/SectorBar/TrackPolylineMap 用原始 samples，AccelTimeChart 用派生 accelerationG 后的 samples——但 accelerationG 派生只 `copy(accelerationG=...)`，`absoluteTsMs` 不变，所以精确相等匹配仍命中同一时间点）。

**为什么精确相等够用（本 round）**：同一圈内所有组件吃的 samples 来自同一份 `getLapTelemetry` 输出，每个 sample 的 `absoluteTsMs` 唯一且一致。一个 chart 发出某 sample 的 `absoluteTsMs`，其他组件 `find { it.absoluteTsMs == cursorAbsoluteTs }` 必命中同一逻辑点。**M3 多圈比较屏跨圈时不同圈 absoluteTsMs 完全不同，精确相等永远 miss → 那时才需改组件 API 引 gridIndex（路线图 §3 比较屏升级 large）**，本 round 不需要。

**Alternatives 与拒绝理由**：

- **Alt A（拒绝）：现在就引 gridIndex 距离映射**。拒绝理由：单圈内不需要（精确相等可命中）；提前改 4 组件公共 API 是 M3 的 large 改动，本 round 引入会扩 scope + 改组件签名触发连锁返工。YAGNI。
- **Alt B（采纳）：state hoisting + 精确相等**。采纳，零组件 API 改动。

### Decision Downsample: 25Hz 全量渲染本 round 评估后 defer

详见 §Risks「25Hz 降采样评估」。决策：首版 Compose Canvas 全量渲染（典型圈 1500-4500 samples），可接受；显式立 future round `chart-downsample-virtualization`（§10 backlog link，§5.3 要求不留悬空 risk）。

### Decision 圈行可点范围: 只有 VALID/BEST 圈可点

**决策**：`LapSessionDetailScreen.LapRecordRow` 只对 `UiLapStatus.VALID` / `UiLapStatus.BEST` 圈加 onClick；INVALID / INCOMPLETE 圈不可点（它们 timeMs=null，lapNumber 是合成值，不对应有效 `getLapTelemetry` lapIndex）。

**Rationale**：`deriveDetailMetrics`（L511-530）只有 VALID/BEST 圈的 `lapNumber = idx+1` 严格对应 acceptedSF zipWithNext index == `getLapTelemetry` 的 lapIndex（lapIndex = lapNumber-1）；INVALID 圈的 lapNumber 合成在 durations.size 之后，点了会 `getLapTelemetry` 越界返回 null（白屏）。

**Alternatives**：

- **Alt A（拒绝）：所有圈可点**。拒绝理由：INVALID 圈点开 getLapTelemetry 返回 null → 用户看到空屏，体验差。
- **Alt B（采纳）：仅 VALID/BEST 可点**。采纳。INVALID 圈点开无意义（无完整圈数据）。

## Risks / Trade-offs

### Risk 1: 25Hz 全量渲染性能（降采样评估，§5.3 认领）

**数据**：25Hz × 典型圈 1-3 min ≈ 1500-4500 samples。每个组件 Compose Canvas 全量画 polyline（SpeedTimeChart/AccelTimeChart/TrackPolylineMap 各 O(n) lineTo）。

**评估**：
- Compose Canvas 单 Path 含数千 lineTo 在现代真机（华为 8KE0219522008434 / vivo V2405A）首版可接受——polyline 是一次性 draw，不是逐帧重建（`computeChartCoordinates` 在 Canvas lambda 内每次重绘跑，但触发频率 = 游标拖动重组，非 25Hz）。
- 游标拖动重组时 `samples.find/indexOfFirst` 是 O(n)，4500 次线性扫描在拖动手势频率下可接受（非热路径）。
- accelerationG 派生 `AccelerationSmoother.compute` 是 O(n)，`remember(lapTelemetry)` 缓存一次，不在重组热路径。

**Trade-off / 决策**：首版**不做**降采样/虚拟化，全量渲染。若真机攒批路测发现长圈（>3min / >5000 samples）滑动卡顿或 OOM，则启动 future round。

**Defer 决定（不留悬空 risk，§5.3 + 任务卡要求）**：显式立 future round backlog `chart-downsample-virtualization`（见 §10），detail 屏 tasks §10 link。defer 理由：(1) 当前预置赛道（TFIC / 博裕 loop）单圈 1-3min，sample 数在可接受区间；(2) 降采样涉及「保峰值 vs 等距抽样」算法决策 + 游标命中策略调整（降采样后精确相等可能 miss），是独立设计课题，不该塞进组屏 round；(3) 没有真机卡顿证据前先做属过早优化。

### Risk 2: accelerationG 派生 dt 非均匀导致 SG 退化

**描述**：`AccelerationSmoother` 等间距假设，dt 偏差 >20% 自动退化 3 点 SG（`AccelerationSmoother.kt:38`）。25Hz GPS 有掉帧时 dt 非均匀。

**Mitigation**：AccelerationSmoother 已内置非均匀退化路径（`computeNonUniform3PointSg`）+ 病理输入（dt=0/负）输出 0.0 不产 NaN（L75-85 已处理，污染下游 Canvas 已防）。本 round 直接复用，不重复实现。n<=1 时 compute 返回全 0，派生后 accelerationG 全 0（非 null）→ AccelTimeChart 会画一条 0 线而非 "NO ACCEL DATA"；这是可接受边界（单 sample 圈本就无意义）。

### Risk 3: lapIndex 越界 / getLapTelemetry 返回 null

**描述**：路由传入的 lapIndex 可能越界（如用户点 INVALID 圈被绕过保护，或数据竞态），`getLapTelemetry` 返回 null。

**Mitigation**：`LapDetailScreen` 对 null lapTelemetry 显式渲染降级态（"NO LAP DATA" 占位，非崩溃/白屏），并 `FileLogger.e` 记录 sessionId + lapIndex + null 原因。圈行 onClick 已限 VALID/BEST 圈（Decision 圈行可点范围）作为第一层防护。

### Risk 4: 跨 phase 文件占用（LapLiveScreen 无关，本 round 不碰）

**描述**：路线图 §5.4 提醒 redesign-delta + Phase 2 camera preview 抢 `LapLiveScreen.kt`。**本 round 不碰 `LapLiveScreen.kt`**，无此风险。本 round 碰 `TrackTechAppShell.kt`（加 route）+ `LapSessionDetailScreen.kt`（圈行 onClick），看板 §5 确认无并行 round 占用（round 5 lap-comparison 未启动）。合回时 §6 登记这两个共享文件。

## Migration Plan

无 schema 改动 / 无数据迁移。纯新增屏 + 路由 + 圈行 onClick。向后兼容：现有 `lap_session_detail` 路由 + 圈列表行为不变（只是 VALID/BEST 圈多了 onClick）。

## Open Questions

无。R1/R2/降采样/游标/圈行可点范围决策均已在 road-test-first 下由 CC 主会话拍板（任务卡 M2 已定）。
