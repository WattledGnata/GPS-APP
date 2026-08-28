## ADDED Requirements

### Requirement: 4 tab persistent bottom navigation shell

`MainActivity` 内 Compose 入口 MUST 是 `TrackTechAppShell()`（替代现有 `TestFlowNavigation()` 直接调用），shell 内提供持久 bottom navigation，包含且仅包含 4 个 top-level tab：`Test` / `Laps` / `Records` / `Device`，顺序固定。

shell MUST 用 `androidx.navigation:navigation-compose` 的 `NavHost` 实现，4 个 tab 路由名称固定为 `test` / `laps` / `records` / `device`，startDestination 为 `test`。

#### Scenario: MainActivity 入口替换为 TrackTechAppShell

- **GIVEN** 实施后 `app/src/main/java/com/blazepush/MainActivity.kt` 源码
- **WHEN** grep `TrackTechAppShell()` / `TestFlowNavigation()`
- **THEN** `setContent { ... NeonTheme { Surface { TrackTechAppShell() } } }` 路径命中 `TrackTechAppShell()`
- **AND** `TestFlowNavigation()` 在 `MainActivity.kt` 文件内不再被直接调用（仍可作为 Test tab 内嵌 nested nav 在 `TestHomeScreen` 间接调用，或保留 import）

#### Scenario: TrackTechAppShell 包含 4 个固定 top-level 路由

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `NavHost(...) { ... }` block
- **THEN** 包含 `composable("test") { ... }` / `composable("laps") { ... }` / `composable("records") { ... }` / `composable("device") { ... }` 各一处
- **AND** `NavHost` 的 `startDestination` 参数值为字符串字面量 `"test"`

#### Scenario: TrackTechBottomNav 渲染 4 tab item

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 tab item 列表定义
- **THEN** 含 4 个条目，路由分别为 `test` / `laps` / `records` / `device`，label 分别为 `Test` / `Laps` / `Records` / `Device`，顺序匹配 spec

#### Scenario: bottom nav 高度约 68dp + 固定 padding 16dp

- **GIVEN** 实施后 `TrackTechBottomNav` 渲染
- **WHEN** 检查 `Modifier` 链
- **THEN** 高度 SHOULD 落在 `60.dp` 至 `76.dp` 之间（guidance §Layout Guidance "bottom nav 高度约 68dp"）
- **AND** content padding 横向 `16.dp` ± `4.dp`

### Requirement: tab 间状态保持

切换 tab 时 MUST 保留各 tab 的 nested navigation back stack 和 UI state。NavHost 调用 `navController.navigate(...)` 时 MUST 设置 `popUpTo(graph.startDestinationId) { saveState = true }` + `launchSingleTop = true` + `restoreState = true`。

#### Scenario: 切走再切回保留 nested back stack

- **GIVEN** 用户在 Test tab 内导航 `Test → Selection → Execution`
- **WHEN** 用户点击底部 Laps tab 切走，再点击底部 Test tab 切回
- **THEN** Test tab 内仍处于 `Execution` 屏（不是首屏 `TestHomeScreen`）
- **AND** Compose state（如 `remember`）保留

#### Scenario: navigate 调用包含 saveState/restoreState

- **GIVEN** 实施后 `TrackTechBottomNav.kt` 源码
- **WHEN** 阅读 `onClick` 内 `navController.navigate(route) { ... }` block
- **THEN** block 内含 `popUpTo(...) { saveState = true }` + `launchSingleTop = true` + `restoreState = true` 三行配置

### Requirement: Test tab 内嵌 TestFlowNavigation nested nav

Test tab 路由 `test` 对应的 Composable MUST 在内部继续支持现有 `TestFlowNavigation` 的 nested 路径（`Selection → Execution → Result/History`），但 nested startDestination MUST 改为 `Selection`（不再是 `Connection`）。

`TestFlowNavigation.kt` 的 `Connection` sealed object 路由 MUST 保留作 transitional fallback（旧代码若调用 `setRoute(TestNavRoute.Connection)` 不报错），但 `var currentRoute by remember { mutableStateOf<TestNavRoute>(TestNavRoute.Connection) }` MUST 改为 `mutableStateOf<TestNavRoute>(TestNavRoute.Selection)`。

