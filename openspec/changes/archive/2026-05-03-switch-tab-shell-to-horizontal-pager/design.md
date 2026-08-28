## Context

`add-track-tech-app-shell` 已落地的 `TrackTechAppShell` 用 `NavHost { composable("test"|"laps"|"records"|"device") { ... } }` 实现 4 tab，靠 `popUpTo + saveState + restoreState` 维持各 tab 的内部状态。这个实现满足了"4 tab + 状态保留"的功能要求，但留下两个体感缺陷：

1. Compose Navigation 的默认 enterTransition（`fadeIn() + scaleIn()`）在 tab **首次访问**时一定会触发，无论 saveState/restoreState 怎么配。视觉上是从屏幕中心放大并淡入，与 Track Tech V2 的硬切角、平面赛车风格不和谐。
2. 用户报告希望像 Material You / iOS 主流首页那样左右滑切 tab，目前只能点击底部导航。

约束：
- 不能改 4 个 home screen 内部 UI（不是本 round 的 scope）
- 不能改子页（`test_execution` / `gps_details`）的跳转动画/路径
- 不能新增依赖（HorizontalPager 已是 Compose foundation 自带）
- 不能让 Pager 内的 4 个 page 各自重新订阅 GPS（因为 `GpsDataViewModel` 是 Koin single，订阅它的 hot StateFlow 不会引发额外 BLE 流量，但若 4 个 page 同时活跃地组合，对低端机布局开销需要评估）

## Goals / Non-Goals

**Goals:**

- 4 个 tab 之间用 `HorizontalPager` 滑动切换替代 NavHost 路由切换，去掉首次访问的 scaleIn 动画
- 底部导航与 Pager 双向绑定（点击 → 滚动；滑动 → 更新选中态）
- 跨 tab 触达（StatusStrip onClick / 未 ready 跳 Device tab / EventBus）改造为统一的 `onTabSelected: (Int) -> Unit` 回调
- 子页跳转（`test_execution`、`gps_details`）显式禁用 NavHost 默认动画，避免子页跳转时再触发放大效果
- 单元测试覆盖 page 索引映射 + 子页跳转隐藏 bottom nav 的契约

**Non-Goals:**

- 不重写 4 个 home screen 内部布局
- 不重写子页（`TrackTechTestExecutionScreen` / `GpsDetailsScreen`）
- 不引入 ViewPager（旧 view system），坚持纯 Compose
- 不重新设计底部导航视觉
- 不动 `TestSessionViewModel` / `GpsDataViewModel` / 数据层
- 不动 BLE Scan Sheet 内部逻辑
- 不为子页加滑动手势（子页用 NavHost 单独路由，不在 Pager 内）

## Decisions

### D1：Pager 嵌在单个 NavHost destination `home` 内

**决定**：`TrackTechAppShell` 顶层结构为：

```
TrackTechAppShell()
└── TrackTechTheme
    └── Scaffold(bottomBar = if (currentRoute == "home") TrackTechBottomNav)
        └── NavHost(startDestination = "home", enterTransition = None, exitTransition = None)
            ├── composable("home") { HomePagerHost(...) }
            ├── composable("test_execution") { TrackTechTestExecutionScreen(...) }
            └── composable("gps_details") { GpsDetailsScreen(...) }

HomePagerHost
└── HorizontalPager(state = pagerState, pageCount = 4, beyondBoundsPageCount = 1)
    ├── page 0: TestHomeScreen
    ├── page 1: LapsHomeScreen
    ├── page 2: RecordsHomeScreen
    └── page 3: DeviceHomeScreen
```

**为什么不把 Pager 作为 Shell 的根，让子页用 overlay/sheet**：因为 `test_execution` 是完整的全屏体验，需要独立的 backstack（`navController.popBackStack()` 退回 home）和系统返回键支持，NavHost 的语义最契合。Pager 内的 4 个 home 不进 backstack（tab 切换不入栈），与系统返回键的预期一致（按 back 在 home 页直接退出 app）。

**为什么不每个 tab 一个独立 NavHost route 加自定义 None 转场**：单纯关掉 NavHost 转场只能解决问题 1，解决不了问题 2（滑动手势）。换 Pager 一次性解决两个问题。

**替代方案考虑**：
- ❌ `AnimatedNavHost`（accompanist）+ 自定义 enterTransition：仍是路由切换，无滑动手势
- ❌ Material3 `PrimaryTabRow` + Pager（无 NavHost）：失去子页 backstack 语义
- ✅ NavHost + Pager 嵌套（本方案）：两全

