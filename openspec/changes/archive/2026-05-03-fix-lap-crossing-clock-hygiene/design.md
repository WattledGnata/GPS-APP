## Context

`fix-lap-binary-ts-hygiene` round（已合回 daca418，归档 599562e）通过 `repository.activeSessionStartTs` property + bridge anchor 切换，让 lap session binary samples 的 `absoluteTs = header.startTs + ts_delta_ms` 落在接收侧真壁钟时钟域，与 `entity.startTs / endTs` 同源。session 全窗口的 `readLapSamples(file, entity.startTs, entity.endTs)` 因此可用。

**但 per-lap / sector segment 窗口仍不可用**——`TelemetryCrossingEvent.crossingTimestampMs` 这条数据流从未触碰，仍是 GPS 协议时间：

```
LapTimingEngine.processSample(sample)
  ↓ sample.timestampMillis = GpsData.timestamp（协议解码 epoch ms）
crossing.timestampMillis = sample.timestampMillis（in-memory CrossingEvent，feature/test/.../laptiming/CrossingEvent.kt）
  ↑ 这是 LapTimingEngine pure function 产出物，无 wallClock 注入路径，不在本 round scope 修改
  ↓ ViewModel 消费（in-memory lapSession.crossingEvents 直接喂 LapDebugExecutionScreen UI 显示）
  ↓ ViewModel 同时跨层 → Room 持久化层
TestSessionViewModel.kt:891 附近 writeCrossing 调用栈
  TelemetryCrossingEvent(crossingTimestampMs = crossing.timestampMillis, ...)
  ↓ pass-through
TelemetryRepository.writeCrossing → CrossingEventEntity.crossingTimestampMs
  ↑ Room 持久化，仍是协议时间
```

**关键：本 round 仅修 Room 持久化层（`TelemetryCrossingEvent` DTO + `CrossingEventEntity`），不动 `feature/test/.../laptiming/CrossingEvent.kt` 这个 in-memory pure domain object**。理由：
- in-memory `CrossingEvent` 由 `LapTimingEngine.processSample` 纯函数产出，唯一时间来源是输入 `sample.timestampMillis`（协议时间），无法在不破坏纯函数 contract 的前提下注入 `System.currentTimeMillis()`
- in-memory CrossingEvent 的下游消费方（LapDebugExecutionScreen 实时面板 + Snackbar lap save result）都在同一活跃帧/同一活跃小时段内做协议时间减法，跨时钟域问题不暴露，无需修
- wallClock 仅在 ViewModel 跨层进 Room 持久化的瞬间记录（同协程上下文取 `System.currentTimeMillis()`），不污染 in-memory 路径

任何"用 crossing.timestampMillis 当窗口截取 binary samples"的代码（如 `readLapSamples(file, crossings[i].timestampMs, crossings[i+1].timestampMs)`）都会因时钟域不匹配返回 0 命中，per-lap UI（Analysis Mode 单圈轨迹、sector 分段、Records LAPS sub-tab 圈分段）阻塞。

本 round 是 A 的对偶：**给 crossing event 加真壁钟字段**，与 binary samples 同时钟域，让 per-lap / sector 窗口的 readLapSamples 可用。

## Goals / Non-Goals

**Goals**：

- `TelemetryCrossingEvent`（domain DTO）+ `CrossingEventEntity`（Room schema）加 `crossingWallClockTimestampMs: Long?` 字段（**nullable**，接收侧真壁钟）。**不动** `feature/test/.../laptiming/CrossingEvent.kt`（in-memory pure domain object，由 LapTimingEngine 产出，无 wallClock 注入路径）
- 写入路径：过线事件触发的同一 ViewModel 协程上下文内取 `System.currentTimeMillis()`（避免与异步写入间产生延迟漂移）
- Room migration v4 → v5：`ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`（**nullable，无 NOT NULL 约束**，旧 row 自动 NULL）
- 单测覆盖：双字段独立可读 / per-lap 窗口 readLapSamples 命中 / Migration SQL 自检 / UI 显示语义不回归 grep gate
- 与 `fix-lap-binary-ts-hygiene` round-trip 测试互补——本 round 测的是"crossing wallClock 窗口截取 samples"

