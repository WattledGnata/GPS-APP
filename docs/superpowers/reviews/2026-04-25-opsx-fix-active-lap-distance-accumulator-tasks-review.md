# fix-active-lap-distance-accumulator tasks review

- **日期**：2026-04-25
- **评审对象**：`openspec/changes/fix-active-lap-distance-accumulator/tasks.md` V1
- **覆盖攻击点**：A22
- **评审方**：codex session
- **实施方**：claude session
- **轮次**：第 4 轮 tasks review（proposal / design / spec 已放行）
- **结论**：🔴 暂不放行 `/opsx:apply`
- **前置条件**：P1/P2 全闭合后重提 tasks mini review

## 0. TL;DR

tasks 主体顺序基本正确：§3 标明 engine BREAKING 连锁必须一气做完，A56 diff grep 也承接了 spec V2。

但 V1 不能直接 apply：UI 改读 engine 字段后，现有 `LapDebugExecutionScreenStateTest` 两条旧测试会变红；另外 sector accepted 的源码断言只排除了 `session.activeLap.copy(`，但当前旧代码实际是本地变量 `activeLap.copy(`，这个 grep 锁不住退化实现。

## 1. 🔴 P1 / P2 Findings

### Finding 1 — [P1] tasks 漏改既有 UI state 测试，apply 后会直接红

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/tasks.md:295-333`、`feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt:72-122`、`:217-223`
- **问题**：tasks 只要求新增 `rememberStartFinishTimingCardState_readsEngineDistanceField` 和 7500 samples smoke，但没有要求迁移现有两条测试：
  - `rememberStartFinishTimingCardState_withFirstAcceptedStartFinishCrossing_reportsCurrentLapSummary` 当前期待 `"14.7 m"`，其 `activeLap(...)` helper 未传新字段，A22 后 UI 读字段会得到默认 `0.0 m`。
  - `rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_reportsFixedSummaryFields` 当前期待 `"32.4 m"`，同样会变成 `0.0 m`。
- **后果**：实施方照 tasks 新增测试但不改旧测试，`LapDebugExecutionScreenStateTest` 必失败；A22 甚至会被误判为实现错误。
- **要求**：
  - 在 §5.3 前新增任务：迁移现有 UI state 测试，给 `activeLap(...)` helper 增加 `distanceMetersSinceStart: Double = 0.0` 参数，并在上述两条测试中分别传入 `14.7` / `32.4`（或对应精确 double），让断言继续验证显示格式但数据来源改为 engine 字段。
  - 明确删除“通过 samples 推导距离”的旧语义，旧 samples 仍可保留用于 `currentLapElapsedMillis`。

### Finding 2 — [P2] sector accepted 源码断言锁错了旧实现形态

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/tasks.md:364-379`
- **问题**：任务写“handleSectorCrossing 内不再含 `session.activeLap.copy(`”，但当前旧实现并不是 `session.activeLap.copy(`，而是 `val activeLap = session.activeLap ?: ...` 后使用 `activeLap.copy(...)`。所以这个源码断言即使不改旧实现也可能通过，锁不住 spec R3(f) 的真正目标。
- **后果**：如果行为测试漏掉或 fixture 没真正穿到 sector accepted，源码门槛仍可能假绿。
- **要求**：
  - 源码断言改为同时要求：
    - `handleSectorCrossing` 函数体包含 `activeLapWithDistance!!.copy(` 或等价明确从 `activeLapWithDistance` 派生的表达式；
    - `handleSectorCrossing` sector accepted 分支不含 `activeLap = activeLap.copy(` / `activeLap.copy(` 这种旧本地变量派生。
  - 同步把 tasks §3.5 / spec R3(f) 的文字从“不走 `session.activeLap.copy(...)`”改成“不走旧本地 `activeLap.copy(...)` / 不从未带 distance 的 activeLap 派生”，避免文档继续锁错对象。

## 2. 🟢 已充分认可

- §3 明确 BREAKING 连锁中间态不要跑 compile，这点很重要，避免实施方在签名半改时误判。
- §6.5 A56 grep 已按 spec V2 改成 diff 新增行，关键词也足够窄。
- §4.4 删除孤立 `GpsSample` import 的预检很细，能避免 UI 文件残留无用 import。
- §5.4 把源码 grep 做成单测 + §6 合流门槛双层验证，方向对。

## 3. 给实施方的回复模板

tasks V1 暂不放行 `/opsx:apply`。请先修 2 点：

1. 补任务迁移现有 `LapDebugExecutionScreenStateTest` 两条距离断言：`activeLap(...)` helper 增加 `distanceMetersSinceStart` 参数，并让旧测试显式传 `14.7` / `32.4`，否则 UI 改读字段后旧测试会红。
2. 收紧 sector accepted 源码断言：不能只查 `session.activeLap.copy(`，要查旧本地 `activeLap.copy(` 不再用于 accepted 分支，并正向要求 `activeLapWithDistance!!.copy(`。

修完后重提 tasks mini review；无需 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：tasks V2 新增 §5.3，要求迁移 `LapDebugExecutionScreenStateTest` 旧 distance 断言；`activeLap(...)` helper 增加 `distanceMetersSinceStart: Double = 0.0` 参数，并在两条旧测试中显式传 `14.7` / `32.4`。这能防止 UI 改读字段后旧测试红。
- Finding 2 closed：tasks V2 的 §5.5.3 已改为正向要求 `activeLapWithDistance!!.copy(`，并用 `\bactiveLap\.copy\(` 反向禁止旧本地变量 copy；这能锁住当前真实旧实现形态。
- `openspec validate fix-active-lap-distance-accumulator --strict` 继续通过。

### 4.2 Non-blocking notes

- spec R3(f) 与 tasks §3.5 / commit body 仍有几处旧表述写“不走 `session.activeLap.copy(...)`”。严格说旧代码实际是本地 `activeLap.copy(...)`，不是 `session.activeLap.copy(...)`。不过 tasks §5.5.3 已用正向/反向断言锁住真实实现形态，故不阻塞 apply；实施方可顺手把这些说明文字改成“不走旧本地 `activeLap.copy(...)`”。

### 4.3 Verdict

tasks review 通过。可以进入 `/opsx:apply`。
