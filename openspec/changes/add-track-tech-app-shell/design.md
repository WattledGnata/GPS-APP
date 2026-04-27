## Context

### 当前 IA / UI 状态

`MainActivity:57` 唯一入口 `TestFlowNavigation()`，单栈 sealed class 路由：

```kotlin
sealed class TestNavRoute {
    object Connection : TestNavRoute()
    object Selection : TestNavRoute()
    object LapDebugConfig : TestNavRoute()
    object Execution : TestNavRoute()
    object LapDebugResult : TestNavRoute()
    data class Result(val testId: String) : TestNavRoute()
    object History : TestNavRoute()
}
// startDestination = TestNavRoute.Connection
```

`Connection` 是阻塞首屏，对应 `DeviceConnectionScreen.kt`，把"连接状态卡 + 扫描按钮 + GPS 信号卡 + DataQualityCard + start test 按钮"全部堆在一起。视觉是 Material3 默认 `Card` + `Button`。

### 数据层状态（关键事实）

`AppModule.kt:121` `single { GpsDataViewModel(get(), get(), get()) }` —— `GpsDataViewModel` 是 Application **singleton**（不是 viewModel scope），暴露：

```kotlin
val gpsData: StateFlow<GpsData>
val connectionState: StateFlow<ConnectionState>
val dataQuality: StateFlow<DataQuality>
val isScanning: StateFlow<Boolean>
val scanResults: StateFlow<List<ScannedDevice>>
fun startScan() / stopScan() / connectDevice(device) / disconnect()
```

其他相关 ViewModel：
- `TestSessionViewModel`：性能测试执行状态
- `SmartTestLauncher`：`checkLaunchConditions(...): LaunchStatus(canLaunch, unmetConditionIds)` —— 跨 tab gating 现成 API

### V2 设计方向锁定（输入文档）

- `docs/design/track-tech-v2-cc-guidance.md`：CC 指导文档，明确视觉强度分层、色号、字体、组件清单
- `docs/design/track-tech-function-probe.md`：capability matrix（11 能力 × supported/partial/missing）
- `docs/design/visual-refs/`：4 张 V2 calmer 参考图

### 约束

- 不修改 RaceChrono BLE 协议 / GpsData 字段
- 不修改 `core/*` 模块（所有 UI 改动局限于 `feature/test/.../ui/tracktech/` + `MainActivity.kt`）
- 不引入字体 .ttf 资产 / 自定义 icon SVG（首版用系统字体 + Material Icons）
- 不像素级复刻参考图（guidance §Important Boundary 明确）
- 现有 `TestSessionViewModel` / `GpsDataViewModel` / `SmartTestLauncher` 接口零改动
- 现有 `TestFlowNavigation` / `TestSelectionScreen` / `TestExecutionScreen` 等屏在 Test tab 内 nested 复用，不删除
- `DeviceConnectionScreen` / `DeviceScanDialog` 保留作 transitional fallback，不删除（future round 删除）

## Goals / Non-Goals

**Goals**：

- 4 tab persistent shell：`Test | Laps | Records | Device`，跨 tab 切换保留各自 nested nav 状态
- Device tab 是连接入口，BLE / GPS 状态全局共享（`GpsDataViewModel` Application singleton 直接复用）
- BLE 扫描从 center Dialog 升级为 ModalBottomSheet（Material3）
- Test/Laps 未 ready 时主操作引导到 Device，并自动展开 Scan Sheet（如未连接）
- StatusStrip 提供 Test/Laps 顶部主动入口
- Track Tech 视觉基础组件（CutCornerPanel + Bottom Nav + StatusStrip + MetricNumber/Tile + ActionPanel + Bottom Sheet）一次性交付
- 视觉强度分层（Test/Laps 中 / Records/Device 低 / Sheet 工具型）

**Non-Goals**：