**Non-Goals**：

- 不改 `crossing.timestampMillis` 或 `crossingTimestampMs` 现有字段语义（保 UI 显示与历史代码不回归）
- 不改 `LapDebugExecutionScreen.kt:222-243` 的 HH:mm:ss 显示 + lap 时长减法逻辑
- 不动 `LapTimingEngine.processSample` 内部纯函数（输入仍是 `sample.timestampMillis = GpsData.timestamp` 协议时间，几何计算与圈速判定都依赖此协议时间，不能改）
- 不引入 per-lap UI（Analysis Mode 单圈轨迹 / Records LAPS 圈分段）；下游 UI 消费由独立 UI round 立项
- 不改 binary 字节布局（17 bytes/sample 契约保持不变；本 round 加的是 crossing event Room column，不是 binary sample 字段）
- 不改 RaceChrono BLE 公共协议任何字段
- 不做旧 crossing 数据迁移（v4→v5 migration ADD COLUMN 后旧 row wallClock = NULL，旧数据 per-lap 不可用，符合"修复仅对修复后写入的数据生效"边界）

## Decisions

### 决策 1：方案 A 双时钟域字段（不破坏现有显示）+ 3-class 字段对应表

**3-class 字段对应表**（写代码前必读，避免架构分层混乱）：

| Class | 层 | 来源 | 是否加 wallClock | 理由 |
|---|---|---|---|---|
| `feature/test/.../laptiming/CrossingEvent.kt` | in-memory pure domain | `LapTimingEngine.processSample` 纯函数 | ❌ **不改** | 纯函数 contract 不允许注入 `currentTimeMillis()`；下游消费方都在同一活跃小时段内做协议时间减法，跨时钟域问题不暴露 |
| `core/domain/.../TelemetryCrossingEvent` | domain DTO（跨进程接口） | `TestSessionViewModel.kt:891 附近` 构造 | ✅ **加** | repository 写入 Room 的 schema 边界；wallClock 在此构造时取值 |
| `core/data/.../CrossingEventEntity` | Room schema | `TelemetryRepository.writeCrossing` 把 DTO 映射到 Entity | ✅ **加** | Room 持久化字段，跟 DTO 同步；用 nullable Long?（见决策 3，调用方显式判 null fallback） |

**选择**：仅 `TelemetryCrossingEvent` + `CrossingEventEntity` 加 `crossingWallClockTimestampMs: Long?`（nullable），与 `crossingTimestampMs` 共存。

**对比**（详见 deferred memo §3）：

| 方案 | 改动量 | 影响 | 选择理由 |
|---|---|---|---|
| **A：双时钟域字段** | core/domain (~2) + core/data (~16) + feature/test (~1) ≈ 20 行 | 加新字段不改老语义 | 推荐 |
| B：crossing 改用真壁钟 | feature/test/.../laptiming/ 大改 + UI 显示需重新 review | 破坏 LapDebugExecutionScreen HH:mm:ss + lap 时长减法 | 不推荐 |
| C：reader 接受协议时间窗口 + binary 同时存协议 ts | binary 字节布局变（破坏 A56 17 bytes/sample 契约） | 偏离 A56 既定 schema | 不推荐 |

**Rationale**：
- A 改动面最小、不破坏现有 UI 显示语义、向前兼容（旧数据 wallClock = NULL → per-lap 不可用但不崩溃）
- A 让两个时钟域字段都"显式存在 + 各司其职"：`crossingTimestampMs` 给 UI 显示用（GPS 协议时间，跨设备对比时一致）；`crossingWallClockTimestampMs` 给 readLapSamples 窗口用（与 binary samples 同时钟域）
- 解决 deferred memo §4.1 数学问题（GPS 协议时间 vs 真壁钟在 sync 前/跨小时切换/simulator 重启时偏差不平稳，无法做后期换算）

