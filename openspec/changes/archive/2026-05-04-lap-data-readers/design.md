## Context

**当前状态**：Phase 0（数据层闭合）后 binary samples + crossing events 双时钟域已对齐——
- `TelemetrySample.tsDeltaMs` 在 binary 文件 `header.startTs` 锚下解码出的 `absoluteTs = header.startTs + tsDeltaMs` 与接收侧真壁钟同源（A round + §8.4/M）
- `CrossingEventEntity.crossingWallClockTimestampMs: Long?` nullable 字段持有接收侧真壁钟（§8.3）；旧 row 的 wallClock 为 NULL，spec 已锁定"调用方 MUST 显式判 null fallback"
- `TestRecordEntity.dataFilePath: String`（默认 `""`）持有 PERFORMANCE_TEST binary 路径（G round）

**消费方现状**：
- `LapSessionDetailScreen.deriveDetailMetrics`（feature/test/.../LapSessionDetailScreen.kt:471-515）逐 entity 调 `readPerformanceSamples` + `getCrossings` 现拼派生
- `RecordsHomeScreen.SpeedCurveStub` 解析函数硬编码假数据（参 deferred memo #5 §1）
- 4 个 Phase 1 并行 round（W1/W2/W3/W4）需要消费同一份"单圈完整切片"——若各自拼装，会出现 model 多形态不兼容

**约束**：
- **类型契约稳定性**（sketch §6）：`LapTelemetry` / `LapTelemetrySample` / `PerformanceTelemetry` 字段 + 方法签名一旦定型不再改；W2/W3 mock 已对齐
- **§8.3 nullable wallClock**：调用方 MUST 显式判 null fallback；MUST NOT 偷偷 fallback 到 `crossingTimestampMs`（GPS 协议时间）让旧 row 误命中 0 帧
- **不引入 schema migration**：本 round 仅追加 reader API
- **不动公共协议**：RaceChrono BLE 字段编码 0 触碰
- **rebase 友好**：既有方法签名 / 构造函数尽可能 0 改动（W2/W3 worktree 已开同 base e2a42a1，并行推进期间不破坏）

**stakeholder**：W2 chart-and-map-components / W3 lap-comparison-time-align / W4 wire-laptime-to-gps-filter / Tier2 lap-detail-screen-with-cursor / Tier2 lap-comparison-screen-with-cursor / Records PERFORMANCE 真实 SpeedCurve（合并 memo #5 Phase 2）。

## Goals / Non-Goals

**Goals**：
- 引入 `LapTelemetry` / `LapTelemetrySample` / `PerformanceTelemetry` 三个 domain 数据契约类，作为 Phase 1 单圈/PERFORMANCE 完整切片的统一形态
- 引入 `getLapTelemetry(sessionId, lapIndex)` + `getDataPointsForResult(testId)` 两个 high-level reader API，消费方仅与 sessionId/testId/lapIndex 打交道（不再现拼 `readXxxSamples` + crossings 配对 + entity 派生）
- 跨时钟域 fallback 语义可读：旧 row（wallClock=null）→ 返回 null，UI 走"暂无该圈数据"空态
- 单测 10 cases（含 5 个反例 + grep gate 防回退）锁死契约
- 合并 deferred memo #5（speed-curve-real-data-persistence）—— `getDataPointsForResult` 即 memo #5 §5.5 提的统一接口
- rebase 友好：既有 `readLapSamples` / `readPerformanceSamples` / `getCrossings` / `getSession` 签名 0 改动

**Non-Goals**：
- 不实施 W2 chart 组件 / W3 多圈对齐算法 / Tier2 detail / comparison screen / Records PERFORMANCE SpeedCurve 替换（这些都是 consumer round 的活）
- 不引入 reactive `Flow<...>` 签名（cursor 拖动场景 `LaunchedEffect(testId/lapIndex)` 重新 fetch 已足够）
- 不派生 `accelerationG`（保 null，由 W3 lap-comparison-time-align 或后续 round 选择派生策略后填入；本 round 透传 `LapTelemetrySample.accelerationG: Double? = null`）
- 不改 `bearingDeg` / `flags` 透传逻辑
- 不实施 schema migration / Room 改动 / 公共协议改动
- 不实施真机端到端验证（数据层 round，无 UI 消费可见证据；W2/Tier2 落地后再统一做）

## Decisions

