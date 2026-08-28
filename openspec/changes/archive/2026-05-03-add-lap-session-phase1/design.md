## Context

baseline 的圈速执行流程是 V1 阶段的 `LapDebugExecutionScreen.kt`：竖屏 / Compose-scope 绑 recorder / 信息密度高（speed / GPS / lap list 全堆）。这个流程不符合：

- PRD"驾驶仪表"原则（一眼扫视、不展示 speed / GPS 细节）
- V2 视觉系统的强度分层（Laps 应是赛道为中心 + 实时仪表克制）
- recorder 生命周期独立性（不应绑 UI scope）

A56（unify-gps-telemetry-persistence）已落地数据层：`TelemetryRepository.startSession(LAP_SESSION) / writeSample / writeCrossing / endSession` + Room `CrossingEventDao` / `TelemetrySessionDao`。`LapTimingEngine.processSample` 纯函数已有；`TestSessionViewModel.bridgeGpsToLapTiming` 已把 GPS 帧桥接到 lap 计时。但 baseline 缺：

1. 一个独立横屏的 live 仪表屏
2. recorder 不绑 UI scope 的架构落地
3. 从 `LapSession.crossingEvents` 派生 best/last/delta 的纯函数 + UI state 暴露
4. session detail 显示历史记录

约束：
- 不引入新依赖
- 不改 V2 typography 系统（上一 round 落地）
- 不改公共 RaceChrono BLE 协议
- 不动其他 home screen 内容
- 不删除 baseline `LapDebugExecutionScreen`（保留作 transitional fallback）

## Goals / Non-Goals

**Goals:**

- 落地完整圈速 session 闭环（Laps home → start → live → end → save → detail）
- Live 屏强制横屏 / 屏幕常亮 / 返回手势拦截 / 2x2 仪表 / Delta+Current+Last+Best+Lap 五字段
- Recorder 不绑 Composable scope，配置变化 / 横竖屏重建 / 退后台不停
- 从 `crossingEvents` 派生 live state 的纯函数（可单元测试）
- Records/Laps session detail Overview 屏（含 lap records list）
- session 保存反馈 → 默认回 Laps 首页或 Records 列表 + 提供 View Record

**Non-Goals:**

- sector timing / theoretical best / chart / map / video（未来 Analysis Mode 范围）
- live session 内展示 speed / GPS 细节（违反"驾驶仪表"原则）
- live session 返回手势直接退出（必须拦截）
- 强制跳转传统 V1 `LapDebugResultScreen`（PRD 明确不强制）
- 引入完整 Foreground Service（一期最小可行方案）
- 像素级复刻视觉参考图（CC guidance：大结构 / 页面职责 / 交互路径 / 信息层级正确即可）
- 多赛道选择（一期只有 Chengdu Tianfu，不做完整 selector UI）
- 真实数据接入到 RecordsHomeScreen LAPS 视图首屏（仍是 placeholder；session detail 才接真数据）
- ComposeRule UI test（沿用前几 round 决策）

## Decisions

### D1：Live 屏路由 + 横屏锁定方式

**决定**：`LapLiveScreen` 作为顶层 NavHost 独立路由 `lap_live`，与 `test_execution` / `gps_details` 平级（**不**进 home Pager）。

`TrackTechAppShell.NavHost` 加：

```kotlin
composable("lap_live") {
    LapLiveScreen(
        navController = navController,
        sessionViewModel = sessionViewModel,
    )
}
composable(
    route = "lap_session_detail/{sessionId}",
    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
) { backStackEntry ->
    val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
    LapSessionDetailScreen(
        navController = navController,
        sessionId = sessionId,
    )
}
```

bottom nav 可见性已由 `currentRoute == "home"` 单值判定（上 round），`lap_live` / `lap_session_detail/...` 自动隐藏 bottom nav，无需改。

**横屏锁定方式**：用 **Compose `DisposableEffect`** 在 `LapLiveScreen` 进入时改 Activity `requestedOrientation`，离开时恢复：

```kotlin
@Composable
fun LapLiveScreen(...) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // ...
}
```

**为什么不用 Manifest activity android:screenOrientation="landscape"**：MainActivity 是全局 Activity，整 App 都强制横屏会影响 Test / Records / Device tab；只能 per-screen 方案。

**为什么不用单独 Activity**：增加 Activity 数量 + 多 Compose 实例 + 数据传递复杂度；side-effect 方案最轻。