### 决策 2：写入瞬间记录 wallClock（而非异步路径）

**选择**：在 `TestSessionViewModel.kt:891` 附近（构造 `TelemetryCrossingEvent` 调用 `repository.writeCrossing` 的位置；rebase 到主区 daca418 + F/G/I/J/K 合回后实际行号；apply 时按 `grep -n "TelemetryCrossingEvent(" TestSessionViewModel.kt` 实际命中位置为准）——过线事件触发的同一 ViewModel 协程上下文内——立即取 `System.currentTimeMillis()` 作为 wallClockTimestampMs。

```kotlin
// TestSessionViewModel.kt:891 附近改动
TelemetryCrossingEvent(
    sessionId = lapSessionId,
    lapIndex = lapIndexAtCrossing,
    crossingTimestampMs = crossing.timestampMillis,                // 保留：协议时间
    crossingWallClockTimestampMs = System.currentTimeMillis(),     // 新增：触发瞬间真壁钟
    speedKmh = ...,
    ...
)
```

**Rationale**：
- 过线事件触发到 writeCrossing 的间隔典型 < 1ms（同协程顺序调用），wallClock 误差可忽略
- 若改成 `repository.writeCrossing` 内部记录 wallClock，会把 binary writer queue 的延迟（典型几 ms - 几十 ms）算进 wallClock，跟 binary samples 的真壁钟差产生漂移
- 与 deferred memo §5.2 实施约束 2 一致

### 决策 3（v3 修订）：Room migration v4 → v5 ADD COLUMN，**改用 nullable `Long?`**

**选择**：`migration4To5Sql = listOf("ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER")`（nullable，无 NOT NULL 约束），注册到 `AppDatabase.migration4To5: Migration` + `AppModule.databaseModule` 的 `addMigrations(...)` 调用。Domain DTO + Entity 都用 `Long?`。

**为什么 v2 → v3 改决策**（v2 review 揭示）：

v2 选 NOT NULL DEFAULT 0 的 rationale 是"避免调用方判空噪声"。但 review 戳穿：
- 调用方判 `wallClock != 0` 跟判 nullable `wallClock != null` **完全等价**（都需写检查），没有真实收益
- 更糟：未来 UI round 用旧数据（v4→v5 升级前的 session）时，wallClock=0 哨兵让 `readLapSamples([0, futureWallClock])` **命中所有真壁钟 ≥0 的样本**——产生"per-lap UI 显示全 session 帧"的误导效果，比清晰的 `null` fallback 更危险

**对比 nullable Long 选项**（v3 修订后）：

| 选项 | 调用方负担 | 旧数据语义 | UI 展示风险 |
|---|---|---|---|
| `NOT NULL DEFAULT 0`（v2 选） | 判 `!= 0` | 哨兵值 0 | UI 错认"per-lap 可用"显示全帧 ❌ |
| `nullable Long?`（**v3 选**） | `wallClock?.let { ... }` 或 `?: return emptyList()` | 显式 null | UI 必须显式 fallback，不会误显示 ✅ |

**选择 nullable Long?**：
- Kotlin compile-time 判空检查比 runtime `!= 0` 安全
- 调用方契约清晰："wallClock 为 null 表示旧数据，per-lap 不可用，必须 fallback 到全 session 路径"
- Room ADD COLUMN 不带 NOT NULL 约束，旧 row 自动 NULL（SQLite 默认行为）
- 跟 C round v3→v4 模式（trackId/trackNameSnapshot 也是 nullable）一致

**Rationale**：未来 UI round 用 wallClock 时显式判空 → 旧数据 fallback 路径明确 → 不会误展示。

### 决策 4：spec delta 用 ADDED Requirements

