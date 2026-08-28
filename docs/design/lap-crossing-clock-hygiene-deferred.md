# Lap crossing event 时钟域 hygiene — 延期立项设计 memo

**状态**：deferred，将作为独立 round `fix-lap-crossing-clock-hygiene` 立项

**起源**：`fix-lap-binary-ts-hygiene` round Codex review §2 揭示——本 round 把 binary samples 的 `absoluteTs` 修复为接收侧真壁钟后，仍然不能解锁 per-lap / sector segment 的 readLapSamples 窗口，因为 `TelemetryCrossingEvent.crossingTimestampMs` 仍来自 GPS 协议时间（`crossing.timestampMillis`），用它作为窗口与真壁钟 samples 仍然 0 命中

**关联**：本文件由 `fix-lap-binary-ts-hygiene` round 的 design.md 决策 5 + tasks.md §8.3 backlog 引用

---

## 1. 现象

`fix-lap-binary-ts-hygiene` 修复后，binary samples 的 `absoluteTs` 已对齐接收侧真壁钟（与 `entity.startTs / entity.endTs` 同时钟域）。但若用 crossing event 的 timestamp 做窗口截取单圈样本：

```kotlin
// 假设未来实现
val crossings = repository.getCrossings(sessionId)
for (i in 0 until crossings.size - 1) {
    val lapSamples = repository.readLapSamples(
        filePath,
        lapStartTs = crossings[i].crossingTimestampMs,    // GPS 协议时间
        lapEndTs   = crossings[i + 1].crossingTimestampMs // GPS 协议时间
    )
    // ↑ 窗口是协议时间，binary samples absoluteTs 是真壁钟 → 0 命中
}
```

结果：所有 per-lap segment 调用返回空 list，Analysis Mode 单圈轨迹、sector 分段、Records LAPS sub-tab 圈分段读取仍然不可用。

## 2. 根因（数据流污染图）

### 2.1 crossing event 时间字段链路

```
LapTimingEngine.processSample(sample)
  ↓ sample.timestampMillis = GpsData.timestamp (GPS 协议解码 epoch ms)
crossing = CrossingEvent(timestampMillis = sample.timestampMillis, ...)
  ↑ 协议时间（in-memory pure domain, feature/test/.../laptiming/CrossingEvent.kt）
  ↑ **不在本 round 修改范围**——LapTimingEngine 纯函数 contract 不允许注入 currentTimeMillis()，
     且其下游消费方（LapDebugExecutionScreen 实时面板等）都在同一活跃帧/小时段内做协议时间减法
  ↓
TestSessionViewModel.kt:891 附近 writeCrossing 调用栈
  （行号会随其他 round 合回漂移；apply 时按 grep -n "TelemetryCrossingEvent(" 实际命中位置为准）
TelemetryCrossingEvent(crossingTimestampMs = crossing.timestampMillis, ...)
  ↑ 协议时间，pass-through 到 Room 持久化层
  ↓
TelemetryRepository.writeCrossing → CrossingEventEntity.crossingTimestampMs
  ↑ Room 持久化，仍是协议时间
```

### 2.2 binary samples 时间字段链路（fix-lap-binary-ts-hygiene 修复后）

```
TestSessionViewModel.bridgeGpsToLapTiming
  ↓ sessionStartTs = repository.activeSessionStartTs (真壁钟)
TelemetrySample(tsDeltaMs = currentTimeMillis - sessionStartTs, ...)
  ↑ 真壁钟差
  ↓
BinaryTelemetryWriter.write → file
  ↓
BinaryTelemetryReader.read → absoluteTs = header.startTs (真壁钟) + tsDeltaMs (真壁钟差)
                          = 真壁钟时刻
```

### 2.3 时钟域不匹配

| 数据源 | 时钟域 |
|---|---|
| `TelemetryCrossingEvent.crossingTimestampMs` | GPS 协议时间（epoch ms 但来自协议解码） |
| `TelemetrySample.absoluteTs`（binary 重建） | 接收侧真壁钟 |
| `entity.startTs / endTs` | 接收侧真壁钟 |

任何"用 crossing time 做窗口过滤 samples"调用都会因时钟域不匹配返回空。

### 2.4 影响面

