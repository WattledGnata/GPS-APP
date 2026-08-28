# 战役 C 二期 · 二轮 review 规格更新驱动清单

> **用途**：把 2026-04-24 二轮 review 结论（5 P2 + 12 P3 + 1 proposal 遗留 P3）转为实施方**可直接执行**的规格文件更新指令。每条指令含 **文件路径 + 精确位置 + 原文 + 改写/新增内容**，逐条照改即可完成 V2 三件套。
>
> **上游 review 文档**：`docs/superpowers/reviews/2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md`
>
> **执行后产出**：proposal.md → V4（含决策 5）；spec.md → V2；tasks.md → V2。之后提交第三轮 review，通过后 `/opsx:apply`。
>
> **操作顺序建议**：A → B → C → D → E。前三节改完再跑 E 节自检，自检通过再提交评审。

---

## 本清单概要

| 节 | 修订对象 | 操作数 | 阻塞 `/opsx:apply` |
|---|---|---|---|
| A | proposal.md | 2 项（修矛盾 + 新增决策 5） | ✅ 是 |
| B | spec.md | 9 项（5 P2 相关 + 部分 P3） | ✅ 是（P2 相关） |
| C | tasks.md | 8 项（4 条新测试 + §8.11 + 部分 P3） | ✅ 是（P2 相关） |
| D | attack-backlog.md | **已由评审方落盘**（头部分层原则 + 第六节未来战役 + 4 条最近动作），实施方无操作 | N/A |
| E | 验证清单 | 5 项 grep / validate / 测试 | 必跑 |

---

## A · proposal.md 修订（2 项）

### A1 · 修 line 384 "降级到 change 3" 与 R7 "硬并入" 矛盾（proposal 遗留 P3）

- **文件**：`openspec/changes/fix-lap-timing-closure-and-precision-contract/proposal.md`
- **位置**：line 384 附近，风险表中包含 "降级到 change 3" 表述的条目
- **动作**：删除或改写

**改写后参考文案**（二选一，实施方判断哪种更贴合原文上下文）：

方案 A（删除）：直接删除整个含 "降级到 change 3" 的风险降级条目。

方案 B（改写，推荐）：
```markdown
| 风险 | 影响 | 应对 |
|---|---|---|
| A33 `qualityFlags` 断言暴露新 bug | 新断言可能 fail 揭示 v1 隐性行为偏差 | **硬并入本 change**（见 R7）。若 fail，按正常修复流程排查根因 + 修正代码 + 更新断言，不提供拆分到后续 change 的降级路径 |
```

**验证**：`grep -n "降级到 change 3" openspec/changes/fix-lap-timing-closure-and-precision-contract/proposal.md` 输出空。

---

### A2 · 新增"决策 5 · 插值模型选型"段落（P2-5a）

- **文件**：`openspec/changes/fix-lap-timing-closure-and-precision-contract/proposal.md`
- **位置**：在现有决策 1-4 段落之后（"关键决策" 章节末尾，风险表之前）
- **动作**：新增整段

**完整新增内容**：

````markdown
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
````

**验证**：`grep -cn "### 决策 5" openspec/changes/fix-lap-timing-closure-and-precision-contract/proposal.md` 输出 1。

---

## B · spec.md 修订（9 项）

### B1 · 新增 header 插值模型约束段（P2-5b）

- **文件**：`openspec/changes/fix-lap-timing-closure-and-precision-contract/specs/lap-timing-engine/spec.md`
- **位置**：line 12-28 之间的 header 区（`> 依赖关系：` 之前或之后，与其他约束段并列）
- **动作**：新增段落

**新增内容**：
```markdown
> **插值模型约束**：本 Requirement 族采用帧间线性（匀速）插值。选型依据与
> 1Hz 弱定位设备升级路径（四阶段）见 proposal 决策 5。**本 change 范围内
> MUST NOT 引入匀加速 / 朝向几何等升级代码路径**，防止扰乱 ±5ms 合成契约
> 前置量级假设。
```

---

### B2 · R2 字段契约分 accepted/rejected 两档（P2-2）

- **文件**：spec.md
- **位置**：line 101-109（R2 字段契约列表第 1 条 `CrossingEvent.timestampMillis`）
- **原文**：
  ```markdown
  - `CrossingEvent.timestampMillis` MUST 等于 `interpolatedMillis`（毫秒级 Long，四舍五入到整数毫秒）
  ```
