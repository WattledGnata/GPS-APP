## MODIFIED Requirements

### Requirement: PERFORMANCE_TEST 完整 dataPoints 切片读取（getDataPointsForResult）

`TestResultRepository` SHALL 暴露 `suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry?` 高层 reader 方法，组合 TestRecord metadata 与 binary samples 顺序读，返回 PERFORMANCE_TEST 完整 dataPoints 切片或 null。

实现 MUST 满足：

- 通过 `testRecordDao.getTestRecordById(testId)` 拿 entity；entity 不存在 → 返回 null
- entity 存在但 `dataFilePath` 为空字符串（baseline default `""`）→ 返回 null
- **entity 存在但 `entity.timestamp == Long.MIN_VALUE`（GPS 未同步 sentinel）→ 返回 null（MUST 在 `readPerformanceSamples` 调用之前阻断）**。理由：`entity.timestamp` 是 PERFORMANCE_TEST 起点 wallClock（GPS 协议时间，`RaceChronoParser` 未同步时写 `Long.MIN_VALUE`）；若为 sentinel 仍继续，则 `absoluteTsMs = testStartWallClock + sample.tsDeltaMs ≈ Long.MIN_VALUE`，chart 时间轴语义崩塌。writer 侧 trigger guard（`satellites>=6 + hdop<2.0`）已阻断 sentinel 数据进 binary，本 normative 是 reader 侧 defensive 第二道防线，防 future trigger guard 修改回归。
- 通过注入的 `telemetryRepository.readPerformanceSamples(dataFilePath)` 顺序读 binary samples；调用 **MUST** 包在 `runCatching { ... }.getOrDefault(emptyList())` 内防 IOException 抛出（与 `getLapTelemetry` 同款防护理由：baseline `PerformanceTestTelemetryReader.read` 不防 readFully 中途抛 EOFException）
- binary 文件不存在 / 读取异常 / readPerformanceSamples 返回 emptyList → 返回 null（**MUST** 把"empty samples"视为读取失败，避免 `PerformanceTelemetry.samples = emptyList` 但 `testStartWallClock/testEndWallClock` 有值的语义错乱）
- `PerformanceTelemetry.testStartWallClock = entity.timestamp`（PERFORMANCE_TEST 起点 wallClock，TestRecord 字段）
- `PerformanceTelemetry.testEndWallClock = entity.timestamp + (samples 最后一帧 tsDeltaMs ?: 0)`（用 binary 最后帧的 tsDeltaMs 派生）
- samples 列表中每个 LapTelemetrySample 派生：
  - `absoluteTsMs = testStartWallClock + sample.tsDeltaMs`
  - `elapsedMsInLap = sample.tsDeltaMs`（PERFORMANCE 场景下 elapsedMsInLap 语义即"测试中累计耗时"，等于 tsDeltaMs）
  - `lat`/`lon`/`speedKmh`/`bearingDeg`/`flags` 透传
  - `accelerationG = null`

**`absoluteTsMs` 跨时钟域对齐 invariant（normative，替换原「§8.4/M anchor 已对齐」unargued assertion）**：

`absoluteTsMs = testStartWallClock + sample.tsDeltaMs` 是跨时钟域加法（`testStartWallClock = entity.timestamp` 是 GPS 协议时间域；`sample.tsDeltaMs` 是接收侧本地 `System.currentTimeMillis()` delta 域）。该加法仅在以下三条 invariant 同时成立时语义"对齐"：

1. **`entity.timestamp != Long.MIN_VALUE`**（GPS 已同步）—— 本 round MUST 在 reader 侧用 sentinel guard 显式锁定（违反时返回 null，不让 catastrophic 值进 chart）
2. **GPS-UTC-本地壁钟差在容许范围内**——典型 ~50ms（实测 < 200ms）；chart x 轴 25Hz（40ms/帧）粒度下漂移 < 5 帧，无可见错乱。本 round **不**解决此条，作为 P3 backlog 透明声明（无实际渲染影响）
3. **session 开始 → binary 第一帧写入期间无 GPS 锁定状态切换**——同步→失锁→重同步会让 `hourStartMillis` 跳变。本 round **不**解决此条，作为 P3 backlog 透明声明（生产 trigger guard 前提下罕见）

