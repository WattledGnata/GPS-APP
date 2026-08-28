## MODIFIED Requirements

### Requirement: StatusStrip 点击主动跳转到 Device tab

`TrackTechStatusStrip` MUST 接受 `onClick` lambda 参数（与 baseline 一致），点击后切换到 Device tab。

但 Test/Laps 屏调用 `TrackTechStatusStrip` 时传入的 lambda MUST 改为 `onTabSelected(TabIndex.Device)` 调用，**不再**调用 `navController.navigate("device") { popUpTo(...) ... }`。

`onTabSelected: (Int) -> Unit` 由 `TrackTechAppShell` 在 `composable("home") { ... }` 内构造（参见 `track-tech-app-shell` capability "HorizontalPager 与 BottomNav 双向绑定" Requirement），通过 home screen 函数参数传入。

#### Scenario: TrackTechStatusStrip 接受 onClick

- **GIVEN** 实施后 `TrackTechStatusStrip.kt` 源码
- **WHEN** 阅读 Composable 函数签名
- **THEN** 包含 `onClick: () -> Unit` 或 `onClick: (() -> Unit)? = null` 参数（视觉契约，不变）
- **AND** 实现内对 outermost Modifier 调用 `Modifier.clickable { onClick() }` 或等价交互

#### Scenario: Test/Laps 屏传入 onTabSelected lambda

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 内 `TrackTechStatusStrip` 调用点
- **WHEN** 阅读传入的 onClick lambda
- **THEN** lambda body 含 `onTabSelected(TabIndex.Device)` 或等价 `onTabSelected(3)` 调用
- **AND** **不**含 `navController.navigate("device")` 或 `navController.navigateToTab("device")` 调用

### Requirement: 主操作未 ready 拦截 + Device tab 切换

Test 首页的 `0-100` / `100-0` 主操作和 Laps 首页的 `START LAP SESSION` 主操作 MUST 通过 `TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality): TabReadiness` 派生 enabled 状态。

`readiness.canEnterTestFlow == true` 时主操作正常进入对应 nested screen。
`readiness.canEnterTestFlow == false` 时主操作 onClick MUST：

1. 不进入 nested screen
2. 显示提示（`Toast.makeText(context, "Connect a GPS device first", Toast.LENGTH_SHORT)` 或等价 Snackbar，文案可优化但 MUST 提示用户去 Device tab）
3. 切换到 Device tab，**MUST** 通过 `onTabSelected(TabIndex.Device)` 调用实现（**不再**通过 `navController.navigate("device") { ... }`）
4. 如果 `connectionState == DISCONNECTED`，自动展开 `BleScanBottomSheet`（通过 SharedFlow event bus 通知 Shell，由 Shell collect 后切 Device tab + 设置 `pendingShowScanSheet` flag，再由 `DeviceHomeScreen` 组合后通过 `LaunchedEffect(pendingShowScanSheet)` 消费 flag 设 `showSheet = true` —— 详见后面 ADDED Requirement "TrackTechEventBus 触发 sheet 展开同时切到 Device tab"）

主操作按钮 enabled 状态 SHOULD 直接绑定 `readiness.canEnterTestFlow`，但即使按钮看起来 disabled，本 spec 仍要求 onClick 含上述拦截逻辑（避免 disabled state 不触发 onClick 时无法引导用户）—— 推荐用 `clickable` + 内部分支，而非 `enabled = false`。

#### Scenario: 主操作 onClick 含 TabGatingPolicy 检查

- **GIVEN** 实施后 `TestHomeScreen.kt` 源码
- **WHEN** 阅读 `0-100` 主操作的 onClick 实现
- **THEN** 含对 `TabGatingPolicy.computeTabReadiness(...)` 调用或对 `TabReadiness.canEnterTestFlow` 的判断
- **AND** 含 `if (canEnterTestFlow) { ... } else { ... }` 或等价分支结构

