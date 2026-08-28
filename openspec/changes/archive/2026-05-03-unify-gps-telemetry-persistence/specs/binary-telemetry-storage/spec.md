## ADDED Requirements

### Requirement: 二进制编码格式

系统 SHALL 以 17 bytes/sample 的固定宽度二进制格式编码 GPS 点阵样本：
- `ts_delta_ms`（u32, 4 bytes）：距 session 起点的毫秒差
- `lat_i32`（i32, 4 bytes）：纬度 × 1e7，四舍五入取整
- `lon_i32`（i32, 4 bytes）：经度 × 1e7，四舍五入取整
- `speed_u16`（u16, 2 bytes）：速度（km/h）× 10，四舍五入取整
- `bearing_u16`（u16, 2 bytes）：航向（度）× 10，无效值填 `0xFFFF`
- `flags`（u8, 1 byte）：扩展位，当前版本为 `0x00`

文件 SHALL 以固定 22-byte header 开头：`version`（u8, 1 byte）+ `type`（u8, 1 byte）+ `sampleCount`（u32, 4 bytes）+ `startTs`（u64, 8 bytes）+ `endTs`（u64, 8 bytes），大端序；紧接 N × 17 bytes samples；无 footer。

#### Scenario: 编码精度验证

- **WHEN** 输入 `lat=39.9042°`、`lon=116.4074°`、`speed=100.0 km/h`、`bearing=45.0°`
- **THEN** `lat_i32 = 399042000`、`lon_i32 = 1164074000`、`speed_u16 = 1000`、`bearing_u16 = 450`

#### Scenario: 无效 bearing 编码

- **WHEN** GPS 样本的 bearing 字段为 null 或不可用
- **THEN** `bearing_u16` 编码为 `0xFFFF`

### Requirement: BinaryTelemetryWriter 写引擎

系统 SHALL 提供 `BinaryTelemetryWriter`，以 `Channel<TelemetryCommand>` 为输入，在 `Dispatchers.IO` 单协程中通过 `RandomAccessFile` 或 `FileChannel` 逐条追加写入二进制文件。

`TelemetryCommand` SHALL 包含 `Append(sample)`、`Flush(ack: CompletableDeferred<Unit>)`、`Close(ack: CompletableDeferred<Unit>)` 三类命令。

写引擎 SHALL 支持 `open(sessionId, sessionType, startTs)`、`write(sample)`、`flush()`、`close()` 生命周期。

`write(sample)` SHALL 为 `suspend fun`，Channel 容量 SHALL 为 1024，`onBufferOverflow` SHALL 为 `SUSPEND`；当写入速度超过消费速度时 SHALL 挂起等待背压，不得静默丢弃 GPS 点。

`flush()` SHALL 发送 `Flush` 命令并等待 ack 完成后返回；`close()` SHALL 发送 `Close` 命令并等待 ack 完成后返回。

`open(sessionId, sessionType, startTs)` SHALL 写入完整 22-byte header，其中 `sampleCount=0`、`endTs=startTs`，并 seek 到 offset 22 准备 append samples。

`flush()` 与 `close()` SHALL 在 pending samples 写入完成后保存当前文件尾 position，seek 到 offset 0 回写 header 中的 `sampleCount` 与 `endTs`，force 刷新后 seek 回文件尾；`close()` SHALL 在完成 header 回写后关闭 channel/file。

#### Scenario: 高频写入通过背压保护数据完整性

- **WHEN** 以 25Hz 速率连续调用 `write(sample)` 持续 60 秒（共 1500 次）
- **THEN** IO 协程在后台完成写入；若 Channel 已满，`write(sample)` 挂起等待容量释放，且不丢弃任何 sample

#### Scenario: flush 后数据可读

- **WHEN** 调用 `flush()` 完成后
- **THEN** `flush()` 已收到 ack，文件中已写入的 samples 可被正确反序列化，数量与 `write()` 调用次数一致

#### Scenario: flush 后 header 与 sample 数一致

- **WHEN** 写入 3 条 sample 后调用 `flush()`
- **THEN** header 中 `sampleCount = 3`，`endTs` 等于第 3 条 sample 的 timestamp，文件大小为 22 + 3×17 = 73 bytes

#### Scenario: 多次 flush 后 samples 顺序无污染

- **WHEN** flush 后继续写入 2 条 sample，再次 flush
- **THEN** header 中 `sampleCount = 5`；读取 samples 顺序与写入顺序一致，无重复或空洞

#### Scenario: 崩溃截断半条 sample 时 reader 忽略尾部

- **WHEN** 文件末尾有 < 17 bytes 的不完整 sample（模拟崩溃）
- **THEN** reader 以 `floor((fileSize - 22) / 17)` 计算实际可读 sample 数，忽略不完整尾部，不报错

