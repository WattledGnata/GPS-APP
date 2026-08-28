## 实施任务（依赖顺序）

本 change 落地圈速 session 一期完整闭环：`Laps home → live session → end and save → Records/Laps detail`。

覆盖：

- §0 grep 预检
- §1 LapLiveStateDeriver 纯函数 + 单元测试
- §2 TestSessionViewModel 加 lapLiveState StateFlow
- §3 LapLiveScreen Composable（横屏 + 屏幕常亮 + 返回拦截 + 2x2 dashboard + HOLD TO END + 异常状态）
- §4 LapSessionDetailScreen Composable（Overview + lap records list + 真数据接入）
- §5 TrackTechAppShell NavHost 加 lap_live + lap_session_detail 路由 + SnackbarHost
- §6 LapsHomeScreen / RecordsHomeScreen 改造（START LAP SESSION 进入 live；SESSION HISTORY 跳 detail）
- §7 编译/测试门槛
- §8 真机视觉/交互验证（manual gate）
- §9 commit + 合流门槛

参考 `proposal.md` / `design.md` D1-D11 / `specs/lap-live-session-screen/spec.md` + `specs/lap-session-recorder-lifecycle/spec.md` + `specs/lap-live-state-derivation/spec.md` + `specs/lap-session-detail-screen/spec.md` + `specs/track-tech-app-shell/spec.md`。

PRD：`docs/product/lap-session-phase1-requirements.md`
规格：`docs/design/lap-session-live-and-result-spec.md`
CC 指导：`docs/design/track-tech-v2-cc-guidance.md`
视觉参考：`docs/design/visual-refs/lap-live-landscape-balanced-v1.png`

---

## 0. grep 预检

- [x] 0.1 **enhance-track-presentation 已 apply 状态核实**（本 round 依赖此 round 先落地）：

  ```bash
  grep -nE "currentSelectedTrack|selectTrack\(" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt
  grep -rn "SelectTrackBottomSheet\|TrackThumbnail" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：`TestSessionViewModel` 含 `currentSelectedTrack: StateFlow<Track?>` + `selectTrack(track: Track)` API；`SelectTrackBottomSheet.kt` + `TrackThumbnail.kt` 文件存在；`LapsHomeScreen.CurrentTrackPanel` 已消费 `currentSelectedTrack`；`RecordsHomeScreen.placeholderTrackRecord` 已真实化（消费 `currentSelectedTrack` 派生）；`CHANGE TRACK` 按钮 onClick 已接通真实 sheet。

  **若上述任一条件不满足**：本 round MUST 暂停，先完成 `enhance-track-presentation` 的 apply。本 round 不重做这些契约。

- [x] 0.2 **baseline LapDebugExecutionScreen 调用点核实**（保留 fallback，不删）：

  ```bash
  grep -rn "LapDebugExecutionScreen" /Users/wattledgnata/traeProjects/gps-app/feature --include="*.kt"
  ```

  预期：`TestFlowNavigation.kt` 旧路径调用 + 自身定义。本 round 不删，保留作 transitional fallback。

- [x] 0.3 **TestSessionViewModel 现有字段确认**：

  ```bash
  grep -nE "activeLapSessionId|activeLapStartSystemTs|lastWrittenCrossingCount|lapSession\b|crossingEvents" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt | head -20
  ```

  预期：A56 已落地的 lap session 状态字段全部存在。

- [x] 0.4 **CrossingEventDao / TelemetrySessionDao 现有 API**：

  ```bash
  grep -nE "fun insert|fun queryBy|fun queryAll|fun updateEndTs" /Users/wattledgnata/traeProjects/gps-app/core/data/src/main/java/com/blazepush/core/data/local/dao/CrossingEventDao.kt /Users/wattledgnata/traeProjects/gps-app/core/data/src/main/java/com/blazepush/core/data/local/dao/TelemetrySessionDao.kt
  ```

  预期：A56 落地的 DAO 方法（`queryBySessionId` / `insertInTransaction` / `queryAll` / `updateEndTs`）全部存在。

- [x] 0.5 **赛道缩略图资产确认**：

  ```bash
  ls -la /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/assets/track_thumbnails/
  ```

  预期：`chengdu_tianfu.png` 存在（720x405 透明底）。

- [x] 0.6 **AndroidManifest 现有 orientation 配置**：

  ```bash
  grep -n "screenOrientation\|configChanges" /Users/wattledgnata/traeProjects/gps-app/app/src/main/AndroidManifest.xml
  ```

  预期：MainActivity 现有 `android:screenOrientation` 配置（如有，本 round 通过 Compose side-effect 临时覆盖；不改 manifest）。

---

## 1. LapLiveStateDeriver 纯函数 + 单元测试

- [x] 1.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt`：

  - `data class LapLiveState(...)` 6 字段
  - `enum class AbnormalState { GPS_SIGNAL_LOST, WAITING_FOR_GPS_LOCK, BLE_DISCONNECTED, LAP_INVALIDATED }`
  - `object LapLiveStateDeriver { fun derive(session: LapSession?, currentTimeMs: Long, gpsData: GpsData, connectionState: ConnectionState, dataQuality: DataQuality): LapLiveState }`
  - 异常状态优先级（D1-D5 → BLE / GPS_LOST / WAITING / INVALID / 正常）
  - currentLapTimer / lastLap / bestLap / deltaToBest / lapNumber 派生（INVALID 圈跳过）

