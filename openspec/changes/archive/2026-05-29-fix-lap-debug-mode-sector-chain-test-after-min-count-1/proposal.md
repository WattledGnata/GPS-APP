# Proposal: fix-lap-debug-mode-sector-chain-test-after-min-count-1

## Why

### 问题溯源（baseline fail 的来龙去脉）

Phase 1 W4 round `wire-laptime-to-gps-filter`（归档 commit `e2f4417`）做了两件事：

1. 把 `bridgeGpsToLapTiming` 上游接进 `GpsDataFilter`：`TestSessionViewModel.kt:341` 调 `gpsDataFilter.process(gpsData)` 得到 `filteredData`，line 351-356 用 filter 输出**替换 4 字段 `latitude/longitude/speed/bearing`**（`timestamp` 保持 raw），再以 `cleaned` 喂 `bridgeGpsToLapTiming(cleaned)`（line 357）。
2. 把 `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 从 3 降到 1（`LapLiveStateDeriver.kt:61`）。理由：filter 接通后单帧 jitter 已从数据流根因消除，单次真 invalidating event 即可触发 banner。

W4 由 mimo 模型执行且跳过 L2 review，遗留了一个**测试 fixture 与 filter 集成的交互回归**：单元测试 `TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete`（`feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt:197-221`）。看板 `docs/implementation-design/parallel-change-collab.md §7 memo #9` 记录该 test 在 baseline HEAD `4326e11` 已 fail，形态 **`expected:1 实际 0`**（`session.completedLaps.size`）。

### 当前 baseline（已 grep 核实，self-contained）

**关键事实 1 —— test expected 早已是宽容闭合语义，"修 expected 回 1" 的前提不成立**：

`git show e2f4417` 与 `git show HEAD`（`21809c7`）对比证实，该 test 的断言**在 W4 时点与当前 HEAD 完全一致**：

```kotlin
val session = requireNotNull(viewModel.lapSession.value)
assertEquals(1, session.completedLaps.size)        // 早已是 1（宽容闭合）
assertEquals(2, session.currentLapIndex)
assertTrue(session.completedLaps.first().qualityFlags.isNotEmpty())
```

即 expected **从来就是 1**，不是任务描述/看板暗示的"被改成 0、需要改回 1"。本 round 的真实修复对象是 **fixture 触发链路在 filter 接通后无法闭圈**，而非 expected 数值。

**关键事实 2 —— 生产代码的宽容闭合语义已存在且正确**：

`LapTimingEngine.kt:158-223` 的 `handleStartFinishCrossing`：当 `session.activeLap != null`（即第二次 startFinish 过线）时**无条件闭圈**，构造 `LapRecord` 并在 `qualityFlags` 里加 `LapQualityFlag.IncompleteSectors`（line 190-193：`if (activeLap.sectorEntries.size != track.sectorGates.size) add(IncompleteSectors)`），返回 `completedLaps + lapRecord`（size 从 0 → 1）。生产闭圈逻辑就是宽容闭合（user 2026-05-29 拍板的语义），无需改生产判圈代码。

**关键事实 3 —— baseline fail 根因 = filter 把合成过线两帧污染掉**（高置信推断，沙箱无网未实跑，apply 期 MUST 实测确认，见 design Decision 1）：

测试用 `emitCrossing(gate, prevTs, curTs)`（test:399-415）注入两帧 GPS：gate 中心 ± `passDirection × 0.25` 度的位置（~28km 跨度的合成跳变，speed 固定 36）。这两帧先过 `GpsDataFilter.process`（`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt`）：

- filter 对 lat/lon 做 9 点 median 平滑（line 111-113：`window.size >= 3` 时输出 median，否则 raw）。
- filter 的 `checkPositionVelocityConsistency`（line 276）：`isPositionAnomaly = ratio > 3.0` —— 0.25 度位置跳变远超 speed=36 允许的位移，必判 `isPositionAnomaly = true`；A13 异常帧 MUST NOT 进 window / 更新基准（line 36/94）。

结果：第二次过线的两合成帧被 filter 平滑/标异常，detector 拿到的 lat/lon 不再是 raw gate 两侧位置 → directionScore 算错 → 第二次 startFinish 过线**判定不 accepted** → 不进 `handleStartFinishCrossing` 闭圈分支 → `completedLaps.size` 停在 0 ≠ expected 1。

### 用户场景

