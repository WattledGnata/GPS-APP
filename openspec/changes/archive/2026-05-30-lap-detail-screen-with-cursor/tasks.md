# Tasks: lap-detail-screen-with-cursor

> 执行模式 = road-test-first（去 Codex/Opus；CC 自审 + FileLogger + 真机攒批）。
> apply 启动前先跑 #3/#14/#16 自查（见 §0）。新增 .kt 首行预加 `// @IgnoreFormatCheck`。

## 0. apply 前自查（road-test-first 保留闸门）

- [x] 0.1 #3 grep 锚点对齐：实跑 grep 复核 design §Context 列的所有锚点（`getLapTelemetry` L291 / `accelerationG = null` L313 / sectorBoundaries L329 / 4 组件签名行号 / `AccelerationSmoother.compute` L22 / `GRAVITY_MS2` L117 / `LapRecordRow` L338 / `deriveDetailMetrics` L488 / NavHost L167-187）与生产代码一致；rebase 后行号若漂移则更新 design。命令示例：`grep -n "accelerationG = null" core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`
- [x] 0.2 #14 fake DAO 漏 abstract：本 round 不新增 DAO abstract 方法（纯 UI 组屏），grep 确认无 `interface .*Dao` 签名变化 → 无需补 fake stub。透明声明「本 round 无 DAO 改动」。
- [x] 0.3 #16 跨 round 共享字段 drift：本 round R1/R2 都在 UI 层消费，**不改** LapTelemetry / LapTelemetrySample 共享 entity 字段、**不改** getLapTelemetry 填充语义 → 不触发 #16。grep 确认 `LapTelemetry.kt` 0 改动。透明声明。
- [x] 0.4 #17 design drift 自查基线：`grep '^### Decision ' openspec/changes/lap-detail-screen-with-cursor/design.md` 记录当前 Decision 列表（R1 / R2 / Cursor / Downsample / 圈行可点范围），apply 每完成 1 task 与之比对，发生 drift 暂停走修订流程。

## 1. accelerationG UI 层派生纯函数（R1）

- [x] 1.1 在 `feature/test/.../ui/tracktech/LapDetailScreen.kt`（新建，首行 `// @IgnoreFormatCheck`）写 internal 纯函数 `deriveAccelerationG(samples: List<LapTelemetrySample>): List<LapTelemetrySample>`：
  - 用 `AccelerationSmoother.compute(samples.map { TimedSpeedSample(it.absoluteTsMs, it.speedKmh) })` 得 m/s² 数组
  - 每个 sample `copy(accelerationG = msPerS2[i] / GRAVITY_MS2)`（仅填 accelerationG，absoluteTsMs/elapsedMsInLap/lat/lon/speedKmh 不变）
  - import `com.blazepush.core.domain.usecase.AccelerationSmoother` / `TimedSpeedSample` / `GRAVITY_MS2`
  - done condition：空列表返回空；N≥1 返回每个 accelerationG 非 null
- [x] 1.2 单测 `feature/test/src/test/.../ui/tracktech/LapDetailAccelDeriveTest.kt`（新建，首行逃课注释）：
  - case A：N≥5 sample（accelerationG 全 null 输入）→ 派生后全非 null
  - case B：派生后第 i 个 sample.absoluteTsMs == 原始第 i 个 absoluteTsMs（不改时间戳，锁游标命中）
  - case C：单 sample（N=1）→ AccelerationSmoother 返回 [0.0] → accelerationG=0.0 非 null（边界）
  - done condition：3 case 全绿（pure JVM JUnit4，不依赖 Compose/Robolectric）

## 2. LapDetailScreen Composable 组屏（R1/R2/Cursor）

- [x] 2.1 `LapDetailScreen(navController, sessionId: String, lapIndex: Int, telemetryRepository = koinInject(), sessionViewModel = koinViewModel())`：
  - `var lapTelemetry by remember { mutableStateOf<LapTelemetry?>(null) }`
  - `LaunchedEffect(sessionId, lapIndex) { lapTelemetry = telemetryRepository.getLapTelemetry(sessionId, lapIndex) }`
  - FileLogger 埋点：加载成功 `FileLogger.d("LapDetail", "loaded sid=$sessionId idx=$lapIndex samples=${it.samples.size}")`；null `FileLogger.e("LapDetail", "getLapTelemetry null sid=$sessionId idx=$lapIndex")`
  - 结构 mirror `PerformanceResultScreen.kt`：Column + DetailHeader("LAP DETAIL", onBack=popBackStack) + LazyColumn（Overview item + 4 组件 item）
  - done condition：编译过 + 进屏调 getLapTelemetry
