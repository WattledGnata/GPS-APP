# 实施任务（依赖顺序）

本 change 包含 4 个 capability，覆盖 IA 重构 + 视觉基础组件 + 4 tab 首页 + Device 控制台 + BLE 弹层 + cross-tab gating。

- **§0 grep 预检**：依赖 / 旧路由调用 / icon 可用性
- **§1-§9 视觉基础组件层**：theme + tokens + 9 个组件
- **§10 Shell 接线 + §11 4 个 tab 首页 + §12 Device Home + §13 BLE Sheet**：UI 层主体
- **§14 Cross-tab gating + SharedFlow event bus**：交互连通
- **§15 编译/测试门槛 + §16 真机截图 + §17 commit**

参考 `proposal.md` / `design.md` / `specs/track-tech-app-shell/spec.md` / `specs/device-home-connection-console/spec.md` / `specs/ble-scan-bottom-sheet/spec.md` / `specs/cross-tab-device-gating/spec.md` / `docs/design/track-tech-visual-tokens.md`。

---

## 0. grep 预检（apply 阶段开工前一次性执行）

- [ ] 0.1 **navigation-compose 依赖核实**：

  ```bash
  grep -rn "navigation-compose" /Users/wattledgnata/traeProjects/gps-app/feature/test/build.gradle.kts /Users/wattledgnata/traeProjects/gps-app/app/build.gradle.kts /Users/wattledgnata/traeProjects/gps-app/build.gradle.kts /Users/wattledgnata/traeProjects/gps-app/gradle/libs.versions.toml 2>/dev/null
  ```

  - 命中 → 直接消费
  - 未命中 → §10.1 加 `implementation("androidx.navigation:navigation-compose:2.7.7")` 或与项目 androidx 版本对齐

- [ ] 0.2 **TestNavRoute.Connection 全仓显式跳转点核实**：

  ```bash
  grep -rn "TestNavRoute.Connection" /Users/wattledgnata/traeProjects/gps-app/feature/test/src --include="*.kt"
  ```

  预期：
  - `TestFlowNavigation.kt` 自身定义 `object Connection : TestNavRoute()` —— 保留
  - `TestFlowNavigation.kt` startDestination 使用 —— §11.1 改为 `Selection`
  - 其他显式跳转（如 `currentRoute = TestNavRoute.Connection` 或 `setRoute(Connection)`）—— 检查每处是否仍合理；若是 fallback 路径保留，若是误用记录到 Open Questions 后续讨论

- [ ] 0.3 **GpsDataViewModel 实例化点核实**（确保零外部消费方需要改）：

  ```bash
  grep -rn "GpsDataViewModel(" /Users/wattledgnata/traeProjects/gps-app/feature /Users/wattledgnata/traeProjects/gps-app/app /Users/wattledgnata/traeProjects/gps-app/core --include="*.kt"
  ```

  预期：
  - `class GpsDataViewModel(...)` 类定义自身
  - `AppModule.kt:121` `single { GpsDataViewModel(get(), get(), get()) }`
  - `GpsDataViewModelTest.kt` 测试构造（不影响）
  - 其他实例化点 → 如果有则记录，本 change 不改

- [ ] 0.4 **Material Icons Extended 可用性**：

  ```bash
  grep -rn "androidx.compose.material:material-icons-extended" /Users/wattledgnata/traeProjects/gps-app/feature/test/build.gradle.kts /Users/wattledgnata/traeProjects/gps-app/gradle/libs.versions.toml 2>/dev/null
  ```

  - 命中 → 用 Extended icon 包（含 Speedometer / Stop / Flag 等更全 icon）
  - 未命中 → 用 `androidx.compose.material.icons.Icons.Default` 基础包；具体 icon 选 `Icons.Default.Speed` / `Icons.Default.Stop` / `Icons.Default.Flag` / `Icons.Default.Insights` / `Icons.Default.Bluetooth` / `Icons.Default.Settings` / `Icons.Default.HelpOutline` / `Icons.Default.ChevronRight` 等可用项

