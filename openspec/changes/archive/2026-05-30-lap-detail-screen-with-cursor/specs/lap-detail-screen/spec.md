# lap-detail-screen

## ADDED Requirements

### Requirement: LapDetailScreen 加载并渲染单圈 telemetry

`LapDetailScreen`（`feature/test/.../ui/tracktech/LapDetailScreen.kt`）SHALL 在进入时用 `LaunchedEffect(sessionId, lapIndex)` 调 `TelemetryRepository.getLapTelemetry(sessionId, lapIndex)` 加载 `LapTelemetry`，并据结果渲染单圈详情。

实现 MUST 满足：

1. **加载入口**：MUST 用 `LaunchedEffect(sessionId, lapIndex)` 异步调 `getLapTelemetry(sessionId, lapIndex)`，结果存 `remember { mutableStateOf<LapTelemetry?>(null) }`。
2. **成功态**：`getLapTelemetry` 返回非 null 时 MUST 渲染 4 个组件（SpeedTimeChart + AccelTimeChart + SectorBar + TrackPolylineMap）+ Lap Overview（圈号 / 圈时 / track name）。
3. **降级态**：`getLapTelemetry` 返回 null（lapIndex 越界 / wallClock null / binary 缺失）时 MUST 渲染显式降级占位（如 "NO LAP DATA" 文案），**MUST NOT** 崩溃、**MUST NOT** 白屏。
4. **FileLogger 埋点**：加载成功（含 samples 数）/ 失败 null（含 sessionId + lapIndex + 原因）MUST 各埋一条 `FileLogger.d/e`（road-test-first 兜底）。
5. **圈时字体**：Lap Overview 内圈时（时间字符串 `m:ss.SSS`）MUST 用 Score 字体（`TrackTechTypography.Score*` 或经 `MetricNumber/MetricTile kind=MetricKind.Score`），**MUST NOT** 用 DSEG7（Mechanical），因为时间字符串非纯数字仪表瞬时读数（CLAUDE.md V2 视觉约束）。

#### Scenario: 有效单圈加载渲染 4 组件

- **GIVEN** 一个 LAP_SESSION，sessionId=S1，lapIndex=0 对应有效圈（getLapTelemetry 返回非 null，samples 非空）
- **WHEN** 进入 `LapDetailScreen(sessionId=S1, lapIndex=0)`
- **THEN** `LaunchedEffect(sessionId, lapIndex)` 调 `getLapTelemetry("S1", 0)`
- **AND** lapTelemetry 非 null 后渲染 SpeedTimeChart / AccelTimeChart / SectorBar / TrackPolylineMap 四个组件
- **AND** Lap Overview 显示圈号 + 圈时（Score 字体）

#### Scenario: lapIndex 越界返回 null 走降级态不崩溃

- **GIVEN** sessionId=S1 只有 2 个有效圈（合法 lapIndex 0/1），路由传入 lapIndex=99
- **WHEN** 进入 `LapDetailScreen(sessionId=S1, lapIndex=99)`
- **THEN** `getLapTelemetry("S1", 99)` 返回 null
- **AND** 屏幕渲染显式降级占位（"NO LAP DATA"），不崩溃、不白屏
- **AND** `FileLogger.e` 记录 sessionId=S1 + lapIndex=99 + null 原因

#### Scenario: 反例——圈时 MUST NOT 用 DSEG7（Mechanical）字体

- **GIVEN** `LapDetailScreen.kt` 渲染 Lap Overview 内圈时字符串（如 `1:32.457`）
- **WHEN** contract test 扫描 `LapDetailScreen.kt` 源文件
- **THEN** 圈时渲染路径 MUST NOT 出现 `MetricKind.Mechanical` 用于圈时字段
- **AND** 若实现误把圈时设为 `kind = MetricKind.Mechanical`（DSEG7 七段字体吃带冒号/小数点的时间串会字符变形），contract test 的 FORBIDDEN 断言 fail，锁死「时间字符串用 Score」

### Requirement: 共享游标 cursorAbsoluteTs 拖动时 4 组件同步联动

`LapDetailScreen` SHALL hoist 单一 `cursorAbsoluteTs: Long?` state，使任一 chart（SpeedTimeChart / AccelTimeChart）拖动发起游标变更时，4 个组件同步高亮同一时间点。

实现 MUST 满足：