### D2：Pager 与底部导航双向绑定

**决定**：

- `TrackTechAppShell` 持有 `val pagerState = rememberPagerState(pageCount = { 4 })`
- 底部导航 `TrackTechBottomNav` 接受 `currentPage: Int` + `onTabSelected: (Int) -> Unit` 两个参数（替代旧 `navController` 参数）
- 选中态用 `currentPage == index` 判定（不再读 `navController.currentBackStackEntry`）
- 点击 tab 时调用 `onTabSelected(index)`，Shell 内通过 `LaunchedEffect` + `coroutineScope.launch { pagerState.animateScrollToPage(index) }` 触发滚动
- Pager 内的滑动会自动更新 `pagerState.currentPage`，下一个 recomposition 把新选中态喂给底部导航

**信号源单向化**：`pagerState.currentPage` 是 single source of truth；底部导航是它的视图，跨 tab 事件触发的也是它的"setter"（通过 onTabSelected）。

**为什么不让 BottomNav 直接拿 pagerState**：保持组件接口简单。`pagerState` 是 Compose foundation 的对象，把它直接传给 BottomNav 会让 BottomNav 与 Pager 实现细节耦合；用 `Int + (Int) -> Unit` 抽象更稳定，方便未来若改用其他 page indicator 实现。

### D3：跨 tab 事件链路

**决定**：4 个 home screen 的 composable 签名都加 `onTabSelected: (Int) -> Unit` 参数（默认值 `{}`，单测不用全填）。Shell 在 `composable("home")` 内构造 `onTabSelected` lambda，捕获 `pagerState` + `coroutineScope`：

```kotlin
val coroutineScope = rememberCoroutineScope()
val onTabSelected: (Int) -> Unit = { index ->
    coroutineScope.launch { pagerState.animateScrollToPage(index) }
}
```

将这个 lambda 传给 4 个 home screen。home screen 内现有调用点改造：

| 现状 | 改造后 |
|------|--------|
| `navController.navigateToTab("device")` | `onTabSelected(TabIndex.Device)` |
| `navController.navigate("device") { ... }` | `onTabSelected(TabIndex.Device)` |
| `TrackTechEventBus.requestShowScanSheet()` | 保留；触发侧不变 |

**Tab 索引常量**：在 `TrackTechAppShell.kt` 定义 `object TabIndex { const val Test = 0; const val Laps = 1; const val Records = 2; const val Device = 3 }`，避免 home screen 散落 magic number。

**EventBus 改造**：baseline 中 `TrackTechEventBus.showScanSheetEvent` 由 `DeviceHomeScreen` 内的 `LaunchedEffect` 直接 collect 后展开 sheet。**本 round 必须改路由**：

- **真实 bug 场景**（Codex review v3）：EventBus 是 `MutableSharedFlow(extraBufferCapacity = 1, replay = 0)`。Pager `beyondBoundsPageCount = 1` 架构下，用户位于 Test page (index 0) 触发 `requestShowScanSheet()` 时，Device page (index 3) 与当前页距离 3 > 1，**未组合**。Shell collector 收到事件并切到 Device tab，但 Device page 此时才开始组合，DeviceHomeScreen 的 LaunchedEffect 才订阅 EventBus。`replay=0` 对未订阅者已发出的事件不重放，`showSheet` 仍为 `false`，BLE Scan Sheet 不展开 → manual gate "切到 Device tab + sheet 自动展开" 退化为只切 tab。
- **修复方案**：Shell 是 EventBus 的**唯一可靠 collector**。Shell 内持有 `var pendingShowScanSheet by remember { mutableStateOf(false) }`，collect 事件后两步动作并发：(1) `onTabSelected(TabIndex.Device)` 切 tab；(2) `pendingShowScanSheet = true` 设 flag。`DeviceHomeScreen` MUST 接收 `pendingShowScanSheet: Boolean` + `onPendingShowScanSheetConsumed: () -> Unit` 两个参数，组合后 `LaunchedEffect(pendingShowScanSheet)` 观察：true 时设 `showSheet = true` + 调 `gpsViewModel.startScan()` + 调 `onPendingShowScanSheetConsumed()` reset flag。
- **DeviceHomeScreen MUST NOT 直接订阅 EventBus**：避免事件丢失，事件路由统一从 Shell 走。