### D1：方法归属——按"数据真相源"分流（A8 vs A1）

**决定**：
- `getLapTelemetry(sessionId, lapIndex)` 留 `TelemetryRepository`（真相源是 TelemetrySession + crossings + binary，已具齐依赖）
- `getDataPointsForResult(testId)` 放 `TestResultRepository`（真相源是 TestRecord，testId == TestRecord.id；TestResultRepository 加 `TelemetryRepository` 单向依赖只为消费 `readPerformanceSamples` 纯函数）

**Alternatives considered**：

- **A1（sketch §2 原写）**：两个方法都放 `TelemetryRepository`，构造函数加 `TestResultRepository` 依赖
  - **拒绝理由**：(i) `TelemetryRepository` 已有 5+ 既有 unit test class（`TelemetryRepositoryTest` / `BinaryLapTelemetryRoundTripTest` / `TelemetryRepositoryDeleteSessionTest` / `BinaryPerftestTelemetryRoundTripTest` / `TelemetryRepositoryEndSessionPersistTest` / `CrossingClockRoundTripTest`），构造函数加参数 → 6 个 setup 都要补 `fakeTestResultRepository`，工作量重 + rebase 不友好（W2/W3 worktree 同 base 期间任何 baseline test setup 改动都会让其 rebase 期间被迫处理）；(ii) PERFORMANCE_TEST 真相源是 TestRecord 而非 TelemetrySession，方法塞到 TelemetryRepository 是按"binary 文件归属"分流而非按"数据语义归属"分流，调用方 mental model 不直观（"我有 TestRecord，给我它的 dataPoints" 显然该问 `TestResultRepository`）

- **A2**：`getDataPointsForResult(filePath: String)` 直接接 `dataFilePath` 参数，让调用方负责反查 entity
  - **拒绝理由**：调用方 verbose（UI 层先 `testResultRepository.getById(testId) → entity.dataFilePath → telemetryRepository.getDataPointsForResult(filePath)` 三步走），且参数 leak 实现细节（filePath 是 internal storage 概念，不该暴露给 UI）；测试覆盖也得分两块（不同 testId 误返不同 dataPoints / dataFilePath 空 / dataFilePath 指向不存在文件）

- **A3（直接 TestRecordDao 注入）**：`TelemetryRepository` 加 `TestRecordDao` 依赖
  - **拒绝理由**：跨边界拉 DAO（`TelemetryRepository` 跟 `TestRecordDao` 不同领域），而 `TestRecordDao` 已被 `TestResultRepository` 包装；引入第二条调用路径（DAO + Repository 都引用同一份 entity）会让 caching / single source-of-truth 语义混乱

- **A5（构造 nullable default）**：`TelemetryRepository(..., testResultRepository: TestResultRepository? = null)`
  - **拒绝理由**：anti-pattern——构造可选但运行时 required；production 调 `getDataPointsForResult` 会因 null 静默返回 null（同代码两套语义路径）

- **A8（采纳）**：方法归属按"数据真相源"分流，`TestResultRepository` 单向反向依赖 `TelemetryRepository.readPerformanceSamples`
  - **采纳理由**：(i) `TestResultRepository` 0 现有 unit test（grep `core/data/src/test/.../TestResultRepository*` 无命中），新建 1 个 reader test 类 + fake `TelemetryRepository` setup 即可，0 个既有 test 改动；(ii) `TelemetryRepository` 5+ 既有 test 全部 0 改动，构造函数 0 diff，rebase 100% 友好；(iii) PERFORMANCE_TEST 调用方 `testResultRepository.getDataPointsForResult(testId)` mental model 直观；(iv) 单向反向依赖 `TestResultRepository → TelemetryRepository.readPerformanceSamples`（仅消费纯函数，不依赖 mutable session state），无循环；(v) sketch §2 锁定的"类型契约稳定性"指 `LapTelemetry / LapTelemetrySample / PerformanceTelemetry` data class 字段 + 方法签名（参数 + 返回类型），repository class 归属是 implementation detail，本 round 显式 documenting 该偏移

**类型契约稳定性 verify**：W2/W3 worktree 仅消费 `LapTelemetry / LapTelemetrySample / PerformanceTelemetry` 类型（mock 数据生成器 + chart 组件 / 对齐算法 pure function），不调 method —— A8 偏移**不影响** W2/W3 mock 期开发。

