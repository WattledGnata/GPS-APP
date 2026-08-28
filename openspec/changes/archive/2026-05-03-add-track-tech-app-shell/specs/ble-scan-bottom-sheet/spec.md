## ADDED Requirements

### Requirement: BleScanBottomSheet 用 Material3 ModalBottomSheet 实现

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/BleScanBottomSheet.kt` MUST 存在，使用 `androidx.compose.material3.ModalBottomSheet` API 实现底部弹层（**不**用 `AlertDialog` / `Dialog`）。

#### Scenario: BleScanBottomSheet 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `ls feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/BleScanBottomSheet.kt`
- **THEN** 文件存在

#### Scenario: 用 Material3 ModalBottomSheet API

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** grep `ModalBottomSheet`
- **THEN** 命中（`import androidx.compose.material3.ModalBottomSheet` + 调用点）
- **AND** 同文件**不**含 `androidx.compose.ui.window.Dialog` 或 `androidx.compose.material3.AlertDialog` import

### Requirement: 5 个状态机分支

`BleScanBottomSheet` MUST 渲染 5 种状态，状态由 `GpsDataViewModel.isScanning` + `scanResults` + `connectionState` + sheet 局部 state（`attemptedConnectAddress`） 派生：

| 状态名 | 触发条件 | 主 UI |
|---|---|---|
| `scanning` | `isScanning == true` && `scanResults.isEmpty()` | 副标 "Searching nearby GPS receivers" + spinner，无设备列表 |
| `found` | `scanResults.isNotEmpty()` && `connectionState != CONNECTING` | 副标 "Searching nearby GPS receivers · ${count} found"（如仍在扫）或 "${count} devices found"（已停扫）+ 设备列表 |
| `empty` | `isScanning == false` && `scanResults.isEmpty()` && 已扫一轮 | 副标 "No devices found" + Scan Again 按钮，无设备列表 |
| `connecting` | `connectionState == CONNECTING` | 副标 "Connecting to ${selectedDevice?.name ?: "device"}" + spinner |
| `failed` | `connectionState == DISCONNECTED` && sheet 内 `attemptedConnectAddress != null` && 该 address 不在当前 connected device | 副标 "Connection failed · Tap a device to retry" + 设备列表，CONNECT 按钮文案变 "RETRY" |

#### Scenario: 5 个状态文案字面量命中

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** grep 状态文案字符串字面量
- **THEN** 命中：`Searching nearby GPS receivers` + `No devices found` + `Connecting to` + `Connection failed`（4 个核心字面量；`found` 状态副标可在 scanning 字面量基础上动态拼接 count 不强制独立字面量）

#### Scenario: 状态派生函数包含 5 分支

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** 阅读状态计算（如 `derivedStateOf` / `when` / 状态机函数）
- **THEN** 至少能识别 5 个状态分支：scanning / found / empty / connecting / failed

### Requirement: 设备列表行视觉与交互

每个设备行（`scanResults` 中的 `ScannedDevice`）MUST 渲染：

- 左侧 selected radio（紫色填充 ✓ / 灰色空圈 ○）
- 主文 device 名（`scannedDevice.name`）
- 副文 标签（如 `Recommended` / `External GPS` / `Unsupported`）—— 本 change 用最小启发式：name 含 `RaceChrono` 标 `Recommended`，name 含 `GPS` 标 `External GPS`，否则标 `Unsupported`
- 右侧 RSSI dBm 文本（`${scannedDevice.rssi} dBm`）+ 4 格信号条（`buildSignalBars(rssi)` 工具函数派生格数：≥-50 为 4 格，≥-65 为 3 格，≥-80 为 2 格，否则 1 格）
- `selectedDevice == this device` 时整行用紫色描边 CutCornerPanel；否则灰色描边
- `Unsupported` 行 SHOULD 弱化（alpha 降低 / 文字变 `TextMuted`）

点击行 MUST 设置 `selectedDevice = this device`（局部 state，不下沉到 ViewModel）。

#### Scenario: 设备行含 4 个视觉元素

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 设备行渲染代码
- **WHEN** 阅读 row 渲染
- **THEN** 含 selected radio + device 名 + RSSI 文本 + 信号条 4 个视觉元素

#### Scenario: 推荐标签启发式

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** grep 标签字符串字面量
- **THEN** 命中 `Recommended` + `Unsupported` 两个字面量（`External GPS` 可选，未命中不视为违规）

#### Scenario: 选中态局部 state 不下沉到 ViewModel

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** grep `selectedDevice`
- **THEN** 命中 `var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }` 或等价 `rememberSaveable` 局部 state；**不**命中 `gpsViewModel.selectedDevice` / `gpsViewModel.setSelectedDevice(...)`（即不要求 ViewModel 暴露选中 state）

### Requirement: 底部行动按钮 CONNECT + SCAN AGAIN

Sheet 底部 MUST 渲染：

- `CONNECT` 紫色主按钮（PrimaryActionPanel 复用 / 自定义紫色 Button）：
  - `enabled = selectedDevice != null && connectionState != CONNECTING`
  - `onClick`：调 `gpsViewModel.connectDevice(selectedDevice!!)` + 设置 sheet 内 `attemptedConnectAddress = selectedDevice!!.address`
  - 文案：`failed` 状态下变 `RETRY`，其他状态都是 `CONNECT`
- `SCAN AGAIN` 紫色文字按钮：
  - `enabled = !isScanning`
  - `onClick`：调 `gpsViewModel.startScan()`
  - 文案固定 `SCAN AGAIN`
- `Choose a BLE GPS receiver for tests` hint 文案，在最底部以 muted 灰显示

#### Scenario: CONNECT 按钮绑定 selectedDevice

- **GIVEN** 实施后 `BleScanBottomSheet.kt` CONNECT 按钮渲染代码
- **WHEN** 阅读按钮 enabled / onClick
- **THEN** enabled 含 `selectedDevice != null` 表达式
- **AND** onClick 调 `gpsViewModel.connectDevice(...)` 或等价方法

#### Scenario: SCAN AGAIN 按钮调 startScan

- **GIVEN** 实施后 `BleScanBottomSheet.kt` SCAN AGAIN 按钮渲染代码
- **WHEN** 阅读按钮 onClick
- **THEN** 调 `gpsViewModel.startScan()`

#### Scenario: hint 文案命中

- **GIVEN** 实施后 `BleScanBottomSheet.kt` 源码
- **WHEN** grep 字符串字面量
- **THEN** 命中 `Choose a BLE GPS receiver for tests` 或语义等价的 hint 文案（如 `Select a BLE device to connect`）

### Requirement: 关闭 sheet 时停止扫描

Sheet `onDismissRequest`（用户拉下手势 / 点 close X / 点击 scrim）MUST 调用 `gpsViewModel.stopScan()`，避免扫描泄漏。

#### Scenario: onDismissRequest 内调 stopScan

- **GIVEN** 实施后 `BleScanBottomSheet.kt` `ModalBottomSheet(onDismissRequest = ...)` 调用点
- **WHEN** 阅读 lambda body
- **THEN** 含 `gpsViewModel.stopScan()` 调用

### Requirement: 现有 DeviceScanDialog 保留作 transitional fallback

`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/DeviceScanDialog.kt` MUST 保留不删除，作为 transitional fallback（旧代码若仍使用该 Dialog 不报错）。本 change MUST 在该文件顶部添加 deprecation 注释，注明：

- Track Tech V2 后 BLE 扫描走 `BleScanBottomSheet`（ModalBottomSheet 形态）
- 该 Dialog 不再被新代码调用
- future round 删除

#### Scenario: DeviceScanDialog 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `ls feature/test/src/main/java/com/blazepush/feature/test/ui/screen/DeviceScanDialog.kt`
- **THEN** 文件存在

#### Scenario: deprecation 注释在文件顶部

- **GIVEN** 实施后 `DeviceScanDialog.kt` 前 20 行
- **WHEN** 阅读注释
- **THEN** 含 `@Deprecated` annotation 或 KDoc 注释明确 "Track Tech V2 后用 BleScanBottomSheet" / "transitional fallback" 字样