- 不做 Records / Laps 完整产品体验（只有首页骨架）
- 不做 GPS Details / Diagnostics / Settings 子页（入口行 placeholder）
- 不做 Execution HUD 升级（保留现有 `TestExecutionScreen`）
- 不做字体最终化（用系统 SansSerif）
- 不做 icon 资产体系（用 Material Icons Extended 默认）
- 不修改 `GpsDataViewModel` / `TestSessionViewModel` / `SmartTestLauncher` 接口
- 不删除 `DeviceConnectionScreen` / `DeviceScanDialog`（transitional fallback）
- 不像素级复刻参考图

## Decisions

### D1 · change 命名 `add-track-tech-app-shell`

**决策**：change 命名为 `add-track-tech-app-shell`，对齐 first-slice 五步定位（4 tab shell 是核心）。

**Rationale**：

- 与 guidance §First Implementation Scope 第 1 条 "四 tab shell" 命名对齐
- 后续 GPS Details / Records 完整图表 / Laps 赛道选择 / 字体最终化 / Execution HUD 升级各自独立 change（命名 `add-track-tech-gps-details` / `add-records-charts` / etc）
- `add-` 前缀对齐 OpenSpec 既有 `add-` / `fix-` / `refactor-` 命名习惯

**Alternatives considered**：

- (a) `redesign-ui-track-tech-v2`：拒收 —— "redesign" 暗示全量重做，与本 change 仅做首切片的 scope 不符
- (b) `add-four-tab-shell`：拒收 —— 失去"Track Tech 风格"的语义信息，未来其他 shell 重构 round 命名混淆

### D2 · 模块归属 `feature:test`

**决策**：所有新代码落在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包，**不**新建 `feature:tracktech` 模块。

**Rationale**：

- `GpsDataViewModel` / `TestSessionViewModel` / `SmartTestLauncher` / 现有 `ui/screen/*` / `ui/theme/*` 都在 `feature/test` 模块
- 跨模块访问 ViewModel 需要 module dependency 调整，本 change 不引入额外 build 复杂度
- `tracktech/` 子包与 `theme/` `screen/` `viewmodel/` 同级，包结构清晰
- future round 若 Track Tech 组件需要被其他 feature 模块复用，再独立提取到 `core/ui/tracktech` 模块（属于独立 refactor round）

**Alternatives considered**：

- (a) 新建 `feature/tracktech` 模块：拒收 —— 跨模块依赖增加 build 复杂度，且本 change 无其他 feature 模块消费方
- (b) 落在 `feature/test/.../ui/theme/`：拒收 —— `theme/` 命名暗示 token + Material3 ColorScheme，与 Track Tech 组件库语义不匹配

### D3 · 导航实现 Compose Navigation `NavHost`

**决策**：4 tab shell 用官方 `androidx.navigation:navigation-compose` 的 `NavHost` + `BottomNavigation`；Test tab 内嵌的现有 `TestFlowNavigation` sealed class 单栈作为 nested nav 保留。

**Rationale**：

- 4 tab + nested screens + bottom sheet + 跨 tab 跳转用官方 nav lib 是社区标准，state restoration / back stack / deep link / saveState 全是免费的
- 现有 sealed class `TestNavRoute` 作为 Test tab nested 路由保留，避免一次性重写所有屏的导航胶水代码（scope 控制）
- ApplyMode：Compose Navigation 的 `saveState` + `restoreState` 让 tab 切换保留 state 是声明式的（不需要手写 ViewModel scope hack）

**实现要点**：

```kotlin
@Composable
fun TrackTechAppShell() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { TrackTechBottomNav(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "test",
            modifier = Modifier.padding(padding),
        ) {
            composable("test") { TestHomeScreen(navController) }
            composable("laps") { LapsHomeScreen(navController) }
            composable("records") { RecordsHomeScreen(navController) }
            composable("device") { DeviceHomeScreen(navController) }
        }
    }
}

@Composable
fun TrackTechBottomNav(navController: NavController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    NavigationBar(/* 切角紫色 active 视觉 */) {
        listOf(
            "test" to "Test",
            "laps" to "Laps",
            "records" to "Records",
            "device" to "Device",
        ).forEach { (route, label) ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = {
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { /* ... */ },
                label = { Text(label) },
            )
        }
    }
}
```