**选择**：本 round spec delta 用 `## ADDED Requirements`，新增"过线事件采样时间字段双时钟域 hygiene"requirement，不动 A56 + A round 已有的 requirements。

**Rationale**：
- A56 已有"CrossingEvent 事务写入" requirement（仅约束 sessionId / lapIndex / crossingTimestampMs / speed_kmh 字段 + 事务原子性）
- A round 已有"采样时间字段时钟域 hygiene 与 anchor 同源" requirement（约束 binary samples）
- 本 round 加的是 crossing event 的双时钟域约束，与上述两条平行存在不冲突
- A56 + A + 本 round 三 change 都用 ADDED Requirements，归档时按时序合并到主 spec

### 决策 5：单测策略——不引入 Room.inMemoryDatabaseBuilder / room-testing / Robolectric / mockk

**选择**：跟 A round + C round 一致，用 Fake DAO（实现接口）+ 真实 BinaryTelemetryWriter / LapTelemetryReader + mockito-core mock(Context).filesDir.thenReturn(tempDir)。

**Rationale**：
- 与现有 4 个 repository 测试套件（`TelemetryRepositoryTest`, `TelemetryRepositoryEndSessionPersistTest`, `BinaryLapTelemetryRoundTripTest`, 待加的 `CrossingClockRoundTripTest`）形态一致
- Migration SQL 字符串自检走 `AppDatabaseMigrationSqlTest`（C round 的同款 pattern），不引入 MigrationTestHelper（room-testing 库未引入）
- Migration row migration 的自动化跑 v4→v5 验证作为 follow-up `room-test-infrastructure` round（C round 的同款延期项）

## Risks / Trade-offs

