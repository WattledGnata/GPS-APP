# fix-lap-timing-closure-and-precision-contract

战役 C change 2 升级为 **A15 + A20 + A32 + A33** 合并战役，把圈时契约从帧粒度
（±20ms）升级到毫秒级语义（±5ms 合成契约）。核心决策：采用**方案 (d) 插值时刻驱动**
—— 圈时起止用毫秒级插值时刻，trajectory 按时间窗口切分，CrossingEvent.timestampMillis
是"过线那一瞬间的插值毫秒"而非当前样本帧 ts。原 `fix-lap-timing-gate-and-boundary-contract`
proposal 的 A32 方案 (a)/(b)/(c) 对照表全部挪到 Alternatives 作为"帧粒度权衡"的**拒收
方案**（都只能在帧粒度里换帧归属，本质上无法跨越帧粒度精度边界）。

本 change 依赖：`fix-lap-timing-engine-entry-hardening` 已闭环归档（A19/A21/A34/A38
入口守卫 + R3 `filter` 裁剪层）。本 change 的 R5 **修订**已归档 change 的 spec R3
Scenario 1 边界（`>=` → `>`），属预期的契约扩展（edge case 在原 change 未写全，由本
change 在毫秒级语境下补齐），修订动作在本 proposal § Impact 里显式列出行号级变化。

## Why

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
§ 1.3 / § 1.4 / § 1.6 / § 1.8 + 功能设计讨论（2026-04-22）揭示圈时判定链路四条缺陷
在**毫秒级语义**这一根因上联动：

### A32 闭圈帧归属（§ 1.6）：帧粒度的"边界二义性"

`LapTimingEngine.handleStartFinishCrossing` 第 133 行 `val trajectory = updatedSamples.drop(activeLap.sampleStartIndex)` 让闭圈帧既属上圈末帧、又属下一圈 ActiveLap 首帧（第 172 行 `sampleStartIndex = updatedSamples.lastIndex`）。下游 `laps.sumOf { trajectory.size }` 每闭圈多算 1 帧。

**帧粒度的根因**：当前 `ActiveLap.startedAtMillis` / `LapRecord.finishedAtMillis` 都直接取 `currentSample.timestampMillis`（帧 ts）；`trajectory` 切分靠 `sampleStartIndex` 整数索引。边界只能在帧与帧之间选归属，无法表达"过线发生在帧间 40ms 内的某一时刻"。

### A20 多门同帧丢失（§ 1.4）：帧粒度"无法拆分过线时刻"

`handleSectorCrossing` 第 188-192 行 `firstOrNull { accepted }` 让一对 `(prev, current)` 同时过多门时只记**第一个**非期待门 UnexpectedGateOrder，其余 accepted 门被吞掉。即使遍历所有 accepted 门，v1 在同一 `currentSample.timestampMillis` 下生成的 CrossingEvent 共享同一 ts，下游按时间排序事件时**无法分辨谁先谁后**（帧粒度假设"同帧同时刻"）。

**帧粒度的根因**：多门同帧过线在物理上是按 `crossingProgress`（0→1）先后发生的，v1 的 `CrossingEvent.timestampMillis = currentSample.timestampMillis` 抹平了先后顺序。

### A15 穿线时刻帧粒度精度（功能设计 2026-04-22）：圈时误差上限

当前 `LapRecord.durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis` 是帧 ts 差。TFIC 10 秒圈场景帧粒度上界误差 ≈ 两次过线帧粒度合并 ±20ms（现行
`EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta` line 99-101 的契约就是 `durationMillis in 9_980..10_020`）。业界（VBOX / RaceChrono / Harry's LapTimer）按毫秒级精度报圈时，与帧粒度差一个数量级。

**帧粒度的根因**：`startedAtMillis` / `finishedAtMillis` 必须是帧 ts，无法表达"过线瞬间"，精度上限 = 采样间隔。

### A33 测试断言强度（§ 1.8）：随带修订

`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors`
只断言 `durationMillis` 不断言 `qualityFlags = listOf(IncompleteSectors)`。本 change 的 R5
改 filter 规则时触及 crossingEvents 语义，顺路补齐 A33 的 `qualityFlags` 断言，零边际成本。

### 四条联动的共同根因

**圈时契约未在毫秒级语义上拍板**：从 `GateCrossingDetection` → `CrossingEvent` → `ActiveLap` → `LapRecord` 的整条链路都是帧粒度数据流。想要解决 A32 重叠 + A20 多门 + A15 精度，打补丁解不了（方案 (a)/(b)/(c) 都是帧粒度内换归属），必须升级到毫秒级语义：**CrossingEvent.timestampMillis 改为插值时刻，trajectory 改为时间窗口切分**。这是"一次性到位"的语义升级，不是"修修补补"。

**业界对齐**作为 Why 的论据（参考 VBOX / RaceChrono / Harry's 的"毫秒级过线"定位），本 change 自身通过 **R6 ±5ms E2E 合成契约**兑现（不是空口号，见 § What R6）。

## 核心决策

### 决策 1：方案 (d) 插值时刻驱动

数据模型与语义变化：

