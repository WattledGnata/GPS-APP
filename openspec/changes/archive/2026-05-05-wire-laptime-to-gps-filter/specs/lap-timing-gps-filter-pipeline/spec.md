## ADDED Requirements

### Requirement: 圈速通道 GPS Filter 接通契约

`feature/test/.../viewmodel/TestSessionViewModel.kt` 内 `viewModelScope.launch { gpsDataViewModel.gpsData.collect { gpsData -> ... } }` 块中喂入 `bridgeGpsToLapTiming` 的 GPS 数据 MUST 经 `core/domain/.../usecase/GpsDataFilter.process(gpsData)` 滤波后构造的 cleaned 副本，**仅替换** `latitude / longitude / speed / bearing` 四个字段；`timestamp / isTimeSynced` 等元信息字段 MUST 保留 raw（`GpsDataFilter` 不滤时间字段，filter 输出的 `timestamp` 与 raw 一致直传）。

实施层面 MUST 满足：

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

#### Scenario: 单帧 jitter outlier 不触发 WrongDirection

- **WHEN** 构造 8 帧正常前进（lat 单调增 0.0001 / 帧 + speed 100 km/h + bearing 90° + ts 间隔 40ms）+ 第 9 帧位置突变（lat 偏移 +1°，等价 ~111km 跳跃，模拟单帧 GPS 错点；**reported speed 仍 = 100 km/h** 不突变，让 v_implied / v_reported >> 3.0 触发 `isPositionAnomaly = true`），后续 4 帧恢复正常前进
- **AND** 把整段 13 帧依次喂 `GpsDataFilter`，每帧用 cleaned 副本喂 `LapTimingEngine.processSample`（previousSample 由 detector 内部维护）
- **THEN** detector 在第 9 帧 MUST NOT 输出 `reason = WrongDirection`（具体可能输出 `NoIntersection` / 正常 `accepted = true` / 其他非 invalidating reason 等多种合法路径，但**不应**触发 invalidating reason；因 9 帧 median 把 outlier 的 lat 拉回中位数 → cleaned 帧位置接近正常前进序列 → 矢量方向不构成强反向特征）

#### Scenario: lap duration 不受 filter ~160ms 滞后影响

- **WHEN** 构造一段完整圈数据：开圈过 startfinish gate（直线段，t = 0ms，speed = 200 km/h）→ 100 帧 racing line（4s）→ 闭圈过 startfinish gate（直线段，t = 4000ms，speed = 200 km/h）
- **AND** 分别用 raw 直喂 detector 与「filter → cleaned 替换 → 喂 detector」两条路径跑一遍
- **THEN** 两条路径产出的 `lap_duration` 差值 MUST < 50ms（开/闭圈 filter 滞后量相等，过线点速度变化平稳时相减抵消；50ms 是直线段速度微扰最坏估计上界）

#### Scenario: 反例—— 误把 timestamp 一并替换为 filtered.timestamp 退化圈时插值

- **WHEN** 实施时把 `cleaned = gpsData.copy(latitude = ..., longitude = ..., speed = ..., bearing = ..., timestamp = filteredData.timestamp)` 写错（`timestamp = filteredData.timestamp`）
- **THEN** 测试断言 `cleaned.timestamp == gpsData.timestamp` MUST fail（filter 不滤时间，但显式赋值会让 timestamp 来自 filter 内部计算路径；contract gate 防止"未来 filter 改成滤时间戳"无声破坏 detector 插值精度）

---

### Requirement: anomaly 帧不丢点契约

filter 输出 `isAnomaly == true` 或 `isPositionAnomaly == true` 时，`bridgeGpsToLapTiming` MUST NOT 跳过该帧，cleaned 副本仍喂 detector。`bridgeGpsToLapTiming` 函数体内 MUST NOT 出现 `if (filtered.isAnomaly) return` / `if (filtered.isPositionAnomaly) return` / `?.takeIf { !it.isAnomaly }` 等任何 anomaly skip 分支。

detector 收到的连续帧 `timestampMillis` 间隔 MUST 与 raw 一致（filter 不改 timestamp + 不 skip 帧 → detector 时序无 gap）。

#### Scenario: 连续 5 帧 isAnomaly = true detector 仍收 5 帧样本