**为什么不用 navigation-compose 的 `LaunchedEffect`**：`DisposableEffect` 配 `onDispose` 自动恢复（pop back 时），`LaunchedEffect` 没这个保证。

### D2：Recorder 生命周期 owner（最小可行）

**问题**：当前 `TestSessionViewModel` 在 `viewModelModule` 里 Koin 注册为 `viewModel { ... }`，绑到 NavBackStackEntry scope。但上 round 已通过 `koinViewModel<TestSessionViewModel>()` 在 `TrackTechAppShell` 顶层（Activity scope）创建，传给所有 home screen 共享 → 实际是 Activity-scoped。

进入 `lap_live` 时 navController.navigate("lap_live") 创建新 NavBackStackEntry，但 `LapLiveScreen` 的 `sessionViewModel` 通过参数传入（来自 Shell 的 Activity-scoped 实例），所以**已经满足"不绑 NavBackStackEntry scope"**。

但还有几个剩余问题：

1. **TestSessionViewModel 是 Activity-scoped 还是 Application-scoped**？  
   Activity scoped。Activity destroy（用户切到别的 App + 系统回收）会让 ViewModel onCleared，丢 session 状态。

2. **配置变化（横竖屏 / 多窗口）会重建 Activity**？  
   Android Activity 默认在 configuration changes 重建，但 `TestSessionViewModel` 通过 `ViewModelStoreOwner` 跨重建保留。配置变化不丢。

3. **退后台 / 锁屏**？  
   不杀进程。Activity 进入 onPause/onStop，ViewModel 保留，session 不停。GPS 数据流（`GpsDataViewModel.gpsData` StateFlow）也保留（Application-scoped Koin single）。bridgeGpsToLapTiming 的 collect job 也持续。

4. **GPS BLE 后台运行权限**？  
   当前项目 `BluetoothDataSource` 在 Application Class onCreate 时启动，连接持续到 Activity destroy。本 round 不动 BLE 链路。

**决定**：本 round 不引入完整 Foreground Service。最小可行方案：

- 保留现有 `TestSessionViewModel` 在 Activity scope（Koin `viewModel { ... }` 注册不变）
- 在 `LapLiveScreen` 与 `LapsHomeScreen` 用 Activity-scoped 同一 instance 共享 session（已是上 round 落地状态）
- **MUST NOT** 把 recorder / writer 状态绑到 Composable 的 `LaunchedEffect` / `DisposableEffect` —— 这些只触发 UI 副作用（横屏锁定 / 屏幕常亮），不持有 session
- session 状态仅由 `TestSessionViewModel.activeLapSessionId` + `TelemetryRepository.activeWriter` 持有
- Activity destroy（极端情况）：**一期不保证**，作为 follow-up backlog（spec/lap-session-recorder-lifecycle 已明确 MUST NOT 引入 `MainActivity.onDestroy` / `onCleared` cleanup；`viewModelScope` 在 onCleared 时被取消，await endSession 不可靠，需独立 round 加 Application-scoped scope + abnormal endTs 标记）
- **明确不做**：Foreground Service / Application-scoped session controller / SavedStateHandle 持久化

**Risks 见后**。

**为什么不用 Application-scoped 单例 SessionController**：当前 `TestSessionViewModel` 已经聚合 session 状态（包括 lap session 模式），再拆出 SessionController 会造成双状态源。Activity-scoped + GPS Application-scoped 已是合理边界。

**为什么不引入 Foreground Service**：用户明确"一期不强求完整 Foreground Service，但至少不能绑在 Composable scope 上"。Activity-scoped ViewModel 满足"不绑 Composable scope"，配置变化不丢，退后台不杀进程时不丢。系统极端杀进程的 case 一期不保证（属于 future scope）。

### D3：Live state 派生位置

**决定**：新建 `feature/test/.../usecase/LapLiveStateDeriver.kt` **纯函数 object**，从 `LapSession` + `currentTimeMs` 派生 5 个字段：

```kotlin
data class LapLiveState(
    val currentLapTimerMs: Long?,    // null 表示尚未开始有效圈
    val lastLapTimeMs: Long?,         // null 表示尚未完成首圈
    val bestLapTimeMs: Long?,         // null 同上
    val deltaToBestMs: Long?,         // null = 无 best 参考
    val currentLapNumber: Int,         // >= 1
    val abnormalState: AbnormalState?, // GPS_LOST / WAITING_GPS / BLE_DISCONNECTED / LAP_INVALIDATED / null
)

enum class AbnormalState { GPS_SIGNAL_LOST, WAITING_FOR_GPS_LOCK, BLE_DISCONNECTED, LAP_INVALIDATED }

object LapLiveStateDeriver {
    fun derive(
        session: LapSession?,
        currentTimeMs: Long,            // System.currentTimeMillis() 或 Telemetry session anchor
        gpsData: GpsData,
        connectionState: ConnectionState,
    ): LapLiveState { ... }
}
```

