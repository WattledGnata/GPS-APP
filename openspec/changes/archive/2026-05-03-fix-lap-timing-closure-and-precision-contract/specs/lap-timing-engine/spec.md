# Spec Delta: lap-timing-engine

> Capability: **LapTimingEngine 判圈链路的毫秒级语义升级**。从 `GateCrossingDetector.detect`
> → `CrossingEvent` → `ActiveLap` → `LapRecord` 整条数据流改用"过线插值毫秒时刻"
> 而非"触发检测的帧 ts"；`LapRecord.trajectory` 按时间窗口 `[startedAt, finishedAt)`
> 切分，配合保留的 `sampleStartIndex` 作 subList 起点把复杂度锁在 O(本圈帧数)；
> `handleSectorCrossing` 改为遍历所有 sector 门并按"期待门优先 + 非期待门按
> `orderedSectorGates` 顺序"构造多个 CrossingEvent，配合期待门 accepted / rejected
> 的 state 推进分支；闭圈裁剪层 filter 边界修订为严格 `>` 语义（通过 MODIFIED
> Requirements 段覆盖已闭环 change `fix-lap-timing-engine-entry-hardening` 的
> R3 Scenario 1）。
>
> 依赖关系：
>
> - 本 change 依赖 `fix-lap-timing-engine-entry-hardening` 已闭环（A19 白名单 /
>   A21 入口 ts 单调 / A38 bridge 三段式 / A34 死码清理）
> - R2（engine 插值时刻）依赖 R1（detector 产出 crossingProgress）
> - R3（trajectory 时间窗口）依赖 R2（startedAtMillis / finishedAtMillis 是插值时刻）
> - R4（多门 + state 推进）独立于 R2/R3，但 SectorEntry.crossedAtMillis 用插值
>   毫秒需要 R1 的 crossingProgress
> - R5（filter 严格 `>`）修订 engine-entry-hardening R3 Scenario 1；MODIFIED 段
>   语法已 dry-run 验证 CLI 支持，无需归档时序前置
> - R6（±5ms E2E 合成契约）由 R1+R2+R3 共同兑现
> - R7（A33 qualityFlags 断言）与 R4 / R5 无强耦合，但 fixture 复用同批测试
>
> 不影响 `fix-laptime-clock-source-integrity` 的分层守卫（未同步帧跳过 / bridge
> 段 1 首样本守卫均保持）。
>
> **插值模型约束**：本 Requirement 族采用帧间线性（匀速）插值。选型依据与
> 1Hz 弱定位设备升级路径（四阶段）见 proposal 决策 5。**本 change 范围内
> MUST NOT 引入匀加速 / 朝向几何等升级代码路径**，防止扰乱 ±5ms 合成契约
> 前置量级假设。

## ADDED Requirements

### Requirement: `GateCrossingDetector.detect` 返回归一化过线参数 `crossingProgress`（R1）

`GateCrossingDetector.detect(previous, current, gate)` MUST 在 `GateCrossingDetection`
返回值中填充 `crossingProgress: Double?` 字段，作为 `previous → current` 线段上过线
点的归一化参数 `t`（线性插值时 `t=0` 对应 `previous` 位置，`t=1` 对应 `current` 位置）。

字段契约：

- `accepted == true` 时 MUST 非 null，MUST 经 `coerceIn(0.0, 1.0)` clamp（防
  `segmentsIntersectMeters` 浮点边界越界：实际内部计算的 `t` 可能微越界，例如
  `-1e-16` 或 `1.0000001`）
- `accepted == false` 时 MUST 为 null（`NoIntersection` / `WrongDirection` /
  `TooSlow` 均 null）
- 取值范围：`[0.0, 1.0]`，单位：无（归一化参数）

配套内部修改：`segmentsIntersectMeters` 的返回值 MUST 从 `Boolean` 改为 `Double?`，
null 表示不相交，非 null 为原算法内部已计算的 `t` 参数（无需新增计算，仅公开已有值）。

#### Scenario: accepted 过线返回 crossingProgress ∈ [0, 1] 非 null