- **WHEN** 构造 5 帧连续位置-速度不一致序列（每帧 reported speed = 50 km/h 但 Δd/Δt 计算出 v_implied = 200 km/h，比值 4 > 阈值 3.0 触发 `isPositionAnomaly = true`）
- **AND** 喂 `GpsDataFilter` 后取 cleaned 喂 detector（previousSample 由 detector 内部维护）
- **THEN** detector 在该 5 帧上 MUST 收到 5 个 `processSample` 调用（不丢点；调用前后 prev/cur 时间戳间隔等于 raw 帧间隔）

#### Scenario: bearing 跨 0°/360° 边界 detector 看到的方向矢量不出现 180° 中点伪影

- **WHEN** 构造 9 帧 bearing 序列：[355°, 357°, 359°, 1°, 3°, 5°, 7°, 9°, 11°]（北向跨越 0°/360° 边界）
- **AND** 喂 `GpsDataFilter` 取 cleaned bearing
- **THEN** 第 5 帧（窗口中心）cleaned bearing MUST 落在 `[355°, 360°] ∪ [0°, 11°]` 区间（不出现 178° / 180° 之类的中点伪影；filter 内 bearing 用 `atan2(mean(sin), mean(cos))` 循环均值处理）

#### Scenario: 反例—— 实施时加 `if (filteredData.isAnomaly) return` 跳过 anomaly 帧

- **WHEN** 实施时在 collect block 加 `val filteredData = gpsDataFilter.process(gpsData); if (filteredData.isAnomaly || filteredData.isPositionAnomaly) return; val cleaned = ...`
- **THEN** 源码 grep gate `grep -n "if (filteredData.isAnomaly\|filtered.isAnomaly" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` MUST 0 命中；contract test 断言「连续 5 帧 isAnomaly=true → detector 收 5 个 processSample 调用」MUST fail

---

### Requirement: filter warmup 期 detector 行为契约

session 起点前 9 帧（`GpsDataFilter` 滚动窗口未填满期）`bridgeGpsToLapTiming` MUST 不出现 `NullPointerException` / `IndexOutOfBoundsException` / 状态污染等异常。filter 内置 fallback 行为（窗口不足时输出当前 raw 速度等）由 `GpsDataFilter` 保证；ViewModel 层 MUST NOT 加额外 warmup 守卫（不引入帧计数器、不在前 9 帧切回 raw、不在 filter 接通后再加 mode 分支）。

#### Scenario: warmup 期前 10 帧 detector 调用次数符合 bridge 三段式守卫

- **WHEN** session 起点开始 ticker 推进 10 帧 GPS 数据（filter 窗口从 0 帧填到 10 帧；前 9 帧填满 windowSize=9）
- **AND** 每帧经 `gpsDataFilter.process` 后 cleaned 喂 `bridgeGpsToLapTiming`
- **THEN** detector `LapTimingEngine.processSample` MUST 收到 9 次调用（首帧走 bridge line 801「`previousSample == null` 首样本分支」**不调** detector，其余 9 帧每帧 1 次；这是 baseline `bridgeGpsToLapTiming` 三段式守卫语义，参 `EndToEndLapTimingContractTest.kt:571-574` 注释），无任何 throw

#### Scenario: 反例—— 实施时在 ViewModel 加帧计数器跳前 9 帧 filter

- **WHEN** 实施时在 collect block 加 `var warmupFrameCount = 0; if (warmupFrameCount < 9) { bridgeGpsToLapTiming(gpsData); warmupFrameCount++; return; }`
- **THEN** 源码 grep gate `grep -n "warmupFrameCount\|warmup" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` MUST 0 命中（warmup 行为是 filter 内置责任，ViewModel 不重复守卫）

---

### Requirement: LAP_INVALIDATED 去抖阈值降至 1 契约

`feature/test/.../usecase/LapLiveStateDeriver.kt` 内 `private const val LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` MUST 等于 `1`（filter 接通后 jitter 已从数据流根因消除，去抖阈值不再需要兜底；保留 `LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1_000L` 仅作"窗口内最多弹一次 banner"的节流）。

`LapLiveStateDeriver.derive` 函数 MUST 在窗口内累计 `≥ 1` 个 invalidating event（`CrossingReason.WrongDirection` / `UnexpectedGateOrder` / `TooSlow`）时触发 `lapInvalidatedAt` 状态。

filter 接通契约（前文 R1）与本去抖契约 MUST **同 commit 落地**（不分阶段；filter 接通即"jitter 不再产生 invalidating event"，阈值仍为 3 是死代码兜底，必须同步降）。

#### Scenario: 1 个 invalidating event 在 1 秒窗口内即触发 LAP_INVALIDATED banner

