## Why

`LapDebugExecutionScreen.rememberStartFinishTimingCardState`（line 197，**名叫 remember 但不是 `@Composable`** + 不用 `remember { ... }` 缓存）每次 recomposition 全量 filter + 全量 haversine：25Hz × 7500 sample/圈 × 4 三角函数 ≈ 每秒 187,500 次 haversine；真机长圈有 UI 掉帧风险。Round 1/2 已经关闭主线程同步 I/O（A18）和冷启动阻塞（A37），叠加放大效应消失，本 round 是 F 战役性能线的收尾。

更根本的问题是**"本圈已跑距离"在 UI 层重复计算**：每次 recomposition 都遍历 samples 重新求和。本 change 把这个派生量从 UI 层 pull 式 O(N) 搬到 engine 层 push 式 O(1) 增量，作为 `ActiveLap.distanceMetersSinceStart` 暴露，建立**单一 producer**（engine）+ **consumer-only**（UI）契约。

**Why 范围澄清**：判圈几何（engine 用线段叉积 / `crossingProgress` 做穿线投影）与"本圈已跑距离"（UI 显示用相邻 samples haversine 累积）是**不同概念**，本 change **不要求二者统一**。前者关心"何时穿线"的几何精度，后者关心"已跑多远"的轨迹长度，公式与目的均不同。本 round 解决的是"distance 单一 producer"，不是"engine 与 UI 共用同一距离公式"。

## 核销条件修订（申请评审方批准）

**当前 backlog A22 核销条件 (3) 与本 proposal 的 Non-goal 冲突**，本 change 申请将其修订为：

| backlog 原条件 | proposal v2 申请修订为 |
|---|---|
| (3) 与 A15（穿线插值）合并：引擎和 UI 统一使用同一距离定义，解决"UI haversine vs 引擎欧氏叉积"不同源 | (3) **engine 是 `ActiveLap.distanceMetersSinceStart` 的唯一 producer，UI 只读该字段不自算**；判圈几何（叉积 / crossingProgress）与本圈已跑距离是不同概念，本 change 不要求二者统一公式 |
| (5) 性能回归（Robolectric 或 benchmark）：7500 样本 recomposition < 16ms | (5) **可机器核销的替代门槛**（**保留**性能意图，**拒收** Robolectric/benchmark 的工程成本）：(a) 源码断言 `LapDebugExecutionScreen.kt` 不再含 `calculateDistanceSince` / 私有 `haversineDistanceMeters` / 任何 `samples.zipWithNext` 或 `samples.filter { it.timestampMillis }` 的 distance 计算 pattern；(b) UI state 函数 7500 samples 输入下纯函数调用 < 16ms 的 unit-level smoke（`measureNanoTime` 包裹直接调 `rememberStartFinishTimingCardState`，无 Robolectric / Compose runtime 依赖）|

修订理由：(3) 原表述把判圈几何与显示距离绑死，scope 蔓延到战役 C 几何模型且无实际正确性收益；(5) Robolectric/benchmark 工程成本远大于 A22 收益，但保留"7500 samples 性能保护"的实质门槛改为可机器核销 smoke + 源码零残留断言。

## What Changes

### 核心改造（3 层）

