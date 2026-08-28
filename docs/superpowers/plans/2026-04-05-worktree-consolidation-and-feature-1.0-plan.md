# Worktree 固化与 feature-1.0 主线收束 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将主目录与 `track-lap-timing` worktree 中尚未进入任何 commit 的有效内容固化到对应分支，删除业务 worktree 对真实停靠点的承载，再以清晰分支作为后续 `feature-1.0` 主线收束输入。

**Architecture:** 先按 worktree 维度做“事实固化”，不直接重写历史、不直接合并成最终主线。主目录 `refactor/multi-module-architecture` 与 `feature_ctg_20260405_tfic_rcz_geometry` 分别形成清晰停靠提交，`feature/lap-debug-mode` 仅整理成待检阅状态；待 worktree 清空删除后，再以分支为唯一 review 单位继续做主线整合。

**Tech Stack:** Git worktree、Git branch、Gradle/Android 项目、Markdown specs/plans

---

### Task 1: 固化主目录文档资产

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-04-03-replay-lap-timing-closure-plan.md`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-04-04-simulator-connected-device-panel.md`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-04-05-tfic-rcz-track-geometry-alignment-plan.md`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-04-03-track-laptiming-page-first-stage-design.md`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-04-05-tfic-rcz-track-geometry-alignment-design.md`

- [ ] **Step 1: 确认主目录当前仅有文档类未跟踪文件需要固化**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" status --short
```
Expected: 输出包含上述 docs 文件，且 `.claude/worktrees/*` 仍为未跟踪目录。

- [ ] **Step 2: 分离不应纳入本次提交的本地目录**

保留在工作区中但不要纳入本次提交的路径：
```text
.claude/worktrees/agent-ac4e4f0b/
.claude/worktrees/agent-team-governance/
app/src/test/java/com/blazepush/PermissionRequestOutcomeTest.kt
```

- [ ] **Step 3: 精确暂存文档资产**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" add \
  docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md \
  docs/superpowers/plans/2026-04-03-replay-lap-timing-closure-plan.md \
  docs/superpowers/plans/2026-04-04-simulator-connected-device-panel.md \
  docs/superpowers/plans/2026-04-05-tfic-rcz-track-geometry-alignment-plan.md \
  docs/superpowers/specs/2026-04-03-track-laptiming-page-first-stage-design.md \
  docs/superpowers/specs/2026-04-05-tfic-rcz-track-geometry-alignment-design.md
```
Expected: 仅 docs 文件进入 staged 状态。

- [ ] **Step 4: 检查 staged 内容仅包含文档**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" diff --cached --name-status
```
Expected:
```text
A or M docs/superpowers/plans/...
A or M docs/superpowers/specs/...
```
且不包含 `.claude/` 和 `PermissionRequestOutcomeTest.kt`。