- **GIVEN** `prev` 位于 gate 线一侧、`current` 位于另一侧（线段 `prev→current` 几何相交 gate line）
- **AND** 移动方向 `passDirection` 投影 `> 0`（未被 `WrongDirection` 拦下）
- **AND** `directionalSpeedMps` 满足 `gate.minDirectionalSpeedMps`（未被 `TooSlow` 拦下）
- **WHEN** 调用 `detector.detect(prev, current, gate)`
- **THEN** 返回的 `GateCrossingDetection.accepted == true`
- **AND** `GateCrossingDetection.crossingProgress` 非 null
- **AND** `GateCrossingDetection.crossingProgress in 0.0..1.0`

#### Scenario: 对称过线 crossingProgress 精确等于 0.5

- **GIVEN** `prev` 位于 gate 中点 + `passDirection` 方向反偏 `0.25` 向量长度
- **AND** `current` 位于 gate 中点 + `passDirection` 方向正偏 `0.25` 向量长度
  （构造与 `LapTimingEngineTest.crossingSamples` 同结构的对称过线 fixture）
- **WHEN** 调用 `detector.detect(prev, current, gate)`
- **THEN** `crossingProgress` 应满足 `Math.abs(crossingProgress - 0.5) < 1e-9`
  （浮点精度内精确 0.5，对应 "过线点位于 prev→current 线段中点"）

#### Scenario: 浮点边界越界被 clamp 到 [0.0, 1.0]

- **GIVEN** 通过以下任一方式让 `detect` 路径获得一个越界 `t` 值：
  - (a) 构造极端几何 fixture 触发 `segmentsIntersectMeters` 内部浮点越界（若当前 JVM / CPU 可复现）
  - (b) 把 `segmentsIntersectMeters` 标注为 `@VisibleForTesting internal`，测试内直接注入越界 t（推荐降级路径，省去浮点复现成本）
- **AND** 无论 (a) 或 (b)，断言核心均为"越界 t 经 `coerceIn(0.0, 1.0)` clamp 后 crossingProgress 落在 [0.0, 1.0]"
- **WHEN** `detect` 填充 `crossingProgress`
- **THEN** `crossingProgress == 1.0`（上界 clamp，注入 `t = 1.0000001`）
- **AND** 对称场景：注入 `t = -1e-16` → `crossingProgress == 0.0`（下界 clamp）

#### Scenario: rejected 场景返回 crossingProgress == null

- **GIVEN** `prev` 与 `current` 在 gate 同侧（无几何相交）
- **WHEN** 调用 `detector.detect(prev, current, gate)`
- **THEN** `accepted == false`，`reason == NoIntersection`
- **AND** `crossingProgress == null`
- **AND** 对称：`WrongDirection` / `TooSlow` 路径的 `crossingProgress` 也 MUST 为 null

#### Scenario: `segmentsIntersectMeters` 返回 Double? 语义

- **GIVEN** 任意线段 `(prev, current)` 与 gate 线段 `(gateStart, gateEnd)`
- **WHEN** 调用 `segmentsIntersectMeters(prevN, prevE, currN, currE, gateStartN, gateStartE, gateEndN, gateEndE)`
- **THEN** 几何相交时返回的 `Double?` 非 null，值为 `t ∈ [0, 1]` 表示 `prev → current` 线段上的归一化相交参数
- **AND** 几何不相交时返回 null（denominator == 0 亦返回 null，保留 v1 防御性语义）

---

### Requirement: LapTimingEngine 使用插值毫秒时刻构造 ActiveLap / LapRecord / CrossingEvent（R2）

engine 内所有圈时相关的时刻字段 MUST 从帧粒度 ts（`currentSample.timestampMillis`）
升级到毫秒级插值时刻。核心公式：`interpolatedMillis = Math.round(previousSample.timestampMillis + crossingProgress × (currentSample.timestampMillis - previousSample.timestampMillis))`。
具体涉及 `CrossingEvent.timestampMillis` / `ActiveLap.startedAtMillis` /
`LapRecord.startedAtMillis` / `LapRecord.finishedAtMillis` / `SectorEntry.crossedAtMillis`
全部字段；`LapRecord.durationMillis` 派生为插值时刻差。

字段契约：

- `CrossingEvent.timestampMillis`：
  - 当 `accepted == true` 时 MUST 等于 `interpolatedMillis(prev, current, crossingProgress)`（毫秒级 Long，四舍五入到整数毫秒）
  - 当 `accepted == false` 时 MUST 等于 `currentSample.timestampMillis`（降级到触发帧 ts，作为诊断时间戳；该 event 不作为圈时边界裁剪源，仅进 `session.crossingEvents` 作诊断）
