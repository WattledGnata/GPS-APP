## Context

**现状**

- 加减速测试（0-100 / 100-0）用 `TestDataFileStorage` 将原始 GPS 点阵序列化为 Gson JSON 文件，违反 A56（拒收 JSON 高频点阵格式）。该实现未上线，可直接废弃。
- 圈速测试无任何持久化，session 结束即丢失所有轨迹数据。
- 两套测试共用同一个数据流（`GpsDataRepository`），但存储层完全割裂或缺失。

**目标架构**

Room 存元数据 + 二进制文件存点阵。写引擎统一，查询适配器按测试类型分化。

## Goals / Non-Goals

**Goals:**
- 统一加减速测试与圈速测试的存储机制（相同写引擎，相同格式）
- 内存上界维持常数级（≈13KB working set，不随 session 时长线性增长）
- 过线事件（CrossingEvent）以 Room 事务方式单独落盘，不走 binary 流
- 为 Records 页、个人最佳查询提供可读的 session 元数据

**Non-Goals:**
- 旧 JSON 数据迁移（未上线，直接丢弃）
- Chunk index 表（文件小，顺序读足够）
- A52/A53 reference lap / prediction（只提供数据层基础）
- 云端同步 / 服务端上传

## Decisions

### D1：二进制固定编码（17 bytes/sample）

**选择**：自定义固定宽度二进制，不用 Protobuf / Parquet / JSON。

| 字段 | 类型 | 字节 | 说明 |
|------|------|------|------|
| `ts_delta_ms` | u32 | 4 | 距 session 起点毫秒差（最长约 13 小时不溢出） |
| `lat_i32` | i32 | 4 | 度 × 1e7 |
| `lon_i32` | i32 | 4 | 度 × 1e7 |
| `speed_u16` | u16 | 2 | km/h × 10（最大 6553.5 km/h，足够） |
| `bearing_u16` | u16 | 2 | 度 × 10；无效填 `0xFFFF` |
| `flags` | u8 | 1 | accuracy tier 等扩展位 |

**chunk header**：文件以固定 22-byte header 开头，保存 `version(1) + type(1) + sampleCount(4) + startTs(8) + endTs(8)`，样本区紧跟 header 顺序 append。无 footer。

**理由**：解析无需第三方库，格式对 Kotlin `ByteBuffer` 友好，可在单元测试中精确断言每一位。Protobuf 在 17 bytes 粒度下开销比例过高；JSON 明确违反 A56。

**内存上界**：25Hz × 30s = 750 samples × 17 bytes ≈ 12.75KB，常数级 buffer。

### D2：Channel + IO 协程写引擎

**选择**：`Channel<TelemetryCommand>` 生产者-消费者，消费侧单协程运行在 `Dispatchers.IO`，逐条追加写入，OS 缓冲层聚合 I/O。与 A18 `FileLogger` 同一模式。

```kotlin
sealed class TelemetryCommand {
    data class Append(val sample: TelemetrySample) : TelemetryCommand()
    data class Flush(val ack: CompletableDeferred<Unit>) : TelemetryCommand()
    data class Close(val ack: CompletableDeferred<Unit>) : TelemetryCommand()
}
```

Channel 容量为 1024，`onBufferOverflow = SUSPEND`。`write(sample)` 是 `suspend fun`，通过 `Append` 命令写入，并在背压时挂起；GPS 点不得静默丢弃。`flush()` 发送 `Flush` 命令并等待 ack；`close()` 发送 `Close` 命令并等待 ack。

**文件边界选择**：采用 **seek 回写 header** 方案（Option A）。文件格式为固定 22-byte header + N × 17 bytes sample，无 footer。open() 使用 RandomAccessFile 写初始 header（sampleCount=0），之后 seek 到 offset 22 顺序 append samples。flush/close 时仅 seek 到 position 0 回写 header 中的 sampleCount 和 endTs，写完后 seek 回文件尾继续 append。sample 写入路径本身始终是顺序 append，只有 flush/close 时才有一次 seek。

