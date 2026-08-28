## ADDED Requirements

### Requirement: LapLiveState 数据类与 AbnormalState enum

新建 `feature/test/.../usecase/LapLiveStateDeriver.kt` MUST 定义：

```kotlin
data class LapLiveState(
    val currentLapTimerMs: Long?,    // 当前圈实时计时（从最近 start/finish 过线开始）；null = 尚未开始有效圈
    val lastLapTimeMs: Long?,         // 上一圈完成时间（毫秒）；null = 尚未完成首圈
    val bestLapTimeMs: Long?,         // session 内最佳圈时间；null = 同上
    val deltaToBestMs: Long?,         // current vs best 差值（绝对值；正负由 sign 隐含表示）；null = 无 best 参考
    val currentLapNumber: Int,         // 当前圈号，>= 1
    val abnormalState: AbnormalState?, // 异常状态优先级最高；null = 正常
)

enum class AbnormalState {
    GPS_SIGNAL_LOST,
    WAITING_FOR_GPS_LOCK,
    BLE_DISCONNECTED,
    LAP_INVALIDATED,
}
```

#### Scenario: LapLiveState 6 个字段

- **GIVEN** 实施后 `LapLiveStateDeriver.kt` 源码
- **WHEN** 阅读 `data class LapLiveState(...)` 定义
- **THEN** 含 6 个字段：`currentLapTimerMs: Long?` / `lastLapTimeMs: Long?` / `bestLapTimeMs: Long?` / `deltaToBestMs: Long?` / `currentLapNumber: Int` / `abnormalState: AbnormalState?`

#### Scenario: AbnormalState 4 个枚举值

- **GIVEN** 实施后 `LapLiveStateDeriver.kt` 源码
- **WHEN** 阅读 `enum class AbnormalState`
- **THEN** 含 4 个枚举值：`GPS_SIGNAL_LOST` / `WAITING_FOR_GPS_LOCK` / `BLE_DISCONNECTED` / `LAP_INVALIDATED`

### Requirement: LapLiveStateDeriver.derive 纯函数派生

`LapLiveStateDeriver.derive(...)` MUST 是纯函数 object 方法（无副作用、无 IO、无 ViewModel 依赖），输入：

- `session: LapSession?`（baseline `feature/test/model/laptiming/LapSession`）
- `currentTimeMs: Long`（System.currentTimeMillis() 或 telemetry session anchor）
- `gpsData: GpsData`（核心字段：`satelliteCount`、`hdop`）
- `connectionState: ConnectionState`
- `dataQuality: DataQuality`（核心字段：`dataAge`）

输出：`LapLiveState`。

#### Scenario: 派生函数签名

- **GIVEN** 实施后 `LapLiveStateDeriver.kt` 源码
- **WHEN** 阅读 `object LapLiveStateDeriver` 内 `fun derive(...)` 签名
- **THEN** 含上述 5 个参数（参数顺序不限，但 5 个全有）
- **AND** 返回类型 `LapLiveState`

### Requirement: 异常状态优先级派生

`derive` 内异常状态判断 MUST 按以下优先级（高到低）：

1. `connectionState != ConnectionState.CONNECTED` → `BLE_DISCONNECTED`
2. `dataQuality.dataAge > 1000ms` → `GPS_SIGNAL_LOST`（或如果 satelliteCount = 0 也属于此类）
3. `gpsData.satelliteCount < 6` → `WAITING_FOR_GPS_LOCK`
4. session 内最近一次 crossingEvent.accepted = false → `LAP_INVALIDATED`
5. 上述都不满足 → `abnormalState = null`（正常）

#### Scenario: BLE 未连接 abnormalState

- **GIVEN** 调用 `derive(session = ..., connectionState = ConnectionState.DISCONNECTED, ...)`
- **WHEN** 读返回值
- **THEN** `abnormalState == AbnormalState.BLE_DISCONNECTED`

#### Scenario: GPS 数据陈旧 abnormalState

- **GIVEN** 调用 `derive(session = ..., connectionState = CONNECTED, dataQuality = DataQuality(dataAge = 1500), gpsData = (satelliteCount = 8, hdop = 1.5), ...)`
- **WHEN** 读返回值
- **THEN** `abnormalState == AbnormalState.GPS_SIGNAL_LOST`

#### Scenario: 卫星数不足 abnormalState

- **GIVEN** 调用 `derive(session = ..., connectionState = CONNECTED, dataQuality = (dataAge = 500), gpsData = (satelliteCount = 4, hdop = 1.5), ...)`
- **WHEN** 读返回值
- **THEN** `abnormalState == AbnormalState.WAITING_FOR_GPS_LOCK`