**Alternatives considered**：

- (a) 自建 sealed class `TrackTechTab` + `var currentTab by remember { mutableStateOf(...) }`：拒收 —— 不会处理 deep link / 跨 tab 共享 back stack / saveState 需要手写
- (b) Accompanist BottomNavigation：已 deprecated，迁到 Material3 NavigationBar

### D4 · Track Tech 组件落点 `feature/test/.../ui/tracktech/`

**决策**：新组件全部放 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 子包，与 `ui/theme/`（NeonTheme）并列。

**理由**：见 D2 + D3。NeonTheme（现有 Material3 风格）作为 fallback 保留，新 Track Tech 组件用 `TrackTechTheme`，两者并存。`MainActivity:52` `NeonTheme { ... }` 包装层保留，**内部** 内容从 `TestFlowNavigation()` 改为 `TrackTechTheme { TrackTechAppShell() }` 嵌套（`TrackTechTheme` 提供 Track Tech 色号/字体上下文，叠在 NeonTheme 之上）。

**Rationale**：

- 不污染 `theme/` 子包（已被 NeonTheme 占用，语义为"Material3 ColorScheme + Components"）
- 双 Theme 嵌套是 Compose 标准做法，不会引发冲突
- 未来若 NeonTheme 完全废弃，可独立 round 删除（不在本 change scope）

### D5 · OpenSpec 流程 full spec-driven

**决策**：本 change 走完整 OpenSpec 流程：proposal → design → specs → tasks → review → apply。**不** fast-forward（ff）。

**Rationale**：

- scope 涵盖 IA 重构（4 tab shell）+ 9 个新组件 + 4 个新 capabilities + 跨 tab gating + BLE Sheet 重写，远超 Round 1-4 的后端单点修复 scope
- proposal 阶段就把视觉解读、scope 边界、API 改造取舍、Non-goals 全部摆出来 review 一次，能避免大量返工
- 既往 4 个 round 都 full spec-driven，UI 切片不应破例

### D6 · ViewModel scope 复用 Application singleton

**决策**：跨 tab 共享 `GpsDataViewModel` 直接复用现有 Koin `single` scope（Application singleton），**不**做 nav graph viewModel scope 改造，**不**改 `AppModule.kt:121` 注册。

**Rationale**：

- 现状 `single { GpsDataViewModel(get(), get(), get()) }` 已是 Application singleton，跨 tab Compose 内 `koinInject<GpsDataViewModel>()` 拿到的是同一实例
- 不改 DI scope 意味着 BLE 生命周期管理零改动（连接持久 across tab switch，符合 guidance §Device 是全局连接入口的语义）
- 若改为 nav graph scope，反而会因 tab 切换 ViewModel 重建丢失 BLE 连接（违反产品意图）

**Alternatives considered**：

- (a) 改成 `viewModel { ... }` + nav graph scope：拒收 —— BLE 生命周期会泄漏；现状已是合适的 scope
- (b) Activity scope：拒收 —— Application singleton 已覆盖

### D7 · `MainActivity` 改造范围（最小化）

**决策**：`MainActivity:57` 单行替换 `TestFlowNavigation()` → `TrackTechAppShell()`，外层 `NeonTheme { Surface { ... } }` 保留。

**Rationale**：

- 改动面最小：1 行实质改动 + 1 行 import 替换
- 权限请求逻辑（`PermissionRequestScreen` 等）零改动
- `NeonTheme` 包装层保留，`TrackTechAppShell` 内部嵌套 `TrackTechTheme { ... }` —— 双 Theme 提供互不冲突的 token 上下文

### D8 · `TestFlowNavigation` startDestination 调整

**决策**：现有 `TestFlowNavigation.kt` 内 `var currentRoute by remember { mutableStateOf<TestNavRoute>(TestNavRoute.Connection) }` MUST 改为 `TestNavRoute.Selection`，避免在 Test tab 内嵌时再次落到 Connection 阻塞屏。