- **改写后**：
  ```markdown
  - `CrossingEvent.timestampMillis`：
    - 当 `accepted == true` 时 MUST 等于 `interpolatedMillis(prev, current, crossingProgress)`（毫秒级 Long，四舍五入到整数毫秒）
    - 当 `accepted == false` 时 MUST 等于 `currentSample.timestampMillis`（降级到触发帧 ts，作为诊断时间戳；该 event 不作为圈时边界裁剪源，仅进 `session.crossingEvents` 作诊断）
  ```

---

### B3 · R2 Scenario 6 加 GIVEN 前置防 crossingProgress == 1.0 边界（P2-1）

- **文件**：spec.md
- **位置**：line 150-156（`#### Scenario: CrossingEvent.sampleIndex 是触发帧索引（诊断语义）`）
- **原文**：
  ```markdown
  #### Scenario: CrossingEvent.sampleIndex 是触发帧索引（诊断语义）

  - **GIVEN** `session.samples` 已有 N 帧，喂入第 N+1 帧触发过线
  - **WHEN** `processSample` 构造 CrossingEvent
  - **THEN** `event.sampleIndex == updatedSamples.lastIndex == N`（触发 detection 的 currentSample 索引）
  - **AND** `event.timestampMillis` 为插值毫秒，**不等于** `session.samples[event.sampleIndex].timestampMillis`（该帧是 currentSample，ts 为帧 ts）
  - **AND** 下游若按 `samples[event.sampleIndex]` 查询过线时刻帧，会得到 currentSample 而非插值点对应的虚拟帧（诊断语义自洽）
  ```
- **改写后**（加 GIVEN 前置 + 移出说明文本到 Requirement 说明段）：
  ```markdown
  #### Scenario: CrossingEvent.sampleIndex 是触发帧索引（诊断语义，非边界场景）

  - **GIVEN** `session.samples` 已有 N 帧，喂入第 N+1 帧触发过线
  - **AND** `crossingProgress ∈ (0.0, 1.0)` 开区间（即**非边界过线**，过线点严格落在 prev 与 current 之间而非端点）
  - **WHEN** `processSample` 构造 CrossingEvent
  - **THEN** `event.sampleIndex == updatedSamples.lastIndex == N`（触发 detection 的 currentSample 索引）
  - **AND** `event.timestampMillis` 为插值毫秒，**不等于** `session.samples[event.sampleIndex].timestampMillis`（该帧是 currentSample，ts 为帧 ts）
  ```

  **同步**：把原 Scenario 最后一条 AND（"下游若按 samples[event.sampleIndex] 查询..."）移出 Scenario，作为 R2 Requirement 的说明段追加到字段契约列表之后（见 B4）。（解决 P3-6）

---

### B4 · R2 Requirement 说明段追加（P3-6）

- **文件**：spec.md
- **位置**：R2 字段契约列表最后一条（`CrossingEvent.sampleIndex MUST 等于 updatedSamples.lastIndex...`）之后，Scenario 之前
- **动作**：新增说明段落

**新增内容**：
```markdown
**诊断语义说明**：下游若按 `samples[event.sampleIndex]` 查询过线时刻对应帧，得到的是 currentSample（触发 detection 的帧），而非插值毫秒 `event.timestampMillis` 对应的虚拟帧。`event.sampleIndex` 是**帧粒度诊断索引**，`event.timestampMillis` 是**毫秒级过线时刻**，两者互补，不混用。
```

---

### B5 · R2 新增 Scenario "rejected CrossingEvent 降级到触发帧 ts"（P2-2 补 Scenario）

- **文件**：spec.md
- **位置**：R2 Scenario 6 之后（line 156 之后，R2 结束横线 `---` 之前）
- **动作**：新增 1 条 Scenario

**新增内容**：
```markdown
#### Scenario: rejected CrossingEvent.timestampMillis 降级到触发帧 ts

- **GIVEN** 期待门被 `TooSlow` 或 `WrongDirection` rejected，`prev.ts = 200`，`current.ts = 240`
- **AND** `detection.crossingProgress == null`（rejected 分支不填充 crossingProgress）
- **WHEN** `handleSectorCrossing` 构造 rejected event
- **THEN** `event.timestampMillis == 240L`（= `currentSample.timestampMillis`，降级到触发帧 ts）
- **AND** `event.accepted == false`
- **AND** `event.reason` 保留 detection.reason（`TooSlow` 或 `WrongDirection`）
- **AND** 该 event 进入 `session.crossingEvents` 作诊断，不参与 `LapRecord.crossingEvents` 裁剪的时间边界比较
```

---

### B6 · R3 Scenario 2 表达修订（P3-2）

