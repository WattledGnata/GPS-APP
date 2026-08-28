# L2 Adversarial Review — Opus 子 agent A 线（lap-comparison-time-align）

> 触发时机：2026-05-05 W3 归档 commit `4326e11` 之后（mimo 实施期跳过 L2，user 统一协调时补跑）
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> Codex review 因后端失效由 Opus 双线替代
>
> Round 代码作者：mimo-v2.5-pro

## 测试结果

13 cases 全绿（caseA, caseB, caseC1/C2/C3, caseD1/D2/D3/D4/D5, caseE, caseF1/F2）。

## L1 P0/P1 修订核查 (verify pass)

| L1 finding | 状态 | 证据 |
|---|---|---|
| P0-1: D6 ff-only merge 自相矛盾 | ✓ 已修 | design.md:157 明确 "worktree → 主区方向"+"rebase 必然非 ff-only" |
| P0-2: accelerationG 字段定位模糊 | ✓ 已修 | design.md:213-214 D6 inline + OQ1 段 |
| P0-3: .empty() vs .EMPTY 不一致 | ✓ 已修 | grep 测试代码 7 命中 .EMPTY，0 .empty() |
| P0-4: fallback absoluteTsMs 跨时钟域污染 | ✓ 已修 | LapAlignment.kt:135 `absoluteTsMs = lapStartWallClock + refSample.elapsedMsInLap`，重新派生而非 copy；caseF2 反例锁死 |
| P0-5: helper 多余 guard | ✓ 已修 | LapAlignment.kt:30-33 helper 简化（**但**遗留 P1-B 死参数未清理） |
| P1: case E/F2 数值矩阵 / D2 (b1)(b2) / 5→6 cases | ✓ 全修 |

## P0/P1/P2 findings

### [P1-A] 静默丢弃 `flags` 字段（数据保真度漏洞）

**位置**：`core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt:193-201` (interpolate) + 134-143 (resampleByGridFallback)

**现状**：W1（commit 3c2f2d9）后 LapTelemetrySample 有 8 个字段（含 `flags: Int = 0`）。但 W3 的 interpolate() 与 resampleByGridFallback() 在构造新 sample 时**只显式传 7 个字段**，flags 走默认值 0。结果：
- interpolate 路径产生的 grid sample **丢失原始 flags 位**（哪怕 s0/s1 都有 flags=5，输出仍 flags=0）
- resampleByGridFallback 路径丢失 flags 是设计一致（fallback 圈本就无原始数据），但需 spec 显式规定
- findSampleAtDistance 边界 clamp 路径直接返回原 sample 对象 → 保留 flags ✓

**问题**：
1. spec/design 完全未规定 flags 字段插值规则，违反"字段插值 SHALL 区分连续标量、角度型、nullable" requirement 的"完备字段表"暗约
2. 现实风险低（当前无 UI 消费 flags），但 binary 协议带的 GPS 质量位（flags）在重采样后人为归零，未来 W2/W4 round 加 fallback 染色 / 弱信号提示时会发现该字段已被清零，定位困难
3. spec D6 inline 字段集合也未列 flags——这是因为 W3 design 写在 W1 加 flags 之前，但 W1 合回后 spec 应同步

**修订建议** (P1，合并 push 前可修)：
- (a) interpolate 输出加 `flags = if (alpha < 0.5) s0.flags else s1.flags`（最近邻语义，与 bearingDeg 同源）
- (b) resampleByGridFallback 显式 `flags = 0`（标记"无原始数据"）
- (c) spec "字段插值" requirement 表加 1 行 `flags | 最近邻 | 离散位掩码不能线性插值`
- 测试加 1 case：mock samples 给 flags=5，断言 `result.samplesPerLap[0][k].flags == 5`

### [P1-B] 死参数 `fallbackRefSamples` 被 `@Suppress("UNUSED_PARAMETER")` 掩盖

**位置**：LapAlignment.kt:120

**现状**：
```kotlin
private fun resampleByGrid(
    samples: List<LapTelemetrySample>,
    cumulative: DoubleArray,
    grid: DoubleArray,
    lapStartWallClock: Long,
    @Suppress("UNUSED_PARAMETER") fallbackRefSamples: List<LapTelemetrySample>?,
): List<LapTelemetrySample> {
```

**问题**：
1. tasks §3.1 设计 resampleByGrid(... fallbackRefSamples: ...) 单一函数承担两职；实际实施改成两函数 → 设计漂移未同步签名
2. 主动 @Suppress 死参数比直接删除更糟糕，误导阅读者"该参数在某处被消费"
3. L1 P0-5 提到 "helper 多余 guard → 简化" 但**漏检**这个死参数