**Rationale**：

- 4 tab shell 后 Connection 已不应再是首屏（Device tab 是全局连接入口）
- `TestFlowNavigation` 的 `Connection` 路由仍保留作 transitional fallback（旧代码路径若调到 `setRoute(TestNavRoute.Connection)` 不报错），但 startDestination 改了
- 这是本 change 唯一对现有屏的 logic 改动（其他屏零改动）

**Risk**：旧代码路径若有 hardcoded `TestNavRoute.Connection` 跳转（如某 callback 显式调），需要 apply 阶段 grep 确认无 stale 调用。grep 预检：`grep -rn "TestNavRoute.Connection" feature/test/src/main`。

### D9 · `BleScanBottomSheet` 状态机最小化

**决策**：`BleScanBottomSheet` 状态机覆盖 5 状态，但 `selectedDevice` / `failedReason` / `live RSSI 区分` 在底层 `GpsDataViewModel` API 暂不可得时按 guidance §BLE Scan Sheet "可以先用最小状态实现，后续补"：

| 状态 | 触发 | UI |
|---|---|---|
| `scanning` | `isScanning = true` + `scanResults.isEmpty()` | "Searching nearby GPS receivers" + spinner |
| `found` | `isScanning = true/false` + `scanResults.isNotEmpty()` | 设备列表 |
| `empty` | `isScanning = false` + `scanResults.isEmpty()` + 已扫一轮 | "No devices found" + Scan Again |
| `connecting` | `connectionState == CONNECTING` | "Connecting to ..." + spinner |
| `failed` | `connectionState == DISCONNECTED` after 用户主动 connect 后 | "Connection failed" + Retry —— **本 change 用最小启发式区分**：state 为 `DISCONNECTED` 且 sheet 内有 `attemptedConnectAddress` 但 `lastDeviceAddress != attemptedConnectAddress` 视为 failed |

`selectedDevice` 用 `BleScanBottomSheet` 内部 `var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }` 局部 state（不下沉到 ViewModel）。`live RSSI` 后续可通过新增 `GpsDataViewModel.connectedDeviceRssi: StateFlow<Int?>` 实现，**本 change 不引入**（保持 ViewModel 接口零改动）。

**Rationale**：

- 与 guidance §BLE Scan Sheet 最小状态原则对齐
- `failed` 状态启发式区分准确度可能不 100%，但与"工具弹层 + 用户可点 Scan Again"的可恢复性结合后，UX 影响很小
- 长期方向（live RSSI / 真正的 failed reason）作为 future round backlog

### D10 · `cross-tab-device-gating` 用独立 `TabGatingPolicy`（不复用 SmartTestLauncher）

**决策**：跨 tab gating 用新建 `feature/test/.../ui/tracktech/TabGatingPolicy.kt`，**不**复用 `SmartTestLauncher.checkLaunchConditions`。

**Rationale**（**Round 4 review v2 修补**）：

- `SmartTestLauncher.checkLaunchConditions` 内部包含 5 个 condition，第 5 个 `speed_at_start` 检查 `gpsData.speed in startSpeedMin..startSpeedMax`。`100-0` 制动测试调用时传 95-105 km/h，**用户静止时 `canLaunch = false` 必然成立**，会被错误导到 Device tab，但实际设备/数据完全正常 —— 用户需要的是先开车加速，不是去 Device 调连接
- `SmartTestLauncher` 适用 **执行前 Smart Launch** 场景（用户已经在车里、已经在测试流内、即将按 Start），**不适用** **首页 tab 入口** 场景（设备/数据基础就绪即可）
- 两个语义层分离实现优于过滤同一个 API：tab gating 与 smart launch 是产品上独立的两层 readiness 检查，硬绑定反而风险

**TabGatingPolicy 边界**（与 D14 协同锁定）：

`TabGatingPolicy.computeTabReadiness(...)` MUST **仅** 检查 4 项 device/data 条件：