1. **state hoisting**：MUST 在 `LapDetailScreen` 持有 `var cursorAbsoluteTs by remember { mutableStateOf<Long?>(null) }` 作为 single source of truth。
2. **发起方**：SpeedTimeChart 与 AccelTimeChart 的 `onCursorChange` MUST 回写同一 hoisted state（`onCursorChange = { cursorAbsoluteTs = it }`）。
3. **消费方**：4 个组件（含只消费的 SectorBar / TrackPolylineMap）的 `cursorAbsoluteTs` 入参 MUST 传同一 hoisted state。
4. **精确相等匹配**：单圈内 4 组件吃的 samples 来自同一份 `getLapTelemetry` 输出（accelerationG 派生只 `copy(accelerationG=...)` 不改 `absoluteTsMs`），故 `absoluteTsMs` 精确相等匹配命中同一逻辑时间点。本 round **MUST NOT** 引入 gridIndex 距离映射（那是 M3 跨圈比较的事）。

#### Scenario: SpeedTimeChart 拖动游标 4 组件同步

- **GIVEN** `LapDetailScreen` 已加载有效单圈，4 组件共享同一 samples + hoisted cursorAbsoluteTs（初值 null）
- **WHEN** 用户在 SpeedTimeChart 上拖动，触发 `onCursorChange(T)`（T = 某 sample 的 absoluteTsMs）
- **THEN** hoisted `cursorAbsoluteTs` 更新为 T
- **AND** AccelTimeChart / SectorBar / TrackPolylineMap 的 cursorAbsoluteTs 入参都收到 T
- **AND** SpeedTimeChart / AccelTimeChart / TrackPolylineMap 用 `find/indexOfFirst { it.absoluteTsMs == T }` 命中同一 sample；SectorBar 用 `(T - lapStartWallClock)/lapDuration` 标记同一时间分数

#### Scenario: AccelTimeChart 拖动游标同样回写同一 state

- **GIVEN** `LapDetailScreen` 已加载有效单圈
- **WHEN** 用户在 AccelTimeChart 上拖动触发 `onCursorChange(T2)`
- **THEN** hoisted `cursorAbsoluteTs` 更新为 T2（与 SpeedTimeChart 共享同一 state，非各自独立）
- **AND** 4 组件同步高亮 T2 对应位置

#### Scenario: 反例——4 组件 MUST NOT 各自持有独立游标 state

- **GIVEN** `LapDetailScreen.kt` 渲染 4 组件
- **WHEN** contract test 扫描源文件结构
- **THEN** 4 组件的 cursorAbsoluteTs 入参 MUST 引用同一 hoisted 变量（single source of truth）
- **AND** 若实现给某个组件传独立的局部 `remember { mutableStateOf<Long?>` 而非共享 hoisted state，则该组件不会随其他组件联动 → 违反「拖动 4 组件同步」契约，contract test（结构断言：同一 state 名被 4 处入参引用）fail

### Requirement: accelerationG 在 UI 层从 speedKmh 反算非空喂 AccelTimeChart

`LapDetailScreen` SHALL 在 UI 数据准备层用 `AccelerationSmoother` 从 samples 的 `speedKmh` + `absoluteTsMs` 反算 accelerationG（转 G），构造带非 null accelerationG 的 sample 列表喂 `AccelTimeChart`，使其渲染加速度曲线而非 "NO ACCEL DATA"。

实现 MUST 满足：

1. **派生位置**：MUST 在 UI 层（`LapDetailScreen` 内 `remember(lapTelemetry)` 缓存块）派生，**MUST NOT** 改 `getLapTelemetry` reader（保持 sample.accelerationG=null 的公共契约不变，不触发 v3 #16）。
2. **算法**：MUST 用 `core/domain` 的 `AccelerationSmoother.compute(samples.map { TimedSpeedSample(it.absoluteTsMs, it.speedKmh) })`，得 m/s² 数组后 `/ GRAVITY_MS2` 转 G。
3. **构造**：MUST 把派生的 G 值 `copy(accelerationG = gValue)` 回每个 sample（保持 absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh 不变，确保游标精确相等仍命中），这份派生列表只喂 AccelTimeChart。
4. **纯函数可测**：派生逻辑 MUST 抽 internal 纯函数（如 `deriveAccelerationG(samples): List<LapTelemetrySample>`），便于 JVM 单测断言。
5. **其他组件用原始 samples**：SpeedTimeChart / SectorBar / TrackPolylineMap MUST 用原始 samples（它们不读 accelerationG）。

#### Scenario: 非空 accelerationG 使 AccelTimeChart 画曲线

- **GIVEN** 一份有 N≥5 个 sample 的单圈 telemetry，所有 sample.accelerationG 初值 null（reader 输出）
- **WHEN** 调 `deriveAccelerationG(samples)`
- **THEN** 返回的 N 个 sample 中每个 accelerationG 非 null（由 speedKmh 序列经 AccelerationSmoother 反算 / GRAVITY_MS2）
- **AND** 把派生列表喂 AccelTimeChart 时 `samples.all { it.accelerationG == null }` == false → 不走 "NO ACCEL DATA" 分支 → 画加速度曲线

#### Scenario: 派生不改 absoluteTsMs 游标精确相等仍命中

