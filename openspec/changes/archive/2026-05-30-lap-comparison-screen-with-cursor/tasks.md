# Tasks: lap-comparison-screen-with-cursor

> 执行模式 = road-test-first（去 Codex/Opus；CC 自审 + FileLogger + 真机攒批）。
> apply 启动前先跑 #3/#14/#16 自查（见 §0）。新增 .kt 首行预加 `// @IgnoreFormatCheck`。
> 复杂度 large（新组件 API + 新屏 + 圈选择 + 跨圈游标），但第一刀收窄到 speed 叠加，无 schema / 公共协议 / 单圈组件 API 改动。

## 0. apply 前自查（road-test-first 保留闸门）

- [x] 0.1 #3 grep 锚点对齐：实跑 grep 复核 design §Context 列的所有锚点（`getLapTelemetry` L291 / `elapsedMsInLap` L15 + `speedKmh` L18 字段 / `SpeedTimeChart` L96 + `computeChartCoordinates` L57 + `findNearestSampleIndex` L80 / `deriveDetailMetrics` L789 + `UiLapRecord` L552 + `UiLapStatus` L560 / `TrackTechColors` Purple/Cyan/Green/Red L21-25 / NavHost L167-203 + 现有 `lap_detail` route L189）与生产代码一致；rebase 后行号若漂移则更新 design。命令示例：`grep -n "val elapsedMsInLap" core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`
- [x] 0.2 #14 fake DAO 漏 abstract：本 round 不新增 DAO abstract 方法（纯 UI 组屏 + 复用现有 reader），grep 确认无 `interface .*Dao` 签名变化 → 无需补 fake stub。透明声明「本 round 无 DAO 改动」。
- [x] 0.3 #16 跨 round 共享字段 drift：本 round **不改** LapTelemetry / LapTelemetrySample 共享 entity 字段、**不改** getLapTelemetry 填充语义、**不改** 4 个单圈组件公共 API → 不触发 #16。grep 确认 `LapTelemetry.kt` + 4 组件签名 0 改动（`SpeedTimeChart.kt:96` / `AccelTimeChart.kt:29` / `SectorBar.kt:38` / `TrackPolylineMap.kt:63` 签名行不变）。透明声明。
- [x] 0.4 #17 design drift 自查基线：`grep '^### Decision ' openspec/changes/lap-comparison-screen-with-cursor/design.md` 记录当前 Decision 列表（X-Axis / New-Component / Cursor / Lap-Selection / X-Y-Scale），apply 每完成 1 task 与之比对，发生 drift 暂停走修订流程。

## 1. MultiLapSpeedChart 组件 + 纯函数（New-Component / X-Axis / X-Y-Scale）

- [x] 1.1 新建 `feature/test/.../ui/components/MultiLapSpeedChart.kt`（首行 `// @IgnoreFormatCheck`）：
  - `data class LapSeries(val lapNumber: Int, val color: Color, val samples: List<LapTelemetrySample>)`
  - `data class MultiLapBounds(val maxElapsedMs: Long, val speedMin: Double, val speedMax: Double)`
  - internal 纯函数 `computeMultiLapBounds(series: List<LapSeries>): MultiLapBounds`：maxElapsedMs = 所有 series 所有 sample 的 `elapsedMsInLap` 全局 max（空/0 退化 1L）；speedMin/speedMax = 全局 speedKmh min/max + 5% padding（mirror SpeedTimeChart.computeChartBounds L42-55 思路，但跨 series 全局）
  - done condition：纯函数无 androidx 依赖，可 JVM 单测
