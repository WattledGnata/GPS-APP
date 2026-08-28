# 战役 C 二期 `fix-lap-timing-closure-and-precision-contract` · spec V1 + tasks V1 第二轮 review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-lap-timing-closure-and-precision-contract/specs/lap-timing-engine/spec.md` V1（401 行，28 Scenario）
  - `openspec/changes/fix-lap-timing-closure-and-precision-contract/tasks.md` V1（235 行，9 Section）
- **覆盖攻击点**：A15（穿线插值）+ A20（多门同帧）+ A32（闭圈帧归属）+ A33（qualityFlags 断言）
- **评审方**：Claude（haozhang93 session）
- **实施方**：另一 AI session
- **轮次**：第二轮结构化 review（第一轮 review 于 proposal 阶段，已迭代至 V3）
- **结论**：🟡 **有条件准予进入 `/opsx:apply`**（5 P2 + 1 proposal 遗留 P3 全闭合为前置）

---

## 0. 结论摘要

| 维度 | 评级 | 结论 |
|---|---|---|
| Requirement 覆盖 | 🟢 | 7 R × 28 Scenario 分布与 proposal 决策 1:1 对应，无遗漏 |
| 硬区分 v1/v2 | 🟢 | R2 S4 / R3 S3 / R5 S2 均明确挑出 v1 具体数值，测试可断言 |
| 对偶防退化 | 🟢 | R5 S4 `filter >` 与 `dropWhile <=` 双向校验，本 change 亮点 |
| 审计自动化 | 🟢 | tasks 8.7 / 8.8 / 8.9 三层卡口把"自洽性"从人工变工具 |
| Scenario 精度 | 🟡 | R2 S6 在 `crossingProgress == 1.0` 时断言 FAIL；R4 S5 "反序" 指代不明 |
| 契约自洽 | 🟡 | R2 契约要求 `MUST 等于 interpolatedMillis`，但 tasks rejected 分支降级 `currentSample.ts`，spec 未授权此降级 |
| tasks / Scenario 映射 | 🔴 | 4 个 Scenario 在 tasks 无对应测试任务 |
| 插值模型决策留档 | 🔴 | 40ms 下匀速够用（<0.1ms）；1Hz 弱定位下偏差质变（50-200ms），未留档 |

### 趋势

| 轮次 | P1 | P2 | P3 | 收敛方向 |
|---|---|---|---|---|
| proposal V1 → V2 | 0 | 6 | 0 | |
| proposal V2 → V3 | 0 | 6 | 0 | |
| proposal V3 → 等 V4 | 0 | 0 | 1 | 🟢 |
| **spec V1 + tasks V1 本轮** | **0** | **5** | **12** | 🟢🟢 |

P2 从 6 条降到 5 条（含 P3-3 升级进来的插值模型留档），均为具体 Scenario / 契约级精度问题或决策留档缺失，**无结构性问题**。符合"proposal 定完结构后、spec/tasks 是精度调校"的自然收敛。

---

## 1. 🔴 P2（必修，阻塞 `/opsx:apply`）

### P2-1 · R2 Scenario 6 边界场景断言 FAIL

- **位置**：spec.md:150-156（Scenario: CrossingEvent.sampleIndex 是触发帧索引）
- **问题**：Scenario 第 4 条 AND 硬断言
  > `event.timestampMillis` 为插值毫秒，**不等于** `session.samples[event.sampleIndex].timestampMillis`

  当 `crossingProgress == 1.0`（边界 clamp 或对称于 current 一侧几何），`interpolatedMillis = prev.ts + 1.0 × (current.ts - prev.ts) = current.ts`，而 `session.samples[event.sampleIndex].timestampMillis` 就是 currentSample.ts，两者**恰好相等**，硬 `!=` FAIL。
- **修订建议**：Scenario 加 GIVEN 前置 `crossingProgress != 1.0`（推荐 0.5 / 0.75），或断言降级为"可能不等"并锁定 `crossingProgress ∈ [0, 1)` 开区间场景。
- **推荐方案**：前者，硬断言保留。

---

### P2-2 · R2 CrossingEvent.timestampMillis 在 rejected 分支语义缺失

