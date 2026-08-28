## 1. 写入路径覆盖盘点（apply 第一步必跑）

- [x] 1.1 grep `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` 内所有 `tsDeltaMs\s*=` 命中，verify 当前主区行号锚点（v3 工件预期 line 643 + line 728，rebase 后再 grep 防漂移）→ 实测 line 643 + 728 + 833（A round 已修），对齐
- [x] 1.2 grep `activeTestStartTs` → 实测 4 处（line 283 + 639 + 722 + 983），未发现额外下游派生引用，本 round 修复后字段仍保留语义
- [x] 1.3 baseline 跨文件 grep `tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-` 在 feature/test/src/main/java 命中 2 处（line 643 + 728），全部在 TestSessionViewModel.kt
- [x] 1.4 simulator/src/main 下无任何 .kt 命中 tsDeltaMs/writeSample；feature/test 下不存在 SimulatorViewModel.kt → simulator 路径无 PERFORMANCE_TEST binary 写入入口
- [x] 1.5 §1.1-§1.4 grep 结论已回填 design.md "§1 grep 盘点结论"节
- [x] 1.6 TelemetryRepository.startSession 函数体内 `System.currentTimeMillis()` 仅 1 处（line 67），同一 startTs 赋给 entity.startTs + writer header + activeSessionStartTs → 三字段同源 invariant 满足
- [x] 1.7 case F helper 路径解析 verify（apply 期实施 §3.7 case F 前必跑）：打印 `locateTestSessionViewModelFile().parentFile.parentFile.parentFile.parentFile.parentFile.parentFile.absolutePath`，verify 终点是 `.../feature/test/src/main/java`；不正确表明 ViewModel 位置 / 包路径变化，调整 .parentFile 链路层数到对齐

## 2. 核心修复（anchor 同源 + 时钟域单源）

- [x] 2.1 修改 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:processFilteredData` 内 `TestState.Running` 分支（v3 工件预期 line 638-651，rebase 后实际定位）：
  - 删除 `val anchorTs = activeTestStartTs` + 该 anchor 的 if 守卫
  - 改为：`val sessionStartTs = telemetryRepository.activeSessionStartTs`
  - 仅当 `sessionStartTs != null` 时调 `writeSample`，每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs`
  - null 分支：`FileLogger.e(TAG, "...missing activeSessionStartTs...")` 警告日志（FileLogger API 仅有 d/v/e，无 w；error 级与 A round line 843 同 pattern）+ skip telemetry 写入
  - **MUST** 保留 `state.session.addFilteredDataPoint(filteredData)` 在 if 块**之前**（原行号 638 的位置不动）+ `if (state.session.template.shouldEnd(filteredData.raw)) { finishTest(state.session) }` 在 if 块**之后**照常执行（不阻塞 finishTest 触发；与 A round 决策 1 同 pattern——bridge null 分支不阻塞下游状态机）
  - **MUST NOT** 用 bare `?: return` 处理 null 分支（提前 return 会让 shouldEnd / finishTest 漏判）
- [x] 2.2 修改同文件 `startTest` 内 pre-trigger buffer 回填段（v3 工件预期 line 720-734）：
  - 保留 `val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp` 与 `activeTestStartTs = anchorTs`（语义不变，仍用作 0-100 计时显示等派生）
  - 在 `telemetryRepository.startSession(PERFORMANCE_TEST)` 调用之后，新增 `val sessionStartTs = telemetryRepository.activeSessionStartTs`
  - 把 for 循环用 `if (sessionStartTs != null) { for (frame in lockedPreTriggerBuffer) { writeSample(...) } }` 包起来，循环内每帧 `tsDeltaMs = System.currentTimeMillis() - sessionStartTs`
  - null 分支：`FileLogger.e(TAG, "...missing activeSessionStartTs after startSession...")` 警告 + 整个 for 循环跳过（不写 telemetry）
  - **注**：preTrigger 回填段位于 startTest 函数末尾，循环跳过后只剩 `FileLogger.d` 调试日志，不阻塞 `_testState = Running`（已设置在 line 717）+ 后续 GPS 帧驱动状态机进入 Running 分支
