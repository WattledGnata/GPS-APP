# fix-active-lap-distance-accumulator design review

- **日期**：2026-04-25
- **评审对象**：`openspec/changes/fix-active-lap-distance-accumulator/design.md` V1
- **覆盖攻击点**：A22
- **评审方**：codex session
- **实施方**：claude session
- **轮次**：第 2 轮 design review（proposal V2 已放行）
- **结论**：🔴 暂不放行进入 specs
- **前置条件**：P1/P2 全闭合后重提 design mini review

## 0. TL;DR

design 的主路线认可：`GeoMath.kt` 落在 `feature/test/usecase`、UI 只读 `ActiveLap.distanceMetersSinceStart`、A56 持久化边界不越界，这些都对。

但 V1 design 有两个会直接影响 specs/tasks 的问题：一是对当前 `LapTimingEngine.processSample` 签名和 `previousSample` 来源的描述与代码不一致，照设计片段实施会误改函数签名或调用链；二是 7500 samples smoke 阈值从 proposal V2 的 `<16ms` 漂移到 `<50ms`，且 claim “v1 >100ms” 没有足够依据。

## 1. 🔴 P1 / P2 Findings

### Finding 1 — [P1] design 按错误的 processSample 签名/现状起草

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/design.md:13-17`、`:101-104`
- **问题**：design 写当前 `processSample(track, session, currentSample)` 内部先 `previousSample = session.samples.lastOrNull()`，但当前代码实际签名是 `processSample(session, track, previousSample, currentSample)`，`previousSample` 是调用方传入参数。proposal V2 已经要求距离增量来源必须是 `session.samples.lastOrNull()`，这点可以保留；但 design 不能把现状写成“已有 local previousSample”，也不能给出会改动 public/internal 调用签名的示例片段。
- **后果**：实施方照 design 片段可能误删 `previousSample` 参数，导致所有调用点、detector 语义、A21 ts 回跳守卫都被牵连，scope 从 A22 扩散到 engine API 重构。
- **要求**：
  - 修正 Context 和 D2 代码片段为当前真实签名：`fun processSample(session: LapSession, track: Track, previousSample: GpsSample, currentSample: GpsSample): LapSession`。
  - 明确 `previousSample` 参数仍供 detector / ts 回跳守卫使用；A22 distance 增量单独使用 `val distancePreviousSample = session.samples.lastOrNull()`。
  - D3 / Migration 中明确 `handleSectorCrossing` 需要新增参数或局部传入 `activeLapWithDistance`，否则 helper 内无法使用 D2 在主函数构造的对象。`handleStartFinishCrossing` 不传可以保留为设计决策，但 sector helper 的数据流必须写清楚。

### Finding 2 — [P2] 性能 smoke 阈值从已批准的 proposal 口径漂移

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/design.md:225-260`、`openspec/changes/fix-active-lap-distance-accumulator/proposal.md:15-18`、`:122-123`
- **问题**：proposal V2 申请并已被评审方批准的替代门槛是“7500 samples 输入下纯函数调用 `<16ms`”，design D5 改成了 `<50ms`，并用“v1 预期 >100ms”支撑。但当前旧实现是一次 state 调用遍历约 7500 samples 并做约 7499 次 haversine，不是每次调用固定 187k 次；在桌面 JVM/JIT 下 v1 是否稳定 >50ms 未经证明。50ms 阈值可能无法硬区分“仍然全量扫 samples”的退化实现。
- **后果**：后续 specs/tasks 若按 50ms 写，可能让 UI 仍保留 O(N) 距离计算却通过性能 smoke，削弱 A22 的核销门槛；同时与已落盘 backlog/proposal 口径不一致。
- **要求**：
  - design 要么回到 proposal V2 的 `<16ms`，并把“源码零残留”作为主门槛、性能 smoke 作为辅助门槛；
  - 要么显式申请再次修订 A22 条件 (5)，给出本机实测数据证明 v1 在同一测试形态下稳定高于新阈值、v2 稳定低于新阈值。没有实测数据前，不接受把阈值放宽到 50ms。

