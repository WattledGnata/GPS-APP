## Why

当前 `TrackTechAppShell` 使用 `NavHost + composable {}` 渲染 4 个 tab，存在两个用户可见的体验问题：

1. **首次进入 tab 出现 fadeIn + scaleIn "从小放大"动画**：Compose Navigation 默认 enterTransition 是 `fadeIn() + scaleIn()`，每个 tab 第一次访问时都会触发，与 Track Tech V2 的硬朗赛车感视觉风格不匹配。`saveState/restoreState` 只在已访问过的 tab 上跳过，无法消除首次访问的 scale-in。
2. **不支持手势滑动切换 tab**：tab 间切换只能点击底部导航 item，缺少手机原生的左右滑动手势。

修复时机：A56（unify-gps-telemetry-persistence）已 review 通过、UI 接线刚完成，现在切到 Pager 不会与其他活跃 change 冲突；继续推迟会让用户每次都在首次进入 tab 时看到那个不和谐的放大动画。

## What Changes

- **顶层架构调整**：`TrackTechAppShell` 内 4 个 tab 路由从 `NavHost.composable("test"/"laps"/"records"/"device")` 改为单个 `composable("home")`，内含 `HorizontalPager(pageCount=4)`。NavHost 仅保留作为 `home ↔ 子页（test_execution / gps_details）` 跳转的承载。
- **底部导航信号源切换**：`TrackTechBottomNav` 的选中态从 `navController.currentBackStackEntryAsState().destination.route` 改为 `pagerState.currentPage`；点击 tab 从 `navController.navigate(route)` 改为 `pagerState.animateScrollToPage(index)`。
- **底部导航可见性逻辑**：`showBottomNav` 判断从 "currentRoute in tabRoutes" 改为 "currentRoute == \"home\""；进入子页（`test_execution` / `gps_details`）时仍隐藏底部导航。
- **跨 tab 事件改造**：`TrackTechStatusStrip` onClick、`PrimaryActionPanel` 未 ready 分支等需要"切到 Device tab"的代码从 `navController.navigate("device") { ... }` 改为 `onTabSelected(deviceIndex)` 回调，由 Shell 翻译为 `pagerState.animateScrollToPage`。`TrackTechEventBus.requestShowScanSheet` 在 Shell 监听后同时触发切到 Device tab + 展开 sheet。
- **去掉 fadeIn + scaleIn**：Pager 内的 page 切换走 Pager 自带的水平滑动过渡，子页（`test_execution`）的 NavHost enter/exit 显式设为 `EnterTransition.None / ExitTransition.None`，避免子页跳转时再触发放大动画。
- **`navigateToTab` 内部 helper**（`TestHomeScreen.kt:234`）：从 `navController.navigate(route) { ... }` 改为调用上层传下来的 `onTabSelected(index)` 回调；helper 重命名为 `onTabSelected: (Int) -> Unit` 参数。

## Capabilities

### New Capabilities

无新 capability。

### Modified Capabilities

- `track-tech-app-shell`: 4 tab 切换实现从 NavHost 路由改为 HorizontalPager；新增 page 间状态共存与滑动手势要求；`navigate(tab) { saveState/restoreState }` 不再适用（Pager 内 page 自动同时存活），相关 Requirement 删除/重写。
- `cross-tab-device-gating`: StatusStrip onClick / 未 ready 分支跳 Device tab 的实现从 `navController.navigate("device")` 改为 `onTabSelected(Int)` 回调；`TrackTechEventBus.requestShowScanSheet` 触达侧从 `LaunchedEffect` 内只展开 sheet 改为同时切 Device tab + 展开 sheet。

## Impact

### 受影响代码

- `feature/test/.../ui/tracktech/TrackTechAppShell.kt` — 主体重构（NavHost → 嵌套 Pager + sub-route NavHost）
- `feature/test/.../ui/tracktech/TrackTechBottomNav.kt` — 信号源 + onClick 改造
- `feature/test/.../ui/tracktech/TestHomeScreen.kt` — 加 `onTabSelected: (Int) -> Unit` 参数；删除 `navigateToTab` helper
- `feature/test/.../ui/tracktech/LapsHomeScreen.kt` — 加 `onTabSelected` 参数；StatusStrip onClick 用回调
- `feature/test/.../ui/tracktech/RecordsHomeScreen.kt` — 加 `onTabSelected` 参数（保留 future 跨 tab 跳转空间）
- `feature/test/.../ui/tracktech/DeviceHomeScreen.kt` — 加 `onTabSelected` 参数；EventBus collect 触达保持
- `feature/test/.../ui/tracktech/BleScanBottomSheet.kt` — 不改

### 不受影响

- `core/*` 全部模块、`simulator/*` 全部模块
- `TestSessionViewModel` / `GpsDataViewModel` / `SmartTestLauncher` / `LapTimingEngine`
- 单个 home screen 内部布局（不改 page header / hero / metric tile）
- BLE 扫描连接链路（连接、scan sheet 5 状态机、deviceClassification 全部不动）
- 公共 RaceChrono BLE 协议（不动）

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 依赖

- `androidx.compose.foundation.pager.HorizontalPager` 已是 Compose foundation 自带，无需新增依赖
- `androidx.compose.foundation.pager.rememberPagerState` 同上

### 测试影响

- 新增：`TrackTechAppShellPagerTest`（page 数量 = 4 + tab/page 索引映射 + 子页跳转隐藏底部导航）
- 现有 `TabGatingPolicyTest` 不动（policy 纯函数无变化）
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归