- **位置**：spec.md:101-109（R2 字段契约）vs tasks.md:99-102（§4.1 rejected 分支代码）
- **问题**：
  - spec R2 字段契约第 1 条：`CrossingEvent.timestampMillis MUST 等于 interpolatedMillis`（无 accepted/rejected 区分）
  - tasks §4.1 第 99-102 行实际代码：
    ```kotlin
    timestampMillis = if (expectedDetection.accepted)
        interpolatedMillis(previousSample, currentSample, expectedDetection.crossingProgress!!)
    else
        currentSample.timestampMillis,  // rejected 分支仍用帧 ts（无 crossingProgress）
    ```
  - rejected 分支 `crossingProgress == null`，**无法**插值；tasks 降级到 `currentSample.timestampMillis`，但 spec 未授权此降级。
- **后果**：spec V1 严格 dry-run 下，任何 rejected 分支的 CrossingEvent 都违反 R2 契约；Review 方 / 后续核销方若只读 spec 不看 tasks，会要求 rejected 也插值 —— 但 `detection.crossingProgress!!` NPE。
- **修订建议**：spec R2 字段契约第 1 条补一层条件：
  > `CrossingEvent.timestampMillis`：
  > - 当 `accepted == true` 时 MUST 等于 `interpolatedMillis(prev, current, crossingProgress)`
  > - 当 `accepted == false` 时 MUST 等于 `currentSample.timestampMillis`（降级到触发帧 ts，作为诊断时间戳；该 event 不作为圈时边界裁剪源，仅进 `session.crossingEvents` 作诊断）

  新增 1 条 Scenario：
  > #### Scenario: rejected CrossingEvent.timestampMillis 降级到触发帧 ts
  > - **GIVEN** 期待门被 TooSlow rejected，`prev.ts=200, current.ts=240`
  > - **WHEN** `handleSectorCrossing` 构造 rejected event
  > - **THEN** `event.timestampMillis == 240L`（= currentSample.ts）
  > - **AND** `event.accepted == false`

  对应 tasks 追加 1 条测试任务（放到 §4.X 或新增 §4.9）。

---

### P2-3 · R4 Scenario 5 "反序" 指代不明

- **位置**：spec.md:290-296（Scenario: 多个非期待门按 orderedSectorGates 顺序追加）+ tasks.md:154（§4.8）
- **问题**：最后一条 AND
  > 若 fixture 构造 `(prev, current)` 同时过 `S3, S2`（反序）仍应得 `[S2, S3]` 顺序

  "同时过 S3, S2" 和 "同时过 S2, S3" 在几何上是**同一件事**（两条门都过，无时间先后的几何概念），"反序" 指什么？tasks §4.8 "触发顺序" 同样模糊。engine 内部按 `track.sectorGates.sortedBy { sequenceIndex }` 遍历，**数据源头**的顺序才是变量。
- **修订建议**：spec Scenario 5 最后一条 AND 改为：
  > **AND** 即使 `track.sectorGates` 在数据层面构造为 `[S3, S2, S1]`（反 sequenceIndex 顺序），engine 内部 `sortedBy { sequenceIndex }` 后输出仍应 `[S2, S3]`（由 engine 排序保证确定性，与数据源顺序解耦）

  tasks §4.8 同步改为 "即使 `track.sectorGates` 数据顺序为 `[S3, S2, S1]`"。

---

### P2-4 · tasks 遗漏 4 个 Scenario 测试任务

- **位置**：tasks §1 / §2 / §3 / §5
- **问题**：按 "Scenario N → tasks N.X" 逐条映射后，4 个 spec Scenario 在 tasks 无对应测试：

| Req | Scenario | spec 行号 | 追加 task |
|---|---|---|---|
| R1 S5 | `segmentsIntersectMeters` 返回 Double? 语义 | spec.md:84-89 | §1.8 新增 |
| R2 S3 | 对称闭圈 duration 与 v1 数值等价 | spec.md:125-131 | §2.12 新增 |
| R3 S4 | filter 兜底排除 subList 起点越界帧 | spec.md:208-213 | §3.7 新增 |
| R5 S3 | 非单调 crossingEvents 拒收 | spec.md:385-392 | §5.6 新增 |

