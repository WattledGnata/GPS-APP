## Why

**问题溯源**：Phase 0 已闭合（`chore commit e2a42a1` / 2026-05-04，binary + crossing + PERFORMANCE_TEST 三层时钟域全部对齐），下一步 Phase 1 要交付"单圈数据图表 + 多圈比较"。Entry sketch（`docs/design/phase-1-entry-data-contracts.md`，本地不进 git）把 Phase 1 拆成 4 worktree 并行：W1 `lap-data-readers`（数据底座 / repository 加 `getLapTelemetry` + `getDataPointsForResult`）、W2 `chart-and-map-components`（本 round / UI 组件库）、W3 `lap-comparison-time-align`（多圈对齐算法）、W4 `wire-laptime-to-gps-filter`（独立修复）；Tier 2 的 `lap-detail-screen-with-cursor` / `lap-comparison-screen-with-cursor` 才组屏。

**当前 baseline**：
- `feature/test/.../ui/components/` 目录下已有 V1 时代的 `SpeedChart.kt`（该文件内含 `SpeedChart` + `GForceChart` 两个 Composable，`GForceChart` 不是独立文件——行 159-296；消费 `List<DataPoint>`，绑死 PERFORMANCE_TEST 单测试场景）；G round 加过 `wrapInCard: Boolean = true` 参数让 V2 详情页按需嵌套；但**没有**消费 `LapTelemetrySample` 的 chart 组件，也没有 sector bar / track polyline map
- 新增 `AccelTimeChart` 与现有 `GForceChart` 是语义双胞胎（都画加速度 vs 时间），但数据接口不同：`AccelTimeChart` 消费 `LapTelemetrySample.accelerationG: Double?`（圈速场景，elapsedMsInLap 时间轴），`GForceChart` 消费 `List<GpsDataPoint>`（0-100 / 100-0 性能测试场景，elapsedTime 秒时间轴 + 内部派生 G）。两者数据类型 / 单位 / 场景不同，Tier 2 父屏按场景区分调用
- `LapTelemetrySample` 类型契约由 entry sketch §1 定签（`absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg? / accelerationG?`），由 W1 `lap-data-readers` round 在 `core/domain/.../model/LapTelemetry.kt` 实施落地；本 round 仅消费类型签名 + mock 数据，不依赖 W1 实施完成
- V2 视觉规则（`gps-app/CLAUDE.md` "UI 视觉约束" 节）已在 add-lap-session-phase1 round 落地——七段字体仅用于纯数字仪表瞬时读数 + metric/row/label 类 Text 严格 `maxLines = 1, overflow = TextOverflow.Ellipsis`

**用户场景**：Tier 2 详情屏 / 比较屏的核心交互是**时间游标拖动**——用户在 SpeedTimeChart 上拖动游标 → AccelTimeChart / SectorBar / TrackPolylineMap 同步高亮该时间点的数据 / 位置。游标状态属于 Tier 2 父屏（多组件共享），本 round 的组件**不持有 cursor 状态**——通过 `cursorAbsoluteTs: Long?` 入参 + `onCursorChange: (Long) -> Unit` 出参把状态权交给外部。本 round 期间 user 用 Compose preview + 单测验收单组件行为，不组屏。

**为什么是现在**：Phase 1 已开 4 worktree 并行（W1/W2/W3/W4），看板 §5 已登记 W2 状态"待启动（worktree 已创 e2a42a1）"。本 round 是 Tier 1 并行四件套之一，且与 W1/W3/W4 文件级 0 交叉——尽快启动 + Compose preview 自闭环开发不阻塞 W1 数据底座，达到合回主区时即可被 Tier 2 round 消费。

## What Changes