- [x] 1.2 新建 `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapLiveStateDeriverTest.kt`：覆盖 ≥ 11 个关键场景（spec 列表）

- [x] 1.3 编译 + 跑测：`./gradlew :feature:test:testDebugUnitTest --tests "*LapLiveStateDeriverTest*"` 全绿

---

## 2. TestSessionViewModel 暴露 lapLiveState

- [x] 2.1 `TestSessionViewModel` 加：

  ```kotlin
  val lapLiveState: StateFlow<LapLiveState> = combine(
      _lapSession,
      gpsDataViewModel.gpsData,
      gpsDataViewModel.connectionState,
      gpsDataViewModel.dataQuality,
      tickerFlow(50L),  // 20Hz tick 让 currentLapTimer 平滑
  ) { session, gps, conn, quality, _ ->
      LapLiveStateDeriver.derive(session, System.currentTimeMillis(), gps, conn, quality)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LapLiveStateDeriver.derive(null, 0L, ..., ..., ...))
  ```

  其中 `tickerFlow(50L)` 是私有辅助 `flow { while (true) { emit(Unit); delay(50) } }`。

- [x] 2.2 **`TestSessionViewModel` 暴露 public suspend `finishActiveLapSession(): LapSessionSaveResult?`**（D12）：

  - 新增 data class `LapSessionSaveResult(val sessionId: String, val lapCount: Int, val bestLapMs: Long?, val totalDurationMs: Long)`（同文件 internal 或同包级）
  - 新增 public suspend 方法 `finishActiveLapSession()`：
    1. **先捕获**派生数据（清状态前）：sessionId / lapCount（accepted lap 数）/ bestLapMs / totalDurationMs
    2. **await** `telemetryRepository.endSession(sessionId)`
    3. **后清** ViewModel 状态（activeLapSessionId / activeLapStartSystemTs / lastWrittenCrossingCount / isLapRecording / `_lapSession` 标记 Finished）
    4. 返回 `LapSessionSaveResult`；`activeLapSessionId == null` 时返回 null
  - 保留 baseline private `endActiveLapSession()` 给 `stopLapDebugSession` / `exitLapDebugMode` 等 fire-and-forget caller 使用，**不**改它们的行为
  - LapLiveScreen HOLD TO END 完成 / EndConfirmationDialog 选 End Session 时调 `finishActiveLapSession()` 拿 result 给 Snackbar

- [x] 2.3 **`TelemetryRepository` 加 public suspend API**（D13）：

  - `suspend fun getCrossings(sessionId: String): List<TelemetryCrossingEvent>` —— 调 `crossingDao.queryBySessionId(sessionId)` + 映射 entity → domain
  - `suspend fun getRecentLapSessions(limit: Int = 10): List<TelemetrySession>` —— 调 `sessionDao.queryAll()` + filter `LAP_SESSION` + take limit + 映射

  实施细节：
  - 文件：`core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`
  - 加 private extension `CrossingEventEntity.toDomain()` 转换（baseline 已有 `TelemetrySessionEntity.toDomain()`）
  - 注释每个新方法 `@author CC / @description / @date 2026-05-01`（kt-check 规则）

- [x] 2.4 编译验证

---

## 3. LapLiveScreen Composable

