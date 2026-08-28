# Tasks: fix-lap-debug-mode-sector-chain-test-after-min-count-1

> 执行模式 road-test-first / 复杂度 small。所有路径绝对锚定 HEAD `21809c7`；apply 期先跑 §1 锚点 verify。
> worktree：`.worktrees/fix-lap-debug-mode-sector-chain-test-after-min-count-1`（已存在）。

## 1. apply 期锚点对齐自查（#3，启动前必跑）

- [x] 1.1 grep 确认失败测试锚点未漂移：
  `grep -n "lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete" feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`
  done condition：命中且函数在 line ~197；测试体含 `assertEquals(1, session.completedLaps.size)`（确认 expected 已是 1）。
- [x] 1.2 grep 确认生产宽容闭合逻辑未漂移：
  `grep -n "IncompleteSectors\|sectorEntries.size != track.sectorGates.size" feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
  done condition：命中 `LapTimingEngine.kt:190-193` 附近（无条件闭圈 + 打 flag）。
- [x] 1.3 grep 确认 filter 集成路径未漂移：
  `grep -n "gpsDataFilter.process\|bridgeGpsToLapTiming(cleaned)\|filteredData.latitude" feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
  done condition：命中 `TestSessionViewModel.kt:341/351-357`（4 字段替换 + cleaned 喂 bridge）。
- [x] 1.4 #14 fake DAO 自查（本 round 无 DAO 接口签名变化）：
  `grep -rn "interface .*Dao" feature/test/src/main` —— 确认本 round 不改任何 DAO 接口（纯测试断言改动）。done condition：无需补 fake stub。

## 2. baseline 取证（实跑确认 fail 形态）

- [x] 2.1 在 worktree 实跑当前 test 确认 baseline fail：
  实测：`assertEquals(1, session.completedLaps.size)`（test:215）报 `expected:<1> but was:<0>`，
  与 design Decision 1 推断（filter 污染合成过线帧→第二次过线不 accepted→不闭圈）一致。
  `cd .worktrees/fix-lap-debug-mode-sector-chain-test-after-min-count-1 && ./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete"`
  done condition：记录 fail 形态（预期 `expected:<1> but was:<0>` on `completedLaps.size`）。
  注：若环境 gradle wrapper 缺 dist 需先确保可联网下载或复用已有 dist 缓存。
- [x] 2.2 实测 fail 形态/根因与 design Decision 1 推断一致（filter 污染合成过线帧）→ 无需 #17 修订，按原计划进 §3。

## 3. 修测试 fixture 让过线真实达成（Decision 1）

- [x] 3.1 在 `TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete`
  内，第二次 startFinish 过线改用手法 A+B 组合（稳态预热 9 帧刷 median 窗口 + dt=400ms/4m 物理合理位移 +
  median 步长 > gate 带宽保单次穿越），实跑确认第二次过线 accepted、真实闭圈。
  done condition：实跑 `completedLaps.size == 1`、`currentLapIndex == 2`（非靠改 expected）。✓ 实测通过。
- [x] 3.2 局部在本 test 内构造第二次过线轨迹（未改共享 `emitCrossing` helper —— 首次过线仍复用 helper）。
  R2 连锁风险最小化；全量回归见 §4.2。

## 4. 强化断言（Decision 2）+ 回归验证

- [x] 4.1 断言已收紧为 `qualityFlags.contains(LapQualityFlag.IncompleteSectors)`，并补
  `import com.blazepush.feature.test.model.laptiming.LapQualityFlag`。实测该圈 qualityFlags =
  `[IncompleteSectors, ProtocolDesyncGap]`，contains(IncompleteSectors) == true，编译 + 断言通过。
- [x] 4.2 整个 `TestSessionViewModelTrackLapTest` 类全绿（R2 连锁检查）：实测 tests=20 failures=0 errors=0，无新 fail。

## 5. spec 同步校验

- [x] 5.1 `openspec validate fix-lap-debug-mode-sector-chain-test-after-min-count-1 --strict` → "is valid"（0 error）。
- [x] 5.2 spec scenarios 与实测断言对应已核对：正例 (size==1/index==2/contains IncompleteSectors) == test 改后断言 +
  实测；反例 (严格闭合→size=0→assertEquals(1) fail) 与 baseline 实测 was:0 一致；边界 (sector 完整不打 flag) 由
  生产 LapTimingEngine.kt:191 保证；弱断言反例 (isNotEmpty 放过) 由 Decision 2 改 contains 锁死。

## 6. kt-format 逃课

- [x] 6.1 本 round 仅改既有 `TestSessionViewModelTrackLapTest.kt`（无新增 .kt）。commit 时 kt-format hook
  未拦截 → 无需加 `// @IgnoreFormatCheck`；未用 `--no-verify`。worktree commit `128388a`。

## 10. follow-up backlog（延期立项，防遗忘）

- **`wire-incomplete-sector-hint-to-ui`**（独立主题，非本 round 派生债）：把 sector 不完整提示
  从 in-memory `LapRecord.qualityFlags.IncompleteSectors` 真正接通到用户可见 UI。当前 3 条链路现状
  （已 grep 核实，见 proposal.md Impact）：
  1. 实时横屏 `LapLiveStateDeriver.AbnormalState.LAP_INVALIDATED`（`LapLiveStateDeriver.kt:149-167`）
     的 `invalidatingReasons`（line 63-67）只含 `{WrongDirection, UnexpectedGateOrder, TooSlow}`，
     **不含 sector 不完整** → 实时不提示。需 L0/design 决定：另起"圈有效但有瑕疵"warning 通道
     （不要混进语义为"圈作废"的 LAP_INVALIDATED）。
  2. 详情屏 `UiLapStatus.INCOMPLETE`（`LapSessionDetailScreen.kt:461`）在 `deriveDetailMetrics`
     （line 471-510）**从未被赋值**（派生只产 BEST/VALID/INVALID，不读 qualityFlags） → 死分支。
     需先解决 `IncompleteSectors`（in-memory `LapRecord.qualityFlags`）→ Room `TelemetryCrossingEvent`
     的持久化 gap（当前 crossing 表不持久化 qualityFlags）才能接通详情屏。
  3. 接通后 MUST 复用本 round 锁死的 `qualityFlags.IncompleteSectors` 作权威信号源。
  复杂度预估：medium（触生产 `LapLiveStateDeriver` + 可能 Room 持久化 + UI 设计）→ 走 v3 标准（非加速通道）。
