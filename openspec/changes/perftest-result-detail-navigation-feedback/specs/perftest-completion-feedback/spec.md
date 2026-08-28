## ADDED Requirements

### Requirement: TrackTechTestExecutionScreen 在 Completed 时显示已保存反馈 Toast

`TrackTechTestExecutionScreen`（`feature/test/.../ui/tracktech/TrackTechTestExecutionScreen.kt`）SHALL 在 `testState` 变为 `TestState.Completed` 时，显示一次 Android `Toast` 短反馈（"已保存到历史"），给用户传达"成绩已自动持久化"信号。

实现 MUST 满足：

1. **Toast API**：MUST 使用 `Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()`；context 由 `LocalContext.current` 获取
2. **触发位置**：MUST 在既有 `LaunchedEffect(testState)`（line 128-141 附近）的 `if (s is TestState.Completed)` 分支内调用，与 VoiceAnnouncer 播报代码同位置
3. **不重复**：复用现有 LaunchedEffect 的 key（testState），不引入 `toastShown` flag；Android ToastQueue 系统层处理重复调用合并
4. **文案**：MUST 为 `"已保存到历史"`（简体中文）；MUST NOT 为 "Saved" / "Recorded" 等英文（与 user 字面要求一致）
5. **MUST NOT 替代 VoiceAnnouncer 播报**：Toast 是 UI 反馈，VoiceAnnouncer 是语音反馈，两通道独立共存

#### Scenario: Completed 时显示 Toast

- **GIVEN** 用户在 `TrackTechTestExecutionScreen` 跑 0-100 加速测试
- **WHEN** GPS 数据让 `shouldEnd` 返回 true → `TestSessionViewModel.finishTest()` 执行完 → `_testState` 变为 `TestState.Completed(result)`
- **THEN** `LaunchedEffect(testState)` 重跑，分支进入 `is TestState.Completed`
- **AND** `Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()` 被调用
- **AND** 屏底部弹出短 Toast "已保存到历史"

#### Scenario: 失败 DNF 不阻止 Toast

- **GIVEN** 用户跑 perftest，触发 DNF（如未达 100 km/h 直接退出），`result.totalTime <= 0.0`
- **WHEN** `_testState` 变为 `TestState.Completed(result)`（DNF 路径仍 Completed）
- **THEN** Toast "已保存到历史" 仍显示（DNF 记录也落库到 test_records 表）
- **AND** VoiceAnnouncer 播 "测试未完成"（既有 DNF 分支），不冲突

#### Scenario: 反例——MUST NOT 在 Running 状态显示 Toast

- **GIVEN** 用户在 `TrackTechTestExecutionScreen`，testState 为 `Running`
- **WHEN** LaunchedEffect(testState) 触发但 `s is TestState.Completed` 为 false
- **THEN** Toast 调用语句 MUST NOT 执行
- **AND** 若实现把 Toast 调用置于 if 守卫之外（无条件触发），spec 反例 scenario fail

### Requirement: CancelOrDoneButton 在 Completed 状态文案为「查看详情」且点击导航到详情屏

`CancelOrDoneButton`（`TrackTechTestExecutionScreen.kt:727-756`）SHALL 在 `testState is TestState.Completed` 时显示文案 `"查看详情"`，点击 onClick 时基于当前 testState 派发：
- Completed → `navController.navigate("performance_result/${result.id}")`
- 其他（race condition fallback） → `navController.popBackStack()`

实现 MUST 满足：

1. **文案**：`CancelOrDoneButton:733` `val label = if (isComplete) "查看详情" else "CANCEL TEST"`
2. **导航**：父 Composable 传 `onDone` 闭包 MUST 在内部读 testState，若 Completed 则 `navController.navigate("performance_result/${s.result.id}")`，否则 fallback popBackStack
3. **route 字面量对齐**：navigate 字面量 MUST 为 `"performance_result/${id}"`，跟 `TrackTechAppShell.kt:183` 注册路由 `"performance_result/{testId}"` + `RecordsHomeScreen.kt:219` 既有入口 `navController.navigate("performance_result/${result.id}")` 同源
4. **保留 Cancel 分支**：non-Completed 状态（Idle / Preparing / Running）文案保留 `"CANCEL TEST"`、onClick 保留 `cancelTest() + popBackStack()`，不变
5. **maxLines=1 + Ellipsis 保留**：line 752-753 现有 Text modifier 不动（既符合 V2 视觉约束，"查看详情" 4 字短不会触发截断）

#### Scenario: Completed 时按钮文案为「查看详情」

- **GIVEN** 用户跑完 0-100 加速，`testState is TestState.Completed`
- **WHEN** Composable 重组渲染 CancelOrDoneButton
- **THEN** `isComplete = true`，`label = "查看详情"`
- **AND** 按钮颜色为 `TrackTechColors.Green`（既有 isComplete 分支不变）
- **AND** Box 内 Text 显示 "查看详情"

#### Scenario: 点击「查看详情」导航到 PerformanceResultScreen

- **GIVEN** 用户在 Completed 状态看到 "查看详情" 按钮
- **WHEN** 点击按钮 → `onClick` 触发 `onDone`
- **THEN** onDone 闭包读取 testState → `s is TestState.Completed` 为 true
- **AND** 调 `navController.navigate("performance_result/${s.result.id}")`
- **AND** UI 进入 `PerformanceResultScreen`，展示本次记录详细成绩
- **AND** 同一详情屏跟历史界面入口（`RecordsHomeScreen.kt:219`）路径同源

#### Scenario: Running 状态按钮文案仍为「CANCEL TEST」

- **GIVEN** 测试运行中，testState is Running
- **WHEN** Composable 渲染 CancelOrDoneButton
- **THEN** isComplete = false，label = "CANCEL TEST"
- **AND** 按钮颜色 TrackTechColors.Red
- **AND** 点击调 cancelTest() + popBackStack()，不进入详情屏

#### Scenario: 反例——race condition fallback 走 popBackStack

- **GIVEN** Completed → 状态突变（理论上不发生，但代码层防御）
- **WHEN** onDone 闭包读 testState 时 `s is TestState.Completed` 为 false
- **THEN** fallback popBackStack（不抛 NPE / ClassCastException）
- **AND** 若实现用 unsafe cast `(testState as TestState.Completed).result.id` 直接取值，race 时崩——spec 反例 fail
