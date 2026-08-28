# fix-active-lap-distance-accumulator proposal review

- **日期**：2026-04-25
- **评审对象**：`openspec/changes/fix-active-lap-distance-accumulator/proposal.md` V1
- **覆盖攻击点**：A22
- **评审方**：codex session
- **实施方**：claude session
- **轮次**：第 1 轮 proposal review
- **结论**：🔴 暂不放行进入 design/specs
- **前置条件**：P1/P2 全闭合后重提 proposal mini review

## 0. TL;DR

方向认可：把 UI pull 式全量 haversine 改成 engine push 式增量字段，是 A22 的正确主线。

但 V1 proposal 还不能进入 design/specs：一是与当前 backlog A22 核销条件存在冲突和漏项；二是 engine 分支返回路径没有定义如何携带已累加的 `ActiveLap`，后续实现很容易只在部分路径生效；三是刚新增的 A56 长期点阵持久化边界没有入网。

## 1. 🔴 P1 / P2 Findings

### Finding 1 — [P1] proposal 与 A22 当前核销条件冲突，且漏掉性能门槛

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/proposal.md:63-71`、`docs/superpowers/reviews/attack-backlog.md:139-144`
- **问题**：A22 backlog 当前核销条件仍要求“与 A15 合并：引擎和 UI 统一使用同一距离定义，解决 UI haversine vs 引擎欧氏叉积不同源”，但 proposal 又把“不统一 engine 判圈几何 vs 距离累积口径”列为 Non-goal。这两者不能同时成立。另一个漏项是 backlog 条件 (5) 要求 7500 样本 recomposition 性能回归 `< 16ms`，proposal 的验收门槛只列了 validate / 下游测试 / E2E / backlog 迁档，没有保留性能核销门槛。
- **后果**：实施方即使按 proposal 完成，也无法按当前 A22 条目核销；评审阶段会在“到底按 backlog 还是按 proposal”上冲突。
- **要求**：
  - 在 proposal 中新增“核销条件修订”段，明确申请评审方将 A22 条件 (3) 修订为：engine 是 active-lap distance 的唯一 producer，UI 只读该字段；判圈几何与距离累计是不同概念，本 change 不要求二者统一。
  - 保留或替换条件 (5)：若不做 Robolectric/benchmark，就必须给出可机器核销的替代门槛，例如 UI state 计算不再遍历 samples 的源码断言 + `LapDebugExecutionScreen.kt` 中 `calculateDistanceSince` / UI 私有 `haversineDistanceMeters` 零残留 + 一个 7500 samples 的 state 函数轻量性能 smoke。

### Finding 2 — [P1] distance 累加 hook 没有覆盖 engine 多分支返回路径

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/proposal.md:12-20`、`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:83-126`、`:278-306`
- **问题**：proposal 写“在 `updatedSamples = session.samples + currentSample` 后、`session.copy` 前累加”，但当前 `processSample` 有多条提前返回路径：start/finish 进入 `handleStartFinishCrossing`，无 target gate 直接 `session.copy(samples = updatedSamples)`，sector rejected 返回 `session.copy(samples, crossingEvents)`，sector accepted 又在 `activeLap.copy(...)` 里只追加 gate/sector。若 proposal 不规定“先构造 `activeLapWithDistance` 并传入所有 helper / copy 分支”，实施方很容易只在 sector accepted 或某一条 copy 路径里更新距离，导致 rejected/no-target/普通采样路径距离不动。
- **后果**：UI 会读到 engine 字段，但该字段在部分合法样本路径下停滞，A22 性能修了、正确性却退化。
- **要求**：
  - proposal 明确增量来源必须是 `session.samples.lastOrNull()` 与 `currentSample` 的相邻 samples 流，而不是无条件使用 `previousSample` 参数；这样才能与旧 UI `samples.zipWithNext()` 口径一致。
  - proposal 明确实现形态：在 detector 分支前计算 `activeLapWithDistance`，所有保留当前 active lap 的返回路径必须使用它；闭圈后新 `ActiveLap` 仍从 0 开始。
  - specs/tasks 至少覆盖 4 类路径：普通 sector rejected/no gate 路径仍累加、sector accepted 累加且保留 sectorEntries、start/finish 开圈帧为 0、闭圈后下一圈 active lap 重置为 0。