**为什么纯函数 object**：可单元测试（无依赖注入），关键场景全覆盖（首圈 / N 圈 / 无 best / 跨圈过线 / INVALID 跳过 / 异常状态优先级）。

**为什么不在 TestSessionViewModel 内派生**：派生函数与 ViewModel state 解耦后，`LapLiveViewModel` 或现有 `TestSessionViewModel` 都能调；测试不依赖 Mock ViewModel。

**Live state 暴露**：`TestSessionViewModel` 加 `val lapLiveState: StateFlow<LapLiveState>` —— 由 `combine(lapSession, gpsDataViewModel.gpsData, gpsDataViewModel.connectionState, tickerFlow(50ms))` 派生（50ms tick 让 currentLapTimer 平滑更新）。

**为什么 50ms tick**：太频繁（10ms）浪费；太稀疏（200ms）时间显示卡顿。50ms = 20Hz，与显示帧率匹配。

### D4：HOLD TO END 时长

**决定**：**1.5 秒**长按。

**理由**：
- 0.5s 太短，开车颠簸误触
- 3s 过长，紧急停止延迟
- 1.5s 是常见行业标准（如 iOS 删除应用确认）

**实现**：`Modifier.pointerInput(Unit) { detectTapGestures(onPress = { ... }) }` + 协程定时器：

```kotlin
var holdProgress by remember { mutableStateOf(0f) }
LaunchedEffect(isHolding) {
    if (isHolding) {
        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < HOLD_DURATION_MS) {
            holdProgress = (System.currentTimeMillis() - startMs) / HOLD_DURATION_MS.toFloat()
            delay(16)
        }
        if (isHolding) {
            holdProgress = 1f
            onEnd()
        }
    } else {
        holdProgress = 0f
    }
}
```

视觉：button 内有 progress bar 从左到右填充（1.5s 过程）。

### D5：返回手势拦截

**决定**：用 `androidx.activity.compose.BackHandler` 拦截：

```kotlin
@Composable
fun LapLiveScreen(...) {
    var showEndConfirmation by remember { mutableStateOf(false) }
    BackHandler {
        showEndConfirmation = true   // 弹出确认对话框
    }
    // ...
    if (showEndConfirmation) {
        EndConfirmationDialog(
            onConfirm = { /* end + nav back */ },
            onDismiss = { showEndConfirmation = false },
        )
    }
}
```

**为什么用确认对话框而非"提示用 HOLD TO END"**：

- 提示文案（如"长按底部按钮结束"）用户驾驶时看不清
- 确认对话框是 Android 标准防误触模式
- 对话框两个选项：`Continue` / `End Session`（前者 dismiss 对话框，后者真正结束）
- 不破坏"HOLD TO END 是主结束路径"的语义 —— 对话框只是返回手势的兜底

**为什么不用 ModalBottomSheet**：ModalBottomSheet 在横屏小屏占大比例屏幕，不适合 driving 场景。AlertDialog 居中弹出，干扰小。

### D6：赛道选择 UX（依赖 enhance-track-presentation）

**决定**：**START LAP SESSION onClick 读取 `currentSelectedTrack` 启动 lap session**，**不**做单独 selector 屏，**不**改 `CHANGE TRACK` 行为（已由 `enhance-track-presentation` 接通真实 sheet）。

**理由**：

- `enhance-track-presentation` 已建立 `currentSelectedTrack: StateFlow<Track?>` + `selectTrack(track)` 唯一真相源；本 round 复用，**不**造平行"当前 trackId"
- `CHANGE TRACK` 按钮已由 enhance 接通 `SelectTrackBottomSheet`，本 round MUST NOT 改 onClick 行为或退回 toast 占位
- 一期 PresetTrackCatalog 只有 TFIC LPCC 一条赛道，sheet 内可选项有限但行为正确
- 简化一期 UX：`START LAP SESSION` onClick → `currentSelectedTrack.value` 拿 Track → `selectLapDebugMode(track.id)` → `navigate("lap_live")`；如 `currentSelectedTrack == null`（极端情况）则保留主操作 disable 或退回 gating 提示

