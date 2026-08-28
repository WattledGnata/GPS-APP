## ADDED Requirements

### Requirement: 过线事件采样时间字段双时钟域 hygiene

系统 SHALL 在 Room 持久化层（domain DTO `TelemetryCrossingEvent` + Room schema `CrossingEventEntity`）同时持有两个时间字段：

- `crossingTimestampMs: Long`：GPS 协议时间（来自 `LapTimingEngine.processSample` 输入的 `sample.timestampMillis = GpsData.timestamp`）。语义不变，供 UI 显示（HH:mm:ss）+ lap 时长减法等同时钟域内运算使用
- `crossingWallClockTimestampMs: Long?`（**nullable**）：接收侧真壁钟（`System.currentTimeMillis()`），在过线事件触发的同一 ViewModel 协程上下文内立即取值。与 binary samples 的 `absoluteTs = header.startTs + ts_delta_ms` 同时钟域，供 `readLapSamples(file, lapStartTs, lapEndTs)` 的 per-lap / sector segment 窗口截取使用。**旧数据（v4→v5 migration 之前的 row）该字段为 null，调用方 MUST 显式判 null fallback 到全 session 路径**

**架构边界**（v3 review 修订）：仅 Room 持久化层加 wallClock 字段；`feature/test/.../laptiming/CrossingEvent.kt`（in-memory pure domain object，由 `LapTimingEngine.processSample` 纯函数产出）**不在本 requirement 约束范围内**——它没有 wallClock 注入路径，且其下游消费方（LapDebugExecutionScreen 实时面板等）都在同一活跃帧/同一活跃小时段内做协议时间减法，跨时钟域问题不暴露。

写入侧 SHALL 在 `TestSessionViewModel` 构造 `TelemetryCrossingEvent` 时立即取 `System.currentTimeMillis()` 作为 `crossingWallClockTimestampMs`，不得在 `repository.writeCrossing` 内部或其他异步路径取值（避免引入 binary writer queue 延迟到 wallClock 上）。

Room schema SHALL 通过 v4 → v5 migration `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER` 引入新列（**nullable，无 NOT NULL 约束**，旧 row 自动 NULL）。

#### Scenario: 双时钟域字段 round trip 映射不漏（本 round 单测覆盖）

- **WHEN** 测试代码手工构造 `event = TelemetryCrossingEvent(crossingTimestampMs = X, crossingWallClockTimestampMs = Y, ...)` 注入精确值，调用 `repository.writeCrossing(event)` 后立即 `repository.getCrossings(sessionId)` 拉取
- **THEN** 返回的 `TelemetryCrossingEvent`：
  1. `crossingTimestampMs == X`（精确等，验证写入路径不污染该字段）
  2. `crossingWallClockTimestampMs != null`（验证 `TelemetryRepository.toDomain()` 映射不漏 wallClock 字段；Kotlin 单测应用 `assertNotNull(...)` 显式断言，**不**用 `?.let { ... }` 否则 null 时 assertion 静默 skip 不 fail）
  3. `crossingWallClockTimestampMs == Y`（精确等；测试场景下 wallClock 是手工注入值，应精确等而非 ±100ms 容差）

#### Scenario: 生产路径 ViewModel scope 取 wallClock vs binary writer 真壁钟差 < 100ms（生产契约，**不在本 round 单测覆盖**）

- **WHEN** 生产代码 ViewModel scope（Main dispatcher）构造 `TelemetryCrossingEvent` 取 `currentTimeMillis()` 瞬间，与 `BinaryTelemetryWriter` IO channel queue 实际 flush 该时刻 sample 的真壁钟相比
- **THEN** 漂移 < 100ms（生产环境不变式）；该断言由真机 sanity check + logcat 抽检验证（同 A round 模式），**不在本 round 单测覆盖**（单测无 ViewModel scope + IO channel queue 真实环境）。如未来发现 100ms 不够，需独立 round 改用 elapsedRealtime 或 binary writer 内部记录 wallClock

#### Scenario: per-lap segment readLapSamples 用 wallClock 窗口命中

- **WHEN** 某 LAP_SESSION 写入 N 帧 binary samples（覆盖时间 `[T1, T1 + 4000ms]`），并写入 2 个 crossing：第 1 个 `crossingWallClockTimestampMs = T1 + 1000`，第 2 个 `crossingWallClockTimestampMs = T1 + 3000`
- **THEN** 调用 `repository.readLapSamples(filePath, T1 + 1000, T1 + 3000)` 返回中间 50±1 帧样本，所有样本 `absoluteTs` 落在 `[T1 + 1000, T1 + 3000]` 闭区间内（参照 `LapTelemetryReader.kt:39` 的 `absoluteTs in lapStartTs..lapEndTs` Kotlin range 闭区间语义）

#### Scenario: 协议时间 crossing 窗口的反例锁死（极端偏差 0 命中）