- **GIVEN** 原始 samples 中某 sample 的 absoluteTsMs=T
- **WHEN** 调 `deriveAccelerationG(samples)`
- **THEN** 返回列表对应位置 sample 的 absoluteTsMs 仍 == T（仅 accelerationG 字段被填充）
- **AND** 游标 cursorAbsoluteTs=T 时 AccelTimeChart `find { it.absoluteTsMs == T }` 命中（与 SpeedTimeChart 命中同一逻辑点）

#### Scenario: 反例——MUST NOT 在 reader 内填充 accelerationG

- **GIVEN** `getLapTelemetry`（`TelemetryRepository.kt:291`）当前 L313 `accelerationG = null`
- **WHEN** contract test 扫描 `TelemetryRepository.kt` 的 getLapTelemetry 段
- **THEN** 本 round **MUST NOT** 改 reader 让 accelerationG 变非 null（保持 `accelerationG = null` 字面量存在）
- **AND** 若实现把派生塞进 reader（reader 引入 AccelerationSmoother / accelerationG 不再恒 null），会命中 v3 #16 共享字段语义扩展 + 强制升级 medium → 违反 M2「纯组屏」约束，contract test（reader 段 `accelerationG = null` 必须仍在 + reader 不 import AccelerationSmoother）fail

### Requirement: sectorBoundaries 多段直接喂 SectorBar 画多段

`LapDetailScreen` SHALL 把 `getLapTelemetry` 返回的多段 `sectorBoundaries` 直接传给 `SectorBar`，画出真实 Sector 分段，而非只画 1 段。

实现 MUST 满足：

1. **直接消费**：MUST 把 `lapTelemetry.sectorBoundaries` + `lapTelemetry.lapStartWallClock` + `lapTelemetry.lapEndWallClock` + 共享 `cursorAbsoluteTs` 传给 `SectorBar`，**MUST NOT** 在本 round 重做 sector 派生（`future-sector-derivation` 已归档）。
2. **多段渲染**：sectorBoundaries 含 K 个元素（K≥2）时 SectorBar MUST 画出对应分段（windowed(2) + 自动补 lapEnd）。
3. **单段回退兼容**：sectorBoundaries == `listOf(lapStartWallClock)`（无 sector 门赛道 / debug 宽容闭合）时 MUST 退化画 1 段全圈条，不崩溃。

#### Scenario: 多段 sectorBoundaries 画多段

- **GIVEN** `getLapTelemetry` 返回 `sectorBoundaries = listOf(1000L, 2500L, 3800L)`，lapStartWallClock=1000, lapEndWallClock=5000
- **WHEN** `LapDetailScreen` 把该 sectorBoundaries 传给 SectorBar
- **THEN** SectorBar `computeSectorBounds` 自动补 lapEnd=5000 后 windowed(2) 画出 3 段（[1000,2500] / [2500,3800] / [3800,5000]）
- **AND** sectorBoundaries.first()==lapStartWallClock，SectorBar 不触发 first()!=lapStart 警告

#### Scenario: 单段回退（无 sector 门）画 1 段全圈条

- **GIVEN** `getLapTelemetry` 返回 `sectorBoundaries = listOf(lapStartWallClock)`（单元素回退）
- **WHEN** `LapDetailScreen` 把该 sectorBoundaries 传给 SectorBar
- **THEN** SectorBar 画 1 段全圈条（baseline 行为），不崩溃
- **AND** 与多 sector 门赛道行为统一（同一组件入参，不需 detail 屏特判）

#### Scenario: 反例——detail 屏 MUST NOT 用单元素硬编码覆盖 reader 多段

- **GIVEN** `getLapTelemetry` 已返回多段 sectorBoundaries
- **WHEN** contract test 扫描 `LapDetailScreen.kt`
- **THEN** detail 屏 MUST 直接传 `lapTelemetry.sectorBoundaries`，**MUST NOT** 硬编码 `listOf(lapStartWallClock)` 覆盖 reader 输出
- **AND** 若实现传死单元素（退回 baseline 单段），多 sector 门赛道圈也只画 1 段 → 违反「多段画多段」契约，contract test（detail 屏传 `lapTelemetry.sectorBoundaries` 字面量 + 不含硬编码单元素覆盖）fail

### Requirement: 新路由注册 + 圈行 onClick 导航

`TrackTechAppShell` SHALL 注册 `lap_detail/{sessionId}/{lapIndex}` 路由；`LapSessionDetailScreen` 的有效圈行 SHALL 加 onClick 导航到该路由。

实现 MUST 满足：