- **model 层**：`ActiveLap` 新增字段 `distanceMetersSinceStart: Double`（默认 0.0），语义明示"从开圈点累计到当前帧"；**不**修改 `LapRecord` schema
- **engine 层**：`LapTimingEngine.processSample` 在 `updatedSamples = session.samples + currentSample` 之后、所有 detector 分支之前，**集中构造一次** `activeLapWithDistance`；所有"保留当前 active lap"的返回路径**统一**使用 `activeLapWithDistance`（不是只在某一条 copy 路径里更新）。增量来源**MUST** 是 `session.samples.lastOrNull()` 与 `currentSample` 的相邻 samples 流（**不是** `previousSample` 参数），与旧 UI `samples.zipWithNext()` 口径一致，避免 detector "上一帧" 与 samples 流时序差异

  ```kotlin
  // 推荐实现形态（design 阶段固化最终细节）
  val updatedSamples = session.samples + currentSample
  val activeLapWithDistance: ActiveLap? = session.activeLap?.let { current ->
      val prev = session.samples.lastOrNull()
      if (prev != null) {
          current.copy(
              distanceMetersSinceStart = current.distanceMetersSinceStart +
                  haversineDistanceMeters(prev.latitude, prev.longitude, currentSample.latitude, currentSample.longitude)
          )
      } else {
          current  // active lap 已存在但 samples 空（理论不应发生，因为开圈帧必入 samples）
      }
  }
  // 后续所有 session.copy(activeLap = X) 必须用 activeLapWithDistance 派生
  ```

  **5 类 processSample 返回路径分别处理**（基于现有 `LapTimingEngine.kt:65-108` 与 `:233-303` 真实结构）：

  | 路径 | 当前位置 | distance 处理 |
  |---|---|---|
  | (a) **ts 回跳早退** | line 73 `return session` | 不动（current sample 不入 samples，无新增距离） |
  | (b) **start-finish accepted 第一次开圈** | `handleStartFinishCrossing` line 134-148 `activeLap = ActiveLap(...)` | 显式 `distanceMetersSinceStart = 0.0`（新 ActiveLap 构造时） |
  | (c) **start-finish accepted 闭圈** | `handleStartFinishCrossing` line 150+ | **closing active lap 累入闭圈帧**（current 仍是该 lap 最后一帧）；`LapRecord` 构造规则不变；闭圈后**若**新开 lap 则新 `ActiveLap.distanceMetersSinceStart = 0.0`（与 b 同语义） |
  | (d) **no target gate**（line 98 `session.copy(samples = updatedSamples)`） | 普通采样路径 | MUST 改为 `session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)` 携带累距 |
  | (e) **sector rejected**（line 287-289 `session.copy(samples, crossingEvents)`） | active lap 保留 | MUST 改为带 `activeLap = activeLapWithDistance` |
  | (f) **sector accepted**（line 292-303 `activeLap = activeLap.copy(passedGateIds=...)`） | active lap copy 加 sector | MUST 用 `activeLapWithDistance.copy(passedGateIds = ..., sectorEntries = ...)` 而非 `session.activeLap.copy(...)` |

  **关键不变量**：闭圈后新 ActiveLap 的 `distanceMetersSinceStart` 从 0 重开（与第一次开圈语义一致）；active lap 生命期内单调不减；无 active lap 时根本不构造 `activeLapWithDistance`
- **UI 层**：`LapDebugExecutionScreen.rememberStartFinishTimingCardState` 读 `lapSession.activeLap?.distanceMetersSinceStart ?: 0.0`，不再调 `calculateDistanceSince` 与 `haversineDistanceMeters`；**两个私有函数整体删除**（UI 层不再做三角函数）

### 累积语义（5 条硬契约）

1. **只在 active lap 有上一帧时累加**：`session.activeLap != null && session.samples.isNotEmpty()` 双条件成立时才做 `+= haversine(prev, current)`；第一帧 / 开圈帧（ActiveLap 构造时自带 `distanceMetersSinceStart = 0.0`）增量为 0
2. **异常帧处理沿用现状**：不引入新的过滤/白名单；`processSample` 顶部已有的 ts 回跳守卫 / filter 结果决定样本是否进入 `session.samples`，距离计算跟着 samples 流走
3. **距离单调不减**：active lap 生命期内，`distanceMetersSinceStart` 只增加不回退（haversine 返回非负）；开圈是新 ActiveLap 实例，从 0 重开
4. **haversine 算法**：`haversineDistanceMeters(prev.lat, prev.lon, current.lat, current.lon)`，复用 UI 层原 `haversineDistanceMeters` 实现体（移植为 engine 层 top-level 或 domain `GeoMath` 工具），跨经度 ±180° 用 `wrappedDeltaLon` helper **本 round 不引入**（A22 目标不是跨经度正确性；战役 C 三期 A44 已为 filter 层提供，future round 再统一）
5. **闭圈规则不变**：`LapRecord.trajectory` 构造规则完全不动；`LapRecord` 不新增 distance 字段（A51 规划项）

### 删除

- `LapDebugExecutionScreen.kt:239-275` `calculateDistanceSince` + `haversineDistanceMeters` 两个 private fun（UI 层不再做几何计算）

## Capabilities

### New Capabilities

- `active-lap-distance-accumulator`：定义 `ActiveLap.distanceMetersSinceStart` 的语义契约（开圈 = 0、每帧 `+= haversine(prev, current)`、单调不减、active lap 生命期），engine 是**唯一 producer**，UI 是 **consumer only**，禁止 UI 层自行重算

### Modified Capabilities