#### Scenario: header count 大于实际 sample 数时按实际截断

- **WHEN** `header.sampleCount = 10`，但文件实际只有 5 条完整 sample（模拟崩溃未 flush header）
- **THEN** reader 以 `actualCount = 5` 为准，返回 5 条 sample，不越界读取

### Requirement: Flush 策略

系统 SHALL 执行以下 flush 触发规则：
1. **定时触发**：session 进行中每 30 秒自动 flush 一次
2. **lap 结束触发**：收到 `LapCompleted` 事件后延迟 5 秒 flush（settle delay）
3. **session 结束触发**：session 结束时立即 flush 全部残余 buffer
4. **安全兜底**：buffer 中未 flush 样本数达到 1000 帧时强制 flush

#### Scenario: 定时触发不丢失数据

- **WHEN** 25Hz 采样持续 35 秒，期间发生一次 30 秒定时 flush
- **THEN** 30 秒 flush 后文件包含 ≥750 个 samples；后续 5 秒数据在下次 flush 后追加写入

#### Scenario: 安全兜底防止内存失控

- **WHEN** buffer 中未 flush 样本数达到 1000
- **THEN** 立即触发 flush，不等待定时器到期

#### Scenario: session 结束无残余丢失

- **WHEN** 调用 `close()` 结束 session
- **THEN** close 完成后所有已 write 的 samples 均已持久化到文件

### Requirement: CrossingEvent 事务写入

系统 SHALL 将过线事件（`CrossingEvent`）写入 Room 数据库，使用数据库事务保障原子性，不走 binary 文件流。

`CrossingEvent` SHALL 包含：`sessionId`、`lapIndex`、`crossingTimestampMs`（插值精确时间）、`speed_kmh`。

`CrossingEventEntity` SHALL 持久化完整事件表（包括 rejected crossing），字段包含：`sessionId`、`lapIndex`、`crossingTimestampMs`、`speed_kmh`、`gateId`、`gateType`、`accepted`、`reason`（String/enum）、`directionScore`（Double）。`sampleIndex` 与 `directionalSpeedMps` SHALL 故意省略，因为可由 binary 文件派生。

#### Scenario: 过线事件在 session 结束后可查询

- **WHEN** session 中发生 3 次过线事件并分别写入 Room
- **THEN** 以 `sessionId` 查询可返回全部 3 条 `CrossingEvent`，时间戳与写入值一致

#### Scenario: 过线事件不依赖 binary flush

- **WHEN** binary 文件尚未 flush（buffer 中有未持久化 samples）
- **THEN** CrossingEvent 写入 Room 后立即可查询，不受 binary flush 状态影响

### Requirement: TelemetrySession 元数据

系统 SHALL 在 Room 中为每个 session 维护 `TelemetrySessionEntity`，包含：
`sessionId`（UUID）、`sessionType`（PERFORMANCE_TEST / LAP_SESSION）、`startTs`（ms）、`endTs`（ms，session 结束后更新）、`binaryFilePath`（chunk file 绝对路径）、`lapCount`（圈速 session）、`bestLapMs`（圈速 session，可 null）。

#### Scenario: session 结束后元数据完整

- **WHEN** 一次加减速 session 结束，含 startTs=T0、endTs=T1
- **THEN** Room 中对应 `TelemetrySessionEntity` 的 `endTs = T1`，`binaryFilePath` 指向已存在的文件

### Requirement: 加减速测试点阵查询

系统 SHALL 支持以 `sessionId` 读取加减速测试的全部 GPS 点阵，返回 `List<TelemetrySample>`，顺序与记录顺序一致。

#### Scenario: 读取加减速测试点阵

- **WHEN** 以 sessionId 查询一次已完成的 0-100 session
- **THEN** 返回的 `List<TelemetrySample>` 不为空，首尾时间戳覆盖测试时间窗口

### Requirement: 圈速点阵时间窗口查询

系统 SHALL 支持以 `sessionId + lapStartTs + lapEndTs` 过滤 binary 文件中属于该圈的样本，返回 `List<TelemetrySample>`。

#### Scenario: 圈速点阵时间窗口过滤

- **WHEN** lap 时间窗口为 `[T_start, T_end]`，binary 文件含跨越多圈的连续样本
- **THEN** 返回的样本集合中所有 `absoluteTs`（`sessionStartTs + ts_delta_ms`）均在 `[T_start, T_end]` 范围内

#### Scenario: 相邻圈数据不越界

- **WHEN** 相邻两圈的 lapStartTs/lapEndTs 无重叠
- **THEN** 两次查询返回的样本集合不包含相同的样本（无重叠、无遗漏）