- [x] 2.2 降级态（Risk 3）：lapTelemetry==null 时渲染 "NO LAP DATA" 占位（Box + 居中 Text，maxLines=1 + Ellipsis），不崩溃不白屏。
- [x] 2.3 共享游标 hoisting（Cursor 决策）：`var cursorAbsoluteTs by remember { mutableStateOf<Long?>(null) }`；4 组件 cursorAbsoluteTs 入参全传它；SpeedTimeChart/AccelTimeChart `onCursorChange = { cursorAbsoluteTs = it }`。FileLogger 埋点游标变更（`FileLogger.v("LapDetail", "cursor ts=$it")`，25Hz 频率用 v 级别可过滤）。
- [x] 2.4 accelerationG 派生接线（R1）：`val accelSamples = remember(lapTelemetry) { lapTelemetry?.let { deriveAccelerationG(it.samples) } ?: emptyList() }`；AccelTimeChart 喂 accelSamples，其余 3 组件喂 `lapTelemetry.samples` 原始。FileLogger 埋一条派生完成（含派生 sample 数）。
- [x] 2.5 SectorBar 多段接线（R2）：`SectorBar(sectorBoundaries = lapTelemetry.sectorBoundaries, lapStartWallClock = lapTelemetry.lapStartWallClock, lapEndWallClock = lapTelemetry.lapEndWallClock, cursorAbsoluteTs = cursorAbsoluteTs)`。**不硬编码单元素覆盖**。
- [x] 2.6 Lap Overview：圈号（"LAP ${lapIndex+1}" 用 RacingTitle/UiTextLabel）+ 圈时（`formatLapTime(lapTelemetry.lapDurationMs)` 用 Score 字体，**非 Mechanical**）+ track name（trackNameSnapshot ?: "—"，单行 Ellipsis + weight 约束）+ top speed in lap（samples.maxOf speedKmh，可选）。复用 `formatLapTime` 模式（参 LapSessionDetailScreen.kt:442）。
- [x] 2.7 V2 视觉：屏内每个直接 Text 加 maxLines=1 + TextOverflow.Ellipsis；Overview label-value Row 配 weight（参 LapSessionDetailScreen OverviewRow L287，但用 weight(1f, fill=false) 模式，不裸 SpaceBetween 撑爆）。

## 3. 路由注册 + 圈行 onClick（导航）

- [x] 3.1 `TrackTechAppShell.kt`（L167-187 NavHost 段）：在 `performance_result/{testId}` route 之后加 `composable("lap_detail/{sessionId}/{lapIndex}", arguments = listOf(navArgument("sessionId"){type=NavType.StringType}, navArgument("lapIndex"){type=NavType.IntType}))`，解析后实例化 `LapDetailScreen(navController, sessionId, lapIndex, sessionViewModel = sessionViewModel)`。done condition：route 注册 + 编译过。
- [x] 3.2 `LapSessionDetailScreen.kt`：`LapRecordRow`（L338）加 `onClick: (() -> Unit)?` 参数，调用方（L158-160 items 块）对 VALID/BEST 圈传 `onClick = { navController.navigate("lap_detail/$sessionId/${record.lapNumber - 1}") }`，INVALID/INCOMPLETE 圈传 null。Row 用 `.clickable(enabled = onClick != null) { onClick?.invoke() }`。done condition：仅 VALID/BEST 可点。
- [x] 3.3 FileLogger 埋点圈行点击（`FileLogger.d("LapDetail", "navigate sid=$sessionId lapNumber=${record.lapNumber} -> lapIndex=${record.lapNumber-1}")`）便于真机核对点击 lapNumber 与打开 lapIndex 一致（R2 配对正确性）。

## 4. Contract test（grep 风格，mirror PerformanceResultScreenContractTest）