- [x] 1.2 internal 纯函数 `nearestSampleByElapsed(samples: List<LapTelemetrySample>, targetElapsedMs: Long): LapTelemetrySample?`（mirror `SpeedTimeChart.findNearestSampleIndex` L80-93 二分思路，返回 sample 而非 index；空 → null）。done condition：单调 elapsedMsInLap 二分最近邻正确，边界（空/单元素/超出范围 clamp 到端点）。
- [x] 1.3 `@Composable fun MultiLapSpeedChart(series: List<LapSeries>, cursorElapsedMs: Long?, onCursorChange: (Long) -> Unit, modifier: Modifier = Modifier)`：
  - series 空 / 全空 samples → 占位 "NO DATA"（Box + 居中 Text maxLines=1+Ellipsis）
  - Canvas：`computeMultiLapBounds` 得统一尺度；每 series 画 polyline（`x = elapsedMsInLap / maxElapsedMs × width`，`y` 由 speedKmh 在 [speedMin,speedMax] 归一），用 `series.color`
  - 游标竖线：cursorElapsedMs != null 时在 `cursorElapsedMs / maxElapsedMs × width` 画竖线（TrackTechColors.Purple 或中性色）+ 每 series 在 `nearestSampleByElapsed` 处画高亮点
  - 触摸（detectDragGestures + detectTapGestures，mirror SpeedTimeChart L128-154）：`touchElapsedMs = (touchX / width × maxElapsedMs).coerceIn(0, maxElapsedMs)` → `onCursorChange(touchElapsedMs)`（回写 elapsedMs，**非 absoluteTsMs**）
  - done condition：编译过 + X 轴用 elapsedMsInLap + 各圈一色 + cursor 回写 elapsedMs
- [x] 1.4 单测 `feature/test/src/test/.../ui/components/MultiLapSpeedChartTest.kt`（首行逃课注释，pure JVM JUnit4）：
  - case A：`computeMultiLapBounds` 两圈不同 lapDuration → maxElapsedMs = 最长圈；speedMin/Max = 全局
  - case B：`nearestSampleByElapsed` 目标落两 sample 中间 → 取最近邻；目标超出范围 → clamp 端点
  - case C：`nearestSampleByElapsed` 空 samples → null；单 sample → 该 sample
  - case D：两圈采样密度不同（一圈掉帧），同 targetElapsedMs 各取各圈最近邻（验证非跨圈精确相等）
  - done condition：≥4 case 全绿

## 2. LapComparisonScreen 组屏（Cursor / Lap-Selection / 加载 / 降级）

- [x] 2.1 新建 `feature/test/.../ui/tracktech/LapComparisonScreen.kt`（首行逃课注释）：
  - `LapComparisonScreen(navController, sessionId: String, telemetryRepository = koinInject(), sessionViewModel = koinViewModel())`
  - `LaunchedEffect(sessionId)` 调 `telemetryRepository.getCrossings(sessionId)` → `deriveDetailMetrics(crossings)` 得可选圈（复用 `LapSessionDetailScreen.deriveDetailMetrics`，internal 同 module 可见）
  - `var selectedLapNumbers by remember { mutableStateOf<List<Int>>(emptyList()) }`：默认选择由纯函数 `computeDefaultSelection`（见 2.2）算
  - 结构 mirror M2 `LapDetailScreen.kt`：Column + DetailHeader("LAP COMPARE", popBackStack) + **if/else 分支（不足 2 圈降级 / loaded）MUST NOT early-return**（Risk 3，M2 crash 教训 65d6ada）
  - done condition：编译过
- [x] 2.2 internal 纯函数 `computeDefaultSelection(records: List<UiLapRecord>): List<Int>`（放 LapComparisonScreen.kt 或 helper）：过滤 VALID/BEST + timeMs!=null 圈；选 BEST 圈 + 圈时升序最多 3 个其他 valid（合计 ≤4）；可选圈 < 2 → emptyList（触发降级）。+ `assignLapColors(selectedLapNumbers: List<Int>): List<Color>` 按选中顺序分配 `[Purple, Cyan, Green, Red]`。done condition：可 JVM 单测。
- [x] 2.3 圈选择 chips UI：从可选圈渲染多选 chips（每 chip：Lap N + 圈时(Score 字体) + 选中态色块）；toggle 受 [2,4] 约束（已满 4 不再加 / 剩 2 不再减）。FileLogger 埋点圈选择变更（`FileLogger.d("LapCompare", "select=$selectedLapNumbers")`）。
- [x] 2.4 多圈加载：`LaunchedEffect(sessionId, selectedLapNumbers)` 对每选中 lapNumber 调 `getLapTelemetry(sessionId, lapNumber-1)`，`mapNotNull` skip null（+ `FileLogger.e("LapCompare", "getLapTelemetry null sid=$sessionId lapNumber=$ln")`），构造 `List<LapSeries>`（color = assignLapColors）。成功埋 `FileLogger.d`（含各圈 samples 数）。
- [x] 2.5 共享游标 hoisting（Cursor 决策）：`var cursorElapsedMs by remember { mutableStateOf<Long?>(null) }`；`MultiLapSpeedChart(series, cursorElapsedMs, onCursorChange = { cursorElapsedMs = it })`。FileLogger 埋游标变更（`FileLogger.v("LapCompare", "cursor elapsed=$cursorElapsedMs")`，25Hz 拖动用 v 级）。
- [x] 2.6 图例 + 游标读数：每选中圈一行（Lap N 色块 + 圈时 Score）；cursorElapsedMs != null 时每行追加该圈 `nearestSampleByElapsed` 的瞬时 speed（`MetricNumber(kind=MetricKind.Mechanical)` 纯数字 OK）。Row 配 weight 约束（不裸 SpaceBetween）。
- [x] 2.7 降级态（Risk 2）：series.size < 2 时渲染 "SELECT 2+ LAPS TO COMPARE" 占位（Box 居中 Text maxLines=1+Ellipsis）；可选圈本就 < 2 时显式提示 session 圈不足。**if/else 不 early-return**。
- [x] 2.8 V2 视觉：屏内每个直接 Text 加 maxLines=1 + TextOverflow.Ellipsis；圈时 Score 非 Mechanical；图例/chip Row 配 weight。