1. **BLE connected**：`connectionState == ConnectionState.CONNECTED`
2. **data fresh**：`dataQuality.dataAge < 1000` ms
3. **satellites sufficient**：`gpsData.satelliteCount >= 6`
4. **hdop good**：`gpsData.hdop > 0 && gpsData.hdop < 2.0`（与 SmartTestLauncher 阈值对齐）

`TabGatingPolicy` MUST **不**检查：

- speed range（任何速度区间检查）
- test template（测试类型 / 模板）
- acceleration/braking 起点条件（`100-0` 起点速度 95-105 km/h、`0-100` 起点速度 0-3 km/h 等）

**实现要点**：

```kotlin
// feature/test/.../ui/tracktech/TabGatingPolicy.kt
package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData

enum class TabReadinessCondition {
    BLE_CONNECTED,           // connectionState == CONNECTED
    DATA_FRESH,              // dataQuality.dataAge < 1000ms
    SATELLITES_SUFFICIENT,   // gpsData.satelliteCount >= 6
    HDOP_GOOD,               // gpsData.hdop > 0 && gpsData.hdop < 2.0
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
        if (connectionState != ConnectionState.CONNECTED) unmet += TabReadinessCondition.BLE_CONNECTED
        if (dataQuality.dataAge >= 1000) unmet += TabReadinessCondition.DATA_FRESH
        if (gpsData.satelliteCount < 6) unmet += TabReadinessCondition.SATELLITES_SUFFICIENT
        if (gpsData.hdop <= 0 || gpsData.hdop >= 2.0) unmet += TabReadinessCondition.HDOP_GOOD
        return TabReadiness(canEnterTestFlow = unmet.isEmpty(), unmetConditions = unmet)
    }
}

// TestHomeScreen.kt 内
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
    enabled = true,  // 不用 disabled state，避免 onClick 不触发
    onClick = {
        if (readiness.canEnterTestFlow) {
            // 进入 Test tab nested nav: Selection
        } else {
            // 切到 Device tab + 自动开 sheet（如未连接）
            navController.navigate("device") { /* ... */ }
            if (connectionState == ConnectionState.DISCONNECTED) {
                TrackTechEventBus.requestShowScanSheet()
            }
        }
    },
)
```

**Alternatives considered**：

- (a) 直接禁用主操作（disabled state）：拒收 —— UX 差，用户不知道为什么不能点
- (b) 复用 `SmartTestLauncher.checkLaunchConditions`：**Round 4 review v2 拒收** —— 包含 `speed_at_start` 条件，用户静止点 `100-0` 必然 `canLaunch = false`，错误导到 Device tab；语义层错位
- (c) 复用 `SmartTestLauncher.checkLaunchConditions` + 调用方过滤 `unmetConditionIds`（剔除 `"speed_at_start"`）：拒收 —— 依赖字符串字面量，未来 SmartTestLauncher 改 id 名静默断；要传 dummy `startSpeedMin/Max`，hacky
- (d) 拆分 `SmartTestLauncher` 为 `checkDeviceReadiness` + `checkLaunchConditions` 两层（改 core/domain）：拒收 —— 触动 D10 "SmartTestLauncher 接口零改动" + proposal Non-goals "不修改 core/* 模块" 两条边界；scope 扩张。等到多个 tab/feature 都需要 device readiness 时再独立 refactor round 沉淀到 core/domain

### D14 · TabGatingPolicy 边界声明（首页 gating MUST/MUST NOT 清单）

**决策**：本 change 把 TabGatingPolicy 的检查范围锁死在 device/data 4 项，**禁止** 任何 speed/template/test-specific 条件渗入。

**MUST 检查**（4 项，与 SmartTestLauncher 内 device condition 阈值对齐保证语义一致）：

| 条件 | 阈值 | 数据源 |
|---|---|---|
| `BLE_CONNECTED` | `connectionState == CONNECTED` | `GpsDataViewModel.connectionState` |
| `DATA_FRESH` | `dataQuality.dataAge < 1000` ms | `GpsDataViewModel.dataQuality` |
| `SATELLITES_SUFFICIENT` | `gpsData.satelliteCount >= 6` | `GpsDataViewModel.gpsData` |
| `HDOP_GOOD` | `gpsData.hdop > 0 && gpsData.hdop < 2.0` | `GpsDataViewModel.gpsData` |

