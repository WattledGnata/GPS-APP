## ADDED Requirements

### Requirement: DeviceHomeScreen 是 Device tab 路由的连接控制台

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt` MUST 存在，作为 Device tab 路由 `device` 的 root Composable。屏幕结构 MUST 包含且按以下顺序排列 6 个 section：

1. Page Header：标题 `Device` + 右上角设置 icon
2. Readiness Hero（CutCornerPanel）
3. Quick Status Row（3 个 CutCornerPanel 小卡：BLE / SATS / RATE）
4. Connected Device 主卡（紫色描边 CutCornerPanel）
5. GPS Details 入口行
6. Diagnostics 入口行
7. Settings 入口行

#### Scenario: DeviceHomeScreen 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `ls feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt`
- **THEN** 文件存在

#### Scenario: 6 个 section 字面量命中

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** grep section 标题字符串字面量
- **THEN** 命中（顺序不限）：`READY TO TEST` 或 `CONNECT GPS DEVICE` 或 `WAITING FOR GPS LOCK`（hero 三态文案任一）+ `BLE` + `SATS` + `RATE` + `CONNECTED DEVICE` + `GPS DETAILS` + `DIAGNOSTICS` + `SETTINGS`

### Requirement: Readiness Hero 三态状态映射

Readiness Hero 区域 MUST 根据 `GpsDataViewModel.connectionState` + `GpsData.isTestReady` 渲染 4 种状态文案 + 对应主色：

| connectionState | isTestReady | hero 文案 | 主色 token |
|---|---|---|---|
| `CONNECTED` | `true` | `READY TO TEST` | `TrackTechSemantic.ReadyAccent`（Green） |
| `CONNECTED` | `false` | `WAITING FOR GPS LOCK` | `TrackTechSemantic.ConnectingAccent`（Cyan） |
| `CONNECTING` | (任意) | `CONNECTING…` | `TrackTechColors.TextSecondary`（灰） |
| `DISCONNECTED` 或 `DISCONNECTING` | (任意) | `CONNECT GPS DEVICE` | `TrackTechSemantic.PrimaryActionAccent`（Purple） |

Hero 副文 MUST 显示 `GPS locked · BLE connected` 类似的并列状态短语，根据当前 `connectionState` + GPS lock 真实情况自适应（如 `BLE disconnected · GPS no fix`）。

#### Scenario: 状态映射代码内含 4 个分支

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 hero 状态计算逻辑（如 `when (connectionState) { ... }` 或等价表达式）
- **THEN** 含 `READY TO TEST` / `WAITING FOR GPS LOCK` / `CONNECTING` / `CONNECT GPS DEVICE` 四个文案分支
- **AND** 每个分支绑定对应主色 token

#### Scenario: hero 三态副文随状态变化

- **GIVEN** Device tab 当前打开
- **WHEN** `GpsDataViewModel.connectionState` 从 `DISCONNECTED` 变为 `CONNECTED` + `isTestReady = true`
- **THEN** Hero 文案从 `CONNECT GPS DEVICE` 切到 `READY TO TEST`，主色从 Purple 切到 Green

### Requirement: Quick Status Row 三状态卡

Hero 下方 MUST 渲染 3 个并列 CutCornerPanel 小卡，分别显示 `BLE` / `SATS` / `RATE` 信息：

- `BLE` 卡：上标 `BLE`，主文案 `Connected` / `Disconnected` / `Connecting…`，副文 device 名（如 `RaceChrono`）
- `SATS` 卡：上标 `SATS`，主文案 `GpsData.satelliteCount` 数字，副文 `Ready` / `Low`（≥6 为 Ready）
- `RATE` 卡：上标 `RATE`，主文案 `GpsData.frequency` 取整 + `Hz`（如 `25Hz`），副文 `Good` / `Slow`（≥10Hz 为 Good）

每张卡的 accent 色（边框或上标颜色）按状态着色：BLE 卡用 `Cyan`（GPS/BLE 语义），SATS 卡用 `Cyan`，RATE 卡用 `Cyan`。Connected/Ready/Good 状态可叠加 `Green` 微点缀（如卡角小圆点）。

#### Scenario: 3 张状态卡数据绑定

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 Quick Status Row 渲染代码
- **THEN** 含对 `connectionState` / `gpsData.satelliteCount` / `gpsData.frequency` 三处数据绑定

### Requirement: Connected Device 主卡

Connected Device 主卡 MUST 用紫色描边 CutCornerPanel 渲染，包含：

- 上标 `CONNECTED DEVICE`
- 大号 device 名（来自 `BleDeviceManager` 当前连接 device 的 name；若 `connectionState != CONNECTED` 则显示 `Not connected` placeholder）
- 副文 `Ready for Test` / `Waiting for GPS Lock` / `Disconnected`（按状态自适应）
- 右侧动作：`SCAN`（紫色文字按钮，点击触发 `BleScanBottomSheet` 展开）+ `DISCONNECT`（红色描边按钮，仅在 `connectionState == CONNECTED` 时启用，点击调 `gpsViewModel.disconnect()`）

如果当前 `BleDeviceManager` 暂未提供 connected device name 的稳定 API，本 change 用最小启发式：从 `scanResults` 找最近一次 `connectDevice(...)` 调用的 device，缓存到 `DeviceHomeScreen` 局部 state；找不到则显示 `RaceChrono GPS`（generic placeholder）+ TODO 注释标注 future round 补底层 API。

#### Scenario: Connected Device 主卡含 SCAN/DISCONNECT 双按钮

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 Connected Device 卡渲染
- **THEN** 含 `SCAN` 按钮 onClick callback + `DISCONNECT` 按钮 onClick callback
- **AND** `SCAN` callback 触发 `BleScanBottomSheet` 展开（通过 `var showSheet by remember { mutableStateOf(false) }` 或等价 state 管理）
- **AND** `DISCONNECT` 按钮 enabled 状态绑定 `connectionState == CONNECTED`

#### Scenario: 主卡用紫色描边

- **GIVEN** 实施后 `DeviceHomeScreen.kt` Connected Device 卡渲染代码
- **WHEN** 阅读卡的 Modifier
- **THEN** 含 `border(width = 1.dp, color = TrackTechColors.Purple, shape = CutCornerPanelShape(...))` 或等价描边设置

### Requirement: GPS Details / Diagnostics / Settings 入口行（占位）

Device Home 底部 MUST 有 3 个入口行，命名分别为 `GPS DETAILS` / `DIAGNOSTICS` / `SETTINGS`，每行用 `TrackTechRow` 组件渲染（含 leading icon + 主文案 + 副文案 + trailing chevron）。

本 change 入口行 onClick callback MUST 是 placeholder（弹 `Toast` "Coming in next round" 或调 no-op lambda），子页延后到独立 round 实现。

#### Scenario: 3 个入口行存在

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** grep 字符串字面量
- **THEN** 命中 `GPS DETAILS` + `DIAGNOSTICS` + `SETTINGS` 三个字面量

#### Scenario: 入口行 onClick 是 placeholder

- **GIVEN** 实施后 `DeviceHomeScreen.kt` 源码
- **WHEN** 阅读 3 个入口行的 onClick
- **THEN** 实现 SHOULD 是 `Toast.makeText(context, "Coming in next round", ...)` / `{}` / 等价 placeholder（不跳转到任何具体子页）

### Requirement: 现有 DeviceConnectionScreen 保留作 transitional fallback

`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/DeviceConnectionScreen.kt` MUST 保留不删除，作为 Test tab 内 nested `TestNavRoute.Connection` 路由的 transitional fallback。但本 change MUST 在该文件顶部添加 deprecation 注释，注明：

- 4 tab shell 后 `Device` tab 是连接入口
- 该屏不再是首屏，仅作 nested fallback
- future round 删除

#### Scenario: DeviceConnectionScreen 文件存在

- **GIVEN** 实施后代码库
- **WHEN** `ls feature/test/src/main/java/com/blazepush/feature/test/ui/screen/DeviceConnectionScreen.kt`
- **THEN** 文件存在

#### Scenario: deprecation 注释在文件顶部

- **GIVEN** 实施后 `DeviceConnectionScreen.kt` 前 20 行
- **WHEN** 阅读注释
- **THEN** 含 `@Deprecated` annotation 或 KDoc 注释明确 "Track Tech V2 后 Device tab 是全局连接入口" / "transitional fallback" 字样
