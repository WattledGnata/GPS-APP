## Why

现有加减速测试（0-100 / 100-0）用 Gson JSON 文件存储原始 GPS 点阵，违反 A56 原则（拒收 JSON 高频点阵格式）；圈速测试完全缺失持久化。两套机制应统一为一套架构，内存上界维持常数级（≈13KB），并为后续 Records 页真实数据展示打好基础。

## What Changes

- **废弃** `TestDataFileStorage` 的 Gson JSON 方案（未上线，直接丢弃，不做迁移）
- **新增** `BinaryTelemetryWriter`：基于 Channel + IO 协程的二进制逐条追加写入引擎，统一被加减速与圈速复用
- **新增** `GpsBinaryFormat`：17 bytes/sample 固定编码格式，支持 10Hz / 25Hz / 50Hz 设备
- **新增** Room 表：`TelemetrySessionEntity`（session 元数据）+ `CrossingEventEntity`（过线事件，事务保障）
- **新增** Flush 策略：30 秒定时主触发 + lap 结束后 5 秒 settle 副触发 + session 结束立即 flush + 1000 帧安全兜底
- **新增** 两套查询适配器：性能测试（1 session = 1 chunk file，直接读取）、圈速（时间窗口 `[lapStartTs, lapEndTs]` 过滤）
- **不做**：chunk index 表（文件小，顺序读即可）、旧 JSON 迁移、A52/A53 reference lap / prediction

## Capabilities

### New Capabilities

- `binary-telemetry-storage`：统一 GPS 点阵持久化能力——二进制格式定义、Channel 写引擎、Flush 策略、Room session/crossing 表、及加减速与圈速的查询适配器

### Modified Capabilities

（无：现有 JSON 方案未上线，直接替换不产生 spec 级行为变更）

## Impact

**受影响模块**

| 模块 | 变更性质 |
|------|---------|
| `core/data` | 新增 `BinaryTelemetryWriter`、`GpsBinaryFormat`、Room 表（`TelemetrySessionEntity`、`CrossingEventEntity`）、`TelemetryRepository` |
| `core/domain` | 新增 `TelemetrySample`、`TelemetrySession`、`CrossingEvent` 领域模型 |
| `feature/test` | 加减速测试：`TestExecutionViewModel` 替换 JSON 存储调用；圈速：`LapTimingEngine` 输出接入 `BinaryTelemetryWriter` |

**废弃路径**

- `core/data/.../file/TestDataFileStorage.kt`（Gson JSON，直接删除或清空实现）

**协议兼容性**

本变更为纯本地存储层，不涉及 RaceChrono BLE 协议字段，协议兼容性不受影响。
