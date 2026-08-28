## 1. 锚点 verify（apply 前 grep — v3 #3 自查）

- [ ] 1.1 grep `LaunchedEffect(testState)` 在 `TrackTechTestExecutionScreen.kt:128` 命中一次（VoiceAnnouncer 调用所在块）
- [ ] 1.2 grep `onDone = { navController.popBackStack() }` 在 `TrackTechTestExecutionScreen.kt:250` 命中一次（待改）
- [ ] 1.3 grep `val label = if (isComplete) "DONE"` 在 `TrackTechTestExecutionScreen.kt:733` 命中一次（待改）
- [ ] 1.4 grep `performance_result` 在 `RecordsHomeScreen.kt:219` + `TrackTechAppShell.kt:183` 各命中一次（既有 route 入口锚点）
- [ ] 1.5 grep `Toast.makeText` 在工程内有先例（`LapVideoPlaybackScreen` 等），import 路径 `android.widget.Toast`

## 2. 实施 — TrackTechTestExecutionScreen 改动

- [ ] 2.1 顶部 imports 加 `android.widget.Toast` 与 `androidx.compose.ui.platform.LocalContext`（如未导入）
- [ ] 2.2 `fun TrackTechTestExecutionScreen` 顶层加 `val context = LocalContext.current`（如未已存在）
- [ ] 2.3 `LaunchedEffect(testState)` line 128-141 内 `is TestState.Completed` 分支首句加：
      ```kotlin
      Toast.makeText(context, "已保存到历史", Toast.LENGTH_SHORT).show()
      ```
      与 VoiceAnnouncer 播报代码并列（同 if 守卫内）
- [ ] 2.4 line 244-250 `CancelOrDoneButton` 的 `onDone` 闭包改为基于 testState 的分发：
      ```kotlin
      onDone = {
          val s = testState
          if (s is TestState.Completed) {
              navController.navigate("performance_result/${s.result.id}")
          } else {
              navController.popBackStack()
          }
      },
      ```
- [ ] 2.5 line 733 `CancelOrDoneButton` 内 `val label = if (isComplete) "DONE" else "CANCEL TEST"` 改为 `val label = if (isComplete) "查看详情" else "CANCEL TEST"`

## 3. 编译 + grep gate

- [ ] 3.1 `./gradlew :app:assembleDebug` 通过
- [ ] 3.2 grep `Toast.makeText(context, "已保存到历史"` 在 TrackTechTestExecutionScreen.kt 命中 1 次
- [ ] 3.3 grep `navigate("performance_result` 在 TrackTechTestExecutionScreen.kt 命中 1 次（新增的 onDone 分发分支）
- [ ] 3.4 grep `"DONE"` 在 TrackTechTestExecutionScreen.kt 命中 0 次（label 已改）
- [ ] 3.5 grep `"查看详情"` 在 TrackTechTestExecutionScreen.kt 命中 1 次

## 4. apk + user 真机验证

- [ ] 4.1 apk 落盘 `app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`，告诉 user 装机
- [ ] 4.2 user vivo V2405A 跑 0-100 测试：成绩出来瞬间看到 Toast "已保存到历史" + 按钮文案变 "查看详情"
- [ ] 4.3 点击 "查看详情" → 进入 PerformanceResultScreen 展示成绩详细
- [ ] 4.4 退回历史界面 → 看到本次记录
- [ ] 4.5 点击历史中本次记录 → 同一 PerformanceResultScreen，数据一致

## 5. push 顺序（user 拍板）

- [ ] 5.1 真机验证通过后准备 commit；本 round 涉及 1 文件 + 工件目录，单 commit
- [ ] 5.2 user 决定何时 push

## 6. 归档（push 后）

- [ ] 6.1 metrics.yaml 写入（`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0`）
- [ ] 6.2 `openspec archive perftest-result-detail-navigation-feedback`

## 10. follow-up backlog

- **圈速 (lap timing) 同步**：user 反馈"圈速也一样"。圈速 session 结束流程（`endActiveLapSession() / finishActiveLapSession():609-643`）也是自动落库，但缺类似 Toast + 自动跳转详情。需起独立 round `lap-session-completion-feedback`：分析 lap session 结束的 UI 入口（可能是 `LapLiveScreen` 退出 / Done 按钮路径）、PerformanceResultScreen 等价的"单圈详情屏"（已有 `LapSessionDetailScreen`）、navigate 路径。
- **VoiceAnnouncer 资源泄漏**：`VoiceAnnouncer.shutdown()` 存在但从未在 DisposableEffect 中调用，TextToSpeech 实例累积占内存（不 crash）。起 round `voice-announcer-lifecycle-shutdown` 在 LapLiveScreen / TestExecutionScreen DisposableEffect onDispose 中调 `voiceAnnouncer.shutdown()`。
- **vivo TTS 健康度诊断**：跑 `TtsProbeTest.kt`（已存在工具）确认 vivo 上 TTS 引擎是否健康；如果 vivo 缺中文引擎，UI 加"语音播报不可用" Toast 兜底（不在本 round scope）。
