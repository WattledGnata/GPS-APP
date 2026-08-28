## 实施任务（依赖顺序）

本 change 把 `TrackTechAppShell` 的 4 个 tab 切换从 `NavHost.composable` 改为 `HorizontalPager`，覆盖：

- §0 grep 预检
- §1 TabIndex 常量层
- §2 TrackTechAppShell 主体重构（NavHost + Pager + EventBus 监听）
- §3 TrackTechBottomNav 信号源切换
- §4 4 个 home screen 加 onTabSelected 参数 + 删除 navigateToTab
- §5 NavHost 子页转场显式 None
- §6 单元测试 TrackTechAppShellPagerTest
- §7 编译/测试门槛
- §8 真机验证 + 偏差对比
- §9 commit + 合流门槛

参考 `proposal.md` / `design.md` / `specs/track-tech-app-shell/spec.md` / `specs/cross-tab-device-gating/spec.md`。

---

## 0. grep 预检（apply 阶段开工前一次性执行）

- [x] 0.1 **HorizontalPager 可用性核实**：

  ```bash
  grep -rn "androidx.compose.foundation.pager" /Users/wattledgnata/traeProjects/gps-app/feature/test /Users/wattledgnata/traeProjects/gps-app/gradle/libs.versions.toml 2>/dev/null
  ```

  - 若 `feature/test/build.gradle.kts` 已 implementation `androidx.compose.foundation:foundation` ≥ 1.6（含 Pager API），直接使用
  - 若版本 < 1.6 → 升级 foundation 版本到与项目 BOM 对齐的最新

- [x] 0.2 **baseline navigateToTab 调用点核实**：

  ```bash
  grep -rn "navigateToTab" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期命中：
  - `TestHomeScreen.kt:234` `internal fun NavController.navigateToTab` 定义
  - `TestHomeScreen.kt` 内 StatusStrip onClick × 1 + 主操作未 ready 分支 × 2（合计 3 处调用）
  - `LapsHomeScreen.kt` 内 StatusStrip onClick × 1 + START LAP SESSION 未 ready 分支 × 1（合计 2 处调用）

  若实际命中数与预期偏差 ≥ 2，记录到 Open Questions 后再启动改造。

- [x] 0.3 **baseline navController.navigate("device"/"laps"/...) 命中核实**：

  ```bash
  grep -rn 'navController.navigate("\(test\|laps\|records\|device\)"' /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：仅 `TrackTechBottomNav.kt` 内 1 处（`navController.navigate(tab.route)`，由 §3 改造为 `onTabSelected(index)`）。

- [x] 0.4 **NavHost 转场默认值验证**（确认改造目标）：

  ```bash
  grep -rn "enterTransition\|exitTransition\|popEnterTransition\|popExitTransition" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期 baseline 内零命中（NavHost 全部用默认转场），§5 添加 4 个显式 None。

- [x] 0.5 **EventBus collector 现状核实**：

  ```bash
  grep -rn "showScanSheetEvent" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main --include="*.kt"
  ```

  预期：
  - `TrackTechEventBus.kt` 定义 + `requestShowScanSheet()` 方法
  - `DeviceHomeScreen.kt` 内 `LaunchedEffect` 订阅
  - `TestHomeScreen.kt` / `LapsHomeScreen.kt` 内 `requestShowScanSheet()` 调用（未 ready 分支）

  §2 在 `TrackTechAppShell.kt` 加新 collector，不动现有调用。

---

## 1. TabIndex 常量层

- [x] 1.1 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt` 文件顶层（package 声明下方，`@Composable fun TrackTechAppShell()` 上方）添加：

  ```kotlin
  object TabIndex {
      const val Test = 0
      const val Laps = 1
      const val Records = 2
      const val Device = 3
      const val Count = 4
  }
  ```

  TabIndex 与 `DefaultTrackTechTabs`（`TrackTechBottomNav.kt`）的 4 项顺序 MUST 严格对齐。

- [x] 1.2 编译验证：`./gradlew :feature:test:compileDebugKotlin`

---

## 2. TrackTechAppShell 主体重构

- [x] 2.1 **添加 import**：

  ```kotlin
  import androidx.compose.foundation.pager.HorizontalPager
  import androidx.compose.foundation.pager.rememberPagerState
  import androidx.compose.runtime.LaunchedEffect
  import androidx.compose.runtime.rememberCoroutineScope
  import androidx.compose.animation.EnterTransition
  import androidx.compose.animation.ExitTransition
  import kotlinx.coroutines.launch
  ```