- [x] 3.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt`：

  - 函数签名：`LapLiveScreen(navController: NavController, sessionViewModel: TestSessionViewModel = koinViewModel())`
  - `DisposableEffect` 锁定 landscape orientation（进入时设 `requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE`，`onDispose` 恢复 `SCREEN_ORIENTATION_UNSPECIFIED`）
  - Modifier 加 `keepScreenOn` 或调 `(LocalView.current).keepScreenOn = true`（DisposableEffect onDispose 设回 false）
  - `BackHandler { showEndConfirmation = true }`
  - 异常状态打断分支：`val state by sessionViewModel.lapLiveState.collectAsState()`，`if (state.abnormalState != null) { AbnormalBanner(state.abnormalState) }` 优先级最高，覆盖主体
  - 正常态：Top strip（`LAPS · trackName · LAP N · tiny Ready`） + 2x2 dashboard（Delta / Current / Last / Best 4 个 MetricTile，使用上一 round 落地的 `MetricKind.Score`）+ 底部 HOLD TO END button
  - HOLD TO END：`Modifier.pointerInput(Unit) { detectTapGestures(onPress = { ... }) }` + 协程定时 1500ms + progress bar 视觉
  - HOLD TO END 完成后（**先 popBackStack，再 Shell scope 显 Snackbar**，避免用户停留在已结束的 live 屏 10 秒）：
    ```kotlin
    coroutineScope.launch {
        val result = sessionViewModel.finishActiveLapSession()  // suspend，await endSession（D12）
        if (result != null) {
            // 1. 把 result 推给 Shell scope（EventBus / SharedFlow）
            LapSessionSaveBus.emit(result)   // 或复用 TrackTechEventBus.emit(LapSessionSavedEvent(result))
        }
        // 2. 立刻回 home，不等 Snackbar
        navController.popBackStack()
    }
    ```

    `TrackTechAppShell` 侧（§5.2）持有 `SnackbarHostState`，并 `LaunchedEffect(Unit) { LapSessionSaveBus.events.collect { result -> ... } }` collect 后调：

    ```kotlin
    val snackResult = snackbarHostState.showSnackbar(
        message = "Lap session saved · ${result.lapCount} laps",
        actionLabel = "View Record",
        duration = SnackbarDuration.Long,
    )
    if (snackResult == SnackbarResult.ActionPerformed) {
        navController.navigate("lap_session_detail/${result.sessionId}")
    }
    // 不点 action → 用户已在 home，无额外动作
    ```

    apply 阶段拍板：复用 `TrackTechEventBus` 加新事件类型，或新建 `LapSessionSaveBus` —— 两种实现均可，关键是 LapLiveScreen 不阻塞等 Snackbar dismiss。
  - `EndConfirmationDialog`（私有 Composable）：标题 `End Lap Session?`，两个按钮 `Continue` / `End Session`

- [x] 3.2 单行约束（沿用上 round 落地的 V2 视觉规则）：所有 LapLiveScreen 内 Text 调用加 `maxLines = 1, overflow = TextOverflow.Ellipsis`

- [x] 3.3 编译验证

---

## 4. LapSessionDetailScreen Composable

- [x] 4.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt`：

  - 函数签名：`LapSessionDetailScreen(navController: NavController, sessionId: String, telemetryRepository: TelemetryRepository = koinInject())`
  - `LaunchedEffect(sessionId)` 通过 **TelemetryRepository public API** 加载真实数据（**不**直接访问 DAO，D13）：
    - `val session = telemetryRepository.getSession(sessionId)` — baseline A56 已有
    - `val crossings = telemetryRepository.getCrossings(sessionId)` — §2.3 新增
    - 派生 lap records：相邻 accepted start/finish crossings 之间的差 = 圈时长；INVALID 圈用 reason 字段
  - 顶部返回按钮 / 标题 `"Session"`
  - Overview 区域：Track name（来自 `session?.binaryFilePath` 关联或本 round 暂从 `currentSelectedTrack` 派生 —— 一期 TFIC LPCC 一条赛道，session 无 trackId 字段则 fallback 到 currentSelectedTrack；apply 阶段确认）/ Session date·time（`session.startTs` 格式化）/ Best lap / Total laps / Valid laps / Invalid laps / Top speed / Duration / Distance（数据缺失显示 `--`）
  - Lap Records List：`LazyColumn` 渲染每个 lap 一行：Lap N / Time / Diff / Status；BEST 圈视觉强化（紫色 accent）；INVALID 圈展示 reason
  - 单行约束沿用：所有 Text 加 `maxLines = 1, overflow = Ellipsis`
  - **MUST NOT** import `CrossingEventDao` / `TelemetrySessionDao` —— UI 层只通过 repository 边界访问

