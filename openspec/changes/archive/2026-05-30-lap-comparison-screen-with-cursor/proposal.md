# Change: lap-comparison-screen-with-cursor

## Why

### 问题溯源

M2 单圈详情屏（`lap-detail-screen-with-cursor`，archive/2026-05-30）已落地：用户点开一个圈 → 看速度/加速度/分段/轨迹曲线 + 共享游标。但**「圈与圈对比」（功能二数据分析的核心价值）仍 0% 可用**——用户无法把同一 session 内的多圈速度曲线叠在一张图上对照「这一圈在哪个弯比上一圈慢了」。

这正是路线图 §0.3 诊断的「根因 1：Tier2 UI 屏纯串行排在队列尾段」遗留的最后一块用户可见拼图，对应里程碑 M3「多圈比较可用」。

### 当前 baseline（已 grep 核实，2026-05-30）

- **reader 就绪**：`TelemetryRepository.getLapTelemetry(sessionId, lapIndex): LapTelemetry?`（`core/data/.../repository/TelemetryRepository.kt:291`）已返回完整单圈切片。每个 `LapTelemetrySample`（`core/domain/.../model/LapTelemetry.kt:13`）含 `elapsedMsInLap`（L15，= `absoluteTsMs - lapStartWallClock`，圈内流逝时间）+ `speedKmh`（L18）+ `absoluteTsMs`（L14）。
- **圈源就绪**：`LapSessionDetailScreen.deriveDetailMetrics(crossings)`（`LapSessionDetailScreen.kt:789`，`internal`）已产出 `DetailMetrics.lapRecords`，每条 `UiLapRecord` 有 `lapNumber` / `timeMs` / `status`（VALID/BEST/INVALID/INCOMPLETE）。VALID/BEST 圈的 `lapNumber` 严格对应 `getLapTelemetry(sessionId, lapNumber-1)` 的 lapIndex（已由 `unify-lap-count-pairing-semantics` 收敛排序键为 wallClock）。
- **入口缺口**：`LapSessionDetailScreen` 当前只能点单个圈行进 M2 详情屏，**没有「COMPARE」入口**；`TrackTechAppShell.kt:167-203` NavHost 无 `lap_comparison` 路由。
- **单圈组件画法范本（只读不改）**：`SpeedTimeChart.kt:96` 已用 `elapsedMsInLap` 做 X 轴定位（`computeChartCoordinates` L67：`x = sample.elapsedMsInLap / lapDurationMs × canvasWidth`）+ `findNearestSampleIndex(samples, targetElapsedMs)`（L80-93，二分最近邻）。但它的游标 identity 是 `absoluteTsMs`（`onCursorChange(samples[idx].absoluteTsMs)` L140）——**单圈内可命中，跨圈不同圈 absoluteTsMs 完全不同，精确相等永远 miss**。

### 用户场景

用户在 `LapSessionDetailScreen` 看到圈列表后想对比多圈：点「COMPARE」入口 → 进多圈比较屏 → 默认选中最快圈 + 最多 3 个其他 valid 圈（chips 可改选 2-4 圈）→ 看到多圈速度曲线按各自圈内流逝时间叠在一张图上（各圈一色）→ 拖动共享游标（一个 elapsed-time 值），每圈在该流逝时间处取最近邻 sample 高亮，图例区显示各圈在该点的瞬时 speed + 圈号 + 圈时。

### 为什么时间轴（elapsedMsInLap）而非距离轴

**M3 核心设计决策（user 拍板，本 round 按此实施，详见 design Decision X-Axis）**：比较屏 X 轴 = **圈内流逝时间 `elapsedMsInLap`**，多圈按各自圈内流逝时间对齐叠加。游标是一个 `elapsedMs` 值，每圈取 `elapsedMsInLap` 最近邻 sample。**不用** W3 `LapAlignment.alignByDistance` 距离重采样 / `gridIndex` 映射（路线图旧草案曾把比较屏标为「距离轴 + 改 4 组件 API 引 gridIndex」，user 选时间轴第一刀，距离轴 defer）。理由：(1) `LapTelemetrySample` 已天然带 `elapsedMsInLap`，零额外算法依赖；(2) 单圈组件已在 elapsed-time 轴画图，时间轴叠加与既有画法一致；(3) 距离轴需要 GPS 轨迹投影 + 重采样，是独立设计课题，第一刀先交付最核心的 speed 叠加价值。

## What Changes