**MUST NOT 检查**（3 项硬边界）：

- **speed range**：任何 `gpsData.speed` 区间检查（`0-3 km/h` / `95-105 km/h` 起点速度等）
- **test template**：测试类型 / 模板的检查（`0-100` / `100-0` / `lap-session` 等区分）
- **acceleration/braking 起点条件**：与具体测试流程相关的预备状态（如方向稳定性、制动距离前置条件等）

**Rationale**：

- 首页 tab 入口的语义是 "用户能否进入测试流程"，不是 "用户能否立即开始测试"
- 速度门槛 / template 准备 / 起点条件应该在 **进入测试流程后** 的执行准备屏（如 `TestExecutionScreen`）以 "Waiting for entry speed... 95 km/h required" 这类提示呈现，而不是阻止用户进入测试流程
- 锁死边界后，未来扩展 TabGatingPolicy 的 PR 必须经过明确的 design review（不是悄悄加 condition）

**Rollback 影响**：本约束写入 `specs/cross-tab-device-gating/spec.md`，apply 阶段单测 + grep 自检覆盖，`/opsx:archive` 后会沉淀到 `openspec/specs/` 作为长期合约。

### D11 · 视觉基础组件设计原则

**决策**：所有切角面板用 `GenericShape` + `Path`，**不**用 bitmap 切图、**不**用 `RoundedCornerShape` 假装切角；所有装饰图形（cyan 遥测线 / 速度曲线 / 网格 / 斜线）用 `Canvas` + `drawPath` / `drawLine`，**不**引入 SVG 资产。

**CutCornerPanel 设计**：

- 参数：`cutSize: Dp`（默认 12.dp）+ `cutCorners: Set<CutCorner>`（默认 `{TopLeft, BottomRight}`，对角切）
- `Path`：从未切角顶点开始，遇切角顶点画两条小线（`moveTo` + `lineTo`）
- 8 种 corner variant 通过 `cutCorners` 参数表达（4 个 corner enum × 任意子集）
- 边框：`Modifier.border(1.dp, color, shape = CutCornerPanelShape(...))`

**装饰图形思路**：

- **cyan 遥测线**：`Canvas.drawPath(buildTelemetryPath(samples), Stroke(width = 1.dp.toPx(), cap = Round), color = Cyan)` —— samples 用最近 N 个 GpsData speed 点
- **速度曲线**（Records 占位）：同上 + Bezier 平滑（`Path.cubicTo`）
- **细网格**：`Canvas` + `drawLine` 重复（垂直线 + 水平线，间距 16dp，alpha 0.1）
- **斜线装饰**：`Canvas` + `drawLine`（45° 角，间距 8dp，alpha 0.05）放 section header 角落
- **状态点 ●/○**：`drawCircle`

**Rationale**：

- 全部 native Compose，零 bitmap 资产
- 后续视觉迭代不依赖资产 pipeline，CC 直接改代码
- 与 guidance §Native Compose Direction 完全对齐

### D12 · 字体角色三层架构（`TrackTechTypography`）

**决策**：`TrackTechTypography.kt` 暴露 3 个 `TextStyle`：

```kotlin
val RacingTitle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.ExtraBold,
    fontStyle = FontStyle.Italic,
    fontSize = 28.sp,
    letterSpacing = 0.05.em,
)

val Metric = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Normal,
    fontSize = 96.sp,  // SpeedHero 用；MetricTile 用 36.sp 缩小版
    letterSpacing = (-0.02).em,  // 紧致
)

val UiText = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    letterSpacing = 0.sp,
)
```

**Rationale**：

- 与 guidance §Typography Guidance 三角色对齐
- 不引入 .ttf 资产（首版不卡）
- 后续替换七段数码字体 = 单一文件 `TrackTechTypography.kt` 改 `Metric.fontFamily`