- `ActiveLap.startedAtMillis` MUST 等于开圈 CrossingEvent 的 `timestampMillis`
- `LapRecord.startedAtMillis` MUST 等于 `ActiveLap.startedAtMillis`（闭圈时从 activeLap 复制）
- `LapRecord.finishedAtMillis` MUST 等于闭圈 CrossingEvent 的 `timestampMillis`
- `LapRecord.durationMillis` MUST 等于 `finishedAtMillis - startedAtMillis`（毫秒级精确，不再是帧 ts 差）
- `SectorEntry.crossedAtMillis` MUST 等于 sector 过线 CrossingEvent 的 `timestampMillis`（插值毫秒），不得用 `currentSample.timestampMillis`
- `CrossingEvent.sampleIndex` MUST 等于 `updatedSamples.lastIndex`（触发 detection 的 currentSample 在 session.samples 中的索引，诊断语义；与 `timestampMillis` 互补，**不代表过线时刻对应帧**）

**诊断语义说明**：下游若按 `samples[event.sampleIndex]` 查询过线时刻对应帧，得到的是 currentSample（触发 detection 的帧），而非插值毫秒 `event.timestampMillis` 对应的虚拟帧。`event.sampleIndex` 是**帧粒度诊断索引**，`event.timestampMillis` 是**毫秒级过线时刻**，两者互补，不混用。

#### Scenario: 对称过线构造的 CrossingEvent.timestampMillis 精确位于 prev/current 中点

- **GIVEN** `prev.ts = 200L`，`current.ts = 240L`，对称过线 `crossingProgress = 0.5`
- **WHEN** `handleStartFinishCrossing` 构造起点 CrossingEvent
- **THEN** `event.timestampMillis == 220L`（`200 + 0.5 × 40 = 220`）
- **AND** `event.sampleIndex == updatedSamples.lastIndex`（触发帧 = currentSample 索引）

#### Scenario: ActiveLap.startedAtMillis 是插值时刻（不等于 currentSample.ts）

- **GIVEN** 首次起点过线 `prev.ts = 200L`，`current.ts = 240L`，`crossingProgress = 0.5`
- **WHEN** `processSample` 返回新 session
- **THEN** `session.activeLap.startedAtMillis == 220L`
- **AND** `session.activeLap.startedAtMillis != 240L`（硬区分 v1 帧粒度语义）

#### Scenario: 闭圈 LapRecord.durationMillis 是毫秒级精确值（对称过线差相消）

- **GIVEN** 开圈 `(prev.ts=200, current.ts=240, t=0.5)` → `startedAtMillis=220`
- **AND** 闭圈 `(prev.ts=10_200, current.ts=10_240, t=0.5)` → `finishedAtMillis=10_220`
- **WHEN** `handleStartFinishCrossing` 构造 LapRecord
- **THEN** `lapRecord.durationMillis == 10_000L`（`10_220 - 220`）
- **AND** 与对称 fixture 下 v1 的 `current.ts - activeLap.startedAtMillis = 10_240 - 240 = 10_000L` **数值等价**（对称过线差相消）

#### Scenario: 不对称过线 durationMillis 与 v1 帧粒度差相消结果不同

- **GIVEN** 开圈对称 `t=0.5` → `startedAtMillis = 220`
- **AND** 闭圈 `prev.ts=10_200, current.ts=10_240, t=0.25` → `finishedAtMillis = 10_210`
- **WHEN** 构造 LapRecord
- **THEN** `lapRecord.durationMillis == 9_990L`（`10_210 - 220`）
- **AND** v1 帧粒度下 `durationMillis = current.ts - frame_startedAt = 10_240 - 240 = 10_000L`
- **AND** 两者差 **10ms**（硬区分 v1/v2）

#### Scenario: SectorEntry.crossedAtMillis 用插值毫秒

- **GIVEN** sector 门过线 `prev.ts=5_200, current.ts=5_240, crossingProgress=0.75`
- **WHEN** `handleSectorCrossing` 期待门 accepted 分支构造 SectorEntry
- **THEN** `sectorEntry.crossedAtMillis == 5_230L`（`5_200 + 0.75 × 40`）
- **AND** `sectorEntry.crossedAtMillis != 5_240L`（硬区分 v1）
- **AND** `LapRecord.sectorTimes` 按 `sectorEntries.toSectorTimes(startedAtMillis)` 派生时使用插值时刻差分，精度 = 毫秒级