**LapsHomeScreen 改造（仅 START LAP SESSION onClick）**：

```kotlin
PrimaryActionPanel(
    title = "START LAP SESSION",
    onClick = {
        if (readiness.canEnterTestFlow) {
            val track = testSessionViewModel.currentSelectedTrack.value
            if (track != null) {
                testSessionViewModel.selectLapDebugMode(track.id)
                navController.navigate("lap_live")
            }
            // currentSelectedTrack == null 兜底：保持原 UX（不进入 live）
        } else {
            // 已有 gating 路径不变（onTabSelected(Device) + Toast）
        }
    },
)
```

**MUST NOT**：
- 修改 `LapsHomeScreen.CurrentTrackPanel` 的 trackName / length / 缩略图渲染（已由 enhance 接 currentSelectedTrack）
- 修改 `CHANGE TRACK` 按钮 onClick（已由 enhance 接 sheet）
- 引入 hardcoded trackId（如 `"preset-tfic-lpcc"`）作为 fallback —— enhance 已负责把默认赛道初始化到 `currentSelectedTrack`

### D7：HOLD TO END 后 navigation 路径（**LapSessionSaveBus 异步分发**，非阻塞）

**决定**（依赖 D12 finish API + 通过事件总线分发到 Shell scope 显示 Snackbar）：

```
LapLiveScreen HOLD TO END 完成
→ coroutineScope.launch {
    val result = sessionViewModel.finishActiveLapSession()  // suspend，await endSession
    if (result != null) {
        LapSessionSaveBus.emit(result)  // 推送 result 给 Shell（不阻塞）
    }
    navController.popBackStack()  // 立刻回 home，不等 Snackbar
  }

TrackTechAppShell（顶层 LaunchedEffect）
→ LapSessionSaveBus.events.collect { result ->
    val snackResult = snackbarHostState.showSnackbar(
        message = "Lap session saved · ${result.lapCount} laps",
        actionLabel = "View Record",
        duration = SnackbarDuration.Long,
    )
    if (snackResult == SnackbarResult.ActionPerformed) {
        navController.navigate("lap_session_detail/${result.sessionId}")
    }
    // 用户不点 action / Snackbar 自动 dismiss → 用户已在 home，无需额外动作
  }
```

**关键不变量**：

- LapLiveScreen **不持有** `SnackbarHostState`（不通过参数 / CompositionLocal / 共享 ViewModel 拿）
- LapLiveScreen 调 `finishActiveLapSession()` 拿到 result 后**立刻** `popBackStack()` 回 home，**MUST NOT** 等 Snackbar dismiss
- Shell-level `LaunchedEffect(Unit)` 在 `TrackTechAppShell` 顶层 collect `LapSessionSaveBus.events`，Snackbar 在 Shell scope 显示（用户已在 home）
- Snackbar 的 `View Record` action 触发 Shell scope 内 `navController.navigate("lap_session_detail/...")`

**LapSessionSaveBus 实现**（参见 tasks §5.3）：

- 选项 A（推荐，最小改动）：新建 `object LapSessionSaveBus { val events: SharedFlow<LapSessionSaveResult> ... }`
- 选项 B：复用 `TrackTechEventBus`，加 `LapSessionSavedEvent(result: LapSessionSaveResult)` 事件类型
- apply 阶段拍板；两种实现的关键不变量一致：Shell 是唯一 collector + LapLiveScreen 不阻塞

**时序保证**：`TrackTechAppShell.LaunchedEffect(Unit)` 在 App 顶层组合时即订阅，**先于**任何 LapLiveScreen 的 `LapSessionSaveBus.emit(...)`，所以 SharedFlow(replay=0) 的事件不会丢（与上一 round `TrackTechEventBus` 同样路径）。

**为什么不阻塞等 Snackbar**：

- Snackbar `Long` duration 约 10 秒，阻塞期间用户停在已结束的 live 屏 → 违背"默认回 home"契约（PRD 明确）
- 异步分发让 Live 屏立刻退出，Shell scope Snackbar 与用户已切到 home 的 UX 互不干扰

**为什么 LapLiveScreen 不持 SnackbarHostState**：

