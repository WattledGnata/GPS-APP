## Context

`TestResultRepository.getDataPointsForResult(testId)`（`core/data/.../TestResultRepository.kt:143`）实现层（line 151-155 当前锚点）：

```kotlin
val testStartWallClock = entity.timestamp
val testEndWallClock = entity.timestamp + (rawSamples.lastOrNull()?.tsDeltaMs ?: 0L)
...
absoluteTsMs = testStartWallClock + sample.tsDeltaMs,
```

归档 spec line 76 normative：`absoluteTsMs = testStartWallClock + sample.tsDeltaMs（§8.4/M anchor 已对齐）`——unargued assertion，未验证 invariant。

### 两 anchor 不同源（形式化）

| 时间锚 | 时钟域 | 来源 |
|---|---|---|
| `entity.timestamp` | GPS 协议时间（UTC 卫星衍生）；未同步 = `Long.MIN_VALUE` | `RaceChronoParser.kt:240` `hourStartMillis + timeSinceHourStart` |
| `sample.tsDeltaMs` | 接收侧本地 `System.currentTimeMillis()` delta | `TestSessionViewModel.kt:744` `(System.currentTimeMillis() - sessionStartTs)` |

`absoluteTsMs = entity.timestamp + sample.tsDeltaMs` **仅在三个 invariant 同时成立时"对齐"**：

1. `entity.timestamp != Long.MIN_VALUE`（GPS 已同步）
2. GPS-UTC-本地壁钟差在容许范围内（NTP 时间漂移容许）
3. session 开始 → binary 第一帧写入期间无 GPS 锁定状态切换（同步→失锁→重同步会让 `hourStartMillis` 跳变）

### 生产能否触发崩塌

- trigger 条件 `satellites>=6 + hdop<2.0` 是 trigger 前置 → `entity.timestamp = Long.MIN_VALUE` 的 PERFORMANCE_TEST **生产不可能存在**
- 但 spec 没显式锁该 invariant，让 future 改 trigger guard 时 reader 侧无第二道防线
- 唯一 catastrophic 场景 = sentinel `Long.MIN_VALUE`：`absoluteTsMs ≈ Long.MIN_VALUE + tsDeltaMs`，chart x 轴语义崩塌

## Goals / Non-Goals

**Goals:**

- reader 侧加 sentinel guard，把 catastrophic 的 `Long.MIN_VALUE` 场景挡在 `readPerformanceSamples` 调用之前
- spec normative 显式化「对齐」的三条款 invariant，消除 unargued assertion
- 单测锁死 sentinel → null 反例，防 future trigger guard 回归

**Non-Goals:**

- 不解决 invariant (2)（GPS-UTC-本地壁钟漂移）和 (3)（GPS 失锁周期切换）—— 数学分析证明漂移 < 5 帧 chart 渲染无可见错乱（见 Decision 1）
- 不迁移 `TestRecord.timestamp` 字段语义（方案 B）
- 不加 Room schema 冗余列（方案 C）
- 不改圈速 reader `getLapTelemetry`（已用 wallClock 同源）

## Decisions

### Decision 1: 选方案 A（reader 加 sentinel guard + spec normative），拒绝 B/C

**方案 A（采用）**：实现层加 1 行 `if (entity.timestamp == Long.MIN_VALUE) return null` + spec 加 normative + 1 个反例 case。

**方案 B（拒绝）**：把 `TestResult.timestamp` 迁移到本地壁钟域 `triggerWallClockMs = System.currentTimeMillis()`，与 `sample.tsDeltaMs` 同源。
- 优点：根本解决跨时钟域，`absoluteTsMs = entity.timestamp + tsDeltaMs` 同源加法
- **拒绝理由**：跨 5+ module 改动（`TestResultRepository` / `CalculateResultUseCase` / `TestSessionViewModel` / `TestRecordEntity` / Records UI）+ Records UI 显示 `timestamp`（"Today, 10:35"）需切语义 + baseline 历史 row migration。当前 Phase 1 仅 chart 消费 `getDataPointsForResult`，在 GPS 同步前提下 GPS 协议时间 ≈ 本地壁钟（漂移容许 <100ms），不构成实际渲染问题。方案 B 是 over-engineering，scope 与"defensive 加固"目标严重不匹配。

**方案 C（拒绝）**：双字段冗余——保留 `entity.timestamp`（GPS 协议时间，UI 显示用）+ 加 `entity.binaryStartWallClock`（本地壁钟，reader 用），类比 §8.3 `crossingTimestampMs / crossingWallClockTimestampMs` 双字段策略。
- 优点：双 anchor 并存，UI 用 GPS 协议时间无需变，reader 同源
- **拒绝理由**：Room schema migration（加列）+ 旧 row null fallback + baseline binary writer 需补"第一帧 wallClock"持久化。当前**没有实际 bug 触发**（生产 0 概率），为 0 触发的隐患做 schema migration 是 over-engineering。**且 schema migration 命中加速通道强制升级 medium 流程**——方案 C 会把本应 trivial 的 round 升级成 medium，与"已在 memo 做完设计"的判断矛盾。

**数学分析（方案 A 的 `absoluteTsMs` 误差范围）**：

