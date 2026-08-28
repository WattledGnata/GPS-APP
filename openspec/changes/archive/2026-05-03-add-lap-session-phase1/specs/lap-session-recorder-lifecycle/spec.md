## ADDED Requirements

### Requirement: Recorder/session controller 不绑 Composable scope

`TestSessionViewModel` 的 lap session 状态（`activeLapSessionId` / `activeLapStartSystemTs` / `lastWrittenCrossingCount` 等）+ `TelemetryRepository` 的 `activeWriter` MUST NOT 绑到任何 Composable 的 `LaunchedEffect` / `DisposableEffect` / `remember` scope。

session 状态 MUST 持有于 ViewModel（已是上 round 落地的 Activity-scoped `koinViewModel<TestSessionViewModel>()` 在 `TrackTechAppShell` 顶层创建）+ `TelemetryRepository` 单例（Koin `single`）。

`LapLiveScreen` 内 `LaunchedEffect` / `DisposableEffect` MUST 仅触发 UI 副作用（横屏锁定 / 屏幕常亮 / 进度条动画），**不**承担 session 数据状态持有。

#### Scenario: LapLiveScreen 不持有 session 状态

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** grep 该文件内的 `LaunchedEffect(...) { ... }` 与 `DisposableEffect(...) { ... }` block
- **THEN** 这些 block 内**不**含 `TelemetryRepository.startSession` / `writeSample` / `writeCrossing` / `endSession` 等 session 状态修改调用
- **AND** session 修改入口仅可通过 `sessionViewModel.<...>` 间接调用（如 `sessionViewModel.endActiveLapSession()`）

#### Scenario: TestSessionViewModel session 状态字段保留 ViewModel-scope

- **GIVEN** 实施前后 `TestSessionViewModel.kt`
- **WHEN** 阅读 `activeLapSessionId` / `activeLapStartSystemTs` / `lastWrittenCrossingCount` 三个字段定义
- **THEN** 它们仍是 ViewModel 的 `private var` / 等价持有
- **AND** 没有引入 Composable scope 的 `remember` 或 `mutableStateOf` 持有这些 session ID 状态

### Requirement: 配置变化 / 横竖屏重建 / 退后台不停 active recording

Activity configuration changes（横竖屏 / 多窗口 / 字号变化）期间，`TelemetryRepository.activeWriter` MUST 持续运行，不被销毁；`activeLapSessionId` MUST 保留。

App 进入后台（onPause / onStop / 锁屏 / 切换 App）期间，writer 与 session ID 同样 MUST 持续运行。

仅以下情况停止 active recording：

- 用户明确 HOLD TO END / 返回手势确认 → 调 public suspend `finishActiveLapSession(): LapSessionSaveResult?`（D12，await `TelemetryRepository.endSession()`，返回 sessionId/lapCount/bestLapMs/totalDurationMs 给 Snackbar）

baseline private `endActiveLapSession()`（fire-and-forget Unit）MAY 保留给 `stopLapDebugSession` / `exitLapDebugMode` 等内部 caller 使用，**不**作为 LapLiveScreen HOLD TO END / EndConfirmationDialog 的终止入口。

Activity 真销毁（`onDestroy` 真触发，非 config-change 重建）的 abnormal cleanup 路径**一期不引入**（参见后续 Requirement "Activity 真销毁场景明确为 follow-up"）。

#### Scenario: TestSessionViewModel ActivityViewModel scope 保留

- **GIVEN** 实施后 `TrackTechAppShell.kt` 源码
- **WHEN** 阅读 `koinViewModel<TestSessionViewModel>()` 调用位置
- **THEN** 调用位于 `TrackTechAppShell()` Composable 顶层（`MainActivity.setContent {}` 内的 LocalViewModelStoreOwner = Activity scope）
- **AND** ViewModel 实例通过参数传给 `LapLiveScreen` / 其他 home screen，**不**在每个 NavBackStackEntry 内单独 `koinViewModel()`（避免 NavBackStackEntry-scoped 实例丢失 session）

#### Scenario: TelemetryRepository 单例

- **GIVEN** 实施前后 `feature/test/.../di/AppModule.kt` 内 `TelemetryRepository` 注册
- **WHEN** 阅读 Koin 注册声明
- **THEN** 注册形式为 `single { TelemetryRepository(...) }`（Application-scoped 单例，与 `GpsDataViewModel` 同等级）

### Requirement: Activity 真销毁场景明确为 follow-up，非一期 scope

本 round MUST NOT 实现 Activity destroy / `onCleared` 触发的 abnormal-cleanup 路径。

**理由**：
- 当前 `endActiveLapSession()` 在 `viewModelScope.launch { ... }` 内异步执行；放进 `ViewModel.onCleared()` 时 scope 正在被取消，await 不可靠
- 真销毁场景需要：(1) 暴露 abnormal endTs 标记 API（如 `endSession(sessionId, isAbnormal: Boolean)`）+ (2) 在 lifecycle 边界用 `GlobalScope` / `Application`-scoped scope await + (3) UI 提示用户上次 session 异常结束
- 这三件事属于完整 Foreground Service / SavedStateHandle 持久化方向，与一期"最小可行"原则冲突
- 配置变化 / 退后台 / 锁屏的常见场景已由 Activity-scoped ViewModel 兜底（前述 Requirement 已覆盖），系统极端杀进程 case 一期接受 binary file flush 已发生 + Room metadata 部分一致即可

