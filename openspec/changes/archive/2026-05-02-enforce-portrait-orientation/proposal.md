## Why

V2 Track Tech 全应用是单 Activity (`MainActivity`) + Compose Navigation 架构，AndroidManifest 当前**没有**指定 `android:screenOrientation` → 默认 `unspecified` → 跟随系统/用户旋转。

实际产品语义：

- **圈速实时屏（`lap_live`）**：仪表布局只为横屏设计（DELTA / CURRENT / BEST 三 tile 一字排开），已用 `DisposableEffect` 进入时切 `SCREEN_ORIENTATION_LANDSCAPE` / 离开时切 `SCREEN_ORIENTATION_PORTRAIT`
- **其他所有页面**（home / test_execution / gps_details / lap_session_detail / performance_result）：视觉系统都是竖屏紧凑布局，旋转后 layout 拉伸难看；user 反馈"目前是圈速实时屏幕，圈速回放详情将来肯定是可以切横屏的，后面不管是加视频还是加图表 都需要横屏，但是现在就没必要"

加一个最小可用的"全应用默认 portrait + `lap_live` 例外 landscape"配置，固定页面方向语义。圈速回放详情屏（`lap_session_detail`）将来加视频 / 轨迹图表时再切横屏，本 round 不动。

## What Changes

### AndroidManifest 锁默认竖屏

- **修改** `app/src/main/AndroidManifest.xml`：`<activity android:name=".MainActivity">` 追加 `android:screenOrientation="portrait"` 属性
- 行为：app 启动后默认 portrait；用户旋转设备时 Activity 不跟随旋转
- `LapLiveScreen` 现有 `DisposableEffect` 内 `requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE` 在 Compose 渲染时临时覆盖 manifest 默认值（标准 Android 行为，互不冲突），离开时 `SCREEN_ORIENTATION_PORTRAIT` 恢复

### LapLiveScreen 现有逻辑保留（不动）

`feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt` 内 `DisposableEffect`：

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

本 round MUST NOT 改这段逻辑。理由：

1. 显式 `SCREEN_ORIENTATION_PORTRAIT` 跟 manifest 默认 portrait 行为一致，但**显式调用可以防御 manifest 被人误改回 unspecified**（双重保险）
2. `keepScreenOn` 是圈速实时屏专属逻辑，跟 orientation 无关，不动

### 测试

- 新增 `MainActivityOrientationContractTest.kt`：grep AndroidManifest.xml 验证 `MainActivity` 节内含 `android:screenOrientation="portrait"` 字面量
- 新增 `LapLiveScreenOrientationContractTest.kt`：grep `LapLiveScreen.kt` 验证 `SCREEN_ORIENTATION_LANDSCAPE` + `SCREEN_ORIENTATION_PORTRAIT` 字面量都还在（防回退）

不做的事（明确 out-of-scope）：

- 不做 `lap_session_detail` 横屏切换（user 拍板"将来加视频/图表再说"，留 follow-up backlog）
- 不做 portrait + reverse-portrait 双向锁（`portrait` 默认含上下方向自适应，需求够用）
- 不引入 `OrientationProvider` / 全局 orientation manager 抽象（一个 manifest 属性 + 一个 DisposableEffect 已经够，过度设计违反 CLAUDE.md "Don't add ... abstractions beyond what the task requires"）
- 不动其他页面的 Composable 内部布局（manifest 锁 portrait 后任何页面在物理横屏的设备上都被 Activity 拒绝旋转，layout 不会被强迫适配）
- 不改 `LapLiveScreen.DisposableEffect`（现有逻辑跟 manifest 互补 + 双重保险）
- 不动 BLE / GPS / RaceChrono 协议
- 不引入 Compose `LocalConfiguration.orientation` 的 runtime 检测（unspecified → portrait 切换是 manifest 一次性配置）

## Capabilities

### New Capabilities

- `screen-orientation-policy`: 全应用屏幕方向策略契约 —— 默认 portrait 锁定 + `lap_live` 唯一 landscape 例外（DisposableEffect 模式）+ AndroidManifest 单一 source-of-truth

### Modified Capabilities

无（本 round 仅新增能力，不修改现有 spec）。

## Impact

### 受影响代码

- **修改**：
  - `app/src/main/AndroidManifest.xml`（`MainActivity` 节加 1 行 `android:screenOrientation="portrait"`）
- **新建**：
  - `app/src/test/java/com/blazepush/MainActivityOrientationContractTest.kt`（grep manifest 字面量验证）
  - `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreenOrientationContractTest.kt`（grep LapLiveScreen.kt LANDSCAPE/PORTRAIT 字面量防回退）

### 不受影响

- `feature/test/.../usecase/`、`feature/test/.../viewmodel/`、`core/data/*`、`core/domain/*`、`core/bluetooth/*`、`simulator/*`
- 任何现有 Composable 内部 layout（`@Preview`、`MetricTile`、`CutCornerPanel` 等）
- Room schema、binary 文件格式
- BLE / GPS / RaceChrono 协议
- 录制流程 / 圈速判定 / 加减速测试逻辑

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

启动前看板 §5/§6 核对：

- 主区当前无并行 round 在改 `app/src/main/AndroidManifest.xml`（grep §6 无登记）
- `LapLiveScreen.kt` 当前主区无并行 round 在改（I round 已归档；J round 未触此文件）
- AndroidManifest 改动是 **app 模块顶层配置**，跟任何 feature module 都不冲突 → 启动后立即 `feature/track-tech-v2` 主区改 + 提一个 commit + 直接合回（worktree 可省 — scope 太小不值得开 worktree）

### 测试影响

- 新增 ~30 行 contract 测试（纯 grep，不依赖 Robolectric / Compose runtime）
- 现有 `:app:testDebugUnitTest` + `:feature:test:testDebugUnitTest` 全套 MUST 零回归
- 真机验证：华为 `8KE0219522008434`：
  1. 启动 app → 默认竖屏；用户旋转设备 → home / Tab 切换 / TestExecution / GpsDetails / RecordsHome / PerformanceResult / LapSessionDetail 全部不跟随旋转
  2. Records → LAPS → 进 lap_live → 自动横屏；退 lap_live → 自动恢复竖屏
  3. lap_live 内旋转设备 → 仍保持横屏（不跟随，避免误反向旋转）