- **文件**：spec.md
- **位置**：line 195-199（`#### Scenario: 第 N+1 圈 ActiveLap.sampleStartIndex 指向闭圈帧`）
- **原文**最后一条 AND：
  ```markdown
  - **AND** 喂入下一帧时，第 N+1 圈 trajectory 从闭圈帧开始累积（`trajectory.first.ts >= startedAtMillis_{N+1}`，不排除等于 `startedAtMillis_{N+1}` 对应帧）
  ```
- **改写后**：
  ```markdown
  - **AND** 喂入下一帧时，第 N+1 圈 trajectory 从闭圈帧开始累积（`trajectory.first.ts >= startedAtMillis_{N+1}`）
  - **AND** 允许（但不保证）存在某帧 ts **数值上恰好等于** `startedAtMillis_{N+1}`；若存在，该帧归下圈（基于 R3 filter `ts >= startedAt` 下界含等号）
  ```

---

### B7 · R3 Scenario 3 GIVEN 补"无幻帧"前置（P3-8）

- **文件**：spec.md
- **位置**：line 202-206（`#### Scenario: samples.size 等于所有圈 trajectory 之和 + activeLap 实时段`）
- **原文** GIVEN 行：
  ```markdown
  - **GIVEN** session 跑完 2 个完整圈 + 第 3 圈部分帧（尚未闭圈）
  ```
- **改写后**：
  ```markdown
  - **GIVEN** session 跑完 2 个完整圈 + 第 3 圈部分帧（尚未闭圈）
  - **AND** session 无 A38 理论越界态（所有 `samples[sampleStartIndex..]` 帧 ts 均 ≥ 对应圈 `startedAt`；幻帧场景另见 Scenario 4）
  ```

---

### B8 · R4 追加顺序契约补"不保证 ts 单调"（P3-9）

- **文件**：spec.md
- **位置**：line 243-245（R4 追加顺序契约段落）
- **原文**：
  ```markdown
  `CrossingEvent` 追加顺序 MUST 为：期待门先 + 非期待门按 `orderedSectorGates` 的 `sequenceIndex`
  从小到大顺序。每个 CrossingEvent.timestampMillis 用该门自己 detection 的插值毫秒
  （不同门的 `crossingProgress` 可不同）。
  ```
- **改写后**（追加一句）：
  ```markdown
  `CrossingEvent` 追加顺序 MUST 为：期待门先 + 非期待门按 `orderedSectorGates` 的 `sequenceIndex`
  从小到大顺序。每个 CrossingEvent.timestampMillis 用该门自己 detection 的插值毫秒
  （不同门的 `crossingProgress` 可不同）。**该追加顺序 MUST NOT 被解读为 `timestampMillis` 时间单调**：
  不同门的 crossingProgress 可能使插值毫秒先后不定；下游若需时间序消费，应在消费侧
  自行 `sortedBy { it.timestampMillis }`。
  ```

---

### B9 · R4 Scenario 5 "反序" 指代改为数据顺序（P2-3）

- **文件**：spec.md
- **位置**：line 290-296（`#### Scenario: 多个非期待门按 orderedSectorGates 顺序追加`）
- **原文**最后一条 AND：
  ```markdown
  - **AND** 若 fixture 构造 `(prev, current)` 同时过 `S3, S2`（反序）仍应得 `[S2, S3]` 顺序（由 engine 遍历顺序保证确定性）
  ```
- **改写后**：
  ```markdown
  - **AND** 即使 `track.sectorGates` 在数据层面构造为 `[S3, S2, S1]`（反 `sequenceIndex` 顺序），engine 内部 `sortedBy { sequenceIndex }` 后输出仍应 `[S2, S3]`（由 engine 排序保证确定性，与数据源顺序解耦）
  ```

---

### B10 · R5 MODIFIED 规则段补"代码层禁 / 测试层对照"分层（P3-10）

- **文件**：spec.md
- **位置**：line 349-352（R5 MODIFIED 规则段落，`MUST NOT 使用 ...` 行末尾）
- **原文**：
  ```markdown
  实现上 MUST 使用 `filter { it.timestampMillis > activeLap.startedAtMillis }`，
  MUST NOT 使用 `filter { it.timestampMillis >= activeLap.startedAtMillis }` 或 `dropWhile`。
  ```
- **改写后**（追加分层声明）：
  ```markdown
  实现上 MUST 使用 `filter { it.timestampMillis > activeLap.startedAtMillis }`，
  MUST NOT 使用 `filter { it.timestampMillis >= activeLap.startedAtMillis }` 或 `dropWhile`。

  **分层声明**：
  - **代码层**（`LapTimingEngine.handleStartFinishCrossing` 生产路径）：禁用 `dropWhile`
  - **测试层**：允许用 `dropWhile` 作对偶等价对照（见 MODIFIED Scenario 4），测试侧不计入生产代码路径
  ```