- [x] 2.3 §1.4 已确认 simulator 无 PERFORMANCE_TEST binary 写入入口，本 task 跳过
- [x] 2.4 编译验证：`./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL（7s）
- [x] 2.5 lint 验证：本地无 ktlint/spotless/ktfmt Gradle task（`ktFormatCheckMain` 不存在）—— kt-format-checker 是远端 hook，push 前由远端检查；本地编译 BUILD SUCCESSFUL 无新增 warning，本 task 仅做"编译 + 无新 warning"代理验证（避开 v3 高频盲点 #1—— 不禁用任何 hook）

## 3. 单元测试（强合流门槛）

- [x] 3.1 实际包路径定为 `core/data/src/test/java/com/blazepush/core/data/repository/BinaryPerftestTelemetryRoundTripTest.kt`（与 A round `BinaryLapTelemetryRoundTripTest.kt` 同包，复用其 Fake DAO + mock Context pattern + projectRoot helper 模式；apply §3.1 第一步先 `find core/data/src/test -name "BinaryLap*.kt"` 确认实际路径与 helper 入口）
- [x] 3.2 case **A**（PERFORMANCE_TEST round trip 在 anchor 同源下窗口过滤命中）：startSession → 拉 sessionStartTs → 连续 N=10 帧 `tsDeltaMs = currentTimeMillis - sessionStartTs` 写入 → endSession → readPerformanceSamples 返回 N 帧，所有 absoluteTs ∈ `[entity.startTs, entity.endTs + 100ms]`
- [x] 3.3 case **B**（anchor 错位反例 — 直接构造 writer 模拟跨时钟域偏移）：直接 `BinaryTelemetryWriter.open(path, type=PERFORMANCE_TEST, startTs=10000)` 构造文件，写入 100 帧 `tsDeltaMs = (i × 40) + 5000`（额外 +5000 模拟跨时钟域偏移）→ `readLapSamples(filePath, 10000, 14000)` → assert `samples.size == 0`（reader 把窗外样本全部剔除是正确行为）。**反例语义**：本 case 锁死 reader 行为；如果生产代码 anchor 仍跨时钟域偏移，case A 的 happy assert `samples.size == N` 会从 N 跌到 0，bug 被捕获
- [x] 3.4 case **C**（preTrigger buffer 回填帧 absoluteTs 集中在 startTest 内部窗口）：模拟 startTest 流程，构造 N=25 帧 lockedPreTriggerBuffer，循环内每帧 `tsDeltaMs = currentTimeMillis - sessionStartTs` 写入 → 验证所有 25 帧 absoluteTs ∈ `[entity.startTs, entity.startTs + 100ms]`（不要求散布在 buffer 实际时长，spec 接受语义）
- [x] 3.5 case **D**（持续写 absoluteTs 分布真实 — 直接构造 BinaryTelemetryWriter，deterministic 无睡眠）：直接 `BinaryTelemetryWriter.open(path, type=PERFORMANCE_TEST, startTs=20000)` 构造文件，写入 100 帧 `tsDeltaMs = i * 40`（精确 40ms 间隔，0..99）→ close → readPerformanceSamples 验证 (a) 相邻 absoluteTs 间隔 == 40ms（精确，因为是直接写，不依赖 Thread.sleep / 合成时戳）(b) 全部样本 absoluteTs ∈ `[20000, 20000 + 99 * 40]`。**反 case G dead spec 风险**：本 case 不 mock 任何 ViewModel / FileLogger / repository，只验证 reader 行为——单测 deterministic 且毫秒级跑完
- [x] 3.6 case **E**（时钟域单源 grep 自检）：写测试 helper 跑两类禁止 pattern grep：(1) `filteredData\.timestamp\s*-\s*(anchorTs|activeTestStartTs)` (2) `frame\.timestamp\s*-\s*anchorTs` —— 在 `TestSessionViewModel.kt` 内 → 两类命中数均 == 0（baseline 修复前各 1，修复后归零）；同时 grep 正向 pattern `System\.currentTimeMillis\(\)\s*-\s*sessionStartTs` 在 `TestSessionViewModel.kt` 内命中数 **== 3**（精确锁定：A round 已加的 bridgeGpsToLapTiming line 833 + 本 round startTest preTrigger 回填 + 本 round processFilteredData TestState.Running 分支；命中数偏离 3 即表明 A round 入口被改 / 本 round 漏改某入口）。本 case 用与 A round case H 同 pattern 的 `locateTestSessionViewModelFile()` helper 解析 source path（候选列表 `File("../$relPath"), File("../../$relPath"), File(relPath)`），避开 v3 高频盲点 #10 working dir caveat
- [x] 3.7 case **F**（跨文件 grep gate 防漏改 + 防扫错路径假性绿）：用 helper 解析到 `feature/test/src/main/java` root（如 `locateTestSessionViewModelFile().parentFile.parentFile.parentFile.parentFile.parentFile.parentFile`，上溯到 `.../feature/test/src/main/java`），递归遍历 .kt 文件并 MUST 排除：(a) 路径含 `/src/test/` 的文件 (b) 路径含 `/.worktrees/` 的文件；对剩余文件 grep `tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-` → assert (1) 命中数 == 0 (2) 扫到的 .kt 文件总数 ≥ 30（防 helper 跳到空目录假性绿，对应 v3 高频盲点 #8）
- [x] 3.8 case **G**（anchor 缺失降级 — 改为源码 grep gate 形态对齐验证）：原计划 mock-based 单测**不可行**（processFilteredData private + activeSessionStartTs final var private set + FileLogger object 单例 + core/data 无 mockito-inline）。改为源码 grep gate（与 A round case H 同 pattern）：用 helper 加载 `TestSessionViewModel.kt` 文本（`locateTestSessionViewModelFile()`），验证三类形态。**实现 hint**（multi-line 形态用 `Regex.findAll` 取行号 + 行号距离断言）：
  - **形态 A**（processFilteredData TestState.Running 分支）：grep `val sessionStartTs = telemetryRepository.activeSessionStartTs` 在 processFilteredData 函数体范围内命中 1 次（行号记 P1）；grep `if \(sessionStartTs != null\)` 在 processFilteredData 范围内命中 1 次（行号记 P2）；assert `P2 - P1 ∈ [0, 5]`（紧随）；grep 包围 `} else \{\s*FileLogger\.e\(TAG,\s*"processFilteredData: missing activeSessionStartTs` 在 processFilteredData 范围内 ≥ 1 次（行号记 P3）；assert `P3 - P2 ∈ [3, 15]`（15 行窗口含 writeSample 调用 + 参数列表，与 processFilteredData 36 行函数体大小对齐避免上界过松）；grep `if \(state\.session\.template\.shouldEnd\(filteredData\.raw\)\)` 在 processFilteredData 范围内命中 1 次（行号记 P4）；**P4 锁死断言**：`P4 > P3 + 1`（即 shouldEnd 必须出现在 else block 之后；防 bare `return@Running` / `return` regression——若有人把 fallback 写成 `if (sessionStartTs == null) { FileLogger.e; return }` 早返回，shouldEnd 漏判 finishTest 永不触发，case A round trip 会卡在 endSession 永不调用导致 reader 读不到样本——但 case A happy path sessionStartTs != null 不进入 else 分支，dead spec 残留必须靠 P4 grep gate 锁死）
  - **形态 B**（startTest preTrigger 回填）：同 pattern，在 startTest 函数体范围内 grep `val sessionStartTs = telemetryRepository.activeSessionStartTs` + `if \(sessionStartTs != null\) \{[\s\S]*?for \(frame in lockedPreTriggerBuffer\)` + `} else \{\s*FileLogger\.e\(TAG,\s*"startTest preTrigger backfill: missing activeSessionStartTs` 各 ≥ 1 命中
  - **形态 C**（本 round scope 内 FileLogger.e 调用次数 — 严格收紧不含 A round 历史字符串）：grep `FileLogger\.e\([^)]*(processFilteredData|startTest preTrigger backfill)[^)]*missing activeSessionStartTs` 在该文件内命中数 == 2。**注**：A round 已加的 `bridgeGpsToLapTiming: missing activeSessionStartTs after startSession, ...`（line 845）通过 `processFilteredData|startTest preTrigger backfill` 函数前缀 anchor 排除——baseline 0 命中（修复前）→ 修复后 == 2，是非 trivially-pass 的有效 grep gate
- [x] 3.9 case **H**（activeTestStartTs 字段两步赋值语义未被破坏 — 改为两段源码 grep，与生产实际两步赋值形态对齐）：
  - **第一段**：grep `val anchorTs\s*=\s*lockedPreTriggerBuffer\.firstOrNull\(\)\?\.timestamp\s*\?:\s*filteredData\.timestamp` 在 `TestSessionViewModel.kt:startTest` 函数体范围内命中 1 次（赋值表达式保留协议时间语义）
  - **第二段**：紧随第一段的 `activeTestStartTs\s*=\s*anchorTs` 在 startTest 范围内命中 1 次（行号距第一段 ≤ 3 行）
  - **第三段**：grep `activeTestStartTs\s*=\s*null` 在 finishTest 函数体内命中 1 次（清空保留）。**注**：实测当前生产代码 line 721-722 是两步赋值 `val anchorTs = ...; activeTestStartTs = anchorTs`，不是单行直接赋值——本 case 的 regex 与生产形态严格对齐（避开 v3 高频盲点 #3 / #12 形态描述错）
- [x] 3.10 `./gradlew :core:data:testDebugUnitTest --tests "*BinaryPerftestTelemetryRoundTrip*"` 全绿（合流强门槛；预期 8 cases 全 pass）

## 4. grep 自检（spec Scenario 5/6 验证）

- [x] 4.1 跑 spec Scenario 5 grep 模式（在 `TestSessionViewModel.kt` 内）→ 两类禁止模式均 0 命中 + 正向 `System.currentTimeMillis() - sessionStartTs` 在 PERFORMANCE_TEST 入口 ≥ 2 处命中
- [x] 4.2 跑 spec Scenario 6 跨文件 grep 模式（`feature/test/src/main/java` 排除 src/test 与 worktree） → 0 命中 + 扫到 .kt 文件数 ≥ 30
- [x] 4.3 复跑 §1.3 baseline grep（`tsDeltaMs\s*=\s*[a-zA-Z_]+\.timestamp\s*-`）→ 0 命中（修复前应命中 2 处，修复后归零）
- [x] 4.4 跑 spec Scenario 7 形态对齐 grep：(A) processFilteredData fallback 形态 (B) startTest fallback 形态 (C) 本 round scope 收紧版 `FileLogger\.e\([^)]*(processFilteredData|startTest preTrigger backfill)[^)]*missing activeSessionStartTs` 命中数 == 2（A round 历史的 line 845 通过函数前缀 anchor 排除）
- [x] 4.5 grep `activeSessionStartTs` 在 `TestSessionViewModel.kt` 内引用数（修复后应 ≥ 6 处：A round bridgeGpsToLapTiming 拉取 + 错误日志 line 845 ≥ 2 处 + 本 round processFilteredData 拉取 + startTest 拉取 + 2 处错误日志 = ≥ 6 处）

## 5. OpenSpec 工件自检

- [x] 5.1 `openspec validate fix-perftest-binary-ts-hygiene --strict` ✅ valid
- [x] 5.2 工件四件齐全：`proposal.md` / `design.md` / `specs/binary-telemetry-storage/spec.md` / `tasks.md`
- [x] 5.3 deferred memo 同步：`docs/design/perftest-binary-ts-hygiene-deferred.md` 加 "本 round 起源" 节链回 `openspec/changes/fix-perftest-binary-ts-hygiene/`，与 v3 高频盲点 #15 对齐（memo 与工件同步）；本 round apply 期若决策有调整需同步更新 memo
- [x] 5.4 看板更新：`docs/implementation-design/parallel-change-collab.md` §5 加本 round 行（独占路径 + 依赖 + 状态推进中），§7 Phase 0 round 列表把 §8.3 fix-lap-crossing-clock-hygiene 状态从"已 ff 立项 待 apply"改为"已归档 archive/2026-05-03-fix-lap-crossing-clock-hygiene"（看板信息过时勘误）
- [x] 5.5 验证 spec.md ADDED Requirements 与 A round 已加的 LAP_SESSION requirement 平行存在不冲突（ADDED 不改写 A round 已加部分）

## 6. L1 adversarial review（小复杂度 1-2 轮）

- [x] 6.1 工件全集准备就绪后调独立 Opus 子 agent 跑 L1 adversarial review（参 `docs/templates/adversarial-review-prompt.md` 模板）
- [x] 6.2 review 重点查 v3 高频盲点列表（CLAUDE.md "v3 高频盲点列表"节）：#3 grep pattern 形态对齐 / #4 测试 working dir caveat / #5 3-class 字段对应 / #7 grep gate 防 trivially pass / #8 跨文件 grep gate 防扫错路径 / #11 跨 module file IO 边界 / #15 memo 与工件同步
- [x] 6.3 消化 review P0/P1 修订到工件；P2 改进按取舍纳入或备注
- [x] 6.4 plateau 判定（参 CLAUDE.md "Plateau 判定"节）：§A 上轮 P0/P1 全到位 + §B grep pattern + 行号 + DSL 形态实测对齐生产代码 + §C/§D 无新 P0/P1 → 放行 apply

## 7. 真机不回归验证（弱合流门槛 — 可接受 SKIP）

> **caveat（L1 review v2 揭示）**：真机条款**不能验证本 round 修复生效**——SpeedCurve / GForceChart / 0-100 计时显示等当前下游消费方都不走 binary 窗口过滤路径，即使本 round 修复没生效真机仍会全过（与 A round Codex review §3 揭示的 "quick fix 路径绕过修复点" 同模式）。本节是**弱门槛、可接受 SKIP**——user 拍板是否走真机；功能正确性证据完全依赖 §3 单测 8 cases + §4 grep gate。
>
> 强门槛是 §3 单元测试 8 cases 全绿；本节真机仅做"测试主流程不回归"健康检查（验证修复**没破坏**现有 PERFORMANCE_TEST 主流程，不验证修复**生效**）。准备真机时 MUST 在对话窗口告知 user 当前 round / apk / 验证场景，等 user 授权再 install。

- [x] 7.1 编译并 install apk 到华为 8KE0219522008434（user 授权后再执行）
- [x] 7.2 跑一次 0-100 加速测试场景：触发 PERFORMANCE_TEST → 加速到 100 km/h → 测试自动结束 → 测试结果页正常打开
- [x] 7.3 测试结果页 SpeedCurve 折线图显示正常（不空、不全 0、与历史 BEST 模式一致）
- [x] 7.4 测试结果页 GForceChart 加速度曲线显示正常（与 §3 round trip 单测断言的 absoluteTs 间隔一致，~40ms 平滑曲线）
- [x] 7.5 Records → PERFORMANCE 子 tab 历史记录列表能看到本次新测试 + 进入详情屏不空
- [x] 7.6 Records → LAPS 子 tab 不受影响（本 round 不改 LAP_SESSION 路径）
- [x] 7.7 验证完成在对话窗口报告结果，看板状态从"验证中"改回"待合回"

## 8. 合回 + Codex review

- [x] 8.1 主区直改不需 worktree；`./gradlew :core:data:testDebugUnitTest :feature:test:testDebugUnitTest` BUILD SUCCESSFUL（apply 期 + L2 期强制重跑 8/8 全绿）
- [x] 8.2 单合并 commit `76a2735`（scope 极小：核心修复 24 行 + 单测 547 行 + .openspec.yaml 2 行；user 已授权；kt-check pre-commit 拦截违规已 patch 修复后通过）
- [~] 8.3 主区直改不需 rebase（直改 feature/track-tech-v2，A/F/I/J/K/L 等已合回归档）
- [~] 8.4 同 8.3 跳过
- [~] 8.5 主区直改即合回，无需 ff-only merge
- [x] 8.6 主区合回态编译 + 测试 BUILD SUCCESSFUL（commit 后强制重跑确认）
- [x] 8.7 看板 §5 + §7 状态已更新到"已归档 + 待 Phase 0 Exit Review + push"
- [~] 8.8 Codex 当前 3 天不可用，**deferred**：Codex 恢复后由 user 触发独立 review；本期改为 Opus 子 agent 跑 L2 单线
- [~] 8.9 同 8.8 deferred；L2 单线 review 0 P0/P1 + 3 P2 文档同步残留已消化（proposal 4→8 case + 看板 §7 状态描述 + 归档 commit body 引用 76a2735）
- [x] 8.10 user 拍板归档（push 顺序留待 user 拍板，独立于本归档 task）
- [x] 8.11 `/opsx:archive` 执行（含 metrics.yaml 生成）
- [~] 8.12 主区直改无 worktree / 无单独 feature 分支需清理

## 9. Phase 0 Exit Review（本 round 闭环后触发）

> 本 round 是 Phase 0 唯一剩余 round。归档后 MUST 跑 Phase Exit Review（参 `docs/implementation-design/parallel-change-collab.md` §7 流程）。

- [ ] 9.1 盘点 Phase 0 期间产生的所有 deferred memo 决议（disposition：下个 phase 内闭环 / 推迟 / 移除）
  - `docs/design/laptime-ts-hygiene-deferred.md`（A round 起源）—— A 已闭环，标 done
  - `docs/design/lap-crossing-clock-hygiene-deferred.md`（A round v2 收紧产物）—— §8.3 已闭环（archive/2026-05-03-fix-lap-crossing-clock-hygiene），标 done
  - `docs/design/perftest-binary-ts-hygiene-deferred.md`（A round §1 grep 产物）—— 本 round 闭环，标 done
  - `docs/design/laptime-gps-filter-integration-deferred.md`（B round）—— 决议是否 Phase 1 内或推迟到 Phase 1 后
  - `docs/design/speed-curve-real-data-persistence-deferred.md`（F round）—— 决议是否 Phase 1 内或合并入 Phase 1 chart 数据契约 round
  - `docs/design/perftest-cascade-orphan-cleanup-deferred.md`（J round）—— cleanup 类，决议推迟到 release 前还是 Phase 1 内
  - `docs/design/records-by-track-filter-deferred.md`—— UI/查询 layer，决议推迟到 Phase 1 内或之后
- [ ] 9.2 生成 Phase 0 exit commit `chore(phase): Phase 0 exit review`，body 含本 phase 所有 deferred memo disposition + 总估时 vs 实际 retrospective
- [ ] 9.3 看板 §7 Phase 0 节追加 exit commit 引用 + 状态改为 done
- [ ] 9.4 在 `~/.claude/projects/-Users-wattledgnata-traeProjects-gps-app/memory/` 加一条 project memory 记录 "Phase 0 已闭合（2026-05-03 onwards）"，保留下个 phase 立项时引用

## 10. follow-up backlog

- [x] 10.1 `PreTriggerFrame` 加 `receivedAtWallClockMs` 字段（方案 B）—— 触发时机：未来出现"加速度时间序列高保真回放"明确需求时单独立项；当前 spec Scenario 已显式承认 ms 级集中度作为可接受语义，时间窗够再做。建议立项名 `add-pretrigger-frame-wallclock-anchor` 或类似
- [x] 10.2 `LapSessionDetailScreen` quick fix 回切（`readPerformanceSamples` → `readLapSamples` 真壁钟窗口路径）—— 触发时机：Phase 1 单圈 chart cursor 拖动 round 起步，把 detail 屏 chart 数据来源切到窗口过滤 readLapSamples；与 A round §8.1 backlog 同一项
- [x] 10.3 未来"双端时钟同步分析"`dual-clock-telemetry-correlation` —— 触发时机：分析双端时钟漂移 / RaceChrono 协议时间与本地接收时刻关联性需求出现时，新加 capability，binary 字段不动，与 A round §8.2 backlog 同一项
