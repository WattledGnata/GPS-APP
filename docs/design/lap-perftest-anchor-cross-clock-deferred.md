# PERFORMANCE_TEST anchor 跨时钟域风险 deferred memo

> **沉淀时机**：2026-05-05 lap-data-readers round L2 review (Opus 双线) 期间，B 线发现 `getDataPointsForResult` 的 `entity.timestamp` anchor 与 binary samples 的 `tsDeltaMs` 不严格同源；当前 round 工件 verbose 写"§8.4/M anchor 已对齐"是 unargued assertion，未真验证 invariant。
>
> **立项 round 命名建议**：`unify-perftest-anchor-cross-clock`（直接对应 `/opsx:ff` 命令）
>
> **优先级**：P1（不阻塞当前 round 闭环，但须在 Phase 1 Tier2 `lap-detail-screen-with-cursor` round 落地前完成；否则 PERFORMANCE 场景 SpeedCurveReal 消费 `getDataPointsForResult` 时若 GPS 未同步会拿到 `absoluteTsMs ≈ Long.MIN_VALUE` 的样本流，整个 chart 时间轴语义崩塌）

## §1. 现状

`TestResultRepository.getDataPointsForResult(testId)` 实现层 `core/data/.../TestResultRepository.kt:151-155`：

```kotlin
val testStartWallClock = entity.timestamp
val absoluteTsMs = testStartWallClock + sample.tsDeltaMs
```

spec line 75 的 normative 声明：

```
- absoluteTsMs = testStartWallClock + sample.tsDeltaMs（§8.4/M anchor 已对齐）
```

——这条声明是 unargued assertion；spec 没解释"已对齐"的具体 invariant 是什么、什么前提下成立。

## §2. 数据证据

### 2.1 `entity.timestamp` 来源链路

```
RaceChronoParser.kt:239-242
  protocolTimestamp = if (syncedNow) {
    reference!!.hourStartMillis + timeSinceHourStart   // (a) GPS 协议时间
  } else {
    Long.MIN_VALUE                                     // (b) sentinel (未同步)
  }
  GpsData(timestamp = protocolTimestamp, ...)

GpsDataFilter.kt
  FilteredGpsData(timestamp = raw.timestamp, ...)      // 透传

TestSessionViewModel.kt:727
  session.startTime = filteredData.timestamp           // 继承 protocolTimestamp（含 sentinel 风险）

CalculateResultUseCase.kt:56
  TestResult(timestamp = session.startTime, ...)

TestResultRepository.kt:50
  TestRecordEntity(timestamp = result.timestamp, ...)
```

**关键**：`entity.timestamp` 是 GPS 协议时间（`hourStartMillis + timeSinceHourStart`）；
- (a) `hourStartMillis` 是当前小时起点的 UTC ms（卫星时间衍生）
- (b) `timeSinceHourStart` 是当前 GPS sample 距小时起点 ms

### 2.2 `sample.tsDeltaMs` 来源链路

```
TestSessionViewModel.kt:744 (binary 写入帧)
  tsDeltaMs = (System.currentTimeMillis() - sessionStartTs).toInt()
```

——`sample.tsDeltaMs` 是接收侧 `System.currentTimeMillis()` delta（**本地壁钟域**）。

### 2.3 两 anchor 不同源的形式化证明

| 时间锚 | 域 | 来源 |
|---|---|---|
| `entity.timestamp` | GPS 协议时间（UTC 卫星衍生） | RaceChronoParser line 240 |
| `sample.tsDeltaMs` | 本地 `System.currentTimeMillis()` delta | TestSessionViewModel line 744 |

`absoluteTsMs = entity.timestamp + sample.tsDeltaMs` **仅在以下三个 invariant 同时成立时才"对齐"**：
1. **`entity.timestamp != Long.MIN_VALUE`**（GPS 已同步）
2. **GPS-UTC-本地壁钟差** 在容许范围内（NTP 时间漂移容许）
3. **session 开始 → binary 第一帧写入** 期间没有 GPS 锁定状态切换（同步 → 失锁 → 重同步会让 `hourStartMillis` 跳变）

### 2.4 baseline 已意识到 sentinel 风险但 guard 未覆盖到 reader 路径

`TestSessionViewModel.kt:643-644` 注释：

```
// Running 期间失联 filter 返回 sentinel timestamp = Long.MIN_VALUE + zero acceleration
// 的"零 delta 快照"。若吃进 session.dataPoints，elapsedTime = Long.MIN_VALUE - startTime
```

——baseline writer 侧已 aware sentinel；但当前 `getDataPointsForResult` reader 侧 **0 guard**：
- 没检查 `entity.timestamp != Long.MIN_VALUE`
- 没检查 GPS 锁定状态切换
- 没显式 assertion `absoluteTsMs > 0`

### 2.5 生产场景能否触发崩塌

