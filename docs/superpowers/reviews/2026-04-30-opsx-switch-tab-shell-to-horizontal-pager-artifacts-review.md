# switch-tab-shell-to-horizontal-pager artifacts review

- **日期**：2026-04-30
- **变更**：`openspec/changes/switch-tab-shell-to-horizontal-pager`
- **结论**：暂不放行 `/opsx:apply`。OpenSpec strict validate 通过，但 spec / design / tasks 对测试强度和 baseline 迁移数量存在冲突，需先修正文档再实施。

## Findings

### 1. [P1] HorizontalPager 测试契约与 tasks 实施方案冲突

- **位置**：
  - `openspec/changes/switch-tab-shell-to-horizontal-pager/specs/track-tech-app-shell/spec.md:201-209`
  - `openspec/changes/switch-tab-shell-to-horizontal-pager/tasks.md:322-356`
- **问题**：spec 明确要求 `TrackTechAppShellPagerTest` 使用 JUnit4 + Compose UI Test（`createComposeRule`），并要求覆盖 `pagerState.pageCount == 4`、拖拽滑动到 page 1 后 `pagerState.currentPage == 1`（若支持）。但 tasks §6.1 又明确说“不引入 Compose UI Test”，只做 `TabIndex` 与 `DefaultTrackTechTabs` 的静态契约测试。实施方按 tasks 做会违反 spec；按 spec 做则需要新增 Compose UI test 依赖/基础设施，和 tasks 的 scope 边界冲突。
- **要求**：二选一拍板并同步三件套：
  - **方案 A（推荐小 round）**：把 spec 的测试 requirement 降级为静态契约测试，明确本 round 只验 `TabIndex` / `DefaultTrackTechTabs` / 源码 grep，拖拽与 bottom nav selected 走 §8 真机 manual gate；删除 “MUST 用 Compose UI Test”。
  - **方案 B**：保留 spec 强契约，把 tasks 改为引入 `ui-test-junit4` / `ui-test-manifest` 并实现真实 ComposeRule 测试，同时把依赖变更、测试稳定性和运行门槛写进 proposal/design/tasks。

### 2. [P2] navigateToTab baseline 数量在 spec 中写少，可能漏掉 100-0 或 Laps 未 ready 分支

- **位置**：
  - `openspec/changes/switch-tab-shell-to-horizontal-pager/specs/cross-tab-device-gating/spec.md:94-104`
  - `openspec/changes/switch-tab-shell-to-horizontal-pager/tasks.md:31-41`
- **问题**：tasks §0.2 正确识别 baseline 有 5 个 `navigateToTab` 迁移点：定义 1 处 + TestHomeScreen 调用 3 处（StatusStrip + 0-100 未 ready + 100-0 未 ready）+ LapsHomeScreen 调用 2 处（StatusStrip + START LAP SESSION 未 ready）。但 spec 写成“共 4 处：StatusStrip onClick × 2 + 主操作未 ready 分支 × 2”，漏掉一个主操作未 ready 分支。虽然后面的 grep 零残留能兜底，但 spec 的文字核销条件会误导实现和 review。
- **要求**：把 spec 改为“共 5 处调用 + 1 处 extension 定义”，并逐项列出：Test StatusStrip、Test 0-100 未 ready、Test 100-0 未 ready、Laps StatusStrip、Laps START LAP SESSION 未 ready。对应 Scenario 的 `THEN` 也改为“5 处 baseline 调用 + 1 处 extension 定义全部清除”。

## Verification

- `openspec validate switch-tab-shell-to-horizontal-pager --strict`：PASS。
- 本轮未运行 Gradle，因 review 停在 artifacts 阶段。

## Verdict

修完上述 P1/P2 后可重提 mini review。若采纳 P1 方案 A，这个 change 仍可保持小而快；真实滑动体验由 §8 真机 manual gate 兜底即可。

---

## V2 Review

- **日期**：2026-04-30
- **结论**：仍暂不放行。P2 数量问题已修；P1 的 spec/tasks 已降级一致，但 design.md D7 仍残留旧 ComposeRule 测试契约。

### Closed

