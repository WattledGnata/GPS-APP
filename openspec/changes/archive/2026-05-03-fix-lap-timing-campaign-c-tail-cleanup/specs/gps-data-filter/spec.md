# Spec Delta: gps-data-filter

> Capability: **filter 清理纠偏（A43 命名 + A44 antimeridian）**。
> `GpsDataFilter.circularMedian` 重命名为 `circularMean` 让 API 名称与实现语义一致
> （单位向量求和是循环均值，不是真中位数）；`checkPositionVelocityConsistency` 的
> 经度差计算加入 ±180° 绕回处理（`wrappedDeltaLon`），跨 antimeridian 不再产生
> 假位移假阳性。
>
> 本 Requirement 不影响已闭环的 `fix-gps-data-filter-signal-loss-and-anomaly-hygiene`
> （A12 信号丢失重置顺序 / A13 异常帧不污染 previousRaw / A14 异常帧不入窗口）
> 和 `fix-laptime-clock-source-integrity`（`!isTimeSynced` 分层守卫）语义。

## ADDED Requirements

### Requirement: bearing 窗口算法重命名为 circularMean（A43 命名纠偏）

filter 用于 bearing 窗口跨 0°/360° 边界正确收敛的扩展函数 MUST 命名为
`circularMean`（而非 v1 的 `circularMedian`）。算法保持不变（单位向量求和 + atan2 +
规范化到 `[0, 360)`），仅名称与注释纠偏：v1 名称 `circularMedian` 误导"对离群鲁棒"，
实际实现是**向量均值**，对长尾会被拉向长尾，不对离群鲁棒。

新函数注释 MUST 明确：
- 是循环**均值**（单位向量求和 + atan2），不是中位数
- 对对称分布准确，对长尾分布会被拉向长尾
- 想要鲁棒性的场景应先用外层 anomaly 过滤把 spike 样本排除，不要假设本函数自带鲁棒性

调用点（`GpsDataFilter.process` 第 102 行 `bearingWindow.circularMedian()`）MUST 同步
更新为 `bearingWindow.circularMean()`。

#### Scenario: 函数重命名 circularMean 在算法层面保持等价

- **GIVEN** bearing 样本 `[355.0, 0.0, 5.0]`（跨 0°/360° 边界，对称分布，真实中心 0°）
- **WHEN** 调用 `bearing 窗口.circularMean()`（v2 新名）
- **THEN** 返回值应位于 `0°` 附近（浮点精度内 `Math.abs(result - 0.0) < 1e-9` 或 `result in 359.99..360.0`）
- **AND** 与 v1 `circularMedian()` 在同一输入下返回完全相同的数值（rename 不改算法）

#### Scenario: filter bearing 输出路径改用 circularMean

- **GIVEN** `GpsDataFilter.process` 内 `bearingWindow.size >= 3`
- **WHEN** 计算 `outputBearing`
- **THEN** 代码路径 MUST 调用 `bearingWindow.circularMean()`，MUST NOT 调用 `bearingWindow.circularMedian()`（v1 名已不存在）

---

### Requirement: wrappedDeltaLon 跨经度 180° 绕回处理（A44 边界 case）

filter 的位置-速度一致性检验计算经度差时 MUST 通过 helper `wrappedDeltaLon` 处理
±180° 绕回（antimeridian wrap），确保跨经度 180° 场景下 `deltaLonM` 反映真实小位移
而非绕地球一周的假差。具体涉及 `GpsDataFilter.checkPositionVelocityConsistency`
函数内所有 `current.longitude - previousPosition.second` 的计算位置。

helper 语义：

```kotlin
private fun wrappedDeltaLon(currentLon: Double, prevLon: Double): Double {
    val raw = currentLon - prevLon
    return when {
        raw > 180.0 -> raw - 360.0
        raw < -180.0 -> raw + 360.0
        else -> raw
    }
}
```

调用点（`GpsDataFilter.kt:219`）MUST 从：

```kotlin
val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)
```

改为：

```kotlin
val deltaLonM = abs(wrappedDeltaLon(current.longitude, prevPos.second)) * 111320.0 * Math.cos(latRad)
```

#### Scenario: 跨 antimeridian 不产生假位移（物理自洽小位移）

- **GIVEN** `previousPosition = (lat=0.0, lon=179.9999975)`（赤道，近 180°E 边界）
- **AND** filter 内部建立了对应的 `previousRaw` 指向该帧，`prevRaw.timestampMillis = t0`
- **WHEN** 下一帧 `current = (lat=0.0, lon=-179.9999975, speed=50.0 km/h, ts = t0 + 40ms)`（跨 antimeridian 到 180°W，物理自洽的 40ms / 50km/h 场景对应位移 ~0.56m）
- **AND** `checkPositionVelocityConsistency` 计算 `deltaLonM`
- **THEN** v2 调 `wrappedDeltaLon(-179.9999975, 179.9999975)`：`raw = -359.999995`，`< -180` 修正 `+360` → `0.000005°`
- **AND** `deltaLonM = |0.000005| × 111320 × cos(0) ≈ 0.5566m`
- **AND** `vImpliedKmh ≈ 0.5566 / 0.04 × 3.6 ≈ 50.1 km/h`（与 `current.speed=50` 匹配）
- **AND** `result.isPositionAnomaly == false`（ratio ≈ 0.02 远小于阈值 3）
- **AND** `result.consistencyFactor` 接近 1.0（低速容差 5 km/h 下 speedDiff ≈ 0.1）
- **AND** 硬区分 v1：v1 不处理 wrap → `abs(-179.9999975 - 179.9999975) ≈ 359.999995°` → `deltaLonM ≈ 40,075 km` → `vImpliedKmh` 爆表到 1e9 级 → `isPositionAnomaly = true` 假阳性 + `consistencyFactor = 0.3`

#### Scenario: 非跨边界场景 wrappedDeltaLon 透传原始差

- **GIVEN** `previousPosition = (lat=30.49, lon=104.43)`（TFIC，远离 antimeridian）
- **AND** `current = (lat=30.49, lon=104.44)`（经度差 +0.01°，~1km 物理位移）
- **WHEN** `wrappedDeltaLon(104.44, 104.43)` 被调用
- **THEN** `raw = 0.01`，`raw in [-180, 180]` → 返回 `0.01`（透传原始差，不做修正）
- **AND** `deltaLonM` 与 v1 计算结果完全相同（TFIC 场景零行为变更）

#### Scenario: 边界值 raw = ±180° 精确（理论边界）

- **GIVEN** 构造 `currentLon - prevLon = 180.0`（精确等于 180°）
- **WHEN** `wrappedDeltaLon` 被调用
- **THEN** 因条件是 `raw > 180.0`（严格大于），`raw = 180.0` 不触发修正，返回 `180.0`
- **AND** 对称：`raw = -180.0` 因 `raw < -180.0` 不触发，返回 `-180.0`
- **AND** 边界值 `abs(180.0)` = 180 度投影到米 ~= 20,038 km，在物理上等价于"跨 antimeridian 一半" —— 这是**理论退化点**，`wrappedDeltaLon` 对此不特殊处理（相邻两帧跨 180° 完整边界的场景物理上对应车速 > 2000 km/s，不现实，不需要考虑）
