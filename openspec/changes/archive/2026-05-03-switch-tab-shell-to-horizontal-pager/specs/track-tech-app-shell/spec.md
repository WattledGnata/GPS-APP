## MODIFIED Requirements

### Requirement: 4 tab persistent bottom navigation shell

`MainActivity` 内 Compose 入口 MUST 是 `TrackTechAppShell()`（与 baseline 一致），shell 内提供持久 bottom navigation，包含且仅包含 4 个 top-level tab：`Test` / `Laps` / `Records` / `Device`，顺序固定。

shell MUST 用 `androidx.navigation:navigation-compose` 的 `NavHost` 作为子页跳转承载，但 4 个 top-level tab 的切换实现 MUST 改为 `androidx.compose.foundation.pager.HorizontalPager`：

- `NavHost` 的 `startDestination` MUST 为字符串字面量 `"home"`
- `NavHost` MUST 包含至少 3 个 `composable(...)` 路由：`"home"` + `"test_execution"` + `"gps_details"`
- `composable("home") { ... }` block 内部 MUST 调用 `HorizontalPager(state = pagerState, ...) { page -> ... }`，且 `pagerState` MUST 由 `rememberPagerState(pageCount = { 4 })` 创建
- Pager 的 4 个 page 顺序 MUST 与 bottom nav tab 顺序一致（page 0 = Test、page 1 = Laps、page 2 = Records、page 3 = Device），page 内容分别渲染 `TestHomeScreen` / `LapsHomeScreen` / `RecordsHomeScreen` / `DeviceHomeScreen`

#### Scenario: MainActivity 入口仍为 TrackTechAppShell

- **GIVEN** 实施后 `app/src/main/java/com/blazepush/MainActivity.kt` 源码
- **WHEN** grep `TrackTechAppShell()`
- **THEN** `setContent { ... }` 路径命中 `TrackTechAppShell()`

#### Scenario: TrackTechAppShell 顶层 NavHost startDestination 为 home

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `NavHost(...) { ... }` block
- **THEN** `NavHost` 的 `startDestination` 参数值为字符串字面量 `"home"`
- **AND** 包含 `composable("home") { ... }` 一处
- **AND** 包含 `composable("test_execution") { ... }` 一处
- **AND** 包含 `composable("gps_details") { ... }` 一处
- **AND** **不**包含 `composable("test")` / `composable("laps")` / `composable("records")` / `composable("device")` 任意一处（4 个 tab 不再是 NavHost 路由）

#### Scenario: home composable 内含 HorizontalPager

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `composable("home") { ... }` block 内部
- **THEN** 含 `HorizontalPager(` 调用
- **AND** Pager 的 `state` 参数引用 `rememberPagerState(pageCount = { 4 })` 创建的 `PagerState`（变量名不限，但 `pageCount` lambda MUST 返回字面量 `4`）
- **AND** Pager 的 `beyondBoundsPageCount` 参数 MUST 显式设为 `1`（D5 决策，避免相邻 page 滑动白屏）

#### Scenario: Pager page 索引到 home screen 的映射

- **GIVEN** 实施后 `TrackTechAppShell.kt` 内 `HorizontalPager` 的 page content lambda
- **WHEN** 阅读 `when (page) { 0 -> ... 1 -> ... 2 -> ... 3 -> ... }` 或等价分支
- **THEN** `page == 0` 渲染 `TestHomeScreen(...)`
- **AND** `page == 1` 渲染 `LapsHomeScreen(...)`
- **AND** `page == 2` 渲染 `RecordsHomeScreen(...)`
- **AND** `page == 3` 渲染 `DeviceHomeScreen(...)`