- 原 Finding 2 已修：`cross-tab-device-gating/spec.md` 已明确 5 处 baseline `navigateToTab` 调用 + 1 处 extension 定义，tasks / design / commit body 中的 "4 处" 残留已清。

### Remaining Finding

#### 1. [P1] design.md D7 仍要求 Robolectric / ComposeRule 和真实滑动测试

- **位置**：`openspec/changes/switch-tab-shell-to-horizontal-pager/design.md:150-163`
- **问题**：spec 已改成 `HorizontalPager 静态契约测试`，tasks §6 也明确不引入 `androidx.compose.ui.test.junit4.createComposeRule`，真实滑动由真机 manual gate 兜底。但 design D7 仍写新增 `TrackTechAppShellPagerTest（Robolectric / ComposeRule）`，覆盖点击 Device tab、拖拽滑动、进入子页隐藏 bottom nav、pop 回 home 保留 page，并写 “Robolectric ComposeRule 已能验证 page 索引契约”。这与 V2 采纳的小 round 方案仍冲突。
- **要求**：把 D7 改为与 spec/tasks 一致：
  - `TrackTechAppShellPagerTest` 仅做纯 JUnit4 静态契约：`TabIndex` 5 个常量 + `DefaultTrackTechTabs` 顺序。
  - 明确不引入 ComposeRule / Compose UI Test 依赖。
  - 点击/拖拽/子页 bottom nav/状态保留改为 §8 真机 manual gate。
  - ComposeRule UI test 升级保留在 follow-up backlog。

## V2 Verification

- `openspec validate switch-tab-shell-to-horizontal-pager --strict`：PASS。

---

## V3 Review

- **日期**：2026-04-30
- **结论**：通过。允许进入 `/opsx:apply switch-tab-shell-to-horizontal-pager`。

### Closed

- V2 remaining finding 已修：`design.md` D7 已改为纯 JUnit4 静态契约测试，明确不引入 Compose UI Test / ComposeRule / `ui-test-junit4` / `ui-test-manifest`；真实点击、滑动、bottom nav 显隐、popBack 状态保留交给 `tasks.md §8` 真机 manual gate。
- P1 原始冲突已闭环：`specs/track-tech-app-shell/spec.md`、`design.md`、`tasks.md` 对测试范围一致。
- P2 原始冲突已闭环：`navigateToTab` baseline 数量在 spec/design/tasks 中统一为 5 处调用 + 1 处 extension 定义。

### Verification

- `openspec validate switch-tab-shell-to-horizontal-pager --strict`：PASS。
- `rg "4 处|Robolectric|ComposeRule|createComposeRule|ui-test" openspec/changes/switch-tab-shell-to-horizontal-pager`：无旧契约实质残留。

### Apply Review Focus

代码落地后重点复查：

- `TrackTechAppShell.kt`：`NavHost(startDestination = "home")` + home 内 `HorizontalPager` + 子页 4 个 transition None。
- `TrackTechBottomNav.kt`：签名改为 `currentPage + onTabSelected`，不再持有 `NavController`。
- `TestHomeScreen.kt` / `LapsHomeScreen.kt`：5 处 `navigateToTab` 调用全部迁移为 `onTabSelected(TabIndex.Device)`，extension 删除。
- `TrackTechEventBus.showScanSheetEvent`：Shell collector 切 Device tab，DeviceHomeScreen 原 collector 继续展开 sheet。
- `TrackTechAppShellPagerTest`：只做静态契约，不新增 Compose UI Test 依赖。

---

## Code Review

- **日期**：2026-04-30
- **结论**：暂不核销。代码、静态测试、grep 大体按工件落地，`installDebug` 已成功安装到在线设备；但跨 tab 自动展开 BLE sheet 的核心 manual gate 存在事件丢失风险，需要先修。

### Finding

#### 1. [P1] SharedFlow replay=0 会让 DeviceHomeScreen 错过打开 sheet 的事件

