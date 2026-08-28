## ADDED Requirements

### Requirement: Live Lap Session 屏强制横屏 + 屏幕常亮

`LapLiveScreen` Composable MUST 在进入时强制 Activity 进入 landscape orientation，离开时恢复原 orientation。

`LapLiveScreen` MUST 通过 Compose side-effect（推荐 `DisposableEffect`）实现：

- 进入时：`LocalContext.current as? Activity` 拿到 host Activity，设 `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE`
- 离开时：`onDispose` 中恢复 `requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED`（或保存的原值）

`LapLiveScreen` MUST 在进入时启用屏幕常亮（`FLAG_KEEP_SCREEN_ON` 或等价 `Modifier.keepScreenOn()`），离开时释放。

#### Scenario: 进入 LapLiveScreen 锁定横屏

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** 阅读 Composable body
- **THEN** 含 `DisposableEffect(...)` 调用
- **AND** body 含 `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE` 或等价表达式

#### Scenario: 离开 LapLiveScreen 恢复 orientation

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** 阅读 `DisposableEffect` 的 `onDispose { ... }` block
- **THEN** body 含 `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED` 或恢复原值的逻辑

#### Scenario: 屏幕常亮启用

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `FLAG_KEEP_SCREEN_ON` 或 `keepScreenOn`
- **THEN** 至少一处命中（用于启用屏幕常亮的 side-effect）

### Requirement: Live 屏不显示 bottom tab bar 且独立 NavHost 路由

`LapLiveScreen` MUST 是顶层 NavHost 的独立路由 `lap_live`（与 `test_execution` / `gps_details` 平级，**不**在 home Pager 内）。

`TrackTechAppShell` 的 `showBottomNav` 判定保持 `currentRoute == "home"`（上一 round 落地），副作用是 `lap_live` 路由下 bottom nav 自动隐藏。

#### Scenario: TrackTechAppShell NavHost 含 lap_live 路由

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 NavHost block
- **THEN** 含 `composable("lap_live") { ... }` 一处

#### Scenario: lap_live 路由下 bottom nav 不可见

- **GIVEN** 用户从 Laps 首页 START LAP SESSION 进入 lap_live
- **WHEN** Shell recomposition 后渲染 Scaffold
- **THEN** bottom nav 不可见（`currentRoute == "lap_live"` ≠ `"home"`，`showBottomNav = false`）

### Requirement: 返回手势/返回键拦截

`LapLiveScreen` MUST 用 `androidx.activity.compose.BackHandler` 拦截返回手势/返回键，**不直接退出 session**：

- BackHandler 触发时显示结束确认 AlertDialog（`Continue` / `End Session` 两个选项）
- 用户选 `Continue` → dismiss dialog，session 继续
- 用户选 `End Session` → 走标准结束流程（同 HOLD TO END）

#### Scenario: BackHandler 拦截返回手势

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `BackHandler` 或 `androidx.activity.compose.BackHandler`
- **THEN** 至少一处命中
- **AND** BackHandler lambda body 含触发结束确认对话框的逻辑（如 `showEndConfirmation = true`）

#### Scenario: 结束确认对话框含两个选项

- **GIVEN** 实施后 `LapLiveScreen.kt` 内结束确认 Composable
- **WHEN** 阅读对话框 button 定义
- **THEN** 含 `Continue` 或等价语义按钮（dismiss 对话框）
- **AND** 含 `End Session` 或等价语义按钮（触发结束）

### Requirement: 2x2 dashboard 五字段布局

`LapLiveScreen` 主体 MUST 用 2x2 dashboard 展示 4 个 metric tile：`Delta to best` / `Current lap` / `Last lap` / `Best lap`，加顶部 strip 含 `Lap N` 小 badge。

视觉优先级（从大到小）：

1. `Delta to best`
2. `Current lap`
3. `Last lap`
4. `Best lap`
5. `Lap number`（顶部小 badge）

`Current lap` MUST NOT 独占视觉中心（不像传统秒表 hero）。

#### Scenario: 5 个 metric 字段全部展示

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep 字段 label 字面量
- **THEN** 命中 `DELTA` / `CURRENT` / `LAST` / `BEST` 四个字面量（或对应中英文等价表达）
- **AND** 命中 `LAP` 字面量（lap number badge）

#### Scenario: 2x2 网格布局

- **GIVEN** 实施后 `LapLiveScreen.kt` 内 dashboard 区域
- **WHEN** 阅读布局结构
- **THEN** 含两个 `Row(...)` 各包含两个 metric tile（共 4 个 tile）；或等价 2x2 grid 实现