- 持有意味着 LapLiveScreen 需要 await Snackbar 才能 popBackStack —— 重新引入 P2-2 的阻塞问题
- 持有还会让 Shell scope 与 LapLiveScreen scope 同时竞争 SnackbarHostState 写入，潜在 race
- 通过事件总线让 Shell scope 单一拥有 host state，路径清晰

**为什么不直接进入 detail 页**：PRD 明确"不强制跳转传统结果页"，默认应回 Laps 首页或 Records 列表，View Record 是可选路径。

**为什么用 Snackbar 而非 Dialog**：Dialog 阻断用户，Snackbar 轻量；用户驾驶刚结束精力下降，不应阻断。

### D12：TestSessionViewModel 暴露 public suspend `finishActiveLapSession`

**问题**：baseline `TestSessionViewModel.endActiveLapSession()` 是 **private + 返回 Unit + viewModelScope.launch 异步 + 先清 activeLapSessionId**：

```kotlin
private fun endActiveLapSession() {
    val sessionId = activeLapSessionId ?: return
    activeLapSessionId = null
    activeLapStartSystemTs = null
    lastWrittenCrossingCount = 0
    viewModelScope.launch { telemetryRepository.endSession(sessionId) }
}
```

`LapLiveScreen` 既不能直接调用（private），也拿不到结束完成的 ack（fire-and-forget）和 sessionId（先清后 launch），更没有 lapCount 信息给 Snackbar 显示"Lap session saved · X laps"。

**决定**：本 round 新增 **public suspend** `TestSessionViewModel.finishActiveLapSession(): LapSessionSaveResult?`：

```kotlin
data class LapSessionSaveResult(
    val sessionId: String,
    val lapCount: Int,        // 完成的有效圈数（qualityFlags 为空的 LapRecord 数）
    val bestLapMs: Long?,     // 最佳圈毫秒，缺则 null
    val totalDurationMs: Long, // session 总时长毫秒
)

suspend fun finishActiveLapSession(): LapSessionSaveResult? {
    val sessionId = activeLapSessionId ?: return null
    val session = _lapSession.value
    // 1. 先捕获派生数据（清状态前）
    //    LapSession.completedLaps: List<LapRecord>
    //    LapRecord.durationMillis: Long
    //    LapRecord.qualityFlags: List<LapQualityFlag>，empty = 有效圈
    val validLaps = session?.completedLaps?.filter { it.qualityFlags.isEmpty() }.orEmpty()
    val lapCount = validLaps.size
    val bestLapMs = validLaps.minOfOrNull { it.durationMillis }
    val totalDurationMs = activeLapStartSystemTs?.let { System.currentTimeMillis() - it } ?: 0L
    // 2. await Repository 写 endTs
    telemetryRepository.endSession(sessionId)
    // 3. 清 ViewModel 状态
    activeLapSessionId = null
    activeLapStartSystemTs = null
    lastWrittenCrossingCount = 0
    isLapRecording = false
    _lapSession.value = _lapSession.value?.copy(status = LapSessionStatus.Finished)
    return LapSessionSaveResult(sessionId, lapCount, bestLapMs, totalDurationMs)
}
```

**baseline 字段名核实**（apply §0 grep 已确认；不再依赖 `lapRecords` / `isValid` / `lapTimeMs` 等不存在的字段）：

- `LapSession.completedLaps: List<LapRecord>`（**不是** `lapRecords`）
- `LapRecord.durationMillis: Long`（**不是** `lapTimeMs`）
- 有效圈语义：`LapRecord.qualityFlags: List<LapQualityFlag>` 为空（`qualityFlags.isEmpty()` = 无质量问题 = valid；任一 flag 出现 = invalid）

baseline private `endActiveLapSession()` MAY 保留给 `stopLapDebugSession` / `exitLapDebugMode` 等其他 caller 使用（fire-and-forget 语义对它们够用）；或者重构这些 caller 去调 `viewModelScope.launch { finishActiveLapSession() }`（design 实施时拍板，影响 scope 大小）。

**为什么不在 endActiveLapSession 内部 await**：当前 endActiveLapSession 是 fire-and-forget，把它改 suspend 会影响所有 caller（`stopLapDebugSession` / `exitLapDebugMode` 等都是 non-suspend）。新增 suspend 函数最小化影响。

**为什么先捕获再 await**：A56 落地的 `endSession()` 内部会清 `activeWriter` / 调 `sessionDao.updateEndTs`；如果先 await 再捕获，`_lapSession` 可能已被异步流程触动。先捕获保证一致性快照。