#### Scenario: CrossingEvent.sampleIndex 是触发帧索引（诊断语义，非边界场景）

- **GIVEN** `session.samples` 已有 N 帧，喂入第 N+1 帧触发过线
- **AND** `crossingProgress ∈ (0.0, 1.0)` 开区间（即**非边界过线**，过线点严格落在 prev 与 current 之间而非端点）
- **WHEN** `processSample` 构造 CrossingEvent
- **THEN** `event.sampleIndex == updatedSamples.lastIndex == N`（触发 detection 的 currentSample 索引）
- **AND** `event.timestampMillis` 为插值毫秒，**不等于** `session.samples[event.sampleIndex].timestampMillis`（该帧是 currentSample，ts 为帧 ts）

#### Scenario: rejected CrossingEvent.timestampMillis 降级到触发帧 ts

- **GIVEN** 期待门被 `TooSlow` 或 `WrongDirection` rejected，`prev.ts = 200`，`current.ts = 240`
- **AND** `detection.crossingProgress == null`（rejected 分支不填充 crossingProgress）
- **WHEN** `handleSectorCrossing` 构造 rejected event
- **THEN** `event.timestampMillis == 240L`（= `currentSample.timestampMillis`，降级到触发帧 ts）
- **AND** `event.accepted == false`
- **AND** `event.reason` 保留 detection.reason（`TooSlow` 或 `WrongDirection`）
- **AND** 该 event 进入 `session.crossingEvents` 作诊断，不参与 `LapRecord.crossingEvents` 裁剪的时间边界比较

---

### Requirement: `LapRecord.trajectory` 按时间窗口 `[startedAt, finishedAt)` 切分（R3）

`LapTimingEngine.handleStartFinishCrossing` 构造 `LapRecord.trajectory` 时 MUST 采用
**两段式切分**：先用 `sampleStartIndex` 作 subList 起点跳过非本圈前驱帧，再按时间
窗口 `[startedAtMillis, finishedAtMillis)` 逐元素 filter 判定归属。切分规则 MUST 保证
"每帧恰属一圈"的严格非重叠性。

```kotlin
val trajectory = updatedSamples
    .subList(activeLap.sampleStartIndex, updatedSamples.size)
    .filter { sample ->
        sample.timestampMillis >= activeLap.startedAtMillis &&
            sample.timestampMillis < lapRecord.finishedAtMillis
    }
```

`ActiveLap.sampleStartIndex` 字段保留但语义 MUST 为"subList 性能起点"（非归属判定
依据）；若 subList 起点位置与时间窗口边界冲突（理论可因 A38 ts 回跳守卫造成"幻帧"），
最终归属 MUST 以 filter 时间窗口为准。

`ActiveLap` / `LapRecord` trajectory 不变式：

- `trajectory.first.timestampMillis >= startedAtMillis`（若非空）
- `trajectory.last.timestampMillis < finishedAtMillis`（若非空）
- `trajectory.none { it.timestampMillis == finishedAtMillis }`（闭圈时刻帧归下圈首帧）
- `trajectory.none { it.timestampMillis < startedAtMillis }`（即使 subList 起点指向更早帧，filter 兜底排除）

#### Scenario: 闭圈 trajectory 不含闭圈时刻对应帧

- **GIVEN** 第 N 圈开圈插值时刻 `startedAtMillis = 220`，第 N+1 圈开圈（= 第 N 圈闭圈）插值时刻 `finishedAtMillis = 10_220`
- **AND** `session.samples` 含一帧 `sample(ts=10_240)` 是触发第 N+1 圈开圈的 currentSample
- **WHEN** `handleStartFinishCrossing` 构造第 N 圈 LapRecord
- **THEN** `trajectory.last.timestampMillis < 10_220`（末帧 ts 严格小于 finishedAt）
- **AND** `trajectory.none { it.timestampMillis == 10_240 }`（`sample(ts=10_240)` 归下圈，不属本圈）

#### Scenario: 第 N+1 圈 ActiveLap.sampleStartIndex 指向闭圈帧

- **GIVEN** 完成第 N 圈闭圈构造后
- **THEN** `session.activeLap.sampleStartIndex == updatedSamples.lastIndex`（闭圈帧索引）
- **AND** 喂入下一帧时，第 N+1 圈 trajectory 从闭圈帧开始累积（`trajectory.first.ts >= startedAtMillis_{N+1}`）
- **AND** 允许（但不保证）存在某帧 ts **数值上恰好等于** `startedAtMillis_{N+1}`；若存在，该帧归下圈（基于 R3 filter `ts >= startedAt` 下界含等号）