## 3. 路由注册 + COMPARE 入口（导航）

- [x] 3.1 `TrackTechAppShell.kt`（L189 `lap_detail` route 之后）加 `composable("lap_comparison/{sessionId}", arguments = listOf(navArgument("sessionId"){type=NavType.StringType}))`，解析后实例化 `LapComparisonScreen(navController, sessionId, sessionViewModel = sessionViewModel)`。done condition：route 注册 + 编译过。
- [x] 3.2 `LapSessionDetailScreen.kt`：加 COMPARE 入口（在圈列表区上方或 OverviewSection 下方，按钮/section）。仅在 `derived.lapRecords` 含 ≥2 个 VALID/BEST 圈时可点（`clickable(enabled = ...)`），点击 `navController.navigate("lap_comparison/$sessionId")`。FileLogger 埋点（`FileLogger.d("LapCompare", "open compare sid=$sessionId validLaps=${derived.validLaps}")`）。done condition：入口可点导航 + < 2 圈 disabled。

## 4. Contract test（grep 风格，mirror LapDetailScreenContractTest）

- [x] 4.1 `feature/test/src/test/.../ui/tracktech/LapComparisonScreenContractTest.kt`（首行逃课注释），mirror `LapDetailScreenContractTest.kt`（readSource + projectRoot + collectTextBlocksMissingMaxLines）：
  - `LapComparisonScreen.kt` REQUIRED_LITERALS：`"LAP COMPARE"` / `"SELECT 2+ LAPS TO COMPARE"` / `MultiLapSpeedChart(` / `getLapTelemetry(` / `deriveDetailMetrics(` / `onCursorChange = { cursorElapsedMs = it }` / `computeDefaultSelection(` / `nearestSampleByElapsed`（图例读数）
  - FORBIDDEN_PATTERNS：`MetricKind.Mechanical`（仅圈时禁；瞬时 speed 允许 → 用更精确的「圈时上下文不含 Mechanical」断言或仅扫圈时 helper；为简化 grep 用「不含 `alignByDistance` / `gridIndexFor` / `LapAlignment`」锁 time-axis）+ `return@Column`（M2 crash 防护）
  - every direct Text maxLines+ellipsis（复用配平扫描）
  - done condition：断言全绿
- [x] 4.2 不改单圈组件 + time-axis contract：
  - `SpeedTimeChart.kt` 签名 `cursorAbsoluteTs: Long?` 仍存在（未被本 round 改）；`AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt` 签名行不变（grep 关键签名字面量）
  - `MultiLapSpeedChart.kt` 不含 `alignByDistance` / `gridIndexFor` / `LapAlignment`（time-axis 不用距离映射）；含 `elapsedMsInLap`（time-axis）
  - done condition：锁死「不改单圈组件 + time-axis」
- [x] 4.3 路由 + 入口 contract：
  - `TrackTechAppShell.kt` 含 `"lap_comparison/{sessionId}"` + `navArgument("sessionId")` + `NavType.StringType` + `LapComparisonScreen(`
  - `LapSessionDetailScreen.kt` 含 `lap_comparison/$sessionId` navigate
  - done condition：断言全绿