---

### B11 · R3 / R5 边界规则对比表（P3-7）

- **文件**：spec.md
- **位置**：R5 MODIFIED 规则段结束之后、R5 Scenario 之前（作为 R5 MODIFIED 的补充说明）
- **动作**：新增对比表

**新增内容**：
```markdown
**R3 trajectory 与 R5 crossingEvents 的 filter 边界规则对比**：

| 数据流 | 下界 | 上界 | 语义依据 |
|---|---|---|---|
| `LapRecord.trajectory`（R3） | `ts >= startedAt`（含等号） | `ts < finishedAt`（严格小于） | 开圈帧含边界 / 闭圈帧归下圈 |
| `LapRecord.crossingEvents`（R5） | `ts > startedAt`（严格大于） | 无上界裁剪 | 开圈 event 归前一圈（避免边界 event 同属两圈） |

两者下界规则**刻意不同**（`>=` vs `>`），是因为 trajectory 是采样帧序列（物理时刻），crossingEvents 是过线事件序列（插值时刻），"同一时刻" 的归属语义本质不同：

- trajectory 含开圈帧作为"本圈的物理起始采样"，不含闭圈帧因其已是下圈起点
- crossingEvents 不含开圈 event 因其是前一圈闭圈 event（同一 CrossingEvent 对象），保留在前一圈避免跨圈重复

实施方 MUST NOT 统一两处边界规则。
```

---

### B12 · R1 Scenario 3 GIVEN 承认降级路径（P3-1）

- **文件**：spec.md
- **位置**：line 68-74（`#### Scenario: 浮点边界越界被 clamp 到 [0.0, 1.0]`）
- **原文** GIVEN：
  ```markdown
  - **GIVEN** 构造极端几何使 `segmentsIntersectMeters` 内部算出 `t = 1.0000001`
    （或通过单元测试直接注入越界 t 值）
  ```
- **改写后**：
  ```markdown
  - **GIVEN** 通过以下任一方式让 `detect` 路径获得一个越界 `t` 值：
    - (a) 构造极端几何 fixture 触发 `segmentsIntersectMeters` 内部浮点越界（若当前 JVM / CPU 可复现）
    - (b) 把 `segmentsIntersectMeters` 标注为 `@VisibleForTesting internal`，测试内直接注入越界 t（推荐降级路径，省去浮点复现成本）
  - **AND** 无论 (a) 或 (b)，断言核心均为"越界 t 经 `coerceIn(0.0, 1.0)` clamp 后 crossingProgress 落在 [0.0, 1.0]"
  ```

---

## C · tasks.md 修订（8 项）

### C1 · tasks §1.1 明确 `segmentsIntersectMeters` visibility（P3-5）