debug 模式（`TestMode.LapDebug`）用于真机调赛道：起终点门 + sector 门可能未配全、信号可能丢帧。user 2026-05-29 拍板 **business decision：宽容闭合** —— 起终点过线两次就算一圈，不要求中间 sector 门全过；sector 不完整的圈仍出圈速，但需要给用户"这圈 sector 不完整"的可见提示。当前回归让该核心语义无单测护栏；若不修，宽容闭合契约在 filter 接通后处于"生产逻辑对、但没有任何绿测证明它在真实数据流（含 filter）下仍工作"的假绿状态。

## What Changes

1. **修测试 fixture 让 filter 接通后第二次过线仍能判定 accepted 并闭圈**（让 `completedLaps.size == 1` 真实达成，而非靠改 expected 数值掩盖）。最小化修法：在 `emitCrossing` 注入的过线两帧前后补足够的"稳态喂帧"预热 filter 窗口 / 或显式标注过线帧使其不被 `isPositionAnomaly` 误杀，使 detector 拿到可正确判定方向的 lat/lon。**MUST NOT 改生产 `bridgeGpsToLapTiming` 的 filter 集成契约**（那是 W4 design Decision，改动触发 #17 + 升级 medium，见 design Decision 1 拒绝理由）。

2. **强化断言锁死宽容闭合语义**：保留 `completedLaps.size == 1` / `currentLapIndex == 2`，并把 `qualityFlags.isNotEmpty()` 收紧为**显式断言 `qualityFlags.contains(LapQualityFlag.IncompleteSectors)`**（锁死"宽容闭合时该圈被打 sector 不完整标记"这条提示语义的真实落点）。

3. **保留 W4 的 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 1`**（与宽容闭合一致，不回退阈值）。本 round MUST NOT 触碰 `LapLiveStateDeriver.kt`。

4. **把"实时 banner / 详情屏 chip 真正承载 sector 不完整提示" 显式 defer 到 follow-up**（见下方 Impact 的 scope 收紧说明 + tasks §10）。

## Impact

### 受影响代码

- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`：仅 `lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete`（test:197-221）+ 可能 `emitCrossing` helper（test:399-415）。**纯测试侧改动**。
- 新增/修改 spec：capability `lap-timing-engine` 增 1 条 normative requirement，把"宽容闭合 + IncompleteSectors 标记"写成 SHALL/MUST 契约。

### 明确不改（scope 收紧 + 防半闭环承诺，v3 盲点 #1）

- **不改任何生产代码**（`LapTimingEngine.kt` / `TestSessionViewModel.kt` / `LapLiveStateDeriver.kt` / `GpsDataFilter.kt` / `LapSessionDetailScreen.kt`）。
- **不在本 round 接通实时 banner / 详情屏 chip 的 sector-不完整提示**。grep 核实当前提示机制现状：
  - 实时 `LapLiveStateDeriver.AbnormalState.LAP_INVALIDATED`（`LapLiveStateDeriver.kt:149-167`）只由 `invalidatingReasons = {WrongDirection, UnexpectedGateOrder, TooSlow}`（line 63-67）触发，**不含 sector 不完整** → 实时横屏不会因宽容闭合提示。
  - 详情屏 `UiLapStatus.INCOMPLETE`（`LapSessionDetailScreen.kt:461`）在 `deriveDetailMetrics`（line 471-510）中**从未被赋值**（派生只产 BEST/VALID/INVALID，不读 qualityFlags）→ 当前是死分支，sector 不完整的圈在详情屏显示为普通 VALID。
  - 唯一真实承载 sector 不完整信号的是 in-memory `LapRecord.qualityFlags` 含 `IncompleteSectors`（测试可断言）。
  - 因此本 round scope #2 收紧为"断言 in-memory qualityFlags"，把"UI 层真正接通提示"defer 到 follow-up round（tasks §10），避免承诺"加 invalidation banner"却只动测试断言的半闭环。

### 复杂度 / review 流程

- 复杂度 **small**（纯测试侧 + 单 capability spec + 无 schema 改 + 不改生产代码）。
- 执行模式 **road-test-first**（user 已授权批次内）：去 Codex / 跳 Opus 子 agent；兜底 = CC 单遍自审 + Baseline 7 条款 + apply 期 #3/#14/#16 自查 + #17 drift 自查 + 真机攒批。
- **不命中强制升级 medium 的 5 例外**：不改公共协议（不动 GpsDataFilter / bridge filter 契约）、无跨 capability ripple、无 Room schema migration、不引入新 module/capability、不派生 follow-up round（§10 backlog 是 UI 提示接通，属独立主题非本 round 派生债）。
- **纯测试代码无运行时 → 无需 FileLogger 埋点**（road-test-first MANDATORY 埋点仅针对运行时生产路径）。