- [x] 4.1 `feature/test/src/test/.../ui/tracktech/LapDetailScreenContractTest.kt`（新建，首行逃课注释），mirror `PerformanceResultScreenContractTest.kt`（readSource candidate paths + collectTextBlocksMissingMaxLines）：
  - `LapDetailScreen.kt` REQUIRED_LITERALS：`"LAP DETAIL"` / `"NO LAP DATA"` / `SpeedTimeChart(` / `AccelTimeChart(` / `SectorBar(` / `TrackPolylineMap(` / `deriveAccelerationG(` / `getLapTelemetry(` / `lapTelemetry.sectorBoundaries` / `onCursorChange = { cursorAbsoluteTs = it }`
  - FORBIDDEN_PATTERNS：圈时字段 `MetricKind.Mechanical`（圈时 MUST Score）/ 硬编码 `sectorBoundaries = listOf(` 单元素覆盖
  - every direct Text maxLines+ellipsis 断言（复用配平扫描）
  - done condition：以上断言全绿
- [x] 4.2 路由 + 圈行 contract（同测试文件或 mirror）：
  - `TrackTechAppShell.kt` 含 `"lap_detail/{sessionId}/{lapIndex}"` + `navArgument("lapIndex")` + `NavType.IntType` + `LapDetailScreen(`
  - `LapSessionDetailScreen.kt` 含 `lap_detail/$sessionId/` navigate + onClick 限 VALID/BEST（grep onClick 出现在 VALID/BEST 分支上下文）
  - done condition：断言全绿
- [x] 4.3 reader 未被改 contract（#16 防护）：断言 `TelemetryRepository.kt` getLapTelemetry 段仍含 `accelerationG = null` 字面量 + `LapDetailScreen.kt` import AccelerationSmoother（UI 层）而 `TelemetryRepository.kt` 不 import AccelerationSmoother（reader 层保持纯净）。done condition：锁死「R1 在 UI 层不在 reader」。

## 5. 编译 + 测试 gate

- [x] 5.1 `./gradlew :feature:test:compileDebugKotlin --offline` 通过
- [x] 5.2 `./gradlew :feature:test:testDebugUnitTest --offline` 全绿（含 §1.2 / §4 新测试 + 既有 ui.components contract test 不回归）
- [x] 5.3 `./gradlew :app:compileDebugKotlin --offline` 通过

## 6. 真机验证（攒批，串行 gate）

- [ ] 6.1 攒批告知 user：当前 round=lap-detail-screen-with-cursor / apk / 验证场景，等 user 授权再 adb install（CLAUDE.md 真机串行规则）
- [ ] 6.2 验证场景：点 VALID 圈 → 进详情屏 → 4 组件渲染（speed/accel 曲线 + sector 多段 + 轨迹）→ 拖游标 4 组件同步高亮 → accelerationG 非空（AccelTimeChart 非 "NO ACCEL DATA"）→ 圈号/圈时正确
- [ ] 6.3 **小屏 gate（vivo V2405A，MUST）**：detail 屏 Overview 长 track name / 圈时单行不换行；chart canvas cursor 可点性

## 7. 归档

- [ ] 7.1 metrics.yaml 写入：`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + `codex_l1/l2_findings: []`（注 road-test-first 去 Codex）+ FileLogger 埋点锚点摘要（LapDetail tag：load / cursor / accel derive / navigate / null degrade）+ `design_decisions_diverged_during_apply: []`（或实际 drift）+ `cross_round_field_drift_resolved: []`（本 round 不触发 #16）+ `complexity: "medium"` + `phase: "Phase 1"`
- [ ] 7.2 `openspec archive lap-detail-screen-with-cursor`（user 拍板归档时机）

## 10. Follow-up backlog（延期立项，§5.3 不留悬空 risk）

- [ ] 10.1 **`chart-downsample-virtualization`**（future round）：25Hz 全量渲染降采样/虚拟化。本 round design §Risks「25Hz 降采样评估」已认领并 defer：首版全量渲染（典型圈 1500-4500 samples 可接受），若真机攒批路测发现长圈（>5000 samples）滑动卡顿/OOM 则启动。涉及「保峰值 vs 等距抽样」算法 + 降采样后游标命中策略（精确相等可能 miss → 需 nearest 兜底）。立项时直接读 design §Risks Risk 1 起草 proposal。复杂度预估 medium。
- [ ] 10.2 （M3 衔接，非本 round）`lap-comparison-screen-with-cursor`：跨圈比较需改 4 组件公共 API 引 gridIndex 距离映射（本 round 单圈精确相等够用，未改签名）。本 round 不立 memo（路线图 §3 已记 large + X 轴语义拍板）。