- **WHEN** `LapLiveStateDeriver.derive` 接收的 lap session 在 `currentTimeMs` 前 500ms 出现 1 个 `CrossingReason.WrongDirection` event
- **THEN** 派生的 `LapLiveState.lapInvalidatedAt` MUST 不为 null（banner 应触发）

#### Scenario: 0 个 invalidating event 不触发 banner

- **WHEN** lap session 在 `currentTimeMs` 前 1 秒窗口内没有 invalidating event（仅 `NoIntersection` 心跳）
- **THEN** 派生的 `LapLiveState.lapInvalidatedAt` MUST 为 null

#### Scenario: 反例—— 阈值未降仍为 3 时单次真反向冲线 banner 不弹

- **WHEN** 实施时遗漏 `LapLiveStateDeriver.kt:60` 的常量修改（仍为 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3`）
- **THEN** 测试断言「1 个 invalidating event → lapInvalidatedAt != null」MUST fail（这正是 contract test 抓回退的关键反例 case）；同时源码 grep gate `grep -n "LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3" feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt` MUST 0 命中（防字面值回退）

---

### Requirement: telemetry binary sample 字段写 cleaned 版本

`bridgeGpsToLapTiming` 函数体内 telemetry binary 写入分支（`fix-lap-binary-ts-hygiene` round 闭环后位于 line 822-861 附近的 `if (lapAnchorTs != null) { ... telemetryRepository.writeSample(...) }` 段）MUST 用入参 `gpsData`（即 cleaned 副本）的 `latitude / longitude / speed / bearing` 字段构造 `TelemetrySample`。

`tsDeltaMs` 计算公式 MUST 与 A round (`fix-lap-binary-ts-hygiene`) 闭环后保持一致：`tsDeltaMs = System.currentTimeMillis() - sessionStartTs`，其中 `sessionStartTs = telemetryRepository.activeSessionStartTs`（与 `header.startTs` 同源；本 round **不动 anchor 公式**，仅入参换为 cleaned）。

binary header / sample 编解码格式 MUST 不变（不改协议）。

#### Scenario: binary sample 位置字段反映 detector 看到的 cleaned 位置

- **WHEN** 喂入一段含单帧 jitter outlier 的 GPS 序列（如 R1 第一个 scenario 的 13 帧），lap mode 激活，`activeLapStartSystemTs` 已设置
- **AND** worktree 内跑端到端集成测试读出 binary sample
- **THEN** outlier 帧的 binary `lat / lon / speedKmh / bearingDeg` MUST 等于 `cleaned.lat / cleaned.lon / cleaned.speedKmh / cleaned.bearingDeg`（被 median 拉回的中位数值），**不**等于 raw 的 outlier 值

#### Scenario: binary tsDeltaMs 仍由 raw timestamp + sessionStartTs 派生

- **WHEN** 喂入帧 `gpsData.timestamp = T_raw`，session 起点 `activeSessionStartTs = T_start`
- **THEN** binary 中该帧 `tsDeltaMs` MUST 等于 `System.currentTimeMillis() - T_start`（注意 `bridgeGpsToLapTiming` 内 `tsDeltaMs` 用 `System.currentTimeMillis()` 不是 `gpsData.timestamp`；这是 A round 闭环契约，本 round 不动）

#### Scenario: 反例—— binary 误写 raw lat 而不是 cleaned

- **WHEN** 实施时把 `bridgeGpsToLapTiming(cleaned)` 误写成 `bridgeGpsToLapTiming(gpsData)`（即 collect block 没真正接通 cleaned，rawGpsData 直传 bridge），但 cleaned 副本仍构造放着不用
- **THEN** 源码 grep gate `grep -n "bridgeGpsToLapTiming(gpsData)" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` MUST 0 命中（baseline 1 命中 line 347；apply 后唯一调用是 `bridgeGpsToLapTiming(cleaned)`）；contract test 断言「outlier 帧 binary lat == cleaned.lat」MUST fail（防止 binary 与 detector 看到的轨迹解耦）

> **设计 note**：bridge 函数签名内变量名永远是 `gpsData`（函数参数名），无论 collect 传入 raw 还是 cleaned，函数体内 `lat = gpsData.latitude` 不变。源码 grep gate 真正能 catch 的回退点是**调用现场**（collect block 内 `bridgeGpsToLapTiming(...)` 的实参）而非函数体内字段引用。这是 v3 高频盲点 #7 的 mitigation：grep gate 必须 anchor 在 baseline 命中数 ≥ 1 → apply 后 0 命中（或反之）的真实变化路径上，trivially pass 的 gate 无保护价值