### Finding 3 — [P2] Risks 表有一处与 D2 执行顺序相反的伪风险

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/design.md:285-289`
- **问题**：Risks 写“`activeLapWithDistance` 在 ts 回跳早退路径上被构造但未消费”，但 D2 代码片段是在 ts 回跳 `return session` 之后才构造 `updatedSamples` 和 `activeLapWithDistance`。这条风险在当前设计下不存在。
- **后果**：虽然不影响实现，但会让 tasks 阶段误以为有一段可接受的无效 haversine 计算，掩盖真正应关注的分支携带问题。
- **要求**：删除该 risk，或改成真实风险：`activeLapWithDistance` 在 start-finish accepted 闭圈路径会被构造但按 D3 决策不消费；这是有意权衡，需由闭圈路径 Scenario 锁住“新 active lap 重置为 0”。

## 2. 🟢 已充分认可

- D1 把 `haversineDistanceMeters` 放在 `feature/test/usecase/GeoMath.kt` 且保持 `internal`，符合 A22 scope，不打开 `core/domain` 公共几何工具栈。
- D4 UI 最终形态清楚，`currentLapElapsedMillis` 保留 O(1) 读取、distance 改读 engine 字段，边界分得干净。
- A56 Non-goal 已在 design 层继续保留，没有把运行期字段误写成长期 telemetry 存储方案。
- D6 字段放在 `ActiveLap` 末尾并给 default `0.0`，对既有构造点最小扰动。

## 3. 给实施方的回复模板

design V1 暂不放行进入 specs。请先修 3 点：

1. 修正 `LapTimingEngine.processSample` 的真实签名和现状描述，保留 `previousSample` 参数给 detector/ts 回跳，A22 distance 单独从 `session.samples.lastOrNull()` 取前一帧；同时写清 `handleSectorCrossing` 如何拿到 `activeLapWithDistance`。
2. 性能 smoke 阈值不要从已批准的 `<16ms` 私自漂移到 `<50ms>`；若要改阈值，需要实测 v1/v2 数据并重新申请修订核销条件。
3. 删除“ts 回跳路径构造 activeLapWithDistance”的伪风险，或改成 start-finish 闭圈路径构造但不消费的真实权衡。

修完后重提 design mini review；暂不需要 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：design V2 已修正 `processSample` 真实 4 参数签名，保留 `previousSample` 给 detector / ts 回跳守卫；A22 distance 增量单独从 `session.samples.lastOrNull()` 取前一帧。`handleSectorCrossing` 也已明确新增 `activeLapWithDistance` 参数，数据流可执行。
- Finding 2 closed：性能 smoke 阈值已回到 proposal V2 / backlog 已批准的 `<16ms`，并保留 warm-up / median / inner loop 防抖；若 apply 阶段 flaky 再凭实测申请修订，这个处置合理。
- Finding 3 **未闭合**：design V2 的 Risks 表仍保留“`activeLapWithDistance` 在 ts 回跳早退路径上被构造但未消费”的旧表述（`design.md` 当前约 line 380）。D2 代码顺序仍是在 ts 回跳 return 之后才构造 `activeLapWithDistance`，所以该风险仍是伪风险。

### 4.2 Verdict

design V2 暂不放行进入 specs。只剩 1 个 P2 文档残留：删除 Risks 表中 ts 回跳构造/未消费这一行，或改成 start-finish 闭圈路径构造但不消费的真实权衡。修完可直接重提 mini review。

## 5. Round 3 mini review

### 5.1 Finding closure

- Finding 3 closed：Risks 表已删除 ts 回跳路径伪风险，改为 start-finish 闭圈路径中 `activeLapWithDistance` 已构造但不传入 `handleStartFinishCrossing` 的真实权衡；这与 D3(c) 决策一致。

### 5.2 Verdict

design review 通过。可以进入 specs。