- **Risk**：A56 (`unify-gps-telemetry-persistence`) 仍未归档，`binary-telemetry-storage` capability 在主 specs/ 不存在 → 本 round spec delta 引用一个 specs/ 下还不存在的 capability 名 → openspec validate 可能告警 → **Mitigation**：保持 capability 名 `binary-telemetry-storage` 与 A56 + A round 完全一致；归档顺序 user 拍板，A56 先归档则本 round delta 自动找到 base spec
- **Risk**：旧 crossing 数据（修复前写入，wallClock = NULL）的 per-lap segment readLapSamples 用 wallClock 窗口仍不可用 → **Mitigation**：本 round scope 不含旧数据迁移；这是已声明边界（proposal Impact "数据兼容性" 节）；未来 UI 立项时如需兼容旧数据可显式处理
- **Risk**：与并行 round B (`wire-laptime-to-gps-filter`，待启动) 在 `TestSessionViewModel.kt` 同一 ViewModel 但不同函数 → **Mitigation**：B 改 `bridgeGpsToLapTiming`（与 GpsDataFilter 接线），本 round 改 LAP_SESSION 过线事件 writeCrossing 调用块（当前 line 891 附近，apply 时按 grep `TelemetryCrossingEvent(` 命中位置为准；行号会随其他 round 合回漂移），函数级不重叠；rebase 友好
- **Risk**：与并行 round D (`track-tech-v2-style-debt-cleanup`，待启动) 文件全交叉 → **Mitigation**：D 等本 round 合回后再启动（D 的依赖列已声明"依赖 A/B/C/E 全部合回"）
- **Risk**：写入瞬间记录 wallClock 跟 binary samples 的真壁钟存在毫秒级差异（writeCrossing → BinaryTelemetryWriter 各自走 IO 队列）→ **Mitigation**：deferred memo §4.1 数学已分析，typical < 100ms 误差对 per-lap segment 截取无影响（sample 间隔 40ms，端点 boundary 容差 1-2 帧可接受）；测试 case A 用 < 100ms 容差断言
- **Trade-off**：本 round 仅完成数据层，没有"立刻可见的功能解锁"（同 A round）→ 真机不能直接验证 → 功能正确性靠 round trip 单测覆盖
- **Risk**（v3 review §P1#4 揭示）：`TelemetryRepository.endSession` line 161-164 + `LapSessionDetailScreen.deriveDetailMetrics` line 471-515 的 `b.crossingTimestampMs - a.crossingTimestampMs` 派生 bestLapMs / lap durations 仍走 GPS 协议时间减法。GPS 跨小时切换时（mod 3,600,000 解码 + hourStartMillis 切换）会产生**负数 / 1+ 小时错乱值**，写入 `entity.bestLapMs` 影响 Records 列表展示。本 round 加了 wallClock 字段但**不顺手切派生层**（保持 round scope 紧）。**MUST 不沉默漏盘**——已沉淀 follow-up `migrate-lap-duration-derivation-to-wallclock`（见 tasks §11.5），等本 round 合回后立项。Mitigation：deferred memo 已补盘点 §1.1 影响表加这一项
- **Risk**（v3 review v3 §C#3 揭示 · honest acknowledgment）：spec scenario 1b "生产路径 ViewModel scope 取 wallClock vs binary writer 真壁钟差 < 100ms" 当前**没有任何路径验证**——单测明示不覆盖（无 ViewModel scope + IO channel queue 真实环境）+ 真机 §9 默认 user 拍板跳过（按 A round 先例）+ 即便真机做也只验 sqlite 列存在不抽检 100ms 漂移。本 round 接受这条契约**当前不闭合**（如未来生产撞到 100ms 不够，独立 round `wallclock-drift-monitoring` 立项实现：可选方案 = 改用 elapsedRealtime 或 binary writer 内部记录 wallClock 或加 logcat 抽检 + assertions）。proposal Why "完整闭合 lap binary 时钟域能力" 承诺**适用于 session 窗口 + per-lap segment 数据层**，不含"100ms 漂移生产契约的自动验证"——这是 honest 范围声明，避免 dead spec
- **Risk**（v3 review v3 §C#5 揭示 · §11.5 follow-up 数学层）：tasks §11.5 fallback 表达式 MUST 用 per-pair 二选一形态而非独立 elvis，否则混合 row 时退化成跨时钟域减法（本 round 修复的核心 bug 复发）；§11.5 已修订到 per-pair 二选一 + 混合 session ERROR logcat 报警

## Migration Plan

- **代码**：~20 行核心改动 + ~120 行单测。无 feature flag、无配置改动
- **回滚**：单 commit 系列，revert 即恢复
- **数据**：旧 crossing 数据 wallClock = NULL（兜底，不崩溃，但 per-lap 不可用——已声明边界）；新 session 数据正确
- **真机**：build apk → install → 跑 LapSession → 收尾 → adb pull DB → sqlite 查 `crossing_events` 表，验证 `crossingWallClockTimestampMs` 列存在 + 新数据 wallClock != 0（端到端真机有 sanity check 价值，但功能正确性证据仍主要靠单测；按 A round 经验，user 可拍板跳过真机）
- **合流门槛**：`./gradlew :core:data:testDebugUnitTest` + `:core:data:compileDebugKotlin` + `:feature:test:compileDebugKotlin` 通过 + 5 个单测 case 全绿

## Open Questions

- 测试文件名 `CrossingClockRoundTripTest.kt`（待 apply 阶段以现有惯例确认）
- 真机端到端 adb pull DB 验证是否做：默认 user 拍板时机（同 A round 跳过先例），如做需先告知 user 等授权

## 已锁定（v3 review 修订）

- **per-lap segment readLapSamples 窗口的端点容差语义**：`LapTelemetryReader.kt:39` 实现是 `absoluteTs in lapStartTs..lapEndTs` **闭区间**（Kotlin range operator），spec scenario 与 case 测试均按闭区间设计；case B `T1+1000..T1+3000` 端点样本计入，期望 50±1 帧（端点 boundary 容差 = 闭区间端点取整误差）