- [ ] **Step 5: 提交主目录文档停靠点**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" commit -m "$(cat <<'EOF'
docs(branch): 固化主目录圈速与回放设计文档

将主目录中尚未进入提交历史的圈速、回放和赛道几何相关计划与设计文档
固化到 refactor 分支，恢复文档层停靠点可追溯性。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 生成一个仅包含文档的 commit。

---

### Task 2: 判断主目录测试文件是否保留

**Files:**
- Review: `/Users/wattledgnata/traeProjects/gps-app/app/src/test/java/com/blazepush/PermissionRequestOutcomeTest.kt`

- [ ] **Step 1: 查看测试文件内容与职责**

Run:
```bash
sed -n '1,220p' "/Users/wattledgnata/traeProjects/gps-app/app/src/test/java/com/blazepush/PermissionRequestOutcomeTest.kt"
```
Expected: 能判断该测试是否属于主目录当前主线，还是一次性实验文件。

- [ ] **Step 2: 根据文件内容做二选一决定**

决策规则：
```text
如果它是稳定、可解释、与现有 app 权限流程直接相关的测试，则保留并单独提交。
如果它是一次性验证、重复覆盖、或与当前主线无关，则继续保持未跟踪，不纳入本轮固化。
```

- [ ] **Step 3: 若保留，则单独暂存该文件**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" add app/src/test/java/com/blazepush/PermissionRequestOutcomeTest.kt
```
Expected: staged 中只新增这一条测试文件。

- [ ] **Step 4: 若保留，则单独提交**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" commit -m "$(cat <<'EOF'
test(app): 固化权限请求结果回归测试

将主目录中尚未进入版本历史的权限请求结果测试单独固化，避免与文档或
圈速主线改动混合。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 测试文件获得独立停靠 commit。

- [ ] **Step 5: 若不保留，则记录结论并保持未提交**

记录：
```text
PermissionRequestOutcomeTest.kt 未纳入本轮主线固化，原因是与当前主线无关或价值不足。
```

---

### Task 3: 固化接收端协议时间修复

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt`
- Exclude: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/app/src/test/java/com/blazepush/data/service/parser/RaceChronoParserTest.kt`

- [ ] **Step 1: 仅暂存接收端协议时间修复文件**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add \
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt \
  core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt
```
Expected: staged 中仅包含 parser 修复与新测试。

- [ ] **Step 2: 确认误碰文件未被带入**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" diff --cached --name-status
```
Expected:
```text
M core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt
A core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt
```
且不包含 `app/src/test/java/com/blazepush/data/service/parser/RaceChronoParserTest.kt`。

- [ ] **Step 3: 运行协议时间测试**

Run:
```bash
cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :core:bluetooth:testDebugUnitTest --tests "com.blazepush.core.bluetooth.parser.RaceChronoParserProtocolTimeTest"
```
Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: 提交协议时间修复停靠点**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "$(cat <<'EOF'
fix(bluetooth): 接收端对齐协议时间戳

让接收端使用协议时间包与主包中的时间字段还原 GPS 时间，避免回放场景下
继续以本机墙钟覆盖 timestamp。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 生成独立的接收端时间修复 commit。

---

### Task 4: 固化 replay 与 simulator 支撑

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/main/assets/replay/tianfu_track_replay_5hz.json`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt`

- [ ] **Step 1: 精确暂存 replay 与 simulator 支撑文件**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add \
  simulator/src/main/assets/replay/tianfu_track_replay_5hz.json \
  simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt \
  simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt \
  simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt \
  simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt \
  simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt \
  simulator/src/test/java/com/blazepush/simulator/viewmodel/SimulatorViewModelReplayLoopTest.kt
```

- [ ] **Step 2: 运行 simulator 相关测试**

Run:
```bash
cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :simulator:testDebugUnitTest --tests "com.blazepush.simulator.data.GpsDataGeneratorReplayClockTest" --tests "com.blazepush.simulator.data.replay.RczLap7ReplayConversionTest" --tests "com.blazepush.simulator.viewmodel.SimulatorViewModelReplayLoopTest"
```
Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: 提交 replay / simulator 停靠点**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "$(cat <<'EOF'
feat(simulator): 固化 replay 时钟与循环播放支撑

将 RCZ 回放资源、simulator 回放时钟对齐和循环播放相关实现与回归测试固化，
为圈速主线提供稳定的回放输入基础。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 生成独立 replay / simulator commit。

---

### Task 5: 固化圈速主线运行时代码与配套测试

**Files:**
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/build.gradle.kts`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`
- Modify: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/di/DomainModuleKoinTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
- Test: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt`

- [ ] **Step 1: 仅暂存圈速主线运行时代码与主线测试**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add \
  feature/test/build.gradle.kts \
  feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt \
  feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt \
  feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt \
  feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt \
  feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt \
  feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt \
  feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt \
  feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt \
  feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt \
  feature/test/src/test/java/com/blazepush/feature/test/di/DomainModuleKoinTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/repository/TrackCatalogTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalogTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/usecase/GateCrossingDetectorTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt \
  feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt
```

- [ ] **Step 2: 确认实验工具测试未被带入**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" diff --cached --name-only | grep "ReplayGateFitter\|ReplayTemporaryGate"
```
Expected: 无输出。

- [ ] **Step 3: 运行 feature:test 关键测试集合**

Run:
```bash
cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.GateCrossingDetectorTest" --tests "com.blazepush.feature.test.usecase.LapTimingEngineTest" --tests "com.blazepush.feature.test.viewmodel.TestSessionViewModelTrackLapTest"
```
Expected: `BUILD SUCCESSFUL`；若 `DomainModuleKoinTest` 或 `TrackCatalogTest` 失败，先记录为后续清理项，不要将失败测试静默忽略。

- [ ] **Step 4: 提交圈速主线停靠点**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "$(cat <<'EOF'
feat(laptiming): 固化圈速主线运行时与回归测试

将赛道目录、穿线判定、圈速状态机、运行时装配和配套回归测试固化为当前
圈速主线停靠点，为后续统一主线收束提供稳定骨架。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 生成主功能骨架 commit。

---

### Task 6: 固化资源与设计文档

**Files:**
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/assets/replay/tianfu_track.vbo`
- Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json`
- Modify/Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/docs/superpowers/plans/*.md`
- Modify/Create: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/docs/superpowers/specs/*.md`

- [ ] **Step 1: 暂存资源与文档资产**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" add \
  feature/test/src/main/assets/replay/tianfu_track.vbo \
  feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json \
  docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md \
  docs/superpowers/plans/2026-04-02-replay-temporary-gate-implementation-plan.md \
  docs/superpowers/plans/2026-04-04-lap-debug-timing-card-reset-implementation.md \
  docs/superpowers/plans/2026-04-04-lap-timing-start-finish-closure-fix.md \
  docs/superpowers/plans/2026-04-04-lap7-replay-source-replacement.md \
  docs/superpowers/plans/2026-04-04-replay-loop-playback-implementation.md \
  docs/superpowers/plans/2026-04-04-tfic-lpcc-preset-track-plan.md \
  docs/superpowers/plans/2026-04-05-replay-clock-source-alignment-plan.md \
  docs/superpowers/specs/2026-04-02-replay-temporary-gate-design.md \
  docs/superpowers/specs/2026-04-04-lap-debug-timing-card-reset-design.md \
  docs/superpowers/specs/2026-04-04-replay-loop-playback-design.md \
  docs/superpowers/specs/2026-04-04-tfic-lpcc-preset-track-design.md \
  docs/superpowers/specs/2026-04-05-replay-clock-source-alignment-design.md
```

- [ ] **Step 2: 检查 staged 内容只包含资源与文档**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" diff --cached --name-status
```
Expected: 仅出现 `feature/test/src/main/assets/replay/*` 与 `docs/superpowers/*`。

- [ ] **Step 3: 提交资源与文档停靠点**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" commit -m "$(cat <<'EOF'
docs(track): 固化回放资源与圈速设计文档

将 track-lap-timing worktree 中仍停留在文件系统层的回放输入资源与设计文档
纳入提交历史，恢复当前圈速方向的可追溯文档停靠点。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```
Expected: 文档与资源文件获得独立 commit。

---

### Task 7: 整理 `lap-debug-mode` 为待检阅状态

**Files:**
- Review: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/lap-debug-mode/feature/test/...`

- [ ] **Step 1: 列出 `lap-debug-mode` 当前未提交文件**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/lap-debug-mode" status --short
```
Expected: 输出当前 11 条左右未提交文件，覆盖 lap debug 页面、model、usecase、test。

- [ ] **Step 2: 导出待检阅清单供人工判断**

整理为：
```text
UI 页面文件
状态/模型文件
用例文件
测试文件
```
并记录哪些文件疑似已被 `track-lap-timing` 吸收、哪些疑似仍独有。

- [ ] **Step 3: 不做主线合并，只确保状态可 review**

要求：
```text
不提交到 feature-1.0 候选主线
不删除分支
不删除文件
仅保持工作区可查看、可比较、可人工决策
```

---

### Task 8: 删除业务 worktree，回到分支治理

**Files:**
- Remove worktree: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing`
- Remove worktree: `/Users/wattledgnata/traeProjects/gps-app/.worktrees/lap-debug-mode`（仅在人工确认后）

- [ ] **Step 1: 检查主目录与 `track-lap-timing` 是否仍有未提交内容**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" status --short && echo "---" && git -C "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" status --short
```
Expected: 仅剩明确决定暂缓或不提交的文件。

- [ ] **Step 2: 删除 `track-lap-timing` worktree**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" worktree remove "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing"
```
Expected: worktree 被移除，分支 `feature_ctg_20260405_tfic_rcz_geometry` 保留。

- [ ] **Step 3: `lap-debug-mode` 仅在人工确认后删除 worktree**

Run:
```bash
git -C "/Users/wattledgnata/traeProjects/gps-app" worktree remove "/Users/wattledgnata/traeProjects/gps-app/.worktrees/lap-debug-mode"
```
Expected: 只在用户确认其状态已足够 review 后执行。

- [ ] **Step 4: 记录后续主线整合输入**

保留这两条作为后续统一主线输入：
```text
feature_ctg_20260405_tfic_rcz_geometry 作为圈速主骨架
refactor/multi-module-architecture 作为并行增量来源
```

---

## Self-Review

### Spec coverage
- worktree 停靠点识别：Task 1、Task 7、Task 8 覆盖
- 主目录与 track-lap-timing 固化：Task 1、3、4、5、6 覆盖
- lap-debug-mode 仅保留待检阅：Task 7 覆盖
- 删除 worktree、恢复分支治理：Task 8 覆盖
- 不直接创建 `feature-1.0`：整份计划均未包含该动作

### Placeholder scan
- 无 `TODO`、`TBD`、`later` 等占位词
- 每个任务包含明确路径、命令和预期输出

### Type consistency
- 统一使用 `refactor/multi-module-architecture`、`feature_ctg_20260405_tfic_rcz_geometry`、`feature/lap-debug-mode`
- 统一将最终统一主线表述为“后续主线候选”而非立即创建的真实分支