- **文件**：`openspec/changes/fix-lap-timing-closure-and-precision-contract/tasks.md`
- **位置**：§1.1（line 22-24 附近）
- **原文**：
  ```markdown
  - [ ] 1.1 **代码改动**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt` 的 `segmentsIntersectMeters` 返回值从 `Boolean` 改为 `Double?`：
      - 相交时返回 `t` 参数（原函数内部已算，v1 只丢弃返回 Boolean）
      - 不相交（`denominator == 0` 或 `t !in 0..1` 或 `u !in 0..1`）返回 null
  ```
- **追加一条子项**：
  ```markdown
      - **Visibility**：保持 `private` 为优，除非 §1.8（R1 S5）测试需直接调用 —— 若需，改为 `@VisibleForTesting internal` 并加 KDoc "仅测试可见"
  ```

---

### C2 · tasks §1.6 推荐降级路径（P3-1 tasks 侧）

- **文件**：tasks.md
- **位置**：§1.6（line 33 附近）
- **原文**：
  ```markdown
  - [ ] 1.6 **新增测试** `GateCrossingDetectorTest.detect_floatingPointOverflow_crossingProgressIsClamped`（R1 Scenario 3）：构造几何让 `segmentsIntersectMeters` 内部算出轻微越界 t（可能需反射或极端 fixture），断言 `crossingProgress == 1.0` 或 `0.0`。
  ```
- **改写后**：
  ```markdown
  - [ ] 1.6 **新增测试** `GateCrossingDetectorTest.detect_floatingPointOverflow_crossingProgressIsClamped`（R1 Scenario 3）：推荐走 §C1 的 `@VisibleForTesting internal` 路径直接注入越界 t（比如 `1.0000001` 与 `-1e-16`），省去几何复现成本；断言 `crossingProgress == 1.0` 或 `0.0`。
  ```

---

### C3 · tasks §1.7 明确 rejected 三路径参数化（P3-11）

- **文件**：tasks.md
- **位置**：§1.7（line 34 附近）
- **原文**：
  ```markdown
  - [ ] 1.7 **新增测试** `GateCrossingDetectorTest.detect_rejectedCrossing_crossingProgressIsNull`（R1 Scenario 4）：构造 `NoIntersection` / `WrongDirection` / `TooSlow` 三种 rejected 场景，断言 `crossingProgress == null`。
  ```
- **改写后**：
  ```markdown
  - [ ] 1.7 **新增测试** `GateCrossingDetectorTest.detect_rejectedCrossing_crossingProgressIsNull`（R1 Scenario 4）：单测试方法内通过 JUnit4 参数化或 `assertAll` 覆盖 `NoIntersection`（prev/current 同侧）/ `WrongDirection`（方向投影反）/ `TooSlow`（directionalSpeedMps 低于 gate.minDirectionalSpeedMps）三种 rejected 场景，每种分别断言 `detection.accepted == false` 且 `detection.crossingProgress == null`。
  ```

---

### C4 · tasks §1 追加 §1.8 R1 S5 测试（P2-4 a）

- **文件**：tasks.md
- **位置**：§1.7 之后，§2 之前
- **动作**：新增 1 条 task

**新增内容**：
```markdown
- [ ] 1.8 **新增测试** `GateCrossingDetectorTest.segmentsIntersectMeters_returnsDoubleNullable`（R1 Scenario 5）：直接针对 `segmentsIntersectMeters`（visibility 见 §1.1）断言：
    - 线段几何相交时返回的 `Double?` 非 null，值为 `t ∈ [0.0, 1.0]`（线段上相交参数）
    - 不相交时返回 null
    - `denominator == 0`（平行或共线）时返回 null（保留 v1 防御性语义）
    - 至少 4 个断言子场景：相交正向 / 相交反向（方向不在本函数处理，仅几何） / 不相交同侧 / denominator == 0
```

---

### C5 · tasks §2 追加 §2.12 R2 S3 对称等价测试（P2-4 b）

- **文件**：tasks.md
- **位置**：§2.11 之后，§3 之前
- **动作**：新增 1 条 task

**新增内容**：
```markdown
- [ ] 2.12 **新增测试** `LapTimingEngineTest.processSample_symmetricBothCrossings_durationMillisEquivalentToV1FrameLevel`（R2 Scenario 3）：构造开圈（prev.ts=200, current.ts=240, t=0.5）+ 对称闭圈（prev.ts=10_200, current.ts=10_240, t=0.5）场景，断言：
    - `lap.startedAtMillis == 220L`
    - `lap.finishedAtMillis == 10_220L`
    - `lap.durationMillis == 10_000L`
    - **AND** 与 v1 帧粒度 `current.ts - frame_startedAt = 10_240 - 240 = 10_000L` 数值等价（锁定"对称场景下 v1/v2 数值恰好相同、不硬区分"的意图）
```

---

### C6 · tasks §3 追加 §3.7 R3 S4 filter 兜底测试（P2-4 c，**最重要**）

- **文件**：tasks.md
- **位置**：§3.6 之后，§4 之前
- **动作**：新增 1 条 task

**新增内容**：
```markdown
- [ ] 3.7 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_subListStartIndexOutOfWindow_filterExcludesOutOfBoundFrames`（R3 Scenario 4，防御性测试）：通过测试脚手架直接构造 `ActiveLap(sampleStartIndex = 5, startedAtMillis = 220L)` 且 `session.samples[5].timestampMillis = 180L`（subList 起点指向比 startedAt 更早的帧，模拟 A38 理论越界态）；喂入下一过线帧触发闭圈；断言：
    - `lap.trajectory.none { it.timestampMillis < 220L }`（filter 兜底排除越界帧）
    - `lap.trajectory.first.timestampMillis >= 220L`
    - 该测试 MUST 直接构造 ActiveLap 而非通过 engine 主流程（A38 守卫在主流程中会阻止此态产生；本测试锁定的是 filter 的防御性正确性）
```

---

### C7 · tasks §4 追加 §4.9 rejected timestampMillis 降级测试（P2-2 tasks 侧）

- **文件**：tasks.md
- **位置**：§4.8 之后，§5 之前
- **动作**：新增 1 条 task

