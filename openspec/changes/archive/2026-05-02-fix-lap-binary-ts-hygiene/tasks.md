## 1. 写入路径覆盖盘点

- [x] 1.1 grep 全工程 `tsDeltaMs\s*=` 与 `gpsData\.timestamp\s*-` 调用，定位所有写入 lap session binary 的入口（不止 `bridgeGpsToLapTiming`）→ 命中 3 处：line 598 LAP（本 round 修），line 415/500 PERFORMANCE_TEST（出 scope，§8.4 backlog）
- [x] 1.2 重点确认 `feature/test/.../viewmodel/SimulatorViewModel.kt` 的 `startReplayDataUpdate` 路径下是否复用 `bridgeGpsToLapTiming` 或另写 tsDeltaMs 计算（design 决策 3）→ 无命中，simulator 端无 lap binary 写入
- [x] 1.3 在 design.md "Open Questions" 末尾追加确认结论：simulator replay 路径处置方式（复用 / 另修）→ 已在 design.md "§1 grep 盘点结论"节追加
- [x] 1.4 grep `activeLapStartSystemTs` 所有引用，确认 lapAnchorTs 当前的语义边界（保留用于 totalDurationMs 等"UI 进入到结束"语义，不再作为 tsDeltaMs anchor）→ 5 处用法：field 声明、selectLapDebugMode、endActiveLapSession、finishActiveLapSession (totalDurationMs)、bridgeGpsToLapTiming（本 round 仅改这一处的 anchor source）

## 2. 核心修复（anchor 同源 + 时钟域单源）

- [x] 2.1 在 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 加只读 property `activeSessionStartTs: Long?`（与现有 activeWriter / activeSessionId 等 field 同区域，未加 @Volatile 保持现有 pattern 一致）
- [x] 2.2 在 `startSession()` 内（实际 line 76 附近，紧跟 activeFilePath 赋值）：`_activeSessionStartTs = startTs`
- [x] 2.3 在 `endSession()` 内（实际 line 134 附近，紧跟 activeFilePath = null）：`_activeSessionStartTs = null`
- [x] 2.4 修改 `feature/test/.../TestSessionViewModel.kt` line 596 附近 `bridgeGpsToLapTiming`（原预期 562，rebase 后漂到 596）：拉 `val sessionStartTs = telemetryRepository.activeSessionStartTs`；仅当非 null 时写 sample（`tsDeltaMs = System.currentTimeMillis() - sessionStartTs`）；null 时走 `FileLogger.e` 警告（FileLogger 无 .w 方法，按工程现有 d/v/e 三档选 e）+ skip telemetry 写入。**未用 `?: return`**——后面 lapTimingEngine.processSample 等照常执行
- [x] 2.5 §1.2 已确认 simulator 无 lap binary 写入入口，本 task 跳过
- [x] 2.6 单元构建验证：`./gradlew :core:data:compileDebugKotlin :feature:test:compileDebugKotlin` BUILD SUCCESSFUL（warnings 均为 baseline pre-existing deprecated icon / unused var，与本 round 无关）

## 3. 单元测试（强合流门槛）

- [x] 3.1 实际包路径定为 `core/data/src/test/java/com/blazepush/core/data/repository/BinaryLapTelemetryRoundTripTest.kt`（与现有 `TelemetryRepositoryEndSessionPersistTest.kt` 同包，复用 Fake DAO + mock Context pattern）
- [x] 3.2 case **A** 实现 + pass（10ms）
- [x] 3.3 case **B**（Codex review §1 关键反例）实现 + pass（5ms）—— 直接 BinaryTelemetryWriter 构造 anchor 错位 binary，验证 readLapSamples 返回 0 帧
- [x] 3.4 case **C** 实现 + pass（13ms）
- [x] 3.5 case **D** 实现 + pass（10ms）
- [x] 3.6 case **E**（Codex review v2 §1）实现 + pass（502ms - mockito init 一次性开销）—— 三相等 entity.startTs / activeSessionStartTs / header.startTs
- [x] 3.7 case **F** 实现 + pass（6ms）
- [x] 3.8 case **G**（writer flush 后未 close 即 read）实现 + pass（16ms）
- [x] 3.9 `./gradlew :core:data:testDebugUnitTest --tests "*BinaryLapTelemetryRoundTrip*"` —— **7 tests / 0 failures / 0 errors，总耗时 0.563s，合流强门槛通过**

## 4. grep 自检（spec 第 5 个 Scenario）