### D13：TelemetryRepository public API 加 getCrossings / getRecentLapSessions

**问题**：`TelemetryRepository.crossingDao` / `sessionDao` 都是 **private constructor dependency**。`LapSessionDetailScreen` 不能直接 `telemetryRepository.crossingDao.queryBySessionId(sessionId)` —— 编译失败 + 绕过 repository 边界。

**决定**：`TelemetryRepository` 新增两个 public suspend API：

```kotlin
class TelemetryRepository(
    private val context: Context,
    private val sessionDao: TelemetrySessionDao,
    private val crossingDao: CrossingEventDao,
) {
    // ... baseline 方法不变 ...

    /**
     * 拉指定 session 的全部 crossing 事件（按 timestamp 升序）。
     */
    suspend fun getCrossings(sessionId: String): List<TelemetryCrossingEvent> {
        return crossingDao.queryBySessionId(sessionId).map { it.toDomain() }
    }

    /**
     * 拉最近 N 个 LAP_SESSION 类型的 session metadata（按 startTs 降序）。
     */
    suspend fun getRecentLapSessions(limit: Int = 10): List<TelemetrySession> {
        return sessionDao.queryAll()
            .filter { it.sessionType == TelemetrySessionType.LAP_SESSION.name }
            .take(limit)
            .map { it.toDomain() }
    }
}

private fun CrossingEventEntity.toDomain() = TelemetryCrossingEvent(
    sessionId = sessionId,
    lapIndex = lapIndex,
    crossingTimestampMs = crossingTimestampMs,
    speedKmh = speedKmh,
    gateId = gateId,
    gateType = gateType,
    accepted = accepted,
    reason = reason,
    directionScore = directionScore,
)
```

UI 层（`LapSessionDetailScreen` / `RecordsHomeScreen.LapsView`）通过 `koinInject<TelemetryRepository>()` 拿仓库，调这两个 public 方法 —— **不**直接依赖 DAO。

**为什么不暴露 DAO**：repository 边界让数据层未来可以无侵入扩展（如缓存层 / 远程同步 / 加密），UI 不耦合 Room。

**为什么 `getRecentLapSessions(limit = 10)` 默认 10**：Records LAPS 视图 SESSION HISTORY 列表展示约 10 条最近 session 即可；扩展 limit 是参数化设计。

### D8：Top speed / Duration / Distance 一期是否展示

**决定**：**一期都展示**（PRD"数据可靠时可以"放宽到都展示，标注数据来源）。

**派生方式**：

| 字段 | 来源 |
|---|---|
| Top speed | `TelemetryRepository.readPerformanceSamples(filePath).maxOf { it.speedKmh }` 或 lap session 的 max speed sample |
| Duration | `TelemetrySession.endTs - startTs` |
| Distance | session samples 的 lat/lon 累计 great-circle 距离（一期可简化为直线累加） |

如果 binary file 不存在 / 截断 / sample count = 0 → 显示 `--`。

**为什么一期就接**：A56 数据层已就绪，session detail 是 Records tab 的主要内容，**没数据等于没价值**。Top speed / Duration / Distance 都可从已有 binary file 派生，无新数据依赖。

**Risks**：一期 distance 简化算法（直线累加 lat/lon）会与真实 GPS 路径距离有偏差（曲率不计）；high-frequency 5Hz 采样下偏差小可接受。Mitigation：commit body 标注"distance 是直线累加近似，曲率不计"。

### D9：CurrentTrack 数据源（依赖 enhance-track-presentation）

**决定**：本 round **不动** `LapsHomeScreen.CurrentTrackPanel` 的 trackName / length / 缩略图渲染逻辑 —— `enhance-track-presentation` 已建立 `TestSessionViewModel.currentSelectedTrack: StateFlow<Track?>` 真相源，CurrentTrackPanel 已消费它派生 trackName / length。

`LapsHomeScreen` 内本 round 唯一改动是 `START LAP SESSION` onClick body（D6）：读 `currentSelectedTrack.value` 拿 trackId 调 `selectLapDebugMode`。

**MUST NOT**：
- 引入 `private val DEFAULT_TRACK_ID = "preset-..."` 硬 coded fallback
- 改 `CurrentTrackPanel.trackName` / `length` / `direction` 来源
- 改 `CHANGE TRACK` 按钮 onClick（已是真实 `SelectTrackBottomSheet` 触发器）

### D10：Records/Laps SESSION HISTORY → session detail nav（依赖 enhance + D13 repository public API）