1. **新建多圈速度叠加组件** `MultiLapSpeedChart`（`feature/test/.../ui/components/MultiLapSpeedChart.kt`，新建，**纯新组件，不碰单圈 SpeedTimeChart/AccelTimeChart/SectorBar/TrackPolylineMap 的任何 API**）：
   - 入参 `List<LapSeries>`（每圈：`lapNumber` + `color` + `samples: List<LapTelemetrySample>`）+ `cursorElapsedMs: Long?` + `onCursorChange: (Long) -> Unit`。
   - Canvas 叠加每圈 speed vs `elapsedMsInLap` 折线（各圈一色）+ 游标竖线（在 cursorElapsedMs 处）+ 各圈在游标 `elapsedMs` 处最近邻 sample 高亮。
   - X 轴用全圈 `elapsedMsInLap` 最大值统一尺度（各圈圈时不同，叠图右端对齐到最长圈的 lapDuration）；Y 轴用全圈 speedKmh min/max 统一尺度。
2. **新建多圈比较屏** `LapComparisonScreen`（`feature/test/.../ui/tracktech/LapComparisonScreen.kt`，新建）+ 路由 `lap_comparison/{sessionId}` 注册到 `TrackTechAppShell.kt`（1 个 navArgument：sessionId=StringType）：
   - **圈选择**：从 session 的 VALID/BEST 圈里多选 2-4 圈（chips；默认选最快圈 + 最多 3 个其他 valid 圈，合计 ≤4）。
   - **LaunchedEffect 加载**：对每个选中圈调 `getLapTelemetry(sessionId, lapNumber-1)` → 构造 `List<LapSeries>`（按 lap 分配调色板颜色）。
   - **MultiLapSpeedChart 叠加** + 共享 hoist 的 `cursorElapsedMs: Long?`。
   - **游标读数 + 图例**：游标 `elapsedMs` 处各圈 speed（每圈最近邻 sample）+ 图例（Lap N 颜色色块 + 圈时，圈时走 Score 字体）。
   - **降级态**：null / 选中圈 < 2 时显式降级占位（"SELECT 2+ LAPS TO COMPARE" / "NO LAP DATA"），不崩溃不白屏。
3. **`LapSessionDetailScreen` 加「COMPARE」入口** → navigate `lap_comparison/$sessionId`（按钮 / section，仅在有 ≥2 个 VALID/BEST 圈时可点）。
4. **FileLogger 埋点**（road-test-first 强制）：比较屏 LaunchedEffect 各圈加载（成功 samples 数 / null 越界 / 圈不足降级）/ 游标关键状态转移 / 圈选择变更。

### 不在本 round 范围（显式 out-of-scope，写进 §10 follow-up backlog）

- **accel / sector / map 多圈叠加** → follow-up（第一刀只做 speed 叠加，最核心）。
- **距离轴比较**（`LapAlignment.alignByDistance` 重采样 + gridIndex 跨圈映射）→ follow-up（user 选时间轴第一刀）。
- **预测 time-delta（距离基）**（圈对圈实时秒差曲线）→ follow-up（依赖距离轴 / 投影）。
- **改 4 个单圈组件公共 API** → 本 round MUST NOT 碰（M2 单圈详情屏依赖它们，改了回归 + 触发 v3 #16）；新建 MultiLapSpeedChart 独立组件。

## Impact

- Affected specs: 新增 capability `lap-comparison-screen`（屏级 capability，mirror `lap-detail-screen`）。
- Affected code:
  - 新建 `feature/test/.../ui/components/MultiLapSpeedChart.kt`（多圈速度叠加组件 + `LapSeries` data class + internal 纯函数）
  - 新建 `feature/test/.../ui/tracktech/LapComparisonScreen.kt`（比较屏 + 圈选择 + 图例 + 调色板）
  - 修改 `feature/test/.../ui/tracktech/TrackTechAppShell.kt`（加 1 个 route `lap_comparison/{sessionId}`）
  - 修改 `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`（加 COMPARE 入口 → navigate）
  - 新增测试 `feature/test/src/test/.../ui/components/MultiLapSpeedChartTest.kt`（纯函数：调色板 / 最近邻 / X-Y 尺度统一）
  - 新增测试 `feature/test/src/test/.../ui/tracktech/LapComparisonScreenContractTest.kt`（grep 风格：视觉字面量 / 圈选择 / 不改单圈组件 API 防护 / 路由 / 入口）
- 公共协议 / Room schema / reader 契约 / 4 个单圈组件 API：**0 改动**。本 round 复杂度 large（新组件 API + 新屏 + 圈选择 + 跨圈游标），但第一刀已收窄到 speed 叠加，无 schema / 公共协议改动，不命中强制升级 medium 的 5 个例外场景（公共协议改 / 跨 capability ripple / Room migration / 引入新 module / 派生 follow-up——本 round 是组屏消费，新 capability 是屏级 spec 非新 module）。执行模式 = road-test-first。
- 看板：本 round 独占 `MultiLapSpeedChart.kt` + `LapComparisonScreen.kt`（新建）；`TrackTechAppShell.kt`（加 route）+ `LapSessionDetailScreen.kt`（加 COMPARE 入口）为共享文件，看板 §5 确认无并行 round 占用（M2 已合回），合回时 §6 登记。