- **新建** `feature/test/.../ui/components/SpeedTimeChart.kt`：消费 `List<LapTelemetrySample>`，x = `elapsedMsInLap`，y = `speedKmh`；签名 `(samples, cursorAbsoluteTs, onCursorChange, modifier)`；含 `@Preview` + V2 视觉
- **新建** `feature/test/.../ui/components/AccelTimeChart.kt`：消费同 sample 列表，y = `accelerationG`（nullable 字段 → 全 null 显示"NO ACCEL DATA"占位 + 部分 null 跳点不连线）；同 cursor 联动签名
- **新建** `feature/test/.../ui/components/SectorBar.kt`：纯展示水平 bar，sector 边界用 `sectorBoundaries: List<Long>` + `lapStartWallClock` + `lapEndWallClock` 划分；`cursorAbsoluteTs` 高亮当前 sector；**不带** `onSectorClick` callback（user 拍板 = B 纯展示）
- **新建** `feature/test/.../ui/components/TrackPolylineMap.kt`：纯 polyline（无第三方 map SDK / 无网格背景，user 拍板 = A）；用 `samples` 中 lat/lon 自动 fit + scale 到 canvas，cursor 处高亮位置点
- **新建** `feature/test/src/test/.../ui/components/MockTelemetry.kt`：测试 helper，含 `mockSingleLap(n=100, lapDurationMs=60_000)` 合成正弦波速度 + 圆周轨迹 + `mockMultiLap(n=3)` 三圈不同 pace；**仅 src/test 可用**，生产代码不引用
- **新建** 4 个 contract test 文件 `*ContractTest.kt`（每组件 1 份）：覆盖 cursor callback / 空 sample 列表 / null cursor / `accelerationG` nullable 处理 / 边界场景
- **不改动** 任何现有文件——全部新建，与 W1/W3/W4 文件级 0 交叉
- **不消费** repository 真实数据——所有验收走 mock telemetry + Compose preview；Tier 2 round 才接真实 API

## Capabilities

### New Capabilities

- `lap-telemetry-chart-components`：定义消费 `LapTelemetrySample` 类型的 4 个 Composable 组件库的契约——cursor 状态外置协议、视觉规则贴合 V2、nullable 字段降级行为、mock 数据驱动开发约束

### Modified Capabilities

无（本 round 不修改任何现有 spec 的 requirement）。

## Impact

**受影响代码**：
- 新增文件：`feature/test/src/main/java/com/blazepush/feature/test/ui/components/{SpeedTimeChart,AccelTimeChart,SectorBar,TrackPolylineMap}.kt`（4 个生产 Composable）
- 新增文件：`feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`（mock helper）
- 新增文件：`feature/test/src/test/java/com/blazepush/feature/test/ui/components/{SpeedTimeChartContractTest,AccelTimeChartContractTest,SectorBarContractTest,TrackPolylineMapContractTest}.kt`（4 contract test）
- 不改动：现有 `SpeedChart.kt`（V1 PERFORMANCE_TEST 时代组件，内含 `SpeedChart` + `GForceChart` 两个 Composable；`SpeedTimeChart` ≠ `SpeedChart` 命名不冲突；`AccelTimeChart` 与 `GForceChart` 数据接口不同——见 §Why 段说明）

**类型依赖**：`LapTelemetrySample`（entry sketch §1 已定签，W1 round 在 `core/domain/.../model/LapTelemetry.kt` 实施；本 round 不依赖 W1 实施完成——签名稳定即可 mock 驱动开发）。W1 实施期发现需调签名 → user 拍板 + 同步本 session。

**与 W1/W3/W4 文件级 0 交叉**：本 round 全新建文件，看板 §6 共享文件登记**不需要**。

**Tier 2 解锁**：W2 合回后，`lap-detail-screen-with-cursor`（消费 W1 + W2）与 `lap-comparison-screen-with-cursor`（消费 W1 + W2 + W3）才有组件可组屏。

**协议兼容性**：不涉及 RaceChrono BLE 协议；不涉及 binary 文件格式；不涉及 Room schema。纯 UI 层新增。

**双端改动**：仅接收端 gps-app（feature/test 模块），不影响 simulator。