```kotlin
data class GateCrossingDetection(
    val accepted: Boolean,
    val reason: CrossingReason,
    val directionalSpeedMps: Double?,
    val directionScore: Double?,
    /**
     * 过线插值参数 t ∈ [0, 1]（prev→current 线段的归一化位置）。
     * - accepted == true 时 MUST 非 null 且经 clamp 到 [0.0, 1.0]（防 segmentsIntersectMeters
     *   浮点边界越界：实际 t 可能 = -1e-16 或 1.0000001）
     * - accepted == false 时 MUST 为 null
     * 插值时刻 = previousSample.timestampMillis + crossingProgress × (currentSample.timestampMillis - previousSample.timestampMillis)
     * 单位：无（归一化参数）
     */
    val crossingProgress: Double? = null
)

data class CrossingEvent(
    ...
    /** 插值后的毫秒级过线时刻（= prev.ts + t × (current.ts - prev.ts)），不等于任何帧 ts。 */
    val timestampMillis: Long,
    /**
     * 触发 detection 的 currentSample 在 session.samples 中的整数索引。
     * **不代表过线时刻对应帧**：过线时刻是插值毫秒，位于 (prev, current) 区间内，既不在
     * sampleIndex 处也不在 sampleIndex-1 处；此字段仅用于诊断追溯"哪一帧触发了本 event"。
     */
    val sampleIndex: Int,
    ...
)

data class ActiveLap(
    /** 开圈的插值时刻（毫秒级），= 对应起点过线 CrossingEvent.timestampMillis。 */
    val startedAtMillis: Long,
    ...
    /**
     * 开圈瞬间 `updatedSamples.lastIndex`（触发闭圈检测的 currentSample 在 session.samples
     * 中的整数索引，也即本圈**可能**的首帧位置）。
     *
     * v2 语义重定义：**仅作性能索引（非归属判定依据）**。
     * - 归属判定**只**依赖时间窗口 `[startedAtMillis, finishedAtMillis)`（见 LapRecord.trajectory）
     * - sampleStartIndex 作为 `subList(sampleStartIndex, updatedSamples.size)` 的搜索起点，
     *   把 O(session.samples.size) 的全局扫描降到 O(本圈 trajectory 规模)
     * - 若 sampleStartIndex 与时间窗口边界冲突（理论上可能因 A38 ts 回跳守卫插入"幻帧"），
     *   **以时间窗口为准**，sampleStartIndex 仅用作 subList 起点，不参与最终 filter 判定
     */
    val sampleStartIndex: Int,
    ...
)

data class LapRecord(
    /** 开圈插值时刻（= 该圈起始 CrossingEvent.timestampMillis）。 */
    val startedAtMillis: Long,
    /** 闭圈插值时刻（= 该圈结束 CrossingEvent.timestampMillis，= 下圈 startedAtMillis）。 */
    val finishedAtMillis: Long,
    /** 毫秒级精度的圈耗时（= finishedAtMillis - startedAtMillis）。 */
    val durationMillis: Long,
    /**
     * 本圈 GpsSample 集合，按时间窗口切分：
     *   `startedAtMillis <= sample.timestampMillis < finishedAtMillis`
     * 左闭右开：含开圈时刻对应帧，不含闭圈时刻对应帧（闭圈帧归下圈首帧）。
     */
    val trajectory: List<GpsSample>,
    ...
)
```

`GateCrossingDetector.segmentsIntersectMeters` 返回值：`Boolean` → `Double?`（内部已算 `t`
参数，只是之前丢弃；返回 null 表示不相交，非 null 为 t 值）。

### 决策 2：A21 filter 边界碰撞的处理 —— 选 (d+P) 严格大于

插值时刻引入后，闭圈 event.ts 与下圈 startedAt 精确相等（两者都是同一过线的插值时刻，**不再像 v1 那样隐含 40ms 帧粒度容差**）。engine-entry-hardening 的 R3 filter `ts >= activeLap.startedAtMillis` 在 Lap N+1 构造 LapRecord 时会把 Lap N 闭圈 event 也拿走 —— 边界碰撞。

两种修法：

- **(d+P) filter 边界修订**：`>=` → `>`，严格大于；边界 event 归前一圈。需修订 engine-entry-hardening spec R3 Scenario 1 + 同步改 `LapTimingEngine.kt` filter 代码 1 行。
- **(d+E) CrossingEvent 加 lapBoundarySide 枚举字段**：`Upper / Lower / None`，filter 改为复合判定（`ts > start || (ts == start && side == Upper)`）。

三维论证：

| 维度 | (d+P) 边界 `>=` → `>` | (d+E) 加 lapBoundarySide 枚举 |
|---|---|---|
| **改动面** | 2 处：`LapTimingEngine.kt` filter 1 行 + engine-entry-hardening spec R3 Scenario 1 边界文本 | 4+ 处：新 enum + CrossingEvent 加字段 + 所有 CrossingEvent 构造点改 + filter 改复合判定 + 新增 Scenario 覆盖 enum 各值 |
| **语义纯度** | 单一规则"边界事件归前一圈"；不引入新维度 | 归属显式枚举化，但引入"事件可属两种状态"新维度 |
| **未来扩展** | 若出现"多重边界归属"（例：ts 回跳帧 / 同一帧既闭旧圈又开新圈的极端场景）需回头重构 | 预留"多归属"字段（实际 v2 下 TestSessionViewModel 已在 bridge 层拦 ts 回跳 — 见 A38/R4，极端场景不出现） |

**选 (d+P)**：YAGNI。本 change 的 edge case 只有"边界碰撞归前一圈"，不引入新枚举。若未来确实出现"多重归属"需求，届时再做 (d+E) 扩展不晚。

### 决策 3：trajectory 时间窗口 + filter 边界的一致性

统一"边界时刻归前一圈"原则，两处切分规则对偶：

- **GpsSample 时间窗口**（trajectory 切分）：`sample.ts >= startedAt && sample.ts < finishedAt` （左闭右开）
  - 若某 sample.ts 精确 == startedAt：归本圈（首帧）
  - 若某 sample.ts 精确 == finishedAt：不归本圈（归下圈首帧）
- **CrossingEvent 时间窗口**（filter 裁剪）：`event.ts > startedAt`（严格左开右闭：右端自然由"构造时机是本圈闭圈"保证）
  - 开圈 event.ts 精确 == startedAt：不归本圈（归上一圈，作为上一圈闭圈 event）
  - 闭圈 event.ts 精确 == finishedAt：归本圈（由 updatedEvents 末尾 append 保证）

数据一致性自洽：

