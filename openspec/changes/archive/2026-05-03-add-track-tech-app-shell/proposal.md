## Why

当前 `MainActivity:57` 直接调 `TestFlowNavigation()`，单栈线性流：`Connection → Selection → LapDebugConfig → Execution → Result/History`。`Connection` 是阻塞首屏，`DeviceConnectionScreen` 把连接、扫描、GPS 信号、开始测试按钮都堆在一起。这套形态属于试用期交互（Round 1-4 已闭环底层稳健性，但 UI 仍是 Material3 默认形态），与 `docs/design/track-tech-v2-cc-guidance.md` 锁定的 Track Tech V2 方向（4 tab shell + Device 连接控制台 + BLE 工具弹层 + 视觉强度分层）有显著结构差距。

`docs/design/track-tech-function-probe.md` §11 capability matrix 已盘点：
- **数据层强于 UI 层**：`GpsDataViewModel` 已是 Application singleton，暴露 `gpsData` / `connectionState` / `dataQuality` / `scanResults` / `isScanning` 等共享 StateFlow（`AppModule.kt:121` `single { GpsDataViewModel(get(), get(), get()) }` 三参数注册，跨 tab 共享天然支持）
- **最大缺口是 IA**（信息架构）：4 tab shell + 跨 tab 共享状态展示 + cross-tab gating 全部缺失
- **次大缺口是 Track Tech 视觉基础组件**：现有 `feature/test/.../ui/theme/Components.kt` 提供 NeonButton/NeonGradientButton/NeonCard/NeonTextField/NeonSurfaceBadge，但都是普通 Material3 圆角形态，与 V2 切角面板风格不匹配

本 change 是 **Track Tech UI 重构第一切片**：交付 4 tab shell + Device 连接控制台 + BLE 工具弹层 + Test/Laps 跨 tab gating，**不**做 Records 完整图表 / Laps 赛道选择产品化 / GPS Details 密集页 / Diagnostics / Settings 子页 / Execution HUD 升级 / 字体文件最终化。

## What Changes

### Capability 1：`track-tech-app-shell`（4 tab 持久化 shell）

- `MainActivity` 入口从 `TestFlowNavigation()` 改为 `TrackTechAppShell()`（新建）
- shell 顶层用 Compose Navigation `NavHost`，4 个 top-level 路由：`test` / `laps` / `records` / `device`
- `TrackTechBottomNav`（新组件）固定底部，4 个 tab item：`Test | Laps | Records | Device`
- 当前 tab 用切角紫色边框 + 低透明紫色填充，**不**强发光（与 guidance §Bottom Navigation 对齐）
- tab 间切换保留各自的 nested navigation 状态（用 nav graph 的 `saveState = true` + `restoreState = true`）
- Test tab 内嵌现有 `TestFlowNavigation` 作为 nested nav（保留 `Selection → Execution → Result/History` 现有路径，但 `Connection` 不再是首屏）
- Laps/Records tab 提供首页骨架（占位结构，承载 V2 视觉，但暂不实现完整产品逻辑）
- 跨 tab 共享 `GpsDataViewModel`（已是 Koin `single` scope，直接 `koinInject<GpsDataViewModel>()`）

### Capability 2：`device-home-connection-console`（Device tab 连接控制台）

- 新建 `DeviceHomeScreen`（路由 `device`），替代现有 `DeviceConnectionScreen` 在 4 tab shell 内的角色
- 视觉结构（参考 `docs/design/visual-refs/device-home-v2-calm.png`）：
  - **Readiness Hero**（切角面板）：● `READY TO TEST` / `CONNECT GPS DEVICE` / `WAITING FOR GPS LOCK` 三态文案 + `GPS locked · BLE connected` 副文 + `25Hz · Quality Good` 状态行 + 右侧 cyan 遥测线轻装饰
  - **Quick Status Row**（三切角小卡）：`BLE` / `SATS` / `RATE`，三种状态以 `green/cyan/red` 着色
  - **Connected Device** 主卡（紫色描边）：`RaceChrono GPS` 名字 + `Ready for Test` 副文 + `SCAN`（紫色文字按钮）+ `DISCONNECT`（红色描边按钮）
  - **GPS Details** 入口行（点击进 GPS Details 子页 —— 本 change 占位，子页留 future round）
  - **Diagnostics** 入口行（占位）
  - **Settings** 入口行（占位）
