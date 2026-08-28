## ADDED Requirements

### Requirement: TrackTechAppShell NavHost 加 lap_live + lap_session_detail 路由

`TrackTechAppShell.NavHost` MUST 新增两个 `composable(...)` 路由（与现有 `home` / `test_execution` / `gps_details` 平级）：

1. `composable("lap_live") { LapLiveScreen(navController, sessionViewModel) }` —— 圈速实时仪表屏
2. `composable("lap_session_detail/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { backStackEntry -> LapSessionDetailScreen(navController, sessionId = ...) }` —— 圈速 session detail Overview 屏

bottom nav 可见性判定保持 `currentRoute == "home"`（上一 round 落地），副作用是 `lap_live` / `lap_session_detail/...` 路由下 bottom nav 自动隐藏。

#### Scenario: NavHost 含两个新路由

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 NavHost block
- **THEN** 含 `composable("lap_live")` 一处
- **AND** 含 `composable("lap_session_detail/{sessionId}", ...)` 一处（路由参数声明 `sessionId: StringType`）
- **AND** 现有 `composable("home")` / `composable("test_execution")` / `composable("gps_details")` 三个路由保留不动

#### Scenario: 两个新路由下 bottom nav 不可见

- **GIVEN** 用户从 Laps 首页进入 lap_live；或从 Records LAPS 进入 lap_session_detail/X
- **WHEN** Shell recomposition 后渲染 Scaffold
- **THEN** bottom nav 不可见（`currentRoute != "home"` → `showBottomNav = false`）

### Requirement: NavHost 不破坏现有 enterTransition / exitTransition None

`TrackTechAppShell.NavHost` MUST 保留 4 个转场参数（`enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition`）为 `EnterTransition.None` / `ExitTransition.None`（上一 round 落地的契约），加 `lap_live` + `lap_session_detail` 两个新路由时 MUST NOT 引入 fadeIn / scaleIn 等默认动画。

#### Scenario: 4 个转场参数仍为 None

- **GIVEN** 实施前后 `TrackTechAppShell.kt` 源码内 NavHost 调用
- **WHEN** 阅读 4 个转场参数
- **THEN** 仍为 `EnterTransition.None` / `ExitTransition.None`（不退化为默认 fadeIn + scaleIn）

### Requirement: TrackTechAppShell 加 SnackbarHost

`TrackTechAppShell` 的 `Scaffold` MUST 暴露 `snackbarHost = { SnackbarHost(snackbarHostState) }`，由 lap session 保存反馈使用。

`snackbarHostState: SnackbarHostState` MUST 通过 `remember { SnackbarHostState() }` 在 Shell 顶层创建。

`snackbarHostState` MUST NOT 作为参数传给 `LapLiveScreen`，也 MUST NOT 通过 CompositionLocal / 共享 ViewModel 暴露给 `LapLiveScreen`（与 `lap-session-recorder-lifecycle` D7 / tasks §5.2 一致 —— 避免 LapLiveScreen 等待 Snackbar dismiss 阻塞 popBackStack）。

Snackbar 触发入口仅限 **Shell-level collector**：在 `TrackTechAppShell` 顶层 `LaunchedEffect(Unit)` 内 collect `LapSessionSaveBus.events`（或等价 Shell-scoped event flow），由 Shell `coroutineScope` 调 `snackbarHostState.showSnackbar(...)`。

#### Scenario: Scaffold snackbarHost 参数

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `Scaffold(...)` 调用
- **THEN** 含 `snackbarHost = { SnackbarHost(...) }` 参数（或等价）
- **AND** 含 `val snackbarHostState = remember { SnackbarHostState() }` 创建

#### Scenario: snackbarHostState 不传给 LapLiveScreen

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码内 `LapLiveScreen(...)` 调用点
- **WHEN** 阅读传入 `LapLiveScreen` 的参数列表
- **THEN** **不**含 `snackbarHostState` / `SnackbarHostState` 类型的参数
- **AND** 整个 `LapLiveScreen.kt` 文件 grep `SnackbarHostState` 零命中

#### Scenario: Shell-level LaunchedEffect collect LapSessionSaveBus

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** grep `LapSessionSaveBus.events` 调用位置
- **THEN** 命中位于 Shell 顶层 `LaunchedEffect(Unit) { ... }` 块内
- **AND** collect lambda 内调 `snackbarHostState.showSnackbar(...)`
- **AND** Snackbar action 触发的 `navController.navigate("lap_session_detail/...")` 也在 Shell `coroutineScope` 内

### Requirement: home Pager 4 tab 不变

本 round MUST NOT 修改 home Pager 内 4 个 tab 的：

- 路由名（`test` / `laps` / `records` / `device`）
- 顺序与 page index（TabIndex.Test=0 / Laps=1 / Records=2 / Device=3）
- TabIndex.Count = 4
- HorizontalPager `beyondBoundsPageCount = 1`
- 4 个 home screen 函数签名（`onTabSelected: (Int) -> Unit` 等参数保留）

#### Scenario: home Pager 4 tab 配置零回归

- **GIVEN** 实施前后 `TrackTechAppShell.kt` 内 `composable("home") { HorizontalPager(...) { page -> when (page) { ... } } }` block
- **WHEN** `git diff` 该 block
- **THEN** TabIndex 4 个 const + Pager pageCount + beyondBoundsPageCount + 4 个 page 渲染分支零行改动
- **AND** 4 个 home screen 调用点的参数签名不变（仅 LapsHomeScreen 内的 START LAP SESSION onClick body 变化，不影响签名）

### Requirement: TrackTechAppShellPagerTest 静态契约保留

baseline `TrackTechAppShellPagerTest`（上一 round 引入的纯 JUnit4 静态契约测试）MUST 保留，零回归。

#### Scenario: TrackTechAppShellPagerTest 文件保留

- **GIVEN** 实施前后代码库
- **WHEN** `find feature/test/src/test/.../TrackTechAppShellPagerTest.kt`
- **THEN** 该文件存在且测试 case 行为零变化（TabIndex 5 const + DefaultTrackTechTabs 4 项顺序断言）

#### Scenario: 测试零回归

- **GIVEN** 实施后跑 `:feature:test:testDebugUnitTest --tests "*TrackTechAppShellPagerTest*"`
- **WHEN** 检查测试结果
- **THEN** 全绿
