## Why

**问题溯源**：Phase 0（数据层闭合）已让 binary samples 的 `absoluteTs` 与 `crossingWallClockTimestampMs` 都走接收侧真壁钟、与 `entity.startTs` 同源。但目前消费方（详情屏 / 多圈对比 / Records PERFORMANCE SpeedCurve）仍各自现拼读 binary：

- `LapSessionDetailScreen.deriveDetailMetrics`（`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:471-515`）每次 session 切换全量 `readPerformanceSamples` 派生 top speed / 累计距离 → C round 已用 `entity.topSpeedKmh` 顶掉一半，但**单圈 telemetry 切片**（cursor 拖动需要的 `(elapsedMsInLap, speedKmh, lat, lon, bearing)` 序列）仍未抽出
- Records PERFORMANCE 子 tab 的 SpeedCurve 仍是 `SpeedCurveStub` 解析函数硬编码（参 `docs/design/speed-curve-real-data-persistence-deferred.md` §1）—— G round 落地了 `TestRecordEntity.dataFilePath` 持久化但**没引入统一 reader API**，Records UI 调用方还得自己拼 `readPerformanceSamples + 100km/h 找点 + 包成画图 model`
- Phase 1 的 4 个并行 round（W1 / W2 chart 组件 / W3 多圈对齐 / W4 wire-laptime）都需要消费同一份"单圈完整切片"——若各自拼装，会出现"chart 组件吃 List<Pair<Long, Double>>、对齐算法吃 List<Triple<Lat, Lon, Speed>>、Records 吃 PerformanceTelemetry 又一个 model"的多个不兼容形态

**当前 baseline**：
- `TelemetryRepository.readLapSamples(filePath, lapStartTs, lapEndTs)` 已返回 `List<TelemetrySample>`（窗口内 samples，A round + §8.3 时钟域已对齐）
- `TelemetryRepository.readPerformanceSamples(filePath)` 已返回 `List<TelemetrySample>`（全帧顺序读，§8.4/M 锚点已对齐）
- `getCrossings(sessionId)` 已返回 `List<TelemetryCrossingEvent>`（含 nullable `crossingWallClockTimestampMs`）
- `TestRecordEntity.dataFilePath: String`（默认 `""`）+ `TestResult.dataFilePath` + `TestResultSummary.dataFilePath` 均已落地（G round）

**用户场景**：
- 详情屏 cursor 拖动看单圈速度曲线（陪练用户看自己最快的圈、判断哪个 sector 起步慢）
- 多圈比较屏看 3 圈在同空间位置的 elapsed time 差（PB 提升来源分析）
- Records PERFORMANCE 子 tab 真实 0-100 加速曲线（替代当前 `SpeedCurveStub` 假数据，参 deferred memo #5）

**现在做**：本 round 引入两个 high-level domain reader API + 三个 domain 数据契约类，作为 Phase 1 所有消费 round（W2 chart / W3 align / Tier2 detail-screen / Tier2 comparison-screen / Records PERFORMANCE）的**统一数据底座**。**合并 deferred memo #5**（speed-curve-real-data-persistence）—— 因为 `getDataPointsForResult(testId)` 与 `getLapTelemetry(sessionId, lapIndex)` 同根（都是从 binary samples + entity 元数据派生 domain telemetry 切片），统一 repository 数据契约比拆 2 round 更经济。

**自洽契约**：本 round 工件 self-contained，不依赖对话 context；引用的 `docs/design/phase-1-entry-data-contracts.md`（entry sketch）+ `docs/design/speed-curve-real-data-persistence-deferred.md`（memo #5）+ `docs/implementation-design/parallel-change-collab.md`（看板 §5 W1 行）三份本地文件都存在且 self-contained。

## What Changes

- **新 domain 数据契约（core/domain）**：
  - `LapTelemetry`：单圈完整 telemetry 切片（sessionId / lapIndex / lapStartWallClock / lapEndWallClock / lapDurationMs / samples / sectorBoundaries / trackId / trackNameSnapshot）
  - `LapTelemetrySample`：单帧 telemetry 样本（absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG）—— 同时复用为 PerformanceTelemetry 的 sample 类型，`elapsedMsInLap` 在 PERFORMANCE_TEST 场景下表示 `elapsedMsInTest`
  - `PerformanceTelemetry`：0-100 / 100-0 完整 dataPoints 切片（testId / testStartWallClock / testEndWallClock / samples）
- **新 repository 高层 API（core/data，按真相源分流）**：
  - `suspend fun TelemetryRepository.getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?` —— 真相源是 TelemetrySession + crossings + binary，留 `TelemetryRepository`（已有 sessionDao + crossingDao + readLapSamples 全部依赖；构造函数 0 改动）；内部组合 `getSession` + `getCrossings` + `readLapSamples`，按两个相邻 accepted StartFinish crossing 的 wallClock 截窗口
  - `suspend fun TestResultRepository.getDataPointsForResult(testId: String): PerformanceTelemetry?` —— 真相源是 TestRecord（`testId == TestRecordEntity.id` + `dataFilePath` 在 TestRecord 上），放 `TestResultRepository`；内部经 `testRecordDao.getTestRecordById` 反查 entity + 走注入的 `TelemetryRepository.readPerformanceSamples` 读 binary