- **严重性**：
  - **R3 S4 是防御性 Scenario**：filter 兜底是对 A38 理论越界态的保险，必须有测试锁定，否则兜底代码 = 死码风险
  - **R1 S5** 是 `segmentsIntersectMeters` 本身的语义契约（不经过 `detect`），属底层 API 断言；该函数从 `Boolean` 破坏性改到 `Double?`，无直接测试是裸奔改 API
  - **R5 S3** 锁定 "非单调 + 严格 `>`" 组合行为，tasks §5 只覆盖到 S1（正向）、S2（边界碰撞）、S4（对偶），非单调场景遗漏
  - **R2 S3** 对称场景不硬区分的意图声明（等价对照），若遗漏测试，未来有人质疑 "对称场景也要硬区分吗" 无回答依据
- **修订建议**：tasks 追加 4 个测试任务：
  ```
  §1.8  GateCrossingDetectorTest.segmentsIntersectMeters_returnsDoubleNullable
  §2.12 LapTimingEngineTest.processSample_symmetricBothCrossings_durationMillisEquivalentToV1FrameLevel
  §3.7  LapTimingEngineTest.handleStartFinishCrossing_subListStartIndexOutOfWindow_filterExcludesOutOfBoundFrames
  §5.6  LapTimingEngineTest.handleStartFinishCrossing_nonMonotonicEvents_filterStrictlyGreaterRejectsHistorical
  ```

---

### P2-5 · 插值模型决策留档（量级分析 + 1Hz 升级路径）

- **位置**：proposal.md / spec.md header / tasks.md §8 / attack-backlog.md 四处同步修订
- **问题背景**：
  - 本 change 采用帧间线性（匀速）插值 `interpolatedMillis = prev.ts + t × (current.ts - prev.ts)`，隐含 "prev→current 期间匀速运动" 假设
  - 40ms 帧距下匀速/匀加速偏差 **< 0.1ms**，远低于 ±5ms 合成契约，本 change 范围内可忽略
  - **1Hz 弱定位设备场景下质变**：Δt² 项主导，偏差放大到 50-200ms，匀速假设失效
- **量级分析表**：

| 偏差源 | 40ms 帧距 | 1Hz 帧距 | 40ms 下影响 |
|---|---|---|---|
| 匀速 vs 匀加速（a=5 m/s²，中等） | < 0.1 ms | 50 ms | 可忽略 |
| 匀速 vs 匀加速（a=20 m/s²，2G 刹车） | < 0.1 ms | 200 ms | 可忽略 |
| 弦长 vs 弧长（弯道 R=50m, v=30m/s） | ~3 ms | 秒级 | 可忽略 |
| GPS 位置噪声（±1-3m） | ±20-60 ms | ±20-60 ms | 与插值模型无关 |
| Skip Δt 拉长 | 2-8 ms | 2-8 秒 | 与插值模型无关 |
| IEEE 754 Double 舍入 | < 1e-6 ms | < 1e-6 ms | 忽略 |

  量级公式（匀速 vs 匀加速）：`|Δτ| ≈ 0.5·|a|·Δt²/v`。Δt² 项在 1Hz 下主导。

- **真机矛盾层级**（40ms 高频设备）：
  1. **主矛盾**：GPS 位置噪声 ±20-60ms（比插值模型大两个数量级）
  2. **次矛盾**：Skip 场景 2-8ms
  3. **近乎不计**：匀速 vs 匀加速 < 0.1ms
  4. **结论**：40ms 设备升级插值模型 = 优化被噪声完全淹没的次要项
- **1Hz 设备升级路径**（四阶段，作为未来战役决策依据）：
  - **阶段 1**：匀加速时间插值 · 利用 GPS Doppler 速度解 `s(τ) = v1·τ + 0.5·a·τ²` 二次方程
  - **阶段 2**：弦长/弧长区分 · heading 差值超阈值时用圆弧近似
  - **阶段 3**：基于朝向的二阶几何 · 用 heading 构造 Bezier/圆弧拟合，过线点从"直线∩gate"升级为"曲线∩gate"
  - **阶段 4**：超阈值 Δt 拒收 · Δt > 阈值时标记 `qualityFlag = LowSamplingRatePrecision` 或拒收