**新增内容**：
```markdown
- [ ] 4.9 **新增测试** `LapTimingEngineTest.handleSectorCrossing_expectedGateRejected_eventTimestampFallbackToCurrentSample`（对应 B5 新增 Scenario）：构造期待门被 `TooSlow` 或 `WrongDirection` rejected，`prev.ts = 200, current.ts = 240`；断言：
    - `session.crossingEvents.last.accepted == false`
    - `session.crossingEvents.last.timestampMillis == 240L`（= currentSample.ts，降级到触发帧 ts）
    - `session.crossingEvents.last.reason` == 原 detection.reason
```

---

### C8 · tasks §4.8 "触发顺序" 改为数据顺序（P2-3 tasks 侧）

- **文件**：tasks.md
- **位置**：§4.8（line 154 附近）
- **原文**：
  ```markdown
  - [ ] 4.8 **新增测试** `LapTimingEngineTest.handleSectorCrossing_multipleNonExpectedAccepted_sortedBySequenceIndex`（R4 Scenario 5）：构造 `track.sectorGates = [S1, S2, S3]` + 期待门 S1 + `(prev, current)` 几何上同时过 S2/S3，**即使 fixture 构造触发顺序为 S3→S2**，engine 输出仍应 `[S2, S3]`。
  ```
- **改写后**：
  ```markdown
  - [ ] 4.8 **新增测试** `LapTimingEngineTest.handleSectorCrossing_multipleNonExpectedAccepted_sortedBySequenceIndex`（R4 Scenario 5）：构造 `track.sectorGates` 在**数据层面**为 `[S3, S2, S1]`（反 `sequenceIndex` 顺序）+ 期待门 `S1` + `(prev, current)` 几何上同时过 S2/S3；engine 内部 `sortedBy { sequenceIndex }` 后输出 crossingEvents 顺序仍应为 `[S1 期待门, S2, S3]`；断言 engine 排序确定性与数据源顺序解耦。
  ```

---

### C9 · tasks §5 追加 §5.6 R5 S3 非单调测试（P2-4 d）

- **文件**：tasks.md
- **位置**：§5.5 之后，§6 之前
- **动作**：新增 1 条 task

**新增内容**：
```markdown
- [ ] 5.6 **新增测试** `LapTimingEngineTest.handleStartFinishCrossing_nonMonotonicEvents_filterStrictlyGreaterRejectsHistorical`（R5 MODIFIED Scenario 3）：构造 `session.crossingEvents = [event(ts=100), event(ts=250), event(ts=150), event(ts=400)]`（ts=150 作为历史事件夹在后面，序列非单调）+ `activeLap.startedAtMillis = 200L`；触发闭圈构造 LapRecord；断言：
    - `lap.crossingEvents == [event(ts=250), event(ts=400)]`（ts=150 < 200 被拒，ts=100 < 200 被拒）
    - **锁定"非单调 + 严格 `>`" 组合行为**，与现有 §5.5 边界碰撞场景互补
```

---

### C10 · tasks §8 追加 §8.11 speed 字段 grep 卡口（P2-5c，**最重要**）

- **文件**：tasks.md
- **位置**：§8.10 之后，§9 之前
- **动作**：新增 1 条 task

**新增内容**：
````markdown
- [ ] 8.11 **插值模型范围边界 grep 审计**（proposal 决策 5 保护）：

    ```bash
    # 本 change 不允许 LapTimingEngine 内出现 prev/current.speed 用于插值的
    # 代码路径（防止提前落进 1Hz 升级路径，扰乱 ±5ms 契约前置假设）
    grep -nE "previousSample\.speed|currentSample\.speed" \
        feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
    ```

    期望输出：**不含**新增的"用于插值"语义引用。
    （`speed` 字段在其他模块或 v1 已有路径的使用不在本 grep 范围；若 v1 已有对
    `previousSample.speed` / `currentSample.speed` 的引用，人工确认其非插值用
    途并在 commit message 声明即可。）
````

---

### C11 · tasks §8.6 拆 core:domain 强制 / core:bluetooth 软检（P3-12）

- **文件**：tasks.md
- **位置**：§8.6（line 190 附近）
- **原文**：
  ```markdown
  - [ ] 8.6 `./gradlew :core:domain:test :core:bluetooth:testDebugUnitTest` 全绿（跨模块零回归）。
  ```
