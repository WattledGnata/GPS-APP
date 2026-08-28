## ADDED Requirements

### Requirement: LapSessionDetailScreen 独立 NavHost 路由

新建 `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt` MUST 是 Records/Laps 历史 session 的 Overview 屏。

`TrackTechAppShell.NavHost` MUST 加 `composable("lap_session_detail/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType }))` 路由。

入口 1：`RecordsHomeScreen.LapsView` 的 SESSION HISTORY 列表行点击 → `navController.navigate("lap_session_detail/${session.sessionId}")`。

入口 2：HOLD TO END 后 Snackbar action `View Record` → 同路由跳转，sessionId 为刚保存的 session。

#### Scenario: NavHost 加 lap_session_detail 路由

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 NavHost block
- **THEN** 含 `composable("lap_session_detail/{sessionId}", ...) { ... }` 一处
- **AND** 路由 arguments 含 `navArgument("sessionId") { type = NavType.StringType }` 或等价声明

#### Scenario: lap_session_detail 路由下 bottom nav 不可见

- **GIVEN** 用户从 Records LAPS 视图 SESSION HISTORY 行点击进入 detail
- **WHEN** Shell recomposition 后渲染 Scaffold
- **THEN** bottom nav 不可见（`currentRoute starts with "lap_session_detail"` ≠ `"home"`，showBottomNav = false）

### Requirement: LapSessionDetailScreen 必须展示字段

`LapSessionDetailScreen` MUST 展示以下字段（一期 Overview，**不**做 tabs）：

| 字段 | 来源 |
|---|---|
| Track name | 一期 fallback `currentSelectedTrack.value?.name`（单赛道；多赛道时 `TelemetrySessionEntity` 加 trackId 字段，follow-up） |
| Session date·time | `TelemetrySession.startTs` 格式化为 `yyyy-MM-dd HH:mm` |
| Best lap | 从 `telemetryRepository.getCrossings(sessionId)` 返回的 crossingEvents 派生（最小 accepted lap 时长） |
| Total laps | accepted start/finish crossingEvents 数量 |
| Valid laps | accepted = true 的 lap completion 数 |
| Invalid laps | accepted = false 的 lap completion 数 |
| Lap records list | 每条含 Lap N / Time / Diff / Status |

#### Scenario: 必须展示字段全部命中

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep section / label 字面量
- **THEN** 命中：`Track` / `Best Lap` / `Total Laps` / `Valid Laps` / `Invalid Laps` / `Lap Records` 等关键字面量（可用大写 / 标题形式 / 中英文都接受，但 7 个字段都要有可识别 UI 渲染点）

### Requirement: 可选展示字段（Top speed / Duration / Distance）

`LapSessionDetailScreen` MUST 展示以下可选字段（数据可派生时一期都展示；不可派生时显示 `--`）：

- **Top speed**：从 `TelemetryRepository.readPerformanceSamples(filePath)` 或 `readLapSamples(...)` 拿全部 sample，取 `maxOf { it.speedKmh }`
- **Duration**：`TelemetrySession.endTs - TelemetrySession.startTs`，格式 `HH:mm:ss` 或 `M:SS`
- **Distance**：session samples 的 lat/lon 累计直线距离（一期简化算法），格式 `X.XX km`

#### Scenario: Top speed / Duration / Distance 展示位置

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep `Top Speed` / `Duration` / `Distance`（label 字面量）
- **THEN** 三个字面量全部命中
- **AND** 在 Overview 区域作为辅助 metric tile 或副信息展示

#### Scenario: 数据缺失时显示占位符

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 内 Top Speed / Duration / Distance 渲染
- **WHEN** 阅读 value 派生逻辑
- **THEN** 当 binary file 不存在 / sample 为空 / endTs == startTs 时，对应字段显示 `--` 或等价占位文本

### Requirement: Lap Records List 列结构与状态

Lap Records List MUST 是 LapSessionDetailScreen 的主体内容。

每条 lap row MUST 含 4 列（一期）：

- **Lap**：圈号（1-based）
- **Time**：圈耗时 `M:SS.mmm`（INVALID/INCOMPLETE 时显示 `--:--.---`）
- **Diff**：与 best 的差值 `+0.42 s` / `-0.18 s`（best 圈显示 `BEST` 文字而非 diff，0.0 也可显示 `0.000`）
- **Status**：`BEST` / `VALID` / `INVALID` / `INCOMPLETE`

