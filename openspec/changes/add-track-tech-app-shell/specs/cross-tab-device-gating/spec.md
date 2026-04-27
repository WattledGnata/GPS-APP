## ADDED Requirements

### Requirement: TrackTechStatusStrip 显示在 Test/Laps tab 顶部

Test tab 首页（`TestHomeScreen` 或在 Test tab nested nav 内嵌的 `TestSelectionScreen` 上方）和 Laps tab 首页（`LapsHomeScreen`）MUST 在 page header 下方紧凑显示 `TrackTechStatusStrip`，包含 3 个 status item：

- GPS：`GPS ready` / `GPS no fix` / `Acquiring fix...`（按 `gpsData.isTestReady` + `connectionState` 派生）
- 频率：`${gpsData.frequency.toInt()}Hz`
- 信号：`Good signal` / `Weak signal` / `No signal`（按 `dataQuality.overall` 派生）

每个 item 文字色按状态：Good 绿、Acquiring/Weak 黄/cyan、No fix/No signal 红。

#### Scenario: TrackTechStatusStrip 在 TestHomeScreen 显示

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/TestHomeScreen.kt` 源码
- **WHEN** 阅读 page header 下方的 Composable 布局
- **THEN** 含 `TrackTechStatusStrip(...)` 调用

#### Scenario: TrackTechStatusStrip 在 LapsHomeScreen 显示

- **GIVEN** 实施后 `LapsHomeScreen.kt` 源码
- **WHEN** 阅读 page header 下方的 Composable 布局
- **THEN** 含 `TrackTechStatusStrip(...)` 调用

#### Scenario: StatusStrip 含 3 个 status item

- **GIVEN** 实施后 `TrackTechStatusStrip.kt` 源码
- **WHEN** 阅读组件实现
- **THEN** 至少接受参数 `gpsReady` / `frequencyHz` / `signalQuality` 或等价 3 个语义维度的 status props

### Requirement: StatusStrip 点击主动跳转到 Device tab

`TrackTechStatusStrip` MUST 接受 `onClick` lambda 参数，点击后切换到 Device tab。

在 Test/Laps 屏调用 `TrackTechStatusStrip` 时 MUST 传入 lambda：调用上层 `navController.navigate("device") { popUpTo(navController.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }`。

#### Scenario: TrackTechStatusStrip 接受 onClick

- **GIVEN** 实施后 `TrackTechStatusStrip.kt` 源码
- **WHEN** 阅读 Composable 函数签名
- **THEN** 包含 `onClick: () -> Unit` 或 `onClick: (() -> Unit)? = null` 参数
- **AND** 实现内对 outermost Modifier 调用 `Modifier.clickable { onClick() }` 或等价交互

#### Scenario: Test/Laps 屏传入跳转 Device 的 lambda

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 内 `TrackTechStatusStrip` 调用点
- **WHEN** 阅读传入的 onClick lambda
- **THEN** lambda body 含 `navController.navigate("device")` 或等价跳转语句

### Requirement: TabGatingPolicy 仅检查 device/data 4 项基础条件（MUST 边界）

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TabGatingPolicy.kt` MUST 存在，提供 `computeTabReadiness(connectionState, gpsData, dataQuality): TabReadiness` API，**仅且仅** 检查以下 4 项 device/data 基础条件：

| 条件 enum | 阈值 | 数据源 |
|---|---|---|
| `BLE_CONNECTED` | `connectionState == ConnectionState.CONNECTED` | `GpsDataViewModel.connectionState` |
| `DATA_FRESH` | `dataQuality.dataAge < 1000` ms | `GpsDataViewModel.dataQuality.dataAge` |
| `SATELLITES_SUFFICIENT` | `gpsData.satelliteCount >= 6` | `GpsDataViewModel.gpsData.satelliteCount` |
| `HDOP_GOOD` | `gpsData.hdop > 0 && gpsData.hdop < 2.0` | `GpsDataViewModel.gpsData.hdop` |

`canEnterTestFlow = unmetConditions.isEmpty()`，即 4 项全满足才返回 true。

阈值 MUST 与 `core/domain/.../usecase/SmartTestLauncher.checkLaunchConditions` 中对应 4 项 device condition 阈值保持一致（`lastDataAge < 1000` / `satelliteCount >= 6` / `hdop < 2.0 && hdop > 0` / `ConnectionState.CONNECTED`），保证语义层一致。

#### Scenario: TabGatingPolicy 文件存在 + 4 个 enum 项

- **GIVEN** 实施后代码库
- **WHEN** `cat feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TabGatingPolicy.kt`
- **THEN** 含 `enum class TabReadinessCondition { BLE_CONNECTED, DATA_FRESH, SATELLITES_SUFFICIENT, HDOP_GOOD }`（顺序不限，但 MUST 覆盖 4 项）
- **AND** 含 `data class TabReadiness(val canEnterTestFlow: Boolean, val unmetConditions: List<TabReadinessCondition>)`
- **AND** 含 `object TabGatingPolicy { fun computeTabReadiness(...): TabReadiness }` 或等价单例 API

#### Scenario: 4 项条件阈值与 SmartTestLauncher 对齐

- **GIVEN** 实施后 `TabGatingPolicy.kt` 源码
- **WHEN** 阅读 `computeTabReadiness` 实现
- **THEN** BLE 条件用 `connectionState == ConnectionState.CONNECTED` 判定
- **AND** data fresh 条件用 `dataQuality.dataAge < 1000` 判定
- **AND** satellites 条件用 `gpsData.satelliteCount >= 6` 判定
- **AND** hdop 条件用 `gpsData.hdop > 0 && gpsData.hdop < 2.0` 判定（**两端开区间**：hdop = 0 或 hdop >= 2.0 都视为 unmet）

#### Scenario: 4 项条件全满足返回 canEnterTestFlow = true

- **GIVEN** 调用 `TabGatingPolicy.computeTabReadiness(connectionState = CONNECTED, gpsData = GpsData(satelliteCount = 8, hdop = 1.5, ...), dataQuality = DataQuality(dataAge = 500, ...))`
- **WHEN** 读返回值
- **THEN** `canEnterTestFlow == true` && `unmetConditions.isEmpty()`

#### Scenario: BLE 未连返回 BLE_CONNECTED unmet

- **GIVEN** 调用 `computeTabReadiness(connectionState = DISCONNECTED, gpsData = (8 sats, hdop 1.5), dataQuality = (dataAge 500))`
- **WHEN** 读返回值
- **THEN** `canEnterTestFlow == false`
- **AND** `unmetConditions` 含 `TabReadinessCondition.BLE_CONNECTED`

#### Scenario: 数据陈旧返回 DATA_FRESH unmet

- **GIVEN** 调用 `computeTabReadiness(connectionState = CONNECTED, gpsData = (8 sats, hdop 1.5), dataQuality = (dataAge 1500))`
- **WHEN** 读返回值
- **THEN** `unmetConditions` 含 `TabReadinessCondition.DATA_FRESH`

#### Scenario: 卫星不足返回 SATELLITES_SUFFICIENT unmet

- **GIVEN** 调用 `computeTabReadiness(connectionState = CONNECTED, gpsData = (5 sats, hdop 1.5), dataQuality = (dataAge 500))`
- **WHEN** 读返回值
- **THEN** `unmetConditions` 含 `TabReadinessCondition.SATELLITES_SUFFICIENT`

#### Scenario: HDOP 边界返回 HDOP_GOOD unmet

- **GIVEN** 调用 `computeTabReadiness(connectionState = CONNECTED, gpsData = (8 sats, hdop 0.0), dataQuality = (dataAge 500))`（hdop = 0 视为 GPS 未 fix）
- **WHEN** 读返回值
- **THEN** `unmetConditions` 含 `TabReadinessCondition.HDOP_GOOD`
- **AND** 同样调用 `gpsData = (8 sats, hdop 2.0)` 也 `unmetConditions` 含 `HDOP_GOOD`（>= 2.0 边界 unmet）

### Requirement: TabGatingPolicy 不检查 speed range / test template / 起点条件（MUST NOT 边界）

`TabGatingPolicy.computeTabReadiness` MUST NOT 检查以下任何条件，且 `TabReadinessCondition` enum MUST NOT 包含以下任何成员：

- **speed range**：任何 `gpsData.speed` 区间检查（如 `0-3 km/h` / `95-105 km/h` / 静止 / 巡航 等）
- **test template**：测试类型 / 模板（如 `0-100` / `100-0` / `lap-session` / `track-session` 等）
- **acceleration/braking 起点条件**：与具体测试流程相关的预备状态（如方向稳定性、制动距离前置、起点位置等）

**Rationale**：

- 首页 tab 入口的语义是 "用户能否进入测试流程"，不是 "用户能否立即开始测试"
- 速度门槛 / template 准备 / 起点条件应在 **进入测试流程后** 的执行准备屏（`TestExecutionScreen` / `LapDebugExecutionScreen`）以 "Waiting for entry speed... 95 km/h required" 类提示呈现，由 `SmartTestLauncher.checkLaunchConditions` 处理；不阻止用户进入测试流程
- **真实 bug 场景**（评审依据）：若 TabGatingPolicy 复用 `SmartTestLauncher.checkLaunchConditions`，用户静止（speed = 0 km/h）点 `100-0` 时，`speed_at_start` 条件检查 `0 in 95.0..105.0 = false` 必然 unmet，`canLaunch = false`，错误把用户引导到 Device tab；但实际设备/数据完全正常，用户需要的是先开车加速到 100 km/h 后再进入 `100-0` 测试

#### Scenario: TabReadinessCondition enum 不含 speed/template/起点条件成员

- **GIVEN** 实施后 `TabGatingPolicy.kt` 源码
- **WHEN** grep `enum class TabReadinessCondition`
- **THEN** enum body **不**含任何以下命名（或等价语义）成员：`SPEED` / `SPEED_AT_START` / `STARTING_SPEED` / `SPEED_RANGE` / `TEMPLATE` / `TEST_TYPE` / `LAUNCH_TEMPLATE` / `START_CONDITION` / `BRAKING_ENTRY` / `ACCEL_ENTRY`

#### Scenario: computeTabReadiness 函数体不引用 gpsData.speed

- **GIVEN** 实施后 `TabGatingPolicy.kt` 源码
- **WHEN** grep `gpsData.speed` 在 `computeTabReadiness` 函数体内
- **THEN** 零命中（速度字段不参与 readiness 计算）

#### Scenario: computeTabReadiness 函数签名不接受 testType / template / startSpeed 参数

- **GIVEN** 实施后 `TabGatingPolicy.kt` 源码
- **WHEN** 阅读 `computeTabReadiness` 函数签名
- **THEN** 参数列表**仅**含 `connectionState: ConnectionState` + `gpsData: GpsData` + `dataQuality: DataQuality` 三参数（**不**含 `testType` / `template` / `startSpeedMin` / `startSpeedMax` / 任何 speed-related / template-related 参数）

#### Scenario: 真实 bug 场景回归——用户静止时 100-0 入口可进入测试流程

- **GIVEN** 调用 `TabGatingPolicy.computeTabReadiness(connectionState = CONNECTED, gpsData = GpsData(speed = 0.0, satelliteCount = 8, hdop = 1.5), dataQuality = DataQuality(dataAge = 500))`
- **WHEN** 读返回值
- **THEN** `canEnterTestFlow == true`（**不**因速度 = 0 阻止）
- **AND** `unmetConditions.isEmpty()`
- **AND** Test 首页 `100-0` 主操作 onClick 进入测试流程，**不**导到 Device tab

### Requirement: Test/Laps 主操作未 ready 时拦截并引导到 Device

Test 首页的 `0-100` / `100-0` 主操作和 Laps 首页的 `START LAP SESSION` 主操作 MUST 通过 `TabGatingPolicy.computeTabReadiness(connectionState, gpsData, dataQuality): TabReadiness` 派生 enabled 状态。

`readiness.canEnterTestFlow == true` 时主操作正常进入对应 nested screen。
`readiness.canEnterTestFlow == false` 时主操作 onClick MUST：

1. 不进入 nested screen
2. 显示提示（`Toast.makeText(context, "Connect a GPS device first", Toast.LENGTH_SHORT)` 或等价 Snackbar，文案可优化但 MUST 提示用户去 Device tab）
3. 切换到 Device tab（`navController.navigate("device") { ... }`）
4. 如果 `connectionState == DISCONNECTED`，自动展开 `BleScanBottomSheet`（通过 SharedFlow event bus 通知 `DeviceHomeScreen`，由 `DeviceHomeScreen` 监听 `LaunchedEffect` 设置 `showSheet = true`）

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

#### Scenario: 未 ready 分支跳 Device tab

- **GIVEN** 实施后 `TestHomeScreen.kt` 主操作 onClick 的 else 分支
- **WHEN** 阅读分支 body
- **THEN** 含 `navController.navigate("device")` 或等价语句

#### Scenario: DISCONNECTED 时自动展开 sheet

- **GIVEN** 实施后 `TestHomeScreen.kt` 或 `LapsHomeScreen.kt` 主操作未 ready 拦截分支
- **WHEN** 阅读自动展开 sheet 的逻辑
- **THEN** 含对 `connectionState == ConnectionState.DISCONNECTED` 或等价的判断
- **AND** 含触发 sheet 展开的事件（如 `events.tryEmit(...)` / `showScanSheetEvent.tryEmit(Unit)` 等 SharedFlow 模式）

#### Scenario: DeviceHomeScreen 监听 SharedFlow 自动展开 sheet

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 `LaunchedEffect` block
- **THEN** 含对某 SharedFlow（如 `gateEvents` / `showScanSheetEvent`）的 `collect { showSheet = true }` 调用

### Requirement: SharedFlow event bus 用于跨 tab 触发 sheet 展开

跨 tab 通信（Test/Laps → Device 自动展开 BLE Scan Sheet）MUST 通过 SharedFlow 实现，**不**通过 `navController.currentBackStackEntry?.savedStateHandle` 或 ViewModel state。

最小启发式实现：在 `tracktech/` 子包内新建 `TrackTechEventBus.kt`（object 或 koin single），暴露：

```kotlin
object TrackTechEventBus {
    private val _showScanSheetEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showScanSheetEvent: SharedFlow<Unit> = _showScanSheetEvent.asSharedFlow()
    fun requestShowScanSheet() { _showScanSheetEvent.tryEmit(Unit) }
}
```

或等价的 koin singleton DI 注入（如果选择 DI 方案，MUST 在 `AppModule.kt` 加 `single { TrackTechEventBus() }` 一行；object 方案则零 DI 改动）。

#### Scenario: TrackTechEventBus 文件或等价 SharedFlow 入口存在

- **GIVEN** 实施后 `feature/test/.../ui/tracktech/` 子包
- **WHEN** grep `MutableSharedFlow<Unit>` 或 `MutableSharedFlow<*>`
- **THEN** 至少命中一处用于 cross-tab gating 事件传递的 SharedFlow 定义（命中文件名不限，但 MUST 在 tracktech 子包内）

### Requirement: SmartTestLauncher 接口零改动

`core/domain/src/main/java/com/blazepush/core/domain/usecase/SmartTestLauncher.kt` 的 public API（`checkLaunchConditions` / `canLaunch` / `LaunchStatus` / `LaunchCondition` / `ConditionIcon`）MUST 零改动。本 change MUST NOT 修改 `core/domain` 模块。

`SmartTestLauncher` 仍由其原有消费方（`TestExecutionScreen` / `LapDebugExecutionScreen` 等执行前 Smart Launch 场景）独占使用，**不被** TabGatingPolicy / Test 首页 / Laps 首页直接调用。

#### Scenario: SmartTestLauncher.kt diff 零改动

- **GIVEN** 实施前后 `core/domain/src/main/java/com/blazepush/core/domain/usecase/SmartTestLauncher.kt` 源码
- **WHEN** `git diff <baseline>..HEAD -- "core/domain/src/main/java/com/blazepush/core/domain/usecase/SmartTestLauncher.kt"`
- **THEN** 零行改动

#### Scenario: 首页 home screen 不 import SmartTestLauncher

- **GIVEN** 实施后 `TestHomeScreen.kt` / `LapsHomeScreen.kt` 源码
- **WHEN** grep `import com.blazepush.core.domain.usecase.SmartTestLauncher`
- **THEN** 零命中（两屏均不 import SmartTestLauncher，主操作 gating 完全走 TabGatingPolicy）