### D13 · 色号 token 与 Compose ColorScheme 桥接

**决策**：`TrackTechColors.kt` 暴露 hex token + 一组 semantic alias，**不**走 Material3 `ColorScheme.Dark` API（避免 NeonTheme 的 ColorScheme 冲突）。

```kotlin
object TrackTechColors {
    val Background = Color(0xFF07080D)
    val Surface = Color(0xFF11131C)
    val SurfaceDark = Color(0xFF0B0D13)
    val Border = Color(0xFF303442)
    val Purple = Color(0xFF9B5CFF)
    val DeepPurple = Color(0xFF5B2AA8)
    val Cyan = Color(0xFF67E8F9)
    val Green = Color(0xFF76D05E)
    val Red = Color(0xFFF25F5C)
    val TextPrimary = Color(0xFFECECF2)
    val TextSecondary = Color(0xFFA5A6B1)
    val TextMuted = Color(0xFF70727E)
}
```

semantic alias（用于"绑定语义而非颜色"）：

```kotlin
object TrackTechSemantic {
    val ReadyAccent = TrackTechColors.Green
    val ConnectingAccent = TrackTechColors.Cyan
    val NotReadyAccent = TrackTechColors.Red
    val SelectedAccent = TrackTechColors.Purple
    val PrimaryActionAccent = TrackTechColors.Purple
    val SecondaryActionAccent = TrackTechColors.Red
    val TelemetryLine = TrackTechColors.Cyan
}
```

**Rationale**：

- hex token 直接来自 guidance §Color Guidance（不重新发明）
- semantic alias 解耦"使用方"与"颜色"，未来调色只改 `TrackTechSemantic` 一层
- 不走 Material3 ColorScheme：Material3 的 `primary` / `secondary` / `tertiary` 语义与 Track Tech 不匹配（Track Tech 紫色是"主行动"而非"primary"，cyan 是"GPS"而非"secondary"），强行映射会导致语义漂移

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| `TestFlowNavigation` startDestination 改 `Selection` 后某 callback 仍跳 `TestNavRoute.Connection` 导致死循环 | apply 阶段 §0 grep `TestNavRoute.Connection` 全仓核实；保留 Connection 路由作 transitional fallback，最坏情况是回到旧 Connection 屏不影响功能 |
| Compose Navigation 依赖未引入导致编译失败 | apply 阶段 §0 grep `androidx.navigation:navigation-compose` 在 `feature/test/build.gradle.kts` / `app/build.gradle.kts`；未引入则同 commit 加依赖（属于必需改动） |
| `BleScanBottomSheet` 的 `failed` 状态启发式区分不 100% 准 | 接受 —— guidance 明确"最小状态实现，后续补"；UX 上用户可点 Scan Again 恢复 |
| `cross-tab-device-gating` 自动展开 sheet 与用户手动点 sheet 的事件竞态 | 用 `SharedFlow` + `viewModelScope.launch` 单事件触发模式，避免 sheet 重复展开；详见 D10 实现要点的 event bus 草稿 |
| `TrackTechTheme` 与 `NeonTheme` 双 Theme 嵌套引入未知 LocalProvider 冲突 | 接受 —— Compose 多层 CompositionLocal 是标准模式；apply 阶段视觉异常即调整 |
| Latest Result（Test home 显示 Personal Best / Last Run）当前 ViewModel 不直接暴露，需要新 query | 本 change 用 `TestSessionViewModel` / 现有 `TestHistoryScreen` 数据源，**最简实现** = `koinInject<TestHistoryViewModel>()`（若不存在，placeholder `--.--` + future round 补 wiring）；不阻塞首页骨架 |
| Speed Hero 的 cyan 遥测线装饰需要"最近 N 个 GpsData speed 点"，当前 ViewModel 不直接暴露 history buffer | 本 change 在 `TestHomeScreen` 内用 `LaunchedEffect` 收集 `gpsViewModel.gpsData` 维护本地 `var samples by remember { mutableStateOf<List<Float>>(emptyList()) }`，固定 capacity 60；不下沉到 ViewModel（保持接口零改动） |
| Laps Home 的 `TrackPreview` 需要 cyan 赛道线，当前 PresetTrackCatalog 数据是否含足够坐标点未知 | 本 change 不实现完整 TrackPreview，占位骨架显示赛道名 + "TRACK PREVIEW —— PLACEHOLDER" 文案；future round 接入坐标 |
| 视觉解读偏差：4 张参考图无法 100% 还原 | guidance §Important Boundary 明确"不是像素级复刻"；apply 阶段交付 5 张真机截图与 V2 对比，偏差大的具体项作为 follow-up round 单独迭代 |