#### Scenario: TestFlowNavigation startDestination 改为 Selection

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` 源码
- **WHEN** grep `mutableStateOf<TestNavRoute>(TestNavRoute.`
- **THEN** 命中 `TestNavRoute.Selection`，**不**命中 `TestNavRoute.Connection`

#### Scenario: TestNavRoute.Connection 路由保留

- **GIVEN** 实施后 `TestFlowNavigation.kt` 源码
- **WHEN** grep `object Connection : TestNavRoute()`
- **THEN** 命中（路由定义保留作 transitional fallback）

### Requirement: Laps / Records / Device tab 首页骨架交付

每个 tab 路由 MUST 对应一个 Composable 屏：

- `test` → `TestHomeScreen` 或在 Test tab 内调 `TestFlowNavigation()`
- `laps` → `LapsHomeScreen`（首页骨架，含 Current Track + START LAP SESSION 占位 + RECENT BEST + Nearby Tracks 占位）
- `records` → `RecordsHomeScreen`（首页骨架，含 PERFORMANCE | LAPS Segmented + Speed Curve 占位 + Recent Runs 列表）
- `device` → `DeviceHomeScreen`（连接控制台，详见 `device-home-connection-console` capability）

每个 home screen 文件 MUST 存在于 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包。

#### Scenario: 4 个 home screen 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `find feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/ -name "TestHomeScreen.kt" -o -name "LapsHomeScreen.kt" -o -name "RecordsHomeScreen.kt" -o -name "DeviceHomeScreen.kt"`
- **THEN** 4 个文件全部命中

#### Scenario: LapsHomeScreen 含骨架结构

- **GIVEN** 实施后 `LapsHomeScreen.kt` 源码
- **WHEN** grep section 标题字符串字面量
- **THEN** 命中 `START LAP SESSION` / `CHANGE TRACK` / `RECENT BEST` / `NEARBY TRACKS` 四个字面量（顺序不限）

#### Scenario: RecordsHomeScreen 含骨架结构

- **GIVEN** 实施后 `RecordsHomeScreen.kt` 源码
- **WHEN** grep section 标题字符串字面量
- **THEN** 命中 `PERFORMANCE` / `LAPS` Segmented label + `RECENT RUNS` 字面量

### Requirement: GpsDataViewModel 跨 tab 共享（Application singleton 复用）

每个 tab home screen 内 MUST 通过 `koinInject<GpsDataViewModel>()` 拿到同一 Application singleton 实例（现状 `feature/test/.../di/AppModule.kt:121` 已是 `single { GpsDataViewModel(get(), get(), get()) }`）。

本 change MUST NOT 修改 `AppModule.kt` 的 `GpsDataViewModel` DI scope，MUST NOT 引入 nav graph viewModel scope（避免 BLE 生命周期泄漏）。

#### Scenario: AppModule.kt GpsDataViewModel 注册保持不变

- **GIVEN** 实施前后 `feature/test/.../di/AppModule.kt` 内 `GpsDataViewModel` 行
- **WHEN** diff 该行
- **THEN** 改动量 0（保持 `single { GpsDataViewModel(get(), get(), get()) }`）

#### Scenario: 4 个 home screen 都用 koinInject 取 ViewModel

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` / `DeviceHomeScreen.kt` 内任一引用 `GpsDataViewModel` 的位置
- **WHEN** 阅读取实例代码
- **THEN** 用 `koinInject<GpsDataViewModel>()` 或等价 Koin Compose API（`getViewModel<GpsDataViewModel>()` 等），**不**手动 `new GpsDataViewModel(...)`

### Requirement: TrackTechTheme 与 NeonTheme 嵌套

`MainActivity:setContent` 内 MUST 保留外层 `NeonTheme { Surface { ... } }` 包装（最小改动原则），`TrackTechAppShell` 内部 MUST 在最外层用 `TrackTechTheme { ... }` 提供 Track Tech color/typography CompositionLocal。

#### Scenario: NeonTheme 外层保留

- **GIVEN** 实施后 `MainActivity.kt` 源码
- **WHEN** 阅读 `setContent { ... }` block
- **THEN** 外层仍含 `NeonTheme { Surface(...) { TrackTechAppShell() } }` 形态

#### Scenario: TrackTechAppShell 内部用 TrackTechTheme

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读最外层 Composable
- **THEN** 包含 `TrackTechTheme { ... }` 包装层（提供 `LocalTrackTechColors` / `LocalTrackTechTypography` 等 CompositionLocal）

### Requirement: Compose Navigation 依赖必需引入

如果 `app/build.gradle.kts` 或 `feature/test/build.gradle.kts` 在 baseline 中未引入 `androidx.navigation:navigation-compose`，本 change MUST 同步加 dependency 到 `feature/test/build.gradle.kts` 的 `implementation(...)` 列表。

#### Scenario: navigation-compose dependency 可用

- **GIVEN** 实施后 `feature/test/build.gradle.kts`（或必要时 `app/build.gradle.kts`）
- **WHEN** grep `navigation-compose`
- **THEN** 至少在一个 build.gradle.kts 文件内命中（version 不限，但 SHOULD 与项目其他 androidx 依赖版本对齐）
