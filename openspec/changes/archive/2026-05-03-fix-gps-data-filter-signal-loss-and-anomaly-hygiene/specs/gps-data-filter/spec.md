# Spec Delta: gps-data-filter

> Capability: **GPS 数据过滤器的信号丢失恢复与异常帧隔离**。`GpsDataFilter.process`
> 必须在信号丢失（`dt > 200ms`）时立即重置内部基准，并且异常帧（速度物理约束违反
> 或位置一致性失效）**不得**污染下一次计算的参考状态。
>
> 依赖关系：A13（异常帧不更新 `previousRaw`）**必须**在 A12（重置顺序前置）之后
> 实现。A12 的 `dt > 200ms` 自动重置兜底机制保证 A13 的 "异常帧不更新" 不会让
> `previousRaw` 永久锁死。
>
> 不影响 `fix-laptime-clock-source-integrity` 的分层守卫（未同步帧的零 delta
> 快照路径，本 change 保留 `if (!raw.isTimeSynced)` 早退不变）。

## ADDED Requirements

### Requirement: 信号丢失重置必须在加速度 / 物理约束判定之前完成（A12）

`GpsDataFilter.process(raw)` MUST 按以下顺序执行：

1. **分层守卫**（现有）：`!raw.isTimeSynced` 时返回零 delta 快照，不动内部状态。
2. **信号丢失重置**：计算 `dtFromPrevious = (raw.timestamp - previousRaw?.timestamp) / 1000.0`；若 `dtFromPrevious > 0.2`（超过 200ms）则 `previousRaw = null` 且 `previousPosition = null`。
3. **加速度计算**：`calculateAcceleration(raw)`，`previousRaw == null` 时 MUST 早退返回 `0.0`。
4. **物理约束判定**：`isPhysicalConstraintViolation(raw)`，`previousRaw == null` 时 MUST 早退返回 `false`。
5. **位置-速度一致性检验**：`checkPositionVelocityConsistency(raw)`，`previousPosition == null` 时 MUST 早退返回 `(1.0, false)`。

关键属性：

- 信号丢失（`dt > 200ms`）后的**第一帧** MUST 作为"新基准"处理，不把跨大 `dt` 的速度差误记为加速度、不把真实跳变误判为物理约束违反。
- 原版 v1 的顺序（先 `calculateAcceleration` / `isPhysicalConstraintViolation`、再重置）在失联后首帧用旧 `previousRaw` 计算 → `maxDelta = 90 × dt` 巨大 → 真实跳变被判"非异常"。本 Requirement 显式禁止该顺序。

#### Scenario: 信号丢失 > 200ms 后首帧加速度归零，不误判物理约束

- **GIVEN** `GpsDataFilter` 刚处理过一帧 `(ts=0, speed=60, isTimeSynced=true)`，`previousRaw` 已建立
- **WHEN** 喂入一帧 `(ts=500, speed=60, isTimeSynced=true)`（`dtFromPrevious = 0.5 > 0.2`）
- **THEN** 内部 `previousRaw` 被重置为 `null`，`previousPosition` 被重置为 `null`
- **AND** `result.acceleration == 0.0`（`calculateAcceleration` 早退）
- **AND** `result.isAnomaly == false`（`isPhysicalConstraintViolation` 早退）
- **AND** `result.consistencyFactor == 1.0`（`checkPositionVelocityConsistency` 早退）

#### Scenario: 信号丢失后首帧大跳变不被旧 previousRaw 错误压制

- **GIVEN** `GpsDataFilter` 已建立 `previousRaw (ts=0, speed=10)`
- **WHEN** 喂入一帧 `(ts=500, speed=310, isTimeSynced=true)`（真实速度跳 300 km/h + 500ms 大 dt）
- **THEN** 因 A12 先重置，`previousRaw == null`，`isPhysicalConstraintViolation` 早退返回 `false`
- **AND** `result.isAnomaly == false`（正确：首帧没有对比基准，不能凭空判异常，而不是 v1 的"用 500ms 前旧速度算 maxDelta 巨大误判非异常"）
- **AND** 下一帧 `(ts=540, speed=310)` 以 310 为新基准正常计算

---

### Requirement: 异常帧 MUST NOT 更新 previousRaw / previousPosition（A13，依赖 A12）

`GpsDataFilter.process(raw)` 末尾更新内部参考状态时，MUST 按以下条件判定：

```kotlin
if (!isAnomaly && !isPositionAnomaly) {
    previousRaw = raw
    previousPosition = raw.latitude to raw.longitude
}
```

即：本帧若被判为物理约束违反（`isAnomaly`）或位置一致性失效（`isPositionAnomaly`），MUST NOT 把该帧写入 `previousRaw` / `previousPosition`。

**A12 兜底前提**（本 Requirement 依赖 A12 的重置机制）：

连续 N 帧异常（均不更新 `previousRaw`）时，`previousRaw` 会保持在最后一次"非异常帧"的时刻。若该时刻与当前帧的时间差超过 200ms，A12 的重置分支自动把 `previousRaw` 置为 `null`，下一帧以"新基准"继续。**因此本 Requirement 不需要引入额外的"连续异常计数器"**。若 A12 未按 "Requirement: 信号丢失重置必须在加速度判定之前完成" 实施，本 Requirement 会让 `previousRaw` 在长期连续异常场景下被**永久锁死**在旧值 —— 这是 A13 必须在 A12 之后实施的依据。

#### Scenario: 单次 spike 不污染下一帧