- 状态映射：
  - `connectionState = CONNECTED + isTestReady = true` → `READY TO TEST`（绿色 hero）
  - `connectionState = DISCONNECTED` → `CONNECT GPS DEVICE`（紫色 hero）
  - `connectionState = CONNECTED + isTestReady = false` → `WAITING FOR GPS LOCK`（cyan hero）
  - `connectionState = CONNECTING` → `CONNECTING…`（灰色 hero）
- `DeviceHomeScreen` 暴露 `onScanClick` callback 触发 `BleScanBottomSheet`（capability 3）
- `DeviceConnectionScreen` 不删除（Test tab 内 nested 流程的 `Connection` 路由仍可能被旧代码路径触达，留作 transitional fallback，标注 deprecation 注释，future round 删除）

### Capability 3：`ble-scan-bottom-sheet`（Material3 ModalBottomSheet 替换 Dialog）

- 新建 `BleScanBottomSheet`（Material3 `ModalBottomSheet`），替代现有 `DeviceScanDialog` 在 Device tab 内的角色
- 视觉结构（参考 `docs/design/visual-refs/ble-scan-sheet-v2-calmer.png`）：
  - 从底部弹出，背景 Device 页压暗（Material3 默认 scrim）
  - **Header**：`SCAN DEVICES` 标题 + close（X）按钮
  - **副标**：`Searching nearby GPS receivers · 3 found` 或对应状态文案
  - **设备列表行**：每行显示 device 名 / Recommended 或 Unsupported 标签 / RSSI dBm + 4 格信号条 / 选中 radio
  - **状态机**：`scanning` / `found devices` / `empty` / `connecting` / `failed`（最小状态，`selectedDevice` / `failed reason` 在底层 API 暂不可得时按 guidance §BLE Scan Sheet 用最小状态实现，标注 future round 补）
  - **底部行动**：`CONNECT`（紫色主按钮，仅在有 `selectedDevice` 时启用）+ `SCAN AGAIN`（紫色文字按钮）
  - **Hint 文案**：`Choose a BLE GPS receiver for tests`
- `DeviceScanDialog` 不删除（同 transitional fallback 策略，future round 删除）
- `BleScanBottomSheet` 复用现有 `GpsDataViewModel.startScan()` / `stopScan()` / `connectDevice(device)` / `scanResults` / `isScanning`

### Capability 4：`cross-tab-device-gating`（Test/Laps 未 ready 引导到 Device · 仅 device readiness）

- 新建 `TabGatingPolicy`（`feature/test/.../ui/tracktech/TabGatingPolicy.kt`，约 30 行 + 单测），仅检查 4 项 device/data 条件：BLE connected / data fresh (`dataAge < 1000ms`) / satellites >= 6 / hdop > 0 && hdop < 2.0
- **明确边界**：TabGatingPolicy MUST NOT 检查 speed range / test template / acceleration-braking 起点条件（这些属于执行前 Smart Launch 范畴，不在首页 tab 入口）
- `TrackTechStatusStrip`（新组件）放在 Test/Laps 首页顶部，显示 `GPS ready · 25Hz · Good signal` 紧凑状态条
- StatusStrip 点击 → 切换到 Device tab（不论当前状态，提供"主动检查连接"路径）
- Test/Laps 主操作（Test 的 `0-100`/`100-0` 卡、Laps 的 `START LAP SESSION` 按钮）的 enabled 取决于 **新建** `TabGatingPolicy.computeTabReadiness(...).canEnterTestFlow`（**不**复用 `SmartTestLauncher.checkLaunchConditions` —— 后者包含 `speed_at_start` 条件，用户静止时点 `100-0` 会被错误导到 Device，详见 design.md D10/D14）
- **未 ready 时点击主操作**：不启动 → 弹 `Toast` 或 `Snackbar` 提示 + 自动切换到 Device tab + 自动展开 BLE Scan Sheet（如果 `connectionState = DISCONNECTED`）
- 已 ready 时主操作正常进入对应 nested screen（Test → Selection → Execution；Laps → 首页骨架的 Start Session 占位 callback，不实际启动 lap 流，留 future round）

### 视觉基础组件（同 change 内交付，落点 `feature/test/.../ui/tracktech/`）

