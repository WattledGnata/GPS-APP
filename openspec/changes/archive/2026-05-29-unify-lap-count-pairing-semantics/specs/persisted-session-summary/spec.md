## MODIFIED Requirements

### Requirement: TelemetryRepository.endSession 派生并持久化 summary（accepted SF crossing pairs 语义）

`TelemetryRepository.endSession(sessionId: String)` MUST：

1. 关闭 active writer（baseline 行为，不变）
2. 用 `withContext(Dispatchers.IO)` 切线程跑：
   - 调 `readPerformanceSamples(filePath)` 派生 `topSpeedKmh = samples.maxOfOrNull { it.speedKmh }?.takeIf { it > 0.0 }`
   - 调 `crossingDao.queryBySessionId(sessionId)` 派生 lapCount/bestLapMs（accepted SF pairs 语义）
3. 调 `sessionDao.updateSummary(sessionId, endTs, lapCount, bestLapMs, topSpeedKmh)` 一次写齐 4 字段

**lapCount 派生语义**（"accepted SF crossing pairs"，**不承诺** qualityFlags 过滤；本 round `unify-lap-count-pairing-semantics` **修订排序键**为 `crossingWallClockTimestampMs`）：

派生 MUST 满足：

- 过滤 `accepted=true && gateType="StartFinish"` 的 crossing
- **MUST 按 `crossingWallClockTimestampMs ?: Long.MAX_VALUE` 升序排序**（接收侧真壁钟域；与 `getLapTelemetry`（站点 C）完全同款排序键；null 项排到末尾不污染前缀的 wallClock 非空配对）
- **MUST NOT 用 `crossingTimestampMs`（GPS 协议时钟，`mod 3,600,000` 解码 + hourStartMillis 切换）作为本派生路径的排序键或 duration 减法基**——理由：endSession 派生的 lapCount 必须与 `getLapTelemetry` 的 lapIndex 配对身份同源（否则 Records 列表 lapCount 与可点击/可打开的圈集合分歧）；且 GPS 协议时钟跨整点回绕会让相邻圈出现 `b < a` 负 duration
- duration 仅对"起止两 crossing 的 `crossingWallClockTimestampMs` 均非空"的相邻对计算（用 wallClock 减法）；任一端 wallClock 为 null 的相邻对 **MUST NOT** 计入有效圈（与 `getLapTelemetry` 对 null wallClock 圈返回 null = "该圈不可读" 收敛）
- lapCount = 有效（均非空 wallClock）相邻对配对数；bestLapMs = 这些有效 duration 的 minOrNull

```kotlin
val acceptedSF = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
    .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }   // wallClock 域，与 getLapTelemetry 同源
val durations = acceptedSF.zipWithNext { a, b -> a to b }
    .mapNotNull { (a, b) ->
        val sa = a.crossingWallClockTimestampMs
        val sb = b.crossingWallClockTimestampMs
        if (sa != null && sb != null) sb - sa else null     // 任一端 null 不计有效圈
    }
val lapCount = durations.size       // wallClock 域有效配对数
val bestLapMs = durations.minOrNull()
```

**圈配对身份同源不变式（normative · 本 round 核心契约）**：

endSession（站点 A）、`getLapTelemetry`（站点 C）、`deriveDetailMetrics`（站点 B，见本 spec 后续 ADDED requirement）对"accepted SF crossing 如何配对成圈、第 N 圈是哪一对"**MUST 用同一套排序键（`crossingWallClockTimestampMs ?: Long.MAX_VALUE` 升序）与同一套 null 处理规则**。该不变式保证：对任意 session，按本配对规则得到的"第 N 圈（zero-based lapIndex = N，1-based lapNumber = N+1）"在 A/B/C 三站点指向**同一物理圈**。违反该不变式（任一站点用 `crossingTimestampMs` 排序）= 用户点击 detail 屏 "Lap N" 行打开错圈。

**已知语义差异**（与 `add-lap-session-phase1` round 的 Snackbar `finishActiveLapSession` 用 `LapSession.completedLaps.filter { qualityFlags.isEmpty() }` 派生计数不一致 —— 见本 spec 后续 ADDED requirement「Snackbar 实时计数归口为 display-only」明确其 normative 用途边界）：

- Snackbar lapCount（站点 D）基于 in-memory `LapSession.completedLaps` + qualityFlags 过滤，是**实时显示计数**
- entity lapCount（站点 A，本 round）基于 crossings + 仅 accepted（不读 qualityFlags，crossings 表无该字段）+ wallClock 配对，是**持久化圈配对身份数**
- 两端在"有作废圈（qualityFlags 非空）"时数字可能不同——这是**有意保留的设计**（D = display-only 实时有效圈数；A = 持久化 accepted 配对身份数），由后续 ADDED requirement 锁死 D **MUST NOT** 作为圈导航 lapIndex 来源

