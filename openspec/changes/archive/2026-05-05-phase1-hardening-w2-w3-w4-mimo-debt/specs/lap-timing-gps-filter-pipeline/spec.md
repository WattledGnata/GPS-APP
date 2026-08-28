## MODIFIED Requirements

### Requirement: 圈速通道 GPS Filter 接通契约

`feature/test/.../viewmodel/TestSessionViewModel.kt` 内 `viewModelScope.launch { gpsDataViewModel.gpsData.collect { gpsData -> ... } }` 块中喂入 `bridgeGpsToLapTiming` 的 GPS 数据 MUST 经 `core/domain/.../usecase/GpsDataFilter.process(gpsData)` 滤波后构造的 cleaned 副本，**仅替换** `latitude / longitude / speed / bearing` 四个字段；`timestamp / isTimeSynced` 等元信息字段 MUST 保留 raw（`GpsDataFilter` 不滤时间字段，filter 输出的 `timestamp` 与 raw 一致直传）。

实施层面 MUST 满足（hotfix B 修回 4 字段后契约，2026-05-05）：

```kotlin
val filteredData = gpsDataFilter.process(gpsData)
val cleaned = gpsData.copy(
    latitude = filteredData.latitude,
    longitude = filteredData.longitude,
    speed = filteredData.speed,
    bearing = filteredData.bearing,
)
bridgeGpsToLapTiming(cleaned)
```

`bridgeGpsToLapTiming` 函数签名 `private suspend fun bridgeGpsToLapTiming(gpsData: GpsData)` MUST 保持不变（不增加参数）。函数体内 `gpsData.toLapGpsSample()` 与 telemetry binary 写入路径都使用 cleaned 入参（不再次调用 `gpsDataFilter.process`，避免破坏 9 帧滚动窗口）。

**E1 加 timestamp 保留正面 contract case（hotfix B 后行为锁）**：本 round MUST 在 `LapFilterIntegrationTest` 加正面 case 锁 `cleaned.timestamp == raw.timestamp`（W4 mimo 实施时跳过该 case，hotfix B 修回 4 字段后行为契约对齐，但缺自动化锁；本 round 补齐）。

#### Scenario: 单帧 jitter outlier 不触发 WrongDirection

- **WHEN** 构造 8 帧正常前进（lat 单调增 0.0001 / 帧 + speed 100 km/h + bearing 90° + ts 间隔 40ms）+ 第 9 帧位置突变（lat 偏移 +1°，等价 ~111km 跳跃，模拟单帧 GPS 错点；**reported speed 仍 = 100 km/h** 不突变，让 v_implied / v_reported >> 3.0 触发 `isPositionAnomaly = true`），后续 4 帧恢复正常前进
- **AND** 把整段 13 帧依次喂 `GpsDataFilter`，每帧用 cleaned 副本喂 `LapTimingEngine.processSample`（previousSample 由 detector 内部维护）
- **THEN** detector 在第 9 帧 MUST NOT 输出 `reason = WrongDirection`（具体可能输出 `NoIntersection` / 正常 `accepted = true` / 其他非 invalidating reason 等多种合法路径，但**不应**触发 invalidating reason；因 9 帧 median 把 outlier 的 lat 拉回中位数 → cleaned 帧位置接近正常前进序列 → 矢量方向不构成强反向特征）

#### Scenario: lap duration 不受 filter ~160ms 滞后影响

- **WHEN** 构造一段完整圈数据：开圈过 startfinish gate（直线段，t = 0ms，speed = 200 km/h）→ 100 帧 racing line（4s）→ 闭圈过 startfinish gate（直线段，t = 4000ms，speed = 200 km/h）
- **AND** 分别用 raw 直喂 detector 与「filter → cleaned 替换 → 喂 detector」两条路径跑一遍
- **THEN** 两条路径产出的 `lap_duration` 差值 MUST < 50ms（开/闭圈 filter 滞后量相等，过线点速度变化平稳时相减抵消；50ms 是直线段速度微扰最坏估计上界）