- `TrackTechTheme`（color tokens + 三种字体角色：`RacingTitle` / `Metric` / `UiText`）
- `CutCornerPanel`（`GenericShape` + `Path`，可配置 cut size + 哪几个角被切）
- `TrackTechBottomNav` + `TrackTechBottomNavItem`
- `TrackTechStatusStrip`（含 status item: GPS ready / 25Hz / Good signal）
- `PrimaryActionPanel`（紫色渐变切角主操作面板，如 `0-100`）
- `SecondaryActionPanel`（红色描边切角次操作面板，如 `100-0`）
- `MetricNumber`（大号金属数字，先用 SansSerif Black 模拟 7 段数码）
- `MetricTile`（label + MetricNumber + unit 组合）
- `TrackTechRow`（Device Home 的 `GPS Details` / `Diagnostics` / `Settings` 入口行）

字体不引入 .ttf 文件（guidance §Typography Guidance "首版不要卡在最终字体上"），用系统 SansSerif + FontWeight + Italic 模拟三种字体角色。

## Capabilities

### New Capabilities

- `track-tech-app-shell`：4 tab persistent bottom nav + tab 间状态保持 + nested navigation 兼容现有 Test 流
- `device-home-connection-console`：Device tab 首页连接控制台（Readiness Hero / Quick Status Row / Connected Device / 三入口行）+ 三态状态映射
- `ble-scan-bottom-sheet`：Material3 ModalBottomSheet + 5 状态机（scanning/found/empty/connecting/failed）+ 选中 device + connect 行动
- `cross-tab-device-gating`：StatusStrip 主动入口 + 未 ready 拦截主操作 + 自动跳 Device + 自动展开 Scan Sheet

### Modified Capabilities

无。`openspec/specs/` 当前为空（OpenSpec 初始化后历次 round 的 capability 都通过 archive 沉淀，本仓库未沉淀到 specs 根目录），全部走 New Capabilities。

## Impact

### 受影响模块路径