- `LapRecord.trajectory.first.ts >= startedAt`（首帧 ts 下界契约）
- `LapRecord.trajectory.last.ts < finishedAt`（末帧 ts 上界契约，不含闭圈帧）
- `LapRecord.crossingEvents.last` 是闭圈 event（event.ts == finishedAt）
- `LapRecord.crossingEvents.first` 是首个 sector event（不含开圈 event）—— 首圈 crossingEvents = [sector events..., 闭圈 event]，其余圈同理
- `session.samples.size == Σ completedLaps.trajectory.size + activeLapTrajectorySize` 严格相等（每帧恰属一圈）

### 决策 4：归档 change spec 修订的工作流合规性

选 **(b) 本 change spec 用 `## MODIFIED Requirements` 段显式覆盖 engine-entry-hardening R3 Scenario 1**：

- 本 change `specs/lap-timing-engine/spec.md` 结构为 `## ADDED Requirements`（R1/R2/R3/R4/R6/R7）+ `## MODIFIED Requirements`（R5 覆盖 engine-entry-hardening R3）
- `## MODIFIED Requirements` 段复制原 Requirement 标题 + 修订后正文，openspec CLI 会识别为"本 change 对已有 Requirement 的修订"
- **CLI 已 dry-run 验证**：在本 change 临时写入单 Scenario 的 MODIFIED 段 + 空 proposal 骨架跑 `openspec validate --strict`（2026-04-24 前 CLI 名为 `openspec-chinese`，现统一为 `openspec`），结果 `Change ... is valid`；CLI 不要求 MODIFIED 的 target Requirement 必须已在主 spec 中存在即可通过验证（engine-entry-hardening 未归档亦不阻塞），故无需 archive 时序前置步骤
- 归档时序：tasks.md 合流门槛仍建议包含"engine-entry-hardening archive 状态检查"作为审计项，但不作为 CLI 验证硬依赖

拒收方案 (a) 直接改归档文件：破例工作流，审计链需额外文档标注，审计成本高于 (b)
拒收方案 (c) 临时把 engine-entry-hardening 移回 active：改状态机复杂度远大于 (b) 的 MODIFIED 段

### 决策 5 · 插值模型选型（匀速 / 匀加速 / 几何升级）

**决策**：本 change 采用**帧间线性（匀速）插值** `interpolatedMillis = prev.ts + t × (current.ts - prev.ts)`。

**量级分析表**（本决策的定量依据，v = 50 m/s 典型值）：

| 偏差源 | 40ms 帧距 | 1Hz 帧距 | 本 change 合成契约影响 |
|---|---|---|---|
| 匀速 vs 匀加速（a=5 m/s²，中等） | < 0.1 ms | 50 ms | 40ms 下可忽略 |
| 匀速 vs 匀加速（a=20 m/s²，2G 刹车） | < 0.1 ms | 200 ms | 40ms 下可忽略 |
| 弦长 vs 弧长（弯道 R=50m, v=30m/s） | ~3 ms | 秒级 | 40ms 下可忽略 |
| GPS 位置噪声（±1-3m） | ±20-60 ms | ±20-60 ms | 与插值模型无关 |
| Skip Δt 异常拉长 | 2-8 ms | 2-8 秒 | 与插值模型无关 |
| IEEE 754 Double 舍入 | < 1e-6 ms | < 1e-6 ms | 忽略 |

量级公式（匀速 vs 匀加速）：`|Δτ| ≈ 0.5·|a|·Δt²/v`。Δt² 项在 1Hz 下主导。

**为什么 40ms 高频设备不升级**：

1. 40ms 帧距下匀加速修正 < 0.1ms，远低于 R6 ±5ms 合成契约阈值
2. 真机主要偏差源（GPS 位置噪声 ±20-60ms）比插值模型偏差大两个数量级
3. GPS 噪声未抑制前升级插值模型，等于优化被噪声完全淹没的次要项

**为什么要留档**：

当前仅支持 40ms 高频 BLE GPS（RaceChrono）。**未来扩展到 1Hz 弱定位设备**（手机内置 GPS / 入门级外设 / 摩托车载设备）时，量级关系发生**质变**：

- 匀速偏差从 0.1ms 放大到 50-200ms（Δt² 主导）
- 弦长 vs 弧长偏差从 3ms 放大到秒级
- GPS 噪声反而被大 Δt 平均稀释
- **插值模型从"次要项"反转为"主要矛盾"**

**1Hz 设备插值升级路径**（供未来战役参考，非本 change 范围）：

- **阶段 1 · 匀加速时间插值**：利用 GPS Doppler 直测的 `prev.speed` / `current.speed`，解二次方程 `v1·τ + 0.5·a·τ² = s_target` 得 τ
- **阶段 2 · 弦长/弧长区分**：当 `|heading(prev) - heading(current)|` > 阈值时启用圆弧近似
- **阶段 3 · 基于朝向的二阶几何**：用 heading 构造 Bezier / 圆弧拟合，过线点从"直线 ∩ gate"升级为"曲线 ∩ gate"
- **阶段 4 · 超阈值 Δt 拒收**：Δt > 阈值（建议 500ms / 1000ms / 2000ms 分档）时标记 `qualityFlag = LowSamplingRatePrecision` 或拒收该次过线事件

**未来战役占位**（已登记于 `docs/superpowers/reviews/attack-backlog.md` 第六节）：

- `fix-gps-position-denoise` · GPS 位置噪声抑制（Kalman / 位置平滑）
- `fix-laptime-skip-frame-precision` · skip 场景 Δt 阈值检测
- `fix-laptime-low-freq-device-support` · 1Hz 弱定位设备支持（阶段 1-4）

**升级次序**（物理量级驱动，**不可跳级**）：

1. 先抑制 GPS 噪声（主矛盾，±20-60ms → ±5-10ms）
2. 再处理 Skip 场景（次矛盾，2-8ms → < 1ms）
3. 最后才升级插值模型（1Hz 下 50-200ms → < 10ms）