- [x] 4.4 LapTelemetry 模型未改 contract（#16 透明声明锚点）：`LapTelemetry.kt` 仍含 `val elapsedMsInLap: Long` + `val speedKmh: Double`（本 round 消费不改）。done condition：锁死共享字段未改。

## 5. 编译 + 测试 gate

- [x] 5.1 `./gradlew :feature:test:compileDebugKotlin --offline` 通过
- [x] 5.2 `./gradlew :feature:test:testDebugUnitTest --offline` 全绿（含 §1.4 / §4 新测试 + 既有 ui.components / LapDetailScreenContractTest 不回归——验证 4 单圈组件未被改）
- [x] 5.3 `./gradlew :app:compileDebugKotlin --offline` 通过

## 6. 真机验证（攒批，串行 gate）

- [ ] 6.1 攒批告知 user：当前 round=lap-comparison-screen-with-cursor / apk / 验证场景，等 user 授权再 adb install（CLAUDE.md 真机串行规则）
- [ ] 6.2 验证场景：session 详情屏点 COMPARE → 进比较屏 → 默认选最快+3 圈 → 多圈 speed 曲线叠加各圈一色 → chips 改选（2-4 圈约束）→ 拖游标多圈同步高亮 + 图例各圈 speed 更新 → 不足 2 圈降级
- [ ] 6.3 **小屏 gate（vivo V2405A，MUST）**：比较屏 chips / 图例多圈行单行不换行；叠图 canvas cursor 可点性 + 多圈曲线可辨色

## 7. 归档

- [ ] 7.1 metrics.yaml 写入：`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + `codex_l1/l2_findings: []`（注 road-test-first 去 Codex）+ FileLogger 埋点锚点摘要（LapCompare tag：load 各圈 / null skip / select 变更 / cursor 转移 / open compare 入口）+ `design_decisions_diverged_during_apply: []`（或实际 drift）+ `cross_round_field_drift_resolved: []`（本 round 不触发 #16）+ `complexity: "large"` + `phase: "Phase 1"`
- [ ] 7.2 `openspec archive lap-comparison-screen-with-cursor`（user 拍板归档时机）

## 10. Follow-up backlog（延期立项，§5.3 不留悬空 risk）

- [ ] 10.1 **`lap-comparison-accel-sector-map-overlay`**（future round，medium~large）：本 round 第一刀只做 speed 叠加。accel（多圈加速度叠加，UI 层 deriveAccelerationG 各圈派生）/ sector（多圈分段对比表）/ map（多圈轨迹叠绘）的多圈叠加留此 round。复用本 round 的 LapSeries + cursorElapsedMs + chips 选择骨架。立项时读本 round design Decision X-Axis/Cursor 起草。
- [ ] 10.2 **`lap-comparison-distance-axis`**（future round，large）：距离轴比较——用 W3 `LapAlignment.alignByDistance`（`core/domain/.../usecase/LapAlignment.kt`）把各圈按累计距离等距重采样到统一 grid，游标走 `gridIndexFor` 距离映射，实现「同物理位置对比」（弯道位置精确对齐）。本 round time-axis 第一刀的固有 trade-off（两圈同 elapsedMs 不一定同物理位置，见 design Decision X-Axis Trade-off）由此 round 补齐。**注意**：距离轴可能需改单圈组件 API 引 gridIndex（路线图 §1.2/§3 已记），届时触发 v3 #16 + 升级 medium。
- [ ] 10.3 **`lap-comparison-predicted-time-delta`**（future round，medium）：圈对圈实时/离线 time-delta 曲线（距离基），消费 10.2 距离轴对齐 + 复用功能一 `RealtimeDeltaCalculator` 思路（交织线 5 收敛）。依赖 10.2。
- [ ] 10.4 **`chart-downsample-virtualization`**（复用 M2 已立 future round，archive/2026-05-30-lap-detail-screen-with-cursor tasks §10.1）：25Hz 全量渲染降采样/虚拟化。**本 round 多圈放大了触发条件**（≤4 圈 × 1500-4500 sample ≈ 最多 ~18000 点）。若真机攒批路测发现 4 圈长圈滑动卡顿/OOM 则启动。本 round design §Risks Risk 1 已认领并 defer，不留悬空 risk。