| UI / 派生功能 | 现状 | 阻塞原因 / 修复路径 |
|---|---|---|
| Analysis Mode 单圈轨迹（用两个连续 crossing 截取一圈） | 不可用 | crossing 协议时间 vs samples 真壁钟。本 round 修复 |
| Sector 分段（用 sector gate crossing 截取 sector） | 不可用 | 同上。本 round 修复 |
| Records LAPS sub-tab 圈分段读取 | 不可用 | 同上。本 round 修复 |
| Detail 屏全 session 全样本派生（如 TOP SPEED） | 可用（fix-lap-binary-ts-hygiene 解锁） | 用 session start/end 真壁钟窗口 |
| **`endSession` 派生 `bestLapMs / lapCount`**（line 161-164） | **跨小时切换有 silent failure** | 用 `b.crossingTimestampMs - a.crossingTimestampMs` 协议时间减法。GPS 跨小时切换时（mod 3,600,000 解码 + hourStartMillis 切换）产生负数 / 1+ 小时错乱值，写入 `entity.bestLapMs` 影响 Records 列表。**本 round 不顺手切**（保持 scope 紧），follow-up `migrate-lap-duration-derivation-to-wallclock` 复用本 round 加的 wallClock 字段切派生层 |
| **`LapSessionDetailScreen.deriveDetailMetrics`**（line 471-515）派生 lap durations | **同上跨小时风险** | 同款减法。同 follow-up |

## 3. 修复方案对比

### 3.1 方案 A（推荐 · v3 review 修订）：仅 Room 持久化层加双时钟域字段

**关键架构边界**（v3 review §P0#2 修订）：仅 `TelemetryCrossingEvent`（domain DTO）+ `CrossingEventEntity`（Room schema）加 wallClock 字段；**不动** `feature/test/.../laptiming/CrossingEvent.kt`（in-memory pure domain object，由 LapTimingEngine 纯函数产出）。

```kotlin
// core/domain/.../TelemetryCrossingEvent
data class TelemetryCrossingEvent(
    val sessionId: String,
    val lapIndex: Int,
    val crossingTimestampMs: Long,                  // 保留：GPS 协议时间（UI 显示用）
    val crossingWallClockTimestampMs: Long? = null, // 新增：接收侧真壁钟（nullable，旧数据 null）
    ...
)
```

写入：`TestSessionViewModel.kt:891 附近` 构造 `TelemetryCrossingEvent` 调用 `repository.writeCrossing` 的位置（行号会随其他 round 合回漂移；apply 时按 grep 命中位置为准），过线事件触发瞬间记录 `System.currentTimeMillis()` 作为 wallClockTimestampMs；与 `crossing.timestampMillis` 一同传给 repository。

读取：未来 per-lap segment 用 `crossingWallClockTimestampMs` 作为窗口（判 null fallback 全 session），UI 显示仍用 `crossingTimestampMs`（HH:mm:ss）。

- 改动：`core/domain` DTO 加字段、`core/data` Room migration（add nullable column）+ entity 加字段、`feature/test/.../viewmodel/TestSessionViewModel.kt` 写入瞬间取 currentTimeMillis；**不改** `feature/test/.../laptiming/CrossingEvent.kt`
- Room migration：`ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`（**nullable**，旧数据 NULL → 调用方显式判 null fallback；v3 review §P1#1 修订：避免 NOT NULL DEFAULT 0 哨兵让未来 UI 用旧数据时误命中全 session 帧）
- 优点：保留 UI 显示语义、解锁 per-lap segment、向前兼容、调用方判 null 比判 0 安全清晰
- 缺点：需 Room migration、entity 多一列、调用方需显式 null fallback

### 3.2 方案 B：crossing 改用真壁钟（破坏现有显示）

把 `crossing.timestampMillis` / `TelemetryCrossingEvent.crossingTimestampMs` 改用 `System.currentTimeMillis()`。

- 改动：`feature/test/.../laptiming/` 大改、UI 显示需重新 review
- 缺点：
  - 破坏 `LapDebugExecutionScreen.kt:222-243` 的 UI 显示语义（HH:mm:ss 显示 + lap 时长减法）
  - 接收侧真壁钟 vs GPS 时间在 UI 显示上不等价（特别是 GPS 同步前）
  - 影响其他依赖 crossing.timestampMillis 的现有调用方