跳过前两步直接升级插值模型 = 优化被噪声完全淹没的次要项，典型"优化了错的东西"。

**本 change 自我约束**：

为防止升级路径被提前落进来扰乱 ±5ms 合成契约的前置量级假设，tasks §8.11 新增 grep 门槛，禁止本 change 引入 `prev.speed` / `current.speed` 用于插值的代码路径。

## What

### R1 GateCrossingDetector.detect 返回 crossingProgress

`GateCrossingDetection.crossingProgress: Double?`：
- accepted=true 时 MUST 非 null，MUST 经 `coerceIn(0.0, 1.0)` clamp（防浮点边界越界，如 `segmentsIntersectMeters` 算出 -1e-16 或 1.0000001）
- accepted=false 时 MUST 为 null
- 单位：无（归一化参数）；取值范围：`[0.0, 1.0]`

`segmentsIntersectMeters(ax, ay, bx, by, cx, cy, dx, dy)` 返回 `Double?`：null 表示不相交，非 null 为 `t` 参数（prev→current 线段上过线点的归一化位置）。

### R2 LapTimingEngine 使用插值时刻构造 ActiveLap / LapRecord / CrossingEvent

- `CrossingEvent.timestampMillis` = `previousSample.timestampMillis + crossingProgress × (currentSample.timestampMillis - previousSample.timestampMillis)`（毫秒级 Long，MUST 四舍五入到整数毫秒）
- `ActiveLap.startedAtMillis` = 对应开圈 CrossingEvent.timestampMillis（插值时刻）
- `LapRecord.startedAtMillis` = `ActiveLap.startedAtMillis`
- `LapRecord.finishedAtMillis` = 闭圈 CrossingEvent.timestampMillis（插值时刻）
- `LapRecord.durationMillis` = `finishedAtMillis - startedAtMillis`（毫秒级精确，不再是帧 ts 差）
- `ActiveLap.sampleStartIndex` 字段**保留**，但**语义降级**为性能索引（不参与归属判定；见决策 1 数据模型注释）
- `CrossingEvent.sampleIndex` 字段**保留**，语义变更：从"过线时刻对应帧索引"（帧粒度语义，v1）→"触发 detection 的 currentSample 索引"（诊断语义，v2），MUST 在 R2 Spec 显式定义
- `CrossingEvent.timestampMillis` 语义重定义为插值毫秒（独立于 sampleIndex，两者指向不同对象）

### R3 LapRecord.trajectory 按时间窗口切分（含性能索引）

`handleStartFinishCrossing` 构造 LapRecord 时采用**两段式切分**（subList 索引跳过 +
filter 时间窗口裁剪），把性能锁定在 O(本圈 trajectory 规模)：

```kotlin
// v2 切分：subList 以 sampleStartIndex 作性能索引起点 + filter 用时间窗口做最终归属判定
val trajectory = updatedSamples
    .subList(activeLap.sampleStartIndex, updatedSamples.size)
    .filter { sample ->
        sample.timestampMillis >= activeLap.startedAtMillis &&
            sample.timestampMillis < lapRecord.finishedAtMillis
    }
```

- **subList 阶段（性能索引）**：从 `sampleStartIndex` 开始扫描，跳过 `session.samples` 前面的历史帧；复杂度 O(N - sampleStartIndex) = O(本圈帧数)
- **filter 阶段（最终归属判定）**：严格按时间窗口 `[startedAtMillis, finishedAtMillis)` 裁剪，左闭右开；**归属判定只依赖时间窗口，不依赖 sampleStartIndex**（防御 A38 ts 回跳守卫可能让 sampleStartIndex 位置与时间窗口边界错位）

不变式：
- `trajectory.first.ts >= startedAtMillis`（若 trajectory 非空）
- `trajectory.last.ts < finishedAtMillis`（若 trajectory 非空）
- `trajectory.none { it.ts == finishedAtMillis }`
- `trajectory.none { it.ts < startedAtMillis }`（即使 sampleStartIndex 指向更早的帧，filter 也会排除）

### R4 A20 handleSectorCrossing 多门 accepted 全记

```kotlin
// 1. 对所有 sector gate 逐个 detect
val allDetections = orderedSectorGates.map { gate ->
    gate to detector.detect(previous = previousSample, current = currentSample, gate = gate)
}
// 2. 期待门（可能 accepted 或 rejected）
val expectedGateDetection = allDetections.first { (gate, _) -> gate.id == targetGate.id }.second
// 3. 非期待门中所有 accepted
val unexpectedAccepted = allDetections.filter { (gate, d) -> gate.id != targetGate.id && d.accepted }

// 4. 构造事件：期待门先，非期待门按 orderedSectorGates 顺序
val allNewEvents = buildList {
    add(crossingEventFromDetection(targetGate, expectedGateDetection, updatedSamples.lastIndex))
    unexpectedAccepted.forEach { (gate, detection) ->
        add(
            CrossingEvent(
                gateId = gate.id,
                ...
                timestampMillis = interpolate(detection.crossingProgress, previousSample, currentSample),
                accepted = false,  // 非期待门一律 rejected
                reason = CrossingReason.UnexpectedGateOrder,
                ...
            )
        )
    }
}
```

注：多门同帧下每个门的 `crossingProgress` 可不同（不同 gate 线位置不同），生成的 CrossingEvent.timestampMillis 顺序由 crossingProgress 大小决定。期待门 / 非期待门分组后**内部**按 orderedSectorGates 的 `sequenceIndex` 排列，保证确定性。

**state 推进规则明细**（Spec 必须显式覆盖）：

