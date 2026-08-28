## Why

当前 Laps tab 已有首页骨架（ V2 视觉 / Current Track 卡片 / `START LAP SESSION` 占位 Toast），但**圈速 session 完整闭环不存在**：

- `START LAP SESSION` 只弹 Toast，不真正进入 live session
- baseline 内有 `TestSessionViewModel.selectLapDebugMode` + A56 `TelemetryRepository.startSession(LAP_SESSION)` 数据持久化全套，但**缺 live 实时仪表 UI 屏**
- baseline `LapDebugExecutionScreen.kt` 是旧 V1 流程的执行屏，竖屏 + Compose-scope 绑定 recorder + 信息密度过高（speed / GPS / lap list 全堆），不符合"驾驶仪表"原则
- Records tab `LapsView` 的 SESSION HISTORY 列表点击只 Toast 占位，**缺 session detail 屏**
- baseline 没有"keep screen on"机制，驾驶时屏幕会黑
- baseline 没有强制横屏锁定 + 返回手势拦截

PRD 已落档（`docs/product/lap-session-phase1-requirements.md`），明确要求一期落地 `Laps home → track selection → live lap session → end and save → Records/Laps session detail` 闭环，且**不提前承诺** sector / theoretical best / chart / map / video。

修复时机：上一 round `differentiate-metric-typography-mechanical-vs-score` 已合并，V2 视觉系统稳定（Mechanical 七段 / Score Italic Bold / 单行约束），本 round 在视觉系统之上落"驾驶仪表" UI + recorder 生命周期改造。

## Change 依赖

本 round **依赖** `enhance-track-presentation`（已 review 放行）先 apply。后者已建立的契约本 round MUST 复用、**不重做、不回退**：

- `TestSessionViewModel.currentSelectedTrack: StateFlow<Track?>` + `selectTrack(track: Track)` API
- `SelectTrackBottomSheet` Composable（`CHANGE TRACK` 按钮已接通真实切换流程）
- `TrackThumbnail` Composable（缩略图复用组件）
- `LapsHomeScreen.CurrentTrackPanel` 已消费 `currentSelectedTrack` 状态（trackName / length / 缩略图）
- `RecordsHomeScreen.placeholderTrackRecord` 已改为消费 `currentSelectedTrack` 派生（trackName / length 不再 hardcoded）
- `CurrentTrackRecordCard` 签名扩展为 `(track: Track?, record: CurrentTrackRecord)`，`TrackPreviewStub` 已删除

本 round 仅在 `LapsHomeScreen.START LAP SESSION` onClick 内**读取** `currentSelectedTrack` 拿 trackId 调 `selectLapDebugMode`，不再造平行的"当前赛道"真相源。`CHANGE TRACK` 按钮 onClick 行为本 round 完全不动。

## What Changes

### 新增能力

- **Live Lap Session Screen**（`feature/test/.../ui/tracktech/LapLiveScreen.kt`）：
  - 强制横屏（landscape orientation）
  - `FLAG_KEEP_SCREEN_ON` 保持屏幕常亮
  - 不显示 bottom tab bar（独立 NavHost route，不在 home Pager 内）
  - 拦截返回手势/返回键 → 触发结束确认 / `HOLD TO END` 提示，**不直接退出**
  - 布局：Top strip（LAPS · track name · LAP n · tiny Ready）+ 2x2 dashboard（Delta / Current / Last / Best）+ Bottom（HOLD TO END）
  - 视觉优先级：Delta > Current > Last > Best > Lap
  - **MUST NOT 展示**：speed / GPS details / satellite count / HDOP / 25Hz / chart / lap list / sector / track map
  - 异常状态可打断：`GPS SIGNAL LOST` / `WAITING FOR GPS LOCK` / `BLE DISCONNECTED` / `LAP INVALIDATED`
  - HOLD TO END：长按触发（design D4 拍时长），红色 outline，底部位置

- **Recorder Lifecycle 架构改造**（design D2 拍方案）：
  - Recorder/session controller 持有 session 状态，**不绑 Composable scope**
  - 配置变化、横竖屏重建、退后台 MUST NOT 直接停止 active recording
  - 用户明确结束 session 才停止 recorder
  - Activity/Service 真销毁时 cleanup 或标记异常结束
  - 一期最小可行方案（design 拍板）：Application-scoped 单例 / Activity-scoped ViewModel / Service