1. **路由注册**：`TrackTechAppShell.kt` MUST 注册 `composable("lap_detail/{sessionId}/{lapIndex}", arguments = [navArgument("sessionId"){StringType}, navArgument("lapIndex"){IntType}])`，实例化 `LapDetailScreen(sessionId, lapIndex, ...)`。
2. **圈行 onClick**：`LapSessionDetailScreen.LapRecordRow` 对 `UiLapStatus.VALID` / `UiLapStatus.BEST` 圈 MUST 加 onClick，导航 `lap_detail/$sessionId/${lapNumber-1}`（lapIndex = lapNumber-1，与 `deriveDetailMetrics` 圈编号同源）。
3. **不可点范围**：INVALID / INCOMPLETE 圈 **MUST NOT** 可点（它们 timeMs=null + lapNumber 合成，不对应有效 lapIndex）。

#### Scenario: 点 VALID 圈导航到 lap_detail 正确 lapIndex

- **GIVEN** `LapSessionDetailScreen` sessionId=S1，圈列表含 Lap 3（UiLapStatus.VALID，lapNumber=3）
- **WHEN** 用户点击 Lap 3 行
- **THEN** 导航到 `lap_detail/S1/2`（lapIndex = lapNumber-1 = 2，与 getLapTelemetry(S1, 2) 同源）
- **AND** TrackTechAppShell 路由解析 sessionId="S1" + lapIndex=2，实例化 `LapDetailScreen("S1", 2)`

#### Scenario: 路由 lapIndex 用 IntType 解析

- **GIVEN** `TrackTechAppShell.kt` 注册 `lap_detail/{sessionId}/{lapIndex}` 路由
- **WHEN** contract test 扫描 `TrackTechAppShell.kt`
- **THEN** 路由 MUST 含 `navArgument("lapIndex")` 且 type 为 `NavType.IntType`（lapIndex 是整数索引）
- **AND** sessionId 用 `NavType.StringType`

#### Scenario: 反例——INVALID 圈不可点

- **GIVEN** 圈列表含一个 INVALID 圈（UiLapStatus.INVALID，timeMs=null，lapNumber 合成在 durations.size 之后）
- **WHEN** contract test 扫描 `LapSessionDetailScreen.kt` 的 LapRecordRow onClick 逻辑
- **THEN** onClick 导航 MUST 限定在 VALID/BEST 分支，INVALID/INCOMPLETE 圈无 navigate 调用
- **AND** 若实现给 INVALID 圈也加 navigate，点开会 `getLapTelemetry` 越界返回 null → 空屏 → 违反「不可点范围」契约，contract test fail

### Requirement: V2 视觉约束（单行 Ellipsis + DSEG7 仅仪表瞬时数字）

`LapDetailScreen` SHALL 全程遵守 Track Tech V2 视觉约束。

实现 MUST 满足：

1. **单行 Ellipsis**：屏内所有直接 `Text(...)` 调用（标题 / section header / Overview label-value / 降级占位）MUST 加 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`。
2. **DSEG7 边界**：DSEG7（Mechanical）仅用于纯数字仪表瞬时读数（游标 SPEED/G 读数在组件内部已 kind=Mechanical）；圈时 / track name / 圈号标题等 MUST NOT 用 Mechanical。
3. **布局 weight 约束**：含可变长度文本的 Row（如 Overview label-value）MUST 配 weight 约束（label `weight(1f, fill = false)` + 末尾固定元素前 Spacer），不裸用 SpaceBetween 让长文本撑爆截断。

#### Scenario: 所有直接 Text 单行 Ellipsis

- **GIVEN** `LapDetailScreen.kt` 内所有直接 `Text(...)` 调用
- **WHEN** contract test 用括号配平扫描每个 `Text(` 块（mirror PerformanceResultScreenContractTest 的 `collectTextBlocksMissingMaxLines`）
- **THEN** 每个块 MUST 同时含 `maxLines = 1` 与 `TextOverflow.Ellipsis`
- **AND** 缺任一的块数 == 0

#### Scenario: 圈号/track name 标题用 Score 非 Mechanical

- **GIVEN** `LapDetailScreen` Lap Overview 渲染圈号（如 "LAP 3"）+ track name（如 "Shanghai T."）
- **WHEN** 渲染这些文字状态/标题字段
- **THEN** MUST 用 Score / RacingTitle / UiTextLabel 字体，**MUST NOT** 用 `MetricKind.Mechanical`

#### Scenario: 反例——长 track name 在 Overview 行 MUST 触发 Ellipsis 而非换行

- **GIVEN** Lap Overview 一行 label="Track" + value 为长 track name
- **WHEN** value Text 在 bounded width 内测量
- **THEN** 该行布局 MUST 配 weight 约束（label `weight(1f, fill=false)` + value 单行 Ellipsis），长名截断为省略号
- **AND** 若实现裸用 `Arrangement.SpaceBetween` 无 weight，长 value 会撑爆/换行 → 违反 V2 严格单行约束，真机小屏（vivo V2405A）gate 会暴露
