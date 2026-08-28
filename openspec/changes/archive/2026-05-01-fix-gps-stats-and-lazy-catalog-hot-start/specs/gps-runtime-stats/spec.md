## ADDED Requirements

### Requirement: `GpsDataViewModel.dataQuality.value.frequency` 透传 parser 1 秒滑窗频率

`GpsDataViewModel._dataQuality` 发射的 `DataQuality.frequency` 字段 MUST 等于最近一帧 `GpsData.frequency`（由 `RaceChronoParser` 维护的 1 秒滑动窗口计算得出），MUST NOT 引入会话级累计平均、跨帧移动平均或 ViewModel 自维护的时间窗口。

- 业务目标：丢帧与频率抖动能在 1 秒内反映到 UI 的 `dataQuality.frequency`，替代 v1 的小时级滞后累计平均
- 源：`RaceChronoParser.kt:29,33,219-225,259` 的 `gpsDataTimestamps` 1 秒窗 + `gpsFrequency` + 写入 `GpsData.frequency = gpsFrequency`
- 消费路径：`updateDataStats(data)` 内构造局部 `DataStats(frequency = data.frequency, ...)` 传入 `DataQualityEvaluator.calculateQuality(data, stats)` → 得到 `DataQuality` 写入 `_dataQuality.value`；Review v2 P1-1 修补：观察点是**已有的** `dataQuality: StateFlow<DataQuality>`（line 58-59），**不新增** `_dataStats: StateFlow<DataStats>` API（避免扩大 scope）
- 消费契约：ViewModel MUST 直接读 `data.frequency`，MUST NOT 订阅 `RaceChronoParser.gpsFrequency` private 字段、MUST NOT 暴露 parser internal state

#### Scenario: 稳态 25Hz 透传

- **GIVEN** `GpsDataViewModel.init` 已完成、订阅 `gpsDataRepository.gpsData`
- **WHEN** 收到一帧 `GpsData(frequency = 25.0, timestamp = T)`
- **THEN** `dataQuality.value.frequency == 25.0`

#### Scenario: 低频帧立即透传（硬区分 v1 累计平均）

- **GIVEN** ViewModel 已连续处理若干帧稳态 `data.frequency = 25.0`
- **WHEN** 收到一帧 `GpsData(frequency = 1.0, ...)`（parser 滑窗判定已掉到 1Hz）
- **THEN** `dataQuality.value.frequency == 1.0`（立即生效，不对历史均值求平均）
- **AND 硬区分 v1**：v1 实现 `dataCount / elapsedSeconds` 会显示接近历史均值（若前序 30 秒 25Hz，此时显示 > 10.0），本契约要求 1.0

#### Scenario: ViewModel 不维护 dataCount / dataCountStartTime

- **GIVEN** 实施后的 `GpsDataViewModel.kt` 源码
- **WHEN** grep 字段 `dataCount`、`dataCountStartTime`、`expectedInterval = 100`
- **THEN** 均不存在（被 A28 删除）

#### Scenario: ViewModel 不新增 dataStats StateFlow API

- **GIVEN** 实施后 `GpsDataViewModel.kt` 源码
- **WHEN** grep `_dataStats` / `val dataStats` / `StateFlow<DataStats>` / `MutableStateFlow<DataStats>`
- **THEN** 均不存在（保持原有 `dataQuality` 观察路径，避免扩大 scope）

### Requirement: `packetLoss` 公式从 `data.frequency` 反推采样周期

`updateDataStats` 内构造的 `DataStats.packetLossRate` 局部值（传入 evaluator 后反映为 `dataQuality.value.packetLoss`）MUST 根据以下公式计算，不硬编码采样率假设：

```kotlin
val expectedSampleInterval = if (data.frequency > 0.0) 1000.0 / data.frequency else 0.0
val packetLossRate = if (expectedSampleInterval > 0.0 && dataAge > expectedSampleInterval * 2) {
    ((dataAge - expectedSampleInterval) / expectedSampleInterval).coerceIn(0.0, 100.0)
} else {
    0.0
}
```

- 业务目标：一条公式覆盖 10Hz / 25Hz / 50Hz 等不同设备，避免 10Hz 假设在 25Hz 设备上长期偏差
- `data.frequency ≤ 0.0`（暖启动 / 丢连瞬间）时回退为 0.0，避免 NaN / 除零 / 冷启动误告警
- 2× 阈值门槛保留（与 v1 容忍度一致），短暂抖动不触发
- 观察路径：`DataStats.packetLossRate`（局部） → `DataQualityEvaluator.calculateQuality` → `DataQuality.packetLoss`（字段名由 domain 层定，ViewModel 透传）

#### Scenario: 25Hz 稳态 dataAge=30ms packetLoss = 0

- **GIVEN** `data.frequency = 25.0`、`dataAge = 30` ms
- **WHEN** ViewModel 处理该帧并写入 `_dataQuality`
- **THEN** `dataQuality.value.packetLoss == 0.0`（`expectedSampleInterval = 40ms`，30 < 80 不触发）

#### Scenario: 25Hz 超阈值 dataAge=200ms packetLoss = 4.0

- **GIVEN** `data.frequency = 25.0`、`dataAge = 200` ms
- **WHEN** ViewModel 处理该帧并写入 `_dataQuality`
- **THEN** `dataQuality.value.packetLoss == 4.0`（`(200-40)/40 = 4.0`）