- [x] 4.2 派生 helper 函数：

  ```kotlin
  private fun deriveLapRecords(crossings: List<TelemetryCrossingEvent>): List<LapRecord>
  private fun deriveTopSpeed(repository: TelemetryRepository, filePath: String): Double?  // suspend，读 binary
  private fun deriveDistance(repository: TelemetryRepository, filePath: String): Double?  // 直线累加近似
  ```

  数据类：`private data class LapRecord(val lapNumber: Int, val timeMs: Long?, val diffMs: Long?, val status: LapStatus, val reason: String?)`，`enum class LapStatus { BEST, VALID, INVALID, INCOMPLETE }`

- [x] 4.3 编译验证

---

## 5. TrackTechAppShell NavHost 加路由 + SnackbarHost

- [x] 5.1 `TrackTechAppShell.kt` NavHost 加：

  ```kotlin
  composable("lap_live") {
      LapLiveScreen(navController = navController, sessionViewModel = sessionViewModel)
  }
  composable(
      route = "lap_session_detail/{sessionId}",
      arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
  ) { backStackEntry ->
      val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
      LapSessionDetailScreen(navController = navController, sessionId = sessionId)
  }
  ```

- [x] 5.2 Scaffold 加 SnackbarHost + Shell scope 监听 LapSessionSaveBus（**Snackbar 在 Shell scope 显示，不被 LapLiveScreen 阻塞**）：

  ```kotlin
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()

  // Shell scope 监听 lap session 保存事件（P2-2 修订）
  LaunchedEffect(Unit) {
      LapSessionSaveBus.events.collect { result ->
          val snackResult = snackbarHostState.showSnackbar(
              message = "Lap session saved · ${result.lapCount} laps",
              actionLabel = "View Record",
              duration = SnackbarDuration.Long,
          )
          if (snackResult == SnackbarResult.ActionPerformed) {
              navController.navigate("lap_session_detail/${result.sessionId}")
          }
      }
  }

  Scaffold(
      ...,
      snackbarHost = { SnackbarHost(snackbarHostState) },
      ...
  )
  ```

  `LapLiveScreen` **不**接收 `snackbarHostState` 参数；HOLD TO END 完成后调 `finishActiveLapSession()` + `LapSessionSaveBus.emit(result)` + 立刻 `popBackStack`（§3.1）。

- [x] 5.3 新建 `LapSessionSaveBus`（或复用 `TrackTechEventBus`）：

  最简实现：
  ```kotlin
  object LapSessionSaveBus {
      private val _events = MutableSharedFlow<LapSessionSaveResult>(extraBufferCapacity = 1)
      val events: SharedFlow<LapSessionSaveResult> = _events.asSharedFlow()
      suspend fun emit(result: LapSessionSaveResult) = _events.emit(result)
  }
  ```

  注意：上一 round 已修复 `TrackTechEventBus` 的 SharedFlow(replay=0) 在未组合 page 上事件丢失问题（Shell 是唯一 collector）；本 bus 同样依赖"Shell collect 必先于 emit"——而 `TrackTechAppShell` 顶层 LaunchedEffect 在 LapLiveScreen 进入前已订阅，时序安全。

- [x] 5.3 编译验证

---

## 6. LapsHomeScreen / RecordsHomeScreen 改造

- [x] 6.1 **`LapsHomeScreen.kt`**（仅改 START LAP SESSION onClick，**不动** trackName / 缩略图 / CHANGE TRACK 行为）：

  - `START LAP SESSION` 主操作 onClick 改为读 `currentSelectedTrack`（D6 + enhance 落地的真相源）：
    ```kotlin
    if (readiness.canEnterTestFlow) {
        val track = testSessionViewModel.currentSelectedTrack.value
        if (track != null) {
            testSessionViewModel.selectLapDebugMode(track.id)
            navController.navigate("lap_live")
        }
        // currentSelectedTrack == null 兜底：保持原状（不进 live；enhance 已确保默认有值）
    } else {
        // 已有 gating Toast 兜底逻辑保留
    }
    ```

  - **MUST NOT** 改 `CurrentTrackPanel.trackName` 来源（已由 enhance 接 currentSelectedTrack）
  - **MUST NOT** 改 `CHANGE TRACK` 按钮 onClick（已由 enhance 接 SelectTrackBottomSheet）
  - **MUST NOT** 引入 hardcoded trackId（如 `"preset-tfic-lpcc"`）作为 fallback