- **未来战役占位**（非本 change 施工项，attack-backlog.md 留档）：
  - `fix-gps-position-denoise` · Kalman / 位置平滑（±20-60ms → ±5-10ms）
  - `fix-laptime-skip-frame-precision` · skip 场景 Δt 阈值检测（2-8ms → < 1ms）
  - `fix-laptime-low-freq-device-support` · 1Hz 弱定位设备支持（50-200ms → < 10ms，走阶段 1-4）
- **修订建议**（四处同步）：

  **(a) proposal.md 新增 "决策 5 · 插值模型选型" 段落** —— 含量级分析表、升级路径四阶段、未来战役占位、本 change 自我约束声明。

  **(b) spec.md header 加插值模型约束段**（约 line 12-28 之间）：
  > **插值模型约束**：本 Requirement 族采用帧间线性（匀速）插值。选型依据与 1Hz 弱定位设备升级路径（四阶段）见 proposal 决策 5。**本 change 范围内 MUST NOT 引入匀加速 / 朝向几何等升级代码路径**，防止扰乱 ±5ms 合成契约前置量级假设。

  **(c) tasks.md §8 新增 8.11 grep 卡口**：
  ```markdown
  - [ ] 8.11 **插值模型范围边界 grep 审计**（proposal 决策 5 保护）：
      ```bash
      grep -nE "previousSample\.speed|currentSample\.speed" \
          feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt
      ```
      期望输出：**不含**新增的 "用于插值" 语义引用。
      （speed 字段在其他模块的使用不在本 grep 范围）。
  ```

  **(d) attack-backlog.md 新增 "未来战役预留" 段**：
  ```markdown
  ## 未来战役预留（非当前进攻点）

  - 🔮 fix-gps-position-denoise · GPS 位置噪声抑制
    真机 ±20-60ms → ±5-10ms；触发条件：真机精度战役启动
  - 🔮 fix-laptime-skip-frame-precision · skip 场景 Δt 阈值检测
    真机偶发 2-8ms → < 1ms；触发条件：真机回归发现 skip 漂移
  - 🔮 fix-laptime-low-freq-device-support · 1Hz 弱定位设备支持
    1Hz 下 50-200ms → < 10ms；触发条件：设备矩阵扩展到手机内置 GPS
    升级路径：proposal 决策 5 阶段 1-4
  ```

- **为什么升级为 P2**：
  - 量级分析是**后续战役决策的前置依据**，"留档" 只在写 proposal 时落笔成本最低（记忆还在、量级刚算完），后续再补需要重新推导
  - grep 卡口不加，proposal 决策 5 无工具保护，升级路径可能偷偷进入本 change 扰乱前置假设
  - 未来设备矩阵扩展到 1Hz 弱定位时，**若无此决策文档**，会出现 "为什么当年用匀速" 的反复推导，增加决策熵

---

## 2. 🟡 P3（建议修，可进入 `/opsx:apply`，留任务回头改）

