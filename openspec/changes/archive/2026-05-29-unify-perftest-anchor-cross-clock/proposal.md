## Why

W1 `lap-data-readers` round 的 L2 review（Opus 双线，B 线 P1-1）发现 `TestResultRepository.getDataPointsForResult` 的核心派生

```kotlin
val absoluteTsMs = testStartWallClock + sample.tsDeltaMs   // testStartWallClock = entity.timestamp
```

是**跨时钟域加法**：

- `entity.timestamp` 链路（`RaceChronoParser.kt:240` → `TestSessionViewModel.startTime` → `CalculateResultUseCase` → `TestRecordEntity`）落地的是 **GPS 协议时间**（`hourStartMillis + timeSinceHourStart`，卫星 UTC 衍生），且 GPS 未同步时为 sentinel `Long.MIN_VALUE`
- `sample.tsDeltaMs` 链路（`TestSessionViewModel.kt:744`）落地的是**接收侧本地 `System.currentTimeMillis()` delta**

两个 anchor 不同源。归档 spec Requirement 2（line 76）把这条写成 `（§8.4/M anchor 已对齐）`——这是 **unargued assertion**，没解释"对齐"的 invariant 是什么、什么前提下成立（L2 review trail `archive/2026-05-04-lap-data-readers/review-l2-opus-b.md` P1-1 已显式 flag）。

**当前不会崩，但 reader 侧零防线**：PERFORMANCE_TEST 的 trigger guard（`satellites>=6 + hdop<2.0`）已阻断 sentinel 数据进 binary，所以生产实务概率为 0；但 `getDataPointsForResult` reader 侧**没有任何 sentinel guard**——未来若有人改动 trigger guard，reader 会拿到 `entity.timestamp = Long.MIN_VALUE`，让 `absoluteTsMs ≈ Long.MIN_VALUE` 的样本流灌进 chart，**整个时间轴语义崩塌**（Phase 1 Tier2 `lap-detail-screen-with-cursor` 的 SpeedCurveReal 正是 `getDataPointsForResult` 的下游消费方）。

**为什么现在做**：Tier2 `lap-detail-screen-with-cursor` round 落地前必须补上这道防线，否则 chart 消费方裸奔。设计层分析（3 个 alternatives + 数学/性能误差范围 + MUST 条款）已在 deferred memo `docs/design/lap-perftest-anchor-cross-clock-deferred.md` §3-4 做完，本 round 是 defensive 加固的机械实施。

## What Changes

- **Modified**：`core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt:getDataPointsForResult` —— 在 `if (entity.dataFilePath.isEmpty()) return null`（当前 line 145）之后加一行 sentinel guard：
  ```kotlin
  if (entity.timestamp == Long.MIN_VALUE) return null
  ```
- **Modified spec**：归档 `lap-data-readers` 的 `lap-telemetry-readers` capability Requirement「PERFORMANCE_TEST 完整 dataPoints 切片读取」：
  - 加 normative：`entity.timestamp != Long.MIN_VALUE` 是前提，为 sentinel 时返回 null（writer 侧 trigger guard 已阻断，reader 侧加 defensive 防 future trigger guard 回归）
  - 把 line 76「§8.4/M anchor 已对齐」替换为**显式 invariant 三条款**（entity.timestamp non-sentinel + GPS-UTC-本地壁钟漂移容许 + session 内无 GPS 失锁→重同步周期）
  - 加 1 个反例 scenario：sentinel `entity.timestamp = Long.MIN_VALUE` → reader 返回 null（且 sentinel 阻断在 `readPerformanceSamples` 调用之前）
- **Modified test**：`core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt` 加 case L（现有 A-J 共 10 cases → 11），覆盖 sentinel `entity.timestamp` 返回 null + verify 0 次 `readPerformanceSamples` 调用

**明确不做**（方案 B/C 推 P3 backlog，见 design Decision 1 拒绝理由）：

- **不**改 `TestRecord.timestamp` 字段语义（不迁移到本地壁钟域）
- **不**引入 Room schema migration（不加 `binaryStartWallClock` 冗余列）
- **不**改 `RaceChronoParser` / GPS 接收链路 / 任何公共协议字段（本 round 仅在 reader 消费层加 defensive guard）
- **不**改 `getLapTelemetry`（圈速 reader 路径）—— 它用 `crossingWallClockTimestampMs`（本地壁钟，已同源），不存在本 round 修的跨时钟域问题

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `lap-telemetry-readers`: Requirement「PERFORMANCE_TEST 完整 dataPoints 切片读取（getDataPointsForResult）」加 sentinel guard normative + invariant 三条款显式化 + 反例 scenario。该 capability 由 W1 `lap-data-readers` round 引入（归档于 `archive/2026-05-04-lap-data-readers/specs/lap-telemetry-readers/spec.md`，未 sync 到主 `openspec/specs/`），本 round 增量修订其 PERFORMANCE 路径的跨时钟域 normative。

## Impact

### 受影响代码

- **修改**：`core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`（`getDataPointsForResult` 加 1 行 guard）
- **修改测试**：`core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt`（加 case L）
- **修改归档 spec**：`openspec/changes/archive/2026-05-04-lap-data-readers/specs/lap-telemetry-readers/spec.md`（Requirement 2 增量同步——precedent：W1 归档 commit `0cd9dbc` 已有修订归档 spec 的先例）

### 不受影响

- `core/domain`、`feature/test`、`app`、`simulator` 全部模块（本 round 独占 `core/data` 的单文件 + 单测）
- `getLapTelemetry`（圈速 reader）—— 用 wallClock 已同源
- chart 消费方（W2 `SpeedTimeChart` / Tier2 SpeedCurveReal）—— 当前在 trigger guard 前提下消费安全，本 round 是 defensive 加固不阻塞其落地
- H round `improve-test-execution-progress-bar`（`feature/test` 独占，零文件交叉，可并行）

### 协议兼容性

无协议改动。本 round 不触碰 RaceChrono BLE 协议字段 / 编码 / GPS 接收链路；仅在接收端 reader 消费层加 sentinel guard。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

本 round 独占 `core/data` 的 `TestResultRepository.kt` + `LapTelemetryReadersTest.kt`。看板 §5/§6 当前无并行 round 占用该文件（H round 独占 `feature/test`，W1-W4 已合回归档）。无串行依赖。

### 测试影响

- `LapTelemetryReadersTest` 加 1 case（10 → 11）
- `:core:data:testDebugUnitTest` 全套 MUST 零回归
- 真机验证：本 round 纯数据层 defensive guard，无 UI 改动 → 真机 SKIP（与加速通道是否走无关，按 round 实际 UI 路径判定）
