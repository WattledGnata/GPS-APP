## Context

**现状**（2026-06-18）：
- `TrackTechTestExecutionScreen` 执行屏 line 128-141 `LaunchedEffect(testState)` 已经在 `Completed` 时调 VoiceAnnouncer 播报，但**未做 UI 反馈 / 导航**
- `CancelOrDoneButton` line 727-756 按 testState 在 Cancel/Done 二分；Done 分支 onClick 调外层 onDone 闭包，目前为 `navController.popBackStack()`
- 结果详情屏 `PerformanceResultScreen` 注册路由 `"performance_result/{testId}"`（TrackTechAppShell.kt:183），既有入口在 `RecordsHomeScreen.kt:219` 用 `result.id`（即 TestRecord.id）
- `TestState.Completed(result: TestRecord)` 数据类持有 result，`result.id` 是 Long 类型（按 Room entity 推断），跟 `RecordsHomeScreen` 入口一致

**约束**：
- V2 视觉约束（maxLines=1 + Ellipsis）保留
- Compose foundation 1.5/1.6（不引入 autoSize / 新外部库）
- road-test-first 模式（user 授权跳 Codex，真机攒批）
- 不影响既有 finishTest 自动落库链路（保留 saveResult 不依赖 UI 操作）

**stakeholders**：用户单一；CC 主会话 Opus 起草 + 实施。

## Goals / Non-Goals

**Goals:**
- Completed 瞬间用户得到"已保存"的强反馈（Toast）
- 按钮文案从"结束"语义改成"导航语义"（"查看详情"）
- 点击按钮后 user 可立即查看本次详情，跟历史界面入口同源
- 保留 finishTest 自动落库不依赖 UI 行为

**Non-Goals:**
- 不改 finishTest 落库链路
- 不改 VoiceAnnouncer / TTS 调用（独立通道）
- 不动圈速 lap session 结束行为（user 留 follow-up）
- 不引入新 navigation 框架；复用既有 NavController + route
- 不新建 PerformanceResultScreen 路由（复用既有 `performance_result/{testId}` 入口）
- 不动 Cancel 分支（运行中取消仍 popBackStack）
- 不加 BackHandler / 二次确认（Toast 反馈足够轻量）

## Decisions

### Decision 1：用 Toast 而非 Snackbar / Banner 做"已保存"反馈

**选择**：在 LaunchedEffect(testState) 内 `is TestState.Completed` 分支调 `Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()`。

**Rationale**：
- Toast 是 Android 标准短反馈控件，跟 user 既有期望对齐（`onExportClick` 也用 Toast 提示"未授权通知"等）
- 1 行 API 调用 + 不引入 Scaffold / Snackbar host 改造
- 不阻塞 UI（异步显示 + 自动消失）

**Alternatives 考虑**：

- (A) Material3 Snackbar：需要 Scaffold + SnackbarHostState + LaunchedEffect.snackbarHostState.showSnackbar；本屏当前无 Scaffold（直接 Column），改造面更大；收益（可点 action）不必要。**拒绝**：过度工程
- (B) 屏内 Banner 文字（Composable 持久显示"已保存"）：永久占屏空间不合适，且执行屏已显示成绩数字，再加 Banner 信息冗余。**拒绝**
- (C) 不加文字反馈仅靠按钮文案变化："查看详情"暗示进入下一页但不直接告知"已保存"；user 心理上可能仍需"主动验证"。**拒绝**：Toast 更稳

### Decision 2：onDone 基于当前 testState 分发 navigate / popBackStack

**选择**：

```kotlin
onDone = {
    val s = testState
    if (s is TestState.Completed) {
        navController.navigate("performance_result/${s.result.id}")
    } else {
        navController.popBackStack()  // 防御 race
    }
}
```

**Rationale**：
- `CancelOrDoneButton:735 onClick = if (isComplete) onDone else onCancel` 已经在 Composable 内做了 Completed 判定，但 `isComplete` 跟 `s is TestState.Completed` 的语义有可能在 Compose 重组时短暂 race（state 由 Completed 变其他态的极短窗口）
- onDone 闭包内**二次校验** testState 是 Completed 状态 → 100% 取到 result.id；else fallback popBackStack（兜底防止 NPE）

**Alternatives 考虑**：

- (A) 把 CancelOrDoneButton.onDone 签名改 `(testId: Long) -> Unit`，CancelOrDoneButton 内部从 testState 取 id 传：签名扩散改动面 + 父子组件耦合更紧。**拒绝**
- (B) 直接 unsafe cast `(testState as TestState.Completed).result.id`：race condition 时 cast 失败崩；不防御。**拒绝**
- (C) navigate("performance_result/${(testState as? TestState.Completed)?.result?.id ?: return@onDone popBackStack}")：safe cast 但表达式复杂可读性差。**拒绝**：用 if/else 块更清晰