#### Scenario: samples.size 等于所有圈 trajectory 之和 + activeLap 实时段

- **GIVEN** session 跑完 2 个完整圈 + 第 3 圈部分帧（尚未闭圈）
- **AND** session 无 A38 理论越界态（所有 `samples[sampleStartIndex..]` 帧 ts 均 ≥ 对应圈 `startedAt`；幻帧场景另见 Scenario 4）
- **WHEN** 检查 session 状态
- **THEN** `session.samples.size == completedLaps.sumOf { it.trajectory.size } + (session.samples.size - session.activeLap.sampleStartIndex)` 严格成立
- **AND 硬区分 v1**：v1 `drop(sampleStartIndex)` 下上圈 trajectory 含闭圈帧、下圈 ActiveLap 从闭圈帧开始，相邻两圈 trajectory 首尾帧重叠，等式两边差 `completedLaps.size`（2 圈差 2 帧）；v2 严格等式成立

#### Scenario: filter 兜底排除 ts < startedAtMillis 的 subList 起点越界帧

- **GIVEN** 构造 `ActiveLap(sampleStartIndex = 5, startedAtMillis = 220)`
- **AND** `updatedSamples[5].timestampMillis = 180`（subList 起点指向比 startedAt 更早的帧，模拟理论越界态）
- **WHEN** `handleStartFinishCrossing` 构造 trajectory
- **THEN** `trajectory.none { it.timestampMillis < 220 }`（filter 兜底排除越界帧）

#### Scenario: trajectory 为空边界（开圈后立即闭圈无推进帧）

- **GIVEN** 开圈 `startedAtMillis = 500`，闭圈 `finishedAtMillis = 520`（插值毫秒差 20ms，小于采样间隔 40ms）
- **AND** `session.samples` 在两次过线之间**没有**任何中间帧 ts 落在 `[500, 520)` 区间
- **WHEN** 构造 LapRecord
- **THEN** `trajectory.isEmpty() == true`（语义正确：开圈到闭圈之间没有任何推进帧）
- **AND** `lapRecord.durationMillis == 20L`（仍为毫秒级精确差）

---

### Requirement: `handleSectorCrossing` 多门同帧完整遍历 + state 推进按期待门 accepted/rejected 分支（R4）

`LapTimingEngine.handleSectorCrossing` MUST 遍历所有 sector 门逐个 detect（而非在首个
accepted 非期待门处 early return），并按"期待门优先 + 非期待门按 `orderedSectorGates` 的
`sequenceIndex` 顺序"构造所有 CrossingEvent。期待门 accepted 与 rejected 分支 MUST 按
下表规则推进 session state。

**state 推进规则**：

| 期待门 detection.accepted | session state 动作 | crossingEvents 动作 |
|---|---|---|
| `true` | 1. 追加 `SectorEntry(gateId=targetGate.id, crossedAtMillis=插值毫秒)` 到 `activeLap.sectorEntries`<br>2. 追加 `targetGate.id` 到 `activeLap.passedGateIds`<br>3. `session.nextExpectedGateIndex += 1` | 追加 1 条期待门 CrossingEvent（`accepted=true`, `reason=Accepted`）<br>+ 所有非期待门 accepted 的 CrossingEvent（`accepted=false`, `reason=UnexpectedGateOrder`） |
| `false` | **session state 保持不变**（`sectorEntries` / `passedGateIds` / `nextExpectedGateIndex` 原样）| 追加 1 条期待门 CrossingEvent（`accepted=false`，reason 保留 detection.reason）<br>+ 所有非期待门 accepted 的 CrossingEvent（`accepted=false`, `reason=UnexpectedGateOrder`） |

无论期待门是否 accepted，非期待门几何 accepted 的 CrossingEvent MUST 一律以
`accepted=false` + `reason=UnexpectedGateOrder` 记录（"非期待门即使几何过线也视为拒收"
的 v1 语义保留）。