- [x] 6.2 **`RecordsHomeScreen.kt` `LapsView`**（仅改 SESSION HISTORY 数据源 + 行点击 nav；**不动** Current Track Record 卡 + 3 metric tile）：

  - `placeholderLapSessions` 替换为真实数据：用 `koinInject<TelemetryRepository>()` 调 `getRecentLapSessions(limit = 10)`（§2.3 新增 public API）
  - `SESSION HISTORY` 列表每条 `TrackTechRow` 的 `onClick` 改为：`navController.navigate("lap_session_detail/${session.sessionId}")`
  - **MUST NOT** 改 `placeholderTrackRecord`（已由 enhance-track-presentation 真实化）
  - **MUST NOT** 改 `CurrentTrackRecordCard` 签名 / 内部 `TrackThumbnail` 调用（已由 enhance 落地）
  - **MUST NOT** import `TelemetrySessionDao` / `CrossingEventDao`（UI 层不直接访问 DAO，D13）

- [x] 6.3 编译验证 + grep 自检：

  ```bash
  # nav 跳转命中
  grep -rn "navigate(\"lap_live\")\|navigate(\"lap_session_detail" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech --include="*.kt"

  # repository public API 命中（不直接访问 DAO）
  grep -rn "TelemetrySessionDao\|CrossingEventDao" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech --include="*.kt"
  ```

  预期：
  - LapsHomeScreen 内 `navigate("lap_live")` 命中 1 次；RecordsHomeScreen 内 `navigate("lap_session_detail/...")` 命中 1 次
  - DAO import 在 tracktech 子包内零命中（UI 层全走 `TelemetryRepository`）

---

## 7. 编译/测试门槛

- [x] 7.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 7.2 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 7.3 `./gradlew :feature:test:testDebugUnitTest` 全绿（含新 `LapLiveStateDeriverTest` + 现有套件零回归 `TrackTechAppShellPagerTest` / `TabGatingPolicyTest` / `TestSessionViewModelTrackLapTest`）
- [x] 7.4 `./gradlew :core:bluetooth:testDebugUnitTest :core:domain:test :core:data:testDebugUnitTest` 全绿（`core/data` 内仅 `TelemetryRepository` 加 2 个 public suspend API，DAO 零改动；`core/bluetooth` / `core/domain` 完全不动）
- [x] 7.5 **grep 自检**：

  ```bash
  # AndroidManifest 不动
  git diff app/src/main/AndroidManifest.xml | wc -l
  # 预期：0（不引入新权限或 service）

  # 不引入 Foreground Service
  grep -rn ": LifecycleService\|: Service()\|MediaSessionService\|FOREGROUND_SERVICE" /Users/wattledgnata/traeProjects/gps-app --include="*.kt" --include="*.xml"
  # 预期：零命中

  # Live 屏不展示 speed / GPS 细节
  grep -nE "gpsData.speed|km/h|hdop|satelliteCount|frequency" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt
  # 预期：异常 banner 内的字面量可接受；正常 dashboard 区域零命中

  # 横屏锁定 + 屏幕常亮
  grep -nE "SCREEN_ORIENTATION_LANDSCAPE|FLAG_KEEP_SCREEN_ON|keepScreenOn" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt
  # 预期：≥ 2 处命中（landscape + keepScreenOn）

  # 单行约束（V2 视觉规则）
  grep -c "maxLines = 1" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt
  # 预期：两个文件各 ≥ 5 处

  # 不展示 sector / theoretical / chart / map / video（detail 屏）
  grep -niE "theoretical|sector|chart|map|video|S1|S2|S3" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt
  # 预期：零命中（或仅 variable 命名 / 注释中的提及；无 UI label 字面量）
  ```

---

## 8. 真机视觉/交互验证（manual gate）

- [ ] 8.1 安装到真机：

  ```bash
  ANDROID_SERIAL=8KE0219522008434 ./gradlew :app:installDebug
  ```

- [ ] 8.2 **Laps 首页**：
  - 显示 Chengdu Tianfu 当前赛道（不再是 hardcoded "Shanghai Tianma"）
  - START LAP SESSION ready 状态点击 → 进入 lap_live 屏

- [ ] 8.3 **Live 屏**：
  - 自动旋转到横屏（即使设备处于竖屏状态）
  - 屏幕常亮（不进入熄屏）
  - 不显示 bottom tab bar
  - 顶部 strip：LAPS · Chengdu Tianfu · LAP 1 · tiny Ready
  - 主体 2x2 dashboard：Delta / Current / Last / Best 四个 metric tile，Current 不独占视觉中心
  - 不显示速度 / GPS 细节 / 卫星数 / Hz
  - 底部 HOLD TO END button（红色 outline）