**崩溃恢复规则**：读取时先计算 `actualCount = floor((fileSize - 22) / 17)`，再取 `validCount = min(header.sampleCount, actualCount)`。若 `header.sampleCount > actualCount`，按 `actualCount` 截断（崩溃未 flush）；若 `header.sampleCount < actualCount`，按 header 读取，避免读取未确认 flush 的尾巴；尾部不足 17 bytes 的半条 sample 忽略。

**理由**：sample 写入路径逐条顺序 append；单线程消费无锁；OS 文件系统缓冲比手写 buffer 更可靠；Channel 背压自动处理突发写入。

**替代方案**：footer 方案已评估，但格式复杂度更高，已放弃；`MappedByteBuffer` — 需要 file descriptor 管理复杂，kill 时 dirty page 可能不 flush；`FileOutputStream.write(byteArray, append)` 同步调用 — 占用主线程。

### D3：双触发 Flush 策略

| 触发条件 | 延迟 | 来源 |
|----------|------|------|
| 定时滚动 | 每 30 秒 | `TickerFlow` 定时发送 `Flush` 信号 |
| lap 结束 settle | lap 结束后 5 秒 | `LapTimingEngine` 发出 `LapCompleted` 事件 |
| session 结束 | 立即 | `ViewModel.onCleared()` 或用户手动结束 |
| 安全兜底 | buffer ≥ 1000 帧 | 防止定时器失效导致内存无限增长 |

**理由**：中途断连丢 1-2 帧可接受（GPS 精度 ±0.1-0.2 km/h 本身比插值误差大），因此不做每帧 fsync；lap 结束 settle delay 是为了让最后几帧（含过线帧）先落盘。

### D4：CrossingEvent 走 Room 事务，不走 binary 流

**理由**：过线事件是计时精度的真相源（含插值精确时间戳），不允许丢失；Room 事务提供原子写入保证；binary 文件追加不提供事务语义。

完整过线事件（包括 rejected crossing）都持久化，用于 Records、debug 与 replay 场景。

### D5：查询模式

- **加减速测试**：1 session = 1 chunk file，直接读取整文件，反序列化为 `List<GpsTelemetrySample>`。
- **圈速**：按时间窗口 `[lapStartTs, lapEndTs]` 从顺序 chunk file 中过滤样本，chunk 不需要对齐 lap 边界（因为圈速 session 是一个连续 binary 文件，按 `ts_delta_ms` 重建绝对时间后 range scan 即可）。

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| session 中途崩溃导致最后一段 buffer 丢失（最多 29.9s 数据） | 对圈速分析可接受，读取时按 `actualCount = floor((fileSize - 22) / 17)` 与 header `sampleCount` 取较小值，并丢弃尾部不完整 sample |
| `ts_delta_ms u32` 约 49 天溢出 | session 最长数小时，无实际风险；如超出范围应报告 session 已损坏 |
| `bearing_u16 0xFFFF` 哨兵值与 65535.0/10 = 6553.5° 冲突 | bearing 有效值 0–359.9°，0xFFFF = 65535 完全超出范围，无歧义 |
| Channel 背压（生产速度 > 消费速度） | Channel 容量 1024，`onBufferOverflow = SUSPEND`；生产侧挂起等待，不静默丢点 |

## Migration Plan

1. 删除 `TestDataFileStorage.kt`（Gson JSON 实现）
2. 在 `core/data` 创建新的 `BinaryTelemetryWriter`、Room 表、`TelemetryRepository`
3. 在 `core/domain` 添加领域模型（`TelemetrySample`、`TelemetrySession`、`CrossingEvent`）
4. `feature/test` 接入新存储：加减速测试 ViewModel 替换 JSON 调用，圈速 ViewModel 新增持久化调用
5. 不需要数据迁移（旧 JSON 未上线）

**回滚**：本变更全为新增层，删除 `TestDataFileStorage` 后旧 JSON 路径不再存在；如需回滚，git revert 即可。

## Open Questions

- `flags` byte 的具体 bit 定义（accuracy tier 几档、是否含 `is_filtered` 标志）——暂定实现时再扩展，0x00 = normal sample。
- 圈速 binary 文件与加减速 binary 文件共用同一 `BinaryTelemetryWriter` 实现，还是两个独立实例？暂定：同一类，由 `TelemetrySession.type` 区分文件路径。