#### Scenario: 正例 — cleaned.timestamp 字段保留 raw（E1 加严，hotfix B 后行为锁）

- **WHEN** 构造一帧 GPS 数据 `gpsData` 带 `timestamp = 1700000000_000L`（raw 时间戳）+ 喂 `gpsDataFilter.process(gpsData)` 得 `filteredData`
- **AND** 按 hotfix B 后契约构造 `cleaned = gpsData.copy(latitude=filteredData.latitude, longitude=filteredData.longitude, speed=filteredData.speed, bearing=filteredData.bearing)`
- **THEN** `cleaned.timestamp == 1700000000_000L`（== `gpsData.timestamp`，**MUST NOT** == `filteredData.timestamp` 即使 filter 输出时间戳值与 raw 相同 — 字面赋值路径不同会让"未来 filter 改成滤时间戳"无声破坏 detector 插值精度）；`cleaned.isTimeSynced == gpsData.isTimeSynced`

#### Scenario: 反例—— 误把 timestamp 一并替换为 filtered.timestamp 退化圈时插值

- **WHEN** 实施时把 `cleaned = gpsData.copy(latitude = ..., longitude = ..., speed = ..., bearing = ..., timestamp = filteredData.timestamp)` 写错（`timestamp = filteredData.timestamp`）
- **THEN** 测试断言 `cleaned.timestamp == gpsData.timestamp` MUST fail（filter 不滤时间，但显式赋值会让 timestamp 来自 filter 内部计算路径；contract gate 防止"未来 filter 改成滤时间戳"无声破坏 detector 插值精度）；同时本 round LapFilterIntegrationTest E1 case "cleaned.timestamp == raw.timestamp" MUST fail（W4 mimo 跳过该 case 时 silent 通过；本 round 补齐后该路径有 lock）

#### Scenario: 反例 — hotfix B 4 字段被回滚为 2 字段必须 fail（防回退）

- **WHEN** 任意 follow-up round 把 `cleaned = gpsData.copy(latitude=..., longitude=..., speed=..., bearing=...)` 回退为仅 2 字段 `cleaned = gpsData.copy(speed=..., bearing=...)`（不替换 lat/lon）
- **THEN** 本 round LapFilterIntegrationTest 单帧 jitter outlier case MUST fail（lat/lon 仍是 raw outlier，detector directionScore 用 raw 位置差计算 → 触发 WrongDirection）；同时 grep gate 扫 `feature/test/.../viewmodel/TestSessionViewModel.kt` MUST 出现 `latitude = filteredData.latitude` + `longitude = filteredData.longitude` + `speed = filteredData.speed` + `bearing = filteredData.bearing` 4 个字面量赋值（命中数 = 4）；命中 ≠ 4 → fail

---

### Requirement: telemetry binary sample 字段写 cleaned 版本

`bridgeGpsToLapTiming` 函数体内 telemetry binary 写入分支（`fix-lap-binary-ts-hygiene` round 闭环后位于 line 822-861 附近的 `if (lapAnchorTs != null) { ... telemetryRepository.writeSample(...) }` 段）MUST 用入参 `gpsData`（即 cleaned 副本）的 `latitude / longitude / speed / bearing` 字段构造 `TelemetrySample`。

`tsDeltaMs` 计算公式 MUST 与 A round (`fix-lap-binary-ts-hygiene`) 闭环后保持一致：`tsDeltaMs = System.currentTimeMillis() - sessionStartTs`，其中 `sessionStartTs = telemetryRepository.activeSessionStartTs`（与 `header.startTs` 同源；本 round **不动 anchor 公式**，仅入参换为 cleaned）。

binary header / sample 编解码格式 MUST 不变（不改协议）。