- [x] 2.2 **删除旧 `tabRoutes` 常量**（`private val tabRoutes = setOf("test", "laps", "records", "device")` 整行删除，本 change 后 home 是单一路由）。

- [x] 2.3 **重写 `@Composable fun TrackTechAppShell()` 函数体**：

  ```kotlin
  @Composable
  fun TrackTechAppShell() {
      TrackTechTheme {
          val sessionViewModel = koinViewModel<TestSessionViewModel>()
          val navController: NavHostController = rememberNavController()
          val backStack by navController.currentBackStackEntryAsState()
          val currentRoute = backStack?.destination?.route
          val showBottomNav = currentRoute == "home"
          val pagerState = rememberPagerState(pageCount = { TabIndex.Count })
          val coroutineScope = rememberCoroutineScope()

          val onTabSelected: (Int) -> Unit = { index ->
              coroutineScope.launch { pagerState.animateScrollToPage(index) }
          }

          // EventBus 是 SharedFlow(replay=0)，未组合的 page 收不到历史事件。
          // Shell 是唯一可靠 collector：切 Device tab + 设 pending flag，由 DeviceHomeScreen 组合后消费 reset。
          var pendingShowScanSheet by remember { mutableStateOf(false) }
          LaunchedEffect(Unit) {
              TrackTechEventBus.showScanSheetEvent.collect {
                  onTabSelected(TabIndex.Device)
                  pendingShowScanSheet = true
              }
          }

          Scaffold(
              modifier = Modifier
                  .fillMaxSize()
                  .background(TrackTechColors.Background),
              containerColor = TrackTechColors.Background,
              bottomBar = {
                  if (showBottomNav) {
                      TrackTechBottomNav(
                          currentPage = pagerState.currentPage,
                          onTabSelected = onTabSelected,
                      )
                  }
              },
          ) { padding ->
              NavHost(
                  navController = navController,
                  startDestination = "home",
                  modifier = Modifier
                      .fillMaxSize()
                      .padding(padding)
                      .background(TrackTechColors.Background),
                  enterTransition = { EnterTransition.None },
                  exitTransition = { ExitTransition.None },
                  popEnterTransition = { EnterTransition.None },
                  popExitTransition = { ExitTransition.None },
              ) {
                  composable("home") {
                      HorizontalPager(
                          state = pagerState,
                          beyondBoundsPageCount = 1,
                          modifier = Modifier.fillMaxSize(),
                      ) { page ->
                          when (page) {
                              TabIndex.Test -> TestHomeScreen(
                                  navController = navController,
                                  onTabSelected = onTabSelected,
                                  sessionViewModel = sessionViewModel,
                              )
                              TabIndex.Laps -> LapsHomeScreen(
                                  navController = navController,
                                  onTabSelected = onTabSelected,
                                  testSessionViewModel = sessionViewModel,
                              )
                              TabIndex.Records -> RecordsHomeScreen(
                                  navController = navController,
                                  onTabSelected = onTabSelected,
                              )
                              TabIndex.Device -> DeviceHomeScreen(
                                  navController = navController,
                                  onTabSelected = onTabSelected,
                                  pendingShowScanSheet = pendingShowScanSheet,
                                  onPendingShowScanSheetConsumed = { pendingShowScanSheet = false },
                              )
                          }
                      }
                  }
                  composable("test_execution") {
                      TrackTechTestExecutionScreen(
                          navController = navController,
                          sessionViewModel = sessionViewModel,
                      )
                  }
                  composable("gps_details") {
                      GpsDetailsScreen(navController = navController)
                  }
              }
          }
      }
  }
  ```

  注意点：
  - `EventBus` 是 `SharedFlow(replay = 0)`，未组合的 page 不能依赖 collector，**Shell 是唯一可靠 collector**；DeviceHomeScreen 不再直接订阅，改为接收 `pendingShowScanSheet` flag 参数
  - `pagerState.animateScrollToPage` 是 suspend，必须包在 `coroutineScope.launch` 里
  - `showBottomNav` 仅在 `currentRoute == "home"` 时为 true，子页隐藏
  - 需添加 `import androidx.compose.runtime.mutableStateOf` + `import androidx.compose.runtime.remember` + `import androidx.compose.runtime.setValue`

- [x] 2.4 编译验证：`./gradlew :feature:test:compileDebugKotlin`（此时会报 `TestHomeScreen` / `LapsHomeScreen` / `RecordsHomeScreen` / `DeviceHomeScreen` / `TrackTechBottomNav` 签名不匹配，预期 → §3、§4 修复）