#### Scenario: 正常状态 abnormalState = null

- **GIVEN** 调用 `derive(session = ..., connectionState = CONNECTED, dataQuality = (dataAge = 500), gpsData = (satelliteCount = 8, hdop = 1.5), ...)`
- **WHEN** 读返回值
- **THEN** `abnormalState == null`

### Requirement: currentLapTimer / bestLap / lastLap / deltaToBest 派生规则

`LapLiveStateDeriver.derive` MUST 按以下规则派生 5 个核心字段：

**currentLapTimerMs**：

- 如果 `session == null` 或 session 内无 accepted start/finish crossingEvent → `null`
- 否则：从最近一次 accepted start/finish crossing 的 `crossingTimestampMs` 到 `currentTimeMs` 的毫秒差

**lastLapTimeMs**：

- 如果 session 内 accepted 的 lap completion crossings < 1 → `null`
- 否则：最近一次完成的 lap 时长（即倒数第 1 与倒数第 2 个 accepted crossingEvent 之间的差）

**bestLapTimeMs**：

- 如果 session 内已完成 accepted lap 数 < 1 → `null`
- 否则：所有 completed accepted lap 时长里的最小值；INVALID lap（accepted = false）**MUST 跳过**，不参与 best 计算

**deltaToBestMs**：

- 如果 `bestLapTimeMs == null` 或 `currentLapTimerMs == null` → `null`
- 否则：`currentLapTimerMs - bestLapTimeMs`（正数 = 当前慢于 best；负数 = 当前快于 best）

**currentLapNumber**：

- 公式：`max(1, session?.currentLapIndex ?: 0)`
- baseline `LapSession.currentLapIndex` 语义（A56 已落地，参见 `TestSessionViewModelTrackLapTest` 断言）：
  - 0 = 还未跨过 start/finish（session 刚开始）
  - 1 = 已跨 1 次 start/finish（**第 1 圈进行中**）
  - 2 = 已跨 2 次 start/finish（**第 2 圈进行中**，第 1 圈已完成）
  - N = 已跨 N 次 start/finish（**第 N 圈进行中**）
- 派生映射：currentLapIndex `0/1` → LAP `1`；currentLapIndex `2` → LAP `2`；currentLapIndex `N` → LAP `N`（N ≥ 1）
- session = null（未开始）→ LAP `1`

#### Scenario: 未开始 session（session = null）

- **GIVEN** 调用 `derive(session = null, ...)`
- **WHEN** 读返回值
- **THEN** `currentLapTimerMs == null`
- **AND** `lastLapTimeMs == null`
- **AND** `bestLapTimeMs == null`
- **AND** `deltaToBestMs == null`
- **AND** `currentLapNumber == 1`

#### Scenario: 第 1 圈过程中（首次 start/finish 已过线，未完成）

- **GIVEN** session 含 1 个 accepted start/finish crossingEvent 在 t=1000，`session.currentLapIndex == 1`，currentTimeMs = 1500
- **WHEN** 调用 `derive(session, currentTimeMs = 1500, ...)`
- **THEN** `currentLapTimerMs == 500`
- **AND** `lastLapTimeMs == null`（未完成首圈）
- **AND** `bestLapTimeMs == null`
- **AND** `deltaToBestMs == null`
- **AND** `currentLapNumber == 1`（max(1, 1) = 1，第 1 圈进行中）

#### Scenario: 第 1 圈过线之前（currentLapIndex = 0）

- **GIVEN** session 已开（非 null）但 currentLapIndex = 0（尚未跨过 start/finish）
- **WHEN** 调用 `derive(session, ...)`
- **THEN** `currentLapNumber == 1`（max(1, 0) = 1）
- **AND** `currentLapTimerMs == null`（无有效起点）

#### Scenario: 第 N 圈进行中（currentLapIndex = N）

- **GIVEN** session.currentLapIndex == 5（已跨 5 次 start/finish）
- **WHEN** 调用 `derive(session, ...)`
- **THEN** `currentLapNumber == 5`（max(1, 5) = 5，第 5 圈进行中）

#### Scenario: 第 1 圈完成后（第 2 圈开始）

- **GIVEN** session 含 2 个 accepted start/finish crossingEvent（t=1000, t=2200），currentTimeMs = 2500
- **WHEN** 调用 `derive(session, currentTimeMs = 2500, ...)`
- **THEN** `currentLapTimerMs == 300`（第 2 圈进行中，2500 - 2200）
- **AND** `lastLapTimeMs == 1200`（第 1 圈耗时 2200 - 1000）
- **AND** `bestLapTimeMs == 1200`（首圈即 best）
- **AND** `deltaToBestMs == -900`（300 - 1200，负数 = 当前快于 best）