**为什么不把 EventBus 改成 StateFlow / replay > 0**：保持 EventBus 的事件型语义（一次性 trigger，不应永久持有最后一次值）；改 replay > 0 后历史事件会被重复消费（如 process recreation 后 Device 页再次组合时又自动展开 sheet）。Shell 持有 `pendingShowScanSheet` state 在 Compose 树内消费 + reset，行为更可控。

**替代方案考虑**：
- ❌ home screen 直接接 `pagerState`：耦合过深，单测要 mock pagerState
- ❌ 用全局单例 `TabNavigator`：增加无谓抽象
- ✅ `onTabSelected: (Int) -> Unit` lambda：原生 Compose 习惯，单测一行 `var lastTab = -1; { lastTab = it }` 即可

### D4：子页 NavHost 转场显式 None

**决定**：NavHost 的 `enterTransition` / `exitTransition` / `popEnterTransition` / `popExitTransition` 全部显式设为 `EnterTransition.None` / `ExitTransition.None`：

```kotlin
NavHost(
    navController = navController,
    startDestination = "home",
    enterTransition = { EnterTransition.None },
    exitTransition = { ExitTransition.None },
    popEnterTransition = { EnterTransition.None },
    popExitTransition = { ExitTransition.None },
    ...
)
```

**理由**：从 `home` 跳到 `test_execution` 是子页全屏接管，目前默认动画（fadeIn + scaleIn）会再次出现"从小放大"。按下系统返回 / DONE 按钮回到 home 时，反向动画也是不对称的 scale。统一关掉，子页切换是干净的瞬切，与 Track Tech V2 视觉语言（硬切角、平面）一致。

**为什么不加自定义 slide-in/out**：本 round scope 是"去掉不和谐动画"，加新动画属于 over-design。后续若要加，单独 round 评审。

### D5：beyondBoundsPageCount 设置为 1

**决定**：`HorizontalPager(beyondBoundsPageCount = 1)`。这个参数控制 Pager 在当前 page 左右各预渲染多少 page。设为 1 意味着：

- 显示 page 0（Test）时，page 1（Laps）也已组合
- 用户向右滑可以无延迟看到 Laps 渲染
- page 2（Records）和 page 3（Device）仍懒组合

**为什么不设为 0**：滑动到下一 page 时会有明显的"白屏 → 内容出现"瞬间，体感卡顿。

**为什么不设为 3（全部预渲染）**：4 个 home 同时组合会让首次进入 app 的开销变大（每个 home 都订阅 `GpsDataViewModel` 的 StateFlow + 跑自己的 `remember` 初始化）。`GpsDataViewModel` 是 single 不会触发额外 BLE 流量，但 Compose recomposition 开销会乘以 4。设为 1 在体感与开销间取平衡。

**评估依据**：4 个 home screen 都是 `verticalScroll(rememberScrollState())` + 几个 CutCornerPanel + Text，单 page 组合开销小（<5ms 在中端机上）。`beyondBoundsPageCount = 1` 同时活跃 2 个 page，开销在 10ms 内，可接受。

### D6：底部导航可见性

**决定**：`showBottomNav` 判定从 `currentRoute in tabRoutes`（4 个 tab 路由）改为 `currentRoute == "home"`（单一 home 路由）。

**为什么不直接 `currentRoute != null && currentRoute !in subRoutes`**：明确白名单（"home" 是唯一显示 bottom nav 的路由）比黑名单更安全，未来加新路由不会意外让 bottom nav 在新路由出现。

### D7：单元测试范围（仅静态契约）

**决定**：新增 `TrackTechAppShellPagerTest` **纯 JUnit4 单元测试**（不引入 Compose UI Test 依赖），仅覆盖**静态契约**：

- `TabIndex.Test == 0` / `Laps == 1` / `Records == 2` / `Device == 3` / `Count == 4` 五个常量值断言
- `DefaultTrackTechTabs.size == TabIndex.Count` 与 `DefaultTrackTechTabs[TabIndex.X].route` 与各 tab 路由名称（`"test"` / `"laps"` / `"records"` / `"device"`）逐一对齐

**MUST NOT** 引入 `androidx.compose.ui.test.junit4.createComposeRule` / `performTouchInput { swipeLeft() }` / `onNodeWith*` 等 Compose UI Test API；`feature/test/build.gradle.kts` MUST NOT 在本 round 新增 `ui-test-junit4` / `ui-test-manifest` 测试依赖。

**为什么不做 ComposeRule 运行时测试**：