#### Scenario: 主操作 onClick 不调用 SmartTestLauncher.checkLaunchConditions

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 源码
- **WHEN** grep `SmartTestLauncher.checkLaunchConditions` / `SmartTestLauncher().checkLaunchConditions`
- **THEN** 在 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 内零命中（首页主操作 gating MUST NOT 复用 SmartTestLauncher）

#### Scenario: 未 ready 分支调用 onTabSelected 切 Device tab

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 主操作 onClick 的 else 分支
- **WHEN** 阅读分支 body
- **THEN** 含 `onTabSelected(TabIndex.Device)` 或等价 `onTabSelected(3)` 调用
- **AND** **不**含 `navController.navigate("device")` 或 `navController.navigateToTab("device")` 调用

#### Scenario: DISCONNECTED 时自动展开 sheet

- **GIVEN** 实施后 `TestHomeScreen.kt` 或 `LapsHomeScreen.kt` 主操作未 ready 拦截分支
- **WHEN** 阅读自动展开 sheet 的逻辑
- **THEN** 含对 `connectionState == ConnectionState.DISCONNECTED` 或等价的判断
- **AND** 含触发 sheet 展开的事件（如 `events.tryEmit(...)` / `showScanSheetEvent.tryEmit(Unit)` 等 SharedFlow 模式）

#### Scenario: DeviceHomeScreen 通过 pendingShowScanSheet 接收展开 sheet 触发

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 `LaunchedEffect(pendingShowScanSheet) { ... }` block
- **THEN** body 含 `if (pendingShowScanSheet) { showSheet = true; ...; onPendingShowScanSheetConsumed() }` 或等价分支结构
- **AND** `DeviceHomeScreen.kt` 内 grep `TrackTechEventBus.showScanSheetEvent` 零命中（事件路由统一从 Shell 走，详见后面 ADDED Requirement）

## ADDED Requirements

### Requirement: TrackTechEventBus 触发 sheet 展开同时切到 Device tab

`TrackTechAppShell` MUST 在 `composable("home") { ... }` block 内监听 `TrackTechEventBus.showScanSheetEvent`：每收到一次事件，**两步动作必须同时发生**：

1. 调用 `onTabSelected(TabIndex.Device)` 切到 Device tab（`pagerState.animateScrollToPage(3)` 翻译版本）
2. 设置 `pendingShowScanSheet` Boolean state 为 `true`（Shell 内 `var pendingShowScanSheet by remember { mutableStateOf(false) }`）

`DeviceHomeScreen` MUST 接收 `pendingShowScanSheet: Boolean` + `onPendingShowScanSheetConsumed: () -> Unit` 两个参数，组合后通过 `LaunchedEffect(pendingShowScanSheet)` 观察 flag：当 flag 变为 `true` 时设 `showSheet = true` + 调 `gpsViewModel.startScan()` + 调 `onPendingShowScanSheetConsumed()` reset flag。

`DeviceHomeScreen` MUST NOT 直接订阅 `TrackTechEventBus.showScanSheetEvent`：Pager `beyondBoundsPageCount = 1` 架构下，Device page 在用户位于 Test/Laps 触发场景时通常未组合，`SharedFlow(replay=0)` 对未组合订阅者会丢事件；事件路由统一从 Shell 走，Shell 是 EventBus 的唯一可靠 collector。

最终态：用户位于 Device tab 且 BLE Scan Sheet 可见，`pendingShowScanSheet` 已被 reset 为 `false`。

#### Scenario: Shell 内监听 EventBus 切 Device tab + 设 pending flag

- **GIVEN** 实施后 `TrackTechAppShell.kt` 内 `composable("home") { ... }` block
- **WHEN** 阅读 `LaunchedEffect(Unit) { TrackTechEventBus.showScanSheetEvent.collect { ... } }` 或等价订阅
- **THEN** collect lambda body 含 `onTabSelected(TabIndex.Device)` 或等价 pager 滚动调用
- **AND** collect lambda body 含 `pendingShowScanSheet = true` 赋值（Shell 内 `var pendingShowScanSheet by remember { mutableStateOf(false) }`）