**决定**：

- `RecordsHomeScreen.LapsView.placeholderLapSessions` 改造为 **真实数据**（通过 D13 新增 public API `TelemetryRepository.getRecentLapSessions(limit = 10)`，**不**直接访问 `TelemetrySessionDao`）
- 每个 `TrackTechRow` 的 onClick 从 Toast 占位改为 `navController.navigate("lap_session_detail/${session.sessionId}")`
- `Records` tab 仍在 home Pager 内，session detail 通过顶层 NavHost 跳转（与 `lap_live` 平级）

**Records LAPS 视图首屏的其他字段（Current Track Record 卡 / 3 metric tile）**：
- baseline `placeholderTrackRecord` 已由 `enhance-track-presentation` 真实化（trackName / length 消费 `currentSelectedTrack` 派生）；本 round **MUST NOT** 改它或回退
- `CurrentTrackRecordCard` 签名扩展（`(track: Track?, record: CurrentTrackRecord)`）已由 enhance 落地；本 round 不动
- `TrackPreviewStub` 已由 enhance 删除并替换为 `TrackThumbnail`；本 round 不动
- 跨 session 统计聚合（best lap aggregation / sessions count / total laps 真值，非 placeholder 数字）作为 follow-up backlog（独立 round）

**Risks**：本 round 后用户进入 SESSION HISTORY 列表点击进 detail（数据真），顶部 3 metric tile 仍是 enhance 落地的 `placeholderTrackRecord` 数字（trackName 真 / 数字部分为 placeholder）。Mitigation：commit body 标注此边界；跨 session 聚合明确为下一 round。

### D11：测试范围

**决定**：本 round 新增 1 个单元测试文件 `LapLiveStateDeriverTest.kt`，覆盖：

- `derive` 在 session=null（未开始）时返回 `currentLapNumber=1, currentLapTimerMs=null, bestLapTimeMs=null` 等
- 第 1 圈过程中 `currentLapTimerMs > 0`，`lastLapTimeMs=null`，`bestLapTimeMs=null`
- 完成第 1 圈后 `lastLapTimeMs` = 第 1 圈时长，`bestLapTimeMs` = 同值，`deltaToBestMs=0`
- 完成第 2 圈快于第 1 圈：`bestLapTimeMs` 更新，`deltaToBestMs` 反映差值（绝对值，正负由 sign 区分）
- 第 2 圈进行中且当前时间快于 best：`deltaToBestMs` 为负
- INVALID 圈跳过：`bestLapTimeMs` 不被 INVALID 圈更新
- 异常状态优先级：`abnormalState` 非 null 时 UI 必须打断（GPS_SIGNAL_LOST / WAITING_FOR_GPS_LOCK / BLE_DISCONNECTED / LAP_INVALIDATED）
- `connectionState != CONNECTED` → `abnormalState = BLE_DISCONNECTED`
- `gpsData.satelliteCount < 6` 或 `dataQuality.dataAge > 1000ms` → `abnormalState = WAITING_FOR_GPS_LOCK / GPS_SIGNAL_LOST`

**不写**：

- ComposeRule UI test（沿用前几 round 决策）
- Recorder lifecycle 集成测试（Activity 级 ViewModel 行为靠真机验证）
- Top speed / Duration / Distance 派生测试（D8 简化算法，单测覆盖率低 ROI；真机数据验证）

## Risks / Trade-offs

[**Activity destroy 仍可能丢 active session 状态**] → 用户切到别的 App + 系统极端回收 → ViewModel onCleared → session 数据状态丢（但 binary file 已 flush，Room metadata 已记录）。**一期不引入** `MainActivity.onDestroy` / `onCleared` cleanup（spec 已明确 MUST NOT），独立 round 处理（"Activity destroy / onCleared abnormal cleanup" follow-up）：暴露 `endSession(isAbnormal: Boolean)` 标记 endTs + Application-scoped scope await + UI "上次 session 异常结束" 提示。

[**横屏锁定 side-effect 在 Compose preview 失败**] → `Activity.requestedOrientation` 在 preview / Robolectric 下无 host Activity。Mitigation：用 `LocalContext.current as? Activity ?: return@DisposableEffect onDispose {}`，preview 不报错。

[**HOLD TO END 1.5s 在颠簸下可能误触**] → 严重颠簸用户可能短按多次累计触发（虽然每次按下都重置 progress）。Mitigation：实现确保 `onPress` 重置 `holdProgress = 0f`，仅持续按住才累加。如真机反馈仍误触，follow-up round 调整到 2s。