- **Lap Live State 派生**（live UI 数据源）：
  - `currentLapTimerMs: Long`（从最近 start/finish 过线开始毫秒计时；未开始有效圈时 null → UI `--:--.---`）
  - `lastLapTimeMs: Long?`（上一圈完成时间，未完成时 null）
  - `bestLapTimeMs: Long?`（session 内最佳圈，未完成第一圈时 null）
  - `deltaToBestMs: Long?`（current vs best；正负 + 颜色 green/cyan vs red；无 best 时 null → `--`）
  - `currentLapNumber: Int`（>= 1）
  - 派生位置 design D3 拍板（TestSessionViewModel 扩展 vs 新 LapLiveViewModel）

- **End Session 流程**：
  - HOLD TO END → `TelemetryRepository.endSession()` 写 Room endTs
  - 显示轻量保存反馈（Toast / Snackbar）
  - 默认回到 Laps 首页或 Records/Laps 列表（design 拍板）
  - 提供 `View Record` 进入 session detail
  - **不强制跳转传统结果页**

- **Records/Laps Session Detail Screen**（`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`）：
  - 路由独立（如 `lap_session_detail/{sessionId}`）
  - 入口 1：Records tab → LAPS 视图 → SESSION HISTORY 列表行点击
  - 入口 2：Save feedback `View Record` 跳转
  - **一期只做 Overview，不做 tabs**
  - 必须展示：Track name / Session date·time / Best lap / Total laps / Valid laps / Invalid laps / Lap records list
  - 可选展示（数据可靠时，design D8 拍板）：Top speed / Duration / Distance
  - Lap records list 列：Lap / Time / Diff / Status（BEST / VALID / INVALID / INCOMPLETE）
  - INVALID 圈展示原因（GPS LOST / PIT / MANUAL END，如已有数据）
  - **MUST NOT 展示**：theoretical best / S1/S2/S3 / sector matrix / chart tab / map tab / video tab / lap-vs-lap comparison

- **Track Selection 改造**（Laps 首页 → live session）：
  - `LapsHomeScreen` 的 `START LAP SESSION` 主操作从占位 Toast 改为真正进入 live session
  - 一期只有 Chengdu Tianfu 一条赛道（PresetTrackCatalog 已有），design D6 拍板：是否需要 selector 屏（如有多赛道扩展空间）vs 直接进入 live
  - 设备/GPS 未 ready → 走全局 gating（已落 capability `cross-tab-device-gating`，不变更）

### 修改能力

- **`track-tech-app-shell`**（修改 modified）：home Pager 路由集合不变，但顶层 NavHost 增加 `lap_live` + `lap_session_detail/{sessionId}` 两个独立子路由（与 `test_execution` / `gps_details` 平级）；进入 `lap_live` 时 bottom nav 隐藏（已是 `currentRoute == "home"` 判定的副作用）

### 不做（MUST NOT）

- sector timing / theoretical best / S1/S2/S3 / sector matrix
- chart tab / map tab / video tab / lap-vs-lap comparison
- live session 内展示 speed / GPS details / satellite count / HDOP / 25Hz
- 强制跳转传统 `LapDebugResultScreen` 结果页
- live session 返回手势直接退出
- recorder 绑 Composable scope
- 像素级复刻视觉参考图（按 CC guidance：大结构 / 页面职责 / 交互路径 / 信息层级正确即可）
- 引入 Foreground Service（一期最小可行方案，design D2 拍板用更轻路径）
- baseline `LapDebugExecutionScreen.kt` 删除（保留作 transitional fallback）

## Capabilities

### New Capabilities

- `lap-live-session-screen`：Live lap session 实时仪表屏的契约 —— 强制横屏 / Keep screen on / 返回手势拦截 / 2x2 dashboard 布局 / 5 个 metric 字段语义 / 异常状态打断 / HOLD TO END
- `lap-session-recorder-lifecycle`：recorder 生命周期与 owner 契约 —— 不绑 Composable scope / 配置变化不停 / 退后台不停 / 用户明确结束才停 / Activity 真销毁的 cleanup 路径
- `lap-live-state-derivation`：从 `LapSession.crossingEvents`（Room CrossingEvent）派生 live state（current/last/best/delta/lap number）的纯函数契约
- `lap-session-detail-screen`：Records/Laps session detail Overview 屏契约 —— 显示字段清单 / lap records 列结构 / 状态分类 / MUST NOT 展示项