- [ ] 8.4 **返回手势拦截**：
  - 系统返回手势 → 弹出确认 AlertDialog（`End Lap Session?` + Continue/End Session）
  - 选 Continue → dialog dismiss，session 继续
  - 选 End Session → 走结束流程

- [ ] 8.5 **HOLD TO END**：
  - 短按（< 1.5s）：progress bar 显示后中途松手 → 重置不结束
  - 长按 1.5s：progress 填满 → 触发结束 → 显示 Snackbar "Lap session saved · X laps" + View Record

- [ ] 8.6 **Snackbar action**：
  - 点击 View Record → 跳转 lap_session_detail/{sessionId}
  - 不点 → Snackbar 自动 dismiss → 回到 Laps 首页

- [ ] 8.7 **异常状态打断**：
  - 强制断开 BLE → Live 屏显示 `BLE DISCONNECTED` banner（覆盖主体）
  - 重连 BLE → banner 消失，dashboard 恢复

- [ ] 8.8 **Records LAPS SESSION HISTORY**：
  - Records tab → LAPS sub-tab → SESSION HISTORY 列表显示真实 session（含刚保存的）
  - 点击行 → 跳转 lap_session_detail/{sessionId}

- [ ] 8.9 **Session Detail Overview**：
  - 显示 Track name / Session date·time / Best lap / Total/Valid/Invalid laps
  - 显示 Top speed / Duration / Distance（数据可派生时；缺失显 `--`）
  - Lap Records List 显示每圈 Lap / Time / Diff / Status，Best 圈紫色 accent，INVALID 显 reason
  - 不显示 sector / theoretical / chart / map / video / S1/S2/S3

- [ ] 8.10 视觉偏差点（如 dashboard 字号 / Delta 颜色对比 / HOLD TO END progress bar 视觉等）作为 follow-up backlog 记录到 commit message body

---

## 9. Commit + 合流门槛

- [ ] 9.1 **Spec 验证**：`openspec validate add-lap-session-phase1 --strict` 返回 `Change ... is valid`

- [ ] 9.2 **grep 自检**（最终汇总）：
  - LapLiveStateDeriver / LapLiveState / AbnormalState / MetricKind.Score 派生命中
  - LapLiveScreen 含 `SCREEN_ORIENTATION_LANDSCAPE` + `keepScreenOn` + `BackHandler` + `HOLD TO END` + `1500L` (or HOLD_DURATION_MS)
  - LapLiveScreen HOLD TO END / EndConfirmationDialog 内调 `finishActiveLapSession()`（D12 public suspend API）
  - TestSessionViewModel 含 `suspend fun finishActiveLapSession()` + `LapSessionSaveResult` data class
  - LapSessionDetailScreen 含 Track / Best Lap / Total Laps / Valid Laps / Invalid Laps / Lap Records 等 label
  - LapSessionDetailScreen 调 `telemetryRepository.getCrossings(sessionId)` + `getSession(sessionId)`（D13 public API）
  - LapSessionDetailScreen / RecordsHomeScreen **不**直接 import `CrossingEventDao` / `TelemetrySessionDao`
  - TelemetryRepository 含 `suspend fun getCrossings` + `suspend fun getRecentLapSessions`
  - TrackTechAppShell 加 `composable("lap_live")` + `composable("lap_session_detail/{sessionId}")` + `SnackbarHost`
  - LapsHomeScreen.START LAP SESSION onClick 读 `currentSelectedTrack` + `navigate("lap_live")`（**不**引入 hardcoded trackId）
  - LapsHomeScreen.CurrentTrackPanel.trackName 来源 / CHANGE TRACK onClick **零改动**（已由 enhance-track-presentation 落地）
  - RecordsHomeScreen.SESSION HISTORY 数据源用 `getRecentLapSessions` + 行 onClick 含 `navigate("lap_session_detail/")`
  - RecordsHomeScreen.placeholderTrackRecord / CurrentTrackRecordCard / TrackThumbnail **零改动**（已由 enhance-track-presentation 落地）
  - AndroidManifest.xml diff 零行（不引入 service / 权限）
  - 不引入 Foreground Service / `: Service()` / `LifecycleService`
  - **不**引入 `MainActivity.onDestroy` cleanup / `TestSessionViewModel.onCleared` cleanup（P2 降级为 follow-up）
  - Live 屏正常 dashboard 不展示 speed / GPS 细节
  - V2 单行约束沿用：≥ 5 处 maxLines = 1 in 两个新屏