| # | 位置 | 问题 | 修订 |
|---|---|---|---|
| P3-1 | spec R1 S3 (68-74) + tasks 1.6 | GIVEN "真实几何 或 直接注入" 二选一给实施方自由度过大 | spec 承认降级路径 (b) 并加约束；tasks 1.6 推荐走 (b) |
| P3-2 | spec R3 S2 (195-199) | "不排除等于 `startedAtMillis_{N+1}` 对应帧" 表达不清（插值毫秒是虚拟时刻，不必对应帧） | 改为 "允许（但不保证）有帧 ts 数值恰好等于" |
| P3-4 | spec 21-22 + tasks 8.9 | MODIFIED dry-run 证据不可复核 | tasks 8.9 明确复核命令，或存档 log 到 `evidence/` |
| P3-5 | tasks 1.1 | `segmentsIntersectMeters` visibility 未声明 | tasks 1.1 明确 `internal` / `@VisibleForTesting` |
| P3-6 | spec R2 S6 (156) | "诊断语义自洽" 说明文本混入 Scenario AND | 移出 Scenario，作为 R2 Requirement 说明段 |
| P3-7 | spec R3 / R5 之间 | trajectory `[startedAt, finishedAt)` vs crossingEvents `(startedAt, ∞)` 非对称易混淆 | 加两段 filter 下界 `>=` vs `>` 对比表 |
| P3-8 | spec R3 S3 (202-206) | 等式隐含 "无 A38 幻帧" 前置未声明 | GIVEN 补前置或 THEN 加注脚指向 Scenario 4 |
| P3-9 | spec R4 (243-245) | 按 sequenceIndex 追加而非 ts 单调，设计意图未明说 | 补 "追加顺序不保证 timestampMillis 单调，下游需按时间序自行 sort" |
| P3-10 | spec R5 (349-352) vs S4 (395-401) | "MUST NOT dropWhile" 与 "测试对照 dropWhile" 字面冲突 | 补分层声明 "代码层禁；测试层作对照不计入代码路径" |
| P3-11 | spec R1 S4 (76-82) + tasks 1.7 | AND 合并三种 rejected vs 单测试映射关系不清 | tasks 1.7 明确参数化 / `assertAll`，或拆成 1.7a/b/c |
| P3-12 | tasks 8.6 | 跑 `core:bluetooth` 与战役 G 并行有耦合风险 | 拆 8.6a（`core:domain` 强制）+ 8.6b（`core:bluetooth` 软检） |
| P3-13 | tasks 9 commit 3 message | 含 "A21-修订" 可能让 backlog 回溯 A21 状态 | 改为 "R5 MODIFIED 覆盖 engine-entry-hardening R3，不回溯 A21" |

（注：原 P3-3 已升级为 P2-5，编号保留空位避免与对话记录错位。）

---

## 3. proposal V3 遗留 P3（同批修）

- **proposal.md line 384** 风险表仍保留 "降级到 change 3" 表述，与 R7 "A33 硬并入本 change" 矛盾
- **若不修的后果**：tasks §8.7 关键词 grep `"降级到 change 3"` 会命中，审计失败阻塞合流
- **修订**：删除风险表中 "降级到 change 3" 条目，或改写为 "本 change 硬并入，无降级方案"

---

## 4. 🟢 已充分认可（无需改动）

1. **R2 对称/不对称硬区分**（Scenario 3 vs 4）：对称 `10_000L` 数值与 v1 等价（承认不硬区分），不对称 `9_990L` vs v1 `10_000L` 差 10ms（硬区分）——刻意把两种情况分开写，避免 "对称测试通过就误以为升级完成"
2. **R3 filter 兜底越界帧**（Scenario 4）：构造 `sampleStartIndex=5` 指向 `ts=180` 的 "幻帧" 场景，证明两段式切分在 A38 理论越界态下仍成立
3. **R4 state 推进规则表格化**：期待门 `accepted=true/false` × 四字段动作表格化，施工零歧义
4. **R5 MODIFIED S4 filter/dropWhile 对偶防退化**：`filter >` 与 `dropWhile <=` 双向锁定，防御未来误改回 `>=`
5. **R6 REPLAY 保持 ±5ms 不收紧**：理由 "防 JVM 浮点实现差异导致 CI 间歇失败" 是正确的风险分层
6. **tasks 8.7 / 8.8 / 8.9 三层审计**：关键词 grep + 字段 grep + 归档状态 —— 协调性从人工记忆变工具链
7. **Commit 策略 Section 9**：3-4 个 code commit + 可选 commit 5 的粒度，与 Requirement 依赖顺序天然对齐

---

## 5. 给实施方的回复模板