### D2：crossing wallClock = null 时的 fallback 语义

**决定**：`getLapTelemetry` 内部 lapStart / lapEnd 必须从 `crossing.crossingWallClockTimestampMs ?: return null` 取（Elvis early return 形态）；任一边界 crossing 的 wallClock 为 null → 整个 `getLapTelemetry` 返回 null，调用方走"暂无该圈数据"空态。

**Alternatives considered**：

- **B1**：fallback 到 `crossing.crossingTimestampMs`（GPS 协议时间）
  - **拒绝理由**：§8.3 spec case C 已锁死跨时钟域 readLapSamples 必 0 命中（GPS 协议时间是 mod 3,600,000 解码值，与 binary samples 的 wallClock 不同时钟域，作为 lapStart/lapEnd 截窗口必命中 0 帧）；fallback 后 UI 看到"空 samples 但 lapStart/lapEnd 有值"会误以为"该圈数据为空"而非"该圈不可读"——后者才是真相

- **B2**：fallback 到全 session（`entity.startTs` / `entity.endTs`）+ 标记 `LapTelemetry.isFullSessionFallback: Boolean`
  - **拒绝理由**：(i) 语义不对——调用方要的是单圈 telemetry 而非全 session（W2 chart 组件画整段会让多圈数据失真）；(ii) 引入新字段 `isFullSessionFallback` 让契约复杂化，下游 UI 逻辑分支膨胀；(iii) 单圈 cursor 拖动 + 多圈对比都会因为 fallback 数据画错

- **B3（采纳）**：return null + 调用方判 null 走空态
  - **采纳理由**：(i) 跟 §8.3 spec "调用方 MUST 显式判 null fallback" 一致；(ii) 旧 row 数据自然不可读（migration 之前的 lap session 没法做单圈分析）—— 这是 §8.3 的 known limitation；(iii) UI 层"暂无该圈数据"是诚实降级，比 fallback 全 session 更安全

**反例 scenario 锁死**（spec case E）：crossing wallClock 全 null（旧 row 模拟） → `getLapTelemetry(s, 0)` 返回 null。

### D3：方法签名 suspend single-shot vs Flow

**决定**：`suspend fun getLapTelemetry(...): LapTelemetry?` + `suspend fun getDataPointsForResult(...): PerformanceTelemetry?`（single-shot suspend，nullable 返回）。

**Alternatives considered**：

- **C1**：`fun getLapTelemetry(...): Flow<LapTelemetry?>`（reactive）
  - **拒绝理由**：(i) cursor 拖动场景 ViewModel 用 `LaunchedEffect(testId/lapIndex)` collect 一次即可，没有 reactive 价值（lapTelemetry 在 lapIndex 不变期间永不变化）；(ii) Flow 形态让 caller 必须管 collect 生命周期，多余复杂度；(iii) 跟 baseline `readLapSamples` / `readPerformanceSamples` / `getCrossings` 全部 suspend non-flow 一致

- **C2**：`fun getDataPointsForResult(...): Flow<List<GpsDataPoint>>`（memo #5 §5.5 写法）
  - **拒绝理由**：(i) memo #5 §5.5 是 G session 起草期的初稿设想，没有真实落地约束；(ii) memo #5 已与本 round 合并 scope，本 round design 决策 override memo §5.5 形态；(iii) `List<GpsDataPoint>` 类型也已被 `List<LapTelemetrySample>` 替代（本 round 复用 LapTelemetrySample 作为 PerformanceTelemetry 的 sample 类型）

- **C3（采纳）**：suspend single-shot + nullable 返回
  - **采纳理由**：(i) 与 sketch §2 形态一致；(ii) Compose 层 `LaunchedEffect(testId)` pattern 自然；(iii) 跟 baseline reader 接口风格一致

### D4：sectorBoundaries 派生策略

**决定**：本 round `sectorBoundaries: List<Long>` 仅含**单圈起点**（即 `lapStartWallClock`），不派生任何 sector segment 边界。

**Alternatives considered**：