| 分支 | 期待门 detection.accepted | 动作 |
|---|---|---|
| **期待门 accepted** | true | 1. 追加 `SectorEntry(gateId=targetGate.id, crossedAtMillis=插值时刻)` 到 `activeLap.sectorEntries`（crossedAtMillis 用插值毫秒，不用 currentSample.ts）<br>2. 追加 `targetGate.id` 到 `activeLap.passedGateIds`<br>3. `session.nextExpectedGateIndex += 1`<br>4. 追加期待门 CrossingEvent（accepted=true）<br>5. 追加所有非期待门 accepted 的 CrossingEvent（accepted=false, reason=UnexpectedGateOrder） |
| **期待门 rejected** | false | 1. **state 保持不变**（`sectorEntries` / `passedGateIds` / `nextExpectedGateIndex` 原样）<br>2. 仅追加期待门 CrossingEvent（accepted=false）<br>3. 追加所有非期待门 accepted 的 CrossingEvent（accepted=false, reason=UnexpectedGateOrder） |

无论期待门是否 accepted，非期待门 accepted 的 CrossingEvent 一律 `accepted=false` + `reason=UnexpectedGateOrder`（"非期待门即使几何过线也视为拒收"的 v1 语义保留）。

### R5 crossingEvents 裁剪 filter 边界改严格 `>`（修订 engine-entry-hardening）

```kotlin
// v1 (engine-entry-hardening R3)：
crossingEvents = updatedEvents.filter { it.timestampMillis >= activeLap.startedAtMillis }

// v2 (本 change)：
crossingEvents = updatedEvents.filter { it.timestampMillis > activeLap.startedAtMillis }
```

**此 R5 修订**已归档的 `fix-lap-timing-engine-entry-hardening/specs/lap-timing-engine/spec.md`
R3 Scenario 1（"单调正常序列 filter 保留所有 ts >= startedAtMillis 的事件（正向语义）"）
→ 改为 "`ts > startedAtMillis`（严格大于，边界事件归前一圈）"，并在文本中交叉引用本
change R5。属预期契约扩展（engine-entry-hardening 当时在帧粒度假设下写，未考虑边界
精确相等的场景；本 change 在毫秒级语境下补齐）。

### R6 E2E ±5ms 圈时合成契约

合成测试（FakeClock + fake replay 零 jitter 环境）圈时契约收紧（**二选一已拍板**）：
- `EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta` line 99-101：`durationMillis in 9_980..10_020` → `kotlin.math.abs(lap.durationMillis - 10_000) < 5`（±5ms 硬契约）
- `EndToEndLapTimingContractTest.replayMode_lapDurationMatchesReplayClock` line 170-173：**保持 `deltaAbs < 5L`**（不收紧到 `< 2L`，避免 JVM 浮点实现差异导致 CI 间歇失败；v0 proposal 中曾保留的"收紧自由度"在本 V4 拍板拒收）

**可达性论证**：

1. 浮点精度层面：`crossingProgress` 是 Double（IEEE 754 64-bit），对 `[0.0, 1.0]` 范围内数值表达精度约 `2^-52 ≈ 2.2e-16`；插值时刻 = `prev.ts + t × (current.ts - prev.ts)` 对 200ms 帧间距的亚毫秒级操作完全无舍入损失。STATIC 场景 40ms 帧间距下插值时刻精度 ≈ 亚微秒，`Long` 四舍五入到毫秒整后误差 ≤ 1ms。
2. 对称过线场景：STATIC 与 REPLAY 的 fake 测试里构造 off-gate / through-gate 位置对称偏移 0.25 × passDirection，u = t = 0.5 理论精确。开圈 / 闭圈时刻相对帧 ts 的偏移相等，相减时差相消 → durationMillis 理论 == 帧 ts 差 —— 与 v1 结果一致，断言保持。
3. 不对称过线场景（未来扩展测试）：t 差异引入毫秒级微差，`abs < 5` 仍保守可达。

**合成场景边界说明**：R6 仅对 FakeClock + fake replay 合成测试契约；真机场景存在上游 GPS 时钟抖动（±2-4ms @25Hz）+ BLE 传输延迟（变动），真实可达精度 ≈ ±10ms，不在 R6 合成契约范围内。真机精度契约留给未来真机回归战役独立立项。

### R7 A33 断言补齐（硬并入本 change）

`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors`
第 54-75 行末尾追加：`assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)`。

零边际成本（1 行断言），与 R5 改 filter 规则时的 crossingEvents 语义调整同窗口顺路做。
**硬并入**：本 R7 不提供"拆到 change 3 独立处理"兜底（对标 R6 显式拒收自由度）；若
实施过程暴露预期外失败，按正常修复流程处理，不作为"可撤回 R7"的触发条件。

## Impact

### 数据模型破坏性变更

| 字段 | v1 语义 | v2 语义 | 破坏性 |
|---|---|---|---|
| `GateCrossingDetection.crossingProgress` | — | 新增 Double?，[0,1]，accepted 时非空 | ✅ 非破坏（新字段 + 默认 null） |
| `CrossingEvent.timestampMillis` | 当前帧 ts（= currentSample.timestampMillis） | 过线插值时刻（毫秒级，不等于任何帧 ts） | ❌ 语义变（下游若假设 `event.ts == someSample.ts` 会失败） |
| `ActiveLap.startedAtMillis` | 开圈帧 ts | 开圈插值时刻 | ❌ 语义变 |
| `ActiveLap.sampleStartIndex` | 开圈帧索引，参与 trajectory 归属判定（`drop(sampleStartIndex)` 含闭圈帧） | **保留**，语义降级为性能索引（仅作 subList 起点，归属由时间窗口决定） | ⚠️ 语义降级（字段不变，但消费方式改） |
| `CrossingEvent.sampleIndex` | 过线时刻对应帧索引（帧粒度假设） | 触发 detection 的 currentSample 索引（诊断语义，不代表过线时刻帧） | ⚠️ 语义变（字段不变，但下游若假设 `event.sampleIndex 处的 sample.ts ≈ event.ts` 会失败） |
| `LapRecord.startedAtMillis` / `finishedAtMillis` / `durationMillis` | 帧 ts 差 | 插值时刻 / 毫秒精确差 | ❌ 语义变（数值变化 0~40ms） |
| `SectorEntry.crossedAtMillis` | 过 sector 门那一帧的 `currentSample.timestampMillis`（帧粒度） | 过 sector 门的插值毫秒（`prev.ts + t × (current.ts - prev.ts)`） | ❌ 语义变（数值变化 0~40ms，`LapRecord.sectorTimes` 派生值同步偏移） |
| `LapRecord.trajectory` | `drop(sampleStartIndex)`（含闭圈帧） | 时间窗口 `[startedAt, finishedAt)` 切分（不含闭圈帧） | ❌ 内容变（差 1 帧 / 每闭圈） |

