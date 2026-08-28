# Worktree 固化与 feature-1.0 主线收束设计

## 背景

当前本地研发状态已经不适合继续依赖 worktree 作为长期停靠机制：

- 真正最新的工作内容分散在多个 worktree 的未提交改动中。
- 分支名、HEAD 提交与“当前真实停靠点”不再一致。
- 人工 review 难以只依赖分支和提交历史完成。
- 任务停靠点不清晰，导致后续很难收束出统一主线。

用户已明确新的治理方向：

- 暂不回溯 0-1 测速完成节点。
- 第一个封闭版本不强调当前必须包含哪些能力，而是先把除地图方向外的有价值研发资产收束起来。
- 当前目标不是直接发布，而是把杂乱分支中的有效工作整理到统一主线候选上。
- 未来希望收束到一个统一分支，暂命名为 `feature-1.0`。

## 目标

本次设计只解决“如何从 worktree 状态恢复为清晰分支治理”的问题，不直接执行 git 操作。

目标包括：

1. 明确当前 worktree 的真实停靠点。
2. 将仍未进入任何 commit 的工作内容固化到对应分支。
3. 删除承载真实业务状态的 worktree，让后续 review 回到“只看分支和提交”的模式。
4. 将 `refactor/multi-module-architecture` 与 `feature_ctg_20260405_tfic_rcz_geometry` 收束为统一主线候选。
5. 将 `feature/lap-debug-mode` 作为待人工检阅资产单独处理，不直接并入主线。

## 当前 worktree 停靠点结论

### 1. 主目录

- 路径：`/Users/wattledgnata/traeProjects/gps-app`
- 当前分支：`refactor/multi-module-architecture`
- HEAD：`a431eca`
- 状态：存在未跟踪文档和测试文件
- 语义：多模块 / BLE / GPS 主链路母线，额外包含一条有效的 BLE 调试链路修复提交

### 2. `track-lap-timing`

- 路径：`/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing`
- 当前分支：`feature_ctg_20260405_tfic_rcz_geometry`
- HEAD：`beb9c86`
- 状态：存在大量未提交改动
- 语义：当前最重的圈速 / replay / RCZ / gate / parser 修复主工作区

### 3. `lap-debug-mode`

- 路径：`/Users/wattledgnata/traeProjects/gps-app/.worktrees/lap-debug-mode`
- 当前分支：`feature/lap-debug-mode`
- HEAD：`fda6ded`
- 状态：存在未提交改动
- 语义：早期 lap debug 页面与用例雏形，暂视为历史遗留资产，需要人工检阅

### 4. 其他 worktree

- `amap-backup`：地图方向，当前整理中排除
- `apk-naming-rule`：历史干净停靠点，可暂不处理
- `.claude/worktrees/*`：agent/治理相关，不纳入产品主线

## 设计决策

### 决策 1：采用“方案 A”收束

即：

1. 先分别固化主目录与 `track-lap-timing` 的未提交内容到各自分支。
2. 暂不把 `lap-debug-mode` 并入主线，只保留为待检阅资产。
3. 待 worktree 清空并删除后，再以分支为唯一 review 单位继续整理。
4. 以 `feature_ctg_20260405_tfic_rcz_geometry` 为骨架，吸收 `refactor/multi-module-architecture` 的有效增量，形成统一主线候选。

### 决策 2：主线收束骨架以 CTG 分支为准

原因：

- 当前圈速相关最新业务停靠点在 `feature_ctg_20260405_tfic_rcz_geometry`。
- `refactor/multi-module-architecture` 更像工程母线和并行增量来源，而不是圈速主线本体。
- 统一主线候选应优先围绕最新业务资产构建，而不是围绕当前 shell 所在分支构建。

### 决策 3：`lap-debug-mode` 只做人工检阅，不进入当前主线

原因：

- 它更像早期阶段 worktree。
- 其中未提交内容可能部分已被后续 worktree 吸收，也可能仍保留少量独有资产。
- 在未人工确认之前，不应直接混入统一主线。

## 可提交单元分组

### A. 主目录 `refactor/multi-module-architecture`

#### 准备提交

文档资产：

- `docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md`
- `docs/superpowers/plans/2026-04-03-replay-lap-timing-closure-plan.md`
- `docs/superpowers/plans/2026-04-04-simulator-connected-device-panel.md`
- `docs/superpowers/plans/2026-04-05-tfic-rcz-track-geometry-alignment-plan.md`
- `docs/superpowers/specs/2026-04-03-track-laptiming-page-first-stage-design.md`
- `docs/superpowers/specs/2026-04-05-tfic-rcz-track-geometry-alignment-design.md`