### Modified Capabilities

- `track-tech-app-shell`：顶层 NavHost 加 `lap_live` + `lap_session_detail/{sessionId}` 两个子路由；不动 home Pager 4 tab 结构

## Impact

### 受影响代码

**新增**：
- `feature/test/.../ui/tracktech/LapLiveScreen.kt`（约 ~400 行）
- `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`（约 ~250 行）
- `feature/test/.../viewmodel/LapLiveViewModel.kt` 或扩展 `TestSessionViewModel`（design D3 拍板）
- `feature/test/.../usecase/LapLiveStateDeriver.kt`（纯函数派生 current/last/best/delta，可单元测试）
- 单元测试：`LapLiveStateDeriverTest`（关键场景：第 1 圈 / 第 N 圈 / 无参考圈 / current 中途 / 跨圈过线即时刷新 / INVALID 圈跳过）

**修改**：
- `feature/test/.../ui/tracktech/TrackTechAppShell.kt`：NavHost 加 `lap_live` + `lap_session_detail/{sessionId}` 两个 `composable(...)` 路由 + Scaffold 加 SnackbarHost + Shell scope collect `LapSessionSaveBus`
- `feature/test/.../ui/tracktech/LapsHomeScreen.kt`：`START LAP SESSION` 主操作改为真进入 live（gating 已 ready 时）
- `feature/test/.../ui/tracktech/RecordsHomeScreen.kt`：LAPS 视图 SESSION HISTORY 列表行点击 → navigate `lap_session_detail/{sessionId}`；`placeholderLapSessions` 改为 `getRecentLapSessions` 真数据
- `feature/test/.../viewmodel/TestSessionViewModel.kt`：新增 public suspend `finishActiveLapSession(): LapSessionSaveResult?` + `lapLiveState: StateFlow<LapLiveState>` + `LapSessionSaveResult` data class
- **`core/data/.../repository/TelemetryRepository.kt`：新增 public suspend API `getCrossings(sessionId): List<TelemetryCrossingEvent>` + `getRecentLapSessions(limit: Int = 10): List<TelemetrySession>`**（D13）
- `app/src/main/AndroidManifest.xml`（如需声明 LapLiveScreen 所在 Activity 的 orientation `landscape`，但本 round 用 Compose 内 side-effect 锁定，不改 manifest —— design D1 拍板）
- `feature/test/build.gradle.kts`（不引入新依赖；如需 Service 才改，design D2 拍板）

### 不受影响

- `core/bluetooth/*` / `core/domain/*`（仅 `core/data` 内 `TelemetryRepository.kt` 加 2 个 public API，其余 core 模块不动）
- `simulator/*` 全部模块
- baseline `LapDebugExecutionScreen.kt`（旧 V1 流程，保留作 transitional fallback）
- BLE / GPS 数据链路、RaceChrono BLE 协议
- V2 typography 系统（Mechanical / Score / 单行约束）
- DAO 层（`TelemetrySessionDao` / `CrossingEventDao` 接口零改动；新 API 仅在 Repository 包装层）
- Room schema / Entity 字段（不动 `TelemetrySessionEntity` / `CrossingEventEntity`）
- 其他 home screen（Test / Device 内容不动；仅 Records LapsView SESSION HISTORY 数据源 + Laps START LAP SESSION onClick 改）

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 依赖

无新依赖。横屏锁定 / Keep screen on 用 Compose side-effect + Android `View.keepScreenOn` 或 `Activity.requestedOrientation`，已是 Compose / Android 标准 API。

### 测试影响

- 新增：`LapLiveStateDeriverTest`（纯函数派生测试，关键场景全覆盖）
- 现有套件 MUST 零回归（含 `TrackTechAppShellPagerTest` / `TabGatingPolicyTest` / `TestSessionViewModelTrackLapTest` 等）
- 不引入 ComposeRule UI test（沿用前几 round 决策）
- 真机验证：圈速完整闭环（开始 / 跑圈 / 异常状态 / HOLD TO END / 保存反馈 / View Record）；横屏锁定 / 返回手势拦截 / 屏幕常亮单独验证