### Finding 3 — [P2] A56 长期点阵持久化边界没有入 proposal

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/proposal.md:63-71`、`docs/superpowers/reviews/attack-backlog.md:660-678`
- **问题**：A56 已新增为“密集 GPS 点阵持久化架构”规划项，并明确写着“不阻塞 A22，但 A22 proposal 必须声明本轮不固化长期轨迹存储模型”。当前 A22 proposal 只说不改 `LapRecord` / 不做 A51，没有提 A56，也没有说明 `ActiveLap.distanceMetersSinceStart` 只是运行期 UI 状态字段。
- **后果**：后续 specs/tasks 可能误把 `LapSession.samples` / `LapRecord.trajectory` 当成长期点阵存储方向，和 A56 的 Room metadata + chunked telemetry storage 规划发生冲突。
- **要求**：Non-goals 增加 A56 边界：本 change 不设计、不实现长圈密集 GPS 点阵持久化；`ActiveLap.distanceMetersSinceStart` 是运行期 active-lap 派生状态，不是长期 telemetry 真相源；不得新增数据库 / chunk / JSON telemetry schema。

### Finding 4 — [P2] Why 段仍把判圈几何与距离累计混成“同一口径”

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/proposal.md:3`、`:71`
- **问题**：Why 段说“UI haversine vs engine 欧氏叉积/crossingProgress 不同源，是正确性隐患”，但 Non-goal 又说判圈几何和已跑距离是不同概念。这会给 design/specs 留下错误暗示：似乎 A22 要把 crossing geometry 与 display distance 统一。实际上本 change 要统一的是“已跑距离由 engine 产生、UI 不自算”，不是统一判圈投影算法。
- **后果**：实施方可能在 specs 中继续要求 engine crossing geometry 与 haversine distance 一致，扩大 scope 到 C 战役几何模型，甚至误碰 A44/wrappedDeltaLon。
- **要求**：重写 Why：保留 UI O(N) 性能风险和“active-lap display distance 单一 producer”两个理由；删除或改写“engine 欧氏叉积/crossingProgress 与 UI haversine 不同源是正确性隐患”的表述，改成“判圈几何不作为本圈距离口径，二者概念分离”。

## 2. 🟢 已充分认可

- `ActiveLap.distanceMetersSinceStart` 命名比 `distanceMeters` 更好，能避免和未来 `LapRecord.distance` / A51 `progressMeters` 混淆。
- UI 层删除 `calculateDistanceSince` 与私有 `haversineDistanceMeters` 是正确方向，A22 的性能问题应该从“UI 不再自算”处解决。
- 不改 `LapRecord` schema、不做 A51、不做 A35 是合理 scope 控制。
- 不跨层消费 A44 `wrappedDeltaLon` 是合理保守选择；跨经度距离正确性可以另起独立 round。

## 3. 给实施方的回复模板

proposal V1 暂不放行进入 design/specs。请先修 4 点：

1. 在 proposal 明确修订 A22 当前核销条件：不再要求统一判圈几何与距离累计口径；同时补回 7500 samples 性能/源码零残留门槛。
2. 把 engine 增量 hook 写成可执行契约：使用 `session.samples.lastOrNull()` 与 `currentSample` 累加，并保证所有保留 active lap 的返回分支都携带 `activeLapWithDistance`。
3. Non-goals 增加 A56：本轮不固化长期 GPS 点阵持久化模型，`ActiveLap.distanceMetersSinceStart` 只是运行期派生状态。
4. Why 段删除“UI haversine vs engine crossing geometry 不同源”的正确性暗示，改成“UI display distance 单一 producer + O(N) 性能风险”。

修完后重提 proposal mini review；暂不需要 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：proposal V2 已新增“核销条件修订”段，明确申请把 A22 条件 (3) 改为 engine 是 `ActiveLap.distanceMetersSinceStart` 唯一 producer、UI consumer-only；条件 (5) 改为源码零残留 + 7500 samples unit-level smoke。评审方认可该修订，并已同步 backlog A22 核销条件。
- Finding 2 closed：engine hook 已从“append 后 copy 前”细化为 `activeLapWithDistance` 集中构造，并要求所有保留当前 active lap 的返回路径都携带该对象；增量来源也已明确为 `session.samples.lastOrNull()` 与 `currentSample`。
- Finding 3 closed：Non-goals 已新增 A56 边界，说明本轮不设计长期 telemetry 持久化，`distanceMetersSinceStart` 只是运行期派生状态。
- Finding 4 closed：Why 已删除“判圈几何 vs UI haversine 不同源是正确性隐患”的暗示，改为 UI O(N) 性能风险 + active-lap display distance 单一 producer。

### 4.2 Non-blocking notes for next artifacts

- spec/tasks 阶段需要把 `activeLapWithDistance` 的分支覆盖落成可测 Scenario，尤其是 no target gate、sector rejected、sector accepted、开圈、闭圈重置这几类路径。
- 7500 samples `< 16ms` 的 unit smoke 容易受 CI 机器波动影响；tasks 阶段建议同时保留“源码零残留”为主门槛，并让性能 smoke 只包裹纯 state 函数、避免日志/格式化/Compose runtime。

### 4.3 Verdict

Round 2 proposal review 通过。可以进入 design/specs。