- **改写后**（拆为 8.6a / 8.6b）：
  ```markdown
  - [ ] 8.6a `./gradlew :core:domain:test` 全绿（跨模块零回归，**强制**）。
  - [ ] 8.6b `./gradlew :core:bluetooth:testDebugUnitTest` 全绿（**战役 C / G 并行期间为软检**：战役 G 归档前此项状态仅供参考，不纳入本 change 合流卡口；战役 G 完整归档后此项转为强制，由下一 change / 尾巴战役跟进复核）。
  ```

---

### C12 · tasks §9 commit 3 message 去 A21 回溯歧义（P3-13）

- **文件**：tasks.md
- **位置**：§9 commit 3 建议消息（line 227 附近）
- **原文**：
  ```markdown
     - 建议消息：`feat(laptiming): 战役 C 判圈契约（R4/R5/R7 / A20/A21-修订/A33）多门遍历 + filter 严格 > + qualityFlags 断言`
  ```
- **改写后**：
  ```markdown
     - 建议消息：`feat(laptiming): 战役 C 判圈契约（R4/R5/R7 / A20/A33）多门遍历 + filter 严格 > + qualityFlags 断言`
     - **注**：commit message 不回溯 A21 状态。本 change R5 MODIFIED 段覆盖 `fix-lap-timing-engine-entry-hardening` R3 Scenario 1，是对已归档 spec 的跨 change 修订（语义升级），A21 的 ✅ resolved 归档状态不变；backlog 仅在 A20/A33 条目更新"最近动作"。
  ```

---

### C13 · tasks §8.9 加 MODIFIED dry-run 复核命令（P3-4）

- **文件**：tasks.md
- **位置**：§8.9（line 205-207 附近）
- **原文**：
  ```markdown
  - [ ] 8.9 **归档 spec 状态审计**：
      - engine-entry-hardening 若仍在 active，记录其状态（`openspec-chinese list` 确认）
      - 本 change spec MODIFIED 段覆盖语义已由 CLI dry-run 验证支持，无需归档时序前置
  ```
- **改写后**（追加复核命令）：
  ````markdown
  - [ ] 8.9 **归档 spec 状态审计 + MODIFIED 段 dry-run 复核**：
      - `$(npm config get prefix)/bin/openspec-chinese list` 确认 engine-entry-hardening 状态
      - 复核 MODIFIED 段 CLI 支持：
        ```bash
        $(npm config get prefix)/bin/openspec-chinese validate \
            fix-lap-timing-closure-and-precision-contract --strict 2>&1 \
            | tee /tmp/modified-validate.log
        grep -iE "MODIFIED|R5|filter 边界" /tmp/modified-validate.log
        ```
      - 期望输出：validate 通过 + MODIFIED 段被 CLI 正确识别（不报 unknown segment 错误）
      - 若有异常，回退到决策 4 方案 (a) 路径 —— 直接 Edit `openspec/changes/archive/fix-lap-timing-engine-entry-hardening/specs/lap-timing-engine/spec.md` 修订 R3 Scenario 1
  ````

---

## D · attack-backlog.md（已落盘，无实施方动作）

评审方已于 2026-04-24 完成以下 backlog 更新，实施方**无需改动**，仅需知晓：

1. **头部新增"文档分层原则"** · 明确 backlog 与日期前缀 review 文档的职责分层
2. **A15 / A20 / A32 / A33 四条条目各追加一行"最近动作"** · 指向 review 文档
3. **新增第六节 🔮 未来战役预留** · 3 条占位（`fix-gps-position-denoise` / `fix-laptime-skip-frame-precision` / `fix-laptime-low-freq-device-support`），每条含触发条件 / 量级依据 / 预计收益 / 升级路径 / 依赖关系 / 留档依据
4. **维护说明追加第六节管理约定**

实施方只需在第三轮 review 通过后，按 tasks §8.10 常规流程把 A15/A20/A32/A33 状态从 🔴 迁到 🟡 → 🟢（附 commit hash）。

---

## E · 改完后自检清单（合并现有 §8 + 本轮新增）

按顺序跑，全绿才提交第三轮 review。

### E1 · 关键词 grep（扩展现有 §8.7）

```bash
# 现有 §8.7 关键词
for kw in "整体淘汰" "5+ 条" "粗估" "降级方案" "降级到 change 3" "可选收紧"; do
    echo "--- $kw ---"
    grep -n "$kw" openspec/changes/fix-lap-timing-closure-and-precision-contract/{proposal.md,tasks.md,specs/lap-timing-engine/spec.md} || echo "(无残留)"
done
```

**期望**：全部 "(无残留)"。特别关注 `"降级到 change 3"` 在 proposal.md line 384 附近必须被 A1 清除。

### E2 · 字段语义 grep（现有 §8.8）