- **TestResultRepository 构造函数追加 1 个依赖**：
  - 加 `private val telemetryRepository: TelemetryRepository`（仅消费 `readPerformanceSamples` 一个纯函数，**不**依赖 mutable session state）
  - 依赖图：`TestResultRepository` → `TelemetryRepository.readPerformanceSamples`（单向）；`TelemetryRepository` **不**反向依赖 `TestResultRepository`（已 verify baseline `TelemetryRepository` 0 引用 `TestResultRepository` 任何符号）—— 无循环
  - **既有 0 现有 unit test**（grep `core/data/src/test/.../TestResultRepository*` 无命中），新建 1 个 reader test 类 + fake `TelemetryRepository` setup 即可
- **行为约定（spec normative）**：
  - `getLapTelemetry`：旧 row 的 crossing wallClock = null → 返回 null（**MUST NOT** fallback 到 `crossingTimestampMs`，理由 §8.3 case C 锁死跨时钟域 readLapSamples 必 0 命中）；lapIndex 越界 → null；session 不存在 → null；binary 文件缺失 → null（不抛异常）
  - `getDataPointsForResult`：testId 不存在 → null；entity.dataFilePath = `""` → null；binary 文件缺失 → null（不抛异常）
  - `LapTelemetrySample.accelerationG: Double?` —— 本 round **不**填充（保 null），由 W3 多圈对齐算法或后续 round 派生填入；`bearingDeg`、`flags` 字段照 baseline `TelemetrySample` 透传
- **测试覆盖**（5 cases A/B/C/D/E + grep gate 防回退）—— 详 spec 与 tasks
- **MUST NOT**：
  - **MUST NOT** 把方法挪到 `TestResultRepository`（sketch §2 已锁定 `TelemetryRepository` 契约位置；W2/W3 mock 已对齐；移动会让并行 session 类型契约断裂）
  - **MUST NOT** 用 reactive `Flow<List<...>>` 签名（与 sketch §2 的 `suspend ... ?` 不一致；cursor 拖动场景 `LaunchedEffect(testId/lapIndex)` 重新 fetch 已足够，不需要 reactive 流；与 memo #5 §5.5 的 `Flow` 形态差异由本 proposal 锁定）
  - **MUST NOT** 改既有 `readLapSamples` / `readPerformanceSamples` / `getCrossings` / `getSession` 签名（追加方法策略，rebase 友好）

## Capabilities

### New Capabilities

- `lap-telemetry-readers`：domain-level 单圈与 PERFORMANCE_TEST 完整 telemetry 切片读取契约，定义 `LapTelemetry` / `LapTelemetrySample` / `PerformanceTelemetry` 数据形态、`getLapTelemetry` / `getDataPointsForResult` repository 方法行为、跨时钟域 fallback 与缺数据降级语义。本 capability 跟 `binary-telemetry-storage` 是不同抽象层（前者 domain reader API，后者 binary writer/reader hygiene），不与 A56 / §8.3 / §8.4 / M 的 spec delta 冲突。

### Modified Capabilities

（无 —— 本 round 不改 `binary-telemetry-storage` 既有 requirement，只是 consume）

## Impact

### 协议兼容性

无影响。本 round 不触碰 RaceChrono BLE 公共协议字段编码 / 解码任何路径。

### 受影响代码

- `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`：**新建**（约 ~50 行：3 个 data class + KDoc）
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`：**追加 1 方法**（约 ~30 行新增；既有方法 + 构造函数 0 行 diff）
  - 加 `suspend fun getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?`
- `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`：**追加 1 方法 + 构造函数加 1 依赖**（约 ~25 行新增）
  - 构造函数加 `private val telemetryRepository: TelemetryRepository`
  - 加 `suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry?`（内部 `testRecordDao.getTestRecordById(testId) → entity.dataFilePath → telemetryRepository.readPerformanceSamples(filePath)`）
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`：**修改 TestResultRepository 注册**（约 ~1 行）
  - `single { TestResultRepository(get(), get()) }` → `single { TestResultRepository(get(), get(), get()) }`（追加 `get<TelemetryRepository>()`，按 Koin 顺序紧跟 `single { TelemetryRepository(...) }` 之后注册）
  - **依赖图风险**：`TestResultRepository` → `TelemetryRepository.readPerformanceSamples` 单向依赖（仅消费纯函数，不依赖 mutable session state），`TelemetryRepository` 不反向引用 `TestResultRepository`（已 verify baseline 0 引用），无循环

### 受影响测试

