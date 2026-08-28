## 1. 领域模型（core/domain）

- [x] 1.1 新增 `TelemetrySample` 数据类（ts_delta_ms, lat, lon, speed_kmh, bearing, flags）
- [x] 1.2 新增 `TelemetrySession` 领域模型（sessionId, sessionType enum, startTs, endTs, binaryFilePath）
- [x] 1.3 新增 `CrossingEvent` 领域模型（sessionId, lapIndex, crossingTimestampMs, speed_kmh）
- [x] 1.4 新增 `TelemetrySessionType` 枚举（PERFORMANCE_TEST, LAP_SESSION）

## 2. 二进制格式编解码（core/data）

- [x] 2.1 实现 `GpsBinaryFormat`：`encode(sample): ByteArray`（17 bytes），`decode(bytes, offset): TelemetrySample`
- [x] 2.2 实现 22-byte header 读写（含 `sampleCount` u32 + `startTs` u64 + `endTs` u64）；open 时写初始 header（`sampleCount=0`），flush/close 时 seek offset 0 回写 `sampleCount/endTs`，不写 footer
- [x] 2.3 新增单元测试 `GpsBinaryFormatTest`：验证编解码往返精度（lat/lon/speed/bearing，含 0xFFFF bearing 哨兵）

## 3. BinaryTelemetryWriter（core/data）

- [x] 3.1 实现 `BinaryTelemetryWriter`：`Channel<TelemetryCommand>` 输入（Append/Flush/Close + ack），Dispatchers.IO 单协程追加写入，容量 1024 且 `SUSPEND` 背压
- [x] 3.2 实现 `open(sessionId, sessionType, startTs)`：使用 `RandomAccessFile(file, "rw")` 创建文件，写 22-byte header（`sampleCount=0`），seek 到 offset 22 准备 append
- [x] 3.3 实现 `flush()`：发送 Flush command 并等待 ack；pending samples 写完后 seek(0) 回写 header `sampleCount/endTs`，force 刷新，再 seek 回文件尾，complete ack
- [x] 3.4 实现 `close()`：发送 Close command 并等待 ack；close 先执行同 flush 的 header 回写，再 close `RandomAccessFile`
- [x] 3.5 实现安全兜底：buffer 达到 1000 帧时强制 flush（不等待定时器）
- [x] 3.6 新增单元测试 `BinaryTelemetryWriterTest`
- [x] 3.6.1 测试：写 3 条 flush 后 header `sampleCount=3`，`endTs` = 最后一条，文件大小 73 bytes
- [x] 3.6.2 测试：flush 后继续写 2 条再 flush，header `sampleCount=5`，samples 顺序无污染
- [x] 3.6.3 测试：人工截断半条 sample，reader 忽略尾部半条
- [x] 3.6.4 测试：header count > actual count 时 reader 按 actual 截断

## 4. Flush 定时触发（core/data / feature/test）

- [x] 4.1 在 `BinaryTelemetryWriter` 内部实现 30 秒定时 flush（`TickerFlow` 或 `delay` loop，IO 协程内）
- [x] 4.2 在圈速 ViewModel 中：收到 `LapCompleted` 事件后，5 秒后触发 writer `flush()`
- [x] 4.3 新增测试：验证 1000 帧兜底在定时器未触发时正确 flush

## 5. Room 持久化层（core/data）

- [x] 5.1 新增 `TelemetrySessionEntity`（Room @Entity）及对应 DAO（insert, updateEndTs, queryBySessionId, queryAll）
- [x] 5.2 新增 `CrossingEventEntity`（Room @Entity：sessionId, lapIndex, crossingTimestampMs, speed_kmh, gateId, gateType, accepted, reason, directionScore）及对应 DAO（insertInTransaction, queryBySessionId）
- [x] 5.3 更新（或新建）`AppDatabase`：添加 `TelemetrySessionEntity`、`CrossingEventEntity` 表
- [x] 5.4 新增 `TelemetryRepository`：封装 DAO + writer，提供 `startSession()`、`writeSample()`、`writeCrossing()`、`endSession()` 接口
- [x] 5.5 新增单元测试 `TelemetryRepositoryTest`（Fake DAO + Fake Writer JVM 单测，替代 Robolectric）：验证 writeCrossing() 后立即可查询，含 startSession/endSession/writeSample 共 5 个测试

