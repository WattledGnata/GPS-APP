## Why

### 问题溯源

W2 round `chart-and-map-components`（归档 `archive/2026-05-04-chart-and-map-components`，合回 commit `fc0afc1`）落地了 4 个 chart/map 组件（`SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap`）及其 contract test。这些组件的契约测试用一个 **test-only 占位容器** `FakeLapTelemetry` 来组装测试数据（定义在 `feature/test/src/test/.../ui/components/MockTelemetry.kt:14`）：

```kotlin
internal data class FakeLapTelemetry(
    val samples: List<LapTelemetrySample>,
    val sectorBoundaries: List<Long>,
    val lapStartWallClock: Long,
    val lapEndWallClock: Long,
)
```

W2 当时**故意不触碰** `core/domain`（独占路径边界，见 W2 `design.md:43` Decision C），等 W1 `lap-data-readers` round 合回后再切到正式类型。W2 的 `tasks.md:113`（§11.2）明确把这个切换登记为 follow-up round，round 名就是本 round `wire-mock-telemetry-to-w1-real-classes`，责任主体 = CC 主会话，触发条件 = W1 合回那一刻。

W1 已合回归档（`archive/2026-05-04-lap-data-readers`），正式 `LapTelemetry` / `LapTelemetrySample` 已 land 在 `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`。`TelemetryRepository.getLapTelemetry(sessionId, lapIndex)`（`core/data/.../TestResultRepository... → TelemetryRepository.kt:272`）已经在生产返回正式 `LapTelemetry`。**触发条件已满足，但切换从未执行**——chart 组件的 contract test 至今仍断言一个生产 APK 不存在的占位类型 `FakeLapTelemetry`，没有任何一条测试证明 chart 组件能直接消费 `getLapTelemetry` 的真实输出形态。

### 当前 baseline（grep 核实）

- 正式类型（`core/domain/.../model/LapTelemetry.kt`）：
  - `LapTelemetrySample`（8 字段）：`absoluteTsMs:Long` / `elapsedMsInLap:Long` / `lat:Double` / `lon:Double` / `speedKmh:Double` / `bearingDeg:Double?` / `accelerationG:Double?=null` / **`flags:Int=0`**（W1 commit `3c2f2d9` 追加）。
  - `LapTelemetry`（9 字段）：`sessionId:String` / `lapIndex:Int` / `lapStartWallClock:Long` / `lapEndWallClock:Long` / `lapDurationMs:Long` / `samples:List<LapTelemetrySample>` / `sectorBoundaries:List<Long>` / `trackId:String?` / `trackNameSnapshot:String?`。
- 占位容器 `FakeLapTelemetry`（`MockTelemetry.kt:14`）仅 4 字段：`samples` / `sectorBoundaries` / `lapStartWallClock` / `lapEndWallClock`。**真实 `LapTelemetry` 比它多 5 字段**：`sessionId` / `lapIndex` / `lapDurationMs`（3 个 non-null 必填）+ `trackId` / `trackNameSnapshot`（2 个 nullable）。
- W2 的 L2 review（`archive/2026-05-04-chart-and-map-components/review-l2-opus-b.md:15` 的 P1-1）**已显式 flag**：tasks.md §11.2 描述的「删除 FakeLapTelemetry / 改 import / 改返回类型」切换路径**过于乐观**——简单 import 切换会让 `mockSingleLap` / `mockMultiLap` 编译失败，因为它们的 `FakeLapTelemetry(...)` 构造调用只填了 4 字段，real `LapTelemetry` 需要补全 5 字段。修订建议原文：「明确 follow-up round scope 必须包括补全 5 个 W1 容器字段」。
- 4 个 chart 组件的**消费形态**（grep contract test 核实）：组件的 pure helper（`computeChartCoordinates` / `findNearestSampleIndex` / `computeSectorBounds` / `computeMapBoundingBox` / `mapLatLonToCanvas` / `computeAccelSegments` / `computeChartBounds`）只吃 `List<LapTelemetrySample>`（来自 `lap.samples`）、`List<Long>`（来自 `lap.sectorBoundaries`）、`Long`（来自 `lap.lapStartWallClock` / `lap.lapEndWallClock`）。**它们从不接受容器类型本身**——所以容器从 `FakeLapTelemetry` 换成 `LapTelemetry` 后，4 字段读取点（`lap.samples` / `lap.sectorBoundaries` / `lap.lapStartWallClock` / `lap.lapEndWallClock`）全部在 real `LapTelemetry` 上同名存在，消费侧零改动。

### 用户场景

这是 Phase 1 Tier2 `lap-detail-screen-with-cursor`（单圈详情屏，M2 里程碑）的**前置依赖**（路线图 §3 Phase 1 收尾表 + §4 第二批：detail 屏依赖第一批线 B `wire-mock-telemetry`）。detail 屏会在 `LaunchedEffect` 里调 `getLapTelemetry(sessionId, lapIndex)` 拿到真实 `LapTelemetry`，直接喂给这 4 个组件。如果 chart 组件的契约从未对正式 `LapTelemetry` 输出形态做过断言，detail 屏组屏时才会暴露「mock 数据能跑、真实 reader 输出跑不通」的类型/形态不匹配，把本应在测试层关掉的风险推到 UI 组装期。本 round 在测试层锁死「4 组件可直接消费 `getLapTelemetry` 的真实 `LapTelemetry` 输出形态」，让 detail 屏组屏建立在已验证的契约上。

## What Changes