- [x] 4.1 grep 跨时钟域减法模式 → empty ✅
- [x] 4.2 grep anchor 错位模式 → empty ✅
- [x] 4.3 grep `tsDeltaMs =` 命中 5 处：line 605（修复后正确公式 `currentTimeMillis - sessionStartTs`）+ line 415/500（PERFORMANCE_TEST 路径，出本 round scope，已沉淀 §8.4 backlog）+ GpsBinaryFormat.kt 2 处（sample decoder 内部赋值与 anchor 无关）
- [x] 4.4 grep `activeSessionStartTs` 命中 9 处：TelemetryRepository.kt 4 处（property + 赋值 + 清空）+ TestSessionViewModel.kt 5 处（注释 + 消费 + 错误日志）

## 5. OpenSpec 工件自检

- [x] 5.1 `openspec validate fix-lap-binary-ts-hygiene --strict` ✅ valid
- [x] 5.2 工件四件齐全：proposal / design / specs/binary-telemetry-storage/spec.md / tasks
- [x] 5.3 三个 deferred memo 都在：`laptime-ts-hygiene-deferred.md`（起源）+ `lap-crossing-clock-hygiene-deferred.md`（v2 scope 收紧）+ `perftest-binary-ts-hygiene-deferred.md`（apply §1 新发现）
- [x] 5.4 `parallel-change-collab.md` §5 row A 状态更新到"实施中"+ row C 标 done（已合回 dd01aeb + 归档 3452003）+ §6 追加 A 的 2 条共享文件登记（TelemetryRepository.kt + TestSessionViewModel.kt）

## 6. 真机不回归验证（弱合流门槛）

> 强门槛是 §3 单元测试全绿；本节真机仅做"不回归"健康检查，不再以 detail 屏 TOP SPEED 作为本 change 生效证据（detail 走 `readPerformanceSamples` quick fix 不变）。

- [~] 6.1-6.5 **user 拍板跳过真机不回归**（2026-05-02）。理由：本 round 是 baseline 写入路径修复，下游 UI 路径已全被 F/I round 绕开（detail 屏走 entity.topSpeedKmh、圈速秒表走 LapTimingEngine、DELTA 走 RealtimeDeltaCalculator），都不依赖 readLapSamples 窗口过滤；F/I 已在 cda8675/fe1a989 真机验证过同路径，本 round 没动 detail 屏 / lap engine / DELTA 任何 UI；功能正确性证据由 §3 7 cases 单测覆盖（commit 历史可审）。如未来引入 sector 分段 / Analysis Mode 单圈轨迹消费 readLapSamples，再做端到端真机验证
- [x] 6.6 不做临时切回试验

## 7. 合回 + Codex review

- [x] 7.1 commit：`b03d3b9` (核心修复) + `61b6550` (rebase 衍生 Fake DAO stub 补齐) + `daca418` (消化 Codex review §1 P2 grep gate case H)
- [x] 7.2 ff-only 合回 `feature/track-tech-v2` → 主区 HEAD `daca418`；3 个 commit 累计 4 文件 +422 -8；主区合回态 8 cases 全绿（0.627s）
- [~] 7.3 `git pull --rebase` —— 本工程无 origin tracking 习惯，跳过
- [x] 7.4 Codex review pass（**没有 P1/P0 阻塞**；P2 grep gate 建议已消化，case H 已合回主区 daca418）
- [x] 7.5 看板 §5 row A 状态更新为 done（最近合回 commit = daca418）
- [x] 7.6 push 顺序 user 拍板（"先 push 其他 27 commits 后 push A 3 commits"）；两次 push 都过远端 kt-format-checker：`6070ef5..fe1a989`（其他）+ `fe1a989..daca418`（A）
- [x] 7.7 清理完成：`git worktree remove` + `git branch -d feature/fix-lap-binary-ts-hygiene`

## 8. follow-up backlog

- [x] 8.1 已沉淀 backlog（不在本 round scope 实施）：`LapSessionDetailScreen` 回切 `readPerformanceSamples` → `readLapSamples` 的 cleanup round，待 user 拍板时机立项
- [x] 8.2 已沉淀 backlog：未来"双端时钟同步分析"`dual-clock-telemetry-correlation` round 触发时机 + 设计要点都在 design.md "Trade-offs"
- [x] 8.3 已沉淀 deferred memo `docs/design/lap-crossing-clock-hygiene-deferred.md`（9 章），下次 `/opsx:ff fix-lap-crossing-clock-hygiene` 直接立项
- [x] 8.4 已沉淀 deferred memo `docs/design/perftest-binary-ts-hygiene-deferred.md`（9 章），下次 `/opsx:ff fix-perftest-binary-ts-hygiene` 直接立项