`TelemetrySessionDao` MUST 保留 `updateSummary` 方法（签名不变，本 round 不动 DAO）：

```kotlin
@Query("""
    UPDATE telemetry_sessions
    SET endTs = :endTs, lapCount = :lapCount, bestLapMs = :bestLapMs, topSpeedKmh = :topSpeedKmh
    WHERE sessionId = :sessionId
""")
suspend fun updateSummary(
    sessionId: String,
    endTs: Long,
    lapCount: Int,
    bestLapMs: Long?,
    topSpeedKmh: Double?,
)
```

baseline `updateEndTs` 保留（不删；其他 callsite 仍可用）。

binary 文件不存在或为空时 `topSpeedKmh = null`；crossings 为空时 `lapCount = 0` / `bestLapMs = null`。全部 accepted SF crossing 的 `crossingWallClockTimestampMs` 均为 null（§8.3 migration 之前历史 session）时 `lapCount = 0` / `bestLapMs = null`（与 `getLapTelemetry` 对全 null 返回 null 一致——旧数据本就无法打开 telemetry）。endSession **不抛异常**。

#### Scenario: endSession 在 IO 调度跑扫描

- **GIVEN** 实施后 `TelemetryRepository.endSession` 实现
- **WHEN** 阅读函数 body
- **THEN** 含 `withContext(Dispatchers.IO)` 调用包裹 `readPerformanceSamples` + `crossingDao.queryBySessionId`

#### Scenario: endSession 写齐 4 字段

- **GIVEN** 实施后 `TelemetryRepository.endSession` 实现
- **WHEN** grep `sessionDao.updateSummary` 调用
- **THEN** 命中一次
- **AND** 参数包含 `endTs / lapCount / bestLapMs / topSpeedKmh`

#### Scenario: lapCount 派生用 wallClock 配对（与 getLapTelemetry 同源）

- **GIVEN** 一个 session 内有 4 个 accepted StartFinish crossing，`crossingWallClockTimestampMs` 分别 = 1000 / 2200 / 3300 / 4400（均非空）+ 1 个 rejected StartFinish（accepted=false, wallClock=2700, reason=WrongDirection）
- **WHEN** endSession 派生 lapCount
- **THEN** `lapCount == 3`（4 个 accepted SF 按 wallClock 升序 zipWithNext → 3 对有效配对）
- **AND** `bestLapMs == 1100`（durations [1200, 1100, 1100] 的 minOrNull）
- **AND** rejected crossing 不计入 lapCount

#### Scenario: 反例——endSession MUST NOT 用 crossingTimestampMs 排序（跨时钟域分歧时与 getLapTelemetry 错位）

- **GIVEN** 一个 session 含 3 个 accepted SF crossing，其 `crossingTimestampMs`（GPS 协议时钟）与 `crossingWallClockTimestampMs`（壁钟）**排序顺序不同**（模拟跨整点回绕：GPS 时钟序为 [c1=100, c2=200, c3=300]，但 wallClock 序为 [c1=1700000000300, c2=1700000000100, c3=1700000000200] —— 即 GPS 序 c1<c2<c3 而 wallClock 序 c2<c3<c1）
- **WHEN** endSession 派生 lapCount/bestLapMs
- **THEN** 派生 MUST 基于 wallClock 排序（c2→c3→c1）得到的相邻对，**而非** GPS 时钟排序（c1→c2→c3）的相邻对
- **AND** 若实现误用 `crossingTimestampMs` 排序，则 endSession 配对与 `getLapTelemetry`（按 wallClock 排序）配对指向不同圈 → 该 case 断言（endSession 派生的 bestLapMs / 圈边界 == 按 wallClock 排序手算结果）将 fail，锁死"MUST 用 wallClock"

#### Scenario: 反例——含 null wallClock 的相邻对不计有效圈（与 getLapTelemetry null 处理收敛）

- **GIVEN** 一个 session 含 5 个 accepted SF crossing：前 2 个 `crossingWallClockTimestampMs = null`（旧 row）、后 3 个 wallClock 非空（如 5000 / 6100 / 7200）
- **WHEN** endSession 派生 lapCount
- **THEN** `lapCount == 2`（仅后 3 个非空 wallClock 排序后 zipWithNext → 2 对有效配对；前 2 个 null 排末尾且不与任何非空对计有效 duration）
- **AND** 该有效圈数 == `getLapTelemetry` 依次调 lapIndex=0,1 返回非 null、lapIndex=2 返回 null 的可读圈数（两站点对"可读圈集合"判断一致）
- **AND** 若实现对 null 端相邻对仍计 duration（fallback 或误算），lapCount 会 > 2 → 与 getLapTelemetry 可读圈数分歧，断言 fail

#### Scenario: lapCount 不依赖 LapRecord.qualityFlags

