## ADDED Requirements

### Requirement: 加速测试低于起步阈值时显示等待文案

V2 性能测试执行屏 `TrackTechTestExecutionScreen` 在加速测试（`TestMode.Acceleration`）模式下，当当前 GPS speed 低于 `LAUNCH_SPEED_THRESHOLD_KMH`（默认 3.0 km/h）时 MUST 显示等待状态：

- 进度条 fill MUST 不绘制（fraction = 0）
- 进度文案 MUST 显示字面量 `"WAITING FOR LAUNCH"`
- 文案使用 `TrackTechTypography.UiTextLabel` + `TrackTechColors.Cyan`，与现有 cyan section header 风格一致

speed 跨过阈值后 MUST 切回正常 progress 渲染（fill fraction = `(speed / 100.0).coerceIn(0f, 1f)`，文案为百分比数字）。

#### Scenario: 加速测试 speed 低于阈值显示 WAITING FOR LAUNCH

- **WHEN** 测试模式为 `TestMode.Acceleration` 且 speed 为 `0.0`
- **THEN** progress fill MUST 不绘制
- **AND** 文案 MUST 显示 `"WAITING FOR LAUNCH"`

#### Scenario: 加速测试 speed 跨过阈值切回正常 progress

- **WHEN** 测试模式为 `TestMode.Acceleration` 且 speed 为 `5.0`
- **THEN** progress fill fraction MUST 为 `0.05`
- **AND** 文案 MUST 显示百分比数字（例如 `"5%"`）

#### Scenario: 阈值边界值 - speed 等于阈值

- **WHEN** speed 恰好为 `LAUNCH_SPEED_THRESHOLD_KMH`（即 3.0）
- **THEN** waitingForLaunch 派生 MUST 为 `false`（阈值上界包含在正常态）
- **AND** 正常 progress 渲染

### Requirement: 制动模式不启用起步阈值

`TestMode.Braking` 模式 MUST NOT 启用起步阈值。制动测试按 START 时 speed 已经 ≥ 100 km/h，progress 立即开始增长，加阈值反而造成"刹车开始后 progress 不动"反向 bug。

#### Scenario: 制动测试 speed 等于起始值不显示 WAITING

- **WHEN** 测试模式为 `TestMode.Braking` 且 speed 为 `100.0`（等于 start）
- **THEN** waitingForLaunch 派生 MUST 为 `false`
- **AND** progress fill fraction MUST 为 `0`（刚开始未减速）

#### Scenario: 制动测试 speed 接近 0 不卡 WAITING

- **WHEN** 测试模式为 `TestMode.Braking` 且 speed 为 `2.0`（已经接近停止）
- **THEN** waitingForLaunch 派生 MUST 为 `false`
- **AND** progress 正常计算（约 `0.98`）

### Requirement: progress 派生函数 MUST 是纯函数可单测

派生 `progress` 与 `waitingForLaunch` 的逻辑 MUST 抽为 `internal` 顶层 / 文件内 pure function（无 Compose `@Composable` 依赖、不读 ViewModel / StateFlow）。函数签名建议：

```kotlin
internal data class ProgressState(val progress: Float, val waitingForLaunch: Boolean)
internal fun computeProgressState(
    testState: TestState,
    currentMode: TestMode,
    speed: Double,
    launchThresholdKmh: Double = LAUNCH_SPEED_THRESHOLD_KMH,
): ProgressState
```

新增 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionProgressTest.kt` 单测覆盖：

- speed = 0 / 2.9 / 3.0 / 50 / 100 / 120（clamp）/ 制动 100 / 50 / 0 等关键边界

#### Scenario: pure function 可在单测调用

- **WHEN** 在单测调用 `computeProgressState(TestState.Running(...), TestMode.Acceleration, speed = 0.0)`
- **THEN** 返回 `ProgressState(progress = 0f, waitingForLaunch = true)`
- **AND** 测试不依赖 Compose runtime / Robolectric

#### Scenario: clamp 行为

- **WHEN** speed = 120（超过 100）+ 模式为 Acceleration
- **THEN** progress = 1f（被 coerceIn 截到 1f）
- **AND** waitingForLaunch = false

### Requirement: V1 dead code TestExecutionScreen 不在本 round 验收范围

`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt:264 ProgressBar` 是 V1 dead code（MainActivity 已用 V2 TrackTechAppShell，全 app grep 无引用）。本 round MUST NOT 修改 V1 文件，cleanup round 整组删除 V1 屏。

#### Scenario: V1 文件 diff 为空

- **WHEN** 本 round 全部 commit 完成后查 git diff
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt` diff 为空