### E2E 断言行号级修订清单（评审方硬要求）

`feature/test/src/test/java/com/blazepush/feature/test/usecase/EndToEndLapTimingContractTest.kt`：

| 测试方法 | 当前行号 | v1 断言 | v2 断言 | 修订理由 |
|---|---|---|---|---|
| `staticMode_lapDurationMatchesSenderClockDelta` (8.2) | 98-101 | `lap.durationMillis in 9_980..10_020` | `kotlin.math.abs(lap.durationMillis - 10_000) < 5` | R6 ±5ms 合成契约 |
| `replayMode_lapDurationMatchesReplayClock` (8.3) | 168-173 | `deltaAbs < 5L` | **保持 `< 5L` 不动**（二选一已拍板，不收紧到 `< 2L`） | 对称过线下 v1/v2 数值一致，已满足 R6；防 CI 浮点漂移 |
| `coldStartOnlyMainNoTimePacket_engineDoesNotStartLap` (8.4) | 210-223 | `samples.size==0, activeLap==null, completedLaps.isEmpty` | 不变 | 仅 sentinel 守卫契约，与插值 / 时间窗口切分无关 |
| `shortTimeDesyncRecoversWithoutSpuriousCrossing` (8.5) | 329-332 | `postRecoverSamples == preRecoverSamples + 4` | 不变 | bridge 首样本分支未改；engine.samples 填充逻辑未改（仍是 `session.samples + currentSample`） |
| `endToEndCoreClockSourceIntegrity_generatorAndEngineNotInvolveSystemClock` (8.6) | 355-358 | 字节码扫描 `currentTimeMillis` | 不变 | 字节码扫描不受数据模型影响 |
| `lapWithProtocolDesyncGap_laprecordFlagged` (8.7) | 422-427 | `completedLaps.size == 1` + `qualityFlags.contains(ProtocolDesyncGap)` | 不变 | `trajectory.zipWithNext()` desync 扫描仅依赖相邻帧 ts 差；本 change 的 trajectory 切分不影响前段相邻差判定（只是尾部不含闭圈帧，但 8.7 测试构造的 desync gap 在圈中部，不在尾部） |

合计：**1 条主改**（8.2）+ **5 条无变化**（8.3 拒收收紧保持 `< 5L` + 8.4/8.5/8.6/8.7）。

### LapTimingEngineTest 断言行号级修订清单

`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt`
（原 10 条 + change 1 已追加 10 条 = 共 20 条）中涉及语义变化的现有测试：

| 测试方法 | 行号 | v1 断言关键字 | v2 预期修订方向 |
|---|---|---|---|
| `processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` | 75 | `assertEquals(267_000L, lap.durationMillis)` | 插值时刻后 durationMillis 仍等于帧 ts 差（开圈/闭圈对称偏移 t=0.5 差相消）；断言保持 `== 267_000L`。R7 追加 `assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)` |
| `processSample_startFinishThenOrderedSectorsThenStartFinish_completesLapWithSectorTimes` | 111 | `assertEquals(listOf(250_600L, 8_200L), lap.sectorTimes)` | SectorEntry.crossedAtMillis 改用插值毫秒后，sectorTimes 派生值取决于 sector 门穿线 t 值；若测试 fixture 里 sector 过线也是对称（crossingSamples 偏移 0.25 对称），t=0.5 下 sector 时间差相消，断言保持；**但需在 spec Scenario 里锁定"对称过线 fixture 下 sectorTimes 数值等式"**，且回归测试允许 `delta < 2` 浮点容差 |
| `processSample_missingSectorStillCompletesLapWithIncompleteFlag` | 147 | `assertEquals(listOf(250_600L), lap.sectorTimes)` | 同上，对称过线下 sector 时间保持；容差 `delta < 2` |
| `processSample_lapWithProtocolDesyncGap_isFlagged` | 233 | `assertEquals(1773478143690L - 1773477876690L, lap.durationMillis)` | 开圈/闭圈对称过线差相消，durationMillis 保持帧 ts 差；断言保持 |
| `handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt`（change 1 追加） | 文件末尾（见 change 1 归档 spec R3 Scenario 1） | `== [200L, 300L, 400L, 500L]`（`>=` 含边界 ts=200） | **R5 修订 `>=` → `>`**：边界 ts=200 归前圈 → 断言改为 `== [300L, 400L, 500L]`；测试名附加 `_strictlyGreaterThan_` 后缀区分 v2 语义；**同时修订测试 Scenario 1 描述**"过线事件 MUST 严格大于 startedAtMillis" |
| `handleStartFinishCrossing_outOfOrderHistoricalEventHardDistinguishesFilterVsDropWhile`（change 1 追加） | 文件末尾 | `== [250L, 400L, 500L]`（startedAt=200） | R5 下断言保持（ts=250 > 200 仍留，非边界事件）；测试意图从"filter vs dropWhile 区分"升级为"非单调 + 边界 v2 `>` 严格"；内部 dropWhile 对照代码需同步更新边界到 `> 200` |
| `handleStartFinishCrossing_monotonicSequence_filterOutputEqualsDropWhileOutput`（change 1 追加） | 文件末尾 | filter (`>= 150`) 与 dropWhile 对照等价 → `== [200L, 300L, 400L, 500L]` | R5 下 filter (`> 150`) 与 dropWhile 对照仍等价（fixture ts 无 150 相等）；断言保持；**但 filter 边界参数需从 `150L` 同步改为 v2 真实 `startedAtMillis` 值**（测试内 `drop` 也同步改） |
| `processSample_firstStartFinishCrossing_startsLapWithoutCompletingLap` | 38-51 | `activeLap!!.passedGateIds`, `currentLapIndex` | ActiveLap.sampleStartIndex 保留但语义降级为性能索引；断言 passedGateIds/currentLapIndex 保持；若测试构造含 sampleStartIndex 值，数值保持不变（= lastIndex） |