- **D1a**：sector 边界从 `crossings.filter { it.gateType != "StartFinish" && it.accepted }` 取，按 wallClock 排序
  - **拒绝理由**：(i) 当前 baseline `TimingGate` 的 sector gate 实现尚未完全落地（仅 StartFinish 路径稳定）—— 派生 sector segment 会有 partial-coverage 风险；(ii) sector 派生属"领域逻辑"应在独立 round 闭环（参 sketch §3 SectorBar 组件接的是 `sectorBoundaries: List<Long>` 数据形态）；(iii) 本 round 把 sectorBoundaries 字段在数据契约里**留好接口**（List<Long>，第 0 项 = lapStart，未来扩 sector 时直接追加），不做 sector 派生 → 兼容未来扩展

- **D1b（采纳）**：sectorBoundaries = listOf(lapStartWallClock)（单元素，仅含起点）
  - **采纳理由**：契约形态保留 + 实现极简 + 不预言 sector gate 设计 + 后续 sector round 可在不改本 round 数据契约前提下扩展

**Spec normative**：`LapTelemetry.sectorBoundaries.first() MUST == lapStartWallClock`（容易 verify 的 invariant）。

### D5：单圈起止 crossing 选择 + lapCount 双语义澄清

**决定**：lapIndex 第 N 圈对应**第 N、N+1 个 accepted StartFinish crossing**（zero-based，按 wallClock 排序后 zipWithNext 配对）；sortedBy `crossingWallClockTimestampMs ?: Long.MAX_VALUE`（null 排到末尾，不污染前缀的非空配对）。

**重要 caveat（lapCount 双语义）**：

baseline `endSession` line 164 派生 `lapCount/bestLapMs` 的 pattern 是：
```kotlin
val acceptedSF = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
    .sortedBy { it.crossingTimestampMs }     // ← GPS 协议时间排序（非 wallClock）
val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }
```

baseline 用 `crossingTimestampMs`（GPS 协议时间）排序 + 计 duration（mod 3,600,000 解码值），本 round `getLapTelemetry` 用 `crossingWallClockTimestampMs` 排序——**两套语义在数据完整时收敛**（新 row 写入时同帧落两个字段，wallClock = startSession 起点 + GPS 协议 duration），**但混合 wallClock null 数据时可能不同**：

- 旧 row（migration 之前）：`crossingWallClockTimestampMs = null`，`crossingTimestampMs` 非空 → baseline `endSession` 派生 `lapCount = K`；本 round `getLapTelemetry(s, 0..)` 全部返回 null
- 混合 row：N 条 wallClock 非空 + M 条 null → baseline `endSession.lapCount = N+M-1`（按协议时间 zipWithNext），本 round 可读 lap = wallClock 非空中相邻配对数（< N+M-1）

**两套语义不强制收敛**——本 round 不修 baseline `endSession`（scope 边界），而是 spec normative 显式锁定"调用方 MUST 通过 `getLapTelemetry(s, 0..)` 依次调用直到首次返回 null 来遍历可读 lap 集合，**不**通过 `entity.lapCount` 决定圈数"。

**Alternatives considered**：