- **WHEN** 某 LAP_SESSION 的 binary samples absoluteTs 在真壁钟时钟域 `[T1, T1+4000]`，写入 2 个 crossing：`wallClock` 在 `[T1+1000, T1+3000]` 中间正确位置，但 `crossingTimestampMs`（GPS 协议时间）跟接收侧真壁钟偏差极大（如 +1_000_000_000ms ~ 16 分钟，模拟跨小时切换 / simulator 重启时的协议时间跳变）
- **THEN** 调用 `repository.readLapSamples(filePath, crossings[0].crossingTimestampMs, crossings[1].crossingTimestampMs)` 返回 **0 帧**（窗口 `[T1+~16min, T1+~16min+2s]` 跟 binary samples `[T1, T1+4s]` 完全无交集），证明跨时钟域窗口截取的反例语义被锁死

#### Scenario: 协议时间 crossing 窗口的反例锁死（小偏差错位命中）

- **WHEN** 同上 LAP_SESSION，但 crossing 的 `crossingTimestampMs` 跟真壁钟偏差仅 +1500ms（典型 GPS clock skew 量级）：`crossingTimestampMs` = `crossingWallClockTimestampMs + 1500`
- **THEN** 调用两次 readLapSamples 对比：
  - `repository.readLapSamples(filePath, crossings[0].crossingWallClockTimestampMs, crossings[1].crossingWallClockTimestampMs)` 返回 50±1 帧样本（正确截取）
  - `repository.readLapSamples(filePath, crossings[0].crossingTimestampMs, crossings[1].crossingTimestampMs)` 返回的样本集合**不等于** wallClock 窗口的样本集合（数量差非 0；典型情况是窗口偏移 1500ms 后命中错误的样本子集，比如截到了下一圈起始 1.5s 的帧）
- 这是真正的 silent failure 模式：小偏差不会让窗口空，但**会命中错误样本**，per-lap UI 看起来"有数据"但是错的——必须用 wallClock 才命中正确样本子集

#### Scenario: 写入路径 grep gate（防止改错位置）

- **WHEN** 在 `feature/test/.../viewmodel/TestSessionViewModel.kt` grep `crossingWallClockTimestampMs\s*=\s*System\.currentTimeMillis\(\)`
- **THEN** 命中**恰好 1 次**，且行号位于 `telemetryRepository.writeCrossing(` 调用上方 ≤30 行（保证写入路径就在 LAP_SESSION 过线事件锚点位置，未被错位插入到 PERFORMANCE_TEST 路径或其他无关位置）

#### Scenario: 跨文件逃逸 grep gate（防止字段误用扩散）

- **WHEN** 在 `feature/test/src/main/`（**仅 main，排除 src/test/ 测试目录**避免 case 自身字符串 trip）grep `crossingWallClockTimestampMs`
- **THEN** 命中文件**仅 1 个**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（写入瞬间取值 + 构造 DTO）。明示禁止命中清单（任一命中即测试 fail）：
  - `feature/test/src/main/.../viewmodel/` 包内除 `TestSessionViewModel.kt` 外的其他文件（防 UiState / state holder 误透传）
  - `feature/test/src/main/.../ui/screen/LapDebugExecutionScreen.kt`（HH:mm:ss + lap 时长减法应仍用 `crossingTimestampMs` 协议时间）
  - `feature/test/src/main/.../ui/tracktech/LapSessionDetailScreen.kt`（detail 屏 quick fix 不消费 wallClock）
  - 其他 UI screen 文件（防字段误显示）
- 本 round 不引入 per-lap UI 消费方；未来 UI round 引入消费时单独立项放宽 grep gate 范围（在该 round spec 内显式列消费方文件清单）

#### Scenario: Room migration v4→v5 SQL 自检（本 round 实施）

- **WHEN** 检查 `AppDatabase.migration4To5Sql` 字符串列表
- **THEN** 列表恰好 1 条语句 `ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER`（**nullable，无 NOT NULL 约束**）；`AppDatabase.migration4To5` Migration 对象 startVersion=4 + endVersion=5；migration 对象在 `AppModule.databaseModule` 的 `addMigrations(...)` 中注册

#### Scenario: Room migration v4→v5 真实 row 自动化验证（**deferred to follow-up `room-test-infrastructure` round**）

- 真实 v4 schema 数据库 upgrade 到 v5 后旧 row 的 `crossingWallClockTimestampMs` 字段为 NULL + 不崩溃 + 新数据 wallClock 写入成功——这一项需要 `androidx.room:room-testing` MigrationTestHelper + Robolectric，本 round 设计 §5 决策不引入这些库（继承 C round v3→v4 同款延期项）；spec 明示推到 `room-test-infrastructure` follow-up round 验证，不算本 round 验收

#### Scenario: 写入路径在同协程上下文内取 wallClock（grep gate）

- **WHEN** 在 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` grep 构造 `TelemetryCrossingEvent` 的代码块
- **THEN** `crossingWallClockTimestampMs` 字段赋值表达式形如 `System.currentTimeMillis()`（同步调用，与 `crossing.timestampMillis` 在同一构造表达式内同时刻取值），不得通过 `viewModelScope.launch` / `delay` / `withContext(Dispatchers.IO)` 等异步路径间接计算