#### 暂缓提交

- `app/src/test/java/com/blazepush/PermissionRequestOutcomeTest.kt`

#### 不应提交

- `.claude/worktrees/agent-ac4e4f0b/`
- `.claude/worktrees/agent-team-governance/`

### B. `track-lap-timing`

#### 准备提交

1. 接收端协议时间修复：
- `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`
- `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt`

2. 圈速主线运行时代码：
- `feature/test/build.gradle.kts`
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`

3. 圈速主线测试：
- `feature/test/src/test/java/com/blazepush/feature/test/di/DomainModuleKoinTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`

4. replay / simulator 支撑：
- `simulator/src/main/assets/replay/tianfu_track_replay_5hz.json`
- `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt`
- `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`
- `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt`
- `simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`
- `simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`

5. 资源文件：
- `feature/test/src/main/assets/replay/tianfu_track.vbo`
- `feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json`

6. 文档资产：
- `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md`
- `docs/superpowers/plans/2026-04-02-replay-temporary-gate-implementation-plan.md`
- `docs/superpowers/plans/2026-04-04-lap-debug-timing-card-reset-implementation.md`
- `docs/superpowers/plans/2026-04-04-lap-timing-start-finish-closure-fix.md`
- `docs/superpowers/plans/2026-04-04-lap7-replay-source-replacement.md`
- `docs/superpowers/plans/2026-04-04-replay-loop-playback-implementation.md`
- `docs/superpowers/plans/2026-04-04-tfic-lpcc-preset-track-plan.md`
- `docs/superpowers/plans/2026-04-05-replay-clock-source-alignment-plan.md`
- `docs/superpowers/specs/2026-04-02-replay-temporary-gate-design.md`
- `docs/superpowers/specs/2026-04-04-lap-debug-timing-card-reset-design.md`
- `docs/superpowers/specs/2026-04-04-replay-loop-playback-design.md`
- `docs/superpowers/specs/2026-04-04-tfic-lpcc-preset-track-design.md`
- `docs/superpowers/specs/2026-04-05-replay-clock-source-alignment-design.md`

#### 暂缓提交

- `app/src/test/java/com/blazepush/data/service/parser/RaceChronoParserTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitter.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitterTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt`
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt`

#### 不应提交

- `.claude/tmp/lap7_replay_preview.json`
- `openspec/changes/lap-debug-telemetry-and-crossing-fix/proposal.md`
- `openspec/changes/lap-debug-telemetry-and-crossing-fix/specs/lap-debug.md`
- `openspec/changes/lap-debug-telemetry-and-crossing-fix/tasks.md`
- `simulator/src/test/resources/replay/session_20260314_170249_天府赛道_lap7.rcz`

## 后续执行顺序

### 第一步：固化主目录

1. 提交主目录文档资产
2. 单独判断 `PermissionRequestOutcomeTest.kt` 是否需要保留
3. 不处理 `.claude/worktrees/*`

### 第二步：固化 `track-lap-timing`

1. 提交接收端协议时间修复
2. 提交 replay / simulator 支撑
3. 提交圈速主线运行时代码与主线测试
4. 提交资源文件
5. 提交文档资产
6. 暂缓实验性测试
7. 排除本地临时产物与 OpenSpec 文件

### 第三步：保留 `lap-debug-mode` 供人工检阅

- 仅整理为可检阅状态
- 不直接并入主线
- 后续再决定是否保留或吸收

### 第四步：删除 worktree 并回到分支治理

执行前提：

- 主目录与 `track-lap-timing` 的有效未提交改动都已进入 commit
- `lap-debug-mode` 至少已完成人工检阅前的状态固化或状态确认

执行结果：

- 真实停靠点回到“分支 + 提交”层面
- 后续再以 `feature_ctg_20260405_tfic_rcz_geometry` 为骨架，吸收 `refactor/multi-module-architecture` 的有效增量，形成统一主线候选

## 不在本次设计内的内容

- 不直接删除任何业务分支
- 不直接创建最终 `feature-1.0`
- 不在本轮处理地图方向
- 不把 `lap-debug-mode` 直接并入主线
- 不在本轮改写 git 历史

## 结论

本次设计的核心不是“立刻生成统一版本”，而是先把 worktree 中真实存在但尚未进入 commit 的工作固化下来，让当前项目从“依赖 worktree 停靠”恢复成“依赖分支停靠”。

在此基础上，`feature_ctg_20260405_tfic_rcz_geometry` 将作为统一主线候选骨架，`refactor/multi-module-architecture` 作为并行有效增量来源，`feature/lap-debug-mode` 作为待人工检阅资产单独处理。