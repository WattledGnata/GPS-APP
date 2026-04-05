# Replay Lap Timing Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用最短关键路径收尾当前圈速验证工作：先打通 replay 一圈验证，再基于该结论决定测试端页面的最终语义与后续实现范围。

**Architecture:** 先冻结“页面继续扩写”这条线，只收敛 replay 验证链路。保持 `LapTimingEngine` 与 `GateCrossingDetector` 主逻辑不动，在测试域内用 `ReplayTemporaryGateBuilder` 替代 `ReplayGateFitter`，拿到一个可信的“真实 replay 可完成一圈”或“仍失败且失败证据明确”的结论。随后再把这个结论反向约束 `lap-debug-mode` 工作区，只做必要的页面语义重定义，不继续盲目扩 UI。

**Tech Stack:** Kotlin, JUnit4, Gradle, Jetpack Compose, Android ViewModel, existing replay assets/tests

---

## 文件结构

### 第一阶段：replay 验证闭环（当前唯一主线）
1. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt` - replay 集成入口，最终要切到 `ReplayTemporaryGateBuilder`
2. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt` - 测试域 temporary gate 构造器
3. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt` - builder contract
4. `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt` - temporary gate 几何合理性
5. `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md` - replay 调查结论与停靠点

### 第二阶段：测试端页面语义收口（只有在第一阶段拿到结论后才做）
1. `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` - 当前仍绑定旧 `LapDebugAnalyzer`，需要根据 replay 结论决定是否继续替换
2. `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt` - 当前流程骨架已存在，只能做语义收口，不能继续扩范围
3. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt` - 决定入口命名与模式文案是否保留“圈速调试”
4. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt` - 决定配置页到底是“调试参数”还是“赛道/回放配置”
5. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` - 决定执行页展示的是原始采样还是 gate/lap 运行状态
6. `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt` - 决定结果页展示结构

---

## 关键路径（只保留 4 步）

### Task 1: 冻结非关键路径，只保留 replay 收尾主线

**Files:**
- Read only: `docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md`
- Read only: `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`

- [ ] **Step 1: 明确本轮成功标准，写入执行备注**

执行备注应固定为以下三条：

```md
本轮只追一个结论：真实 replay 是否能在不修改 LapTimingEngine / GateCrossingDetector 主逻辑的前提下完成一圈。
如果能完成，则记录“测试域链路打通”；如果不能完成，则记录“temporary gate 方案仍不足”，但也必须留下明确失败证据。
本轮不继续扩写 lap-debug-mode UI，不把页面完成度误当成圈速能力完成度。
```

- [ ] **Step 2: 人工核对当前阻塞已经收敛到 s2 / temporary gate，而不是状态机**

检查依据：
- `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md`
- `docs/superpowers/specs/2026-04-02-replay-temporary-gate-design.md`

期望结论：
- parser 锚点问题已不是当前主阻塞
- `LapTimingEngine` 不应继续背锅
- 当前唯一待收尾项是 `ReplayTemporaryGateBuilder` 是否足够支撑一圈验证

- [ ] **Step 3: 暂停对 `lap-debug-mode` 的进一步实现**

暂停范围：
- 不新增 UI 页面
- 不继续改 `TestSessionViewModel` 的圈速主逻辑
- 不继续补与 replay 结论无关的页面文案/交互

- [ ] **Step 4: Commit（仅当有文档性变更时）**

```bash
git add docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md
git commit -m "docs: freeze lap debug expansion pending replay closure"
```

---

### Task 2: 用 `ReplayTemporaryGateBuilder` 取代旧的 `ReplayGateFitter`

**Files:**
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
- Modify: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt`
- Read only: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayGateFitter.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt`
- Test: `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt`

- [ ] **Step 1: 先把集成入口从 fitter 切到 builder**

目标改动：

```kotlin
val rawGates = parser.parseVboGates(trackVbo(), replay.samples.first())
val gates = ReplayTemporaryGateBuilder().build(gates = rawGates, replaySamples = replay.samples)
val track = gates.toTrack(referenceSamples = replay.samples)
```