- 不推荐

### 3.3 方案 C：reader 接受协议时间窗口 + binary 同时存协议 ts

binary sample 加 `protocolTimestampMs` 字段，reader 支持双窗口模式。

- 改动：binary 字节布局变（破坏 A56 的 17 bytes/sample 契约）
- 缺点：偏离 A56 既定 schema、字节宽度变化、向后兼容复杂
- 不推荐

## 4. 推荐方案 + 数学/性能分析

**推荐方案 A**。

### 4.1 数学

记接收侧真壁钟与 GPS 协议时间的偏差为 `Δ(t) = currentTimeMillis - gpsData.timestamp`。在 GPS 时间已 sync + 接收延迟稳定时，Δ(t) 是个慢变量（毫秒级抖动）。

但 Δ(t) 在以下场景非平稳：
- GPS 时间未 sync 期间，Δ(t) 跳变可能达小时级
- 跨小时切换瞬间，gpsData.timestamp 跳变（mod 3,600,000 解码 + hourStartMillis 切换）
- simulator 重启（接收侧真壁钟连续 + 协议时间从头开始）

→ 在 binary samples 与 crossings 之间换算 Δ(t) 不可靠 → MUST 同时存两个时钟域字段。

### 4.2 性能

- Entity 加 1 个 Long column（8 bytes / row），过线事件量级在每 session 几十条 → 增加 < 1KB/session 的存储
- 写入：`System.currentTimeMillis()` 系统调用纳秒级，crossing 写入频率低（每圈 1-3 次）
- 读取：增加 1 column scan 开销可忽略

可接受。

## 5. 实施约束（独立 round 立项时的硬契约）

1. **MUST 用方案 A**（双时钟域字段），不动 binary 字节布局，不破坏 UI 显示语义
2. **MUST 在过线事件触发的同一 ViewModel 协程上下文内**取 `System.currentTimeMillis()` 作为 wallClockTimestampMs（避免与异步写入间产生延迟漂移）
3. **MUST 加 Room migration**（ADD COLUMN nullable，无 NOT NULL 约束，旧数据自动 NULL；调用方 MUST 显式判 null fallback 到全 session 路径，**不**用 NOT NULL DEFAULT 0 哨兵——避免未来 UI 用旧数据时 readLapSamples 误命中全 session 帧）
4. **MUST** 加单元测试，分两层（v3 review v3 §C#3 修订）：
   - **4a · 单测 round trip 字段映射不漏（精确等）**：测试代码手工注入 wallClock 值 + writeCrossing/getCrossings round trip + assertNotNull + 精确等值断言（**不**用 ±100ms 容差，因测试是手工注入值）
   - **4b · 生产路径 100ms 漂移契约（不在本 round 单测覆盖）**：ViewModel scope（Main dispatcher）取 wallClock 与 BinaryTelemetryWriter（IO channel queue）真壁钟漂移 < 100ms 的契约，由真机 sanity check 验证（**当前 follow-up round 默认 user 拍板跳过真机；如未来撞到漂移问题独立立项 `wallclock-drift-monitoring` round**）。本 round 不强制此项闭环，是 honest acknowledgment
5. **MUST** 加 round trip 测试：用 wallClockTimestampMs 作为窗口调 readLapSamples 能正确截取该圈的 samples（与 fix-lap-binary-ts-hygiene 的 round trip 互补）
6. **MUST NOT** 改 `crossing.timestampMillis` 或 `crossingTimestampMs` 现有字段语义（保 UI 显示与历史代码不回归）

## 6. 单元测试覆盖建议

新 round `fix-lap-crossing-clock-hygiene` 的测试套件 MUST 含以下 scenario：