```
战役 C 二期 spec V1 + tasks V1 第二轮 review 完成。
结论：🟡 有条件准予 /opsx:apply

必修（5 P2 + 1 proposal 遗留 P3，阻塞 apply）：

P2-1  spec R2 S6 (150-156) 边界 crossingProgress==1.0 时断言 FAIL
      → 加 GIVEN 前置 crossingProgress != 1.0

P2-2  spec R2 字段契约 (101-109) 缺 rejected 分支 ts 语义
      → 分 accepted/rejected 两档契约 + 新增 Scenario + tasks 新测试

P2-3  spec R4 S5 (290-296) / tasks 4.8 "反序"指代不明
      → 改为"track.sectorGates 数据顺序为 [S3, S2, S1]"

P2-4  tasks 遗漏 4 个 Scenario 测试任务
      → §1.8 (R1 S5) / §2.12 (R2 S3) / §3.7 (R3 S4) / §5.6 (R5 S3)

P2-5  插值模型决策留档（四处同步修订）
      → proposal 新增决策 5（量级分析表 + 1Hz 四阶段升级路径 + 未来战役占位）
      → spec header 加插值模型约束段
      → tasks §8.11 加 speed 字段 grep 卡口
      → attack-backlog.md 加"未来战役预留"段（3 个占位）

proposal.md line 384 "降级到 change 3" vs R7 "硬并入"矛盾
      → 同批修（否则 tasks 8.7 grep 会命中阻塞合流）

建议修（12 P3，施工阶段顺手改）：
  P3-1 ~ P3-13（详见完整 review 文档）

🟢 已充分认可（R2 硬区分 / R3 filter 兜底 / R4 state 推进 /
   R5 dropWhile 对偶 / R6 风险分层 / tasks 三层 grep 审计 /
   commit 策略）

完整 review 文档路径：
  docs/superpowers/reviews/2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md

三件套（proposal V4 含决策 5 / spec V2 / tasks V2）+ attack-backlog.md 改完后
提交第三轮 review，通过后即可 /opsx:apply。
```

---

## 附录 · 评审方原始推导记录

### A. 为什么 40ms 帧距下匀加速修正 < 0.1ms

代入 v1 = 50 m/s (180 km/h)、Δt = 40ms、τ ≈ 20ms（Scenario 对称过线）：

| 场景 | 加速度 a | 匀速 τ | 匀加速 τ | 偏差 |
|---|---|---|---|---|
| 匀速直道 | 0 | 20 ms | 20 ms | 0 |
| 中等加速 | 5 m/s² | 20.00 ms | 19.99 ms | < 0.1 ms |
| 2G 硬刹车 | -20 m/s² | 20.00 ms | 20.08 ms | < 0.1 ms |
| F1 起步 | +50 m/s² | 20.00 ms | 19.80 ms | 0.2 ms |

量级公式 `|Δτ| ≈ 0.5·|a|·τ²/v`。代入 2G + 50 m/s：`0.5 × 20 × (0.02)² / 50 = 0.00008 s = 0.08 ms`。

### B. 为什么 1Hz 下偏差质变到 50-200ms

代入 v1 = 50 m/s、Δt = 1000ms、τ ≈ 500ms：

- a = 5 m/s²：偏差 ≈ `0.5 × 5 × (0.5)² / 50 = 0.0125 s = 12.5 ms`（保守估计，对称过线点）
- 考虑非对称过线与边界：最坏 ≈ 50 ms（a=5）/ 200 ms（a=20）
- Δt² 项放大 625 倍（`1000²/40²`），主导一切

### C. 为什么 GPS 噪声才是 40ms 设备的主矛盾

GPS 定位精度 ±1-3m，50 m/s 速度下 `time_error ≈ position_error / velocity = 3 / 50 = 60 ms`。比插值模型偏差（< 0.1ms）大 600 倍。插值模型升级在 GPS 噪声未抑制前无物理意义。

### D. 未来 1Hz 战役的升级次序

**正确升级次序**（物理量级驱动）：
1. 先抑制 GPS 噪声（`fix-gps-position-denoise`）—— 否则其他升级都被噪声淹没
2. 再处理 Skip 场景（`fix-laptime-skip-frame-precision`）—— Δt 异常拉长时拒收
3. 最后才升级插值模型（`fix-laptime-low-freq-device-support`）—— 阶段 1-4 依次引入

**错误升级次序**：跳过前两步直接升级到匀加速 = 优化被噪声淹没的次要项，典型的 "优化了错的东西"。