## Migration Plan

### 实施顺序（apply 阶段）

1. **§0 grep 预检**：
   - `androidx.navigation:navigation-compose` 是否已在 `build.gradle.kts`
   - `TestNavRoute.Connection` 全仓引用点
   - `GpsDataViewModel(` 全仓实例化点（确保零外部消费方需要改）

2. **§1 视觉基础组件层**（依赖少，先做）：
   - `tracktech/TrackTechColors.kt`
   - `tracktech/TrackTechTypography.kt`
   - `tracktech/TrackTechTheme.kt`（Composable，提供 CompositionLocal）
   - `tracktech/CutCornerPanel.kt`（GenericShape + Modifier extension）
   - `tracktech/TrackTechBottomNav.kt`
   - `tracktech/TrackTechStatusStrip.kt`
   - `tracktech/PrimaryActionPanel.kt` + `SecondaryActionPanel.kt`
   - `tracktech/MetricNumber.kt` + `MetricTile.kt`
   - `tracktech/TrackTechRow.kt`

3. **§2 Shell + Tab 首页**（依赖 §1）：
   - `tracktech/TrackTechAppShell.kt`
   - `tracktech/TestHomeScreen.kt`
   - `tracktech/LapsHomeScreen.kt`
   - `tracktech/RecordsHomeScreen.kt`
   - `tracktech/DeviceHomeScreen.kt`

4. **§3 BLE Scan Sheet**（依赖 §1）：
   - `tracktech/BleScanBottomSheet.kt`

5. **§4 接线**：
   - `MainActivity:57` 替换调用
   - `TestFlowNavigation` startDestination 改 `Selection`

6. **§5 Cross-tab gating**：
   - 在 `TestHomeScreen` / `LapsHomeScreen` 内嵌 `SmartTestLauncher.checkLaunchConditions` 调用
   - StatusStrip 点击 → 切换 Device tab
   - 主操作未 ready → 切 Device + 自动展开 sheet（SharedFlow event bus）

7. **§6 编译/测试门槛**：
   - `:feature:test:compileDebugKotlin` + `:app:compileDebugKotlin` BUILD SUCCESSFUL
   - `:feature:test:testDebugUnitTest` 全绿（现有测试零回归）

8. **§7 真机验证**：
   - 5 张截图（Test / Laps / Records / Device / BLE Scan Sheet）
   - 与 V2 参考图视觉强度对比，记录偏差点

### Rollback 策略

单 commit 实施 → rollback = `git revert`。所有改动局限于 `feature/test/.../ui/tracktech/` 新子包 + `MainActivity:57` 单行 + `TestFlowNavigation` startDestination 单行，rollback 后回到 Round 4 baseline 状态。

## Open Questions

无设计阶段未决项。`apply` 阶段需要 grep 确认的待核实项（不是设计决策）：

- `androidx.navigation:navigation-compose` dependency 是否已在 gradle 文件（apply §0.1 grep）
- `TestNavRoute.Connection` 全仓显式跳转点（apply §0.2 grep；预期最多 1 处即 startDestination 自身）
- `Material Icons Extended` 的 `Speedometer` / `Brake` 等 icon 名是否完全匹配（apply 阶段实测，无匹配则用 fallback `Icons.Default.Speed` / `Icons.Default.Stop` 等）
