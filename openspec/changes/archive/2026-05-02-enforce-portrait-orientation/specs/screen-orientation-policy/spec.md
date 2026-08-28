## ADDED Requirements

### Requirement: AndroidManifest 必须将 MainActivity 锁定为 portrait

`app/src/main/AndroidManifest.xml` 内 `<activity android:name=".MainActivity">` 节 MUST 含属性：

```xml
android:screenOrientation="portrait"
```

理由：MainActivity 是 V2 Track Tech 全应用的唯一 Activity，所有 Compose Navigation routes 都属于它。manifest 锁 `portrait` 是单一 source-of-truth，新加的 route 自动继承 portrait 默认值。

#### Scenario: app 启动后默认竖屏

- **WHEN** 用户冷启动 app（`MainActivity.onCreate`）
- **THEN** Activity `requestedOrientation` 解析为 `SCREEN_ORIENTATION_PORTRAIT`（manifest 声明）
- **AND** 用户旋转设备到横屏 → 屏幕不跟随旋转

#### Scenario: 旋转设备时除 lap_live 外所有页面都不旋转

- **WHEN** 用户在 home / test_execution / gps_details / lap_session_detail / performance_result 任一页面旋转设备到横屏方向
- **THEN** 屏幕保持竖屏，layout 不被强迫适配

### Requirement: lap_live 圈速实时屏必须在进入时切横屏，离开时恢复竖屏

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` 内 MUST 保留现有 `DisposableEffect`：

```kotlin
DisposableEffect(Unit) {
    val activity = context.findActivity()
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    view.keepScreenOn = true
    onDispose {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = false
    }
}
```

理由：

1. `SCREEN_ORIENTATION_LANDSCAPE` 临时覆盖 manifest 默认 portrait，是标准 Android Activity API（兼容）
2. `onDispose` 内显式 `SCREEN_ORIENTATION_PORTRAIT` 跟 manifest 默认 portrait 行为一致，但**显式调用是双重保险**——若 manifest 未来被人误改回 unspecified，LapLiveScreen 离开仍能恢复竖屏
3. `keepScreenOn` 是圈速实时屏专属逻辑，跟 orientation 无关，不动

本 round MUST NOT 修改这段 DisposableEffect 的任何行（CLAUDE.md "Don't add ... refactor surrounding code while completing the requested task"）。

#### Scenario: 进入 lap_live 自动切横屏

- **WHEN** 用户从 LapsHomeScreen 跳转到 lap_live route（如点 START LAP SESSION）
- **THEN** Activity `requestedOrientation` 设为 `SCREEN_ORIENTATION_LANDSCAPE`
- **AND** 屏幕旋转到横屏方向
- **AND** `keepScreenOn = true` 防灭屏

#### Scenario: 退出 lap_live 自动恢复竖屏

- **WHEN** 用户在 lap_live 内 BackHandler 触发 + EndConfirmationDialog 点 End Session（或 popBackStack 任何途径离开）
- **THEN** Activity `requestedOrientation` 设回 `SCREEN_ORIENTATION_PORTRAIT`
- **AND** 屏幕旋转回竖屏方向
- **AND** `keepScreenOn = false` 恢复正常灭屏行为

#### Scenario: lap_live 内旋转设备保持横屏

- **WHEN** 用户在 lap_live 内物理旋转设备
- **THEN** 屏幕保持横屏方向（`SCREEN_ORIENTATION_LANDSCAPE` 不允许跟随 reverse-landscape 动态翻转）

### Requirement: contract test 必须验证 manifest 配置 + LapLiveScreen orientation 字面量都还在

本 round MUST 新增 2 个 grep 风格 contract test（不依赖 Robolectric / Compose runtime / Android Context），文件级 grep 验证关键字面量未被回退或删除：

#### Scenario: MainActivity manifest 配置防回退

`app/src/test/java/com/blazepush/MainActivityOrientationContractTest.kt` 读 `app/src/main/AndroidManifest.xml` 文本验证：

- **WHEN** 跑 `:app:testDebugUnitTest`
- **THEN** AndroidManifest.xml 文本 MUST 包含 `android:name=".MainActivity"` + `android:screenOrientation="portrait"` 字面量都在 MainActivity 节内（同一个 `<activity>` block）

#### Scenario: LapLiveScreen orientation API 字面量防回退

`feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreenOrientationContractTest.kt` 读 `LapLiveScreen.kt` 文本验证：

- **WHEN** 跑 `:feature:test:testDebugUnitTest`
- **THEN** 文件文本 MUST 包含 `SCREEN_ORIENTATION_LANDSCAPE`（进入横屏）+ `SCREEN_ORIENTATION_PORTRAIT`（离开恢复）+ `DisposableEffect` 字面量
- **AND** 任何未来重构若移除其中任何字面量必须 update 本测试 + 用户 review

### Requirement: 本 round 必须 NOT 改 LapLiveScreen.kt 源码

为保持 round scope 最小，`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` MUST 保持 0 行 diff。

理由：CLAUDE.md "Scope Boundaries"：MUST NOT modify files not strictly necessary for the requested task。本 round 仅"加 manifest 配置 + contract test"，不修任何 Composable 内部逻辑。

#### Scenario: LapLiveScreen 源码 diff 为空

- **WHEN** 本 round 全部 commit 完成后
- **THEN** `git diff feature/track-tech-v2..HEAD -- feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` 输出为空

### Requirement: 不影响其他 Composable 内部布局或逻辑

本 round 改动 MUST NOT 触发任何 Composable 内部 layout 重构 / orientation runtime 检测代码。

`androidx.compose.ui.platform.LocalConfiguration.orientation` 不在本 round 引入；任何页面 composable 内 MUST NOT 新增 orientation 分支判断（manifest 锁定后页面只会渲染 portrait）。

#### Scenario: 其他页面 Composable diff 为空

- **WHEN** 本 round 全部 commit 完成后
- **THEN** 以下文件 git diff 为空：
  - `feature/test/.../ui/tracktech/TestHomeScreen.kt`
  - `feature/test/.../ui/tracktech/LapsHomeScreen.kt`
  - `feature/test/.../ui/tracktech/RecordsHomeScreen.kt`
  - `feature/test/.../ui/tracktech/DeviceHomeScreen.kt`
  - `feature/test/.../ui/tracktech/TrackTechTestExecutionScreen.kt`
  - `feature/test/.../ui/tracktech/GpsDetailsScreen.kt`
  - `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`
  - `feature/test/.../ui/tracktech/PerformanceResultScreen.kt`
  - `feature/test/.../ui/tracktech/TrackTechAppShell.kt`