- [ ] 0.5 **现有 NeonTheme + Components 命名空间核实**（避免新组件命名冲突）：

  ```bash
  grep -n "fun NeonButton\|fun NeonCard\|fun NeonTheme" /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/theme/Components.kt /Users/wattledgnata/traeProjects/gps-app/feature/test/src/main/java/com/blazepush/feature/test/ui/theme/Theme.kt
  ```

  Track Tech 组件命名前缀统一 `TrackTech*`（如 `TrackTechBottomNav` / `TrackTechRow`）避免与 Neon* 冲突。

---

## 1. 视觉基础组件层 · TrackTechColors（D13）

- [ ] 1.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechColors.kt`，参照 `docs/design/track-tech-visual-tokens.md` §1 落地：
  - `object TrackTechColors`：12 个 hex Color token（Background / Surface / SurfaceDark / Border / Purple / DeepPurple / Cyan / Green / Red / TextPrimary / TextSecondary / TextMuted）
  - `object TrackTechSemantic`：semantic alias（ReadyAccent / ConnectingAccent / NotReadyAccent / SelectedAccent / PrimaryActionAccent / SecondaryActionAccent / TelemetryLine）

- [ ] 1.2 `:feature:test:compileDebugKotlin` 验证编译

---

## 2. 视觉基础组件层 · TrackTechTypography（D12）

- [ ] 2.1 新建 `tracktech/TrackTechTypography.kt`，参照 visual-tokens 文档 §2：
  - `RacingTitle`：FontFamily.SansSerif + ExtraBold + Italic + 28.sp + letterSpacing 0.05.em
  - `RacingTitleSmall`：缩小版（16.sp，section header 用）
  - `Metric`：SansSerif + Black + 96.sp（hero）/ `MetricMedium`（36.sp）/ `MetricSmall`（20.sp）
  - `UiText` / `UiTextSmall` / `UiTextLabel`（label 大写 + letterSpacing 0.1.em）

- [ ] 2.2 编译验证

---

## 3. 视觉基础组件层 · TrackTechTheme（D4）

- [ ] 3.1 新建 `tracktech/TrackTechTheme.kt`：
  - `LocalTrackTechColors: ProvidableCompositionLocal<TrackTechColors>`
  - `LocalTrackTechTypography: ProvidableCompositionLocal<TrackTechTypography>`
  - `@Composable fun TrackTechTheme(content: @Composable () -> Unit)` —— 包装 `CompositionLocalProvider`，供 children 通过 `LocalTrackTechColors.current` 读取

- [ ] 3.2 编译验证

---

## 4. 视觉基础组件层 · CutCornerPanel（D11）

- [ ] 4.1 新建 `tracktech/CutCornerPanel.kt`：
  - `enum class CutCorner { TopLeft, TopRight, BottomLeft, BottomRight }`
  - `class CutCornerPanelShape(val cutSize: Dp, val cutCorners: Set<CutCorner>) : Shape`，实现 `createOutline(...)` 用 `Path` 构建切角路径
  - `@Composable fun CutCornerPanel(modifier, cutSize, cutCorners, borderColor, fillColor, content)` —— 提供切角填充 + 描边的标准面板

- [ ] 4.2 提供 4 个常用 preset：
  - `cutCornersDiagonal`（TopLeft + BottomRight，主面板默认）
  - `cutCornersAll`（4 角全切，紧凑卡）
  - `cutCornersTopRight`（单 TopRight 切，section header 用）
  - `cutCornersBottomLeft`（单 BottomLeft 切，footer 用）

- [ ] 4.3 编译验证 + 加 `@Preview` 让 IDE 渲染各 variant

---

## 5. 视觉基础组件层 · TrackTechBottomNav（D3）

- [ ] 5.1 新建 `tracktech/TrackTechBottomNav.kt`：
  - `data class TrackTechTabItem(val route: String, val label: String, val icon: ImageVector)`
  - `@Composable fun TrackTechBottomNav(navController: NavController, tabs: List<TrackTechTabItem>, modifier)` —— 用 Material3 `NavigationBar` + `NavigationBarItem`，selected 态自定义 `colors = NavigationBarItemDefaults.colors(...)` + 自定义 `indicator` 用 CutCornerPanel 紫色低透明填充
  - 内部计算 `currentRoute` 自 `navController.currentBackStackEntryAsState()`，`onClick` 调 `navController.navigate(route) { popUpTo(graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }`

- [ ] 5.2 提供默认 4 tab 列表 const：

  ```kotlin
  val DefaultTrackTechTabs = listOf(
      TrackTechTabItem("test", "Test", Icons.Default.Speed),
      TrackTechTabItem("laps", "Laps", Icons.Default.Flag),
      TrackTechTabItem("records", "Records", Icons.Default.Insights),
      TrackTechTabItem("device", "Device", Icons.Default.Bluetooth),
  )
  ```

- [ ] 5.3 编译 + Preview

---

## 6. 视觉基础组件层 · TrackTechStatusStrip

- [ ] 6.1 新建 `tracktech/TrackTechStatusStrip.kt`：
  - `data class StatusItem(val icon: ImageVector, val label: String, val color: Color)`
  - `@Composable fun TrackTechStatusStrip(items: List<StatusItem>, onClick: (() -> Unit)?, modifier)`
  - 渲染：等距分布 3 个 item，每个 item 含 icon + 文字（label 用 RacingTitleSmall 大写）；外层 Row 用 Modifier.clickable 触发 onClick
  - 默认 alpha 0.9，按下态 alpha 1.0

- [ ] 6.2 提供构造工具：

  ```kotlin
  fun StatusItem.Companion.fromGpsState(gpsReady: Boolean, frequencyHz: Int, signalQuality: SignalQuality): List<StatusItem>
  ```

- [ ] 6.3 编译 + Preview

---

## 7. 视觉基础组件层 · PrimaryActionPanel + SecondaryActionPanel

- [ ] 7.1 新建 `tracktech/PrimaryActionPanel.kt`：
  - `@Composable fun PrimaryActionPanel(title: String, subtitle: String, leadingIcon: ImageVector?, enabled: Boolean, onClick: () -> Unit, modifier)`
  - 视觉：紫色渐变背景（`Brush.linearGradient(listOf(TrackTechColors.DeepPurple, TrackTechColors.Purple))`）+ CutCornerPanel 切角 + 右侧 chevron icon
  - `enabled = false` 时降低 alpha 到 0.5

- [ ] 7.2 新建 `tracktech/SecondaryActionPanel.kt`：
  - 同结构，但视觉是红色描边（`TrackTechColors.Red` 1dp border）+ 透明背景

- [ ] 7.3 编译 + Preview

---

## 8. 视觉基础组件层 · MetricNumber + MetricTile

- [ ] 8.1 新建 `tracktech/MetricNumber.kt`：
  - `@Composable fun MetricNumber(value: String, unit: String?, size: MetricSize, modifier)`
  - `enum class MetricSize { Hero, Medium, Small }` 对应 96.sp / 36.sp / 20.sp
  - 视觉：value 用 Metric TextStyle，unit 用 UiText 小字 baseline 对齐底部

- [ ] 8.2 新建 `tracktech/MetricTile.kt`：
  - `@Composable fun MetricTile(label: String, value: String, unit: String?, status: String?, accentColor: Color, modifier)`
  - 视觉：CutCornerPanel + 上标 label（UiTextLabel 大写 + accent 着色）+ 中央 MetricNumber + 底部 status 文字

- [ ] 8.3 编译 + Preview

---

## 9. 视觉基础组件层 · TrackTechRow

- [ ] 9.1 新建 `tracktech/TrackTechRow.kt`：
  - `@Composable fun TrackTechRow(leadingIcon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, modifier)`
  - 视觉：CutCornerPanel + 左 icon + 中部 title/subtitle + 右侧 chevron icon

- [ ] 9.2 编译 + Preview

---

## 10. Shell · TrackTechAppShell + MainActivity 接线（D3, D7）

- [ ] 10.1 如 §0.1 grep 未命中，加 dependency 到 `feature/test/build.gradle.kts`：

  ```kotlin
  implementation("androidx.navigation:navigation-compose:2.7.7")
  ```

  注意与项目其他 androidx 依赖版本对齐。

- [ ] 10.2 新建 `tracktech/TrackTechAppShell.kt`：
  - `@Composable fun TrackTechAppShell()`
  - 最外层 `TrackTechTheme { Scaffold(bottomBar = { TrackTechBottomNav(...) }) { padding -> NavHost(...) { ... } } }`
  - 4 个 `composable("test"|"laps"|"records"|"device") { ... }` 各自调对应 home screen
  - `startDestination = "test"`

- [ ] 10.3 修改 `app/src/main/java/com/blazepush/MainActivity.kt:57`：

  ```kotlin
  // 改前
  TestFlowNavigation()

  // 改后
  TrackTechAppShell()
  ```

  + import：`import com.blazepush.feature.test.ui.tracktech.TrackTechAppShell`
  + 删除 `import com.blazepush.feature.test.ui.TestFlowNavigation`（如孤立）

- [ ] 10.4 `:app:compileDebugKotlin` 编译验证

---

## 11. 4 tab home screen · 骨架结构（spec/track-tech-app-shell + spec/cross-tab-device-gating）

- [ ] 11.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`：

  ```kotlin
  // 改前
  var currentRoute by remember { mutableStateOf<TestNavRoute>(TestNavRoute.Connection) }

  // 改后
  var currentRoute by remember { mutableStateOf<TestNavRoute>(TestNavRoute.Selection) }
  ```

  保留 `object Connection : TestNavRoute()` 路由定义不删除（transitional fallback）。

- [ ] 11.2 新建 `tracktech/TestHomeScreen.kt`：
  - 顶部 page header：`Drive Test` 标题（RacingTitle）+ 帮助 icon
  - StatusStrip（接 cross-tab gating capability §6）
  - Speed Hero（CutCornerPanel）：`SPEED` label + MetricNumber.Hero（绑 `gpsData.speed.toInt()`）+ `km/h` unit + `STATUS READY` 副文 + 右下 cyan 遥测线装饰（Canvas 占位）
  - PERFORMANCE TEST section：PrimaryActionPanel `0-100 ACCELERATION` + SecondaryActionPanel `100-0 BRAKING`
  - LATEST RESULT section：两个 MetricTile（`PERSONAL BEST` `4.21s` placeholder + `LAST RUN` `4.58s` placeholder）

- [ ] 11.3 新建 `tracktech/LapsHomeScreen.kt`：
  - page header `Laps` + 帮助 icon
  - StatusStrip
  - CURRENT TRACK CutCornerPanel：当前赛道名 `Shanghai Tianma`（绑 `TrackCatalog` 默认 track 或 placeholder）+ TrackPreview 占位（`Box` 显示 "TRACK PREVIEW —— PLACEHOLDER"）
  - PrimaryActionPanel `START LAP SESSION`
  - SecondaryActionPanel `CHANGE TRACK`
  - RECENT BEST MetricTile + NEARBY TRACKS 列表占位（3 个 TrackTechRow）

- [ ] 11.4 新建 `tracktech/RecordsHomeScreen.kt`：
  - page header `Records`
  - Segmented control `PERFORMANCE | LAPS`（用 Material3 SegmentedButton 或自定义）
  - PERFORMANCE summary 三个 MetricTile（占位数字）
  - SPEED CURVE Canvas 占位（绘制简单 cyan 线 placeholder）
  - RECENT RUNS section + 列表占位（5 个 TrackTechRow，文案 `0-100 4.58s` 等）

- [ ] 11.5 编译验证 `:feature:test:compileDebugKotlin`

---

## 12. Device Home（spec/device-home-connection-console）

- [ ] 12.1 新建 `tracktech/DeviceHomeScreen.kt`：
  - page header `Device` + 设置 icon
  - **Readiness Hero**（CutCornerPanel）：派生函数 `deriveHeroState(connectionState, isTestReady): HeroState`，根据 4 状态渲染主文案 + 副文案 + accent color；右下 cyan 遥测线 Canvas 装饰
  - **Quick Status Row**：3 个 MetricTile（BLE / SATS / RATE），数据绑 ViewModel
  - **Connected Device** 主卡（紫色描边 CutCornerPanel）：device name 绑 `lastConnectedDeviceName` 启发式（详见 D9）+ Ready/Waiting/Disconnected 副文 + SCAN button（紫色文字按钮，onClick `showSheet = true`）+ DISCONNECT button（红色描边，onClick `gpsViewModel.disconnect()`，enabled 绑 connectionState）
  - **GPS DETAILS** TrackTechRow（onClick → Toast `Coming in next round`）
  - **DIAGNOSTICS** TrackTechRow（同上）
  - **SETTINGS** TrackTechRow（同上）
  - 底部加 `BleScanBottomSheet(showSheet, onDismiss = { showSheet = false; gpsViewModel.stopScan() }, ...)` 调用

- [ ] 12.2 监听 `TrackTechEventBus.showScanSheetEvent`（cross-tab gating 触发）：

  ```kotlin
  LaunchedEffect(Unit) {
      TrackTechEventBus.showScanSheetEvent.collect {
          showSheet = true
          gpsViewModel.startScan()
      }
  }
  ```

- [ ] 12.3 在现有 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/DeviceConnectionScreen.kt` 顶部加 deprecation 注释：

  ```kotlin
  /**
   * @deprecated Track Tech V2 后 [DeviceHomeScreen] 是全局连接入口；
   * 本屏保留作 transitional fallback（旧 [TestNavRoute.Connection] 路由仍可触达），
   * 计划在 future round 删除。
   */
  @Deprecated("Use DeviceHomeScreen in TrackTech App Shell")
  @Composable
  fun DeviceConnectionScreen(...) { ... }
  ```

- [ ] 12.4 编译验证

---

## 13. BLE Scan Bottom Sheet（spec/ble-scan-bottom-sheet）

- [ ] 13.1 新建 `tracktech/BleScanBottomSheet.kt`：
  - `@OptIn(ExperimentalMaterial3Api::class) @Composable fun BleScanBottomSheet(visible: Boolean, onDismiss: () -> Unit, gpsViewModel: GpsDataViewModel = koinInject())`
  - 内部用 `if (visible) { ModalBottomSheet(onDismissRequest = onDismiss, ...) { ... } }`
  - 状态派生：`val state = remember(isScanning, scanResults, connectionState, attemptedConnectAddress) { deriveScanState(...) }` 5 状态机
  - 设备列表：`LazyColumn` 渲染 `scanResults`，每行用 CutCornerPanel + 选中态紫色描边 + RSSI + 信号条 + Recommended/Unsupported 启发式标签
  - 底部 PrimaryActionPanel `CONNECT` / `RETRY`（启发文案）+ 紫色文字按钮 `SCAN AGAIN`
  - 顶部副标和 hint 文案按状态变
  - `selectedDevice` 局部 state（`var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }`）
  - 关闭时 `onDismissRequest` 调 `onDismiss()`，外层 caller MUST 在 callback 内调 `gpsViewModel.stopScan()`

- [ ] 13.2 工具函数：
  - `fun rssiToBars(rssi: Int): Int` —— 4 格信号条派生
  - `fun classifyDevice(name: String): DeviceLabel` —— Recommended/External/Unsupported 启发式
  - `fun deriveScanState(isScanning, scanResults, connectionState, attemptedAddress): ScanState`

- [ ] 13.3 在现有 `DeviceScanDialog.kt` 顶部加 deprecation 注释（同 §12.3 模板）

- [ ] 13.4 编译验证

---

## 14. Cross-tab gating + SharedFlow event bus（spec/cross-tab-device-gating）

- [ ] 14.0 **新建 `tracktech/TabGatingPolicy.kt`**（Round 4 review v2 修复：**不**复用 SmartTestLauncher 因 `speed_at_start` 条件错引导用户）：

  ```kotlin
  package com.blazepush.feature.test.ui.tracktech

  import com.blazepush.core.domain.model.ConnectionState
  import com.blazepush.core.domain.model.DataQuality
  import com.blazepush.core.domain.model.GpsData

  /**
   * 首页 tab 入口 gating policy。
   *
   * **MUST 仅检查** 4 项 device/data 基础条件：
   * - BLE_CONNECTED:        connectionState == CONNECTED
   * - DATA_FRESH:           dataQuality.dataAge < 1000ms
   * - SATELLITES_SUFFICIENT: gpsData.satelliteCount >= 6
   * - HDOP_GOOD:            gpsData.hdop > 0 && gpsData.hdop < 2.0
   *
   * **MUST NOT 检查**：
   * - speed range（任何速度区间）
   * - test template（0-100 / 100-0 / lap 区分）
   * - acceleration/braking 起点条件
   *
   * Rationale：首页入口语义是 "用户能否进入测试流程"，不是 "用户能否立即开始测试"。
   * 速度门槛 / template 准备 / 起点条件应在 TestExecutionScreen 等执行前
   * 准备屏由 SmartTestLauncher.checkLaunchConditions 处理。
   *
   * 阈值与 SmartTestLauncher 内对应 4 项 device condition 阈值对齐保证语义一致。
   */
  enum class TabReadinessCondition {
      BLE_CONNECTED,
      DATA_FRESH,
      SATELLITES_SUFFICIENT,
      HDOP_GOOD,
  }

  data class TabReadiness(
      val canEnterTestFlow: Boolean,
      val unmetConditions: List<TabReadinessCondition>,
  )

  object TabGatingPolicy {
      fun computeTabReadiness(
          connectionState: ConnectionState,
          gpsData: GpsData,
          dataQuality: DataQuality,
      ): TabReadiness {
          val unmet = mutableListOf<TabReadinessCondition>()
          if (connectionState != ConnectionState.CONNECTED) {
              unmet += TabReadinessCondition.BLE_CONNECTED
          }
          if (dataQuality.dataAge >= 1000) {
              unmet += TabReadinessCondition.DATA_FRESH
          }
          if (gpsData.satelliteCount < 6) {
              unmet += TabReadinessCondition.SATELLITES_SUFFICIENT
          }
          if (gpsData.hdop <= 0.0 || gpsData.hdop >= 2.0) {
              unmet += TabReadinessCondition.HDOP_GOOD
          }
          return TabReadiness(
              canEnterTestFlow = unmet.isEmpty(),
              unmetConditions = unmet,
          )
      }
  }
  ```

- [ ] 14.0.1 **新建 `feature/test/src/test/.../ui/tracktech/TabGatingPolicyTest.kt`** 单测：
  - `allConditionsMet_returnsCanEnterTestFlowTrue`
  - `bleDisconnected_returnsBleConnectedUnmet`
  - `dataAgeStale_returnsDataFreshUnmet`
  - `satelliteCountLow_returnsSatellitesSufficientUnmet`
  - `hdopZero_returnsHdopGoodUnmet`（hdop = 0 边界）
  - `hdopAtThreshold_returnsHdopGoodUnmet`（hdop = 2.0 边界）
  - `speedZero_doesNotPreventCanEnterTestFlow`（**关键回归**：用户静止 speed = 0 不影响 readiness，与 100-0 测试场景一致 —— 与现有 design D14 边界一致）
  - `speedHigh_doesNotPreventCanEnterTestFlow`（speed = 120 km/h 也不影响）

- [ ] 14.1 新建 `tracktech/TrackTechEventBus.kt`：

  ```kotlin
  package com.blazepush.feature.test.ui.tracktech

  import kotlinx.coroutines.flow.MutableSharedFlow
  import kotlinx.coroutines.flow.SharedFlow
  import kotlinx.coroutines.flow.asSharedFlow

  object TrackTechEventBus {
      private val _showScanSheetEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
      val showScanSheetEvent: SharedFlow<Unit> = _showScanSheetEvent.asSharedFlow()
      fun requestShowScanSheet() {
          _showScanSheetEvent.tryEmit(Unit)
      }
  }
  ```

- [ ] 14.2 在 `TestHomeScreen` / `LapsHomeScreen` 主操作 onClick 内集成 cross-tab gating：

  ```kotlin
  val gpsViewModel = koinInject<GpsDataViewModel>()
  val connectionState by gpsViewModel.connectionState.collectAsState()
  val dataQuality by gpsViewModel.dataQuality.collectAsState()
  val gpsData by gpsViewModel.gpsData.collectAsState()
  val readiness = remember(connectionState, dataQuality, gpsData) {
      TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality)
  }

  PrimaryActionPanel(
      title = "0-100",
      subtitle = "ACCELERATION",
      leadingIcon = Icons.Default.Speed,
      enabled = true,  // 不用 disabled state，避免 onClick 不触发
      onClick = {
          if (readiness.canEnterTestFlow) {
              // 进入 Test tab nested nav: Selection
              // 通过 callback 让父 TestHomeScreen 接 nested NavController 切换
          } else {
              Toast.makeText(context, "Connect a GPS device first", Toast.LENGTH_SHORT).show()
              navController.navigate("device") {
                  popUpTo(navController.graph.startDestinationId) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
              }
              if (connectionState == ConnectionState.DISCONNECTED) {
                  TrackTechEventBus.requestShowScanSheet()
              }
          }
      },
  )
  ```

  注意：**MUST NOT** import `SmartTestLauncher`，**MUST NOT** 在首页主操作内调 `checkLaunchConditions`（spec/cross-tab-device-gating "TabGatingPolicy 不检查 speed range" + "首页 home screen 不 import SmartTestLauncher" 两条 Requirement 强制）。

- [ ] 14.3 在 `TestHomeScreen` / `LapsHomeScreen` 内 StatusStrip 调用：

  ```kotlin
  TrackTechStatusStrip(
      items = StatusItem.fromGpsState(...),
      onClick = {
          navController.navigate("device") { ... }
      },
  )
  ```

- [ ] 14.4 编译验证

---

## 15. 编译/测试门槛

- [ ] 15.1 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL
- [ ] 15.2 `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [ ] 15.3 `./gradlew :feature:test:testDebugUnitTest` 全绿（现有测试零回归 —— 数据层零改动 + ViewModel 接口零改动）
- [ ] 15.4 `./gradlew :core:bluetooth:testDebugUnitTest :core:domain:test` 全绿
- [ ] 15.5 E2E 契约 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿

---

## 16. 真机截图 + 视觉对比（acceptance）

- [ ] 16.1 安装到真机 `8KE0219522008434`（默认华为）：

  ```bash
  ./gradlew :app:installDebug
  ```

- [ ] 16.2 截图 5 张 + 命名规范：
  - `screenshot-test-tab.png`
  - `screenshot-laps-tab.png`
  - `screenshot-records-tab.png`
  - `screenshot-device-tab.png`
  - `screenshot-ble-scan-sheet.png`

  保存到临时目录（不 commit）。

- [ ] 16.3 与 V2 参考图（`docs/design/visual-refs/*.png`）肉眼对比，记录偏差点：
  - 视觉强度是否接近
  - 切角风格是否到位
  - 色彩比例（黑/石墨 70%）是否克制
  - 字体角色是否清晰区分
  - 状态点缀（cyan 遥测线 / green ready / red disconnect）是否到位

- [ ] 16.4 偏差大的具体项作为 follow-up backlog 记录到 commit message body（不在本 change 内修补）

---

## 17. 合流门槛

- [ ] 17.1 **Spec 验证**：`openspec validate add-track-tech-app-shell --strict` 返回 `Change ... is valid`

- [ ] 17.2 **grep 自检**：
  - `MainActivity.kt` 内 `TrackTechAppShell()` 命中 + `TestFlowNavigation()` 直接调用零命中
  - `TrackTechAppShell` 在 `tracktech/` 子包内命中
  - `feature/test/.../ui/tracktech/` 子包文件数 ≥ 12（视觉组件 9 + Shell 1 + 4 home screen + Sheet + EventBus）
  - `DeviceConnectionScreen.kt` + `DeviceScanDialog.kt` 仍存在 + 含 `@Deprecated` 注释