### Decision 3：按钮文案 "查看详情"（中文），不用 "VIEW DETAIL"

**选择**：`CancelOrDoneButton:733 label = if (isComplete) "查看详情" else "CANCEL TEST"`。

**Rationale**：
- user 字面要求"Done 文案改 '查看详情'"——尊重 user 偏好
- 该按钮属于"UiTextLabel"风格（line 750），foundation Text 不会因为中文导致渲染异常
- maxLines=1 + Ellipsis 已加（line 752-753），"查看详情"4 字短不会触发截断

**Alternatives 考虑**：

- (A) 英文 "VIEW DETAIL" 跟 "CANCEL TEST" 风格保持一致：理论上更统一，但 user 已字面要求中文。**拒绝**：按 user 意图
- (B) "查看本次详情" 5 字：略长但完整；4 字"查看详情"已经语义清楚，无需冗长。**拒绝**

### Decision 4：Toast 只在 Completed 由别的状态翻入触发，不重复触发

**选择**：复用既有 `LaunchedEffect(testState) { val s = testState; if (s is TestState.Completed) { ... Toast ... } }`——LaunchedEffect 的 key 是 testState 引用，每次 testState 变化（**包括 Completed → Completed 的不同 result 实例**）会重跑 effect。但在本屏 flow 中，user 只跑一次测试，Completed 状态出现后不会重置回 Running 再 Completed（屏退出/重进会 dispose + 重新挂载 LaunchedEffect，但那时 Toast 也应该重新显示一次——符合用户预期）。

**Rationale**：
- Toast 的去重由 Android 系统 ToastQueue 处理（短时间内重复调用会合并/丢弃）
- 不需要额外的 `var toastShown by remember` 标记位 + reset 逻辑（增加复杂度无收益）

**Alternatives 考虑**：

- (A) 加 `var toastShown by remember { mutableStateOf(false) }` 仅首次 Completed 触发：避免重复但增加 state；本屏 Composable 退出后 remember 失效，重进时 toastShown 重置，行为等价。**拒绝**：当前方案已足
- (B) 加 `LaunchedEffect(testState is TestState.Completed)` key：boolean 比 testState 引用稳定，false→true 只触发一次。但 testState 在 Composable 生命周期内只能 false→true 一次（finishTest 后状态不回退），等价。**拒绝**：用既有 LaunchedEffect(testState) 复用 VoiceAnnouncer 调用块更清晰

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| `(testState as TestState.Completed)` 在 LaunchedEffect 内是 smart cast，但 Toast 调用时若同期 testState 跳变，可能 cast 失败 | LaunchedEffect 内已用 `if (s is TestState.Completed)` 守卫，smart cast 在分支内成立；Toast 调用在守卫内执行 |
| onDone 闭包内二次 cast testState 时 race condition（极短窗口）导致 fallback popBackStack 而非进入详情屏 | 实际 user 看到 Completed UI 时 testState 已稳定，点击窗口期内 race 概率极低；若发生 fallback 也是 graceful（popBackStack 行为不变） |
| Toast 在 vivo / 华为 ROM 上可能弹窗位置 / 样式异常 | Android Toast 是系统级控件，所有 ROM 支持；样式 / 位置由系统决定（不强求统一） |
| 用户点 "查看详情" 后进入 PerformanceResultScreen，再次 popBack 会回到执行屏（仍 Completed 状态）→ 再次显示 Toast / 仍可点 "查看详情"（循环可点） | 行为可接受（pop 回执行屏不强制 user 退出），且 ToastQueue 系统层去重；若 user 顺路再点也只是再进详情屏。但 user 退出执行屏走 NavController back-stack 即可 |
| 加 `import android.widget.Toast` 在文件顶部是 Android framework，跟既有 Compose pure UI 风格略偏；其他 Compose 屏多用 SnackbarHost | 工程内已有用 Toast 的先例（如 `LapVideoPlaybackScreen:265, 275` Export 流程；`LapVideoPanel` 等）；不破坏风格 |
| road-test-first 模式跳 Codex review → 实施期潜在 bug 只能靠真机攒批兜底 | (1) CC §A 自审；(2) 改动面极小（~15 行 + 1 文件），实施期 bug 面有限；(3) Toast / 文案修改无副作用，回滚 1 commit revert 即可 |

## Migration Plan

- 改动均在 feature/test module UI 层（无 schema migration / 无 DI 改动 / 无协议改）
- 部署：apply 后 gradle 编译 + apk
- 回滚：单 commit `git revert` 即可；UI 层无副作用 / 无 state 持久化

## Open Questions

无。三个关键 choice user 拍板（方案 = Done 文案改"查看详情" + Toast；圈速留 follow-up；TTS 不在本 round scope）。