**E4 加严（hotfix B 后 4 字段 binary 写 cleaned 行为锁）**：hotfix B 修回 4 字段后 `cleaned.{lat, lon, speed, bearing}` 都是 filter 输出 → binary 写入位置字段也是 cleaned；本 round MUST 在 `LapFilterIntegrationTest` 加 case 锁该行为（mimo 跳过该 case → 没有自动化 lock）。

#### Scenario: binary sample 位置字段反映 detector 看到的 cleaned 位置

- **WHEN** 喂入一段含单帧 jitter outlier 的 GPS 序列（如 R1 第一个 scenario 的 13 帧），lap mode 激活，`activeLapStartSystemTs` 已设置
- **AND** worktree 内跑端到端集成测试读出 binary sample
- **THEN** outlier 帧的 binary `lat / lon / speedKmh / bearingDeg` MUST 等于 `cleaned.lat / cleaned.lon / cleaned.speedKmh / cleaned.bearingDeg`（被 median 拉回的中位数值），**不**等于 raw 的 outlier 值

#### Scenario: 正例 — binary sample 4 字段 cleaned 锁定（E4，hotfix B 后行为锁）

- **WHEN** 喂入 1 帧 GPS `gpsData = (lat=30.0001, lon=104.0001, speed=100, bearing=90, timestamp=T)` + filter 输出 `filteredData = (lat=30.0002, lon=104.0002, speed=99.5, bearing=89.5, timestamp=T)`，构造 `cleaned = gpsData.copy(latitude=filteredData.latitude, longitude=filteredData.longitude, speed=filteredData.speed, bearing=filteredData.bearing)` 喂 bridgeGpsToLapTiming
- **AND** lap mode 激活，binary writeSample 触发
- **THEN** binary sample 字段：`lat == 30.0002`（cleaned，非 raw 30.0001）+ `lon == 104.0002` + `speedKmh == 99.5` + `bearingDeg == 89.5`；`tsDeltaMs == System.currentTimeMillis() - sessionStartTs`（不依赖 raw 或 cleaned 的 timestamp，与 A round 契约一致）

#### Scenario: binary tsDeltaMs 仍由 raw timestamp + sessionStartTs 派生

- **WHEN** 喂入帧 `gpsData.timestamp = T_raw`，session 起点 `activeSessionStartTs = T_start`
- **THEN** binary 中该帧 `tsDeltaMs` MUST 等于 `System.currentTimeMillis() - T_start`（注意 `bridgeGpsToLapTiming` 内 `tsDeltaMs` 用 `System.currentTimeMillis()` 不是 `gpsData.timestamp`；这是 A round 闭环契约，本 round 不动）

#### Scenario: 反例—— binary 误写 raw lat 而不是 cleaned

- **WHEN** 实施时把 `bridgeGpsToLapTiming(cleaned)` 误写成 `bridgeGpsToLapTiming(gpsData)`（即 collect block 没真正接通 cleaned，rawGpsData 直传 bridge），但 cleaned 副本仍构造放着不用
- **THEN** 源码 grep gate `grep -n "bridgeGpsToLapTiming(gpsData)" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` MUST 0 命中（baseline 1 命中 line 347；apply 后唯一调用是 `bridgeGpsToLapTiming(cleaned)`）；contract test 断言「outlier 帧 binary lat == cleaned.lat」MUST fail（防止 binary 与 detector 看到的轨迹解耦）

> **设计 note**：bridge 函数签名内变量名永远是 `gpsData`（函数参数名），无论 collect 传入 raw 还是 cleaned，函数体内 `lat = gpsData.latitude` 不变。源码 grep gate 真正能 catch 的回退点是**调用现场**（collect block 内 `bridgeGpsToLapTiming(...)` 的实参）而非函数体内字段引用。这是 v3 高频盲点 #7 的 mitigation：grep gate 必须 anchor 在 baseline 命中数 ≥ 1 → apply 后 0 命中（或反之）的真实变化路径上，trivially pass 的 gate 无保护价值