`CrossingEvent` 追加顺序 MUST 为：期待门先 + 非期待门按 `orderedSectorGates` 的 `sequenceIndex`
从小到大顺序。每个 CrossingEvent.timestampMillis 用该门自己 detection 的插值毫秒
（不同门的 `crossingProgress` 可不同）。**该追加顺序 MUST NOT 被解读为 `timestampMillis` 时间单调**：
不同门的 crossingProgress 可能使插值毫秒先后不定；下游若需时间序消费，应在消费侧
自行 `sortedBy { it.timestampMillis }`。

#### Scenario: 期待门 accepted 推进 state + 记录 CrossingEvent

- **GIVEN** `activeLap.nextExpectedGateIndex = 1`，`orderedSectorGates[0]` 为期待门
- **AND** `(prev, current)` 几何上过期待门（accepted）
- **AND** 同一对 `(prev, current)` 不过任何非期待门
- **WHEN** `handleSectorCrossing` 处理
- **THEN** 返回 session 的 `activeLap.sectorEntries.size == 原值 + 1`
- **AND** `sectorEntries.last == SectorEntry(gateId=期待门.id, crossedAtMillis=插值毫秒)`
- **AND** `activeLap.passedGateIds.last == 期待门.id`
- **AND** `session.nextExpectedGateIndex == 原值 + 1`
- **AND** `session.crossingEvents.size == 原值 + 1`（仅追加期待门 event）
- **AND** 新 event `accepted == true` + `reason == Accepted`

#### Scenario: 期待门 rejected state 保持不变 + 仅记 CrossingEvent

- **GIVEN** `activeLap.nextExpectedGateIndex = 1`
- **AND** `(prev, current)` 几何上过期待门但被 `TooSlow` 或 `WrongDirection` rejected
- **AND** 同一对 `(prev, current)` 不过任何非期待门
- **WHEN** `handleSectorCrossing` 处理
- **THEN** 返回 session 的 `activeLap.sectorEntries.size == 原值`（不变）
- **AND** `activeLap.passedGateIds.size == 原值`（不变）
- **AND** `session.nextExpectedGateIndex == 原值`（不变）
- **AND** `session.crossingEvents.size == 原值 + 1`（仍记期待门 event 供诊断）
- **AND** 新 event `accepted == false`

#### Scenario: 多门同帧 accepted 全部记录（期待门 + 2 非期待门）

- **GIVEN** `(prev, current)` 对同时过期待门 + 2 个非期待门（所有几何均 accepted）
- **WHEN** `handleSectorCrossing` 处理
- **THEN** `session.crossingEvents.size == 原值 + 3`（硬区分 v1 只加 1 条）
- **AND** 新增 3 条 event 顺序：`[期待门, 非期待门按 orderedSectorGates 顺序的第一个, 第二个]`
- **AND** 期待门 event `accepted=true, reason=Accepted`
- **AND** 两个非期待门 event 均 `accepted=false, reason=UnexpectedGateOrder`
- **AND** `activeLap.sectorEntries` 仅追加期待门 SectorEntry（非期待门不推进 state）

#### Scenario: 期待门 rejected + 非期待门 accepted 场景

- **GIVEN** `(prev, current)` 过期待门被 rejected，同时过 1 个非期待门 accepted
- **WHEN** `handleSectorCrossing` 处理
- **THEN** `session.activeLap.sectorEntries.size == 原值`（state 不变，期待门 rejected）
- **AND** `session.crossingEvents.size == 原值 + 2`（期待门 rejected event + 非期待门 UnexpectedGateOrder event）
- **AND** 新增 event 顺序：`[期待门 rejected, 非期待门 UnexpectedGateOrder]`

#### Scenario: 多个非期待门按 orderedSectorGates 顺序追加

- **GIVEN** `track.sectorGates` 按 `sequenceIndex` 排序为 `[S1, S2, S3]`
- **AND** 期待门是 `S1`，`(prev, current)` 同时过 `S2` 与 `S3`（均 accepted）
- **WHEN** `handleSectorCrossing` 处理
- **THEN** 非期待门 event 顺序为 `[S2, S3]`（按 `orderedSectorGates` 的 `sequenceIndex` 从小到大）
- **AND** 即使 `track.sectorGates` 在数据层面构造为 `[S3, S2, S1]`（反 `sequenceIndex` 顺序），engine 内部 `sortedBy { sequenceIndex }` 后输出仍应 `[S2, S3]`（由 engine 排序保证确定性，与数据源顺序解耦）

---

### Requirement: E2E 合成契约圈时精度（R6）

