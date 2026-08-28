# 多 Change 并行协同看板（本地专属约定）

> 本文件由 `.git/info/exclude` 的 `*.md` 规则自动排除，**不进远端 git**，仅本地有效。
> 单 change 线性开发时无需使用；启动多 change 并行时在此登记并遵循以下原则。
>
> **范本来源**：参考 `aistudyclient_creativepaint` 工程 §11 节落地的多 change 并行机制（2026-04-30 起验证），适配 gps-app 当前架构与 round backlog。

---

## 背景

**术语约定**：
- **round**：一个 OpenSpec change，并行的最小工作单元
- **session**：一次 Claude Code 对话窗口本身，每个 session 专注一个 round
- **worktree**：每个并行 round 的独立 git working tree，避免 Gradle 增量编译串台

`add-lap-session-phase1` round 闭环后，沉淀出 4 个 follow-up round，按文件边界研判后**两条并行通道 + 一条收尾路径**：

```
通道 1（数据流路径）：    A fix-lap-binary-ts-hygiene  →  B wire-laptime-to-gps-filter
                          （同 bridgeGpsToLapTiming 函数体，必须串行）

通道 2（持久化路径）：    C persist-session-summary-fields
                          （独立 entity / repository / detail，不与 A/B 交叉）

收尾（风格统一）：        D track-tech-v2-style-debt-cleanup
                          （与 A/B/C 全交叉，最后做）
```

---

## 1. 核心约定

- **任务单元**：并行最小单位是一个 OpenSpec change（一个 round），每个 round 在独立 Claude Code session 推进。
- **编译沙盒**：每个并行 round 各开一个 worktree，互不干扰 Gradle 增量编译。
- **快合回**：worktree 只承担"正在改且还不稳定"的小段，完成可独立编译的里程碑立即 ff-only 合回 `feature/track-tech-v2`，主工作区 `git pull --rebase` 跟上。
- **Review 主干**：Codex review 看合回主干的 commit，不看 worktree 半成品。
- **OpenSpec 强制流程不变**：每个 round 仍走 `/opsx:ff` → 用户拍板 → `/opsx:apply` → Codex review。
- **工件 source-of-truth 在主区**：OpenSpec 工件（proposal / design / specs / tasks）目录统一落主工作区 `openspec/changes/<round>/`，**不**在 worktree 内维护。worktree 内只放代码 + 单元测试。`/opsx:ff` 启动 round 时在主区跑（cwd 主工作区），不在 worktree 内跑。**理由**：(1) Codex / user review 看主区一处不需要在多个 worktree 间切换 (2) 工件改动跟代码 commit 解耦，工件 review 期间不阻塞 worktree 内代码推进 (3) 防止 worktree 与主区双份 .md 漂移

---

## 2. 文件边界

- 每个 round 启动时在 §5 声明**独占路径**，其他 round 不越界修改。
- 跨 round 共用的文件（`gps-app/CLAUDE.md`、`AndroidManifest.xml`、本看板文件自身、`docs/design/laptime-*-deferred.md` 等）修改前在 §6 登记，完成后改 done。
- **独占路径内的改动无需登记。**

典型共享文件（gps-app 当前已知）：
- `gps-app/CLAUDE.md`
- `app/src/main/AndroidManifest.xml`（本 V2 round 已规则零改动）
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（A/B 同函数；C 也可能改 endSession）
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`（C 改 endSession 写字段；D 可能加 KDoc）
- 本看板文件本身

---

## 3. 合回主干 checklist

> 顺序不可乱：未 commit 时不能 rebase。

1. worktree 内 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest :app:compileDebugKotlin` 通过
2. 按功能单元独立 `git commit`（取得 user 授权；不 `--amend`；不 `--no-verify`）
3. `git fetch origin && git rebase feature/track-tech-v2`（有冲突就地解决）
4. rebase 后再次跑编译 + 测试
5. 切回主工作区：`git checkout feature/track-tech-v2 && git merge feature/<round-name> --ff-only`
6. 主区编译确认合回态通过
7. `git diff --stat HEAD~1..HEAD -- feature/ core/` 验证 diff 边界符合预期
8. 更新 §5 对应 round 的状态与最近合回 commit 字段
9. 将 §6 本次共享文件占用标为 done
10. 真机装机验证（华为 8KE0219522008434）—— 见 §4.2 串行规则
11. 提醒 user 触发 Codex review；review 通过后 user 拍板 push 顺序（§4.1）

---

## 4. 启动新 round 的前置检查

**每次开启新 round 之前，必须先看 §5 登记表**，研判新 round 的独占路径与所有**未闭环 round** 是否存在文件交叉：

- 若无交叉 → 直接开 worktree 推进
- 若有交叉 → 明确协商处理方式：
  - 调整其中一个 round 的文件边界（避开）
  - 约定谁先合回再动该文件（串行局部）
  - 合并为同一个 round 推进

**不允许在未完成交叉研判的情况下直接开码。**

---

## 4.1 round 间依赖与 push 顺序

若新 round 依赖另一个未闭环 round 的输出（接口 / 类型 / 数据 schema），需在启动时声明依赖关系：

- **等依赖方先合回再开码** — 适合依赖尚未稳定的情况
- **先用 Fake / 接口占位开码，依赖合回后再接线** — 适合两个 round 可并行推进但最终需要接线

多个 round 同时就绪准备 push 时，**push 顺序由 haozhang93 决定**，不得自行决定顺序。原因：远端 kt-format-checker hook 对 push 历史逐条验证，顺序错误或依赖倒置会导致整批 reject。

---

## 4.2 真机安装验证规则（串行强制）

**需要真机安装验证（`adb install` / `:app:installDebug`）的 round，不允许与其他 round 并行验证**，必须串行执行。

原因：真机同一时刻只能装一个 apk，并行验证会互相覆盖，无法判断问题归属。

执行规则：

1. 准备真机验证前，session **必须在对话窗口中明确告知 haozhang93**：当前 round ID、即将安装的 apk 与设备、要验证的场景列表，**等 haozhang93 明确授权后再执行 `adb install`**
2. 同时在 §5 登记表中将该 round 状态改为 `验证中`
3. 验证完成后将状态改回 `推进中` 或 `待合回`，并在对话窗口告知结果
4. 其他 round 的真机验证需等待当前验证完成 + 收到 haozhang93 放行后才能开始

**默认设备**：
- 接收端真机：华为 `8KE0219522008434`
- Simulator 设备：T40 `DP011011255100142`

---

## 5. 当前并行 round 登记

> 无并行任务时此节为空。启动新并行 round 时追加行，闭环后标 done 并清理 worktree。
> 状态值：`推进中` / `验证中` / `待合回` / `待 push` / `done`