- `core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt`：**新建**（约 ~250 行新增）
  - case A：正常单圈 `getLapTelemetry(s, 0)` → 返回 N 圈第 0 圈，samples count > 0 + sectorBoundaries 含起点 + duration > 0
  - case B：lapIndex 越界 → null
  - case C：session 不存在 → null
  - case D：binary 文件缺失 → null
  - case E：crossing wallClock 全 null（旧 row 模拟） → null（锁死跨时钟域 fallback 不偷偷做）
  - case F：`getDataPointsForResult(testId)` 正常路径 → 返回 PerformanceTelemetry samples count > 0
  - case G：testId 不存在 → null
  - case H：entity.dataFilePath = `""` → null
  - case I：binary 文件缺失（dataFilePath 指向不存在路径） → null
  - case J：测试代码 grep gate —— 验证生产代码 `TelemetryRepository.kt:getLapTelemetry` 内部用 `crossing.crossingWallClockTimestampMs ?:` null 判断 + 跨文件逃逸 grep gate（仅 TelemetryRepository.kt 出现 `getLapTelemetry` 实现，调用方暂为 0）

### 数据兼容性

- 不引入 schema migration（本 round 仅追加 reader API，0 schema 改动）
- 旧 row（crossing wallClock = null）：调用 `getLapTelemetry` 返回 null，UI 显示"暂无该圈数据"空态——与 §8.3 spec "调用方 MUST 显式判 null fallback" 语义一致
- 新 row（crossing wallClock 非空）：正常返回完整 LapTelemetry

### 真机验证

- 本 round 是数据层 reader API，**下游 UI 暂无消费**（W2 chart / Tier2 detail screen / Records PERFORMANCE 接入由后续 round 落地），**无端到端真机可见证据**（同 A round / §8.3 / §8.4 数据底座 round 模式）
- 功能正确性证据由单测覆盖（10 cases 含跨时钟域 fallback 反例 + 端到端 round trip）
- Phase 1 `lap-detail-screen-with-cursor` round 落地时统一做端到端真机验证

### 依赖与时序

- **必须依赖** §8.3 `fix-lap-crossing-clock-hygiene` 已合回归档（已满足，archive/2026-05-03，commit `43bbac4`）—— `crossingWallClockTimestampMs` 字段 + nullable 语义是本 round 的硬前提
- **必须依赖** A round `fix-lap-binary-ts-hygiene` 已合回归档（已满足，archive/2026-05-02，commit `599562e`）—— `readLapSamples` 时钟域对齐
- **必须依赖** §8.4 / M `fix-perftest-binary-ts-hygiene` 已合回（已满足，commit `76a2735`）—— `readPerformanceSamples` 锚点对齐
- **必须依赖** G round `redesign-performance-result-screen` 已合回归档（已满足，archive/2026-05-01）—— `TestRecordEntity.dataFilePath` 字段
- **不依赖** A56 (`unify-gps-telemetry-persistence`) 归档：用新 capability `lap-telemetry-readers`，与 A56 spec delta 平行存在

### 并行 round 隔离

当前 active Phase 1 round（W1/W2/W3/W4）见 `docs/implementation-design/parallel-change-collab.md` §5：
- W2 `chart-and-map-components` —— **消费本 round 的 `LapTelemetry` / `LapTelemetrySample` 类型契约**（基于 sketch §1 mock 数据开发，文件级 0 交叉：W2 改 `feature/test/.../ui/components/`）
- W3 `lap-comparison-time-align` —— **消费本 round 的 `LapTelemetry` 类型契约**（pure function 用 mock 跑测试，文件级 0 交叉：W3 改 `core/domain/.../usecase/`）
- W4 `wire-laptime-to-gps-filter` —— 与本 round 函数级 0 交叉（W4 改 `feature/test/.../viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming`，本 round 不动 ViewModel / TestSessionViewModel.kt）

**类型契约稳定性承诺**：本 round 的 `LapTelemetry` / `LapTelemetrySample` / `PerformanceTelemetry` / `getLapTelemetry` / `getDataPointsForResult` 签名**一旦 review 通过 + apply 完成就不再改动**。如果 W2/W3 mock 期间发现签名需调整 → 起 follow-up round 协商，**MUST NOT** 在本 round 内静默改签名导致 W2/W3 中断。

### 看板登记

启动 apply 时同步在 `docs/implementation-design/parallel-change-collab.md` §6 共享文件登记表登记 3 条 ongoing：
- `core/data/.../repository/TelemetryRepository.kt`：追加 1 个 reader 方法 `getLapTelemetry`（构造函数 0 改动）
- `core/data/.../repository/TestResultRepository.kt`：追加 1 个 reader 方法 `getDataPointsForResult` + 构造函数加 `TelemetryRepository` 依赖
- `feature/test/.../di/AppModule.kt`：TestResultRepository single 注册加 `get<TelemetryRepository>()` 参数（line 89）