- **Modified（test-only，唯一生产改动面）**：`feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`
  - **删除** `internal data class FakeLapTelemetry`（4 字段占位类，`MockTelemetry.kt:14-19`）。
  - **改 import**：新增 `import com.blazepush.core.domain.model.LapTelemetry`（`LapTelemetrySample` 已 import）。
  - `mockSingleLap(...)` 返回类型 `FakeLapTelemetry` → `LapTelemetry`，构造调用补全 5 个 W1 字段（见 design Decision 2 的字段填充表）：`sessionId` / `lapIndex` / `lapDurationMs` / `trackId` / `trackNameSnapshot`。
  - `mockMultiLap(...)` 返回类型 `List<FakeLapTelemetry>` → `List<LapTelemetry>`；其内部 `.copy(...)` + 重建容器同步切到 `LapTelemetry`，且为多圈逐圈赋递增的 `lapIndex`（0,1,2,...）+ 正确的 `lapDurationMs`。
- **Verify-only（不改逻辑，apply 期 #3/#16 自查确认仍绿）**：`SpeedTimeChartContractTest.kt` / `AccelTimeChartContractTest.kt` / `SectorBarContractTest.kt` / `TrackPolylineMapContractTest.kt` / `GrepGateTest.kt` —— 这 5 个测试文件消费 `mockSingleLap(...)` / `mockMultiLap(...)` 的 4 个字段（`.samples` / `.sectorBoundaries` / `.lapStartWallClock` / `.lapEndWallClock`），切换后这些字段在 real `LapTelemetry` 上全部同名存在，断言不变即仍绿。**仅当 apply 期实测某断言因 real 容器形态变化而 fail 时，才允许对应微调**（见 design Decision 3 升级路径）。

**明确不做**（scope 收紧，防半闭环）：

- **不**改 4 个 chart/map 生产组件（`SpeedTimeChart.kt` / `AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt`）的任何签名——它们消费 `List<LapTelemetrySample>` + 原始类型，不消费容器类型，容器替换对它们透明（grep 核实，见 design Decision 1）。
- **不**改 `core/domain` 的 `LapTelemetry` / `LapTelemetrySample` 任何字段（公共数据契约，本 round 是消费方对齐，不是契约扩张）。
- **不**改 `getLapTelemetry` reader（`TelemetryRepository.kt:272`）——本 round 验证消费方与它的**现有输出形态**对齐，不改 reader。
- **不**新建 detail/comparison 屏，**不**做组屏/接线/导航——那是 `lap-detail-screen-with-cursor` round 的 scope。
- **不**引入 `gridIndex` 跨圈映射组件 API 改造——那是 `lap-comparison-screen-with-cursor`（large）的 scope。
- **不**改 `getLapTelemetry` 当前 `sectorBoundaries = listOf(lapStartWallClock)`（单元素）/ `accelerationG = null`（硬编码）的填充语义——那是 `future-sector-derivation-round` / detail 屏 R1 的 scope；mock 仍保留 3-sector + 中央差分 accelerationG 的**更丰富**测试数据（合法：mock 比当前 reader 输出更丰富，用于锁死组件多段渲染能力，见 design Decision 4）。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `lap-telemetry-chart-components`: 该 capability 由 W2 `chart-and-map-components` round 引入（归档于 `archive/2026-05-04-chart-and-map-components/specs/lap-telemetry-chart-components/spec.md`，未 sync 到主 `openspec/specs/`）。本 round 增量修订其「mock 数据 helper」相关 normative：把 `mockSingleLap`/`mockMultiLap` 的返回类型从占位 `FakeLapTelemetry` 改为正式 `LapTelemetry`，并锁死「4 组件 pure helper 可直接消费正式 `LapTelemetry` 输出形态」契约。

## Impact

### 受影响代码

- **修改测试**：`feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`（删 `FakeLapTelemetry` + 改 2 个 helper 返回类型 + 补全 5 字段）。
- **Verify-only（预期零 diff）**：`SpeedTimeChartContractTest.kt` / `AccelTimeChartContractTest.kt` / `SectorBarContractTest.kt` / `TrackPolylineMapContractTest.kt` / `GrepGateTest.kt`。

### 不受影响

- `core/domain` / `core/data` / `app` / `simulator` 全部生产模块（本 round 纯 test source set 改动，生产 APK 0 diff）。
- 4 个 chart/map 生产组件（消费原始类型，容器替换透明）。
- `getLapTelemetry` reader（本 round 验证消费方对齐它的现有输出，不改它）。

### 协议兼容性

无协议改动。本 round 不触碰 RaceChrono BLE 协议 / GPS 接收链路 / binary writer / 任何公共 API 签名；纯接收端测试代码对齐 W1 已 land 的 domain 类型。

### 双端

仅接收端（gps-app）测试代码改动；发射端（simulator）不动。

### 多 change 并行协同

本 round 独占 `feature/test/src/test/java/com/blazepush/feature/test/ui/components/`（纯测试侧）。看板 §5 登记表当前无并行 round 占用该目录（W2 已合回归档 `fc0afc1`；H round `improve-test-execution-progress-bar` 独占 `feature/test/.../ui/tracktech/`，与本 round `ui/components/` 零交叉；G round `redesign-performance-result-screen` 已 done，触碰的是 `ui/components/SpeedChart.kt` / `GForceChart.kt`，与本 round 改的 `MockTelemetry.kt` + 4 个 chart contract test 零交叉）。无串行依赖。

### 测试影响

- `MockTelemetry.kt` 切换后 `:feature:test:compileDebugUnitTestKotlin` MUST 通过（real `LapTelemetry` 全 9 字段构造）。
- 4 个 ContractTest + GrepGateTest（`:feature:test:testDebugUnitTest --tests "*ui.components*"`）MUST 零回归。
- 真机验证：本 round 纯测试代码改动，无 UI / 运行时行为改动 → 真机 SKIP（与加速通道是否走无关，按 round 实际 UI 路径判定）。