**记入 follow-up backlog**：完整 Activity destroy cleanup（含 abnormal endTs 标记 + UI "上次 session 异常结束" 提示）作为独立 round 处理。

#### Scenario: 一期不引入 onCleared cleanup

- **GIVEN** 实施后 `feature/test/.../viewmodel/TestSessionViewModel.kt`
- **WHEN** 阅读 `onCleared()` 重写
- **THEN** **不**含调用 `endActiveLapSession` / `finishActiveLapSession` 等 lifecycle cleanup 逻辑（可有空实现 / 不重写均可）

#### Scenario: 一期不引入 MainActivity.onDestroy 钩子

- **GIVEN** 实施前后 `app/src/main/java/com/blazepush/MainActivity.kt`
- **WHEN** `git diff` 该文件 lifecycle override
- **THEN** 不引入 `override fun onDestroy()` 内对 lap session 的 cleanup 调用

### Requirement: TestSessionViewModel 暴露 public suspend finishActiveLapSession

`TestSessionViewModel` MUST 新增 public suspend 方法 `finishActiveLapSession(): LapSessionSaveResult?` 与配套 data class：

```kotlin
data class LapSessionSaveResult(
    val sessionId: String,
    val lapCount: Int,        // 完成的 accepted lap completion 数
    val bestLapMs: Long?,     // 最佳圈毫秒；无则 null
    val totalDurationMs: Long, // session 总时长毫秒
)

suspend fun finishActiveLapSession(): LapSessionSaveResult?
```

实现 MUST 满足：

1. **先捕获**派生数据（清状态前）：sessionId / lapCount / bestLapMs / totalDurationMs，从 `_lapSession.value` 读
2. **await** `telemetryRepository.endSession(sessionId)` 完成（不是 fire-and-forget）
3. **后清** ViewModel 状态：`activeLapSessionId = null` / `activeLapStartSystemTs = null` / `lastWrittenCrossingCount = 0` / `isLapRecording = false` / `_lapSession.value = ?.copy(status = Finished)`
4. 返回 `LapSessionSaveResult`；`activeLapSessionId == null` 时返回 `null`

baseline private `endActiveLapSession()` MAY 保留作为 fire-and-forget 内部清理（供 `stopLapDebugSession` / `exitLapDebugMode` 等其他 caller 使用，行为不变）。

`LapLiveScreen` HOLD TO END 完成 / EndConfirmationDialog 选 End Session 时 MUST 调 `finishActiveLapSession()` 等待返回，把 `LapSessionSaveResult.lapCount` / `sessionId` 喂给 Snackbar。

#### Scenario: finishActiveLapSession public suspend 签名

- **GIVEN** 实施后 `TestSessionViewModel.kt` 源码
- **WHEN** grep `suspend fun finishActiveLapSession`
- **THEN** 命中函数定义
- **AND** 函数访问修饰符为 `public`（默认或显式）
- **AND** 返回类型 `LapSessionSaveResult?`

#### Scenario: LapSessionSaveResult data class 4 字段

- **GIVEN** 实施后源码
- **WHEN** grep `data class LapSessionSaveResult`
- **THEN** 命中定义
- **AND** 含 4 个字段：`sessionId: String` / `lapCount: Int` / `bestLapMs: Long?` / `totalDurationMs: Long`

#### Scenario: 先捕获再 await endSession

- **GIVEN** 实施后 `finishActiveLapSession` 实现
- **WHEN** 阅读函数 body
- **THEN** sessionId / lapCount / bestLapMs / totalDurationMs 的赋值在调 `telemetryRepository.endSession(...)` **之前**
- **AND** 状态清理（`activeLapSessionId = null` 等）在调 `endSession` **之后**

#### Scenario: LapLiveScreen 通过 finishActiveLapSession 拿 result

- **GIVEN** 实施后 `LapLiveScreen.kt` 源码
- **WHEN** 阅读 HOLD TO END 完成后的 onEnd 逻辑
- **THEN** 含调用 `sessionViewModel.finishActiveLapSession()` 的语句
- **AND** 含使用返回 `LapSessionSaveResult` 的 `lapCount` / `sessionId` 字段（用于 Snackbar 文案 / View Record nav）

### Requirement: 一期不引入 Foreground Service

本 round MUST NOT：

- 新建 `androidx.lifecycle.LifecycleService` 或 `Service` 子类
- 新建 `androidx.media.session.MediaSessionService` 或类似前台服务
- 在 `AndroidManifest.xml` 加 `<service>` 标签
- 申请 `FOREGROUND_SERVICE` 权限

Foreground Service 兜底属于 future round（用户明确"一期不强求"）。

#### Scenario: AndroidManifest 不加 service

- **GIVEN** 实施前后 `app/src/main/AndroidManifest.xml`
- **WHEN** `git diff` 该文件
- **THEN** 零行改动（未新增 `<service>` 节点 / `<uses-permission>` FOREGROUND_SERVICE）

#### Scenario: 不引入 Service 子类

- **GIVEN** 实施后 `app/` + `feature/` + `core/` 全部 `.kt` 文件
- **WHEN** grep `: LifecycleService\|: Service()\|MediaSessionService`
- **THEN** 零命中（baseline 已有 `BluetoothDataSource` 等连接类不算 Service）