- **E1**：按 `CrossingEventEntity.lapIndex` 字段对齐（lapIndex == N 的 crossing 即第 N 圈起点）
  - **拒绝理由**：(i) baseline `LapTimingEngine.processSample` 写入的 `lapIndex` 是 **1-based**（参 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:167` `lapIndex = 1` 起步），与本 round 的 zero-based 语义不一致；(ii) lapIndex 字段是内部 ID，跨 INVALID/重新触发等场景可能跳号；(iii) 本 round 配对来源（acceptedSF wallClock 排序）与 entity.lapIndex 计数器无对应关系

- **E2（baseline endSession 同 pattern）**：用 `crossingTimestampMs`（GPS 协议时间）排序 zipWithNext
  - **拒绝理由**：(i) GPS 协议时间是 mod 3,600,000 解码 + hourStartMillis 切换的 ms，跨小时切换会产生**负数 / 1+ 小时错乱值**，作为 lapStart/lapEnd 传给 `readLapSamples`（窗口期望 wallClock 同时钟域）必命中 0 帧；(ii) §8.3 spec case C 已锁死跨时钟域 readLapSamples 必 0 命中；(iii) baseline `endSession` 用协议时间是历史 quick fix，本 round 不沿用其 anti-pattern——而是新增 wallClock 排序作为 reader 入口标准

- **E3（采纳）**：`accepted=true && gateType="StartFinish"` filter + sortedBy `crossingWallClockTimestampMs ?: Long.MAX_VALUE` + zipWithNext 配对，第 N 圈 = `pairs[N]`（zero-based）
  - **采纳理由**：(i) wallClock 排序与 `readLapSamples` 时钟域同源，跨小时切换不退化；(ii) null 排到末尾（Long.MAX_VALUE）不污染前缀非空配对，让混合 row 仍能读 wallClock 非空部分；(iii) zero-based 与 sketch §1 LapTelemetry.lapIndex 注释 "0-based" 一致；(iv) 双语义 caveat 通过 spec normative 显式声明，调用方走"依次调用直到首次 null"遍历，无歧义

**spec normative（已加）**：lapIndex 语义独立于 `entity.lapCount` 与 `LapTimingEngine.lapIndex` 1-based 计数器；调用方遍历可读 lap 用"依次调用直到 null"。

**Follow-up backlog（已加 tasks §10）**：`unify-lap-count-pairing-semantics` round 决议是否把 baseline `endSession` 同步切到 wallClock 排序（scope 越界本 round，单独立项）。

### D6：LapTelemetrySample.elapsedMsInLap 派生策略

**决定**：`elapsedMsInLap = absoluteTsMs - lapStartWallClock`（直接减法，单位 ms，可能负数若 sample 早于 lapStart——但 readLapSamples 已限定窗口，不应有负数）。

**Alternatives considered**：

- **F1**：用 `TelemetrySample.tsDeltaMs - (lapStart - header.startTs)` 跨 baseline 字段派生
  - **拒绝理由**：双重间接（tsDeltaMs / lapStart / header.startTs 三个时钟参考点）+ 失去 wallClock 直观；本 round 已通过 `readLapSamples(filePath, lapStart, lapEnd)` 在内部走 `header.startTs + tsDeltaMs` 解码出 absoluteTs（A round 闭合），caller 拿到的 `TelemetrySample.tsDeltaMs` 仍是 binary 内部值——本 round 还需把 absoluteTs 重新算出来一次

- **F2（采纳）**：absoluteTsMs = entity.startTs + tsDeltaMs（A round 闭合的 wallClock 锚 == header.startTs == entity.startTs），elapsedMsInLap = absoluteTsMs - lapStartWallClock
  - **采纳理由**：(i) 复用 A round 已闭合的 absoluteTs 解码语义；(ii) elapsedMsInLap 直接 = absoluteTsMs - lapStartWallClock 简洁；(iii) Tier2 chart 组件 x 轴直接用 elapsedMsInLap 即可，不需再派生

> ⚠️ L2 review (Opus B 线) 修订：原稿 F2 公式写"absoluteTsMs = lapStart + tsDeltaMs"与下方实现 caveat（"absoluteTsMs = entity.startTs + sample.tsDeltaMs"）内部矛盾。仅在 lapStart == entity.startTs 时（即 sessionStart 恰好等于第 0 圈 lapStart）等价。已统一为 `entity.startTs + tsDeltaMs`，与生产代码 `TelemetryRepository.kt:288` 对齐。

**实现 caveat**：`readLapSamples` 返回的 `TelemetrySample.tsDeltaMs` 是相对 `header.startTs`，而 `header.startTs == entity.startTs == sessionStartWallClock`（A round 闭合），所以 `absoluteTsMs = entity.startTs + sample.tsDeltaMs`。本 round 内部需要拿 `entity.startTs`（通过 `getSession(sessionId).startTs`）作为 absoluteTs 解码锚——**MUST verify** `entity.startTs` 不会被后续 endSession update 改写（baseline `updateSummary` 改 `endTs/lapCount/bestLapMs/topSpeedKmh`，**不改 startTs**——已 grep 验证）。

### D7：测试 fixture 策略

**决定**：复用 baseline `TelemetryRepositoryTest` 的 Fake DAO + real `BinaryTelemetryWriter` + temp dir pattern，不引入 Robolectric / in-memory Room。

**Alternatives considered**：

- **G1**：用 Robolectric in-memory Room（@Database 真路径 + InMemoryRoomDatabase）
  - **拒绝理由**：(i) baseline 6 个 TelemetryRepository test 都用 Fake DAO + 真 BinaryTelemetryWriter（pattern 已稳定）；(ii) Robolectric setup 复杂，本 round scope 不需要（本 round 不涉及 schema 改动）

- **G2（采纳）**：复用 Fake DAO + real BinaryTelemetryWriter + temp dir
  - **采纳理由**：(i) baseline 已成熟 6 个 test 都跑通；(ii) 本 round 只追加 reader API，不需要新 fixture 类型；(iii) `getDataPointsForResult` 测试需要 fake `TestRecordDao`（baseline 是否已有？查 `core/data/src/test/.../FakeTestRecordDao*`）

**fake DAO pattern（baseline 实情澄清）**：

实测 baseline 6 个既有 TelemetryRepository 测试类各自 `private class` 定义同名 fake DAO（grep 命中 6 处独立定义点：`TelemetryRepositoryTest.kt:174` / `TelemetryRepositoryDeleteSessionTest.kt:190` / `BinaryLapTelemetryRoundTripTest.kt:331` / `CrossingClockRoundTripTest.kt:343` / `BinaryPerftestTelemetryRoundTripTest.kt:477` / `TelemetryRepositoryEndSessionPersistTest.kt:237`）—— **baseline 0 个 file-level / shared fake DAO 类**。

本 round 沿用 baseline pattern：在 `LapTelemetryReadersTest.kt` 文件内部 **`private class` 重复定义** `FakeTelemetrySessionDao` / `FakeCrossingEventDao`（每个 test 类自包含 fixture），不引入跨 test 共享 fake DAO 类（避免引入 abstract 方法签名同步责任 + 让 future test 类各自掌握 fake 行为）。

`FakeTestRecordDao` / `FakeSpeedSegmentDao` 在本 round 同样以 file-internal `private class` 定义（`TestResultRepository` 构造需要 testRecordDao + speedSegmentDao 两参数，本 round 测试只关心 testRecordDao 的 `getTestRecordById` 行为，speedSegmentDao 可 stub 抛 NotImplemented）。

**MUST**：apply 期 grep `interface TestRecordDao` 实际签名列出全部 abstract 方法 + 同步在 fake 类中 override（v3 高频盲点 #14）；若 future round 给 TestRecordDao 加 abstract 方法，**只**影响本 round 的 file-internal `private class FakeTestRecordDao` 一处补 stub，不影响其他 test。

## Risks / Trade-offs

- **R1：A8 方法位置偏离 sketch §2** → **mitigation**：本 design 显式列 D1 alternatives + L1 review verify A8 比 A1 更优；W2/W3 worktree 已开同 base e2a42a1，类型契约（`LapTelemetry / LapTelemetrySample / PerformanceTelemetry` 字段）不动，repository 归属偏移**不影响** mock 期开发；Tier2 集成 round 落地时按 A8 调用 path 实施（直接 `testResultRepository.getDataPointsForResult` 而非 `telemetryRepository.getDataPointsForResult`）

- **R2：旧 row crossing wallClock=null 让 getLapTelemetry 返回 null** → **mitigation**：(i) UI 层 caller 在 Tier2 lap-detail-screen-with-cursor 落地时显式判 null 走"暂无该圈数据"空态；(ii) §8.3 spec 已 normative 锁定该 fallback 语义；(iii) 本 round case E 反例锁死，回归即测试 fail；(iv) 影响范围 = §8.3 migration 之前的 lap session（migration 后写入的 row 都有 wallClock 非空）

- **R3：TestResultRepository 加 TelemetryRepository 依赖可能引入循环** → **mitigation**：grep `TelemetryRepository.kt` 确认 0 引用 `TestResultRepository` 任何符号（已 verify baseline）；apply tasks §1 加 grep gate `grep "TestResultRepository\|TestRecord" core/data/src/main/.../TelemetryRepository.kt` 必须 0 命中（防 future round 误引入循环）

- **R4：sample.absoluteTs 解码用 entity.startTs 锚，若 startTs 被 endSession update 误改则解码错** → **mitigation**：(i) baseline `updateSummary` 仅改 `endTs/lapCount/bestLapMs/topSpeedKmh`（不改 startTs）；(ii) 加 grep gate `grep "set startTs\|startTs =\|startTs:" TelemetryRepository.kt` 仅出现在 `startSession` 内；(iii) spec case A 端到端 round trip 验证 absoluteTs 解码正确

- **R5：testId 在 TestRecord/TelemetrySession 双表语义可能不对应**（PERFORMANCE_TEST 是否同时在 TelemetrySession 表写一份？） → **mitigation**：本 round `getDataPointsForResult` 仅消费 `TestRecordEntity.dataFilePath`，**不**经过 `TelemetrySessionEntity`；spec 显式锁定"testId == TestRecordEntity.id"语义；apply 期 verify `TestRecordEntity` baseline 字段不变（已 verify line 27）

- **R6：accelerationG 字段保 null 可能让 W3 算法不会回填** → **mitigation**：本 round 显式声明非 goal（不派生 accelerationG），Non-Goals 段已说明；W3 round `lap-comparison-time-align` 的 design 期决定派生策略（per-sample 差分 vs SG 5 点）+ 是否回填 LapTelemetrySample.accelerationG

- **R7：sectorBoundaries 仅含起点导致 Tier2 SectorBar 组件无法画 sector 分隔（half-closure 风险）** → **mitigation**：(i) 本 round Non-Goals 已声明 sector 派生不做；(ii) sketch §3 SectorBar 接 `sectorBoundaries: List<Long>` 入参，W2 chart 组件库 mock 期可以填多元素 sector 位置（不影响 W2 worktree 推进）；(iii) **Tier2 风险显式声明**：Tier2 `lap-detail-screen-with-cursor` round 落地时拿到 `getLapTelemetry().sectorBoundaries.size == 1` 真实数据，SectorBar 组件渲染"无 sector 分隔线" → Tier2 round design 期 MUST 决策：(a) 等 sector round 落地后才 enable SectorBar，或 (b) Tier2 round 内派生 sector boundaries（sectorBoundaries 派生的真相源在 Tier2/sector round 而非本 round）；(iv) tasks §10 backlog 已登记 `future-sector-derivation-round`

- **R8：本 round 无端到端真机验证** → **mitigation**：(i) 数据层 round 模式（同 A round / §8.3 / §8.4 / M），by design 暂无 UI 消费可见证据；(ii) 单测 10 cases 含 round trip 端到端 + 跨时钟域 fallback 反例锁死；(iii) Phase 1 Tier2 lap-detail-screen-with-cursor round 落地时统一做端到端真机验证

- **R10：baseline reader IO 异常防护不完整（P1）** → **mitigation**：实测 baseline `LapTelemetryReader.read` (line 18-42) 与 `PerformanceTestTelemetryReader.read` (line 18-38) 仅防 `!file.exists()` 与 `file.length() < HEADER_SIZE` 两个早返回，**不防** `RandomAccessFile.readFully` 在文件中途被删 / 截断 / 磁盘 I/O 错误时抛 `EOFException` / `IOException`。本 round 在 reader 调用层（`getLapTelemetry` / `getDataPointsForResult` 内部）显式用 `runCatching { ... }.getOrDefault(emptyList())` 包裹兜底，把 IO 异常降级为空 list（再由调用层判 emptyList → null）。spec Requirement 1 / 2 已更新 normative 文字"MUST 用 runCatching 包"。Tier2 round 落地时调用方拿到 null 走"暂无该圈数据"空态即可，不应假设 reader 异常已被吞掉。

- **R9（性能 caveat，P2）：cursor 拖动场景 fetch 频率与 IO 延迟** → **mitigation**：(i) 单次 `getLapTelemetry` fetch 涉及 1 次 Room session query + 1 次 Room crossings query + 1 次 binary 顺序读 (~25KB / 25Hz × 60s × 17 byte) + filter，估算总耗时 ~50-100ms（基于 baseline `LapTelemetryReader` 性能；下端机可能 100-300ms）；(ii) Tier2 lap-detail-screen-with-cursor cursor 拖动场景 ViewModel 应：(A) 缓存最近 fetch 的 LapTelemetry（lapIndex 不变期间复用），(B) 用 `LaunchedEffect(testId, lapIndex)` key = lapIndex 自然约束 fetch 频率，(C) Tier2 多圈对比时 parallel async 三圈 fetch；(iii) 本 round 数据底座层不做缓存 / 流式优化（caller 责任，避免 cache invalidation 复杂度泄漏到 reader）；(iv) Tier2 round design 期细化性能策略，本 round 0 改动；(v) 若 Phase 2 出现真机延迟问题，立项 follow-up `lap-data-readers-perf-cache` 在 reader 层引入 LRU 缓存

## Migration Plan

无 schema migration（本 round 仅追加 reader API）。

**deploy 步骤**：
1. 主区合回 `feature/lap-data-readers` → `feature/track-tech-v2`（ff-only），主区跑 `:core:data:testDebugUnitTest` + `:feature:test:compileDebugKotlin` + `:app:compileDebugKotlin` 全绿
2. 看板 §5 W1 状态改 `done` + 最近合回 commit；§6 共享文件 3 条 ongoing 标 done
3. W2 / W3 / Tier2 round 启动时按 A8 调用 path 实施

**rollback**：本 round 是纯追加性变更，rollback = 删除 `LapTelemetry.kt` + 删除 `getLapTelemetry` / `getDataPointsForResult` 方法 + AppModule.kt 复位 `single { TestResultRepository(get(), get()) }` 即可，无数据损坏风险。

## 消费 LapTelemetrySample.flags 字段的已合回 round 列表（v3 高频盲点 #16 retroactive，2026-05-05 phase1-hardening-w2-w3-w4-mimo-debt round 补）

> 按 CLAUDE.md "v3 高频盲点列表" #16 normative — 发起字段扩展的 round（W1，本 round）design 决策段 MUST 列"消费此字段的已合回 round 列表"。本 retroactive section 由 phase1-hardening-w2-w3-w4-mimo-debt round 在 apply 期间补（OQ6 决议）：W1 round 已合回但原 design.md 没列消费方表；本 section 列 W1 后所有 LapTelemetrySample 的 producer + consumer round。

| Round | 角色 | 路径 | flags 字段消费状态 |
|---|---|---|---|
| W1 `lap-data-readers`（本 round）| 类型 producer + binary reader | `core/domain/.../model/LapTelemetry.kt:21` `flags: Int = 0` 字段（commit chain `f6aed72` + `3c2f2d9`）+ `core/data/.../repository/TelemetryRepository.kt:295` `flags = sample.flags` | ✓ producer 与 binary reader 已落地 |
| W2 `chart-and-map-components` | 字段清单 grep gate consumer | `feature/test/.../GrepGateTest.kt §8.7` 字段清单 grep | ✓ phase1-hardening B1 已加 `val flags: Int` 字面量验证 |
| W3 `lap-comparison-time-align` | LapAlignment.interpolate consumer | `core/domain/.../usecase/LapAlignment.kt:179-202` interpolate / clamp / 精确命中 / resampleByGridFallback | ✓ phase1-hardening B2/B4 + R1 P1-1 修复（最近邻策略 + 反例 scenario lock） |
| W4 `wire-laptime-to-gps-filter` | binary writer producer | `feature/test/.../viewmodel/TestSessionViewModel.kt:855-862` `TelemetrySample(tsDeltaMs, lat, lon, speedKmh, bearingDeg)` **不传 flags 字段** | ⚠ binary writer 永久写 0，deferred to Phase 2（flags 信号源不在 RaceChrono BLE 协议） |

**本 retroactive section 来源**：phase1-hardening-w2-w3-w4-mimo-debt round design.md Decision 6 producer/consumer 表 + L1 R2 P1-R2-2 升级 + L2 P1-1 修订。

## Open Questions

1. **`FakeTestRecordDao` 是否 baseline 已存在**？apply 期 §1 grep 确认；若无则本 round 新建（应 trivial 的 in-memory map fake，参 `FakeTelemetrySessionDao` pattern）
2. **是否需要 grep gate verify `TestRecordEntity` 的 `dataFilePath` 字段名**？是——加 `grep "val dataFilePath: String" core/data/src/main/.../TestRecordEntity.kt` 命中 1 次防回退（G round 的字段如被未来 round 改名会让本 round 静默 fail）
3. **L1 review 子 agent 验证点**：
   - D1 A8 偏移 sketch §2 是否合理（W2/W3 mock 是否真的不依赖方法位置）
   - D2 fallback 语义是否真的安全（旧 row 数据 forever lost 是否可接受）
   - D5 第 N 圈定义跟 baseline `endSession` 派生是否真的同 pattern
   - D6 absoluteTs 解码锚是否健壮（startTs 不被改写的 invariant 是否在所有 round 都守住）
   - sectorBoundaries 仅含起点是否会让 W2 SectorBar mock 形态偏离最终形态
4. **L2 实施期 review 关注点**：
   - grep gate 对 TelemetryRepository.kt 的循环依赖防回退（R3 mitigation）
   - test fixture 复用 vs 新建（D7 / Open Q1）
   - elapsedMsInLap 派生不出现负数（D6 / readLapSamples 窗口语义）
