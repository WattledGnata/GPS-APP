## Why

用户反馈"单测成绩出来好像卡死了" / "必须点 done 才记录"。代码层调查显示：
- `TestSessionViewModel.finishTest():1079-1108` 在 `shouldEnd(filteredData)` 返回 true 那一帧**已经自动**调 `endSession + saveResult` 落库到 `test_records` + `speed_segments` 表（全链路 IO dispatcher）
- `TrackTechTestExecutionScreen.kt:244-250` Done 按钮 onClick `popBackStack()`——**只导航不落库**
- 因此"必须点 Done 才记录"假设**不成立**

但**体验层确实有缺口**：
- Completed 后执行屏停留显示成绩数字，**不自动跳转**结果详情屏（`PerformanceResultScreen`）
- 没有任何"已保存到历史"的显式反馈（toast / banner / 字样）
- 用户看到成绩数字但**没有强反馈**告知"已记录" → 心理上以为没保存 → 点 Done 退回历史才发现确实记了 → 误判"是 Done 触发的记录"
- Done 按钮文案 `"DONE"` 暗示"结束"，没传达"查看详情"路径，加深 user 误判

## What Changes

- `TrackTechTestExecutionScreen.kt` 顶层 LaunchedEffect(testState) 监听到 `TestState.Completed` 时，显示一次 `Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()`（与既有 VoiceAnnouncer 播报同位置）
- `CancelOrDoneButton:733` 文案在 `isComplete` 分支从 `"DONE"` 改为 `"查看详情"`
- `CancelOrDoneButton:244-250 onDone` 闭包从 `navController.popBackStack()` 改为基于当前 `testState`：若 `Completed` 则 `navController.navigate("performance_result/${s.result.id}")`，否则 fallback popBackStack（防御 race condition）

不动：
- `TestSessionViewModel.finishTest` 自动落库链路（已正确）
- `CancelOrDoneButton` 的 `Cancel` 分支（运行中点 Cancel 仍 popBackStack）
- VoiceAnnouncer 调用（既有 LaunchedEffect 不动）
- 圈速 lap timing 结束体验（user 拍板留 follow-up）

## Capabilities

### New Capabilities
- `perftest-completion-feedback`：定义 perftest 成绩闭合后的用户反馈契约（Toast 反馈 + 自动导航到结果详情 + 按钮文案）

### Modified Capabilities
（无）

## Impact

**代码改动**（~15-20 行，1 文件）：
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionScreen.kt`
  - line 128-141 LaunchedEffect(testState) 内 `is TestState.Completed` 分支加 Toast 调用 + 顶层加 `val context = LocalContext.current`
  - line 244-250 `onDone` 闭包改基于 testState 的 navigate / popBackStack 分发
  - line 733 文案改 "查看详情"
  - 顶部加 import: `android.widget.Toast` + `androidx.compose.ui.platform.LocalContext`（如未导入）

**测试**：
- 现有 ViewModel / Repository 单测不变（行为契约未变 — 仍是 Completed 自动落库）
- 不加 instrumentation test（Compose UI 行为难单测，由真机 + Toast 视觉验证兜底）

**协议 / Schema / 数据流**：均不变（无公共协议、无 Room schema、无 BLE 协议改）。

**真机验证 gate**（road-test-first 模式，user 真机补）：
- 跑一个完整 0-100 测试：成绩出来瞬间看到 "已保存到历史" Toast + 按钮文案变 "查看详情"
- 点击 "查看详情" → 进入 `PerformanceResultScreen` 展示详细成绩
- 退回历史界面看到本次记录
- 点击历史中本次记录 → 同一 PerformanceResultScreen，数据一致（验证 nav 路径跟既有入口同源）