- **位置**：
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechEventBus.kt:20-35`
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackTechAppShell.kt:59-64`
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt:102-110`
- **问题**：`TrackTechEventBus.showScanSheetEvent` 是 `MutableSharedFlow<Unit>(extraBufferCapacity = 1)`，`replay = 0`。当用户在 Test tab 未连接 BLE 时点击 0-100，事件发出时当前 page 是 Test（0），Pager 的 `beyondBoundsPageCount = 1` 只会预组合 Test/Laps，Device page（3）通常尚未组合，因此 `DeviceHomeScreen` 的 collector 还不存在。Shell collector 会收到事件并开始 `animateScrollToPage(Device)`，但 DeviceHomeScreen 在稍后组合时不会收到已经发出的 SharedFlow event，`showSheet` 仍是 false。
- **影响**：`tasks.md §8.2` 最后一项 “BLE 未连接时点击 0-100 主操作 → 切到 Device tab，BLE Scan Sheet 自动展开” 会退化为只切到 Device tab，不展开 sheet。这正是本 change 的 cross-tab-device-gating 核心契约。
- **要求**：让“切 Device tab + 展开 sheet”成为同一状态路径，而不是依赖未组合页面的旧事件。可选修法：
  - 推荐：Shell collector 在收到事件后设置 `pendingShowScanSheet = true`，传给 `DeviceHomeScreen(showScanSheetRequest = pendingShowScanSheet, onShowScanSheetConsumed = { pendingShowScanSheet = false })`；DeviceHomeScreen 进入组合后消费状态并打开 sheet。
  - 或把 `TrackTechEventBus` 改为 replay/state 型事件，并在 DeviceHomeScreen 消费后显式 reset，避免重复弹出。
  - 或由 Shell 直接持有 sheet 状态并把打开指令下传给 DeviceHomeScreen。
- **测试/验证**：补一个静态/单元契约或最少 grep contract，确保 DeviceHomeScreen 不再只依赖 `SharedFlow(replay=0)` 事件；真机重新跑 §8.2 最后一项。

### Verification

- `./gradlew :feature:test:testDebugUnitTest --tests "*TrackTechAppShellPagerTest*"`：PASS。
- grep：`navigateToTab`、tab route `navController.navigate("test"/"laps"/"records"/"device")`、旧 `beyondViewportPageCount`、Compose UI Test 依赖关键字均无命中。
- `adb devices`：在线设备 `ME011011255101188`。
- `./gradlew :app:installDebug`：BUILD SUCCESSFUL，已安装到 `rk3588s_me30_native - 15`。

---

## Code Review V2

- **日期**：2026-04-30
- **结论**：P1 已关闭，代码 review 通过。剩余为人工视觉 / 手势 manual gate 签收与 commit。

### Closed

- 原 code review P1 已修：`TrackTechAppShell` 作为唯一可靠 collector 监听 `TrackTechEventBus.showScanSheetEvent`，收到事件后同时执行 `onTabSelected(TabIndex.Device)` 与 `pendingShowScanSheet = true`。
- `DeviceHomeScreen` 不再直接订阅 `SharedFlow(replay=0)`，改为接收 `pendingShowScanSheet` + `onPendingShowScanSheetConsumed`，组合后通过 `LaunchedEffect(pendingShowScanSheet)` 打开 sheet、`startScan()` 并 reset flag。
- spec/design/tasks 已同步为 pending state 方案，避免工件与代码偏离。

### Verification

- `openspec validate switch-tab-shell-to-horizontal-pager --strict`：PASS。
- `./gradlew :feature:test:testDebugUnitTest`：PASS。
- `ANDROID_SERIAL=ME011011255101188 ./gradlew :app:installDebug`：PASS，已安装到 `rk3588s_me30_native - 15`。
- grep：`navigateToTab`、tab route `navController.navigate("test"/"laps"/"records"/"device")`、旧 `beyondViewportPageCount`、Compose UI Test 依赖关键字均无命中。

### Remaining Manual Gate

请在已安装设备上人工确认 `tasks.md §8.2` 7 项：冷启动无 scaleIn、左右滑动切 tab、点击 bottom nav 切 tab、滚动位置保留、进入 `test_execution` 无 scaleIn、popBack 后 bottom nav/page 保留、未连接 BLE 时主操作切 Device tab 并自动展开 BLE Scan Sheet。