- PERFORMANCE_TEST trigger 条件：`satellites>=6 + hdop<2.0`（`TestSessionViewModel` 的 trigger guard）—— **GPS 同步是 trigger 前置**
- 但 trigger 之后 session 可能跨"GPS 失锁 → 重同步"周期；此时 `entity.timestamp = trigger 时刻的 GPS 时间`（非 sentinel），但中途某帧 `protocolTimestamp = Long.MIN_VALUE` 会被 binary writer 写为 `tsDeltaMs = (System.currentTimeMillis - sessionStartTs)` —— delta 仍正确，因为 binary writer 用 `System.currentTimeMillis()` 不依赖 protocolTimestamp
- 所以**生产实务中 `entity.timestamp = Long.MIN_VALUE` 的 PERFORMANCE_TEST 不可能存在**（trigger guard 阻断），但 spec 没显式锁该 invariant，让 future 修改 trigger guard 时 reader 侧无第二道防线

## §3. 方案对比

### 方案 A：实现层加 sentinel guard，spec 加 normative

```kotlin
// TestResultRepository.getDataPointsForResult
suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry? {
    val entity = testRecordDao.getTestRecordById(testId) ?: return null
    if (entity.dataFilePath.isEmpty()) return null
    if (entity.timestamp == Long.MIN_VALUE) return null  // <-- 新加
    ...
}
```

spec Requirement 2 加 normative：
- `entity.timestamp != Long.MIN_VALUE` 是前提；为 sentinel 时返回 null（writer 侧 trigger guard 已阻断该数据进入 binary，但 reader 侧加 defensive guard 防 future trigger guard 修改回归）

**优点**：成本最低（1 行 guard + 1 条 spec normative），不影响生产实务，加防线
**缺点**：未解决 (2) GPS-UTC-本地壁钟漂移 + (3) GPS 失锁周期切换的隐患

### 方案 B：迁移 `entity.timestamp` 字段语义到本地壁钟域（与 sample.tsDeltaMs 同源）

把 `TestResult.timestamp` 改为 `triggerWallClockMs = System.currentTimeMillis()`（trigger 时刻的本地壁钟，而非 GPS 协议时间），与 `sample.tsDeltaMs` 同 anchor 域。

**优点**：根本解决跨时钟域问题，`absoluteTsMs = entity.timestamp + tsDeltaMs` 同源加法
**缺点**：
- 跨多 module 改动（TestResultRepository / CalculateResultUseCase / TestSessionViewModel / TestRecordEntity / Records UI）
- Records UI 显示 `timestamp` 当前用 GPS 协议时间（"Today, 10:35"），切到本地壁钟语义不变（仅 anchor 不同）
- baseline 历史 row（旧 `timestamp` 是 GPS 协议时间）需 migration

### 方案 C：双字段冗余（保留 `entity.timestamp` GPS 协议时间 + 加 `entity.binaryStartWallClock` 本地壁钟）

类似 §8.3 `crossingTimestampMs / crossingWallClockTimestampMs` 双字段策略：
- `entity.timestamp`：GPS 协议时间（UI 显示用）
- `entity.binaryStartWallClock`：binary 第一帧写入的本地壁钟（reader 侧用，与 sample.tsDeltaMs 同源）

reader 改为：

```kotlin
val absoluteTsMs = entity.binaryStartWallClock + sample.tsDeltaMs
```

**优点**：双 anchor 并存，UI 用 GPS 协议时间无需变；reader 用本地壁钟同源
**缺点**：
- Room schema migration（v6 → v7 加 `binaryStartWallClock` 列）
- 旧 row（v6 之前）`binaryStartWallClock` 为 null，reader 需 fallback 到 `entity.timestamp` 或返回 null
- baseline `TestSessionViewModel.kt:744` binary 写入路径需补"第一帧 wallClock"持久化

## §4. 推荐方案 + 数学/性能分析

**推荐方案 A（最小改动 + 加 defensive guard）**——理由：

1. **生产实务概率 0**：trigger guard `satellites>=6 + hdop<2.0` 已阻断 sentinel 数据进 binary；reader 加 guard 是防 future trigger 改动回归
2. **方案 B 改动 5+ module + Records UI 显示语义切换**，scope 极大；当前 Phase 1 Tier1/Tier2 仅 chart 消费 `getDataPointsForResult`，**chart 在 GPS 同步前提下 GPS 协议时间 ≈ 本地壁钟**（漂移容许 <100ms）—— 不构成实际渲染问题
3. **方案 C** schema migration 成本中等，但当前没实际 bug 触发，over-engineering

数学分析（方案 A 的 `absoluteTsMs` 误差范围）：
- GPS 协议时间 vs 本地壁钟差 = NTP 时间漂移 + GPS-UTC 接收延迟 = **典型 ~50ms（实测 < 200ms）**
- chart x 轴粒度 = 40ms（25Hz）= 1 帧；漂移 < 5 帧 chart 渲染无可见错乱
- 唯一 catastrophic 场景：sentinel `Long.MIN_VALUE` —— 方案 A guard 已阻断

## §5. 实施约束（MUST 条款）

