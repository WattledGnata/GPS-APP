## ADDED Requirements

### Requirement: 采样时间字段时钟域 hygiene 与 anchor 同源

系统 SHALL 保证 binary telemetry 文件中所有时间字段属于同一时钟域（接收侧真壁钟，即 `System.currentTimeMillis()`），且 sample 的 `ts_delta_ms` anchor SHALL 严格等于 `header.startTs`：

- `header.startTs`：session 开始时刻的接收侧真壁钟（由 `TelemetryRepository.startSession()` 内部 `System.currentTimeMillis()` 生成）
- `header.endTs`：session 结束时刻的接收侧真壁钟
- `TelemetrySessionEntity.startTs / endTs`：与 `header.startTs / endTs` 同源（同一次 `currentTimeMillis()` 调用结果）
- `sample.ts_delta_ms`：样本写入瞬间的 `System.currentTimeMillis()` 与 `header.startTs` 之差，**不是与任何其他时刻取的真壁钟之差**

**禁止用法**（任何写入 lap session binary 的入口）：

```
tsDeltaMs = gpsData.timestamp - lapAnchorTs           // 跨时钟域，禁止
tsDeltaMs = System.currentTimeMillis() - lapAnchorTs  // 同时钟域但 anchor 错位（lapAnchorTs ≠ header.startTs），禁止
```

**正确用法**：

```
val sessionStartTs = telemetryRepository.activeSessionStartTs   // == header.startTs
val tsDeltaMs = System.currentTimeMillis() - sessionStartTs
```

repository SHALL 暴露只读 property `activeSessionStartTs: Long?`，其值在 `startSession()` 时与 `header.startTs / entity.startTs` 同时刻赋值（同一次 `currentTimeMillis()` 调用结果），在 `endSession()` 时清空。

#### Scenario: writer-reader round trip 在 anchor 同源下窗口过滤命中

- **WHEN** 调用 `repository.startSession(LAP_SESSION)` 后立即拉取 `repository.activeSessionStartTs = T1`，连续以 `tsDeltaMs = System.currentTimeMillis() - T1` 写入 N 帧 sample（间隔 ~40ms 模拟 25Hz），调 `repository.endSession()` 关闭文件
- **THEN** 调用 `readLapSamples(filePath, lapStartTs = T1, lapEndTs = T1 + (N-1) × 40 + tolerance)` 返回 N 帧样本，所有样本 `absoluteTs = header.startTs + tsDeltaMs` 落在 `[lapStartTs, lapEndTs]` 范围内

#### Scenario: anchor 错位会被 round trip 测试捕获（writer 直接构造，无需 mock System）

- **WHEN** 直接用 `BinaryTelemetryWriter.open(path, type, startTs = 10000)` 构造文件（模拟 `header.startTs = 10000` = T1），但故意用模拟"anchor 错位"的 sample：每帧 `tsDeltaMs = (i × 40) + 5000`（额外 +5000 模拟 `currentTimeMillis - T0` 中 `T0 = T1 - 5000` 的错位等待时间偏差），写入 100 帧后 close
- **THEN** 调用 `readLapSamples(filePath, lapStartTs = 10000, lapEndTs = 10000 + 100 × 40)` 时，由于所有样本 `absoluteTs = 10000 + tsDeltaMs ≥ 15000` 整体向未来偏移 5000ms，全部 100 帧落在窗口外被剔除——测试 assert "返回样本数 == 100" 会失败，从而捕获 anchor 错位 bug

#### Scenario: 时间窗口过滤正确剔除窗外样本（session 全窗口）

- **WHEN** 写入 100 帧样本（持续 4 秒，每帧间隔 40ms），调用 `readLapSamples(filePath, lapStartTs = sessionStartTs + 1000, lapEndTs = sessionStartTs + 3000)` 截取中间 2 秒
- **THEN** 返回 50±1 帧样本（端点边界容差），所有样本 `absoluteTs` 落在 `[sessionStartTs + 1000, sessionStartTs + 3000]` 范围内

#### Scenario: 与 readPerformanceSamples 兼容性

- **WHEN** 同一 binary 文件先用 `readLapSamples(filePath, sessionStartTs, sessionEndTs)` 全窗口读取，再用 `readPerformanceSamples(filePath)` 顺序读取
- **THEN** 两次读取返回的样本数量一致，speedKmh / lat / lon / bearing 字段逐条相等（顺序读与全窗口读在覆盖全 session 时等价）

#### Scenario: 时钟域单源 grep 自检

- **WHEN** 在 `feature/test/.../viewmodel/TestSessionViewModel.kt` 与任何 simulator replay 写入 lap session binary 的入口 grep 以下模式：
  - `gpsData\.timestamp\s*-\s*(lapAnchorTs|sessionStartTs|activeLapStartSystemTs)`
  - `System\.currentTimeMillis\(\)\s*-\s*(lapAnchorTs|activeLapStartSystemTs)`（anchor 错位模式）
- **THEN** 上述两类 grep 结果均为空；同时 grep 应能找到 `System.currentTimeMillis()\s*-\s*sessionStartTs`（或等价的 repository property reference）作为 `tsDeltaMs` 的唯一计算入口

#### Scenario: anchor 与 header.startTs / entity.startTs 同源单元验证（无需 mock System）

- **WHEN** 实例化 `TelemetryRepository`（注入测试 dao + 临时 file 目录），调 `startSession(LAP_SESSION)` 返回 `sessionId`
- **THEN** 同时满足三相等：
  1. `repository.activeSessionStartTs` 不为 null
  2. `sessionDao.queryBySessionId(sessionId).startTs == repository.activeSessionStartTs`（entity 与 property 同源）
  3. 读取 `repository.getSession(sessionId).binaryFilePath` 的首 `GpsBinaryFormat.HEADER_SIZE` bytes 后调 `GpsBinaryFormat.decodeHeader(bytes)`，得到 `header.startTs == repository.activeSessionStartTs`（writer/header 与 property 同源）。注：`GpsBinaryFormat` 是 `core.data.local.binary` 包内 internal object（参考现有 `BinaryTelemetryWriterTest.kt` 的 `readHeader()` 测试 helper 同 pattern）

#### Scenario: endSession 后 activeSessionStartTs 清空

- **WHEN** 调 `startSession()` 后再调 `endSession(sessionId)`
- **THEN** `repository.activeSessionStartTs == null`（避免下个 session 复用 stale 值导致 anchor 漂移）