**修订建议** (P1)：直接删除 fallbackRefSamples 参数 + @Suppress；tasks §3.1 内部 helper 设计描述同步更新（已归档；可补 patch in archive 注脚）。

### [P2-A] caseA gridSize 断言依赖巧合数值匹配

**位置**：LapAlignmentTest.kt:79-81

**现状**：`val expectedGridSize = floor(1000.0 / 5.0).toInt() + 1  // == 201`，但 mockLap 用 `endLat = 0.009`，实际累计距离 ≈ 1001.39m，floor(1001.39/5)+1 = 201，恰好等于 floor(1000/5)+1。

**问题**：靠"数值意外取整匹配"的脆弱断言隐藏认知差。如果改 endLat 微调，断言会 fail。

**修订建议** (P2)：改为容差断言 `gs in 200..203`，或用实际计算 `floor(0.009 * Math.PI / 180.0 * 6378137.0 / 5.0).toInt() + 1`。

### [P2-B] spec 未规定 NaN 输入下行为

**位置**：spec "距离步长非正" + "距离累计 SHALL 用局部平面投影"

**现状**：`if (distanceStepMeters <= 0.0)` 检查对 `Double.NaN` 输入返回 false（NaN 比较都是 false），算法继续执行 → grid[0] = NaN → findSampleAtDistance 接收 NaN target → 内部 binary search 产生未定义行为。

**修订建议** (P2)：alignByDistance 入口加 `if (!distanceStepMeters.isFinite() || distanceStepMeters <= 0.0)` 防御 + spec 加反例 scenario。

### [P2-C] metrics.yaml review_rounds_l2: 0 未补

mimo 自归档时跳了 L2，本次 review 完成后应补 review_rounds_l2: 1 + review_findings_l2。

## 关键 caveat 复核

- W1 已合回主区（commit 3c2f2d9），W3 占位 LapTelemetry.kt 已被 W1 正式版覆盖。W3 测试在 W1 合并后仍 13/13 通过，证明实施期 rebase 流程（D6）执行无误
- L1 5 轮 plateau 但漏 P1-A (flags) 与 P1-B (死参数)——这两个都是 W1 合回**之后**才显现的问题；L1 plateau 在 W1 合回前判定，**W1 合回后未触发增补 review**，是 v3 流程在跨 round 时序耦合下的真实盲区

## 是否放行

**NO**（需补丁修 P1-A、P1-B 后才放行；P2 可推到 follow-up）

理由：W3 已归档 commit `4326e11`，但 P1-A flags 字段静默归零是数据保真度问题，违反 pure function "确定性 + 无意外副作用" 隐含契约；P1-B 死参数是代码质量问题且容易顺手修。两者都是单文件改动 < 30 行，建议合一个补丁 commit "fix(lap-comparison): preserve flags through resampling + drop dead fallback param"。

如果 user 拍板"P1 推到 follow-up round"，则可放行，但 MUST 沉淀 deferred memo（按 CLAUDE.md 延期立项规矩）。

## 修订清单

**P1（建议合并 push 前修）**：
1. P1-A flags 字段最近邻插值 + spec "字段插值" 表加行 + 加单测
2. P1-B 删除 resampleByGrid 死参数 fallbackRefSamples + @Suppress

**P2（可推到 follow-up）**：
3. P2-A caseA gridSize 断言改为容差
4. P2-B alignByDistance 防 NaN/Inf
5. P2-C metrics.yaml 补 review_rounds_l2

## metrics.yaml 建议值

```yaml
review_rounds_l2: 1
review_findings_l2:
  - "P1-A: flags 字段在 interpolate/fallback 路径静默丢失（W1 合回后才显现，L1 plateau 漏检）"
  - "P1-B: resampleByGrid 死参数 fallbackRefSamples 用 @Suppress(UNUSED_PARAMETER) 掩盖"
  - "P2-A: caseA gridSize 断言依赖巧合数值匹配（1001.39m floor 到 200，恰好等于假设的 1000m）"
  - "P2-B: spec 未规定 NaN/Inf 输入下的 distanceStepMeters 防御"
divergence_reason: "L1 5 轮 plateau 在 W1 合回前判定，W1 合回后追加 flags 字段触发跨 round 时序问题，L2 补跑发现"
model_apply: "mimo-v2.5-pro"  # 修正：原写 opus 不准确
```