立项 round `unify-perftest-anchor-cross-clock` MUST 满足：

1. **MUST** 在 `TestResultRepository.getDataPointsForResult` 第 144 行（`if (entity.dataFilePath.isEmpty()) return null` 之后）加 `if (entity.timestamp == Long.MIN_VALUE) return null`
2. **MUST** 在归档 `lap-data-readers` 的 spec.md Requirement 2 增量同步：
   - 在 line 71 之前加 normative：`entity.timestamp != Long.MIN_VALUE` 是前提，为 sentinel 时返回 null
   - 把 line 75 "§8.4/M anchor 已对齐" 修订为显式 invariant 三条款（entity.timestamp non-sentinel + GPS-UTC-本地壁钟漂移容许 + session 内无 GPS 失锁 → 重同步周期）
3. **MUST** 在 `LapTelemetryReadersTest.kt` 加 case L：sentinel `entity.timestamp = Long.MIN_VALUE` → reader 返回 null
4. **MUST** 跑 `:core:data:testDebugUnitTest` 全部 11 cases 全绿（含原 10 + 新加 case L）
5. **MUST** 不引入 Room schema migration（方案 A 选择 spec normative + 实现层 guard，0 schema 改动）
6. **MUST NOT** 改 `TestRecord.timestamp` 字段语义（方案 B/C 推到 future 的 P3 backlog）

## §6. 单元测试覆盖

新加 case L（在 `LapTelemetryReadersTest.kt` 内）：

```kotlin
@Test
fun `case L - getDataPointsForResult sentinel entity timestamp returns null`() {
    val entity = TestRecordEntity(
        id = "perf-sentinel",
        timestamp = Long.MIN_VALUE,   // sentinel
        dataFilePath = "/tmp/dummy.bin",
        ...
    )
    val testRecordDao = FakeTestRecordDao(listOf(entity))
    val telemetryRepository = mockk<TelemetryRepository>()
    val repo = TestResultRepository(testRecordDao, fakeSpeedSegmentDao, telemetryRepository)

    val result = runBlocking { repo.getDataPointsForResult("perf-sentinel") }

    assertNull(result)
    verify(exactly = 0) { telemetryRepository.readPerformanceSamples(any()) }  // sentinel 阻断在 reader 调用之前
}
```

## §7. 与当前 round (lap-data-readers) 的协同关系

- 当前 round HEAD commit `3c2f2d9` 不含 sentinel guard；归档时 spec.md Requirement 2 line 75 "§8.4/M anchor 已对齐" 暂保留原文 + L2 review trail (`review-l2-opus-b.md`) 显式 flag P1-1
- follow-up round 立项时 spec 增量修订把"§8.4/M anchor 已对齐" 替换为显式 invariant 三条款；实现层加 guard
- chart 消费方（W2 已 land 的 `SpeedTimeChart` / Tier2 lap-detail-screen 的 SpeedCurveReal）在当前 round 上消费 `getDataPointsForResult` 是**安全的**（trigger guard 已阻断 sentinel）—— follow-up round 是 defensive 加固，不阻塞 chart 落地

## §8. 不并入当前 round 的理由

1. **scope 边界**：当前 round 是"加 reader API"；新加 sentinel guard 涉及 spec normative 改写 + 测试 case 新加 + 实现层 guard，超出 reader API 设计 scope
2. **时间窗口**：当前 round 已 plateau，归档动作触发；新加改动会让 plateau 重新开始（v3 review 又一轮）
3. **生产 0 触发**：trigger guard 阻断 sentinel 进 binary；当前 round 不 ship 任何"实际遇到 sentinel"的 code path，加 guard 不阻塞 chart 落地
4. **可独立验证**：跨时钟域 anchor 修订是 self-contained scope，独立 round 单测覆盖即可，不需联调 chart

## §9. 立项节奏估算

| 阶段 | 估算 | 备注 |
|---|---|---|
| L0 需求理解 | 5 min | user 拍板用方案 A |
| `/opsx:ff` 工件起草 | 30 min | proposal/design/specs/tasks，scope 小 |
| L1 review | 1 轮 | trivial 复杂度 |
| `/opsx:apply` 实施 | 15 min | 1 行 guard + 1 条 spec normative + 1 个 case |
| L2 review (Opus 单线) | 1 轮 | 不需要 Codex |
| 归档 | 5 min | metrics.yaml + 看板 |
| **总耗时** | **~1.5h** | trivial 复杂度，建议放 Phase 1 Tier1.5（W4 之后、Tier2 之前） |

预期建议：当前 lap-data-readers round 闭环后，Tier1.5 时段（Phase 1 W1-W4 全部 done 后）插入此 round；不与 W3 lap-comparison-time-align 真机验证、Phase 2 records-real-data round 冲突。

---

> Memo 来源：lap-data-readers round L2 review (Opus 双线) B 线 P1-1 finding；
> 同步看板 §5 follow-up backlog 待补条目；
> 当前 round 工件归档时 review trail (`review-l2-opus-b.md`) 已显式 flag。