- 项目当前测试基础设施未引入 Compose UI Test 依赖，引入会显著扩大本 round scope（构建配置 + Robolectric / instrumentation 选型评审）
- 与"交互升级先快跑"目标冲突
- 真实运行时行为（点击 Device tab → currentPage 变化、拖拽滑动 → bottom nav 选中态同步、子页跳转 bottom nav 隐藏 / popBack 后 Pager state 保留）由 `tasks §8 真机 manual gate` 兜底验证

**为什么不写 espresso UI test**：项目当前没有 espresso 基础设施，本 round 也不引入。

**Follow-up backlog**：ComposeRule + 真实滑动 UI test 升级（运行时断言 `pagerState.pageCount` / `swipeLeft()` 触发 currentPage 变化 / configuration change 后 page 保留 / 子页跳转 bottom nav 隐藏 / popBack 后 Pager state 保留等）作为独立 round 处理，引入 `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` 测试依赖时一并评审 Robolectric vs instrumentation test 选型。

## Risks / Trade-offs

[**Pager state 在 process death 后是否保留**] → `rememberPagerState` 自带 `Saver` 支持，`SavedInstanceState` 自动恢复 currentPage。Mitigation：本 round 不写 ComposeRule 运行时测试（D7 决策），configuration change / process death 场景下的 currentPage 保留作为已知行为不主动验证；项目目前没遇到 process death 复现问题。Follow-up backlog 中包含此场景的 ComposeRule 测试。

[**page 同时活跃，多个 home 同时观察 GpsData StateFlow，性能下降**] → `GpsDataViewModel` 是 single，`StateFlow` 是 hot flow，多订阅者只是多一份 `collectAsState`，不会触发额外计算。Mitigation：`beyondBoundsPageCount = 1` 限制活跃 page 数；若真机 60fps 抖动，回退到 0（接受滑动短暂白屏）。

[**TestHomeScreen 的 navigateToTab helper 删除影响别处**] → grep 确认 `tracktech/` 子包内共 5 处调用（`TestHomeScreen.kt` 3 处：StatusStrip + 0-100 / 100-0 主操作未 ready 各 1 处；`LapsHomeScreen.kt` 2 处：StatusStrip + START LAP SESSION 未 ready 各 1 处）+ 1 处 extension 定义。Mitigation：删除前先 grep 全工程，迁移所有 5 处调用点到 `onTabSelected(index)`，再删除 extension 定义。

[**Test tab 内部 nested nav（TestFlowNavigation）状态在 Pager 中保留方式不同**] → Pager 内 page state 由 `rememberSaveable` 提供，但 `TestFlowNavigation` 用的 `var currentRoute by remember { mutableStateOf(...) }` 是非 saveable 的 remember。这意味着进程重建后 nested nav 回到 startDestination。Mitigation：本 round **不改** `TestFlowNavigation`（和提案一致），现状下 NavHost saveState 也不能跨进程死亡保留普通 mutableStateOf，行为未变差。后续若要支持 process death 恢复，单独 round 把 `remember` 改 `rememberSaveable`。

[**EventBus 双订阅者的执行顺序**] → SharedFlow 不保证 collector 顺序。Shell 的 collector 调 `onTabSelected(Device)` 切 tab，DeviceHomeScreen 的 collector 展开 sheet。两者并发执行即可（切 tab 用 `animateScrollToPage` 是 suspend，sheet 状态用 `mutableStateOf`），最终态一致。Mitigation：测试场景验证"任意触发顺序下，最终 Device tab 选中 + sheet 可见"。

[**屏幕宽度大幅变化（折叠屏外屏 → 内屏 / 横竖屏切换）后 Pager 行为**] → `HorizontalPager` 自动按新宽度重新计算 page 宽度，不丢 currentPage。Mitigation：本 round 不验证横屏（项目锁定竖屏），不验证折叠屏（无设备）。

## Migration Plan

无运行时迁移（纯 UI 重构，无数据格式变更）。

实施顺序：

1. 加 `TabIndex` 常量
2. 重构 `TrackTechAppShell`：建 NavHost（home + sub-routes）+ home 内 Pager
3. 重构 `TrackTechBottomNav`：改参数签名，迁移信号源
4. 改 4 个 home screen：加 `onTabSelected` 参数，删除 `navigateToTab` helper
5. 编译 + 真机首次启动验证（Test tab 不再有 scaleIn 动画）
6. 加 `TrackTechAppShellPagerTest`
7. 跑全套 `:feature:test:testDebugUnitTest`

回滚：本 change 是纯 Compose UI 重构，回滚 = 恢复对应 4 个文件即可，无数据迁移。

## Open Questions

无。本 round 决策点已全部在 D1-D7 中拍板。