- **GIVEN** 实施后 endSession 派生逻辑源码
- **WHEN** grep `qualityFlags`
- **THEN** 在 `TelemetryRepository.endSession` 实现内**零命中**（不读 LapRecord.qualityFlags；crossing 表无该字段，与 Snackbar 路径计数差异由本 spec 后续 ADDED requirement 归口）

#### Scenario: binary 缺失时 topSpeed null

- **GIVEN** session.binaryFilePath 指向不存在的文件
- **WHEN** endSession 派生 topSpeedKmh
- **THEN** topSpeedKmh = null
- **AND** endSession 不抛异常
- **AND** sessionDao.updateSummary 仍被调用（其他 3 字段仍写入）

## ADDED Requirements

### Requirement: detail 屏圈列表（deriveDetailMetrics）按 wallClock 配对，圈编号与 getLapTelemetry lapIndex 严格同源

`LapSessionDetailScreen.deriveDetailMetrics(crossings: List<TelemetryCrossingEvent>): DetailMetrics`（`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`）SHALL 派生用户可见的 "Lap N" 圈列表，其圈编号 **MUST** 与 `getLapTelemetry`（站点 C）的 lapIndex 配对身份严格同源。

实现 MUST 满足：

- accepted SF crossing（`gateType=StartFinish && accepted`）**MUST 按 `crossingWallClockTimestampMs ?: Long.MAX_VALUE` 升序排序**（与 endSession / getLapTelemetry 同款排序键）
- **MUST NOT** 用 `crossingTimestampMs`（GPS 协议时钟）作为 accepted SF 配对路径的排序键或 duration 减法基
- duration 仅对"起止两 crossing 的 `crossingWallClockTimestampMs` 均非空"的相邻对计算（用 wallClock 减法）；任一端 null 的相邻对不计有效圈
- 第 N 个有效相邻对（**zero-based idx**）→ `UiLapRecord.lapNumber = idx + 1`（1-based 展示），使用户点击 "Lap (idx+1)" 行时，detail 屏 **MUST** 能用 `getLapTelemetry(sessionId, idx)` 取到同一物理圈（lapIndex = lapNumber - 1）
- rejected SF crossing 的 `lapNumber` 编号在有效圈之后顺延（baseline 行为，本 round 不改 rejected 圈展示语义；rejected 圈不可点击打开 telemetry）

**圈编号 ↔ lapIndex 映射契约（normative）**：

- detail 屏可见 "Lap N" 行（N 为 1-based `lapNumber`，仅 status=VALID/BEST 的有效圈）对应 `getLapTelemetry(sessionId, N - 1)`
- 任何圈导航（detail 屏点击、未来多圈比较屏选圈）**MUST** 用 `lapIndex = lapNumber - 1` 这一映射，**MUST NOT** 用其他来源（如 `CrossingEventEntity.lapIndex` 字段——它是 `LapTimingEngine` 写入的 1-based 引擎内部编号，与本配对身份不同源；亦 **MUST NOT** 用 Snackbar display count）

#### Scenario: 圈列表圈编号与 getLapTelemetry lapIndex 同源

- **GIVEN** 一个 session 含 4 个 accepted SF crossing，wallClock 升序 = [w0, w1, w2, w3]（均非空，构成 3 个有效相邻对）
- **WHEN** deriveDetailMetrics 派生圈列表 + 调 getLapTelemetry(sessionId, 0/1/2)
- **THEN** 圈列表含 `Lap 1 / Lap 2 / Lap 3`（lapNumber = 1/2/3）
- **AND** `getLapTelemetry(sessionId, 0)` 取到的圈 lapStartWallClock == w0、lapEndWallClock == w1，对应圈列表 `Lap 1`
- **AND** `getLapTelemetry(sessionId, 1)` 对应 `Lap 2`（w1→w2）；`getLapTelemetry(sessionId, 2)` 对应 `Lap 3`（w2→w3）
- **AND** 三站点（A endSession lapCount=3 / B 列表 3 行 / C 可读 3 圈）对圈集合判断完全一致

#### Scenario: 跨时钟域排序分歧时圈列表仍与 getLapTelemetry 同源

- **GIVEN** 3 个 accepted SF crossing，GPS 协议时钟序 [c1, c2, c3] 与 wallClock 序 [c2, c3, c1] 不同（模拟整点回绕）
- **WHEN** deriveDetailMetrics（本 round 改后用 wallClock）派生圈列表
- **THEN** 圈列表 `Lap 1` = wallClock 序首对 (c2, c3)、`Lap 2` = (c3, c1)
- **AND** `getLapTelemetry(sessionId, 0)` 取到 (c2, c3) == `Lap 1`、`getLapTelemetry(sessionId, 1)` 取到 (c3, c1) == `Lap 2`（同源）