- **GIVEN** `previousRaw = (ts=0, speed=30)`，`previousPosition = (lat0, lon0)`
- **WHEN** 喂入一帧 `(ts=40, speed=200, isTimeSynced=true)`，触发 `isAnomaly = true`（速度跳变超物理约束）
- **THEN** `result.isAnomaly == true`
- **AND** `previousRaw` **保持** `(ts=0, speed=30)` 未更新
- **WHEN** 再喂入一帧 `(ts=80, speed=32, isTimeSynced=true)`（恢复到合理值）
- **THEN** `isPhysicalConstraintViolation` 以 `previousRaw=(ts=0, speed=30)` 为基准判定，`dv=2, dt=0.08, maxDelta=90×0.08=7.2 km/h`，跳变 2 km/h < 7.2 → `isAnomaly = false`
- **AND** `previousRaw` 更新为 `(ts=80, speed=32)`（本帧非异常）

#### Scenario: 连续异常不锁死 previousRaw（A12 兜底）

- **GIVEN** `previousRaw = (ts=0, speed=30)`
- **WHEN** 连续 3 帧异常 `(ts=40, speed=200)` / `(ts=80, speed=200)` / `(ts=120, speed=200)`，各帧 `isAnomaly = true` 且 `previousRaw` 未更新
- **AND** 再喂入一帧 `(ts=500, speed=31, isTimeSynced=true)`，此帧相对 `previousRaw=(ts=0)` 的 `dt = 0.5 > 0.2`
- **THEN** A12 重置分支先触发 → `previousRaw = null`
- **AND** `calculateAcceleration` / `isPhysicalConstraintViolation` / `checkPositionVelocityConsistency` 都走早退分支
- **AND** `result.acceleration == 0.0`，`result.isAnomaly == false`
- **AND** 本帧作为非异常帧 → 更新 `previousRaw = (ts=500, speed=31)` 成为新基准

---

### Requirement: 异常帧不得进入滤波窗口，`isAnomaly` 分支简化（A14，独立）

`GpsDataFilter.process(raw)` MUST 在把当前帧 add 进 `speedWindow` / `latWindow` / `lonWindow` 之前根据异常判定结果条件隔离：物理约束违反（`isAnomaly == true`）的帧 MUST NOT 进入 `speedWindow`；位置一致性失效（`isPositionAnomaly == true`）的帧 MUST NOT 进入 `latWindow` / `lonWindow`。`bearingWindow` 不受这两类异常约束。`outputSpeed` / `outputLat` / `outputLon` 统一计算 `if (window.size >= 3) window.median() else raw.value`，不再按 `isAnomaly` 分支条件（v1 两个分支体等价，语义冗余）。`isAnomaly` 对下游的唯一影响 MUST 收敛为 `calculateConfidence` 的 `confidence × 0.5` 降权。

具体 API 合约：

- `speedWindow` MUST 只在 `isAnomaly == false` 时 `add(raw.speed)`；`isAnomaly == true` 帧不入窗口。
- `latWindow` / `lonWindow` MUST 只在 `isPositionAnomaly == false` 时 `add`；`isPositionAnomaly == true` 帧不入窗口。
- `bearingWindow` 不受 `isAnomaly` / `isPositionAnomaly` 影响（`bearing` 是方位角方向量，`circularMedian` 对单帧离群有自带的单位向量平均鲁棒性）。
- `outputSpeed` / `outputLat` / `outputLon` / `outputBearing` 统一计算：
  ```kotlin
  val outputSpeed = if (speedWindow.size >= 3) speedWindow.median() else raw.speed
  ```
  不再有 `isAnomaly` 的 `when` 分支条件（v1 的两个分支体等价，语义冗余）。
- `isAnomaly` 的唯一下游影响 = `calculateConfidence(isAnomaly, hdop, consistencyFactor)` 的 `confidence × 0.5` 降权。

#### Scenario: 速度异常帧不拉偏后续 median

- **GIVEN** `GpsDataFilter.speedWindow` 已填充 `[60.0, 60.0, 60.0]`（3 帧正常）
- **WHEN** 喂入一帧 `(speed=300, isTimeSynced=true)`，触发 `isAnomaly = true`
- **THEN** `speedWindow` 保持 `[60.0, 60.0, 60.0]`（异常帧不入）
- **AND** `result.speed == 60.0`（median 稳定）
- **WHEN** 再喂入 2 帧 `(speed=60, isTimeSynced=true)` 正常帧
- **THEN** `speedWindow` 推进到 `[60.0, 60.0, 60.0, 60.0, 60.0]`（超过 `windowSize=9` 会裁剪最老）
- **AND** 后续 `result.speed` 持续稳定在 60.0，不受 300 污染

#### Scenario: 位置异常帧不拉偏 lat/lon median

- **GIVEN** `latWindow` / `lonWindow` 已填充 3 帧正常值
- **WHEN** 喂入一帧 `(latitude = 偏差 10 米, longitude = 偏差 10 米, isTimeSynced=true)` 触发 `isPositionAnomaly = true`
- **THEN** `latWindow` / `lonWindow` 不新增这帧
- **AND** `result.latitude` / `result.longitude` median 输出不受影响

#### Scenario: bearing 窗口不受 isAnomaly 影响

- **GIVEN** `bearingWindow` 已填充 3 帧
- **WHEN** 喂入任何一帧（`isAnomaly` 真假不限）
- **THEN** `bearingWindow` 无条件 add 该帧 bearing（交由 `circularMedian` 处理）
- **AND** 窗口裁剪逻辑不变