- [ ] 9.3 **下游零回归**：
  - `:core:bluetooth:testDebugUnitTest` ✅
  - `:core:domain:test` ✅
  - `:core:data:testDebugUnitTest` ✅
  - `:app:compileDebugKotlin` ✅
  - `:feature:test:testDebugUnitTest` 全绿（新 `LapLiveStateDeriverTest` + 现有套件零回归）

- [ ] 9.4 **真机验证**已完成（§8 完整闭环 + 横屏 + 屏幕常亮 + 异常 banner + Snackbar + detail 真数据）

- [ ] 9.5 **commit**：`feat(ui): 圈速 session 一期 · live 仪表 + Records detail + recorder lifecycle`

  body 要点：
  - **lap-live-session-screen capability 新建**：横屏锁定 (DisposableEffect requestedOrientation) + 屏幕常亮 (keepScreenOn) + BackHandler 拦截弹 EndConfirmationDialog + 2x2 dashboard (Delta/Current/Last/Best) + Top strip lap badge + HOLD TO END (1.5s long-press) + 异常状态优先级 banner
  - **lap-session-recorder-lifecycle capability 新建**：TestSessionViewModel Activity-scoped + TelemetryRepository Application-scoped 单例；config-change 不停 active recording；新增 public suspend `finishActiveLapSession(): LapSessionSaveResult?`（先捕获 sessionId/lapCount/bestLapMs/totalDurationMs，await endSession，再清状态）；一期 **MUST NOT** 引入 Foreground Service / `onCleared` / `MainActivity.onDestroy` cleanup（Activity destroy 极端 case 明确为 follow-up backlog）
  - **lap-live-state-derivation capability 新建**：LapLiveStateDeriver 纯函数 + 6 字段 LapLiveState + 4 项 AbnormalState；TestSessionViewModel.lapLiveState StateFlow combine 5 流（lapSession + gpsData + connectionState + dataQuality + 50ms ticker）
  - **lap-session-detail-screen capability 新建**：Records/Laps SESSION HISTORY 入口 + Save feedback View Record 入口；Overview（Track name / 日期 / Best lap / Total/Valid/Invalid laps）+ Lap records list (Lap/Time/Diff/Status) + Top speed/Duration/Distance（D8 一期都接真数据，缺失显 `--`）
  - **track-tech-app-shell capability modified**：NavHost 加 `lap_live` + `lap_session_detail/{sessionId}` 子路由；Scaffold 加 SnackbarHost（HOLD TO END 反馈用）；home Pager 4 tab 配置零回归
  - **`core/data/TelemetryRepository.kt` 加 2 个 public suspend API**（D13）：`getCrossings(sessionId): List<TelemetryCrossingEvent>` + `getRecentLapSessions(limit = 10): List<TelemetrySession>`；UI 层（LapSessionDetailScreen / RecordsHomeScreen）通过 repository 边界访问，**不**直接 import DAO
  - **零改动**：`core/bluetooth/*` / `core/domain/*` / `simulator/*` / `core/data` 的 DAO 层 / Room schema / Entity 字段 / 公共 BLE 协议 / V2 typography 系统 / 其他 home screen 内容 / AndroidManifest / baseline `LapDebugExecutionScreen.kt`（保留作 fallback）
  - **测试**：新增 LapLiveStateDeriverTest 11+ 场景（首圈 / N 圈 / INVALID 跳过 / 异常状态优先级 / 派生正负 sign）；现有套件全绿
  - **真机验证**：圈速完整闭环（华为 8KE0219522008434）+ 横屏锁定 + 屏幕常亮 + 返回拦截 + HOLD TO END + Snackbar + Session detail 真数据
  - **合流门槛**：openspec validate --strict ✅ / grep 自检全部通过 ✅

  格式约束：
  - Conventional Commits（feat 因为引入新功能）
  - body 含 capability 名 + 受影响 ~10 个文件清单 + 真机验证状态
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>

---

## 10. Post-apply follow-up backlog（不在本 change scope，记录到 commit message）

