## ADDED Requirements

### Requirement: 单圈完整 telemetry 切片读取（getLapTelemetry）

`TelemetryRepository` SHALL 暴露 `suspend fun getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?` 高层 reader 方法，组合 session metadata、accepted StartFinish crossing 配对与 binary samples 窗口截取，返回单圈完整 telemetry 切片或 null。

实现 MUST 满足：
- 通过 `getSession(sessionId)` 拿 entity；entity 不存在 → 返回 null
- 通过 `getCrossings(sessionId)` 拿 crossings；过滤 `accepted=true && gateType="StartFinish"`，按 `crossingWallClockTimestampMs ?: Long.MAX_VALUE` 升序排序（null 项排到末尾，不污染前缀的 wallClock 非空配对）
- 第 N 圈定义：accepted SF crossing 列表按上述排序后 zipWithNext 配对，第 N 对（**zero-based**）即第 N 圈；lapIndex 越界（lapIndex < 0 或 lapIndex + 1 >= acceptedSF.size）→ 返回 null
- 第 N 圈起止 crossing 的 `crossingWallClockTimestampMs` 任一为 null → 返回 null（**MUST NOT** fallback 到 `crossingTimestampMs`）
- `readLapSamples(entity.binaryFilePath, lapStartWallClock, lapEndWallClock)` 拿窗口内 samples；调用 **MUST** 包在 `runCatching { ... }.getOrDefault(emptyList())` 内防 IOException 抛出（注：baseline `LapTelemetryReader.read` 仅防 `!file.exists()` / `length() < HEADER_SIZE` 早返回，**不防** `RandomAccessFile.readFully` 在文件中途被删 / 截断时抛 `EOFException`——本 round 在 reader 调用层加 runCatching 兜底）
- `runCatching` 兜底返回 emptyList 也 **MUST** 视为读取失败 → 返回 null（避免 `LapTelemetry.samples = emptyList` 但 lapStart/lapEnd/lapDurationMs 仍有值的语义错乱；与 Requirement 2 line 71 PERFORMANCE 场景措辞一致）。L2 review (Opus 双线) 修订：原稿仅在实现层有 `if (rawSamples.isEmpty()) return null`，spec 未声明该 normative，case D 测试隐式依赖该分支 → 现 spec 显式锁定 invariant
- `LapTelemetry.samples` 列表中每个 sample 派生：
  - `absoluteTsMs = entity.startTs + sample.tsDeltaMs`
  - `elapsedMsInLap = absoluteTsMs - lapStartWallClock`
  - `lat`/`lon`/`speedKmh`/`bearingDeg`/`flags` 透传
  - `accelerationG = null`（本 round 不派生）
- `LapTelemetry.sectorBoundaries = listOf(lapStartWallClock)`（仅含起点；future sector round 在不改本 round 数据契约前提下扩展）
- `LapTelemetry.lapDurationMs = lapEndWallClock - lapStartWallClock`
- `trackId` / `trackNameSnapshot` 透传 `entity.trackId` / `entity.trackNameSnapshot`