INVALID 圈 MUST 展示原因（如 `GPS LOST` / `PIT` / `MANUAL END`），来自 `crossingEvent.reason` 字段（baseline 已有）。

可选第 5 列（数据可靠时）：**Top Speed**（每圈 max sample speed）。

#### Scenario: Lap row 4 列字面量

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep table header 或 row 渲染逻辑
- **THEN** 含 `Lap` / `Time` / `Diff` / `Status` 4 个 label 字面量

#### Scenario: 状态分类全覆盖

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep status 字面量
- **THEN** 含 `BEST` / `VALID` / `INVALID` / `INCOMPLETE` 4 个字面量

#### Scenario: Best 圈视觉突出

- **GIVEN** 实施后 lap row 渲染
- **WHEN** 阅读 best 圈的视觉样式
- **THEN** 含与其他 row 不同的视觉强化（如紫色 accent / 加粗 / 描边等）

#### Scenario: INVALID 圈展示 reason

- **GIVEN** 实施后 lap row 渲染逻辑
- **WHEN** 阅读 INVALID 状态的 row 内容
- **THEN** 含读取 `crossingEvent.reason` 或等价字段并渲染（如 `INVALID · GPS LOST`）

### Requirement: LapSessionDetailScreen MUST NOT 展示项

`LapSessionDetailScreen` MUST NOT 展示以下信息（一期不做）：

- Theoretical best
- S1 / S2 / S3 / sector 矩阵
- Chart tab / 图表
- Map tab / 轨迹图
- Video tab / 视频
- Lap-vs-lap comparison

#### Scenario: 不展示 sector / theoretical / chart / map / video

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep `theoretical\|sector\|chart\|map\|video\|S1\|S2\|S3`（部分英文/中文 label）
- **THEN** 这些字面量在 LapSessionDetailScreen 中**不**作为可见 UI 元素出现（fallback 文字 / 历史注释 / variable 命名内出现可接受，但 UI label 不出现）

### Requirement: 真数据接入仅通过 TelemetryRepository public API

本 round MUST：

- **Lap records list**：通过 `TelemetryRepository.getCrossings(sessionId)` 真数据派生（D13 新增 public API）
- **Best lap / Total laps / Valid laps / Invalid laps**：从 `getCrossings` 返回的 `TelemetryCrossingEvent` 列表派生
- **Track name / Session date·time**：通过 `TelemetryRepository.getSession(sessionId)` 真数据（baseline A56 已有 public API）
- **Top speed / Duration / Distance**：D8 派生真数据；缺失时 `--`

`LapSessionDetailScreen` MUST NOT 直接访问 `CrossingEventDao` / `TelemetrySessionDao`（它们是 `TelemetryRepository` 的 private constructor dependency）。所有数据加载通过 `koinInject<TelemetryRepository>()` + public 方法。

`RecordsHomeScreen.LapsView` 的 SESSION HISTORY 列表数据源 MUST 通过 `TelemetryRepository.getRecentLapSessions(limit = 10)` 获取，**不**直接调 DAO。

baseline `RecordsHomeScreen.placeholderTrackRecord` 已由 `enhance-track-presentation` 真实化（消费 `currentSelectedTrack`）；本 round MUST NOT 重做或回退这个改动。

#### Scenario: detail 屏通过 repository public API 加载

- **GIVEN** 实施后 `LapSessionDetailScreen.kt` 源码
- **WHEN** grep `TelemetryRepository\|getCrossings\|getSession\|getRecentLapSessions`
- **THEN** 至少 2 处命中（getCrossings 加载 crossings + getSession 加载 metadata）
- **AND** **不**含 `crossingDao\.\|sessionDao\.` 直接访问 DAO 字面量（DAO 是 repository 的 private 依赖）

#### Scenario: Records LAPS SESSION HISTORY 通过 repository 加载

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 内 `LapsView` 的 SESSION HISTORY 数据源
- **WHEN** grep `getRecentLapSessions\|TelemetryRepository`
- **THEN** 至少一处命中（用 repository public API 加载 sessions）
- **AND** baseline `placeholderLapSessions` 列表 hardcoded 数据替换为真数据派生

#### Scenario: 不直接访问 DAO

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 .kt 文件
- **WHEN** grep `TelemetrySessionDao\|CrossingEventDao` import
- **THEN** 零命中（UI 层禁止 import DAO；通过 repository 边界访问）