- [ ] 17.3 **下游零回归**：
  - `:core:bluetooth:testDebugUnitTest` ✅
  - `:core:domain:test` ✅
  - `:app:compileDebugKotlin` ✅
  - 现有 `GpsDataViewModelTest` / `TestSessionViewModelTrackLapTest` 全绿

- [ ] 17.4 **真机验证 5 张截图**已完成（§16）

---

## 18. Commit 策略

本 change scope 中等偏大（9 视觉组件 + 4 tab home + Device Home + BLE Sheet + cross-tab gating + MainActivity 接线 + 2 个 deprecation 注释），**1 个代码 commit**：

- [ ] 18.1 **commit**：`feat(ui): Track Tech V2 app shell · 4 tab + Device Home + BLE Sheet + cross-tab gating`

  body 要点：
  - **Capability 1 track-tech-app-shell**：Compose Navigation NavHost + 4 tab persistent shell（Test/Laps/Records/Device）；`MainActivity:57` 替换 `TestFlowNavigation()` → `TrackTechAppShell()`；`TestFlowNavigation` startDestination 从 `Connection` 改 `Selection`，Connection 路由保留作 transitional fallback；`GpsDataViewModel` Application singleton 直接复用，DI scope 零改动
  - **Capability 2 device-home-connection-console**：DeviceHomeScreen 替代 DeviceConnectionScreen 作 Device tab root；Readiness Hero 三态状态映射（READY TO TEST / CONNECT GPS DEVICE / WAITING FOR GPS LOCK / CONNECTING）；Quick Status Row（BLE/SATS/RATE 三 MetricTile）；Connected Device 主卡（紫色描边 + SCAN/DISCONNECT 双行动）；3 个入口行 placeholder（GPS Details/Diagnostics/Settings）
  - **Capability 3 ble-scan-bottom-sheet**：Material3 ModalBottomSheet 替换 DeviceScanDialog（Dialog → Sheet）；5 状态机（scanning/found/empty/connecting/failed 启发式区分）；选中态局部 state；CONNECT/SCAN AGAIN 双按钮；onDismissRequest 调 stopScan
  - **Capability 4 cross-tab-device-gating**：TrackTechStatusStrip 主动入口；新建 `TabGatingPolicy.computeTabReadiness` 仅 4 项 device condition（BLE/data fresh/sats/hdop），**不**复用 SmartTestLauncher（避免 `speed_at_start` 错引导静止用户）；TabGatingPolicy MUST NOT 检查 speed range / test template / 起点条件；未 ready 主操作拦截 → Toast + 切 Device tab + 自动展开 Sheet（DISCONNECTED 时）；TrackTechEventBus SharedFlow 跨 tab 通信
  - **视觉基础组件**（feature/test/.../ui/tracktech/ 新子包）：TrackTechColors（12 hex token + semantic alias）+ TrackTechTypography（3 字体角色）+ TrackTechTheme + CutCornerPanel（GenericShape 切角）+ TrackTechBottomNav + TrackTechStatusStrip + PrimaryActionPanel + SecondaryActionPanel + MetricNumber + MetricTile + TrackTechRow + 4 home screen + DeviceHomeScreen + BleScanBottomSheet + TrackTechEventBus 共 16 文件
  - **deprecation 标注**：DeviceConnectionScreen + DeviceScanDialog 顶部加 @Deprecated 注释 + transitional fallback 说明
  - **零改动**：GpsDataViewModel / TestSessionViewModel / SmartTestLauncher / TestSelectionScreen / TestExecutionScreen / TestResultScreen / TestHistoryScreen / LapDebug* / NeonTheme / Components / Theme / core/* / simulator/*
  - **真机截图**：5 张已采集，与 V2 参考图视觉对比偏差点列入 follow-up backlog
  - **合流门槛**：openspec validate --strict ✅ / :feature:test :app :core:domain :core:bluetooth 全绿 ✅ / E2E 契约 ✅ / grep 自检 ✅

  格式约束：
  - Conventional Commits
  - body 含 4 个 capability 名 + 16 文件清单 + 真机截图状态
  - Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>

---

## 19. Post-apply follow-up backlog（不在本 change scope，记录到 commit message）

- GPS Details 子页（密集指标页）—— 独立 round
- Diagnostics 子页 —— 独立 round
- Settings 子页 —— 独立 round
- Records 完整图表（Speed Curve / Acceleration Curve）—— 独立 round
- Laps 赛道选择产品化（Track Preview 真实坐标 / Nearby Tracks 真实数据）—— 独立 round
- Execution HUD 视觉升级 —— 独立 round
- 字体最终化（七段数码 / 真 Italic Racing 字体 license）—— 独立 round
- icon 资产体系（自定义 SVG vector）—— 独立 round
- BLE 底层 API 增强：`selectedDevice` / `failedReason` / `live RSSI` —— 独立 round
- Latest Result 接线（Test home Personal Best / Last Run 真实数据）—— 独立 round
- Speed Hero 遥测线接 GpsData history buffer —— 独立 round
- 删除 DeviceConnectionScreen / DeviceScanDialog（确认旧路径无引用后）—— 独立 round