**lapIndex 语义独立性（normative）**：
- 本 round `getLapTelemetry` 的 `lapIndex` 参数语义 = "**zero-based**，accepted SF crossing 按 wallClock 升序的第 N 对配对（N >= 0）"
- **MUST NOT** 假设本 round 的 lapIndex 与 baseline `LapTimingEngine.processSample` 写入 `CrossingEventEntity.lapIndex` 字段（**1-based**，参 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:167` `lapIndex = 1` 起步）等同
- **MUST NOT** 假设本 round `getLapTelemetry` 调用 N 次 lapIndex=0..K-1 后返回非 null 的次数等于 `entity.lapCount`（baseline `endSession` line 164 用 `crossingTimestampMs` 排序 zipWithNext 派生 lapCount，本 round 用 `crossingWallClockTimestampMs` 排序 zipWithNext，两套语义在数据完整时收敛但混合 wallClock null 数据时可能不同）
- 调用方 MUST 通过 "依次调用 `getLapTelemetry(s, 0)`, `(s, 1)`, ... 直到首次返回 null" 来遍历可读 lap 集合，而非按 `entity.lapCount` 决定圈数

**accepted SF crossing 的 wallClock 模式 invariant（normative）**：
- 实务上 `crossingWallClockTimestampMs` 字段 §8.3 migration 之前全 row 写 null、migration 之后全 row 写非空，时序上 null 全在 session 前缀、非空全在后缀（混合仅来自 migration 边界一次性事件）
- 本 round `getLapTelemetry` 假设 accepted SF 的 wallClock 模式为 "**前缀连续 null 段 + 后缀连续非空段**"（含全 null / 全非空两种端点退化）
- **MUST NOT** 假设本 round 处理"非空 / null 交错"模式（如 `[w1, null, w3, null, w5]`）—— 本 round `sortedBy { ?: Long.MAX_VALUE }` 在交错场景下会把所有 null 排到末尾、非空升序在前，可能产生**跨原始时序**的 zipWithNext 配对（如把 w1→w3 实际跨过 null 的圈算成 (w1, w2) 同新建 row 的圈）；future round 若引入"writeCrossing wallClock 写失败但 crossingTimestampMs 写成功"路径让生产数据出现交错 null，需 revisit 本 round sort 策略（参 tasks §10.8）

#### Scenario: 正常单圈读取
- **WHEN** session 含 3 个 accepted SF crossing（wallClock 形成 2 对配对）+ binary 文件含覆盖整个 session 的 25 Hz samples（fixture sample 的 `flags` 字段写入非 0 值，如 5），调用 `getLapTelemetry(sessionId, 0)`
- **THEN** 返回非 null 的 LapTelemetry；samples 数量等于 lapStart 到 lapEnd 窗口内 binary 帧数；samples 第 0 项的 elapsedMsInLap >= 0；lapDurationMs == lapEndWallClock - lapStartWallClock；sectorBoundaries.size == 1 且首项 == lapStartWallClock；**samples.first().flags == 5**（透传 baseline `TelemetrySample.flags` 不变，锁定 normative "flags 透传"）

#### Scenario: lapIndex 越界返回 null
- **WHEN** session 含 3 个 accepted SF crossing（仅 2 对配对）但调用 `getLapTelemetry(sessionId, 5)`（lapIndex=5 超出 pairs.size=2）
- **THEN** 返回 null（不抛异常）

#### Scenario: 反例——crossing wallClock 为 null 时 MUST 返回 null
- **WHEN** session 的 accepted SF crossing 全部 `crossingWallClockTimestampMs = null`（旧 row 模拟，§8.3 migration 之前数据），调用 `getLapTelemetry(sessionId, 0)`
- **THEN** 返回 null；**MUST NOT** 回退到 `crossingTimestampMs`（GPS 协议时间）做 lap 窗口截取——这种回退会让 `readLapSamples` 因跨时钟域必命中 0 帧，但 lapStart/lapEnd 仍有值，让调用方误以为"该圈数据为空"而非"该圈不可读"

#### Scenario: session 不存在返回 null
- **WHEN** 调用 `getLapTelemetry("non-existent-session-id", 0)`
- **THEN** 返回 null

#### Scenario: binary 文件缺失返回 null
- **WHEN** session entity 存在 + crossings 配对正常，但 `entity.binaryFilePath` 指向的文件已被外部删除
- **THEN** 返回 null（不抛异常，依赖 `readLapSamples` 的 IO fail-safe 降级）

#### Scenario: crossings empty list 任意 lapIndex 返回 null
- **WHEN** session entity 存在但 `getCrossings(sessionId)` 返回 empty list（如 lap session 启动后立即 endSession 没产 crossing）
- **THEN** 任意 lapIndex（含 0 / 1 / -1 / Int.MAX_VALUE）调 `getLapTelemetry` 返回 null（boundary check `lapIndex + 1 >= acceptedSF.size` 在 acceptedSF.size = 0 时 lapIndex+1 >= 0 永远成立）

#### Scenario: 中间态——部分 crossing wallClock 非空时混合返回（可读圈与不可读圈共存）
- **WHEN** session 含 5 条 accepted SF crossing：前 2 条 `crossingWallClockTimestampMs = null`（旧 row migration 之前）、后 3 条 wallClock 非空（新 row）；新 row 之间形成 2 对相邻配对
- **THEN** 调用 `getLapTelemetry(s, 0)` 返回非 null（命中后 3 条 wallClock 非空 zipWithNext 第 0 对）；`getLapTelemetry(s, 1)` 返回非 null（命中后 3 条 zipWithNext 第 1 对）；`getLapTelemetry(s, 2)` 返回 null（第 2 对的 lapEnd crossing 是排到末尾的 null wallClock 旧 row）；`getLapTelemetry(s, 3)` 及以上返回 null（lapIndex 越过总配对数）；调用方 MUST 通过"依次调用直到首次 null"遍历可读 lap，而非按 `entity.lapCount` 决定圈数

---

### Requirement: PERFORMANCE_TEST 完整 dataPoints 切片读取（getDataPointsForResult）

`TestResultRepository` SHALL 暴露 `suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry?` 高层 reader 方法，组合 TestRecord metadata 与 binary samples 顺序读，返回 PERFORMANCE_TEST 完整 dataPoints 切片或 null。

实现 MUST 满足：
- 通过 `testRecordDao.getTestRecordById(testId)` 拿 entity；entity 不存在 → 返回 null
- entity 存在但 `dataFilePath` 为空字符串（baseline default `""`）→ 返回 null
- entity 存在但 `entity.timestamp == Long.MIN_VALUE`（GPS 未同步 sentinel）→ 返回 null（MUST 在 `readPerformanceSamples` 调用之前阻断；reader 侧 defensive 第二道防线，详下方 invariant 三条款 + follow-up round `unify-perftest-anchor-cross-clock` 增量修订）
- 通过注入的 `telemetryRepository.readPerformanceSamples(dataFilePath)` 顺序读 binary samples；调用 **MUST** 包在 `runCatching { ... }.getOrDefault(emptyList())` 内防 IOException 抛出（与 `getLapTelemetry` 同款防护理由：baseline `PerformanceTestTelemetryReader.read` 不防 readFully 中途抛 EOFException）
- binary 文件不存在 / 读取异常 / readPerformanceSamples 返回 emptyList → 返回 null（**MUST** 把"empty samples"视为读取失败，避免 `PerformanceTelemetry.samples = emptyList` 但 `testStartWallClock/testEndWallClock` 有值的语义错乱）
- `PerformanceTelemetry.testStartWallClock = entity.timestamp`（PERFORMANCE_TEST 起点 wallClock，TestRecord 字段）
- `PerformanceTelemetry.testEndWallClock = entity.timestamp + (samples 最后一帧 tsDeltaMs ?: 0)`（用 binary 最后帧的 tsDeltaMs 派生）
- samples 列表中每个 LapTelemetrySample 派生：
  - `absoluteTsMs = testStartWallClock + sample.tsDeltaMs`（**跨时钟域加法**：`testStartWallClock = entity.timestamp` 是 GPS 协议时间域，`sample.tsDeltaMs` 是接收侧本地壁钟 delta 域；"对齐"仅在三条 invariant 同时成立时成立——(1) `entity.timestamp != Long.MIN_VALUE`【本 round sentinel guard 显式锁定】(2) GPS-UTC-本地壁钟漂移 < 5 帧【P3 backlog，无可见错乱】(3) session 内无 GPS 失锁→重同步周期【P3 backlog】。follow-up round `unify-perftest-anchor-cross-clock` 把原「§8.4/M anchor 已对齐」unargued assertion 替换为本三条款）
  - `elapsedMsInLap = sample.tsDeltaMs`（PERFORMANCE 场景下 elapsedMsInLap 语义即"测试中累计耗时"，等于 tsDeltaMs）
  - `lat`/`lon`/`speedKmh`/`bearingDeg`/`flags` 透传
  - `accelerationG = null`

#### Scenario: 正常 PERFORMANCE_TEST 读取
- **WHEN** TestRecord(`testId-001`, dataFilePath="/tmp/test.bin", timestamp=1700000000000) 存在 + binary 文件含 100 帧 samples（fixture sample 的 `flags` 字段写入非 0 值，如 7），调用 `getDataPointsForResult("testId-001")`
- **THEN** 返回非 null 的 PerformanceTelemetry；samples.size == 100；testStartWallClock == 1700000000000；samples[0].elapsedMsInLap >= 0；samples[i].absoluteTsMs == testStartWallClock + samples[i].tsDeltaMs；**samples.first().flags == 7**（透传 baseline `TelemetrySample.flags` 不变，锁定 normative "flags 透传"）

#### Scenario: testId 不存在返回 null
- **WHEN** 调用 `getDataPointsForResult("non-existent-test-id")`
- **THEN** 返回 null（不抛异常）

#### Scenario: 反例——entity.dataFilePath 为空字符串返回 null
- **WHEN** TestRecord entity 存在但 `dataFilePath = ""`（baseline default，未持久化 binary 路径），调用 `getDataPointsForResult(testId)`
- **THEN** 返回 null；**MUST NOT** 把空字符串当合法路径传给 `readPerformanceSamples`（避免 File("").exists() 等假性命中）

#### Scenario: binary 文件缺失返回 null
- **WHEN** TestRecord entity 存在 + dataFilePath 非空，但文件被外部删除
- **THEN** 返回 null（实现层 runCatching 兜底 IOException + 把 emptyList 视为读取失败，不抛异常）

#### Scenario: 反例——binary 文件存在但 0 samples 返回 null
- **WHEN** TestRecord entity 存在 + dataFilePath 指向真实文件 + 文件含合法 header 但 sample 段 0 帧（writer 启动 + 立即 close 未写 sample），调用 `getDataPointsForResult(testId)`
- **THEN** 返回 null；**MUST NOT** 返回 `PerformanceTelemetry(samples = emptyList(), testStartWallClock = entity.timestamp, testEndWallClock = entity.timestamp + 0)`——避免 "samples 空但 wallClock 有值" 语义错乱让 UI 误显示空 chart

---

### Requirement: 数据契约形态稳定性（LapTelemetry / LapTelemetrySample / PerformanceTelemetry）

`core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt` SHALL 定义 3 个 data class，字段顺序与默认值与 sketch §1 锁定，作为 Phase 1 W2/W3 mock 与 Tier2 集成的数据契约。

`LapTelemetrySample` MUST 含字段：
- `absoluteTsMs: Long`（接收侧真壁钟）
- `elapsedMsInLap: Long`（lap 场景=absoluteTsMs - lapStartWallClock；test 场景=tsDeltaMs）
- `lat: Double`
- `lon: Double`
- `speedKmh: Double`
- `bearingDeg: Double?`（GPS 静止时 null）
- `accelerationG: Double? = null`（本 round 不派生，default null）
- `flags: Int = 0`（透传 baseline `TelemetrySample.flags` binary frame quality flags）

**accelerationG 派生语义（normative，避免假闭环）**：
- `LapTelemetrySample` 是 immutable data class —— W3 round (`lap-comparison-time-align`) 落地时若需派生 `accelerationG`，**MUST** 通过 `samples.map { it.copy(accelerationG = derived) }` 重新构造一份 List<LapTelemetrySample>（不存在原地"回填"路径）
- 调用方 (W2 chart 组件 / Tier2 detail screen) **MUST NOT** 期望 `accelerationG` 为 `var` 或可变引用

`LapTelemetry` MUST 含字段：
- `sessionId: String`
- `lapIndex: Int`（zero-based）
- `lapStartWallClock: Long`
- `lapEndWallClock: Long`
- `lapDurationMs: Long`（== lapEndWallClock - lapStartWallClock）
- `samples: List<LapTelemetrySample>`（按 absoluteTsMs 升序）
- `sectorBoundaries: List<Long>`（首项 MUST == lapStartWallClock）
- `trackId: String?`
- `trackNameSnapshot: String?`

`PerformanceTelemetry` MUST 含字段：
- `testId: String`
- `testStartWallClock: Long`
- `testEndWallClock: Long`
- `samples: List<LapTelemetrySample>`（复用 LapTelemetrySample，按 absoluteTsMs 升序）

#### Scenario: LapTelemetrySample 字段全包含
- **WHEN** 通过 reflection 检查 `LapTelemetrySample` data class
- **THEN** 包含上述 7 个字段；`accelerationG` 字段 type 为 `Double?`，default 值为 null

#### Scenario: LapTelemetry sectorBoundaries 首项不变性
- **WHEN** `getLapTelemetry(sessionId, lapIndex)` 返回非 null 的 LapTelemetry
- **THEN** `result.sectorBoundaries.size >= 1` 且 `result.sectorBoundaries.first() == result.lapStartWallClock`

#### Scenario: 反例——accelerationG 字段不得 default 非 null 值
- **WHEN** 检查 `LapTelemetry.kt` 源码 `accelerationG` 字段的 default 值
- **THEN** default 必须是 `null`（**MUST NOT** 写 `accelerationG: Double = 0.0` 或类似非 null default）；W3 round 落地时显式回填，本 round 不预言派生策略

#### Scenario: 反例——LapTelemetrySample 字段不得为 var
- **WHEN** 检查 `LapTelemetrySample` data class 字段声明 + 通过 reflection 检查 Kotlin property mutability
- **THEN** 所有 8 个字段（absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG / flags）MUST 全部为 `val`，**MUST NOT** 出现 `var accelerationG` 或任何 `var` 字段；future round 派生 accelerationG 时必须走 `samples.map { it.copy(accelerationG = derived) }` 不可变路径，与本 round 锁定 immutable 契约一致

#### Scenario: PerformanceTelemetry 复用 LapTelemetrySample 类型
- **WHEN** 检查 `PerformanceTelemetry.samples` 类型
- **THEN** 类型是 `List<LapTelemetrySample>`（**不**新建 `PerformanceSample` 类型；统一 sample 形态让 W2 chart 组件可以同 Composable 复用渲染 lap 与 test 场景）

---

### Requirement: 跨时钟域 fallback 安全（NULL wallClock 不偷偷 fallback 到协议时间）

`getLapTelemetry` 实现 SHALL **MUST NOT** 在 `crossing.crossingWallClockTimestampMs == null` 时 fallback 到 `crossing.crossingTimestampMs`（GPS 协议时间）截 lap 窗口；fallback 必须显式返回 null，让调用方走"暂无该圈数据"空态。

理由（§8.3 spec 已锁定）：
- `crossingTimestampMs` 是 GPS 协议时间（mod 3,600,000 解码 + hourStartMillis 切换的 ms）
- `crossingWallClockTimestampMs` 是接收侧真壁钟（与 binary samples absoluteTs 同源）
- 两者**不同时钟域**：将 `crossingTimestampMs` 作为 `lapStart/lapEnd` 传给 `readLapSamples` 必命中 0 帧（窗口与 absoluteTs 不重叠），让调用方误判"该圈数据空"

实现层 grep gate（防回退，强 pattern）：

> ⚠️ L2 review (Opus 双线) 修订：原稿 pattern `crossing(\w+)?\.crossingWallClockTimestampMs \?:` 有两个问题——(1) 同时命中 `sortedBy { ?: Long.MAX_VALUE }` 内的 fallback，导致命中数 = 3 而非声明的 ">= 2"，spec line 183 "恰好 2 行" 自相矛盾；(2) `\w` 不是 POSIX ERE 标准，在 macOS BSD grep / ugrep 下 0 命中。已收紧为 `crossingWallClockTimestampMs \?: return null` 锁死 Elvis early return 形态（POSIX ERE 兼容 + 命中恰好 2 次），并显式声明测试代码 case J 的 Kotlin Regex `\s*\?\:\s*return\s+null` 是 effective gate（CLI grep 仅作辅助参考）。

- **gate-A（恰好命中 + 位置约束）**：`grep -nE 'crossingWallClockTimestampMs \?: return null' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` MUST 命中 **恰好 2 次**（`getLapTelemetry` 内 lapStart + lapEnd 两次 Elvis early return，不允许 != 2 次）。effective verifier = 测试 case J(d) 的 Kotlin Regex `crossingWallClockTimestampMs\s*\?\:\s*return\s+null`（同语义但宽容任意空白）
- **gate-B（函数体内反例锚点）**：在 `TelemetryRepository.kt` 的 `getLapTelemetry` 函数 block 内（从 `suspend fun getLapTelemetry` 行开始到 `}` 结束），**MUST NOT** 出现裸 `\.crossingTimestampMs\b`（不带 WallClock 后缀）—— 即本 round `getLapTelemetry` 实现 0 引用 GPS 协议时间字段，仅引用 wallClock 字段
- gate-A + gate-B 双层防回退，避免 single-line `.*` 形态在跨行误用时漏 catch；测试断言形态 = `assertEquals(2, elvisCount)` + `assertEquals(0, protocolTsCountInBlock)`

#### Scenario: 旧 row 全 null wallClock 反例
- **WHEN** session 含 5 条 accepted SF crossing 全部 `crossingWallClockTimestampMs = null`，但 `crossingTimestampMs` 都非空（旧 row migration 前模拟），调用 `getLapTelemetry(sessionId, 0)`
- **THEN** 返回 null；调用 `getLapTelemetry(sessionId, 1)` / `(sessionId, 2)` 任意 lapIndex 均返回 null

#### Scenario: 反例——若实现误回退到 crossingTimestampMs，readLapSamples 必 0 命中
- **WHEN** 假设 `getLapTelemetry` 错误实现成 `lapStart = crossing.crossingWallClockTimestampMs ?: crossing.crossingTimestampMs`，且 binary samples wallClock 域 = [1700000000000, 1700001000000]，crossingTimestampMs 域 = [3600000, 3700000]（mod 3,600,000）
- **THEN** `readLapSamples(file, 3600000, 3700000)` 命中 0 帧；最终 `LapTelemetry.samples = emptyList()` 但 lapStart/lapEnd 有值——**这是必须避免的语义错乱**，spec 锁定本 round MUST NOT 实现成此形态

#### Scenario: gate-A——恰好 2 次 Elvis early return 命中
- **WHEN** apply 完成后跑 `grep -nE 'crossingWallClockTimestampMs \?: return null' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`（POSIX ERE 兼容；effective verifier 是测试 case J(d) 的 Kotlin Regex）
- **THEN** 恰好 2 行命中（`getLapTelemetry` 内 lapStart + lapEnd 两次 Elvis early return）；< 2 次说明实现绕开 wallClock 校验、> 2 次说明引入额外冗余分支

#### Scenario: gate-B——getLapTelemetry 函数 block 内 0 引用裸 crossingTimestampMs
- **WHEN** apply 完成后从 `TelemetryRepository.kt` 中提取 `getLapTelemetry` 函数 block（**MUST** 用栈匹配 `{` / `}` 算法定位函数末尾，避免朴素"找下一个 `}`" 在嵌套 `withContext { }` / `runCatching { }` / `.map { lambda }` 时提前截断 —— 算法见 tasks 5.2 case J(b) verbatim 实现）+ 跑 Regex `\.crossingTimestampMs\b`（不带 WallClock 后缀的裸 GPS 协议时间字段）
- **THEN** 0 行命中（防 future 误回退到 GPS 协议时间字段）

---

### Requirement: 反向单向依赖契约（TelemetryRepository 不引用 TestResult 系列实质符号）

`TelemetryRepository` SHALL **MUST NOT** 在源码 import 块、字段声明、方法签名、方法体（函数代码）中引用 `TestResultRepository`、`TestRecordDao`、`TestRecordEntity`、`TestResultSummary` 任何符号；本 round D1 决策使依赖图为单向反向（`TestResultRepository → TelemetryRepository.readPerformanceSamples`），无循环。

**已知合法豁免（baseline KDoc cross-link）**：baseline `TelemetryRepository.kt:229` 的 `deleteSession` 方法 KDoc 注释中有 `[TestResultRepository.deleteResult]` 文档交叉引用（add-history-deletion round 引入），属合法 documentation cross-link，**不**构成实质依赖。本 round 0 改动该 KDoc 注释，grep gate MUST 排除 KDoc 注释行（`^\s*\*` 前缀）以避免 trivially fail。

理由：
- 本 round D1 alternatives 拒绝 A1（TelemetryRepository 加 TestResultRepository 依赖）的核心理由是"避免循环 + 既有 5+ test 0 改动 + PERFORMANCE_TEST 真相源 mental model"
- future round 若误把 TestRecord 相关方法 / 字段 / import 加到 TelemetryRepository，会让循环依赖风险复活

#### Scenario: TelemetryRepository.kt 实质代码 0 引用 TestResult 系列符号（排除 KDoc）
- **WHEN** apply 完成后跑 `grep -nE '^\s*(import |private val |val |var |fun |class |suspend fun ).*\b(TestResultRepository|TestRecordDao|TestRecordEntity|TestResultSummary)\b' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`
- **THEN** 返回 0 行命中（仅扫 import / 字段 / 方法签名 / class 头，KDoc 注释行被前缀 `^\s*\*` 排除——已 verify baseline KDoc cross-link 命中 1 次但走的是 `^\s*\*` 前缀，不被本 grep 扫到）

#### Scenario: TestResultRepository 单向依赖 TelemetryRepository.readPerformanceSamples
- **WHEN** 检查 `TestResultRepository.kt` 构造函数参数列表
- **THEN** 参数列表包含 `private val telemetryRepository: TelemetryRepository`；`getDataPointsForResult` 实现仅调用 `telemetryRepository.readPerformanceSamples(...)`，**不**调用 `telemetryRepository.startSession/endSession/writeSample/writeCrossing/getSession/getCrossings/getLapTelemetry/readLapSamples`（裸 readLapSamples 也禁用，仅 readPerformanceSamples 例外）等 mutable session state 路径

#### Scenario: 反例——若 TelemetryRepository 加 TestRecord 实质引用，gate 必须 fail
- **WHEN** 假设 future round 在 `TelemetryRepository.kt` 字段区加 `private val testRecordDao: TestRecordDao` 或 import 区加 `import com.blazepush.core.data.local.dao.TestRecordDao`
- **THEN** 上述 gate 命中 1 次以上 → 测试 fail；apply tasks §1 锁定该 gate 必须为 0 命中