本 round 仅锁定 invariant 1（catastrophic 场景）；invariant 2/3 的亚帧级漂移不在 scope（方案 A 决策，见 design Decision 1 拒绝方案 B/C 理由）。

#### Scenario: 正常 PERFORMANCE_TEST 读取
- **WHEN** TestRecord(`testId-001`, dataFilePath="/tmp/test.bin", timestamp=1700000000000) 存在 + binary 文件含 100 帧 samples（fixture sample 的 `flags` 字段写入非 0 值，如 7），调用 `getDataPointsForResult("testId-001")`
- **THEN** 返回非 null 的 PerformanceTelemetry；samples.size == 100；testStartWallClock == 1700000000000；samples[0].elapsedMsInLap >= 0；samples[i].absoluteTsMs == testStartWallClock + samples[i].tsDeltaMs；**samples.first().flags == 7**（透传 baseline `TelemetrySample.flags` 不变，锁定 normative "flags 透传"）

#### Scenario: testId 不存在返回 null
- **WHEN** 调用 `getDataPointsForResult("non-existent-test-id")`
- **THEN** 返回 null（不抛异常）

#### Scenario: 反例——entity.dataFilePath 为空字符串返回 null
- **WHEN** TestRecord entity 存在但 `dataFilePath = ""`（baseline default，未持久化 binary 路径），调用 `getDataPointsForResult(testId)`
- **THEN** 返回 null；**MUST NOT** 把空字符串当合法路径传给 `readPerformanceSamples`（避免 File("").exists() 等假性命中）

#### Scenario: 反例——entity.timestamp 为 sentinel（Long.MIN_VALUE）返回 null
- **WHEN** TestRecord entity 存在 + `dataFilePath` 指向一个**完全合法可读的 binary 文件**（含 100 帧 samples）但 `entity.timestamp == Long.MIN_VALUE`（GPS 未同步 sentinel，模拟 future trigger guard 回归），调用 `getDataPointsForResult(testId)`
- **THEN** 返回 null；避免 `absoluteTsMs ≈ Long.MIN_VALUE` 的样本流灌进 chart 让时间轴崩塌
- **AND** sentinel guard 在源码中 MUST 位于 `telemetryRepository.readPerformanceSamples` 调用**之前**（code-position invariant，由实现层 guard 行紧随 `dataFilePath.isEmpty()` 之后保证）
- **验证方式**（case L）：用真 fake DAO + 真 `TelemetryRepository`（非 mockk）注入一个**有效 binary 文件 + sentinel timestamp**，断言 `assertNull(result)`——无 guard 时该 binary 可读出 100 帧返回非 null，断言 null 即证明 guard 生效。注：本 round 测试沿用 `LapTelemetryReadersTest` 既有真 fake 模式（repo 是真实例，无法 mockk-verify 调用次数），故用"有效 binary 仍返回 null"的功能性断言替代调用次数断言，等价证明 guard 截断了正常读取路径

#### Scenario: binary 文件缺失返回 null
- **WHEN** TestRecord entity 存在 + dataFilePath 非空 + timestamp 合法（非 sentinel），但文件被外部删除
- **THEN** 返回 null（实现层 runCatching 兜底 IOException + 把 emptyList 视为读取失败，不抛异常）

#### Scenario: 反例——binary 文件存在但 0 samples 返回 null
- **WHEN** TestRecord entity 存在 + dataFilePath 指向真实文件 + timestamp 合法 + 文件含合法 header 但 sample 段 0 帧（writer 启动 + 立即 close 未写 sample），调用 `getDataPointsForResult(testId)`
- **THEN** 返回 null；**MUST NOT** 返回 `PerformanceTelemetry(samples = emptyList(), testStartWallClock = entity.timestamp, testEndWallClock = entity.timestamp + 0)`——避免 "samples 空但 wallClock 有值" 语义错乱让 UI 误显示空 chart