- **`crossing 双时钟域字段独立可读`**：写入 crossing 后查询，protocolTimestampMs 与 wallClockTimestampMs 都符合预期
- **`per-lap segment readLapSamples 用 wallClock 窗口命中`**：写 N 帧 samples + 2 个 crossing（wallClock 在中间），调 readLapSamples 用两 crossing.wallClockTimestampMs 截取，返回正确帧数
- **`Room migration 兼容旧 entity`**：本 round 仅做 SQL 字符串自检（assert `migration4To5Sql.size == 1` + 内容包含 `ADD COLUMN ... INTEGER` 无 NOT NULL）；**真实 v4→v5 schema upgrade 后旧 row wallClock=NULL + 不崩溃 + 调用方判 null fallback 路径**推到 follow-up `room-test-infrastructure` round 验证（需要 androidx.room:room-testing + Robolectric，本 round design §5 不引入这些库）—— v3 review v3 §D memo 同步修订
- **`UI 显示语义不回归`**：grep `LapDebugExecutionScreen.kt` 等 UI 文件仍用 `crossingTimestampMs`（GPS 协议时间）显示 HH:mm:ss，未误用 wallClockTimestampMs

## 7. 与当前 round（fix-lap-binary-ts-hygiene）的协同关系

| Round | 解决问题 | 解锁 |
|---|---|---|
| `fix-lap-binary-ts-hygiene`（已立项） | binary samples absoluteTs 对齐真壁钟 + anchor 同源 | session start/end 窗口的 readLapSamples |
| `fix-lap-crossing-clock-hygiene`（本 memo） | crossing event 加真壁钟字段 | per-lap / sector segment 的 readLapSamples |

**串行关系**：本 round 必须在 `fix-lap-binary-ts-hygiene` 合回后立项实施。前者是后者的前提（binary samples 必须先是真壁钟，crossing 真壁钟字段才能跟它匹配）。

**文件冲突**：本 round 改 `core/data/.../entity/CrossingEventEntity.kt` + `core/data/.../local/AppDatabase.kt`（schema v4→v5 migration）+ `core/data/.../repository/TelemetryRepository.kt`（writeCrossing 内）+ `core/domain/.../TelemetryModels.kt` + `feature/test/.../TestSessionViewModel.kt`（line 891 附近，apply 时按 grep `TelemetryCrossingEvent(` 命中位置为准；行号会随其他 round 合回漂移）+ **`feature/test/.../di/AppModule.kt`**（v3 review 第 3 轮 §P0 修订：`databaseModule` Koin DSL line 51 `addMigrations(...)` 加新 migration；**实际位置在 feature/test 模块**而非 core/data，因 core/data 模块无 DI 入口）。前 round 仅在 `TelemetryRepository.kt` 加 property + bridgeGpsToLapTiming 改公式，文件层面冲突可控。

## 8. 不立刻并入 fix-lap-binary-ts-hygiene 的理由

- 本 round 主题是"binary samples 时间字段对齐"，crossing event 时钟域 hygiene 是 Room schema 改造，主题不同
- crossing 修复需要 Room migration，影响面大于 1 行 bridge 改公式
- 当前 detail 屏走 readPerformanceSamples quick fix 不依赖 per-lap segment，时间窗够立项
- 并行 round C 在改 `core/data/*` entity / migration / DAO，本 round 也需改 entity / migration → 文件冲突重叠 → 等 C 合回后立项更稳（**已满足**：C round `persist-session-summary-fields` 2026-05-01 合回 `dd01aeb` + 归档 `3452003`，本 round 启动条件已就绪）

## 9. 立项节奏

预计独立 round 工件量（v3 review 4 轮后修订）：
- proposal / design / specs / tasks
- 代码改动：core/domain 加字段 (~2 行) + core/data Room migration (~10 行) + entity (+1 column) + repository writeCrossing (~3 行) + feature/test ViewModel 写入 (~3 行) + **feature/test AppModule.kt 加 migration 注册 (~1 行)** + 同步现有 4 个 Fake DAO（`TelemetryRepositoryTest` / `EndSessionPersistTest` / `DeleteSessionTest` 各加 nullable wallClock default）
- 单元测试 **~7-8 个 case**（v3 review 拆 C1/C2 反例 + D/D' grep gate + E1/E2 跨 module SQL/注册自检）
- 估时：1 天工件 + **1 天实施**（多 case + 跨 module file IO 测试 + projectRoot() helper 复用）+ 半天 Codex review + 真机端到端验证（per-lap segment UI 触发的 sample 列表非空，本 round 默认 user 拍板跳过真机）

---

**索引位置**：`openspec/changes/fix-lap-binary-ts-hygiene/tasks.md` §8.3 follow-up backlog 引用本文件