#### Scenario: 第 2 圈快于第 1 圈，best 更新

- **GIVEN** session 含 3 个 accepted start/finish crossingEvent（t=1000, t=2200, t=3300），currentTimeMs = 3400
- **WHEN** 调用 `derive(session, currentTimeMs = 3400, ...)`
- **THEN** `lastLapTimeMs == 1100`（第 2 圈耗时 3300 - 2200）
- **AND** `bestLapTimeMs == 1100`（第 2 圈快于第 1 圈，更新 best）
- **AND** `currentLapTimerMs == 100`
- **AND** `deltaToBestMs == -1000`

#### Scenario: INVALID 圈跳过 best 计算

- **GIVEN** session 含 3 个 crossingEvent：t=1000 accepted（第 1 圈开始）+ t=2200 accepted（第 1 圈结束 = 1200ms）+ t=2900 **rejected**（第 2 圈过线被判 INVALID，accepted = false）
- **WHEN** 调用 `derive(session, currentTimeMs = 3000, ...)`
- **THEN** `bestLapTimeMs == 1200`（仅第 1 圈算入 best）
- **AND** INVALID 的 lap 不更新 best 或 last

#### Scenario: deltaToBest 正负 sign

- **GIVEN** `currentLapTimerMs = 1300, bestLapTimeMs = 1100`
- **WHEN** 调用 `derive(...)`
- **THEN** `deltaToBestMs == 200`（正数 = 慢于 best）

### Requirement: TestSessionViewModel 暴露 lapLiveState StateFlow

`TestSessionViewModel` MUST 暴露 `val lapLiveState: StateFlow<LapLiveState>` 给 UI 订阅，由 `combine(lapSession, gpsDataViewModel.gpsData, gpsDataViewModel.connectionState, gpsDataViewModel.dataQuality, tickerFlow)` 派生。

`tickerFlow` MUST 以 50ms（20Hz）周期 emit `Unit`，让 currentLapTimer 平滑更新。

UI 订阅 `lapLiveState` 时不需要直接拿 `LapSession` / `GpsData` —— 只通过 `lapLiveState` 一个流。

#### Scenario: TestSessionViewModel 加 lapLiveState

- **GIVEN** 实施后 `TestSessionViewModel.kt` 源码
- **WHEN** grep `lapLiveState`
- **THEN** 至少一处命中（StateFlow 字段定义）
- **AND** 该字段类型 `StateFlow<LapLiveState>`

#### Scenario: lapLiveState 用 combine 派生

- **GIVEN** 实施后 `TestSessionViewModel.kt` 内 `lapLiveState` 派生表达式
- **WHEN** 阅读派生逻辑
- **THEN** 含 `combine(...)` 调用
- **AND** combine 输入流含 `lapSession`（或等价）+ `gpsDataViewModel.gpsData` + `gpsDataViewModel.connectionState`
- **AND** combine 输入含 50ms tickerFlow 或等价周期 emit（20Hz）

### Requirement: LapLiveStateDeriverTest 单元测试

新增 `feature/test/src/test/.../usecase/LapLiveStateDeriverTest.kt` MUST 覆盖：

- 未开始 session（session = null）→ 全 null + lapNumber = 1
- 第 1 圈进行中
- 第 1 圈完成（best = last = 第 1 圈时长，delta = 0）
- 第 2 圈快于第 1 圈，best 更新
- 第 2 圈慢于第 1 圈，best 不变
- 第 2 圈进行中且当前时间快于 best，deltaToBest 为负
- INVALID 圈跳过 best 计算
- BLE_DISCONNECTED 异常
- GPS_SIGNAL_LOST 异常（dataAge > 1000）
- WAITING_FOR_GPS_LOCK 异常（satelliteCount < 6）
- 正常状态 abnormalState = null

#### Scenario: LapLiveStateDeriverTest 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `find feature/test/src/test/java/com/blazepush/feature/test/usecase -name "LapLiveStateDeriverTest.kt"`
- **THEN** 命中

#### Scenario: 测试覆盖 11 个关键场景

- **GIVEN** 实施后 `LapLiveStateDeriverTest.kt` 源码
- **WHEN** 阅读 `@Test` fun 数量
- **THEN** ≥ 11 个 `@Test` 函数（覆盖上述场景，函数名可自定义）