#### Scenario: 反例——deriveDetailMetrics MUST NOT 用 crossingTimestampMs 致点击错圈

- **GIVEN** 同上跨时钟域分歧的 3 个 crossing（GPS 序 [c1,c2,c3] ≠ wallClock 序 [c2,c3,c1]）
- **WHEN** 假设 deriveDetailMetrics 仍用 baseline `crossingTimestampMs` 排序（GPS 序）
- **THEN** 圈列表 `Lap 1` = GPS 序首对 (c1, c2)，但 `getLapTelemetry(sessionId, 0)`（wallClock 序）取到 (c2, c3)——**两者指向不同物理圈**，用户点 `Lap 1` 打开的是 (c2,c3) 的曲线
- **AND** 单测 MUST 断言"deriveDetailMetrics 第 0 圈的 (startWallClock, endWallClock) == getLapTelemetry(sessionId, 0) 的 (lapStartWallClock, lapEndWallClock)"，该断言在误用 crossingTimestampMs 时 fail，锁死"MUST 用 wallClock"

### Requirement: Snackbar 实时计数（finishActiveLapSession）归口为 display-only，MUST NOT 作为圈导航 lapIndex 来源

`TestSessionViewModel.finishActiveLapSession()` 派生的 `LapSessionSaveResult.lapCount`（基于 in-memory `completedLaps.filter { qualityFlags.isEmpty() }.size`，`feature/test/.../viewmodel/TestSessionViewModel.kt:567-568`）SHALL 仅用于 session 结束时的 Snackbar 实时显示计数（"X laps" 文案），**MUST NOT** 被任何圈导航路径（detail 屏点击打开第 N 圈、多圈比较屏选圈）用作 `lapIndex` / `lapNumber` 来源。

本 round **不修改** `finishActiveLapSession` 的 qualityFlags 计数计算逻辑（保留实时排除作废圈的合理实时反馈语义）。本 requirement 仅 normative 锁定其用途边界，关闭"三套圈数语义未完全闭合"的 half-closure 风险：

- Snackbar 计数（站点 D）= 实时 valid（qualityFlags 空）圈数，用途 = 即时反馈
- 持久化 lapCount（站点 A）/ detail 圈列表（站点 B）/ telemetry 切片（站点 C）= wallClock 配对身份数，用途 = 圈导航 + 持久化展示
- 两类口径在有作废圈时数字可能不同，这是**有意区分**，不是 bug

#### Scenario: Snackbar 计数与持久化 lapCount 在有作废圈时可不同（expected）

- **GIVEN** 一个 session 含 3 个 accepted SF crossing（均非空 wallClock）→ A/B/C 口径 wallClock 配对 = 2 圈；且 in-memory `completedLaps` 中这 2 个有效圈里有 1 个被标 `qualityFlags = [IncompleteSectors]`（作废圈）
- **WHEN** finishActiveLapSession 派生 Snackbar lapCount（qualityFlags 过滤）vs endSession 派生 entity.lapCount（wallClock 配对）
- **THEN** Snackbar lapCount == 1（2 个 valid 圈排除 1 个作废圈）；entity.lapCount == 2（accepted SF wallClock 配对，不读 qualityFlags）
- **AND** 两个数字不同是 **expected 行为**（display count vs pairing identity），**MUST NOT** 因此判定为本 round 未闭环

#### Scenario: 圈导航 lapIndex MUST 来自 wallClock 配对身份而非 Snackbar 计数

- **GIVEN** detail 屏 / 未来圈导航需要打开"第 N 圈"telemetry
- **WHEN** 计算传给 `getLapTelemetry` 的 lapIndex
- **THEN** lapIndex **MUST** = detail 屏圈列表 `lapNumber - 1`（wallClock 配对身份，站点 B）
- **AND** **MUST NOT** 用 Snackbar `LapSessionSaveResult.lapCount` 或 in-memory `completedLaps` 索引推导 lapIndex

#### Scenario: 反例——若圈导航误用 Snackbar 计数推导 lapIndex 则打开错圈/越界

- **GIVEN** 一个 session：wallClock 配对身份有 3 圈（getLapTelemetry 可读 lapIndex 0/1/2），但 in-memory `completedLaps` 因 qualityFlags 过滤后只剩 2 个 valid 圈
- **WHEN** 假设圈导航错误地按 Snackbar valid 计数（2）来限制可点击圈，或按 in-memory valid 圈在 completedLaps 中的下标作为 lapIndex
- **THEN** 该映射与 wallClock 配对身份（站点 B/C）错位——可能漏掉第 3 个可读圈，或把 in-memory 下标错当 getLapTelemetry lapIndex 打开错圈
- **AND** 本 round normative 锁死圈导航 lapIndex MUST 来自站点 B 的 lapNumber-1，该反例形态被禁止