---

## 3. TrackTechBottomNav 信号源切换

- [x] 3.1 **重写函数签名**（`TrackTechBottomNav.kt`）：

  ```kotlin
  @Composable
  fun TrackTechBottomNav(
      currentPage: Int,
      onTabSelected: (Int) -> Unit,
      tabs: List<TrackTechTabItem> = DefaultTrackTechTabs,
      modifier: Modifier = Modifier,
  ) {
      Row(
          modifier = modifier
              .fillMaxWidth()
              .height(68.dp)
              .background(TrackTechColors.SurfaceDark)
              .border(1.dp, TrackTechColors.BorderAlpha60)
              .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
          tabs.forEachIndexed { index, tab ->
              val selected = currentPage == index
              TrackTechBottomNavItem(
                  tab = tab,
                  selected = selected,
                  onClick = {
                      if (currentPage != index) {
                          onTabSelected(index)
                      }
                  },
                  modifier = Modifier.weight(1f),
              )
          }
      }
  }
  ```

  关键变化：
  - 删除 `navController: NavController` 参数
  - 删除 `currentBackStackEntryAsState` / `findStartDestination` import 与调用
  - 新增 `currentPage: Int` 与 `onTabSelected: (Int) -> Unit` 参数
  - `forEach` 改为 `forEachIndexed` 拿到 index 喂给 `onTabSelected`

- [x] 3.2 **删除不再使用的 import**：

  ```bash
  # 在 TrackTechBottomNav.kt 内移除以下 import（如还存在）
  import androidx.navigation.NavController
  import androidx.navigation.NavGraph.Companion.findStartDestination
  import androidx.navigation.compose.currentBackStackEntryAsState
  ```

- [x] 3.3 编译验证：`./gradlew :feature:test:compileDebugKotlin`

---

## 4. 4 个 home screen 加 onTabSelected 参数 + 删除 navigateToTab

- [x] 4.1 **`TestHomeScreen.kt`**：

  - 函数签名加 `onTabSelected: (Int) -> Unit` 参数（参数顺序：navController, modifier, sessionViewModel, onTabSelected；默认值 `{}` 可省，由 Shell 显式传）
  - StatusStrip onClick：从 `navController.navigateToTab("device")` 改为 `onTabSelected(TabIndex.Device)`
  - 0-100 主操作未 ready 分支：从 `navController.navigateToTab("device")` 改为 `onTabSelected(TabIndex.Device)`
  - 100-0 主操作未 ready 分支：同上
  - **删除文件末尾的 `internal fun NavController.navigateToTab(route: String)` extension 函数定义**
  - 删除多余的 `androidx.navigation.NavGraph.Companion.findStartDestination` import（如有残留）

- [x] 4.2 **`LapsHomeScreen.kt`**：

  - 函数签名加 `onTabSelected: (Int) -> Unit` 参数
  - StatusStrip onClick：从 `navController.navigateToTab("device")` 改为 `onTabSelected(TabIndex.Device)`
  - START LAP SESSION 主操作未 ready 分支：从 `navController.navigateToTab("device")` 改为 `onTabSelected(TabIndex.Device)`

- [x] 4.3 **`RecordsHomeScreen.kt`**：

  - 函数签名加 `onTabSelected: (Int) -> Unit` 参数（默认值 `= {}`，本 round 内无主动调用，预留接口）
  - 不动其他逻辑

- [x] 4.4 **`DeviceHomeScreen.kt`**：

  - 函数签名加 `onTabSelected: (Int) -> Unit = {}` 参数（默认值 `= {}`，本 round 内 DeviceHomeScreen 不需要主动跳到其他 tab）
  - 函数签名加 `pendingShowScanSheet: Boolean = false` + `onPendingShowScanSheetConsumed: () -> Unit = {}` 两个参数（Pager 架构下 EventBus pending state 由 Shell 持有并向下传递）
  - **删除**原有 `LaunchedEffect(Unit) { TrackTechEventBus.showScanSheetEvent.collect { showSheet = true; gpsViewModel.startScan() } }`（在 Pager `beyondBoundsPageCount=1` 架构下，Device page 通常未组合，SharedFlow(replay=0) 事件直接丢失，事件路由统一从 Shell 走）
  - **新增** `LaunchedEffect(pendingShowScanSheet) { if (pendingShowScanSheet) { showSheet = true; gpsViewModel.startScan(); onPendingShowScanSheetConsumed() } }`：观察 flag 变 true 后展开 sheet + startScan + reset flag