替换位置：
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`

- [ ] **Step 2: 跑最小测试组，先看 builder 当前实际能力**

Run:
```bash
./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"
```

Expected:
- 如果 PASS：说明 replay 验证主链已收口，进入 Task 3
- 如果 FAIL：记录失败点，只允许改 `ReplayTemporaryGateBuilder.kt`，不允许扩散到主逻辑

- [ ] **Step 3: 若失败，只做 builder 最小修补，不碰主逻辑**

允许修改：
- `selectAnchor(...)`
- window 边界选择
- temporary gate 中点/法线构造
- builder 内部 `passDirection` 生成方式

禁止修改：
- `LapTimingEngine`
- `GateCrossingDetector`
- 正式 Track 资产
- UI 代码

- [ ] **Step 4: 复跑相同测试组，直到得到单一结论**

Run:
```bash
./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"
```

Expected:
- PASS，或
- FAIL 且失败原因稳定、可记录、可复现

- [ ] **Step 5: Commit**

```bash
git add feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilder.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateBuilderTest.kt feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayTemporaryGateGeometryTest.kt
git commit -m "test: close replay lap timing validation path"
```

---

### Task 3: 固化 replay 结论，形成“阶段完成”

**Files:**
- Modify: `docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md`

- [ ] **Step 1: 追加结果段落，二选一写清楚**

若 PASS，记录：

```md
### 2026-04-03 replay closure result
- `ReplayLapTimingIntegrationTest` 已切到 `ReplayTemporaryGateBuilder`；
- 在不修改 `LapTimingEngine` 与 `GateCrossingDetector` 主逻辑的前提下，真实 replay 已能完成一圈；
- 该结论只代表 replay 测试链路打通，不代表正式赛道资产真值已修复；
- 后续 `lap-debug-mode` 应基于该结论重新定义页面语义，而不是继续围绕旧 `LapDebugAnalyzer` 扩写。
```

若 FAIL，记录：

```md
### 2026-04-03 replay closure result
- `ReplayLapTimingIntegrationTest` 已切到 `ReplayTemporaryGateBuilder`，但仍未完成一圈；
- 失败已稳定收敛到 temporary gate 构造质量 / passDirection / window 选择问题；
- `LapTimingEngine` 与 `GateCrossingDetector` 仍不作为本轮背锅对象；
- 在 replay 结论明确前，暂停继续扩写 `lap-debug-mode` 页面。
```

- [ ] **Step 2: 用最小命令复核日志与现实一致**

Run:
```bash
./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"
```

Expected:
- 与日志描述一致

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-04-01-replay-lap-progress-log.md
git commit -m "docs: record replay lap timing closure result"
```

---

### Task 4: 只基于 replay 结论，重定义测试端页面下一步

**Files:**
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/ui/TestFlowNavigation.kt`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestSelectionScreen.kt`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugConfigScreen.kt`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt`
- Read only: `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugResultScreen.kt`

- [ ] **Step 1: 只回答一个问题：页面现在是“验证壳子”还是“正式圈速入口”**

判断规则：
- 如果 replay 已 PASS：页面可以开始向“正式圈速入口”收口
- 如果 replay 未 PASS：页面只能被定义为“验证壳子”，不得继续按正式功能扩张

- [ ] **Step 2: 形成单页结论，不直接改代码**

输出内容只包含：

```md
- 当前页面定位：验证壳子 / 正式圈速入口
- 当前 ViewModel 是否还能继续使用 `LapDebugAnalyzer`
- 配置页、执行页、结果页下一步各保留什么、删除什么、暂缓什么
```

- [ ] **Step 3: 只有在结论明确后，才开下一轮实现计划**

下一轮计划只允许二选一：
- 方案 A：把 `lap-debug-mode` 收敛成最小可用的 replay/gate 验证工具
- 方案 B：正式替换到 Track/LapTimingEngine 驱动页面

本轮禁止同时做 A + B。

---

## 最终验证

- [ ] **Step 1: 查看当前工作区状态**

Run:
```bash
git status --short
```

Expected:
- 改动只集中在 replay 测试文件与进度日志
- 没有新的 UI 漫游式改动

- [ ] **Step 2: 复跑 replay 最小闭环测试**

Run:
```bash
./gradlew :feature:test:testDebugUnitTest --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateBuilderTest" --tests "com.blazepush.feature.test.usecase.ReplayTemporaryGateGeometryTest" --tests "com.blazepush.feature.test.usecase.ReplayLapTimingIntegrationTest"
```

Expected:
- PASS，或稳定 FAIL 且结论已固化

- [ ] **Step 3: 人工核对本轮是否真正阶段完成**

Checklist:
- 本轮只收 replay 关键路径
- 不再混做 UI 扩写
- `LapTimingEngine` 未被误改
- `GateCrossingDetector` 未被误放宽
- 已留下明确的 PASS / FAIL 结论
- 已能基于该结论决定页面下一步定位