### Requirement: HOLD TO END 长按结束

`LapLiveScreen` 底部 MUST 有 `HOLD TO END` 长按按钮：

- 视觉：红色 outline（`TrackTechColors.Red`），底部位置
- 长按时长：1.5 秒（`HOLD_DURATION_MS = 1500L`）
- 长按过程显示 progress bar（按钮内填充进度）
- 用户中途松手：progress 重置为 0，**不**触发结束
- 长按完成 1.5s：触发 `onEnd` 调 `sessionViewModel.finishActiveLapSession()`（D12 public suspend API），await 返回 `LapSessionSaveResult` 后用 `lapCount` / `sessionId` 驱动 Snackbar 与 `View Record` 路由（详见 `lap-session-detail-screen` capability 的 Snackbar Requirement）

#### Scenario: HOLD TO END button 视觉契约

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `HOLD TO END` 字面量
- **THEN** 至少一处命中
- **AND** 该 button 视觉用 `TrackTechColors.Red`（red outline / red text 任一）

#### Scenario: 长按时长常量

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `1500L\|1500.dp\|HOLD_DURATION_MS`
- **THEN** 含 1500ms 或等价定义（值 = 1.5 秒）

#### Scenario: 中途松手不触发结束

- **GIVEN** 实施后 `LapLiveScreen.kt` 内 HOLD TO END 实现
- **WHEN** 阅读 long-press progress 逻辑
- **THEN** 含中途取消（`onPress` 中检查持续时间 < HOLD_DURATION_MS 时跳过结束）的判断

### Requirement: Live 屏 MUST NOT 展示项

`LapLiveScreen` MUST NOT 展示以下信息（违反"驾驶仪表"原则）：

- 车辆速度（speed / km/h hero）
- GPS details（fix quality / hdop / accuracy 等）
- Satellite count
- Hz / RATE / 频率
- Telemetry chart / speed curve
- Lap records list / 历史圈速列表
- Sector matrix / S1/S2/S3
- Track map / 轨迹图

异常状态（如 GPS lost）可以打断主页面但 MUST 用短句明确信息（见下条 Requirement）。

#### Scenario: Live 屏不展示 speed / GPS / chart 等

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `gpsData.speed\|km/h\|hdop\|satelliteCount\|frequency\|SpeedCurve\|TrackMap` 等
- **THEN** 这些字面量在 LapLiveScreen 中**不**作为正常状态下的可见字段渲染（异常 banner 内提到 GPS 等情境化文本可接受）

### Requirement: 异常状态打断

`LapLiveScreen` MUST 在以下异常状态下打断主页面，用短句明确信息：

| 状态 | 文本 |
|---|---|
| BLE 未连接 | `BLE DISCONNECTED` |
| GPS 数据陈旧 | `GPS SIGNAL LOST` |
| GPS 未 fix | `WAITING FOR GPS LOCK` |
| 当前圈被判 INVALID | `LAP INVALIDATED` |

异常状态优先级高于正常 dashboard，覆盖全屏（或主体区域）显示，用红色 / 黄色高亮。

#### Scenario: 异常状态文案

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep `GPS SIGNAL LOST` / `WAITING FOR GPS LOCK` / `BLE DISCONNECTED` / `LAP INVALIDATED`
- **THEN** 4 个文案字面量全部命中

#### Scenario: 异常状态优先级派生

- **GIVEN** 实施后 `LapLiveScreen.kt` 内 abnormalState 分支
- **WHEN** 阅读 `when (lapLiveState.abnormalState) { ... }` 或等价分支
- **THEN** 含对 `AbnormalState` 4 个枚举的覆盖
- **AND** 异常状态非 null 时 dashboard 区域被替换为异常 banner（不双显）

### Requirement: 不引入 LapLiveScreenTest（ComposeRule UI test）

本 round MUST NOT 引入 ComposeRule UI test（沿用前几 round 决策）。Live 屏的运行时行为（横屏锁定 / 屏幕常亮 / 返回拦截 / HOLD TO END 长按）由真机 manual gate 验证。

#### Scenario: 不新增 ComposeRule 测试

- **GIVEN** 实施后 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/` 子包
- **WHEN** 找新增的 `LapLiveScreenTest.kt` 文件
- **THEN** 该文件**不**应存在
- **AND** `feature/test/build.gradle.kts` 不引入 `androidx.compose.ui:ui-test-junit4` 依赖