- GPS 协议时间 vs 本地壁钟差 = NTP 时间漂移 + GPS-UTC 接收延迟 = **典型 ~50ms（实测 < 200ms）**
- chart x 轴粒度 = 40ms（25Hz）= 1 帧；漂移 < 5 帧，chart 渲染无可见错乱
- 唯一 catastrophic 场景 = sentinel `Long.MIN_VALUE` → 方案 A guard 已阻断

→ 方案 A 用 1 行 guard 挡住唯一 catastrophic 场景，invariant (2)/(3) 的亚帧级漂移留作 P3 backlog（无实际渲染影响）。

### Decision 2: guard 位置 = `dataFilePath.isEmpty()` 之后、`readPerformanceSamples` 调用之前

**选择**：在当前 line 145（`if (entity.dataFilePath.isEmpty()) return null`）之后插入 `if (entity.timestamp == Long.MIN_VALUE) return null`。

**理由**：

- sentinel guard MUST 在 `readPerformanceSamples` 调用**之前**——否则即使 samples 读出来，`absoluteTsMs` 仍是 catastrophic 值，且白白做了一次 IO
- 放在 `dataFilePath.isEmpty()` 之后保持"先校验 metadata 合法性，再做 IO"的既有顺序（与 `getLapTelemetry` 的 entity null check → crossing 校验 → readLapSamples 顺序一致）

**对比**：

| 位置 | 优 | 劣 |
|---|---|---|
| **dataFilePath.isEmpty() 之后（采用）** | 与既有 metadata 校验段聚合；sentinel 阻断在 IO 之前 | 无 |
| readPerformanceSamples 之后 | —— | 白做一次 IO；sentinel 已污染 absoluteTsMs |
| 派生 absoluteTsMs 时 per-sample 判断 | —— | per-sample 重复判断同一个 entity.timestamp，冗余 |

### Decision 3: spec 走 Modified Capability delta + 同步归档 W1 spec

**选择**：

1. 本 round `specs/lap-telemetry-readers/spec.md` 用 `## MODIFIED Requirements` 携带更新后的「PERFORMANCE_TEST 完整 dataPoints 切片读取」requirement（含新 normative + 新反例 scenario）
2. tasks 含一步：把同样的 normative 增量同步进归档 `archive/2026-05-04-lap-data-readers/specs/lap-telemetry-readers/spec.md`（memo §5 MUST 2）

**理由**：

- `lap-telemetry-readers` capability 由 W1 引入但未 sync 到主 `openspec/specs/`；本 round 是其增量修订，语义上是 Modified Capability
- 同步归档 spec 有 precedent：W1 归档 commit `0cd9dbc` 已修订过归档 spec（hash + spec Req1 isEmpty normative）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **Modified delta + 同步归档（采用）** | 语义诚实（确实在改 W1 的 requirement）；归档 spec 与现实代码对齐 | 需触碰归档目录（有 precedent，可接受） |
| 新建 capability `perftest-anchor-integrity` | 不碰归档 | 两个 spec 同时描述 getDataPointsForResult，易矛盾 |

## Risks / Trade-offs

- **[invariant (2)/(3) 未解决]** → GPS-UTC-本地壁钟漂移 + GPS 失锁周期切换仍存在。**Mitigation**：数学分析证明漂移 < 5 帧 chart 无可见错乱；catastrophic 的 sentinel 场景已被方案 A guard 覆盖；(2)/(3) 留 P3 backlog，无实际渲染影响。透明声明在 spec invariant 三条款中。
- **[guard 在生产永不触发 → grep gate trivially pass 风险]** → sentinel 场景生产 0 触发，单测是唯一验证路径。**Mitigation**：case L 用 fake DAO 注入 `entity.timestamp = Long.MIN_VALUE` + `verify(exactly = 0) { telemetryRepository.readPerformanceSamples(any()) }` 双断言（返回 null + 阻断在 IO 前），而非仅 grep 源码字面量（v3 盲点 #7 防护）。
- **[归档 spec 修订漂移]** → memo 写于 5/5，引用的 line 号（line 76 / line 144）可能已偏移。**Mitigation**：apply 期 #3 自查实测 grep 锚点（已在 ff 期验证：`getDataPointsForResult` 现 line 143、`dataFilePath.isEmpty()` 现 line 145）。

## Migration Plan

无 schema / 协议 migration（方案 A 选择 spec normative + 实现层 guard，0 schema 改动）。

部署步骤：

1. 主区开 worktree `.worktrees/unify-perftest-anchor-cross-clock`，切到 `feature/track-tech-v2`
2. 看板 §5 登记本 round；§6 无共享文件占用（独占 `TestResultRepository.kt` + `LapTelemetryReadersTest.kt`）
3. 实施 tasks.md（1 行 guard + spec normative + case L）
4. `:core:data:testDebugUnitTest` 全绿（11 cases）
5. commit + ff-only 合回主区（真机 SKIP，纯数据层 defensive guard）
6. Codex L2 单线兜底（加速通道）+ metrics.yaml + 归档；push 等 user 拍板

## Open Questions

无。设计层 alternatives + 数学分析在 deferred memo `docs/design/lap-perftest-anchor-cross-clock-deferred.md` §3-4 已闭合；本 round 是机械实施。