- **Records LAPS 视图首屏 3 metric tile 跨 session 聚合**（聚合 best lap / sessions count / total laps 跨多个 lap sessions；当前 placeholder 已由 `enhance-track-presentation` 真实化为 currentSelectedTrack 派生，本 round 不动） — 独立 round
- **多赛道扩展**（增加更多预设赛道，扩充 `SelectTrackBottomSheet` 内的可选项；选择/切换流程已由 enhance-track-presentation 落地） — 独立 round
- **Foreground Service 兜底 active recording**（应对系统极端杀进程场景） — 独立 round
- **Distance 派生 great-circle 公式精度提升** — 独立 round（如真机数据偏差大）
- **HOLD TO END 时长可配置 / 真机反馈调整** — 微调 round（如 1.5s 颠簸下误触）
- **Analysis Mode**（Records detail menu → 横屏高级图表 + sector + lap-vs-lap + speed curve + map replay + video overlay）— 大 round（PRD 已记入 future scope）
- **Sector timing / theoretical best** — Analysis Mode 子能力，独立 round
- **Live session 自定义 template**（Minimal / Sector / Telemetry / Coach）— Future round
- **Activity destroy / onCleared abnormal cleanup**（暴露 `endSession(isAbnormal: Boolean)` 标记 endTs + Application-scoped scope await + UI "上次 session 异常结束" 提示）— 独立 round（P2 review 共识：当前 viewModelScope 在 onCleared 时被取消，await 不可靠；需 Foreground Service 或 Application scope 兜底）
- **Session detail Track 缩略图渲染**（直接复用 `enhance-track-presentation` 落地的 `TrackThumbnail` Composable） — 视觉细化 round
- **`wire-laptime-to-gps-filter`**（lap timing 数据流接入 baseline `core/domain/usecase/GpsDataFilter`，与加减速通道对齐）— 独立 round。详细设计 / 数据证据 / 方案对比 / 实施约束 / 单元测试覆盖 / 与本 round 去抖兜底的关系，全部沉淀于 `docs/design/laptime-gps-filter-integration-deferred.md`。**摘要**：当前圈速通道 `bridgeGpsToLapTiming` 直吃 raw GpsData 绕过 filter，单帧 GPS jitter 在 sector gate 瞬间触发 `WrongDirection` 误判（本 round 真机数据：2742 reject 中 1 帧 invalidating，directionScore=-1.157）。本 round 已加 `LapLiveStateDeriver` 3-event 去抖兜底；filter 接通是数据流根本性消除路径，接通后阈值可降回 1。**MUST 用方案 2**（仅替换 lat/lon/speed/bearing，不 skip 任何帧），避免 detector 收到 ts gap。
- **`persist-session-summary-fields`**（endSession 时计算并持久化 `topSpeedKmh` / `lapCount` / `bestLapMs` / **`trackId`** 到 `TelemetrySessionEntity`）— 独立 round。**动机**：(1) 当前 detail 屏 top speed 通过 `readPerformanceSamples` 全扫 binary 派生（~50ms / 进入），跨 session 切换浪费；(2) baseline `lapCount`/`bestLapMs` Room 字段也是默认值未回写；(3) **detail 屏 track name / distance 现在用 `currentSelectedTrack`，用户切赛道后旧 session detail 显示当前选中赛道（错），需要 entity 持久化 trackId 才能溯源到 session 当时的赛道**（Codex 2026-05-01 review P2.4 提出，本 round 受 entity schema 不可改约束接受 fallback）。**实施**：`endSession()` 增加 binary scan 算 max speed + 派生 lap 统计（基于 crossings）+ 写入 entity；新增 `topSpeedKmh: Double?` + `trackId: String?` Room 字段 + Room migration（schema version + 1）；detail 屏 / Records LAPS history 行直接读 entity 的 `trackId` 解析回 Track，不再读 currentSelectedTrack。**单元测试**：endSession 写入字段后 `getSession(...)` 读到正确值 / Room migration 兼容历史数据库 / detail 屏切赛道后历史 session 仍显示原 track
- **`fix-lap-binary-ts-hygiene`**（lap session binary writer 的 tsDeltaMs 时间轴对齐）— 独立 round。详细设计见 `docs/design/laptime-ts-hygiene-deferred.md`。**摘要**：`bridgeGpsToLapTiming` 写入 `tsDeltaMs = gpsData.timestamp(协议ts) - lapAnchorTs(真壁钟)`，混合时间轴让 `readLapSamples` 的 absoluteTs filter 永远 reject，导致按时间窗口过滤的 lap segment / sector 分段 / 单圈轨迹回放等功能完全不可用。本 round 已用 `readPerformanceSamples`（不过滤）quick fix 让 detail 屏 TOP SPEED 工作。**MUST 用方案 A**（1 行改 tsDeltaMs 公式 → `System.currentTimeMillis() - lapAnchorTs`），保持 entity / header / writer 单一真壁钟时钟域。**单元测试**：writer-reader round trip 验证 absoluteTs 落在 entity 时间窗口内