- [x] 4.5 编译验证：`./gradlew :feature:test:compileDebugKotlin`

- [x] 4.6 **navigateToTab 全工程零命中检查**：

  ```bash
  grep -rn "navigateToTab" /Users/wattledgnata/traeProjects/gps-app/feature --include="*.kt"
  ```

  预期：零命中（5 处调用 + 1 处定义全部清除）。

---

## 5. NavHost 子页转场显式 None

- [x] 5.1 已在 §2.3 NavHost 调用中包含 4 个转场参数显式 None，本 task 仅做最终 grep 验证：

  ```bash
  grep -n "enterTransition\|exitTransition\|popEnterTransition\|popExitTransition" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt
  ```

  预期：4 个参数各命中 1 次，全部为 `EnterTransition.None` / `ExitTransition.None` 字面量。

---

## 6. 单元测试 TrackTechAppShellPagerTest

- [x] 6.1 新建 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShellPagerTest.kt`：

  ```kotlin
  package com.blazepush.feature.test.ui.tracktech

  import com.blazepush.feature.test.ui.tracktech.TabIndex
  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TrackTechAppShellPagerTest {

      @Test
      fun `TabIndex constants align with DefaultTrackTechTabs order`() {
          assertEquals(0, TabIndex.Test)
          assertEquals(1, TabIndex.Laps)
          assertEquals(2, TabIndex.Records)
          assertEquals(3, TabIndex.Device)
          assertEquals(4, TabIndex.Count)
      }

      @Test
      fun `DefaultTrackTechTabs has 4 items in TabIndex order`() {
          assertEquals(4, DefaultTrackTechTabs.size)
          assertEquals("test", DefaultTrackTechTabs[TabIndex.Test].route)
          assertEquals("laps", DefaultTrackTechTabs[TabIndex.Laps].route)
          assertEquals("records", DefaultTrackTechTabs[TabIndex.Records].route)
          assertEquals("device", DefaultTrackTechTabs[TabIndex.Device].route)
      }
  }
  ```

  说明：本 round 仅做"静态契约"测试（TabIndex 常量值 + DefaultTrackTechTabs 顺序），**不**引入 `androidx.compose.ui.test.junit4.createComposeRule`（项目当前测试基础设施未引入 Compose UI Test，引入需独立 round 评审）。

- [x] 6.2 编译 + 跑测：`./gradlew :feature:test:testDebugUnitTest --tests "*TrackTechAppShellPagerTest*"`

---

## 7. 编译/测试门槛

- [x] 7.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 7.2 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 7.3 `./gradlew :feature:test:testDebugUnitTest` 全绿（现有测试零回归）
- [x] 7.4 `./gradlew :core:bluetooth:testDebugUnitTest :core:domain:test :core:data:testDebugUnitTest` 全绿（数据层零改动，本 change 仅改 UI 层）

---

## 8. 真机验证 + 偏差对比

- [ ] 8.1 安装到真机 `8KE0219522008434`（默认华为）：

  ```bash
  ./gradlew :app:installDebug
  ```

- [ ] 8.2 验证清单（手动 gate）：
  - 冷启动 → 首页 Test tab 直接显示，**无** scaleIn 放大动画
  - 在 Test tab 向左滑 → Laps tab 跟随手势横向滑入；底部导航 Laps icon 选中
  - 在 Laps tab 点击底部 Records → 平滑切换到 Records tab
  - 在 Test tab 滚动到底部，左滑到 Laps，再右滑回 Test → 滚动位置保留
  - 在 Test tab 点击 0-100（ready 状态下） → 进入 `test_execution` 子页，**无** scaleIn 动画
  - 在 `test_execution` 内点击 CANCEL → 回到 home，bottom nav 重现，仍在 Test tab
  - BLE 未连接时点击 0-100 主操作 → 切到 Device tab，BLE Scan Sheet 自动展开

- [ ] 8.3 真机问题记录：若 8.2 任一项 fail，回滚本 change 到 §0 起点重新评估。

---

## 9. Commit + 合流门槛

- [x] 9.1 **Spec 验证**：`openspec validate switch-tab-shell-to-horizontal-pager --strict` 返回 `Change ... is valid`

- [x] 9.2 **grep 自检**：
  - `TrackTechAppShell.kt` 内 `composable("home")` / `HorizontalPager(` / `rememberPagerState(` / `EnterTransition.None` 各命中 ≥ 1 次
  - `TrackTechBottomNav.kt` 签名含 `currentPage: Int` + `onTabSelected: (Int) -> Unit`，**不**含 `navController` 参数
  - 4 个 home screen 函数签名各含 `onTabSelected: (Int) -> Unit` 参数
  - `tracktech/` 子包内 `navigateToTab` 零命中（定义 + 调用全清）
  - `tracktech/` 子包内 `navController.navigate(` 命中点的字符串字面量参数仅 `"test_execution"` / `"gps_details"`

- [x] 9.3 **下游零回归**：
  - `:core:bluetooth:testDebugUnitTest` ✅
  - `:core:domain:test` ✅
  - `:core:data:testDebugUnitTest` ✅
  - `:app:compileDebugKotlin` ✅
  - `:feature:test:testDebugUnitTest` 全绿（含新 `TrackTechAppShellPagerTest`）

- [ ] 9.4 **真机验证**已完成（§8.2）

- [ ] 9.5 **commit**：`feat(ui): TrackTechAppShell tab 切换从 NavHost 切到 HorizontalPager · 滑动手势 + 去 scaleIn 动画`

  body 要点：
  - **track-tech-app-shell capability 修订**：4 个 tab 切换从 `NavHost.composable` 改为 `HorizontalPager(pageCount=4, beyondBoundsPageCount=1)`；NavHost 仅承载 home + test_execution + gps_details 三个路由；NavHost 4 个转场参数显式 `EnterTransition.None / ExitTransition.None`，根除首次进入 tab 的 fadeIn + scaleIn "从小放大" 动画
  - **cross-tab-device-gating capability 修订**：StatusStrip onClick + 主操作未 ready 分支 + EventBus 跨 tab 触达，全部从 `navController.navigate("device") { saveState/restoreState }` 改为统一 `onTabSelected(TabIndex.Device)` 回调；TrackTechAppShell 在 home composable 内额外监听 `TrackTechEventBus.showScanSheetEvent` 切 Device tab，DeviceHomeScreen 现有 collector 保留展开 sheet 行为
  - **TabIndex 常量层**：Test=0, Laps=1, Records=2, Device=3 + Count=4，统一 page 索引语义，避免 magic number
  - **TrackTechBottomNav 信号源切换**：函数签名从 `navController: NavController` 改为 `currentPage: Int + onTabSelected: (Int) -> Unit`；选中态以 `pagerState.currentPage` 为 single source of truth
  - **navigateToTab helper 删除**：`TestHomeScreen.kt` 末尾 `internal fun NavController.navigateToTab(route: String)` extension 移除；5 处 baseline 调用（TestHomeScreen 3 处 + LapsHomeScreen 2 处）全部迁移为 `onTabSelected(TabIndex.Device)`
  - **零改动**：`core/*` 全部模块 / `simulator/*` / `TestSessionViewModel` / `GpsDataViewModel` / `SmartTestLauncher` / `TrackTechEventBus` 内部实现 / 4 个 home screen 内部布局 / BLE Scan Sheet 内部逻辑 / RaceChrono BLE 协议
  - **测试**：新增 `TrackTechAppShellPagerTest` 4 个静态契约断言（TabIndex 4 个常量值 + DefaultTrackTechTabs 顺序）；现有全套 `:feature:test:testDebugUnitTest` 零回归
  - **真机验证 7 项 manual gate**：冷启动无 scaleIn / 滑动切换 / 点击切换 / 滚动位置保留 / 子页跳转无动画 / popBack 保留 page / 跨 tab 自动展开 sheet 全部通过
  - **合流门槛**：openspec validate --strict ✅ / grep 自检全部通过 ✅

  格式约束：
  - Conventional Commits
  - body 含 capability 名称 + 改动文件清单 + 真机验证状态
  - Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

---

## 10. Post-apply follow-up backlog（不在本 change scope，记录到 commit message）

- 子页（test_execution / gps_details）的 slide 自定义转场（若用户后续想要进入子页带方向感）—— 独立 round
- Pager indicator dots（4 个 page 在 bottom nav 上方加小圆点指示）—— 独立 round
- horizontal swipe gesture 与 verticalScroll 的嵌套滚动调优（如果真机出现"水平滑动被纵向 scroll 吃掉"）—— 独立 round
- TrackTechAppShellPagerTest 升级为 ComposeRule UI test（需引入 `androidx.compose.ui:ui-test-junit4` + `androidx.compose.ui:ui-test-manifest` 测试依赖）—— 独立 round