E2E 合成测试 MUST 将圈时 durationMillis 相对期望值的绝对误差锁定在 5ms 以内（FakeClock +
fake replay 零 jitter 场景）。真机场景（真实 BLE + 真实 GPS）的精度契约不在本 Requirement
范围（留给未来真机回归战役）。

合成契约的可达性依据：

- `crossingProgress` 是 IEEE 754 Double 64-bit，对 `[0, 1]` 表达精度约 `2^-52 ≈ 2.2e-16`
- 插值时刻 `prev.ts + t × dt` 对 40-200ms 帧间距的亚毫秒级操作无舍入损失
- `Math.round` 到整数毫秒后误差 ≤ 1ms
- ±5ms 相对理论误差保守留 5x 余量，防 JVM 浮点实现差异

#### Scenario: STATIC 模式 10 秒圈 durationMillis 误差小于 5ms

- **GIVEN** `EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta` 构造
  FakeClock 严格 40ms 递增 250 次的 10 秒圈场景
- **WHEN** 管线跑完闭圈
- **THEN** `kotlin.math.abs(lap.durationMillis - 10_000L) < 5L` 成立（硬区分 v1 `in 9_980..10_020` 的 ±20ms 宽容）

#### Scenario: REPLAY 模式 1800ms 圈保持 ±5ms 不收紧

- **GIVEN** `EndToEndLapTimingContractTest.replayMode_lapDurationMatchesReplayClock` 构造
  fake replay 200ms 间隔 10 帧的对称过线场景，期望 `durationMillis == 1800L`
- **WHEN** 管线跑完闭圈
- **THEN** `kotlin.math.abs(lap.durationMillis - 1800L) < 5L` 成立（保持 v1 断言不收紧，防 JVM 浮点实现差异导致 CI 间歇失败）

---

### Requirement: A33 测试断言补齐 qualityFlags（R7）

原本遗漏的场景断言 MUST 补齐：`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors`
在闭圈后 MUST 断言 `lap.qualityFlags == listOf(LapQualityFlag.IncompleteSectors)`（场景
描述"两次起点过线中间完全不穿任何 sector"本应产生 `IncompleteSectors` 标签但 v1
测试遗漏断言）。硬并入本 change，不提供拆到后续 change 的兜底。

#### Scenario: 两次起终点过线 + 无 sector 过线 → qualityFlags == [IncompleteSectors]

- **GIVEN** 首次起终点过线开圈
- **AND** 第二次起终点过线闭圈，期间不穿任何 sector gate
- **WHEN** 检查闭圈后 `lap.qualityFlags`
- **THEN** `lap.qualityFlags == listOf(LapQualityFlag.IncompleteSectors)`（恰好 1 项，无其他 flag）

---

## MODIFIED Requirements

### Requirement: 闭圈 crossingEvents 裁剪必须用 filter 严格语义（A21 裁剪层）

闭圈时的 crossingEvents 裁剪 MUST 对每一个事件独立判断 timestamp 是否**严格大于**
`activeLap.startedAtMillis`（`event.ts > startedAt`，**不含**边界等值），逐元素 filter
保留或丢弃。实现上 MUST 使用 `filter { it.timestampMillis > activeLap.startedAtMillis }`，
MUST NOT 使用 `filter { it.timestampMillis >= activeLap.startedAtMillis }` 或 `dropWhile`。

**分层声明**：

- **代码层**（`LapTimingEngine.handleStartFinishCrossing` 生产路径）：禁用 `dropWhile`
- **测试层**：允许用 `dropWhile` 作对偶等价对照（见 MODIFIED Scenario 4），测试侧不计入生产代码路径

**R3 trajectory 与 R5 crossingEvents 的 filter 边界规则对比**：

| 数据流 | 下界 | 上界 | 语义依据 |
|---|---|---|---|
| `LapRecord.trajectory`（R3） | `ts >= startedAt`（含等号） | `ts < finishedAt`（严格小于） | 开圈帧含边界 / 闭圈帧归下圈 |
| `LapRecord.crossingEvents`（R5） | `ts > startedAt`（严格大于） | 无上界裁剪 | 开圈 event 归前一圈（避免边界 event 同属两圈） |

两者下界规则**刻意不同**（`>=` vs `>`），是因为 trajectory 是采样帧序列（物理时刻），crossingEvents 是过线事件序列（插值时刻），"同一时刻"的归属语义本质不同：