#### Scenario: 10Hz 低频设备 dataAge=300ms packetLoss = 2.0（口径自适应）

- **GIVEN** `data.frequency = 10.0`、`dataAge = 300` ms
- **WHEN** ViewModel 处理该帧并写入 `_dataQuality`
- **THEN** `dataQuality.value.packetLoss == 2.0`（`expectedSampleInterval = 100ms`，`(300-100)/100 = 2.0`）
- **AND 硬区分 v1**：v1 硬编码 `expectedInterval = 100L` 在 25Hz 设备上会误报，在 10Hz 设备上刚好正确，本契约要求两种设备同公式自动对齐

#### Scenario: 暖启动 / 丢连 data.frequency = 0.0 packetLoss = 0.0

- **GIVEN** `data.frequency = 0.0`（parser 滑窗未启用，冷启动或断连）
- **WHEN** ViewModel 处理该帧并写入 `_dataQuality`
- **THEN** `dataQuality.value.packetLoss == 0.0`（无窗口数据不做判断，避免 NaN / 除零 / 错误告警）

### Requirement: `GpsDataViewModel` 在 `ConnectionState.DISCONNECTED` 时自动 `resetStats()`

`GpsDataViewModel.init` MUST 订阅自身已暴露的 `connectionState: StateFlow<ConnectionState>`（来自 `gpsDataRepository.connectionState`），当且仅当状态迁入 `DISCONNECTED` 时调用 `resetStats()`，避免跨连接会话的 stats 残留。

- 订阅层 MUST 在 `GpsDataViewModel.init` 自身完成，MUST NOT 由 `TestSessionViewModel` / `BleConnection` / `GpsDataRepository` 跨层触发
- 订阅链 MUST 使用 `distinctUntilChanged`（或等价机制）避免同状态重复发射时重复 reset
- `BleConnection.kt` 与 `TestSessionViewModel.kt` MUST NOT 引入任何指向 `GpsDataViewModel.resetStats()` 的调用

#### Scenario: DISCONNECTED 触发 resetStats

- **GIVEN** `GpsDataViewModel` 已 init 并处理若干帧 `GpsData`，`dataQuality.value.frequency > 0.0`
- **WHEN** `connectionState` 发射 `ConnectionState.DISCONNECTED`
- **THEN** `_dataQuality.value == DataQuality.Empty`（`DataQuality.Empty` companion 已存在于 `core/domain/.../model/DataQuality.kt:49-59`，`frequency = 0.0` / `packetLoss = 0.0` / `dataAge = 0L` / `overall = QualityLevel.POOR` / `overallScore = 0`）

#### Scenario: 订阅层位于 GpsDataViewModel 自身

- **GIVEN** 实施后 `GpsDataViewModel.kt`、`TestSessionViewModel.kt`、`BleConnection.kt` 三个文件源码
- **WHEN** grep `resetStats()` / `gpsDataViewModel.resetStats`
- **THEN** `resetStats()` 调用点只存在于 `GpsDataViewModel.init` 的 `connectionState` 订阅链内；`TestSessionViewModel` 与 `BleConnection` 不引用

#### Scenario: distinctUntilChanged 防重复 reset

- **GIVEN** `connectionState` 连续发射两次 `DISCONNECTED`（StateFlow 理论去重，但订阅链 MUST 保险起见显式去重）
- **WHEN** 订阅链处理两次发射
- **THEN** `resetStats()` 只被调用一次（可通过 spy / 计数验证）

### Requirement: 不新增 parser public mutable state

`RaceChronoParser.gpsFrequency` 与 `gpsDataTimestamps` MUST 保持 `private` 可见性，本 change MUST NOT 为 ViewModel 订阅新增 public getter / StateFlow / 任何 API 暴露。

- parser 仍是 GpsData 的唯一生产者；ViewModel 通过 `data.frequency` 消费而不是直接订阅 parser

#### Scenario: gpsFrequency / gpsDataTimestamps 保持 private

- **GIVEN** `RaceChronoParser.kt` 实施后源码
- **WHEN** grep `gpsFrequency` 与 `gpsDataTimestamps` 的修饰符
- **THEN** 两者可见性均为 `private`（无 `val gpsFrequency` 或 `public` / `internal`）

### Requirement: 不引入生产侧 `EXPECTED_SAMPLE_INTERVAL_MS` 常量

本 change MUST NOT 在 `core:domain` / `core:bluetooth` / `feature:test` 生产代码中引入表达"期望采样周期"的全局常量（如 `EXPECTED_SAMPLE_INTERVAL_MS = 40L`、`GpsConstants.EXPECTED_FRAME_INTERVAL_MS` 等）。

- 理由：把特定设备（ESP32 25Hz）的采样率写为全局协议会在后续支持 10Hz / 50Hz 设备时变成误导常量
- 测试构造 25Hz 序列所需的 `40L` 可放在**测试文件私有**常量或 `companion object`，不跨文件暴露

#### Scenario: 生产代码中不存在 EXPECTED_SAMPLE_INTERVAL_MS

- **GIVEN** 实施后 `core:domain` + `core:bluetooth` + `feature/test/src/main` 目录
- **WHEN** grep `EXPECTED_SAMPLE_INTERVAL_MS` 或等价命名
- **THEN** 仅可能在 `feature/test/src/test` 测试目录内作为 test-private 常量出现，生产侧零匹配