| Round ID | worktree 路径 | 分支 | 独占路径（概要） | 依赖 round | 状态 | 最近合回 commit |
|---|---|---|---|---|---|---|
| **A. fix-lap-binary-ts-hygiene** | `.worktrees/fix-lap-binary-ts-hygiene`（待清理） | `feature/fix-lap-binary-ts-hygiene`（待清理） | `feature/test/.../viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming`（rebase 到 fe1a989 后实际 line 795 附近）+ `core/data/.../repository/TelemetryRepository.kt`（加 `activeSessionStartTs` 只读 property + startSession/endSession 各 1 行赋值/清空）+ `core/data/src/test` 新增 `BinaryLapTelemetryRoundTripTest.kt`（**8 cases** 全绿，含 case H 源码 grep gate）；详细设计 `docs/design/laptime-ts-hygiene-deferred.md` + Codex review v2/v3/post-merge 修订（anchor 同源 + scope 收紧到 session 窗口 + grep gate 防回退）；apply §1 grep 还沉淀 `perftest-binary-ts-hygiene-deferred.md` follow-up（PERFORMANCE_TEST 路径同 bug） | C/F/I 已合回 → A rebase 完成 → 8 cases 全绿（已闭合） | **done**（Codex review pass + 消化 P2 grep gate；合回 b03d3b9 + 61b6550 + daca418 到 feature/track-tech-v2，主区合回态 8 cases 全绿；user 拍板跳过真机——下游 UI 全被 F/I 绕开不依赖窗口过滤；待清理 worktree + 分支 + push 顺序由 user 拍板） | daca418 |
| **B. wire-laptime-to-gps-filter** | `.worktrees/wire-laptime-to-gps-filter` | `feature/wire-laptime-to-gps-filter` | `feature/test/.../viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming`（接 GpsDataFilter 仅替换 speed/bearing，lat/lon 保持 raw——filter 的 isPositionAnomaly 判定会把 gate 过线位置跳变误标为异常）+ `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 阈值 3→1 + `LapFilterIntegrationTest` 5 cases + `LapLiveStateDeriverTest` 调整去抖 expected 值；详细设计 `docs/design/laptime-gps-filter-integration-deferred.md` | 无（A 已合回） | **done**（合回 e2f4417，待 Codex review + push） | e2f4417 |
| **C. persist-session-summary-fields** | `.worktrees/persist-session-summary-fields`（待清理） | `feature/persist-session-summary-fields`（待清理） | `core/data/.../entity/TelemetrySessionEntity.kt`（加 `topSpeedKmh/trackId/trackNameSnapshot` 三字段）+ Room migration（schema v3→v4）+ `core/data/.../repository/TelemetryRepository.kt:endSession`（写入字段 + IO 派生）+ `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`（读 entity 不再每次扫 binary）+ `feature/test/.../ui/tracktech/RecordsHomeScreen.kt:LapsView`（trackId 解析回 Track）+ Room migration 单元测试；动机：(1) detail 屏 top speed 跨 session 切换 ~50ms 加载浪费 (2) lapCount/bestLapMs Room 字段未回写 (3) detail 历史 session 误读当前 currentSelectedTrack（Codex P2.4 review 提出） | 无 | **done**（合回 dd01aeb + 归档 3452003，2026-05-01） | dd01aeb |
| **D. track-tech-v2-style-debt-cleanup** | `.worktrees/track-tech-v2-style-debt-cleanup` | `feature/track-tech-v2-style-debt-cleanup` | enhance-track-presentation + add-lap-session-phase1 期间加 `// @IgnoreFormatCheck` 文件级豁免的所有 .kt 文件（11 个生产 + 测试）：补 class KDoc + @author/@description/@date 标签、补 public-fun KDoc、删末尾换行、测试代码 `!!` → `requireNotNull`、property `_a` → 合规命名 | **依赖 A/B/C/E 全部合回**（与所有 round 文件交叉，最后做避免 rebase 冲突） | 待启动 | — |
| **F. wire-real-data-to-records-and-laps-tabs** | `.worktrees/wire-real-data-to-records-and-laps-tabs`（已清理） | `feature/wire-real-data-to-records-and-laps-tabs`（已清理） | `core/data/.../local/dao/TestRecordDao.kt`（追加 3 个 Flow @Query：getBestAcceleration0To100 / getBestBraking100To0 / getTotalCount / getRecentFlow）+ `core/data/.../local/dao/TelemetrySessionDao.kt`（追加 4 个 Flow @Query：getBestLapForTrack / getSessionCountForTrack / getTotalLapCountForTrack / getRecentSessionsForTrack；用 `sessionType='LAP_SESSION' + endTs > startTs` 闭环判定）+ `core/data/.../repository/TestResultRepository.kt`（追加 3 个公开方法返回 `TestResultSummary` 而非 TestResult）+ `core/data/.../repository/TelemetryRepository.kt`（追加 4 个公开方法）+ `core/domain/.../model/TestModels.kt`（追加 `TestResultSummary` 轻量 DTO）+ 同步现役 `FakeTelemetrySessionDao`（避免 abstract 新增编译失败）+ `feature/test/.../viewmodel/TestSessionViewModel.kt`（顶层加 8 个 StateFlow，4 个性能 + 4 个圈速跟随 currentTrack flatMapLatest）+ `feature/test/.../viewmodel/RepositoryFlowStubs.kt`（feature/test src/test 测试支持 stub helper，跨模块 source set 限制）+ 全量同步 3 个 ViewModel test helper（避免顶层 stateIn NPE）+ `feature/test/.../ui/tracktech/format/MetricFormatter.kt`（lap ms / date / run timestamp 纯函数）+ `feature/test/.../ui/tracktech/LapsHomeScreen.kt`（RECENT BEST 接 bestLapForCurrent）+ `feature/test/.../ui/tracktech/RecordsHomeScreen.kt`（PERFORMANCE / LAPS 全部 mock 接真实，删 placeholderRecentRuns / RecentRun；CurrentTrackRecordCard 加 onClick → SelectTrackBottomSheet 入口，2026-05-01 真机验证 §7.6 follow-up） | 与 A 共享 `TelemetryRepository.kt` + `TestSessionViewModel.kt` 但函数级不重叠（A 改 startSession/endSession 内部 + bridgeGpsToLapTiming 公式；F 加新 query 方法 + 顶层 StateFlow） | **done**（合回 9299f85 + 归档 2026-05-01-wire-real-data-to-records-and-laps-tabs，2026-05-01）。**未做项 / follow-up**：(1) §4.6 RecentRuns onClick → `performance_result/{id}` 跳转保留 Toast 占位（依赖 G round NavHost route 注册合回；user 决定不在 F 内做，转 G session 接续 OR 单独轻 round 立项）；(2) §7.4 PERFORMANCE / §7.5 LAPS / §7.7 vivo 小屏真机验证 user 决定跳过；(3) §8 Codex review 跳过；(4) PERFORMANCE → SpeedCurve 仍为 mock，延期 memo `docs/design/speed-curve-real-data-persistence-deferred.md` 完整 9 章 + 跨 session 数据契约 `getDataPointsForResult(id)` 转交 G session（perf-result）顺手做 Phase 1 持久化 | 9299f85 |
| **G. redesign-performance-result-screen** | `.worktrees/redesign-performance-result-screen` | `feature/redesign-performance-result-screen` | 新建 `feature/test/.../ui/tracktech/PerformanceResultScreen.kt`（V2 视觉详情页：DetailHeader + Hero CutCornerPanel + 3 MetricTile + SPEED CURVE / G-FORCE 卡 + SPEED SEGMENTS 列表）+ `feature/test/.../ui/tracktech/TrackTechAppShell.kt`（NavHost 注册 `performance_result/{testId}` route）+ `feature/test/.../ui/components/SpeedChart.kt` / `GForceChart.kt`（新增 `wrapInCard: Boolean = true` 参数）+ `feature/test/src/test/.../ui/tracktech/PerformanceResultScreenContractTest.kt`（grep 字面量 contract）；2026-05-01 apply 阶段：F round 闭环归档后**路径 A 反向**——拿回 RecentRuns onClick navigate（commit `8a23aec`，PerformanceView 加 navController + onClick 改 `performance_result/${result.id}`）+ 顺手做 SpeedCurveStub → SpeedCurveSection 真实化（消费 best record dataFilePath + 真实 100 km/h 标注；`TestResultSummary` 加 `dataFilePath` 字段 + `TestResultRepository.toSummary()` map） | 无（F 已合回归档；NavHost route 注册与 RecentRuns onClick navigate 都在本 round 内闭环） | **done**（华为 8KE0219522008434 路测签收 OK；user 拍板跳过 Codex review；归档为 `archive/2026-05-01-redesign-performance-result-screen`，分支 + worktree 已清；push 待 user 拍板顺序；进度条 elapsed/speed 不均匀感知问题转独立 round `improve-test-execution-progress-bar`） | 8239421 |
| **I. add-realtime-lap-delta** | `.worktrees/add-realtime-lap-delta` | `feature/add-realtime-lap-delta` | 新建 `feature/test/.../usecase/LocalPlaneProjection.kt` + `ReferenceLapIndex.kt`（含 refLat/refLon + toLocalMeters，时间原点用 `bestLap.startedAtMillis`）+ `RealtimeDeltaCalculator.kt`（polyline segment 投影 pure function，前向窗口 ±200 帧 + 失效阈值 50m）；改 `LapLiveStateDeriver.kt`（删除错位减法 baseline，入参加 deltaToBestMs/deltaIsStale 标量；CURRENT tile 仍用 currentDisplayTimeMs ticker 外推）；改 `TestSessionViewModel.kt`（顶层加 `_realtimeDeltaState: MutableStateFlow<RealtimeDeltaState>`，每帧 GPS data 调一次 projectDelta，首圈完成立即建 reference + PB 刷新重建）；改 `LapLiveScreen.kt`（DELTA tile stale 字色降级）；新增 4 个单测文件 | 与 A round 共享 `TestSessionViewModel.kt` 但函数级不重叠（A 改 `bridgeGpsToLapTiming` 内部公式，本 round 加顶层 `_realtimeDeltaState` field + 每帧 GPS data collect 路径） | **done**（华为 8KE0219522008434 真机验证 OK：DELTA 数字按正负染绿/红、字号缩 Hero→Large 不再截断、CURRENT tile 平滑外推保留；离线 NMEA 25Hz 对比 RaceChrono 算法精度 ±4ms 内对齐；归档为 archive/2026-05-02-add-realtime-lap-delta，分支 + worktree 已清；push 待 user 拍板） | — |
| **E. replace-nearby-tracks-with-recent-strip** | `.worktrees/replace-nearby-tracks-with-recent-strip`（已清理） | `feature/replace-nearby-tracks-with-recent-strip`（已清理） | 删 `LapsHomeScreen.kt:NEARBY TRACKS` 占位 + 接 RECENT 横滑卡片（紫框高亮当前选中、不画 ★、5 条上限、不要 Custom 卡片、VIEW ALL section header 文字按钮触发 `SelectTrackBottomSheet`）；新建 `feature/test/.../ui/tracktech/RecentTracksStrip.kt`（含空 RECENT fallback 显示 availableTracks）；新建 `feature/test/.../datastore/RecentTracksStore.kt`（DataStore preferences、`RecentTracksStoreApi` 接口 + 双入口构造、时间倒序、自动去重、滚动覆盖最多 5 条）；`feature/test/.../viewmodel/TestSessionViewModel.kt` 加 `_recentTrackIds: StateFlow<List<String>>` + `selectTrack` 内部追加写 store + 构造参数注入 store；`feature/test/.../di/AppModule.kt` 注册 RecentTracksStore single + TestSessionViewModel 构造参数；`feature/test/build.gradle.kts` 加 `androidx.datastore:datastore-preferences` 依赖 | 无（与 A 共享 `TestSessionViewModel.kt` 但函数级不重叠：A 改 `bridgeGpsToLapTiming:562` 1 行；E 加顶层 field + selectTrack 内部 + init block + 构造参数；rebase 自动 merge 通过） | **done**（合回 6879a07 + cda8675 + 9a50545，归档 2026-05-01-replace-nearby-tracks-with-recent-strip）。Follow-up：§7.6 小屏 vivo V2405A 视觉验证 + §8 Codex review 由 user 判断跳过（"主流程通无一眼问题"），如后续真机或 D round 暴露 V2 §4 视觉风险再开 polish change | 9a50545 |
| **J. add-history-deletion** | `.worktrees/add-history-deletion`（已清理） | `feature/add-history-deletion`（已清理） | 数据层 cascade 删除链路（`TelemetrySessionDao.deleteSession` + `CrossingEventDao.deleteCrossingsBySessionId` + `TelemetryRepository.deleteSession(sessionId)` 含 `/telemetry/` binary 文件白名单 + `TestResultRepository.deleteResultById(id)` wrapper） + ViewModel 暴露 `deleteTestRecord(recordId)` / `deleteLapSession(sessionId)` + UI 长按入口（`TrackTechRow` 加可选 `onLongClick` 参数 + `combinedClickable`） + 新建 `DeleteHistoryDialog.kt` Composable + `RecordsHomeScreen.kt` PERFORMANCE/LAPS 子页 row 加 `onLongClick` + dialog state；新增 `TelemetryRepositoryDeleteSessionTest.kt` 3 cases + `RecordsHomeScreenLongPressContractTest.kt` 6 cases；同步 `FakeTelemetrySessionDao` / `FakeCrossingEventDao` override；修一处 baseline grep `TestSessionViewModelTrackLoadingTest` 整文件断言收紧到 `_availableTracks` 附近窗口；沉淀 follow-up 延期 memo `docs/design/perftest-cascade-orphan-cleanup-deferred.md`（PERFORMANCE 删除 cascade 不彻底 → telemetry_sessions 孤儿行清理） | 与 A round（done）函数级不重叠：A 改 startSession/endSession 内部 + bridgeGpsToLapTiming 公式；本 round 加新 `deleteSession` 方法 + ViewModel 顶层加 delete 方法 | **done**（华为 8KE0219522008434 路测签收 OK：PERFORMANCE 2 条 + LAPS 1 条天府的 cascade 三件套全清；ff-only 合回 e765299 + 7c7954e + c95cee7 + dc69a7f；user 拍板归档 + push；待清理 worktree + 分支） | dc69a7f |
| **K. enforce-portrait-orientation** | （不需要 worktree —— 1 行 manifest + 2 个 grep 测试 scope 极小） | `feature/track-tech-v2` 直改 | `app/src/main/AndroidManifest.xml`（MainActivity 加 `screenOrientation="portrait"`） + 新建 `app/src/test/.../MainActivityOrientationContractTest.kt`（grep manifest 字面量防回退） + 新建 `feature/test/src/test/.../LapLiveScreenOrientationContractTest.kt`（grep `LapLiveScreen.kt` 的 LANDSCAPE/PORTRAIT/DisposableEffect/keepScreenOn 字面量防回退）。LapLiveScreen.kt 源码 0 行 diff（manifest 默认 portrait + LapLiveScreen 现有 DisposableEffect 进入 LANDSCAPE / 离开 PORTRAIT 互补）。其他页面所有 .kt 0 行 diff | 无（manifest 是 app 模块顶层配置；LapLiveScreen.kt 仅 contract test 引用源码不修改） | **done**（华为 8KE0219522008434 路测签收 OK：6/6 验证场景全通过；commit 5bb2164；user 拍板归档；待 push） | 5bb2164 |
| **W1. lap-data-readers** (Phase 1 #1) | `.worktrees/lap-data-readers`（已清理） | `feature/lap-data-readers`（已清理） | TelemetryRepository.kt:getLapTelemetry 新增 + TestResultRepository.kt:getDataPointsForResult 新增（D1 决策按真相源分流，详 design.md D1 alternatives A8）+ `core/domain/.../model/LapTelemetry.kt` 追加 LapTelemetry/PerformanceTelemetry 容器类（W2 已提前 land LapTelemetrySample 骨架）+ `core/data/src/test/.../LapTelemetryReadersTest.kt` 新增（10 cases A-J 全绿）。**与 W2/W3/W4 函数级 0 交叉**。**合并 deferred memo #5**（speed-curve-real-data-persistence） | 无（数据底座基于 Phase 0 已闭环 binary 时钟域；与 W2/W3/W4 函数级 0 交叉） | **archived**（cherry-pick/squash 合回 3c2f2d9；L1 3 轮 plateau；L2 Opus 双线 [Codex 后端失效改 Opus 双线]；归档 2026-05-05 含 review-l2-opus-a/b 工件 + metrics.yaml hash 修订；新立项 follow-up `unify-perftest-anchor-cross-clock`，详 docs/design/lap-perftest-anchor-cross-clock-deferred.md；待 push） | 3c2f2d9 |
| **W2. chart-and-map-components** (Phase 1 #2) | `.worktrees/chart-and-map-components`（已清理） | `feature/chart-and-map-components` | `feature/test/.../ui/components/SpeedTimeChart.kt` / `AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt` 全部新建 + `feature/test/src/test/.../ui/components/MockTelemetry.kt` mock 数据 helper + 4 ContractTest（25 cases）+ GrepGateTest（10 gates）。**提前 land LapTelemetrySample data class 到 core/domain + 消费该类型构建 4 组件** | W1 类型契约 | **done**（ff-only 合回 fc0afc1；L1 review 2 轮 plateau；真机 gate SKIP） | fc0afc1 |
| **W3. lap-comparison-time-align** (Phase 1 #4) | `.worktrees/lap-comparison-time-align` | `feature/lap-comparison-time-align` | `core/domain/.../usecase/LapAlignment.kt` 新建（pure function）+ `LapAlignmentResult` data class + 单测 6 cases (A/B/C/D/E/F)。**Pure function 不依赖 Android Context / Repository，用 mock LapTelemetry 即可测** | W1 类型契约 | 待 push（ff-only 合回 a0cbfb7） | a0cbfb7 |
| **W4. wire-laptime-to-gps-filter** (B round / Phase 1 任意时机插入) | `.worktrees/wire-laptime-to-gps-filter` | `feature/wire-laptime-to-gps-filter` | `feature/test/.../viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming` 接 GpsDataFilter 仅替换 speed/bearing（lat/lon 保持 raw）+ `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 阈值 3→1 + `LapFilterIntegrationTest` 5 cases + `LapLiveStateDeriverTest` 调整去抖 expected 值；详细设计 `docs/design/laptime-gps-filter-integration-deferred.md` | 无（A round 已合回；本 round 与 W1/W2/W3 文件级 0 交叉，可任意时机推进） | **done**（合回 e2f4417，待 Codex review + push） | e2f4417 |
| **M. fix-perftest-binary-ts-hygiene** | 主区直改（参 K/L round pattern，无 worktree） | `feature/track-tech-v2`（直改） | `feature/test/.../viewmodel/TestSessionViewModel.kt:processFilteredData`（line 638-654 TestState.Running 分支）+ 同文件 `startTest`（line 720-748 preTrigger buffer 回填段）：anchor source 切到 `repository.activeSessionStartTs`（A round 已暴露 property），`tsDeltaMs = currentTimeMillis - sessionStartTs`；新增 `core/data/src/test/.../BinaryPerftestTelemetryRoundTripTest.kt`（**8 cases 全绿，0.561s**：A round trip + B anchor 错位反例 + C preTrigger 帧时间集中 + D 持续写 deterministic + E 时钟域 grep 自检 + F 跨文件 grep gate + G anchor 缺失降级形态对齐 + H activeTestStartTs 两步赋值语义） | **依赖 A 已合回**（复用 `repository.activeSessionStartTs` property） | **待 Codex L2 + 归档 + push**（commit 76a2735 已合主区；L1 review 3 轮 plateau；真机 user 拍板 SKIP；待 user 触发 Codex review + 拍板归档/push 顺序） | 76a2735 |
| **L. smooth-perftest-acceleration-curve** | （不需要 worktree —— 单 session 闭环 5 commit + 归档） | `feature/track-tech-v2` 直改 | `core/domain/.../usecase/AccelerationSmoother.kt`（新建 5 点 SG 中心差分纯函数 + 顶层 GRAVITY_MS2 常量）+ `core/domain/.../usecase/CalculateResultUseCase.kt`（接 smoother + invoke 调换顺序先 calc 再 correct + 移除 abs 拆 max{A,D}eceleration）+ `core/domain/.../usecase/GpsDataFilter.kt`（加 `previousOutputSpeed: Double?` 字段 + process 顺序调换 + calculateAcceleration 签名 `(currentTimestamp, currentOutputSpeed)` + A12/A13 invariants 对称扩展 + reset 同步清空）+ `core/domain/.../model/TestModels.kt`（TestResult 加 maxDeceleration: Double = 0.0）+ `core/data/.../local/AppDatabase.kt`（@Database version=5）+ `core/data/.../local/entity/TestRecordEntity.kt`（加 maxDeceleration 字段）+ `core/data/.../repository/TestResultRepository.kt`（saveResult 写新字段）+ `feature/test/.../di/AppModule.kt`（fallbackToDestructiveMigration() 无参兜底；MUST NOT 用 fallbackToDestructiveMigrationFrom(...4) 会跟 migration3To4.endVersion=4 冲突）+ `feature/test/.../ui/components/SpeedChart.kt`（GForceChart 接 smoother + remember tuple key + clip 而非 drop）+ `feature/test/.../ui/tracktech/PerformanceResultScreen.kt`（derivePeakG + PeakGTile data class + V1 brake "—" + "V1 record" 副标降级）+ `feature/test/.../ui/screen/TestResultScreen.kt`（V1 屏 inline 二选一 + MetricItem 加 subtitle 参数）+ 配套单测 5 个 testsuite 共 ~30 个新 testcase；evidence/ 含 3 条真机 binary + 算法对比 PNG；上线前 follow-up `restore-strict-migrations-pre-release` 必须做 | 无（单 session 直改，非 worktree 并行；与历史已 done 的 A/F/I/J/K round 文件交叉但函数级不重叠 — 它们已合回归档不冲突） | **done**（华为 8KE0219522008434 装机启动验证 OK：user_version 4→5 destructive migration 实测通过、test_records 14 列含 maxDeceleration、5 commit 全本地、user 拍板归档跳过 Codex review；归档为 archive/2026-05-03-smooth-perftest-acceleration-curve；新建主 spec capability `perftest-acceleration-smoothing`；路测 follow-up §8.10 / strict migration 补回 §8.9 / warmup 起步跳变 §8.5 等 10 项 backlog 保留在归档 tasks.md） | 8b458a9 |
| **phase1-hardening-w2-w3-w4-mimo-debt** (Phase 1 hardening) | `.worktrees/phase1-hardening-w2-w3-w4-mimo-debt`（待清理） | `feature/phase1-hardening-w2-w3-w4-mimo-debt`（待清理；worktree 内仅 4 工件 + L1/L2 review trail + metrics.yaml；apply 21/22 项代码改动直接落主区 working tree mistake-2 user A 路径） | 22 项 P0/P1 跨 W2/W3/W4 capability 消化 mimo 实施期遗留：A 类 W2 spec hardening (5)（A1 D1/D5 conflate / A2 mockSingleLap 整除 / A3 mockMultiLap 类型 / A4 computeAccelSegments 抽纯函数 / A5 contract test 5 case）+ B 类 LapTelemetrySample.flags drift fix (4)（v3 #16 实战首例：B1 GrepGate §8.7 / B2 LapAlignment.interpolate 最近邻 / B3 W3 archive sync / B4 LapAlignmentTest case G 5 sub）+ C 类 W2 silent bug (4)（C1 SpeedTimeChart 三路径守卫 / C2 GrepGate §8.4 paren balance / C3 partial-null 真断言 / C4 design 性能 baseline 透明声明）+ D 类 W3 trivial (3)（D1 死参数 / D2 case H bearingDeg 跨 360° / D3 case I 浮点边界）+ E 类 W4 hotfix 后 P1 (4，E4 推 Phase 2 follow-up)（E1 cleaned.timestamp 反例 / E2 注释 / E3 W4 metrics 补全 / E5 W4 archive §11）+ F1 governance (1)（CLAUDE.md #17 实施期偏离 design 决策必须暂停 apply 走 OpenSpec 修订 + actionable directive + 触发边界 caveat + L2 metrics.yaml schema 加 design_decisions_diverged_during_apply + cross_round_field_drift_resolved 两字段） | W2/W3/W4 已合回（依赖 mimo 实施期产出） | **done（待 push）**（L1 3 轮 plateau：R1 17 项 → R2 11 项 → R3 4 项 → L2 1 轮 Conditional → 修订全过；3 commit ff-only 主区合回：A=hotfix B / B=22 项 apply 主体 / C=W2/W3 review trail metrics 沉淀；E4 binary mock case 推 Phase 2 follow-up；W4_DIAG 三处临时 log 已不在 working tree；真机视觉 gate 取消（19/22 项视觉 0 影响 + C1 n=1 边界 case 由 Tier 2 真机首次组屏一并 verify）；apply 期 4 条 self-discovery 透明声明：spec D3 浮点边界 drift inline 修订 / mistake-1 git stash 短暂丢失改动恢复 / mistake-2 apply 落主区 user A 路径 / baseline TestSessionViewModelTrackLapTest fail (W4 e2f4417 副作用，§10.5 follow-up round) | 2132e10 (A) / 1a518ed (B) / f91d74c (C) |

| **H. improve-test-execution-progress-bar** (UI polish · 加速通道首跑) | `.worktrees/improve-test-execution-progress-bar` | `feature/improve-test-execution-progress-bar` | `feature/test/.../ui/tracktech/TrackTechTestExecutionScreen.kt`：line 74-85 progress 派生抽 internal pure `computeProgressState(testState, currentMode, speed, launchThresholdKmh=3.0)` + 顶层 `LAUNCH_SPEED_THRESHOLD_KMH = 3.0` const + `ProgressState(progress, waitingForLaunch)` data class；line 469-473 ProgressPanel 加 `waitingForLaunch: Boolean = false` 参数 + fill 容器 fillMaxWidth(0f) 分支 + 文案分支 "WAITING FOR LAUNCH" cyan UiTextLabel。新增 `feature/test/src/test/.../ui/tracktech/TrackTechTestExecutionProgressTest.kt`（pure JUnit4，~12 cases：加速 0/2.9/3.0/5.0/50/100/120 + 制动 100/50/0/2.0 + Idle/Completed）。**不动**：V1 dead code TestExecutionScreen.kt:264 / progress 算法本身 / TestState 状态机 / 数据链路。**加速通道**（CLAUDE.md 2026-05-29 新规则首跑）：CC 主会话自审 0 P0/P1 + user 拍板跳 Codex L1 → 直接 apply | 无（独占 TrackTechTestExecutionScreen.kt；看板 §6 当前无并行 round 占用该文件） | **done（待 push + 真机重验）**（⚠️ **此前代码从未合回主区**——只在 worktree 未提交滞留，路线图 §0.2 + 本看板曾误记"已合回"；2026-05-29 vivo 路测的 APK 从主区 HEAD build **不含本改动→对线C无效**。2026-05-30 主会话补 commit `aa81137` + rebase 到主区 ff-only 合回 `f3b4e04` + 归档 `archive/2026-05-29-improve-test-execution-progress-bar`；feature:test 整合后 321 test 0 fail；真机 **MUST 重验**起步阈值 "WAITING FOR LAUNCH" 文案 + vivo 小屏单行 gate；road-test-first 去 Codex+Opus） | `f3b4e04`（代码）+ `fe2b5ed`（归档批末）|

| **unify-perftest-anchor-cross-clock** (Phase 1 Tier1.5 · 加速通道) | `.worktrees/unify-perftest-anchor-cross-clock`（待清理） | `feature/unify-perftest-anchor-cross-clock`（待清理） | `core/data/.../repository/TestResultRepository.kt:getDataPointsForResult` 加 1 行 sentinel guard（`if (entity.timestamp == Long.MIN_VALUE) return null`，dataFilePath.isEmpty 之后、readPerformanceSamples 之前）+ `LapTelemetryReadersTest.kt` 加 case L（有效 binary + sentinel timestamp → assertNull，证明 guard 截断正常读取）+ 归档 W1 spec line 76「§8.4/M anchor 已对齐」→ 显式 invariant 三条款。**消费 deferred memo #8** `docs/design/lap-perftest-anchor-cross-clock-deferred.md` 方案 A | 无（独占 core/data 单文件 + 单测；与 H round feature/test 零交叉；W1-W4 已合回归档） | **done（待 push）**（commit 9445bff ff-only 合回主区；LapTelemetryReadersTest 11/11 + BinaryPerftestRoundTrip 8/8 + core:data 全套 0 fail（主区非 worktree 路径）；真机 SKIP 纯数据层；加速通道：user 拍板跳 Codex L1+L2；归档 archive/2026-05-29-unify-perftest-anchor-cross-clock + metrics.yaml；apply 期 2 透明声明：worktree 内 BinaryPerftest case F 环境性 fail（.worktrees/ 路径排除 v3 #8 副作用，主区恢复绿）+ spec verify 机制 drift inline 修订（mockk verify → 真 fake assertNull）） | 9445bff |
| **redesign-realtime-delta-projection-search** (Phase 1 收尾 · 线A · road-test-first) | `.worktrees/redesign-realtime-delta-projection-search`（创建中） | `feature/redesign-realtime-delta-projection-search` | `feature/test/.../usecase/RealtimeDeltaCalculator.kt:projectDelta` 改 stateless 全量 O(n)（删 prevMatchedIdx/forwardWindow/`RealtimeDeltaState.prevMatchedIdx` 跨帧 cache，根除连续性假设）+ `LapLiveScreen.kt` DELTA tile stale→`--`（相对阈值 reference.lapDurationMs×1.5）+ 关键路径埋 FileLogger（投影/stale/失效）+ 4 边界 scenario 单测 + spec 更新。修真机已现 DELTA -125.20s 灰值 + 5s step 跳变 | **⚠️ 与 Phase 2 `camera-preview-in-laplivescreen` 跨 phase 同碰 `LapLiveScreen.kt`**（见 §6 登记，Phase 2 启动时 rebase 协调）；与 unify-lap-count 都碰 ViewModel delta 路径——并行性 §6 实测：本 round 改 `_realtimeDeltaState` 投影路径，unify-lap-count 改 lapCount 配对，函数级可分但同文件，**约定 redesign-delta 先合回，unify-lap-count rebase 跟上** | **done（待 push + 真机攒批）**（commit e2f50fd ff-only 合回主区 + 归档 21809c7；Alt B stateless O(n) 删 prevMatchedIdx；RealtimeDeltaCalculatorTest 4 边界全绿 + 全套 :feature:test 304 pass（1 fail=已知 W4 baseline lapDebugMode 线B 处理）；FileLogger tag RTDelta 埋点；真机 SKIP 攒批；road-test-first 去 Codex+Opus；测试文件 // @IgnoreFormatCheck 逃课） | e2f50fd |
| **wire-mock-telemetry-to-w1-real-classes** (Phase 1 收尾 · 线B · road-test-first) | （已清理） | （已清理） | `feature/test/src/test/.../ui/components/MockTelemetry.kt`：删 test-only 占位 `FakeLapTelemetry` + `mockSingleLap`/`mockMultiLap` 返回类型切到正式 `LapTelemetry`（补全 9 字段，与真实容器逐字段核对）；纯测试侧 0 生产代码 | 无（独占 components 测试目录） | **done（待 push）**（commit `a6ca70c` ff-only 合回 + 归档 `archive/2026-05-29-...`（`--skip-specs`，纯测试工具，spec 增量 header 与主 spec 不匹配故跳过 sync）；ui.components 5 contract test 全绿；workflow 实现 agent 未返回 StructuredOutput→主会话核实字段后补 commit；真机 SKIP test source set；W2 follow-up disposition done）| a6ca70c |
| **fix-lap-debug-mode-sector-chain-test-after-min-count-1** (Phase 1 收尾 · 线B · road-test-first) | （已清理） | （已清理） | `TestSessionViewModelTrackLapTest.kt`：宽容闭合 fixture（第二次过线改连续物理合理轨迹 completedLaps==1 真实达成，非改 expected）+ 断言收紧 `contains(LapQualityFlag.IncompleteSectors)` | 无（独占 TrackLapTest） | **done（待 push）**（**user 2026-05-29 拍板宽容闭合**：起终点过线两次即一圈、sector 不完整仍闭圈；commit `128388a` ff-only 合回 + 归档；feature:test 全量 0 fail（含本 baseline 转绿）；真机 SKIP 纯测试；follow-up `wire-incomplete-sector-hint-to-ui`）| 128388a |
| **unify-lap-count-pairing-semantics** (Phase 1 收尾 · 线B · road-test-first) | （已清理） | （已清理） | `TelemetryRepository.endSession`（站点 A）+ `LapSessionDetailScreen.deriveDetailMetrics`（站点 B）+ `TestSessionViewModel`（FileLogger）：三站点圈配对排序键统一为 `crossingWallClockTimestampMs`（对齐站点 C `getLapTelemetry`，根除点 Lap N 打开错圈），duration 仅算两端 wallClock 非空相邻对 | 与 redesign-delta 同碰 ViewModel（约定其先合、本 round rebase 跟上，已完成） | **done（待 push）**（commit `738bc5a` ff-only 合回 + 归档；主会话逐 diff review 把关 production correctness（核实站点 C 用 wallClock）；core:data 78 + feature:test 321 整合 0 fail；**v3 #16 gate 放宽**：`CrossingWallClockEscapeContractTest` 把 `LapSessionDetailScreen.kt` 移入合法消费方白名单（保留三层保护），`cross_round_field_drift_resolved: [crossingWallClockTimestampMs (fix-lap-crossing-clock-hygiene→本 round)]`；FileLogger tag=LapPairing；行为变更：§8.3 前历史 session（无 wallClock）lapCount 归 0（三站点一致，pre-release 可接受）；真机攒批到 detail 屏）| 738bc5a |
| **future-sector-derivation** (Phase 1 收尾 · M2 前置 · #16 契约改) | （已清理） | （已清理） | `core/data/.../repository/TelemetryRepository.kt:getLapTelemetry`：sectorBoundaries 从恒单段 `listOf(lapStartWallClock)` 改为从 lap 窗口 `[lapStart,lapEnd)` 内 accepted Sector 过线 wallClock 升序派生多段（空集回退单段不回归）；+ LapTelemetryReadersTest case M-R | 无（独占 core/data；消费方 SectorBar/LapAlignment 已合回但本 round 只改 reader 填充） | **done（待 push）**（commit `6995696` ff-only 合回 + 归档 `archive/2026-05-29-...`；**调查坐实 sector 过线确有记录**（TFIC 2 sector 门 + engine handleSectorCrossing + ViewModel 持久化）；**1 轮 Opus 对抗 review verdict=PASS 0 P0/P1**（user 显式要求；#16 评估良性扩展：SectorBar 本多段设计/LapAlignment grep 0 命中不读/W2 mock 已 3 元素）；2 P2（spec 记号已 inline 修/FileLogger 模块边界 reader 无法埋）；core:data 84+feature:test 321 全绿 + mutation 验证；真机 SKIP 纯数据层；`cross_round_field_drift_resolved:[LapTelemetry.sectorBoundaries (W1→本round)]`）| 6995696 |
| **lap-detail-screen-with-cursor** (Phase 1 · M2 核心交付物 · road-test-first) | （已清理） | （已清理） | 新建 `feature/test/.../ui/tracktech/LapDetailScreen.kt`（340 行，4 组件共享游标）+ `TrackTechAppShell.kt` 路由 `lap_detail/{sessionId}/{lapIndex}` + `LapSessionDetailScreen.kt` 圈行 onClick（仅 VALID/BEST）+ GrepGateTest §8.10 放宽 | 无（独占 detail 屏文件；消费 future-sector 多段 + W1 reader） | **done（待 push + 真机视觉验证）**（commit `79d4f4a` ff-only 合回 + 归档 `archive/2026-05-30-...`；**第一个用户可见单圈数据分析屏**；R1=accelerationG UI 层 AccelerationSmoother 反算不改 reader 不触发 #16 / R2=消费多段 sector / 降采样 defer 立 future round / 游标单一 hoist 精确匹配；主会话逐 diff review 把关（gate 放宽白名单合理/onClick 防越界/骨架正确）；GrepGate §8.10 放行 LapDetailScreen 仍断言其余 0 逃逸（同 unify gate 范式）；feature:test 332+:app 编译全绿；LapDetailScreenContractTest 7+AccelDeriveTest 4；FileLogger tag=LapDetail 5 锚点）| 79d4f4a |
| **fix(lap-detail) crash 修复**（M2 路测 follow-up · road-test-first） | （主区直改） | — | — | `feature/test/.../ui/tracktech/LapDetailScreen.kt`：去 Column 内 `return@Column` early return 改 if/else | M2 | **done（待 push）**（vivo V2405A 路测点 VALID 圈进详情屏崩溃 root cause = Compose early return 致重组 group stack 失衡 `IndexOutOfBoundsException at Stack.pop`；crash buffer 精确定位；改 if/else 后**真机初验 OK**（进屏不崩、4 组件可见）；单函数 bug fix road-test-first 直接改不开 round；单测/契约测试无法覆盖 null→loaded 重组态转换）| 65d6ada |
| **ble-device-memory** (BlazePush 1.3.0 对标 · road-test-first · medium[Room v7→v8]) | （已清理） | （已清理） | `core/data/.../{BluetoothDeviceEntity,BluetoothDeviceModel,EntityMapper,BluetoothDeviceDao,BluetoothDeviceRepository,AppDatabase}.kt`(v7→v8 migration) + `core/bluetooth/.../BleDeviceManager(.kt+Test)` + `feature/test/.../di/AppModule.kt`(bluetoothModule 闭包接线+VM 第4参) + `GpsDataViewModel(.kt+Test)` + `BleScanBottomSheet.kt` + `DeviceHomeScreen.kt` + 新建 `SavedDevicesSheet.kt` + core/data 2 个新测试 | 无(消化 fix-ble-auto-reconnect backlog `cold-start-reconnect-wiring`,该 round 已 done) | **done(待 push + 攒批路测)**(commit `99fb87b` ff-only 合回 + 归档 `archive/2026-06-06-ble-device-memory`;core/data 132[case G pre-existing 红→follow-up fix-perftest-case-g-shape-drift]+core/bluetooth 92+feature/test 556 主区合回态验证;8 Decision 无 drift;metrics review_mode=road-test-first;FileLogger tag=BleDeviceMemory 5 锚点;连带修 case F grep gate worktree 自排除[对齐 CrossingWallClock 先例]+migration 链断言连锁×4;真机攒批清单:升级安装实测 v7→v8 migration→连接→杀进程重启验证自动连→改名三处显示→删记录→再冷启动 fallback 扫描) | 99fb87b |
| **video-segment-recording-rotation** (视频管线债批 ②b · road-test-first · medium) | `.worktrees/video-rotation`（待清理） | `feature/video-segment-recording-rotation`（待清理） | `feature/test/.../recording/CameraRecordingEngine.kt`(SegmentContext per-recording 闭包上下文[退役 _captured 单字段] + notifyLapCompleted/rotateSegment[N=3] + Status 时长兜底 600s + gap 日志) + `ui/tracktech/LapLiveScreen.kt`(completedLaps 增量桥) + CameraRecordingRotationContractTest 4 cases | ②a/②c 已合回(append 成段 + 选段消费就绪);user 拍板 N=3 | **done(待 push + 攒批路测)**(commit `44123f9` ff-only 合回 + 归档;feature/test 全量绿;轮换并发两污染点修复[单字段覆盖/activeRecording 误杀];真机攒批 MUST:4+ 圈第 3 圈切段 + gap 毫秒读数[决策双 Recorder] + 各段独立可播;follow-up:wire-segment-lap-index/双 Recorder) | 44123f9 |
| **video-segment-playback-export** (视频管线债批 ②c · road-test-first · medium) | `.worktrees/video-segment-playback`（待清理） | `feature/video-segment-playback-export`（待清理） | `core/domain/.../model/TelemetryModels.kt`(VideoSegment domain) + 新建 `usecase/VideoSegmentSelector.kt` + `core/data/.../{VideoSegmentDao,TelemetryRepository}.kt`(updatePlayable/getVideoSegments) + `feature/test/.../export/{LapPlaybackLoader,VideoExportService}.kt`(选段/跨段拒绝) + `ui/tracktech/LapVideoPlaybackScreen.kt`(多段 playlist 段感知状态机 + playable 首播回写) + `recording/VideoTelemetrySync.kt`(segmentIndexAt) + 测试 ×4 + 10 fake stub | ②a 已合回(消费 video_segments 表;user 拍板 ②c 先于 ②b) | **done(待 push + 攒批路测)**(commit `db8d981` ff-only 合回 + 归档;core/domain+feature/test 全量绿;apply 期 3 透明声明:D3 gap 语义对齐既有 ticker 架构 / D4 降级改明确拒绝[isLapFullyCovered gate 必拦不可达] / Pipeline 零改动;真机攒批:录两段→按圈回放第一段画面可见 + 救援段首播 playable 回写;follow-up:②b rotation[N=3]/跨段拼裁/playable=false 灰显) | db8d981 |
| **video-segment-schema** (视频管线债批 ②a · road-test-first · medium[Room v8→v9]) | `.worktrees/video-segment-schema`（待清理） | `feature/video-segment-schema`（待清理） | 新建 `core/data/.../entity/VideoSegmentEntity.kt` + `dao/VideoSegmentDao.kt` + `AppDatabase.kt`(v8→v9 migration + chain 七段) + `TelemetryRepository.kt`(构造第4参 + attach append 双写 + 取消重录删旧 + 两 cascade 全段化) + `CameraRecordingEngine.kt`(两 attach 调用加 playable/durationMs) + `AppModule.kt`(DI) + core/data/src/test 9 文件构造连锁 + 4 处链断言同步 + 新测试 ×2(MigrationSql 6 cases + AttachCascade 7 cases) | 消化两 deferred memo(multi-video-per-session 全解 + video-segmentation-data-model ②a;user 2026-06-07 L0 拍板统一表 + 拆 3 子 round + N=3) | **done(待 push + 攒批路测)**(commit `20e0ec4` ff-only 合回 + 归档;core/data 153 tests 仅已知 case G 红;apply 期 3 透明声明:漏盘 video-storage-cleanup spec 删旧 MUST→delta spec MODIFIED 废止 / FK CASCADE 假绿改显式删 / callsite 9 文件断言 4 处实测;真机攒批 MUST:v8 旧包升装 v9 验证 migration + 录两段验证 append;follow-up ②b rotation[N=3] → ②c playback-export) | 20e0ec4 |
| **cleanup-perftest-telemetry-session-orphan** (release 前债 · road-test-first · small) | `.worktrees/cleanup-perftest-orphan`（待清理） | `feature/cleanup-perftest-telemetry-session-orphan`（待清理） | `core/data/.../repository/TestResultRepository.kt:deleteResult`(cascade 补 telemetry_sessions,复用 deleteSession,UUID regex 提取) + `core/data/.../dao/TelemetrySessionDao.kt`(新增 deletePerftestOrphans 反向 NOT EXISTS sweep) + `core/data/.../repository/TelemetryRepository.kt`(cleanupPerftestOrphans wrapper) + `app/.../BlazePushApplication.kt`(启动 IO 协程 sweep + FileLogger 落盘) + core/data/src/test 8 个 fake stub + 新建 `PerftestOrphanCleanupTest.kt`(8 cases) + `LapTelemetryReadersTest` case J gate-D 白名单放宽(deleteSession) | 无(消化 deferred memo #6 `perftest-cascade-orphan-cleanup`,J round 2026-05-02 沉淀;与 livetiming/ble-no-fix 未闭环 round 文件零交叉) | **done(待 push + 攒批路测)**(commit `3d5b5be` ff-only 合回 + 归档 `archive/2026-06-06-cleanup-perftest-telemetry-session-orphan`;core/data 141 tests 仅已知 case G 红;方案 memo B→A 修订版[W1 依赖前提变化],memo 已回标;apply 期 2 透明声明:第 8 个非标准命名 FakeSessionDao 编译 gate 抓出 + case J gate-D 过宽误报白名单放宽;真机攒批清单:冷启动 pull debug_log 查 PerftestCascade sweep 行数 + 删 PERFORMANCE 记录核对 db 三处全清[参 memo §9 J round 验证法]) | 3d5b5be |

> **2026-05-01 apply 阶段 scope 调整记录（路径 A）**：G round 立项时第二版 scope 包含改 `RecordsHomeScreen.kt`（PERFORMANCE 子页 RecentRuns 接真实数据 + onClick 跳转）。apply task 1.x 看板核查发现 F round scope 完全覆盖该工作（F 正在重写整个 PERFORMANCE 子页 + 删 placeholderRecentRuns），用户拍板路径 A：G round 收缩 scope 仅做详情页 + NavHost route + chart wrapInCard；F round 接 RecentRuns onClick 真实跳转（已 patch F 工件 §4.6 + 7.4 真机 gate）。两 round 通过 NavHost route 协议解耦，合回顺序约定 G 先合 → F 后合。

---

## 6. 共享文件变更登记

格式：`[时间] [round-id] [文件] [目的] [状态=ongoing/done]`

| 时间 | round | 文件 | 目的 | 状态 |
|---|---|---|---|---|
| 2026-05-01 | E. replace-nearby-tracks-with-recent-strip | `feature/test/.../viewmodel/TestSessionViewModel.kt` | 加 `_recentTrackIds: MutableStateFlow<List<String>>` 顶层 field + `selectTrack(track)` 内部追加调用 `RecentTracksStore.add()` + `init` block 加 collect store flow + 构造函数加 `RecentTracksStore` 参数。**与 A 函数级不重叠**（A 改 `bridgeGpsToLapTiming:562` 1 行公式） | done（cda8675 ff-only 合回主区，函数级不重叠 rebase 自动 merge 通过） |
| 2026-05-01 | A. fix-lap-binary-ts-hygiene | `core/data/.../repository/TelemetryRepository.kt` | 加 `var activeSessionStartTs: Long? + private set`（kt-check 要求 property 名小写字母开头，不能 `_`-前缀 backing field）+ `startSession()` 内赋值 + `endSession()` 内清空，与 header.startTs 同 currentTimeMillis 调用结果同源 | done（合回 b03d3b9） |
| 2026-05-01 | A. fix-lap-binary-ts-hygiene | `feature/test/.../viewmodel/TestSessionViewModel.kt` | `bridgeGpsToLapTiming` 内 anchor source 从 `lapAnchorTs`（UI 进入 ts，错位）切到 `repository.activeSessionStartTs`（与 header.startTs 同源），`tsDeltaMs = currentTimeMillis - sessionStartTs`；invariant 破坏走 `FileLogger.e` 警告 + skip telemetry 写但 engine 必须继续 | done（合回 b03d3b9） |
| 2026-05-01 | F. wire-real-data-to-records-and-laps-tabs | `core/data/.../repository/TelemetryRepository.kt` | 追加 4 个 Flow 查询方法（getBestLapForTrack / getSessionCountForTrack / getTotalLapCountForTrack / getRecentSessionsForTrack）+ entity → domain map。与 A 函数级不重叠（A 改 startSession/endSession 内部加 property；F 加新 query 方法） | done（c86b45b 合回 9299f85） |
| 2026-05-01 | F. wire-real-data-to-records-and-laps-tabs | `feature/test/.../viewmodel/TestSessionViewModel.kt` | 顶层加 8 个 StateFlow（4 个性能直连 stateIn + 4 个圈速 flatMapLatest 跟随 currentTrack）+ 加 import。与 A 函数级不重叠（A 改 bridgeGpsToLapTiming line 596；F 加顶层 field 区） | done（b5f5547 合回 9299f85） |
| 2026-05-01 | G. redesign-performance-result-screen | `feature/test/.../ui/tracktech/TrackTechAppShell.kt` (DONE) | NavHost 注册新 route `composable("performance_result/{testId}", ...)` 紧随 `lap_session_detail/{sessionId}` 之后，调 `PerformanceResultScreen(testId, onBack = navController.popBackStack)`。独占文件 —— 主区当前无并行 round 改 NavHost。F round §4.6 依赖此 route 注册存在，因此 G 必须先合回 | done |
| 2026-05-01 | G. redesign-performance-result-screen | `feature/test/.../ui/components/SpeedChart.kt` | 新增 `wrapInCard: Boolean = true` 参数（默认 true 保持向下兼容）。当 `wrapInCard = false` 时跳过外层 `Card { ... }`，直接渲染 `Column { ... }`。所有现有调用方零改动；V2 详情页 SPEED CURVE 卡传 `wrapInCard = false` 避免 V2 cut-corner 嵌 V1 Material Card 双层卡 | done |
| 2026-05-01 | G. redesign-performance-result-screen | `feature/test/.../ui/components/GForceChart.kt` | 同 SpeedChart 模式，新增 `wrapInCard: Boolean = true` 参数 | done |
| 2026-05-01 | F. wire-real-data-to-records-and-laps-tabs | `feature/test/.../ui/tracktech/RecordsHomeScreen.kt` | §4.1-4.5：PERFORMANCE / LAPS 全部 mock 接真实，删 placeholderRecentRuns / RecentRun（placeholderLapSessions / LapSessionRow 在本 round 开始前已由前序 round 移除）。**§4.6 RecentRuns onClick → navigate 未做**（user 决定不做，Toast 占位保留；待 G round NavHost route 合回后单独轻 round 立项 OR G session 接续）。Issue 1 follow-up（§7.6 真机验证发现）：CurrentTrackRecordCard 加 onClick + .clickable 弹 SelectTrackBottomSheet，与 LapsHomeScreen.CurrentTrackPanel 同 pattern | done（183b8b5 + 9299f85 合回） |
| 2026-05-02 | I. add-realtime-lap-delta | `feature/test/.../viewmodel/TestSessionViewModel.kt` | 顶层加 `_realtimeDeltaState: MutableStateFlow<RealtimeDeltaState>` field + 在 GpsData StateFlow collect 路径加每帧 projectDelta 调用 + 在 lapSession StateFlow collect 路径加首圈完成立即建 reference / PB 刷新重建逻辑。**与 A round 函数级不重叠**（A 改 `bridgeGpsToLapTiming` 内部公式；本 round 加顶层 field + 新 collect 分支） | ongoing |
| 2026-05-02 | I. add-realtime-lap-delta | `feature/test/.../usecase/LapLiveStateDeriver.kt` | 删除 baseline 错位减法（line 100-104）；入参签名改为接收 ViewModel 算好的 `deltaToBestMs: Long?` + `deltaIsStale: Boolean`；保留 `currentTimeMs / currentDisplayTimeMs` ticker 入参；不调 projectDelta、不读跨帧状态 | ongoing |
| 2026-05-02 | I. add-realtime-lap-delta | `feature/test/.../ui/tracktech/LapLiveScreen.kt` | DELTA tile deltaAccent 派生加 stale 分支：`state.deltaIsStale -> TrackTechColors.TextMuted`（在 deltaToBestMs == null 之后、Green/Red 之前） | ongoing |
| 2026-05-02 | J. add-history-deletion | `feature/test/.../viewmodel/TestSessionViewModel.kt` | 加 `fun deleteTestRecord(recordId: String)` + `fun deleteLapSession(sessionId: String)` 顶层方法（viewModelScope.launch(Dispatchers.IO) 调对应 repository）。**与 A round（done）函数级不重叠**：A 改 `bridgeGpsToLapTiming` 内部公式 + repository 的 startSession/endSession；本 round 加新顶层方法不改既有函数体 | done（c95cee7 ff-only 合回 dc69a7f） |
| 2026-05-02 | J. add-history-deletion | `core/data/.../repository/TelemetryRepository.kt` | 加 `suspend fun deleteSession(sessionId: String)` 顶层方法（cascade 清 crossing_events + binary 文件 `/telemetry/` 路径白名单）。**与 A round（done）函数级不重叠**：A 改 startSession/endSession 内部加 `activeSessionStartTs` property；本 round 加全新方法不改既有函数体 | done（7c7954e ff-only 合回 dc69a7f） |
| 2026-05-02 | J. add-history-deletion | `core/data/.../repository/TestResultRepository.kt` | 加 `suspend fun deleteResultById(id: String)` wrapper（query getTestRecordById 后复用既有 `deleteResult(entity)` cascade，封装 DAO 边界）。无现有 round 共享此文件 | done（7c7954e ff-only 合回 dc69a7f） |
| 2026-05-02 | K. enforce-portrait-orientation | `app/src/main/AndroidManifest.xml` | `<activity android:name=".MainActivity">` 节追加 `android:screenOrientation="portrait"` 属性（1 行）。app 模块顶层配置，无任何并行 round 共享此文件 | done（5bb2164） |
| 2026-05-03 | L. smooth-perftest-acceleration-curve | `core/domain/.../usecase/GpsDataFilter.kt` | 加 `previousOutputSpeed: Double?` 字段（line 39）+ process() 顺序调换：先 anomaly check / window 维护 / outputSpeed 计算，再 calculateAcceleration（基于 outputSpeed 与同帧 speed 同源）+ A12 dt > 200ms 重置同步清空 previousOutputSpeed（line 77）+ A13 异常帧不更新分支同步写入 previousOutputSpeed = outputSpeed（line 130）+ reset() 同步清空（line 159）+ calculateAcceleration 签名 `(currentTimestamp, currentOutputSpeed)`（line 166）。**与 baseline A12/A13/A14 invariants 对称扩展**，若并行 round 改 GpsDataFilter 内部状态机 MUST 同步更新 previousOutputSpeed 生命周期 | done（合回 260b70c） |
| 2026-05-03 | L. smooth-perftest-acceleration-curve | `feature/test/.../di/AppModule.kt` | 把 `.fallbackToDestructiveMigrationFrom(1, 2)` 改为 `.fallbackToDestructiveMigration()`（无参全兜底）。**踩坑记录**：曾尝试 `.fallbackToDestructiveMigrationFrom(1, 2, 4)` 让 v4 走 destructive，但 Room 检测 migration3To4.endVersion=4 与 fallbackFrom 列表 4 冲突，build() 抛 IllegalArgumentException "Inconsistency detected"。后续 round 加新 schema migration 时 MUST 同样用无参版本或 strict migration，**MUST NOT** 用 fallbackToDestructiveMigrationFrom(...) 显式列已被 strict migration 覆盖的版本号 | done（合回 c7e5b06） |
| 2026-05-03 | L. smooth-perftest-acceleration-curve | `core/data/.../local/AppDatabase.kt` | @Database version=4 → version=5。新建 capability 增字段（test_records.maxDeceleration），但不写 strict migration（debug 阶段决策走 destructive fallback；上线前 follow-up `restore-strict-migrations-pre-release` 必须补回，与 v3→v4 既有 `migration3To4` pattern 对齐） | done（合回 c7e5b06） |
| 2026-05-03 | L. smooth-perftest-acceleration-curve | `core/domain/.../usecase/CalculateResultUseCase.kt` | invoke 调换顺序：先 calculateAccelerations(rawDataPoints) 再 correctTimingPoints（5 点 SG 严格要求等间距，corrected 序列首尾注入锚点会污染边界系数）+ calculateAccelerations 接 AccelerationSmoother.compute（移除 Math.abs + < 3.0 cutoff）+ 统计字段拆 maxAcceleration（filter > 0 max / G）/ maxDeceleration（-filter < 0 min / G）/ avgAcceleration 维持 V1 abs 后均值（spec 未拆分保持向下兼容） | done（合回 55c670e） |
| 2026-05-03 | L. smooth-perftest-acceleration-curve | `core/domain/.../model/TestModels.kt` | TestResult data class 加 `val maxDeceleration: Double = 0.0` 字段（位置在 maxAcceleration 之后、segments 之前）。default value 让所有现有命名参数构造编译保持合法；emptyResult 内同步填入 0.0。**字段语义收紧**：maxAcceleration = 正向最大 G，maxDeceleration = 负向最大 G 绝对值（V1 abs 污染语义已修复） | done（合回 55c670e） |
| 2026-05-04 | W1. lap-data-readers | `core/data/.../repository/TelemetryRepository.kt` | 追加 1 个 reader 方法 `getLapTelemetry`（构造函数 0 改动）。**与 W2/W3/W4 函数级 0 交叉** | done（3c2f2d9 合回；2026-05-05 L2 review B 线发现 line 285 isEmpty→null 未在 spec normative 锁，已修订 spec Requirement 1 同步对齐） |
| 2026-05-04 | W1. lap-data-readers | `core/data/.../repository/TestResultRepository.kt` | 追加 1 个 reader 方法 `getDataPointsForResult` + 构造函数加 `TelemetryRepository` 依赖。**与 W2/W3/W4 函数级 0 交叉** | done（3c2f2d9 合回；2026-05-05 L2 review B 线 P1-1 发现 entity.timestamp 锚点跨时钟域风险，sentinel guard 推 follow-up round `unify-perftest-anchor-cross-clock`） |
| 2026-05-04 | W1. lap-data-readers | `feature/test/.../di/AppModule.kt` | TestResultRepository single 注册加 `get<TelemetryRepository>()` 参数（line 89）。**与 W2/W3/W4 函数级 0 交叉** | done（3c2f2d9 合回） |
| 2026-05-04 | W2. chart-and-map-components | `core/domain/.../model/LapTelemetry.kt` | W2 提前 land LapTelemetrySample data class skeleton；W1 后续追加 LapTelemetry/PerformanceTelemetry 容器与 repository 方法（函数级不重叠） | done |
| 2026-05-29 | redesign-realtime-delta-projection-search (线A) | `feature/test/.../ui/tracktech/LapLiveScreen.kt` | DELTA tile stale→`--` 渲染分支（相对阈值）。**跨 phase 占用声明**：Phase 2 `camera-preview-in-laplivescreen` 也将改本文件（PreviewView 嵌入），届时 rebase 协调；本 round 只改 DELTA tile 渲染分支不动布局骨架 | ongoing |
| 2026-05-29 | redesign-realtime-delta-projection-search (线A) | `feature/test/.../viewmodel/TestSessionViewModel.kt` | `_realtimeDeltaState` 投影路径：删 prevMatchedIdx 跨帧 cache 传递（Alt B stateless）。**与 unify-lap-count 同文件不同函数**（后者改 lapCount 配对）→ 约定 redesign-delta 先合回，unify-lap-count rebase 跟上 | ongoing |

---

## 7. Phase 治理表

> 长项目按 Phase 分阶段推进；每个 Phase 的 entry / exit gate 必须在 CLAUDE.md Review v3 框架下闭环。
>
> **Phase Exit Review** 是强制 gate（每个 phase 最后一个 round 闭环时必跑），盘点本 phase 期间产生的所有 deferred memo + tasks §8 backlog，逐个 disposition 决议（下个 phase 内闭环 / 推迟 / 移除），生成 chore commit `chore(phase): Phase N exit review`。
>
> 下个 phase 第一个 round 启动前必须 reference 上个 phase exit commit（确认看过且决议有效）。

### Phase 0 · 数据层闭合（**done 2026-05-04**）

- **Entry**：A round (`fix-lap-binary-ts-hygiene`) 已 archived 2026-05-02 (commit `599562e`)
- **Round 列表（全部已 archived）**：
  - A. `fix-lap-binary-ts-hygiene`（archive/2026-05-02，commit `599562e`）
  - §8.3 `fix-lap-crossing-clock-hygiene`（archive/2026-05-03，commit `43bbac4`）
  - §8.4 / M. `fix-perftest-binary-ts-hygiene`（archive/2026-05-04，commit `76a2735` 实施 + `a708ac1` 归档）
- **Exit gate（满足）**：
  - ✅ 三 round 都 archived
  - ✅ 主区 binary 时钟域 + crossing 时钟域 + PERFORMANCE_TEST 时钟域全闭合
  - ✅ Phase Exit Review：7 个 deferred memo 全决议（见下方 disposition 表）
- **产出**：
  - 数据层完整闭环——LAP_SESSION 任意窗口 readLapSamples + crossing wallClock + PERFORMANCE_TEST 任意窗口都可用
  - 为 Phase 1（单圈数据图表）铺好 reader 侧契约
- **Phase 0 Exit commit**：`chore(phase): Phase 0 exit review`（待 commit；包含 7 个 deferred memo disposition + 实际/估时 retrospective）
- **Deferred memo disposition 表**（2026-05-04 决议）：

  | # | Memo | 起源 | Disposition |
  |---|---|---|---|
  | 1 | `laptime-ts-hygiene-deferred.md` | A round | ✅ done（A round 闭环 archive/2026-05-02）|
  | 2 | `lap-crossing-clock-hygiene-deferred.md` | A v2 收紧 | ✅ done（archive/2026-05-03，commit `43bbac4`）|
  | 3 | `perftest-binary-ts-hygiene-deferred.md` | A §1 grep | ✅ done（archive/2026-05-04，commit `76a2735` + `a708ac1`）|
  | 4 | `laptime-gps-filter-integration-deferred.md` | B round | 🟡 Phase 1 期间任意时机插入（B round `wire-laptime-to-gps-filter`，半天 small；与 Phase 1 round 函数级不交叉，可平行；解锁 LAP_INVALIDATED_DEBOUNCE_MIN_COUNT 阈值降回 1）|
  | 5 | `speed-curve-real-data-persistence-deferred.md` | F round | 🟢 合并到 Phase 1 第一个 round `lap-data-readers`（`getDataPointsForResult(testId)` 与 `getLapTelemetry(sessionId, lapIndex)` 同根，统一 repository 数据契约）|
  | 6 | `perftest-cascade-orphan-cleanup-deferred.md` | J round | 🔵 推迟到 release 前（cleanup 类，不阻塞 Phase 1/2/3 功能；归类 follow-up `cleanup-perftest-telemetry-session-orphan` round）|
  | 7 | `records-by-track-filter-deferred.md` | add-debug-preset-track 真机暴露 | 🟡 Phase 1 cleanup 或 Phase 2 内（UI/查询 layer 优化，不与 chart 数据底座冲突）|

- **Phase 0 retrospective**：3 round 闭环 + 5 deferred memo 沉淀（7 中 3 是闭环路径上自然产生的下一阶段引子）。L1 review 平均 2-3 轮 plateau。Codex 双线 review 在 5 月初稳定，后期 3 天不可用期间 CC 主会话 + Opus 子 agent 单线 L1/L2 已能闭环 small bug 修复 round（M round 实测）。estimated 总 ~2 days / actual ~4 days（含 review 期 + Codex 不可用顺延）。

### Phase 1 · 单圈数据图表 + 多圈比较

- **Entry**：Phase 0 exit commit 通过 + 第一个 round 立项前 reference Phase 0 exit commit；同时把 deferred memo #5 (speed-curve-real-data-persistence) 与 round 1 (lap-data-readers) 合并 scope，#4 (laptime-gps-filter-integration) 在 phase 任意时机插入
- **Round 列表（5 round / ~9.5 天纯实施 + 1 合并 deferred memo）**：
  - 1. `lap-data-readers` —— repository 加 `getLapTelemetry(sessionId, lapIndex)` + `getDataPointsForResult(testId)` 双 high-level API（合并 deferred memo #5）
  - 2. `chart-and-map-components` —— SpeedTimeChart / AccelTimeChart / SectorBar / TrackPolylineMap 基础组件库
  - 3. `lap-detail-screen-with-cursor` —— 单圈详情屏 + 时间游标拖动联动 map
  - 4. `lap-comparison-time-align` —— 多圈对齐算法（按 distance 重采样）
  - 5. `lap-comparison-screen-with-cursor` —— 多圈比较屏 + cursor 同时标多圈位置
  - **+ B round** `wire-laptime-to-gps-filter` —— 任意时机插入（与上 5 round 函数级不交叉；半天 scope）
- **Exit gate**：
  - 5 round + B round 都 archived
  - 单圈详情屏 + 多圈比较屏真机验证通过（华为 8KE0219522008434）
  - Phase Exit Review

- **Phase 1 in-flight status**（2026-05-05）：

  | Round | 主区合回 | 归档（archive 目录完整性） | git add/commit | push |
  |---|---|---|---|---|
  | W1. lap-data-readers (round 1) | ✅ 3c2f2d9 | ✅ archive/2026-05-04（含 review trail + 修订 metrics）| ⏳ untracked | ⏳ user 拍板 |
  | W2. chart-and-map-components (round 2) | ✅ fc0afc1 | ✅ archive/2026-05-04 | ⏳ untracked | ⏳ user 拍板 |
  | W3. lap-comparison-time-align (round 4) | ✅ a0cbfb7 | ✅ archive/2026-05-04 | ⏳ untracked | ⏳ user 拍板 |
  | W4. wire-laptime-to-gps-filter (B round) | ✅ e2f4417 | ✅ archive/2026-05-05 | ✅ 6359c68 chore(openspec) 已 commit | ⏳ user 拍板 |
  | round 3. lap-detail-screen-with-cursor (Tier2) | ⏳ 未启动 | — | — | — |
  | round 5. lap-comparison-screen-with-cursor (Tier2) | ⏳ 未启动 | — | — | — |
  | follow-up. unify-perftest-anchor-cross-clock (Tier1.5) | ✅ 9445bff | ✅ archive/2026-05-29（含 metrics.yaml）| ⏳ untracked | ⏳ user 拍板（跟 W1-W4 一批）|
  | **phase1-hardening-w2-w3-w4-mimo-debt** (Phase 1 hardening) | ✅ 主区 3 commit ff-only：2132e10 / 1a518ed / f91d74c | ⏳ 工件 cp 主区 archive 待做（user 决定时机） | ✅ 已 commit (3 commits) | ⏳ user 拍板 |
  | **fix-lap-debug-mode-sector-chain-test-after-min-count-1** (线B) | ✅ 128388a | ✅ archive/2026-05-29 | ✅ 5b767fc | ⏳ user 拍板 |
  | **wire-mock-telemetry-to-w1-real-classes** (线B) | ✅ a6ca70c | ✅ archive/2026-05-29（--skip-specs）| ✅ 1a67aaa | ⏳ user 拍板 |
  | **unify-lap-count-pairing-semantics** (线B) | ✅ 738bc5a | ✅ archive/2026-05-29 | ✅ ab1838e | ⏳ user 拍板 |
  | **improve-test-execution-progress-bar** (线C) | ✅ f3b4e04（⚠️ 此前从未合回，本次补 commit+rebase+合回） | ✅ archive/2026-05-29 | ✅ fe2b5ed | ⏳ user 拍板 + **真机重验**（上批 APK 不含本改动）|
  | **future-sector-derivation** (M2 前置 · #16 契约改) | ✅ 6995696 | ✅ archive/2026-05-29（含 metrics + 1 轮 L1 review verdict=PASS） | ✅ 85b2bd4 | ⏳ user 拍板 |
  | **lap-detail-screen-with-cursor** (M2 核心交付物 1 · **首个可见单圈数据分析屏**) | ✅ 79d4f4a | ✅ archive/2026-05-30 | ✅ 3462e88 | ⏳ user 拍板 + **真机视觉验证 MANDATORY**（点圈→4 组件→游标→accel→vivo 小屏 gate）|

- **Phase 1 闭环顺序建议**：
  1. W3/W4 归档 + Tier2 round 3/5 立项前补 push 顺序拍板（4 round 都已合回主区，按 commit 时序 push 即可）
  2. follow-up `unify-perftest-anchor-cross-clock` 作为 Tier1.5 插入：W4 归档之后、Tier2 `lap-detail-screen-with-cursor` 立项之前（trivial 复杂度 ~1.5h，不阻塞 Tier2 落地，但 Tier2 SpeedCurveReal 消费 `getDataPointsForResult` 时若漏 sentinel guard 有崩塌风险）
  3. Tier2 round 3 + 5 按看板 §5 现有 round 列表推进
  4. Phase Exit Review 在最后一个 round 闭环时跑（参 Phase 0 §205-216 模式：所有 deferred memo 逐个 disposition）

- **Phase 1 L2 review 状态**（2026-05-05 user 拍板补跑，因 mimo 实施期全部跳过 L2）：

  | Round | L2 review | review trail 路径 | 关键 findings | open（待 Phase 1 协调消化） |
  |---|---|---|---|---|
  | W1. lap-data-readers | ✅ Opus 双线（A 线模板 + B 线差异化） | `archive/2026-05-04-lap-data-readers/review-l2-opus-{a,b}.md` | 4 P1（commit hash 失配 / spec gate-A 内部矛盾 + macOS BSD 不兼容 / spec Req1 isEmpty normative 缺 / 跨时钟域 anchor）+ 8 P2 | hash + spec 工件级已修订入归档 commit `0cd9dbc`；跨时钟域走 follow-up round `unify-perftest-anchor-cross-clock`（已沉淀 memo） |
  | W2. chart-and-map-components | ✅ Opus 双线 | `archive/2026-05-04-chart-and-map-components/review-l2-opus-{a,b}.md` | 5 P0（D1↔D5 conflate / mockSingleLap 整除 silent off-by-one / spec mockMultiLap 返回类型错 / all-null + partial-null spec 行为零自动化覆盖）+ 8 P1（grep gate §8.7 漏 flags / §8.4 滑窗 trivially-pass / SpeedTimeChart n=1 silent / FakeLapTelemetry 切换 scope 低估 / 性能 baseline 假数据 / cursor highlight O(n)）+ 8 P2 | open |
  | W3. lap-comparison-time-align | ✅ Opus 双线 | `archive/2026-05-04-lap-comparison-time-align/review-l2-opus-{a,b}.md` | 0 P0；4 P1（flags 字段静默丢弃【双线共识强 P1】/ resampleByGrid 死参数 + @Suppress 掩盖 / bearing-round dead spec）+ 9 P2 | open |
  | W4. wire-laptime-to-gps-filter | ⏳ 未跑（mimo 跳过；user 决定是否补跑） | — | — | open |

- **跨 round 共识 P1（mimo 根本盲点）**：
  - **flags 字段跨 round 时序耦合**：W1 commit 3c2f2d9 (3 月 4 日 00:53 最晚落地) 在 LapTelemetrySample 追加 `flags: Int = 0`；W2 spec 仍写 7 字段（grep gate §8.7 漏 flags 验证）+ W3 interpolate/fallback 路径不传 flags（数据静默归零）→ 双 round 双线共识 P1；L1 review 在各 round 内独立判定 plateau，**v3 流程缺跨 round 时序耦合检查**

- **Phase 1 deferred memo backlog**（在 round 期间沉淀，Phase Exit Review 时统一 disposition）：

  | # | Memo | 起源 | Disposition |
  |---|---|---|---|
  | 8 | `lap-perftest-anchor-cross-clock-deferred.md` | W1 round L2 review (Opus B 线) P1-1 | ✅ **done（2026-05-29）** round `unify-perftest-anchor-cross-clock` 实施方案 A（reader sentinel guard + spec invariant 三条款 + case L），归档 archive/2026-05-29；待 push。方案 B（迁移 timestamp 到本地壁钟）留 P3 backlog `migrate-perftest-timestamp-to-wallclock`（触发条件：长 session GPS-UTC 漂移可见错乱）|
  | 9 | **`fix-lap-debug-mode-sector-chain-test-after-min-count-1`** follow-up round | phase1-hardening-w2-w3-w4-mimo-debt 期 §10.5 backlog（apply §7 测试 gate 发现）| 🔵 **不今日启动**（2026-05-05 user 拍板放看板）：W4 commit `e2f4417` 改 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT 3→1` 副作用让 `TestSessionViewModelTrackLapTest.lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete` baseline HEAD 4326e11 已 fail（expected:1 实际 0）。W4 mimo 缺漏 test 同步。~~需 user 拍板 lap business decision~~ → **✅ user 已拍板（2026-05-29）：仍闭合（宽容）—— 起终点过线两次就算一圈，不要求中间 sector 门全过**。理由：debug 模式容错高（赛道 sector 未配全 / 信号丢帧也应能出圈速）。**实施方向**：修 `lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete` test expected 回 1（语义=宽容闭合）+ 加 invalidation banner 状态 assertion（sector 不完整时给提示但仍闭圈）；保留 W4 的 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT=1`（与宽容闭合一致，不需回退阈值）。Round 复杂度 small / road-test-first。**待启动**（线 B，user 决定先路测一批再继续）|
  | 10 | **`automate-design-drift-detection`** follow-up round（OQ5）| phase1-hardening-w2-w3-w4-mimo-debt design.md OQ5（F1 #17 governance root-cause 修复路径）| 🔵 **不今日启动**：F1 #17 条款 enforce 路径仅靠 CC 自查，mimo 模式下不在 CC apply 流程内 → F1 dead；root-cause 修复 = `/opsx:ff` skill 自动注入 metrics.yaml schema 字段 + commit-time hook grep 自动抓 drift。复杂度 medium（修改 OpenSpec CLI 行为 + 加 git hook，跨 phase）；建议 Phase 2 启动时机评估 |

- **W1 round 期间额外 follow-up backlog**（在归档 tasks.md §10 沉淀，等价于 Phase 1 Tier2/cleanup 期间消化）：
  - §10.6 `unify-lap-count-pairing-semantics` round（lapCount 双语义收敛）
  - §10.7 `future-sector-derivation-round`（sectorBoundaries 多元素扩展）
  - §10.8 交错 null 模式 invariant 守护（future round 责任）
  - §10.10 测试 invariant assertion（elapsedMsInLap≥0 / sectorBoundaries.size==1）trivial 加固
  - §10.11 测试 P2 cleanup（fake DAO 命名 / ProtectionDomain warning / case A message）—— 推到 D round style-debt-cleanup 一并
  - §10.12 spec Scenario 7 case K（混合 row 测试覆盖）
  - §10.13 design R7 SectorBar Tier2 决策 拍板（Tier2 round 3 design 期 MUST 选定）

### Phase 2 · Session 内置摄像头

- **Entry**：Phase 1 exit + L0 需求理解 review 已跑（camera 模块新引入，触发 L0 必跑条件 (a)）
- **Round 列表（5 round / ~6.5 天纯实施）**：
  - 1. `camera-permission-and-preview` —— CameraX 权限流 + preview
  - 2. `camera-recording-pipeline` —— 启停录制 + 文件命名 + LapSession wallClock 起点同步
  - 3. `session-video-metadata-persist` —— Room migration + entity 加 `videoFilePath / videoStartedAtWallClock`
  - 4. `recording-toggle-and-indicator` —— 圈速主屏录视频开关 + UI indicator
  - 5. `recording-resource-safety` —— 存储满 / 电量低 / 温度过高 / 异常退出处理
- **Exit gate**：
  - 5 round 都 archived
  - 真机端到端：startSession → camera record → endSession 完整链路 + 录制时长 == session 时长（误差 < 200ms）

### Phase 3 · 视频叠加导出

- **Entry**：Phase 1 + Phase 2 exit（功能 3 依赖功能 1 的 chart 组件 + 功能 2 的视频文件 + wallClock 起点）
- **Round 列表（5 round / ~10 天纯实施）**：
  - 1. `video-frame-extractor` —— MediaCodec 提帧 + PTS + 按 wallClock 取帧
  - 2. `overlay-widgets-system` —— widget 系统（Gauge / LapTimer / Delta / Sector / Map）
  - 3. `overlay-realtime-preview` —— 实时叠加预览 + 用户调布局
  - 4. `video-export-pipeline` —— 离线渲染 + MediaMuxer + 进度 / 取消 / 内存压力
  - 5. `video-export-ui` —— 导出屏 + 分享 intent
- **Exit gate**：
  - 5 round 都 archived
  - 真机端到端：能从 LapSession 历史录制视频 + 叠加 overlay 导出 mp4，分享到外部 app

---

## Phase 治理通用规则

- **Phase entry commit**：`chore(phase): Phase N entry`，body 含上个 phase exit 决议引用 + 本 phase 第一个 round 立项前的 L0/L1 review 摘要
- **Phase exit commit**：`chore(phase): Phase N exit review`，body 含本 phase 所有 deferred memo 决议（disposition：下个 phase 内闭环 / 推迟 / 移除）
- **跨 phase 间隔**：不允许有"某 round 半完成"的状态——所有 phase 内 round 必须 archived 才能 exit
- **review v3 适用**：每个 phase 内的每个 round 都必须走 L1/L2 双线 review（Codex + Opus 子 agent）
- **估时校准**：每个 phase exit commit 附 phase 总估时 vs 实际，沉淀 retrospective 给下个 phase 校准