合计：**4 条 durationMillis / sectorTimes 断言保持语义（对称 fixture 差相消）** + **3 条 filter 边界断言修订（R5 `>=` → `>`）** + **1 条新增断言（R7 qualityFlags）**。需另起 R1-R4 新增测试（未列表）约 12-15 条 Scenario 覆盖。

### 修订已归档 change 的 spec

- `openspec/changes/archive/fix-lap-timing-engine-entry-hardening/specs/lap-timing-engine/spec.md`
  R3 Scenario 1（"单调正常序列 filter 保留所有 ts >= startedAtMillis 的事件"）：
  - 边界 `>=` → `>`（严格大于）
  - 文本交叉引用本 change R5，说明"毫秒级语境下边界事件归前一圈"
  - 同步更新归档 change 的 `LapTimingEngineTest.handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt`（该测试构造 `crossingEvents = [ts=100, ts=200, ts=300, ts=400] + startedAtMillis = 200L`，v1 断言 `== [200, 300, 400]`，v2 在新边界下 `== [300, 400]`；需同步更新断言值 + 测试名附加 "_boundary_strictlyGreaterThan_"）。

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| detector | `feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` | `segmentsIntersectMeters` 返回值 `Boolean` → `Double?`；`detect` 计算 crossingProgress 并填入 GateCrossingDetection |
| model | `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt` + `GateCrossingDetection` 所在文件 | crossingProgress 字段 + timestampMillis 语义注释更新 |
| model | `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt` | `sampleStartIndex` 字段保留但注释降级为"性能索引"；`startedAtMillis` 语义注释更新为"插值时刻" |
| model | `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt` | `startedAtMillis` / `finishedAtMillis` / `durationMillis` / `trajectory` 语义注释 |
| engine | `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` | `handleStartFinishCrossing` 用插值时刻 + trajectory 两段式切分（subList + filter）；`handleSectorCrossing` 多门完整遍历 + state 推进规则按期待门 accepted/rejected 分支；filter 边界改严格 `>`；`sampleStartIndex` 引用保留（用作 subList 起点） |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt` | R1/R2/R3/R4/R5/R7 对应新测试；现有测试断言按下方行号级修订清单更新 |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/usecase/EndToEndLapTimingContractTest.kt` | 8.2 断言收紧；8.3/8.4/8.5/8.6/8.7 断言保持（见 § E2E 行号级修订清单） |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt` | 复核涉及 lapSession / session.samples 大小的断言（预计无变化，因为 samples 填充逻辑未改） |
| 归档 spec 修订 | `openspec/changes/archive/fix-lap-timing-engine-entry-hardening/specs/lap-timing-engine/spec.md` | R3 Scenario 1 `>=` → `>` + 交叉引用本 change |
| 归档测试修订 | `LapTimingEngineTest.handleStartFinishCrossing_monotonicCrossingEvents_filterRetainsAllAboveStartedAt` | v2 边界下断言更新 + 测试名附加边界标识 |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| `crossingProgress` 浮点边界越界（`segmentsIntersectMeters` 返回 `-1e-16` 或 `1.0000001`） | R1 Scenario 强制 clamp 到 [0.0, 1.0]；新增 `detect_crossingProgressFloatingPointOverflow_isClamped` 测试锁定 |
| `±5ms` 合成契约在 CI 平台差异 / JVM 版本间波动 | 合成测试零 jitter 源（FakeClock + fake replay），差异仅来自 JVM 浮点实现；实测历史已稳定在 `deltaAbs == 0`（见 replay mode 行 173），±5ms 保守留 5x 余量 |
| R5 修订归档 change spec 引入工作流先例 | 明确声明"边界碰撞是 engine-entry-hardening 未写全的 edge case，本 change 在毫秒级语义下补齐"；修订属预期契约扩展，不是返工。归档 spec 文件内交叉引用本 change，审计链完整 |
| trajectory 切分从 O(1) `drop` 退化为 O(N) `filter` 主线程延迟 | 两段式切分 `subList(sampleStartIndex, size).filter(ts 窗口)` 把复杂度锁在 O(本圈帧数)，最坏情况（25Hz × 5 分钟单圈）= O(7500) ≈ 0.5ms；长 session 不因累积帧数退化。替代的纯 `filter(updatedSamples)` 方案会让 25Hz × 40 圈 × 2 分钟累积到 O(120k)，每闭圈 ~2-5ms 主线程延迟，边界态风险 |
| `sampleStartIndex` 保留但语义降级后下游误用 | 字段注释明确"性能索引，不参与归属判定"；R3 Spec 强制 filter 时间窗口作最终裁剪（即使 sampleStartIndex 被未来重构误修改，归属判定不受影响） |
| R4 多门场景下 CrossingEvent 顺序对下游 UI 时间轴的影响 | 顺序语义显式锁定在 Spec：期待门先 + 非期待门按 `orderedSectorGates` 顺序；UI 时间轴若按 `timestampMillis` 排序，插值时刻天然保证顺序一致 |
| R7 A33 断言补齐可能暴露其他未断言的隐性行为 | R7 硬并入本 change（见 § What R7，不提供"拆到 change 3"兜底）；若断言补齐暴露预期外失败，按正常修复流程处理（排查隐性行为根因 + 修正 + 更新断言），不作为撤回 R7 的触发条件 |

### Scope 预检：`sampleStartIndex` / `sampleIndex` 字段全仓 grep 证据

`sampleStartIndex` 全仓 grep（排除 `/build/` 与 Markdown review / plan 文档）：

```
feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/ActiveLap.kt:8         — 字段定义
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:127         — handleStartFinishCrossing 开圈路径构造 ActiveLap
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:133         — 闭圈路径 drop(sampleStartIndex)
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:172         — 闭圈后新 ActiveLap 构造
feature/test/src/test/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreenStateTest.kt:85,114,166,217,222  — 5 处 test fixture
```

**生产代码引用**：仅 `LapTimingEngine.kt` 3 处（均改为 subList 起点）+ `ActiveLap.kt` 字段定义。

**测试代码引用**：仅 `LapDebugExecutionScreenStateTest.kt` 5 处（fixture 构造，保留无需改）。

**UI 代码引用**：零。`LapDebugExecutionScreen.kt`（生产 UI）无引用；只有同名 `State` 测试引用 fixture。

**结论**：`sampleStartIndex` 保留方案零破坏性；生产代码仅 engine 内部改用 subList 起点，UI 与其他 feature 模块不受影响。

`CrossingEvent.sampleIndex` 全仓 grep（只列生产代码）：

```
feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt:9     — 字段定义
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:107         — handleStartFinishCrossing 构造 CrossingEvent
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:200         — handleSectorCrossing 非期待门分支构造
feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:222         — handleSectorCrossing 期待门分支构造
```

**UI / 其他 feature 代码引用**：零。`LapDebugExecutionScreen.kt` 按 `crossingEvents[].timestampMillis` 消费，不访问 `sampleIndex`。

**结论**：`sampleIndex` 语义变更（从"过线时刻帧索引" → "触发检测帧索引"）仅在诊断场景下可见，对现有下游零破坏。

## Alternatives

### 方案 (a)(b)(c)：帧粒度归属调整（原 `fix-lap-timing-gate-and-boundary-contract` 推荐 (b)，整段拒收）

原 proposal 讨论过三选一：
- **(a)** `ActiveLap.sampleStartIndex = updatedSamples.lastIndex + 1` 闭圈帧属上圈
- **(b)** `LapRecord.trajectory = subList(start, size - 1)` 闭圈帧属下圈
- **(c)** 明确契约"闭圈帧同属两圈" + 下游去重

**三方案共同拒收理由**：都是在帧粒度内"换归属"，无法解决 A15 帧粒度精度（±20ms）的根本瓶颈，也不解决 A20 多门同帧的"同 ts 无法分先后"问题。(b) 虽然数学严格非重叠，但 `Lap N+1.trajectory.first.ts` 仍是帧 ts（≠ startedAtMillis），和 (a) 的"下圈 trajectory 首帧 ts 晚于 startedAt 40ms"是同一个帧粒度问题的两面表现。(c) 隐式共享语义让下游更难。

方案 (d) 升级到毫秒级语义后：
- **A15 精度**：`durationMillis` 达到毫秒级
- **A32 归属**：trajectory 时间窗口切分 + filter 边界严格，天然无重叠
- **A20 顺序**：多门同帧时每个门的 `crossingProgress` 不同 → 插值时刻不同 → event.timestampMillis 天然有顺序（不是人为排序强加）

(a)(b)(c) 全是 (d) 的"局部视角简化版"，都在帧粒度假设下成立；(d) 升级了假设本身。

### 方案 (d+E)：CrossingEvent 加 lapBoundarySide 枚举字段

详见决策 2 三维论证；按 YAGNI 拒收。

### 方案：仅做 A20 + A32 不合并 A15

当前 proposal 的 v0 版本（`fix-lap-timing-gate-and-boundary-contract`）方向。**拒绝理由**：A32 的根因是帧粒度精度，单做 A32 只能在帧粒度里选归属（方案 a/b/c），解决不了 A15。一次性合并到 (d) 才是"夯实基础"的深度修复，避免两个 change 反复拆东墙补西墙。A20 同理。

### 方案：仅做 A15 插值，不改 A32 / A20

**拒绝理由**：A15 改 `CrossingEvent.timestampMillis` 语义后，filter 边界立即碰撞（决策 2 已论证），不改 A21 R3 就会引入新 bug；A32 的 trajectory 切分也必须从 `drop(sampleStartIndex)` 改成时间窗口才能与毫秒级语义自洽（否则 `trajectory.first.ts` 仍是帧 ts ≠ startedAtMillis，违反数据一致性）。A15 与 A32 是**同一升级**的两个侧面，不能拆分。

### 方案：合并到 change 3（A36 清理）一起做

**拒绝理由**：A36（`orderedSectorGates` 单源）是纯性能 / 代码味清理，与本 change 毫秒级语义升级无语义耦合。合并扩 scope 违反单一职责，review 成本非线性。保留给 change 3 独立处理。

## Non-goals

- 不做 A36（`orderedSectorGates` 单源 / sector sort 去重） —— change 3 清理战役
- 不做 A39（engine 日志完整坐标 隐私 / 体量） —— F 战役性能 / 日志
- 不改 `GpsSample` 数据类字段 —— 只改消费 GpsSample 的 engine 逻辑
- 不删除 `sampleStartIndex` / `sampleIndex` 字段 —— 保留作性能索引 / 诊断索引，语义降级不动存储结构
- 不承诺真机场景（真实 BLE + 真实 GPS）圈时 ±5ms 可达 —— R6 仅合成契约；真机契约留给未来真机回归战役
- 不接入 UI 层的 "实时 delta 秒差" / "预测圈速" 功能 —— 那些依赖 progressTimeline 基础设施（A51/A52/A53 功能规划），不在本 change 范围
- 不在本 change 内删除 engine-entry-hardening 归档目录或回滚其代码 —— 仅定向修订 R3 Scenario 1 + 对应测试断言，其他内容保持