- trajectory 含开圈帧作为"本圈的物理起始采样"，不含闭圈帧因其已是下圈起点
- crossingEvents 不含开圈 event 因其是前一圈闭圈 event（同一 CrossingEvent 对象），保留在前一圈避免跨圈重复

实施方 MUST NOT 统一两处边界规则。

本 Requirement 修订来源：`fix-lap-timing-engine-entry-hardening` 中原 R3 Scenario 1
规则为 `>=`（含边界），但本 change 把 `CrossingEvent.timestampMillis` 从帧 ts 升级
为插值毫秒后，闭圈 event.ts 与下圈 startedAt 精确相等（同一过线瞬间，帧粒度容差
消失），`>=` 边界碰撞会让 Lap N 闭圈 event 被 Lap N+1 的 filter 同时捞走（同属两圈）。
`>` 严格大于让"边界事件归前一圈"，消除边界碰撞。

语义自洽约束：

- Lap N 闭圈时构造 LapRecord：`updatedEvents.filter { ts > activeLap.startedAtMillis }`
  保留闭圈 event（`event.ts == finishedAtMillis > startedAtMillis` 成立），含闭圈 event
- Lap N+1 闭圈时构造 LapRecord：同样 filter 规则，Lap N 闭圈 event.ts == Lap N+1.startedAtMillis
  → 严格 `>` 不成立 → 该 event **不属** Lap N+1，归前一圈
- Lap N+1.crossingEvents 不含开圈 event（= Lap N 闭圈 event），首个 event 为 Lap N+1
  期间的 sector event（若有）或本圈闭圈 event

#### Scenario: 单调正常序列 filter 保留所有 ts 严格大于 startedAtMillis 的事件（正向语义）

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=200), event(ts=300), event(ts=400)]`（严格单调递增）
- **AND** 当前即将闭圈的 `activeLap.startedAtMillis = 200L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** `LapRecord.crossingEvents == [event(ts=300), event(ts=400)]`（**边界 ts=200 不保留**，硬区分 v1 `>=` 下的 `[200, 300, 400]`）
- **AND** 语义上 ts=200 的事件归前一圈（作为前一圈闭圈 event），本圈首个 event 为 ts=300

#### Scenario: 边界碰撞场景 filter 严格大于让边界 event 归前一圈

- **GIVEN** Lap N 闭圈 `finishedAtMillis == 10_220`（= Lap N+1 的 startedAtMillis）
- **AND** Lap N 闭圈构造的 CrossingEvent `event.ts == 10_220` 进入 `session.crossingEvents`
- **WHEN** Lap N+1 后续闭圈时 `filter { it.ts > 10_220 }`
- **THEN** Lap N 的闭圈 event（ts=10_220）被 filter 排除（`10_220 > 10_220` false）
- **AND** Lap N+1.LapRecord.crossingEvents.first.ts > 10_220（不含边界 event）

#### Scenario: 非单调序列含 ts < startedAt 夹后，filter 拒收历史事件保持严格单点判定

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=250), event(ts=150), event(ts=400)]`
  （ts=150 夹在后面的历史事件，序列非单调）
- **AND** `activeLap.startedAtMillis = 200L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** `LapRecord.crossingEvents == [event(ts=250), event(ts=400)]`（ts=150 被拒收）
- **AND** 断言与 v2 前的 `>=` 语义结果等价（ts=150 < 200 在两种规则下都被拒）；本 Scenario 主要锁定"非单调 + 严格 `>`"组合

#### Scenario: 单调 filter `>` 与 dropWhile `<` 输出等价（防退化回归保护）

- **GIVEN** `session.crossingEvents = [event(ts=100), event(ts=200), event(ts=300), event(ts=400)]`（严格单调）
- **AND** `activeLap.startedAtMillis = 150L`
- **WHEN** `handleStartFinishCrossing` 构造 `LapRecord.crossingEvents`
- **THEN** filter 输出 `== [event(ts=200), event(ts=300), event(ts=400)]`
- **AND** 同一输入下 `dropWhile { it.timestampMillis <= 150L }` 的对照输出也是 `[event(ts=200), event(ts=300), event(ts=400)]`（注意 dropWhile 谓词也需同步调整为 `<=` 与 filter 的严格 `>` 对偶）
- **AND** 防退化断言：测试 MUST 同时断言 filter 输出与对照 dropWhile 输出逐元素相等 —— 防御"未来把 `>` 误改回 `>=`"