**新增**：
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/` 新子包（视觉基础组件 + shell + 4 tab 首页 + Bottom Sheet）
  - `TrackTechTheme.kt`（color tokens + typography）
  - `CutCornerPanel.kt`
  - `TrackTechBottomNav.kt` + `TrackTechBottomNavItem.kt`
  - `TrackTechStatusStrip.kt`
  - `PrimaryActionPanel.kt` + `SecondaryActionPanel.kt`
  - `MetricNumber.kt` + `MetricTile.kt`
  - `TrackTechRow.kt`
  - `TrackTechAppShell.kt`（顶层 NavHost + Bottom Nav）
  - `TestHomeScreen.kt`（Test tab 首页：Speed Hero + 0-100/100-0 + Latest Result）
  - `LapsHomeScreen.kt`（Laps tab 首页骨架：Current Track + Track Preview + START LAP SESSION + RECENT BEST + NEARBY TRACKS 占位）
  - `RecordsHomeScreen.kt`（Records tab 首页骨架：Performance/Laps Segmented + Speed Curve 占位 + Recent Runs）
  - `DeviceHomeScreen.kt`（Device tab 连接控制台）
  - `BleScanBottomSheet.kt`
- `docs/design/track-tech-visual-tokens.md`（视觉 token 文档：色号/字体/形状/装饰/icon 清单）

**修改**：
- `app/src/main/java/com/blazepush/MainActivity.kt`（`TestFlowNavigation()` → `TrackTechAppShell()` 单行替换）
- 现有 `TestFlowNavigation.kt`：保留作为 Test tab 的 nested nav，但 startDestination 从 `Connection` 改为 `Selection`（Connection 路由仍保留作 transitional fallback）

**保持**（**不修改**）：
- `feature/test/.../viewmodel/GpsDataViewModel.kt`（数据流已完备，零改动）
- `feature/test/.../viewmodel/TestSessionViewModel.kt`（零改动）
- `feature/test/.../viewmodel/SmartTestLauncher.kt`（零改动，cross-tab gating 直接复用其 `checkLaunchConditions` API）
- `feature/test/.../ui/screen/DeviceConnectionScreen.kt`（保留作 transitional fallback，标注 deprecation 注释）
- `feature/test/.../ui/screen/DeviceScanDialog.kt`（同上）
- `feature/test/.../ui/screen/{TestSelectionScreen,TestExecutionScreen,TestResultScreen,TestHistoryScreen,LapDebug*}.kt`（零改动，只在 Test tab nested nav 内被调）
- `feature/test/.../ui/theme/{Color,Components,Theme,Type}.kt`（NeonTheme 保留作 fallback，新组件用 TrackTechTheme，两者并存）
- `core/bluetooth`、`core/data`、`core/domain`、`simulator`：零改动
- `feature/test/.../di/AppModule.kt`：零改动（`GpsDataViewModel` 已是 Application singleton）

### 协议兼容性

**N/A** —— 不涉及 RaceChrono BLE 协议字段 / GpsData 字段。

### 双端任务范围

**仅接收端（gps-app）** —— 不涉及发射端 simulator 改动。

### Compose Navigation 依赖引入

如果 `app/build.gradle.kts` / `feature/test/build.gradle.kts` 当前未引入 `androidx.navigation:navigation-compose`，本 change MUST 同步加 dependency（属于受影响模块的"必需"改动，不是 scope 扩张）。apply 阶段 §0 grep 预检确认。

## Non-goals（scope 硬边界）

- **不做 Records 完整图表**（Speed Curve / Acceleration Curve）—— 首页只放占位骨架
- **不做 Laps 赛道选择产品化体验**（Track preview map / Nearby tracks 实数据）—— 首页占位骨架，`START LAP SESSION` 主操作只到 callback 不实际启动 lap
- **不做 GPS Details 密集指标页** —— Device Home 入口行点击只跳 placeholder 子页或 Toast，子页延后到独立 round
- **不做 Diagnostics / Settings 子页** —— 同 GPS Details，入口行 placeholder
- **不做 Execution HUD 升级** —— Test 进入执行后仍走现有 `TestExecutionScreen` 等 Compose 屏，本 change 不动其视觉
- **不做字体文件最终化** —— 用系统 SansSerif + FontWeight/Italic 模拟，`res/font/` 不引入 .ttf 资产（guidance §Typography Guidance "首版不要卡在最终字体上"）
- **不做 icon 资产体系**（speedometer / brake / flag / signal bars / chevron）—— 用 Material Icons Extended 默认 vector icon 凑齐，自定义 icon SVG 化延后到独立 round
- **不删除现有 `DeviceConnectionScreen` / `DeviceScanDialog`** —— 保留作 transitional fallback，标注 deprecation 注释，future round 删除（避免本 change scope 失控）
- **不修改 `GpsDataViewModel` 等现有 ViewModel 接口** —— 数据层已完备，所有改动在 UI 层
- **不修改 `core/*` 模块** —— 所有改动局限于 `feature/test/.../ui/tracktech/` + `app/.../MainActivity.kt`
- **不引入 1:1 像素级复刻**（guidance §Important Boundary 明确 "本次实现目标不是像素级复刻"）—— 视觉接近 V2 参考图即可，不卡在 hex 微调
- **A35 不在本 round** —— 用户明确推迟（不在当前 UI 阶段 scope）

## 验收门槛（进入 `/opsx:apply` 前）

- `openspec validate add-track-tech-app-shell --strict` 通过
- proposal / design / 4 个 spec / tasks 5 件套结构合规（Requirement 含 MUST/SHALL/SHOULD + 至少 1 个 Scenario，Gherkin 关键字英文 GIVEN/WHEN/THEN）
- 视觉 token 文档 `docs/design/track-tech-visual-tokens.md` 完整覆盖：色号 swatch / 字体角色 TextStyle / CutCornerPanel GenericShape Path / 装饰图形 Canvas 方案 / Material Icons 清单 / 资产边界
- 提供 5 个待用户拍板的具体决策（已在对话内沉淀）：change 命名 / 模块归属 / 导航实现 / Track Tech 组件落点 / OpenSpec 流程

### apply 阶段（review 通过后）独立验收门槛

- `:feature:test:compileDebugKotlin` + `:app:compileDebugKotlin` BUILD SUCCESSFUL
- `:feature:test:testDebugUnitTest` 全绿（现有测试零回归 —— ViewModel 接口零改动，数据层零改动）
- E2E 契约 `*EndToEndLapTimingContractTest*` 全绿（不涉及 UI shell，应天然零回归）
- 真机截图 5 张（Test / Laps / Records / Device / BLE Scan Sheet），与 V2 参考图视觉强度对比
- grep 自检：`TrackTechAppShell` 在 `MainActivity.kt` 命中 + `TestFlowNavigation()` 仍是 Test tab nested 入口（不删除）

## 基线

commit `fcc61cc`（Round 4 闭环：A17 + A30 DI fallback 真机异常传播 + 删除 anomaly 孤岛）。