```bash
grep -n "timestampMillis = currentSample\.timestampMillis\|startedAtMillis = currentSample\.timestampMillis\|finishedAtMillis = currentSample\.timestampMillis\|crossedAtMillis = currentSample\.timestampMillis" feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
```

**期望**：空输出（所有圈时字段从 `interpolatedMillis` 派生；**例外**：B2 / C7 定义的 rejected 分支 `timestampMillis = currentSample.timestampMillis` 合法保留）。

### E3 · 插值模型边界 grep（本轮新增 §8.11）

```bash
grep -nE "previousSample\.speed|currentSample\.speed" \
    feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
```

**期望**：**不含** "用于插值" 语义的新增引用。

### E4 · OpenSpec CLI validate（现有 §8.1 + §8.9 dry-run 复核）

```bash
$(npm config get prefix)/bin/openspec-chinese validate \
    fix-lap-timing-closure-and-precision-contract --strict 2>&1 \
    | tee /tmp/validate-v2.log
echo "exit=$?"
grep -iE "MODIFIED|R5" /tmp/validate-v2.log
```

**期望**：exit=0 + MODIFIED 段被正确识别。

### E5 · Scenario → task 映射自检（本轮新增）

```bash
# 手工核对每个 spec Scenario 都有对应 tasks 项
for scenario_marker in "R1 Scenario 1" "R1 Scenario 2" "R1 Scenario 3" "R1 Scenario 4" "R1 Scenario 5" \
                       "R2 Scenario 1" "R2 Scenario 2" "R2 Scenario 3" "R2 Scenario 4" "R2 Scenario 5" "R2 Scenario 6" "rejected CrossingEvent" \
                       "R3 Scenario 1" "R3 Scenario 2" "R3 Scenario 3" "R3 Scenario 4" "R3 Scenario 5" \
                       "R4 Scenario 1" "R4 Scenario 2" "R4 Scenario 3" "R4 Scenario 4" "R4 Scenario 5" \
                       "R5 MODIFIED Scenario 1" "R5 MODIFIED Scenario 2" "R5 MODIFIED Scenario 3" "R5 MODIFIED Scenario 4" \
                       "R6 Scenario 1" "R6 Scenario 2" "R7 Scenario 1"; do
    echo "--- $scenario_marker ---"
    grep -n "$scenario_marker" openspec/changes/fix-lap-timing-closure-and-precision-contract/tasks.md || echo "(tasks 无对应 task，请补)"
done
```

**期望**：每个 Scenario 标记都有对应 task（至少 1 行输出）。

### E6 · 测试全绿（现有 §8.2 / §8.3 / §8.4 / §8.5 / §8.6a）

代码改完后跑：

```bash
./gradlew :feature:test:testDebugUnitTest --tests "*GateCrossingDetectorTest*"
./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"
./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"
./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"
./gradlew :core:domain:test
```

**期望**：全绿。`:core:bluetooth:testDebugUnitTest` 战役 G 并行期间软检（见 C11）。

---

## F · 提交第三轮 review 前的交付清单

实施方按 A-E 改完并自检通过后，提交以下 artifact 给评审方：

- [ ] proposal.md V4（含决策 5、line 384 矛盾已修）
- [ ] spec.md V2（B1-B12 共 9 项落地；Scenario 数从 28 → 29）
- [ ] tasks.md V2（C1-C13 共 8 项新 task / 5 项既有 task 改写；测试任务数从原有基数 +4 = +5 含 C7）
- [ ] E1-E5 自检输出（贴到第三轮 review PR / 消息体）
- [ ] 第三轮 review 请求（评审方过了即 `/opsx:apply`）

**不需要提交**：attack-backlog.md 更新（评审方已落盘）。

---

## G · 本清单自身的生命周期

- 本文件由**评审方**于 2026-04-24 生成，作为二轮 review 结论到三件套规格更新的**驱动桥**
- 实施方按清单改完后，本文件与 proposal/spec/tasks V2 一起进入第三轮 review
- 第三轮通过后，`/opsx:apply` 归档 change；本文件可一同随 change 目录归档（作为修订溯源），或独立保留在 `docs/superpowers/reviews/` 作历史
- 若未来有三轮及以上，本文件命名改为 `review-v3-patches.md` / `review-v4-patches.md` 逐版追加

---

**本清单统计**：13 文件位置修订（2 proposal + 12 spec + 8 tasks = 22 项修订；其中 5 P2 相关 + 12 P3 + 1 proposal 遗留 P3），覆盖 2026-04-24 二轮 review 全部结论。