### Requirement: TelemetryRepository public API 新增

`core/data/.../repository/TelemetryRepository.kt` MUST 新增两个 public suspend 方法：

```kotlin
suspend fun getCrossings(sessionId: String): List<TelemetryCrossingEvent>
suspend fun getRecentLapSessions(limit: Int = 10): List<TelemetrySession>
```

实现：
- `getCrossings`：内部调 `crossingDao.queryBySessionId(sessionId)`，把 `CrossingEventEntity` 映射为 domain `TelemetryCrossingEvent`
- `getRecentLapSessions`：内部调 `sessionDao.queryAll()`，filter `sessionType == TelemetrySessionType.LAP_SESSION.name`，take limit，映射为 domain `TelemetrySession`

#### Scenario: getCrossings public API 命中

- **GIVEN** 实施后 `TelemetryRepository.kt` 源码
- **WHEN** grep `suspend fun getCrossings`
- **THEN** 命中函数定义
- **AND** 返回类型 `List<TelemetryCrossingEvent>`（domain model，非 entity）

#### Scenario: getRecentLapSessions public API 命中

- **GIVEN** 实施后 `TelemetryRepository.kt` 源码
- **WHEN** grep `suspend fun getRecentLapSessions`
- **THEN** 命中函数定义
- **AND** 含 `limit: Int = 10` 参数（默认值 10）
- **AND** 返回类型 `List<TelemetrySession>`（domain model）
- **AND** body 含 `sessionType == TelemetrySessionType.LAP_SESSION.name` 或等价 filter

### Requirement: HOLD TO END 后 Snackbar 反馈 + View Record action

HOLD TO END 长按完成后 UI MUST 按以下流程触发（**先 popBackStack 回 home，再让 Shell scope 显示 Snackbar**，避免用户停留在已结束的 live 屏 10 秒）：

1. `LapLiveScreen` 内调 `val result = sessionViewModel.finishActiveLapSession()`（D12 public suspend API），await 返回 `LapSessionSaveResult?`
2. `result != null` 时通过 EventBus / SharedFlow / Shell-level state 把 `LapSessionSaveResult` 传给 `TrackTechAppShell`（实现选项：复用 `TrackTechEventBus` 加 `LapSessionSavedEvent` SharedFlow，或新建 `LapSessionSaveBus`；apply 拍板）
3. `LapLiveScreen` **立刻** `navController.popBackStack()` 回 home（不等 Snackbar）
4. `TrackTechAppShell` 在 Shell-level `LaunchedEffect` collect 该事件，在 Shell `coroutineScope` 内调 `snackbarHostState.showSnackbar("Lap session saved · ${result.lapCount} laps", actionLabel = "View Record", duration = SnackbarDuration.Long)`
5. 用户点 Snackbar `View Record` action → Shell scope 调 `navController.navigate("lap_session_detail/${result.sessionId}")`
6. 用户不点 action / Snackbar 自动 dismiss → 用户已在 home，无需额外动作

Snackbar duration MUST 为 `SnackbarDuration.Long`（约 10s），让用户有足够时间点 action；但 **MUST NOT** 让 LapLiveScreen 等待 Snackbar dismiss 才 popBackStack（用户结束后立即回 home）。

**MUST NOT** 在 Snackbar 流程中调用 baseline private `endActiveLapSession()`（不返回 sessionId/lapCount，无法驱动 Snackbar）。

#### Scenario: Shell 内 SnackbarHost

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** grep `SnackbarHostState\|SnackbarHost`
- **THEN** 至少一处命中
- **AND** SnackbarHost 渲染在 `Scaffold(snackbarHost = ...)` 内

#### Scenario: 保存反馈含 X laps + View Record action

- **GIVEN** 实施后 LapLiveScreen HOLD TO END 完成后的 Snackbar 触发逻辑
- **WHEN** 阅读 Snackbar 内容
- **THEN** 含 `Lap session saved` 或等价文案
- **AND** 含 actionLabel = `View Record` 或等价
- **AND** SnackbarDuration 为 Long

#### Scenario: View Record 跳转到正确 session

- **GIVEN** 用户 HOLD TO END 后 Snackbar 显示
- **WHEN** 用户点 `View Record` action
- **THEN** 触发 `navController.navigate("lap_session_detail/${savedSessionId}")`，sessionId 是刚保存的 session