## 6. 点阵查询适配器（core/data）

- [x] 6.1 实现 `PerformanceTestTelemetryReader`：以 sessionId 读取 chunk file，返回 `List<TelemetrySample>`
- [x] 6.2 实现 `LapTelemetryReader`：以 `(sessionId, lapStartTs, lapEndTs)` 时间窗口过滤，返回 `List<TelemetrySample>`
- [x] 6.3 新增单元测试 `LapTelemetryReaderTest`：验证相邻圈时间窗口查询无越界、无遗漏（5 个测试：adjacent windows、boundary inclusive、no match、truncated file、missing file）

## 7. 接入加减速测试（feature/test）

- [x] 7.1 在 `TestExecutionViewModel`（或对应 UseCase）中替换 `TestDataFileStorage` JSON 调用，改为 `TelemetryRepository`
- [x] 7.2 测试开始时调用 `startSession(PERFORMANCE_TEST)`，每帧调用 `writeSample()`，测试结束时调用 `endSession()`
- [x] 7.3 迁移 `TestResultRepository.saveResult/deleteResult`：移除 `TestDataFileStorage` 依赖，改用 `TelemetryRepository`
- [x] 7.4 迁移 `TestResultScreen.loadDataPoints`：改用 telemetry reader 读取二进制点阵
- [x] 7.5 迁移 `AppModule` 注册/注入：移除 `TestDataFileStorage` binding，注册并注入 `TelemetryRepository`/reader
- [x] 7.6 确认零剩余引用：grep 全项目 `TestDataFileStorage`、`saveDataPoints`、`loadDataPoints`；此任务作为删除 gate
- [x] 7.7 删除 `TestDataFileStorage.kt`（已确认零残余引用后删除，编译通过）

## 8. 接入圈速测试（feature/test）

- [x] 8.1 在圈速 ViewModel 中，session 开始时调用 `startSession(LAP_SESSION)`
- [x] 8.2 每帧 GPS 样本调用 `writeSample()`（在 bridgeGpsToLapTiming 段3，tsDeltaMs = gpsTs - systemAnchorTs）
- [x] 8.3 每次过线事件调用 `writeCrossing(crossingEvent)`（比较 crossingEvents.size 检测新事件，事务写入 Room）
- [x] 8.4 session 结束（stopLapDebugSession / exitLapDebugMode）时调用 `endSession()`

## 9. Koin 依赖注入配置

- [x] 9.1 已由 7.5 覆盖：`TelemetryRepository` 以 `single { TelemetryRepository(androidContext(), get(), get()) }` 注册在 `AppModule.repositoryModule`
- [x] 9.2 已由 7.5 覆盖：`TestSessionViewModel` Koin 注册已添加第 9 个 `get()` 注入 `TelemetryRepository`；`telemetrySessionDao`/`crossingEventDao` 在 `databaseModule` 注册

## 代码 Review 状态

- **Codex Review**：🟢 通过（2026-04-29）
  - 已关闭：编译阻断（测试 helper 缺参数）、per-frame launch 竞态（inline suspend）、lapIndex 错位（lapIndexForCrossing）、孤儿文件清理
  - openspec validate --strict PASS
  - :feature:test:testDebugUnitTest PASS
  - :core:data:testDebugUnitTest PASS
- **存档条件**：10.1-10.3 真机验证通过 + commit hash 记录

## 10. 集成验证（Manual Gates）

- [ ] 10.1 **[manual gate]** 真机（华为 8KE0219522008434）：跑一次 0-100 测试，确认二进制文件落盘且可读取
- [ ] 10.2 **[manual gate]** 真机：跑一次圈速 session（含 2-3 圈），确认 CrossingEvent 写入 Room，binary 文件样本数正确
- [ ] 10.3 **[manual gate]** 验证 session 中途断开 BLE，重连后 writer 状态不崩溃，已 flush 数据完整