[**返回手势拦截弹 AlertDialog 在某些 OEM ROM 行为不一致**] → 部分 OEM 自定义返回手势可能跳过 BackHandler。Mitigation：本 round 用标准 `androidx.activity.compose.BackHandler`，已是 AndroidX 推荐路径；如某机型异常，follow-up 评估。

[**Snackbar 保存反馈 + View Record 跳转的 Snackbar 在某些 ROM 自动 dismiss 太快**] → 用户来不及点 View Record。Mitigation：用 `SnackbarDuration.Long`（约 10s）；如仍不够，follow-up 改用持久化 banner。

[**Top speed / Distance 一期接真数据可能因 binary file 缺失或截断显示空值**] → 用户期待这些字段总是有值。Mitigation：D8 显式说明数据缺失时显示 `--`；commit body 标注"distance 是直线累加近似"。

[**Records LAPS 视图首屏 placeholder 与 SESSION HISTORY 真数据混在一屏**] → 用户看到 "BEST LAP 1:32.457" 是假的，但点开 detail 看到真实 lap 时间不一致会困惑。Mitigation：D10 的 Risks 已记入 follow-up backlog，明确分两 round 实现。

[**多 lap session 同时存在的 race condition**] → 如果某种边界情况导致 `activeLapSessionId` 没清就开新 session，会写错 sessionId。Mitigation：A56 已通过 `endActiveLapSession()` 必先清状态再开新 session（lap session 懒启动逻辑）；本 round 不重做。

## Migration Plan

无运行时数据迁移（A56 数据层已就绪 + 一期 session 是新写）。

实施顺序：

1. `LapLiveStateDeriver` 纯函数 + 单元测试
2. `TestSessionViewModel` 加 `lapLiveState: StateFlow<LapLiveState>`（用 `combine` + tick）
3. `LapLiveScreen` Composable（横屏 side-effect / Keep screen on / BackHandler / 2x2 dashboard / HOLD TO END）
4. `EndConfirmationDialog` 子 Composable
5. `TrackTechAppShell.NavHost` 加 `composable("lap_live")`
6. `LapsHomeScreen.START LAP SESSION` 主操作改为 `navigate("lap_live")`
7. `LapsHomeScreen.CurrentTrackPanel` 接 `PresetTrackCatalog` 真数据
8. `LapSessionDetailScreen` Composable（Overview + lap records list）
9. `TrackTechAppShell.NavHost` 加 `composable("lap_session_detail/{sessionId}")`
10. `RecordsHomeScreen.LapsView` SESSION HISTORY 接 `TelemetrySessionDao.queryAll()` 真数据 + 行点击 navigate
11. Snackbar 保存反馈 + `LapSessionEventBus` 或复用 `TrackTechEventBus`
12. Top speed / Duration / Distance 派生 helper（D8 算法）
13. 编译 + grep 自检
14. 真机装机 + 完整闭环验证

回滚：本 round 是新增 UI + 派生函数，不改数据层；回滚 = 还原 ~10 个文件 + 删除 LapLiveStateDeriver + LapLiveScreen + LapSessionDetailScreen。

## Open Questions

1. **`enhance-track-presentation` 是否已 apply** —— 本 round 依赖该 round 的 `currentSelectedTrack` / `selectTrack` / `SelectTrackBottomSheet` / `TrackThumbnail` 已落地。apply §0.1 grep 确认；若未落地，本 round 暂停等 enhance 先 apply。**不阻塞 ff**。
2. **distance 派生算法精度** —— 一期直线累加 lat/lon，是否需要 great-circle 公式？保守用直线（性能好 + 偏差小），如真机数据偏差大再 follow-up 改 great-circle。**不阻塞 ff**。
3. **Snackbar duration 与 View Record action** —— 如果某机型 Snackbar duration Long 仍太短，是否改 banner？真机验证后再决定，**不阻塞 ff**。
4. **Session 与 Track 关联**：当前 `TelemetrySessionEntity` 没有 `trackId` 字段（A56 落地的 metadata 仅含 sessionType / 起止 ts / binaryFilePath）。本 round detail 屏 Track name 用 `currentSelectedTrack` fallback（一期单赛道）；多赛道时需要在 Entity 加 trackId 字段。记入 follow-up（与"多赛道扩展"合并）。**不阻塞 ff**。