#### Scenario: DeviceHomeScreen 接收 pending state 参数

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 `@Composable fun DeviceHomeScreen(...)` 函数签名
- **THEN** 含参数 `pendingShowScanSheet: Boolean`（默认值可省）
- **AND** 含参数 `onPendingShowScanSheetConsumed: () -> Unit`（默认值可省）

#### Scenario: DeviceHomeScreen 通过 LaunchedEffect 消费 pending flag

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 `LaunchedEffect(pendingShowScanSheet) { ... }` 或等价订阅
- **THEN** body 含 `if (pendingShowScanSheet) { ... }` 分支
- **AND** 分支内含 `showSheet = true` 赋值
- **AND** 分支内含 `gpsViewModel.startScan()` 调用
- **AND** 分支内含 `onPendingShowScanSheetConsumed()` 调用（消费后立即 reset，避免重复触发）

#### Scenario: DeviceHomeScreen 不直接订阅 EventBus SharedFlow

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** grep `TrackTechEventBus.showScanSheetEvent` 或 `showScanSheetEvent.collect`
- **THEN** 在 `DeviceHomeScreen.kt` 内零命中（事件路由统一从 Shell 走，避免 SharedFlow(replay=0) 在未组合 page 上的事件丢失）

#### Scenario: Test 首页未 ready 触发 EventBus 后用户落在 Device tab + sheet 可见

- **GIVEN** 用户位于 Test tab，BLE 未连接，点击 0-100 主操作进入未 ready 分支；触发前 Device page 未预组合（Pager `beyondBoundsPageCount = 1` + `currentPage = TabIndex.Test`，Device page 索引差 3）
- **WHEN** 主操作触发 `TrackTechEventBus.requestShowScanSheet()`（baseline 行为，不改）
- **THEN** Shell collector 收到事件后 `currentPage` 切到 `TabIndex.Device == 3`（即 Device tab 选中）
- **AND** Shell `pendingShowScanSheet` flag 变为 `true`
- **AND** Device page 因 Pager 切换组合，`DeviceHomeScreen` 的 `LaunchedEffect(pendingShowScanSheet)` 触发，`showSheet` 变为 `true`，BLE Scan Sheet 可见
- **AND** flag 在消费后被 reset 为 `false`，重复触发同事件可正常工作

### Requirement: 跨 tab 跳转 baseline 信号源迁移完整性

baseline 中 `TestHomeScreen.kt` 与 `LapsHomeScreen.kt` 内现有的所有 `navController.navigateToTab(...)` 调用 **共 5 处** MUST 在本 change 后全部迁移为 `onTabSelected(TabIndex.X)` 调用，分布如下：

- `TestHomeScreen.kt`：StatusStrip onClick × 1 + `0-100` 主操作未 ready 分支 × 1 + `100-0` 主操作未 ready 分支 × 1 = **3 处**
- `LapsHomeScreen.kt`：StatusStrip onClick × 1 + `START LAP SESSION` 主操作未 ready 分支 × 1 = **2 处**

`TestHomeScreen.kt` 内定义的 `internal fun NavController.navigateToTab(route: String)` extension 函数 MUST 删除。

#### Scenario: navigateToTab 调用全部迁移

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 .kt 文件
- **WHEN** grep `navigateToTab(`
- **THEN** 零命中（5 处 baseline 调用 + 1 处 extension 定义全部清除）

#### Scenario: navController.navigate 调用仅用于子页跳转

- **GIVEN** 实施后 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包全部 .kt 文件
- **WHEN** grep `navController.navigate(`
- **THEN** 命中点的 navigate 字符串字面量参数仅可能为 `"test_execution"` / `"gps_details"`（子页路由白名单）
- **AND** 零命中带 `"test"` / `"laps"` / `"records"` / `"device"` 字符串字面量的 `navController.navigate(...)` 调用
