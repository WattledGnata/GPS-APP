## 1. 协同看板登记 + worktree 准备

- [ ] 1.1 阅读看板 §5/§6 核对：`TrackTechTestExecutionScreen.kt` 当前无并行 round 占用（独占）
- [ ] 1.2 看板 §5 登记本 round：`H. improve-test-execution-progress-bar`，状态"推进中"
- [ ] 1.3 创建 worktree：`git worktree add .worktrees/improve-test-execution-progress-bar -b feature/improve-test-execution-progress-bar feature/track-tech-v2`

## 2. progress 派生抽 pure function

- [ ] 2.1 在 `TrackTechTestExecutionScreen.kt` 文件内顶层加：
  ```kotlin
  internal const val LAUNCH_SPEED_THRESHOLD_KMH = 3.0
  
  internal data class ProgressState(val progress: Float, val waitingForLaunch: Boolean)
  
  internal fun computeProgressState(
      testState: TestState,
      currentMode: TestMode,
      speed: Double,
      launchThresholdKmh: Double = LAUNCH_SPEED_THRESHOLD_KMH,
  ): ProgressState {
      val progress: Float = when (val s = testState) {
          is TestState.Running -> when (currentMode) {
              TestMode.Acceleration -> (speed / 100.0).toFloat().coerceIn(0f, 1f)
              TestMode.Braking -> {
                  val start = s.session.dataPoints.firstOrNull()?.speed ?: 100.0
                  ((start - speed) / start.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
              }
              else -> 0f
          }
          is TestState.Completed -> 1f
          else -> 0f
      }
      val waitingForLaunch = testState is TestState.Running &&
          currentMode == TestMode.Acceleration &&
          speed < launchThresholdKmh
      return ProgressState(progress, waitingForLaunch)
  }
  ```
- [ ] 2.2 替换原 inline `val progress: Float = when ...`（line 74-85）为单行调用：
  ```kotlin
  val progressState = computeProgressState(testState, currentMode, speed)
  val progress = progressState.progress
  ```
- [ ] 2.3 ProgressPanel 调用处加 `waitingForLaunch = progressState.waitingForLaunch`

## 3. ProgressPanel 加 waitingForLaunch 分支

- [ ] 3.1 ProgressPanel 函数签名追加 `waitingForLaunch: Boolean = false` 参数
- [ ] 3.2 内部 fill 容器 modifier：当 `waitingForLaunch = true` 时 `Modifier.fillMaxWidth(0f)`，否则 `.fillMaxWidth(fraction = progress.coerceIn(0f, 1f))`
- [ ] 3.3 文案分支：waitingForLaunch = true → 显示 `"WAITING FOR LAUNCH"`（`TrackTechTypography.UiTextLabel` + `TrackTechColors.Cyan`）；false → 维持原 `displayPct%` 数字

## 4. 单元测试

- [ ] 4.1 新增 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/TrackTechTestExecutionProgressTest.kt`，纯 JUnit4，不引入 Robolectric
- [ ] 4.2 关键 test cases：
  - acceleration speed = 0 → progress = 0f, waitingForLaunch = true
  - acceleration speed = 2.9 → waitingForLaunch = true
  - acceleration speed = 3.0 → waitingForLaunch = false（边界包含）
  - acceleration speed = 50 → progress = 0.5, waiting = false
  - acceleration speed = 100 → progress = 1.0
  - acceleration speed = 120 → progress = 1.0（clamp）
  - braking start = 100, speed = 100 → progress = 0, waiting = false
  - braking start = 100, speed = 50 → progress = 0.5
  - braking start = 100, speed = 0 → progress = 1.0
  - braking start = 100, speed = 2.0 → waiting = false（制动模式不启用阈值）
  - testState = Idle → progress = 0, waiting = false
  - testState = Completed → progress = 1.0, waiting = false

## 5. 编译 + 单测

- [ ] 5.1 worktree 内 `./gradlew :feature:test:assembleDebug`
- [ ] 5.2 worktree 内 `./gradlew :feature:test:testDebugUnitTest`（含新增 TrackTechTestExecutionProgressTest）
- [ ] 5.3 `./gradlew :app:assembleDebug`

## 6. 真机验证（按看板 §4.2 串行规则，待 user 授权）

- [ ] 6.1 与 user 确认装机时间（默认华为 8KE0219522008434）
- [ ] 6.2 等 user 授权后 `adb -s 8KE0219522008434 install -r app/build/outputs/apk/debug/BlazePush_v1.0_debug.apk`
- [ ] 6.3 三阶段验证：
  - 按 START 但车未起步（speed < 3 km/h）：进度条无 fill + 文案 `"WAITING FOR LAUNCH"` cyan
  - 起步阶段（speed ≥ 3 km/h 后）：进度条 fill 开始增长，文案切回百分比数字
  - 测试完成：progress = 100%
- [ ] 6.4 制动测试验证：
  - 按 START 时 speed ≥ 100 km/h：直接进入 progress 渲染（无 WAITING 文案）
  - 减速过程 progress 正常增长

## 7. commit + 合回 + push

- [ ] 7.1 worktree 内 commit：`feat(ui): 性能测试加速模式起步阈值 + WAITING FOR LAUNCH 文案`
- [ ] 7.2 ff-only 合回主区
- [ ] 7.3 主区编译确认
- [ ] 7.4 **需用户显式确认才能 push**：`git push origin feature/track-tech-v2`
- [ ] 7.5 看板 §5 状态改 done
- [ ] 7.6 清理 worktree

## 8. follow-up backlog（不在本 round 实现）

- [ ] 8.1 `cleanup-v1-test-execution-screen` — V1 `TestExecutionScreen.kt:264 ProgressBar` 整组删（含 V1 navigation 整组 cleanup round）
- [ ] 8.2 `progress-time-based-fallback` — 如果阈值方案在低性能车型上仍然体感差，考虑加时间基准 fallback：当 speed < 阈值且时间已过 N 秒，强制 progress 走 elapsed/typicalDuration。**触发条件**：阈值方案上线后用户反馈仍有"按 START 后死气期"
- [ ] 8.3 `tune-launch-threshold` — 如果 3.0 km/h 在某些车型不合适（如电动车蠕行），调常量值；属轻量 1 行 commit。**触发条件**：用户报告"WAITING 文案停留过久"