无。`openspec/specs/` 当前为空，全部走 New Capabilities。

## Impact

### 受影响模块路径

- `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt`（加字段 `distanceMetersSinceStart: Double = 0.0`）
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`（每帧 append 后增量累积，2 处 `ActiveLap(...)` 构造点显式初始化 `0.0`；`processSample` 内关键 hook 在 `updatedSamples = session.samples + currentSample` 后）
- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`（line 231-233 读 engine 字段；line 239-275 删除两个 private fun）
- **新建**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GeoMath.kt`（或等价：把 `haversineDistanceMeters` 从 UI 迁到 engine 可复用位置；tasks 阶段决定最终文件名）
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`（新增增量累积契约测试）
- `feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt`（若不存在则新建；断言 UI card state 读 engine 字段，`LapDebugExecutionScreenStateTest.currentLapDistance_matchesIncrementalSum`）

### 不受影响的边界

- `core/bluetooth`：零改动（不触碰 parser / BLE）
- `core/domain`：零改动（不碰 `LapRecord` / GpsData / 协议字段）
- `LapSession` / `CrossingEvent` 等其他 data class：零改动
- `simulator`：零改动

### 协议兼容性

**N/A** —— 不涉及 `GpsData` / BLE / RaceChrono 协议字段。

### 双端任务范围

**仅接收端（gps-app）** —— 不涉及发射端 simulator 改动。

## Non-goals（scope 硬边界）

- **不改 `LapRecord` schema**（不新增 `distanceMeters` 字段；A51 规划项负责）
- **不做 A51** `progressMeters` / `progressTimeline`（Phase 2 规划，依赖 reference path projection）
- **不做 A35** `currentLap` UI 文案修复（Round 5 处理）
- **不做 composable 微优化**（不引入 `derivedStateOf` / `Snapshot.observe` / `remember { }` 缓存 card state；本 round 关的是"UI 不自算"而非"recomposition 本身更便宜"）
- **不引入跨经度 helper**（A44 `wrappedDeltaLon` 已在 `core/domain` 为 filter 层提供，本 round 不跨层消费它；未来若需要跨经度正确距离，另 round 统一）
- **不碰 A17 / A30**（Round 4 处理）
- **不统一 engine 判圈几何 vs 距离累积口径**（engine 判圈用线段叉积 / crossingProgress，UI 显示"已跑距离"用轨迹相邻点 haversine —— 这是不同概念，本 round 明示分离）
- **A56 长期 GPS 点阵持久化边界**（**Finding 3 修补**）：本 change **不**设计、**不**实现长圈密集 GPS 点阵的持久化模型。`ActiveLap.distanceMetersSinceStart` 是**运行期 active-lap 派生状态**，**不**是长期 telemetry 真相源；本 round **不**新增数据库 / chunk / JSON telemetry schema / Room metadata；future A56 round 可能引入与本字段独立的持久化路径，与本字段并存而非取代

## 验收门槛（进入 `/opsx:apply` 前）

- `openspec validate fix-active-lap-distance-accumulator --strict` 通过
- 下游零回归：`:feature:test:testDebugUnitTest` + `:core:bluetooth:testDebugUnitTest` + `:core:domain:test` + `:app:compileDebugKotlin`
- E2E 契约 `*EndToEndLapTimingContractTest*` 全绿（`LapSession.samples` / `ActiveLap` 行为不回归）
- **源码零残留**（机器核销）：`grep -nE "calculateDistanceSince|haversineDistanceMeters" feature/test/src/main/java/com/blazepush/feature/test/ui/screen/` **零命中**；`grep -nE "samples\.zipWithNext|samples\.filter \{ .*timestampMillis" feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` **零命中**（UI 层不再做 distance pattern 计算）
- **性能 smoke**（替代 Robolectric/benchmark，机器核销）：unit-level test 用 `measureNanoTime` 包裹 `rememberStartFinishTimingCardState(lapSession=session-with-7500-samples-and-distanceMetersSinceStart, isTimeSynced=true)` 直接调用，断言 **< 16ms**；不依赖 Compose runtime / Robolectric / Android Looper
- backlog A22 迁 🟢 `pending_review` + 附录表格状态列同步 + **核销条件 (3) 与 (5) 修订**正式落入 backlog（review 通过后由本 change apply 阶段顺手更新）

## 基线

commit `fabb285`（Round 2 已核销）。