#### Scenario: TabIndex 常量定义

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechAppShell.kt` 或同子包独立文件
- **WHEN** grep `object TabIndex` 或 `const val Test = 0`
- **THEN** 命中 `TabIndex` 单例（或等价常量集合），包含 `Test = 0` / `Laps = 1` / `Records = 2` / `Device = 3` 四个 const

#### Scenario: TrackTechBottomNav 渲染 4 tab item（视觉零回归）

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 tab item 列表定义
- **THEN** 含 4 个条目，路由分别为 `test` / `laps` / `records` / `device`，label 分别为 `Test` / `Laps` / `Records` / `Device`，顺序匹配 spec
- **AND** bottom nav 高度 SHOULD 落在 `60.dp` 至 `76.dp` 之间（baseline guidance 不变）

### Requirement: tab 间状态保持

切换 tab 时 MUST 保留各 tab 的 UI state。本 change 用 `HorizontalPager` 替代 NavHost 路由切换后，状态保留通过"4 个 page 同时驻留在 Pager 内"的方式实现：每个 home screen 的 `remember { ... }` 状态自然保留，因为 page Composable 在 Pager 切换时不被销毁。

`NavHost` 的 `popUpTo + saveState + restoreState` 配置在本 change 后 **MUST NOT** 用于 4 个 tab 之间的切换；这套配置仅可能出现在 home → 子页 → home 的场景，但本 change 的 NavHost 也 MUST NOT 在子页跳转中使用 `popUpTo + saveState/restoreState`（子页 popBackStack 即可，无需 saveState）。

#### Scenario: 切走再切回保留 home screen 状态

- **GIVEN** 用户在 Test tab 内滚动到屏幕底部
- **WHEN** 用户向左滑切到 Laps tab，再向右滑切回 Test tab
- **THEN** Test tab 的 `verticalScroll` 滚动位置 MUST 保留（Pager 内 page Composable 同时存活，`rememberScrollState` 状态不销毁）

#### Scenario: TrackTechAppShell 内 NavHost 不调用 saveState/restoreState

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** grep `saveState\s*=\s*true` 与 `restoreState\s*=\s*true`
- **THEN** 在 `TrackTechAppShell.kt` 内零命中（NavHost 仅做单层路由切换，不再依赖 saveState 机制）

#### Scenario: TrackTechBottomNav 内 navigate 调用零命中

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 源码
- **WHEN** grep `navController.navigate(`
- **THEN** 在 `TrackTechBottomNav.kt` 内零命中（点击 tab 不再走 NavHost navigate，改为调用 `onTabSelected(index)` 回调）

## ADDED Requirements

### Requirement: HorizontalPager 与 BottomNav 双向绑定

`TrackTechAppShell` MUST 把 `pagerState.currentPage` 作为 4 个 tab 选中态的 single source of truth：

- `TrackTechBottomNav` Composable 函数签名 MUST 接受 `currentPage: Int` 与 `onTabSelected: (Int) -> Unit` 参数（替代 baseline 的 `navController: NavController` 参数）
- 选中态判定 MUST 用 `currentPage == index`
- 点击 tab item MUST 调用 `onTabSelected(index)`，**不**直接调用 `pagerState.animateScrollToPage`（避免 BottomNav 与 Pager 实现细节耦合）
- `TrackTechAppShell` 在 `composable("home") { ... }` 内 MUST 通过 `rememberCoroutineScope` 与 `LaunchedEffect`（或等价 `coroutineScope.launch { pagerState.animateScrollToPage(index) }`）将 `onTabSelected(index)` 翻译为 Pager 滚动

#### Scenario: TrackTechBottomNav 函数签名

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 `@Composable fun TrackTechBottomNav(...)` 签名
- **THEN** 包含参数 `currentPage: Int`
- **AND** 包含参数 `onTabSelected: (Int) -> Unit`
- **AND** **不**包含 `navController: NavController` 或 `navController: NavHostController` 参数

#### Scenario: TrackTechBottomNav 选中态读 currentPage

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 tab item 选中态判定逻辑
- **THEN** 含对 `currentPage` 参数的引用（如 `selected = currentPage == index` 或等价表达）
- **AND** **不**包含 `navController.currentBackStackEntryAsState()` / `backStack?.destination?.route` 等 NavHost 信号源

#### Scenario: 点击 tab 触发 onTabSelected 回调

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 tab item 的 `onClick` 实现
- **THEN** 含 `onTabSelected(index)` 或等价对 `onTabSelected` lambda 参数的调用
- **AND** **不**直接调用 `pagerState.animateScrollToPage(...)`

#### Scenario: Shell 内 onTabSelected 翻译为 pager 滚动

- **GIVEN** 实施后 `TrackTechAppShell.kt` 内 `composable("home") { ... }` block
- **WHEN** 阅读传给 `TrackTechBottomNav` 的 `onTabSelected` lambda
- **THEN** lambda body 含 `pagerState.animateScrollToPage(...)` 或 `pagerState.scrollToPage(...)` 调用
- **AND** 调用包裹在 `coroutineScope.launch { ... }`（或等价 suspend 调度）内

### Requirement: home screen 跨 tab 跳转用 onTabSelected 回调

`TestHomeScreen` / `LapsHomeScreen` / `RecordsHomeScreen` / `DeviceHomeScreen` MUST 在函数签名中接受 `onTabSelected: (Int) -> Unit` 参数（默认值可省略或填空 lambda）。

home screen 内任何"跳到另一 tab"的需求 MUST 调用 `onTabSelected(TabIndex.X)`，**不**调用 `navController.navigate("device"/"laps"/...)` 或 `navController.navigateToTab(...)`。

baseline 中 `TestHomeScreen.kt` 内的 `internal fun NavController.navigateToTab(route: String)` extension MUST 删除。

#### Scenario: 4 个 home screen 函数签名含 onTabSelected

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` / `RecordsHomeScreen.kt` / `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读各自 `@Composable fun XxxHomeScreen(...)` 函数签名
- **THEN** 每个 home screen 签名包含 `onTabSelected: (Int) -> Unit` 参数（参数顺序不限，可有默认值）

#### Scenario: home screen 内不调用 navController.navigate 切 tab

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` / `RecordsHomeScreen.kt` / `DeviceHomeScreen.kt` 源码
- **WHEN** grep `navController.navigate("test")` / `navController.navigate("laps")` / `navController.navigate("records")` / `navController.navigate("device")`
- **THEN** 4 个 home screen 内零命中（跨 tab 跳转必经 `onTabSelected`）

#### Scenario: navigateToTab extension 已删除

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 .kt 文件
- **WHEN** grep `fun NavController.navigateToTab` 或 `navigateToTab(`
- **THEN** 零命中（baseline 中此 helper 已迁移为 onTabSelected）

#### Scenario: home screen 子页跳转仍用 navController.navigate

- **GIVEN** 实施后 `TestHomeScreen.kt` 源码
- **WHEN** 阅读"主操作 ready 分支"的 onClick 实现
- **THEN** 含 `navController.navigate("test_execution")` 或等价跳转语句（子页跳转仍走 NavHost，不变）

### Requirement: 子页 NavHost 转场显式 None

`TrackTechAppShell` 内 `NavHost(...)` 的 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition` 参数 MUST 显式设为 `EnterTransition.None` / `ExitTransition.None`，禁用 Compose Navigation 默认 fadeIn + scaleIn 动画。

#### Scenario: NavHost 4 个转场参数显式 None

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `NavHost(navController = ..., startDestination = "home", ...)` 调用
- **THEN** 含 `enterTransition = { EnterTransition.None }` 或等价 lambda 返回 `EnterTransition.None`
- **AND** 含 `exitTransition = { ExitTransition.None }` 或等价
- **AND** 含 `popEnterTransition = { EnterTransition.None }` 或等价
- **AND** 含 `popExitTransition = { ExitTransition.None }` 或等价

#### Scenario: 进入 test_execution 子页无放大动画

- **GIVEN** 用户在 Test tab，点击 0-100 主操作（ready 分支）
- **WHEN** Shell 触发 `navController.navigate("test_execution")`
- **THEN** 子页瞬时出现，无 scaleIn / fadeIn 过渡（D4 决策）

### Requirement: BottomNav 可见性绑定 home 路由

`TrackTechAppShell` 内 `Scaffold(bottomBar = { ... })` 的可见性判定 MUST 改为 `currentRoute == "home"`（baseline 是 `currentRoute in tabRoutes`，包含 4 个 tab 路由名称）。

进入 `test_execution` / `gps_details` 子页时 bottom nav 隐藏，回到 `home` 后重现。

#### Scenario: bottomBar 仅在 home 路由显示

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `Scaffold(bottomBar = { if (showBottomNav) TrackTechBottomNav(...) })` 或等价结构
- **THEN** `showBottomNav` 派生表达式含 `currentRoute == "home"` 或等价单值比较
- **AND** **不**包含对 `tabRoutes` 集合的 `in` 判断（旧的 `currentRoute in tabRoutes` 已不再适用）

#### Scenario: 进入子页 bottom nav 隐藏

- **GIVEN** 用户在 Test tab（home），点击 0-100 主操作进入 `test_execution`
- **WHEN** Shell recomposition 后渲染 Scaffold
- **THEN** bottom nav 不可见（`currentRoute == "test_execution"` 时 `showBottomNav = false`）

#### Scenario: 子页 popBack 后 bottom nav 重现且 Pager state 保留

- **GIVEN** 用户从 Test tab 进入 `test_execution` 子页，再 popBack 回 home
- **WHEN** Shell recomposition 后渲染 Scaffold
- **THEN** bottom nav 重现（`currentRoute == "home"` 时 `showBottomNav = true`）
- **AND** Pager 的 `currentPage` 保留为离开 home 时的值（即仍是 Test tab；Pager state 由 `rememberPagerState` 提供 `Saver` 自动保留）

### Requirement: HorizontalPager 静态契约测试

新增 `TrackTechAppShellPagerTest`（位于 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/`）MUST 覆盖以下**静态契约**：

- `TabIndex.Test == 0` / `TabIndex.Laps == 1` / `TabIndex.Records == 2` / `TabIndex.Device == 3` / `TabIndex.Count == 4`
- `DefaultTrackTechTabs.size == TabIndex.Count`，且 `DefaultTrackTechTabs[TabIndex.X].route` 与各 tab 路由名称（`"test"` / `"laps"` / `"records"` / `"device"`）逐一对齐

测试 MUST 用纯 JUnit4 单元测试（`org.junit.Test` + `org.junit.Assert.assertEquals`）。

测试 MUST NOT 引入 `androidx.compose.ui.test.junit4.createComposeRule` / `performTouchInput { swipeLeft() }` 等 Compose UI Test API：

- 项目当前测试基础设施未引入 `androidx.compose.ui:ui-test-junit4` / `androidx.compose.ui:ui-test-manifest` 测试依赖
- 引入这些依赖会显著扩大本 round 的 scope（构建配置 + Robolectric/instrumentation 选型评审）
- 真实滑动行为（pager 跟手切换 / 选中态同步 / page state 保留）由 `tasks §8 真机 manual gate` 兜底验证，与本 round "交互升级先快跑" 目标一致

ComposeRule + 真实滑动测试升级（`pagerState.pageCount` 运行时断言、`swipeLeft()` 触发 `currentPage` 变化等）作为 follow-up backlog 在独立 round 处理。

#### Scenario: TrackTechAppShellPagerTest 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `find feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech -name "TrackTechAppShellPagerTest.kt"`
- **THEN** 命中

#### Scenario: 测试断言 TabIndex 5 个常量值

- **GIVEN** 实施后 `TrackTechAppShellPagerTest.kt` 源码
- **WHEN** 阅读测试 body
- **THEN** 含对 `TabIndex.Test == 0` / `TabIndex.Laps == 1` / `TabIndex.Records == 2` / `TabIndex.Device == 3` / `TabIndex.Count == 4` 的常量值断言（一个测试 case 内集中断言即可）

#### Scenario: 测试断言 DefaultTrackTechTabs 顺序

- **GIVEN** 实施后 `TrackTechAppShellPagerTest.kt` 源码
- **WHEN** 阅读测试 body
- **THEN** 含 `DefaultTrackTechTabs.size == 4`（或 `== TabIndex.Count`）断言
- **AND** 含 `DefaultTrackTechTabs[TabIndex.Test].route == "test"` / `[TabIndex.Laps].route == "laps"` / `[TabIndex.Records].route == "records"` / `[TabIndex.Device].route == "device"` 4 条断言

#### Scenario: 测试 MUST NOT 引入 Compose UI Test 依赖

- **GIVEN** 实施后 `TrackTechAppShellPagerTest.kt` 源码
- **WHEN** grep `import androidx.compose.ui.test`
- **THEN** 零命中（不引入 `createComposeRule` / `ComposeContentTestRule` / `performTouchInput` / `onNodeWith*` 等 Compose UI Test API）
- **AND** 同样在 `feature/test/build.gradle.kts` 内 grep `ui-test-junit4` / `ui-test-manifest` → 本 change 不新增这两个依赖

#### Scenario: 真实滑动行为由真机 manual gate 兜底

- **GIVEN** `tasks.md §8 真机验证 + 偏差对比` 节
- **WHEN** 阅读 §8.2 验证清单
- **THEN** 含"向左/右滑切 tab"、"滚动位置保留"、"popBack 后 Pager state 保留"等真实滑动行为验证项
