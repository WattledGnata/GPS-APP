# L1 Adversarial Review — Opus 子 agent R1（phase1-hardening-w2-w3-w4-mimo-debt）

> 触发时机：2026-05-05 CC 主会话 /opsx:ff 完成后，/opsx:apply 启动前
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> 复杂度：large（22 项 P0/P1 + 跨 3 capability + governance 条款 + 跨 round 共享字段 drift）
>
> 本轮重点：第 1 轮 — 设计骨架 + scope 假闭环 + 决策最优性 + 决策 alternatives 完整性
>
> 工件作者：CC 主会话（Opus）

---

## 总结

| 优先级 | 数量 | 摘要 |
|---|---|---|
| **P0** | 4 | C1 基于错误前提 / E5 锚点死路径 / Decision 2 与 spec 写法不一致 / governance 条款互斥 |
| **P1** | 8 | LapAlignment.kt 既有调用站点未列 / B 类 spec 矛盾 / Decision 3 实测验 caller 错 / Decision 5 非 governance / D2 D3 测试断言矛盾 / cross-round drift 关联 round 不全 / metrics.yaml schema drift / Decision 7 启动条件不能闭环 |
| **P2** | 5 | tasks 11 章可执行性散落 / E1 反例与 spec scenario 重复 / 性能 baseline 选项偷懒 / Risk 3 ContractTest 影响低估 / fee D2/D3 case 编号冲突 |

**总：4 P0 + 8 P1 + 5 P2 = 17 项**

---

## P0 发现

### [P0-1] C1（SpeedTimeChart n=1 silent canvas 外渲染）基于错误前提 — 当前生产代码已经有守卫

**位置**：
- proposal.md:41 + tasks 3.1（`feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt`）
- 对比当前生产代码 `SpeedTimeChart.kt:128, 140, 151`

**现状**：proposal.md / spec / tasks 都声称 "n=1 时 lapDurationMs == 1L + elapsedMsInLap > 0 → silent canvas 外渲染"。但当前生产代码：

```
SpeedTimeChart.kt:128, 140  detectDragGestures / detectTapGestures：
  val lapDurationMs = if (samples.size >= 2) samples.last().elapsedMsInLap - samples.first().elapsedMsInLap else 1L

SpeedTimeChart.kt:151  drawScope:
  if (coords.size >= 2) { ... drawPath ... }  ← n=1 时 coords.size==1 不画 chart line
```

也就是说 n=1 时 chart line 渲染分支根本不会进入。**触摸事件**确实会 lapDurationMs=1L → touchElapsedMs = touchX × 1 / size.width（极小数 → coerceIn 0..1L），落到 sample[0]，触发 onCursorChange — 这是真问题但不是 "silent canvas 外"，**不会造成 visual bug**。

**问题**：
1. proposal/spec 把 P1 的"silent canvas 外渲染"当作 ground-truth，但 mimo 实施其实已经避免了渲染分支（`coords.size >= 2` 守卫）；只有触摸路径有可能 lapDurationMs=1L
2. spec 反例 scenario "n=1 elapsedMsInLap > 0 → x = 5_000 × canvasWidth 飞出 canvas" 在当前代码上**不成立**（drawPath 分支跳过）
3. 修订动作"n=1 走 placeholder 分支"等于把现有 `coords.size >= 2` 跳过分支 + 触摸 lapDurationMs=1L 都收敛到 isEmpty 路径，正确方向，但 **proposal Why 描述与代码现实不符**——L2 review-l2-opus-a.md P1-1 描述的"silent 外渲染"是基于 mimo 当时**或可能**写的某个版本，本 round 工件没 verify 当前代码状态就直接 inherit P1 描述

**修订建议**：
- (a) tasks 3.1 done condition 改为"读 SpeedTimeChart.kt 当前代码 verify `coords.size >= 2` 守卫已存在 + 加 `samples.size <= 1` early-return placeholder（与 isEmpty 同分支）+ 触摸 lapDurationMs=1L 无效化（return 不调 onCursorChange）"
- (b) spec 反例 scenario 改写：当前现实是"chart line 不渲染但触摸 callback 仍会触发 sample[0] → 视觉无 bug 但 cursor sync 行为 surprise"
- (c) proposal Why "silent canvas 外" 描述加 caveat "当前生产代码 line 151 `coords.size >= 2` 守卫已避免渲染分支；本 round 收敛触摸路径 + 把 placeholder 提前到 isEmpty 路径合并"

### [P0-2] E5 修订路径死路径 — W4 archive tasks.md 当前没有 §12 章节

**位置**：proposal.md:58 / tasks 6.5 / impact §"工件层修订" 列出 "W4 archive design/spec/tasks (E1/E2/E4/E5 sync, 不进 git)"

**现状**：实际读 `/Users/wattledgnata/traeProjects/gps-app/openspec/changes/archive/2026-05-05-wire-laptime-to-gps-filter/tasks.md` 当前章节：§1-§10（参 grep "^## " 输出），没有 §12 章节。本 round task 6.5 写"修订 W4 archive tasks.md §12 归档后状态" — 这是**新建**章节，不是"修订"现有章节。

**问题**：
1. tasks.md 6.5 "Done condition: grep tasks.md §12 出现 hotfix B + W4_DIAG + 真机 verify pass 后 字面量"是**新建** section 的 done condition；当前 round 工件含混"修订" / "新增"
2. 参 W2 archive tasks.md 模式 — W2 的 tasks.md 是否真的有"§12 归档后状态"？没有 verify
3. 名称 "§12 归档后状态修订" 借用 "归档后状态修订" 这个标语 → 暗示既有 schema 但实际是新建

**修订建议**：
- (a) tasks 6.5 重写为"在 W4 archive tasks.md 末尾**新增** §11 节（不是 §12，按当前 §1-§10 顺延），含 hotfix B 记录 + W4_DIAG strip 计划"
- (b) verify "W2 archive tasks.md 是否有归档后状态修订 section" → 如果有则 mirror 命名 / 如果没有则 OQ 加新命名约定（参考 §10 follow-up backlog 是普遍约定的"归档后追加"位置）
- (c) impact 段把"W4 archive tasks.md sync (E5)"措辞改"新增章节"

### [P0-3] Decision 2 (flags 最近邻) 与 spec 反例 scenario 写法不一致 — 实施期歧义

**位置**：design.md:71-92 (Decision 2) + spec.md:73-75 (反例)

**现状**：design Decision 2 决议是"选项 B 最近邻：`if (alpha < 0.5) s_k.flags else s_{k+1}.flags`"。但 W3 spec.md:75 反例 grep gate 写：

> grep gate 扫 `core/domain/.../usecase/LapAlignment.kt` MUST 出现 `flags = if (alpha < 0.5) s_k.flags else s_{k+1}.flags` 字面量（或等价 `if (alpha < 0.5) ... else ...` 三元表达式带 flags），命中 `flags = 0` 字面量 → fail

**问题**：grep gate 锁字面量 `flags = if (alpha < 0.5) s_k.flags else s_{k+1}.flags`，但 LapAlignment.interpolate 函数体内本地变量名是 `s0` 和 `s1`（参 LapAlignment.kt:179-202 实际函数签名 `interpolate(s0, s1, alpha, lapStartWallClock)`），不是 `s_k` / `s_{k+1}`。grep gate 字面量 `s_k.flags else s_{k+1}.flags` 在生产代码中**永远 0 命中** → 实施时按 grep gate 写要么必须改函数变量名（破坏既有 K1/K2 命名），要么 grep gate fail。

更严重：tasks 4.2 (B2) 的 done condition：

> grep LapAlignment.kt 出现 `flags = if (alpha < 0.5) s_k.flags else s_{k+1}.flags` 字面量（或等价三元表达式 + flags）

**等价三元表达式 + flags** 太宽——`flags = 0 + if (alpha < 0.5) ... else ...` 也会被宽松规则吃下，反而开后门让回退路径混进。

**修订建议**：
- (a) spec.md:73-75 + tasks 4.2 把 grep gate 字面量统一为 `flags = if (alpha < 0.5) s0.flags else s1.flags`（与现有变量名一致）
- (b) 反例 scenario 加严：grep gate 用「 `Regex("""flags\s*=\s*if\s*\(\s*alpha\s*<\s*0\.5\s*\)\s*s0\.flags\s*else\s*s1\.flags""")` 命中数恰好 1」+ 「`Regex("""flags\s*=\s*0""")` 命中数等于 1（仅 resampleByGridFallback 内）」**双锚点**
- (c) 当前现实：LapAlignment.kt:179-202 interpolate 已构造 LapTelemetrySample 但**0 行 flags 字段**（参 line 192-201）— 编译走默认值 0，Kotlin data class 不报错

### [P0-4] F1 governance 条款与 Decision 7 自身互斥 — "条款 dead 的 governance 不是 governance"

**位置**：design.md:174-194 (Decision 7) + Risk 2 + tasks §1.1-1.3

**现状**：design Decision 7 决议"选项 C — 自然 trigger"。Risk 2 说"如果本 round 没真的 trigger → governance 条款无 worked example"，mitigation 是"metrics.yaml `design_decisions_diverged_during_apply` 字段透明声明"。

**问题**：
1. Decision 7 选项 C 决议**承认本 round 不 demonstrate 该条款**，仅写入 CLAUDE.md + metrics.yaml schema 字段。但 proposal Why §"为什么是现在 (c)" 说"F1 governance 条款是结构性约束，越早写入越能在后续 Phase 1/Phase 2 round 中守住 design 决策" — 关键 claim 是**"守住"** → 守得住的前提是"被发现并触发"，CC 自查盲点表机制能否在实施期"立刻 trigger 暂停 apply"是**没有验证的承诺**
2. **没有任何 trigger condition** 的 governance 条款，落地仅靠"CC 自查 → 暂停 apply" — 但 CC 在 W4 mimo 实施期就是因为 CC 主会话不在场 → 不 trigger；mimo session 不读 CLAUDE.md → 不 trigger；新条款写入 CLAUDE.md 仍然依赖"CC 自觉"，**没有自动化 enforce**
3. 本 round 启动是**为了**消化 W4 mimo 偷改 design 的债务，但本 round 自身的 F1 条款无法防止"未来类似 mimo session 偷改 design"——本 round 把 CC 主会话作为 governance enforcer，但 W4 失败的本质恰恰是 CC 主会话不在 mimo apply 流程内
4. Decision 7 选项 C 与选项 A "条款 dead 时间长" 的 rejection 缺少 force — 选项 C 自己**就是**条款 dead 的状态（除非自然 trigger）

**修订建议**：
- (a) Decision 7 加新选项 E："F1 条款写入 CLAUDE.md + 写入 OpenSpec round 启动模板检查清单（`/opsx:ff` skill 自动加 `design_decisions_diverged_during_apply: []` 到 metrics.yaml schema 默认骨架）"——把 governance 落到工具自动化层而非"自查"
- (b) 透明承认 Decision 7 选项 C 在没有 trigger 的 round 实际上**只是把 governance 条款落地为 CLAUDE.md 文字 + metrics.yaml 字段**，不构成"实施期 demo" — proposal Why §(c) 应改写"F1 条款的 worked example 取决于后续 round 自然 trigger"
- (c) Risk 2 mitigation 加严：CLAUDE.md 中条款本身要求 "CC 主会话每次 apply 启动前 MUST grep 自检 round design 当前所有 Decision id；apply 期每完成 1 task 后 MUST 与 design Decision 比对，发生 drift 立即暂停" — 把"自查"具体化到 actionable sub-step

---

## P1 发现

### [P1-1] B2 LapAlignment.kt 改动列表漏 caller 修订 — `resampleByGrid` 内部调用 `findSampleAtDistance` 走 interpolate

**位置**：tasks 4.2 (B2) / design Decision 2 / impact 段 LapAlignment.kt 行号

**现状**：B2 task 写"在 `interpolate` 加 flags 最近邻 + `resampleByGridFallback` 强制 flags=0"。但 LapAlignment.kt 当前还有 `findSampleAtDistance` (line 146-177) 是 `resampleByGrid` 唯一 grid sample 生产路径 — 调用站包括：
- LapAlignment.kt:160-164 — 精确命中（`if (idx >= 0)` `return samples[kMin]`）→ **直接返回原 sample 含原 flags ✓**
- LapAlignment.kt:155-156 — clamp 边界（`if (targetDist <= cumulative[0])` / `>= cumulative[n-1]`）→ **直接返回原 sample 含原 flags ✓**
- LapAlignment.kt:176 — 内部插值调用 `interpolate(samples[k], samples[k + 1], alpha, lapStartWallClock)` → **本 round 修订点**

工件**没有显式列出** clamp 边界 + 精确命中两条路径已经天然保留 flags（直接 return 原 sample）。这两条路径现状代码**不需修订**但 spec 反例 scenario 应锁死，否则未来重构（如把 clamp 路径改成 `samples[0].copy(...)`）会 silent regress。

W3 archive review-l2-opus-a.md P1-A 第 36 行明确："findSampleAtDistance 边界 clamp 路径直接返回原 sample 对象 → 保留 flags ✓" — 这是 review trail 已 nail 的事实，但本 round spec 的 R1 字段插值表只写 interpolate 行为 + R2 fallback 行为，**漏掉 R1 的 clamp / 精确命中两条不需修订路径的反例锁**。

**问题**：
1. 工件层 spec.md:20-28 边界处理段提到"`d* < d_0`：返回 samples[0] 的复制 (clamp 到首样本，所有字段保持原值，含 flags)"+ "`d* > d_{n-1}`：返回 samples[n-1] 的复制 (clamp 到末样本，含 flags)" — 但这只是 normative 描述，没有反例 scenario
2. 测试 case G 仅锁 interpolate 路径（α<0.5 / α>=0.5 / 重复距离区间）— 没锁 clamp 路径 / 精确命中路径

**修订建议**：
- (a) spec.md:20-28 加反例 scenario "`if findSampleAtDistance clamp 返回 samples[0].copy(flags=0)` 路径必须 fail" — 防止重构 clamp 路径破坏 flags 保留
- (b) tasks 4.4 (B4 case G) 加 sub-scenario G4 "`d* < d_0` clamp 路径 flags 保留" + G5 "精确命中重复距离区间起点 flags 保留"

### [P1-2] B 类 spec 字段表与 W3 spec scenario 表达式矛盾

**位置**：spec.md:13 (B 类新增 flags 表行) + spec.md:62-67 (B4 case G α<0.5/α≥0.5)

**现状**：spec.md:13 字段表写：

| `flags` | 最近邻：`if (alpha < 0.5) s_k.flags else s_{k+1}.flags` |

但 spec.md:62-67 (Scenario G α<0.5 与 α≥0.5) 写"近端 = `s_k`" / "近端 = `s_{k+1}`"。Decision 2 的 rationale 也说"与 R6 现有 bearingDeg 最近邻策略一致"。然而 spec.md:11 R1 字段表 bearingDeg 行：

> bearingDeg | 最近邻：`if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg`

**问题**：α=0.5 边界点的归属，bearingDeg 行写 `< 0.5` (s_k) / `>= 0.5` (s_{k+1})；flags 行同写。但 spec.md:62-67 Scenario "α=0.6（近端 = s_{k+1}）" 是对的（0.6 ≥ 0.5）。**真正问题**是 R1 字段表 + R6 bearingDeg + B 类 flags 都写"`if (alpha < 0.5)`"，但实际上 "**最近邻**" 的标准定义是 `alpha < 0.5 → s_k`，`alpha > 0.5 → s_{k+1}`，`alpha == 0.5 → 任选一边但要 deterministic`。当前都用 `< 0.5` 写法 = α=0.5 时取 s_{k+1}。

这与 design Decision 2 没冲突（实际选 B 就是 `< 0.5`），但 case G α=0.5 边界**没有任何反例 scenario** — 而这是最容易 silently regress 的 case（从 `< 0.5` 改成 `<= 0.5` → 同函数返回值 silently 切换）。

**修订建议**：
- (a) spec.md:62-67 加 case G2.5 "α 严格等于 0.5 边界归属 deterministic"：α=0.5, s_k.flags=1, s_{k+1}.flags=2 → flags == 2（按 `< 0.5` 取右侧），断言不允许 == 1
- (b) 同步 R6 bearingDeg 加边界 case（与 D2 case H 区分）

### [P1-3] Decision 3 (computeAccelSegments 抽到同文件) 验 caller 错 — 实际上 SpeedTimeChart 也用了 segment 思路

**位置**：design.md:94-110 (Decision 3) + 当前 AccelTimeChart.kt:85-99

**现状**：Decision 3 决议选项 A，rationale "当前唯一 caller 是 AccelTimeChart 自己；未来若需复用走重构"。reject 选项 B "未来扩展（多字段 segment 计算）方便 / YAGNI" 假设 SpeedTimeChart 不需要 segment 因 speedKmh 是 non-null。

**问题**：当前 AccelTimeChart.kt:85-99 实际渲染逻辑：

```kotlin
var segmentStart = -1
for (i in samples.indices) {
    if (samples[i].accelerationG != null) {
        if (segmentStart < 0) segmentStart = i
    } else {
        if (segmentStart >= 0) { drawSegment(coords, segmentStart, i - 1); segmentStart = -1 }
    }
}
if (segmentStart >= 0) drawSegment(coords, segmentStart, samples.size - 1)
```

mimo 实施时把 segment 拆分内联到 Composable 体内，**没用** computeAccelSegments 纯函数 — 因为根本没抽。本 round Decision 3 + tasks 2.4 抽 internal fun computeAccelSegments + Composable 改用 segments.forEach { range -> drawSegment(range) } **覆盖**当前代码 line 85-99，但：
1. 既有 `private fun DrawScope.drawSegment(coords, from, to)` (line 121-134) 接受 `from: Int, to: Int` 参数 → 与 IntRange 对接需要 `range.first, range.last` 拆分
2. 当前 segment 拆分 fold 逻辑跨 sample/coords 双数组 — 抽纯函数后 segment 是 sample 索引域，但 Composable 内 drawSegment 用 coords 域（一一对应）；没问题但 tasks 2.4 done condition 说 "Composable 体内出现 `computeAccelSegments(samples)` 调用" 没说"既有 inline fold 必须删除" — 实施可能新增不删旧 → trivially-pass

**修订建议**：
- (a) tasks 2.4 done condition 加严："grep AccelTimeChart.kt MUST 0 命中 `var segmentStart = -1` 字面量（既有 inline fold 必须删除）"
- (b) tasks 2.4 加 "既有 `private fun DrawScope.drawSegment(coords, from, to)` 函数签名修订或保留"决策项 — 简单做法：保留签名，调用处 `forEach { range -> drawSegment(coords, range.first, range.last) }`

### [P1-4] Decision 5 (W4 actual_days 估算 1.0) 不属于 governance Decision

**位置**：design.md:134-154 (Decision 5)

**现状**：Decision 5 把"actual_days = 1.0 + comment 拆分项"作为 Decisions 段一项。decisions 段在 OpenSpec 工程中 normative 是"design 级方案对比 + 选择 rationale"，不是 metadata 选填策略。

**问题**：
1. Decision 5 选项 A/B/C 对比的是 metadata 字段如何填，不是设计权衡 — 把 metrics 填法升格到 Decision 增加阅读负担
2. Decision 5 的 reject 理由"选项 A 数据缺失 / 选项 B 不含 review" 都不是工程权衡，是 retrospective 偏好 — 应放 OQ 段或直接 inline 到 tasks 6.3 done condition
3. 真正的 governance Decision 是 Decision 1 (F1 措辞) / Decision 2 (flags 策略) / Decision 4 (paren balance) — 这些都需要"如果选错就 silent regress"风险评估；Decision 5 选错只是 retrospective 偏 σ

**修订建议**：
- (a) 把 Decision 5 降级为 OQ "OQ5 W4 actual_days 估算依据"（与 OQ1/OQ2 同位置）
- (b) tasks 6.3 done condition 直接含规则"actual_days = mimo 实施 + L2 review + hotfix B 整体闭环 = 1.0"
- (c) 同样审视 Decision 6 (cross_round_field_drift_resolved) — 也是 metrics schema 选填，不是设计权衡

### [P1-5] D2 / D3 测试 case 编号与现有 LapAlignmentTest 冲突

**位置**：tasks 5.2 (D2 case H) + 5.3 (D3 case I) + 4.4 (B4 case G) + 现有 LapAlignmentTest.kt:51, 105, 123, ..., 271, 306

**现状**：当前 LapAlignmentTest.kt 函数命名（参 grep 输出）：caseA / caseB / caseC1/2/3 / caseD1/2/3/4/5 / caseE / caseF1/F2 — 已经用了 D1-D5 命名。本 round tasks 4.4 / 5.2 / 5.3 写"加 case G / case H / case I"延续字母序，但 D / G / H / I 只是 design 习惯命名，不是 mimo 测试命名风格 → 实施期可能命名为 `caseG_flagsNearestNeighbor` / `caseH_bearingWrap360` / `caseI_elapsedFloatBoundary`。

**问题**：
1. 现有测试 D1-D5 已占用 D1-D5 数字编号，本 round D 类（**完全不同的语义**：`D = W3 trivial` 对应 task 5.x）的"case D2 / D3" 描述容易和**测试函数名 D1-D5** 混淆
2. tasks 5.2 / 5.3 的 "case H / case I" 命名是 spec 引用 (spec.md:42-43, 57-59)，但 spec 同时把"D2 加严" / "D3 加严" 写在 spec.md:41, 56 — 让 reviewer 在工件层混淆 "task 5.2 (D2 = trivial 编号)" vs "spec D2 case (= scenario 编号)"

**修订建议**：
- (a) spec.md:42-43 / 57-59 把 "D2 加严" / "D3 加严" 改为 "Tasks 5.2 加严" / "Tasks 5.3 加严" — 与 task 编号 anchored 而非 trivial 类编号
- (b) tasks 5.2 / 5.3 done condition 显式给出测试函数名：`caseG_flagsNearestNeighbor` / `caseH_bearingWrap360` / `caseI_elapsedFloatBoundary` — 不留实施期歧义

### [P1-6] cross_round_field_drift_resolved schema 字段未定义在 CLAUDE.md 的 metrics.yaml schema 之前 — task 1.2 顺序倒置

**位置**：tasks 1.2 (CLAUDE.md schema 加字段) + tasks 8.3 (本 round metrics.yaml 写入)

**现状**：tasks 1.2 写"在 CLAUDE.md `L2 完成后 metrics.yaml` 节 yaml 例子加字段 `cross_round_field_drift_resolved: []`"。tasks 8.3 写"本 round metrics.yaml 含 `cross_round_field_drift_resolved: ["LapTelemetrySample.flags (W1→W3)"]`"。

**问题**：
1. CLAUDE.md "L2 完成后 metrics.yaml" 节当前已 documented `estimated_days / actual_days / review_rounds_l1 / l2 / review_findings_l1 / l2 / divergence_reason / phase / model_apply` 9 字段（参 CLAUDE.md:271-289）— `cross_round_field_drift_resolved` 是新字段，schema 已修订（task 1.2）后写入（task 8.3）顺序对
2. 但 W1/W2/W3/W4 已归档 round 的 metrics.yaml **没有这个字段** — 本 round 加 schema 字段后这些 round 的 metrics.yaml 不补就 schema-violate；本 round 工件 impact 段没列"是否回填这个字段到 W1-W4 metrics.yaml"
3. 事实：W1 → W3 drift 的发起方是 W1（添加 flags），W3 是 consumer。本 round 是 W2/W3/W4 hardening。**触发** drift mini-review 的责任在哪个 round 的 metrics 里？工件没说。如果是 W1（发起方），W1 metrics.yaml 应该列出 "consumed by: W2, W3"；如果是 W3 (consumer)，W3 metrics.yaml 应该列出 "drift resolved by phase1-hardening" — 现在工件只在本 round metrics.yaml 写一行，不闭合

**修订建议**：
- (a) tasks 1.2 加字段定义时写明 schema 含义："`cross_round_field_drift_resolved`: 本 round 修复的跨 round 共享字段 drift 列表；条目格式 `FieldClass.field (originRound→consumerRound)` — 仅当本 round 是修复方时填，发起方 round 在 originRound 字段透明声明 consumed_by 由后续 follow-up 维护"
- (b) impact 段加"W1 metrics.yaml 是否补 consumed_by 字段" OQ
- (c) 本 round metrics.yaml 字段值改为 `["LapTelemetrySample.flags (W1→W3, resolved by this round)"]` 字面量含 "resolved by this round" 关键字 — 让 retrospective 聚合时 deterministic

### [P1-7] design.md Decision 7 选项 C 用 metrics.yaml 字段做 governance 锚点 — 但 schema 字段刚刚加，所有已归档 round 都没有

**位置**：design.md:174-194 (Decision 7) + design.md:196-205 (Risk 2)

**现状**：Decision 7 选项 C 决议"自然 trigger，metrics.yaml 显式声明实施期是否有 drift；如果有，走 OpenSpec 修订；如果没，metrics.yaml 字段为 []"。Risk 2 mitigation "metrics.yaml `design_decisions_diverged_during_apply` 字段透明声明（即使为 []）"。

**问题**：
1. metrics.yaml schema 字段是本 round task 1.2 才加 — 已归档的 W1/W2/W3/W4 metrics.yaml 全部 schema-violate 该字段；本 round 是首个 demonstrate
2. 如果本 round 自身 apply 期没 drift → metrics.yaml 该字段 = `[]` — 这只能证明"CC 自查写了空 list"，**不能证明**"CC 真的自查过 + apply 期间真的没 drift"
3. 字段值 `[]` 与字段不存在不可区分；governance enforce 完全靠 CC 主会话**手动**写入 — 没自动化抓 drift 的能力
4. Decision 7 自己判 governance 锚点失效 — 本 round 同时担任"first round to demonstrate F1" + "F1 锚点定义 round" 双重身份，**自参考**问题；governance 立 condition 不能由它自己 demonstrate（应由 next round 真正 trigger 来 demo）

**修订建议**：
- (a) 把 F1 demo 推到下一个 round（如某 Tier 2 round），本 round 仅做 schema 写入；Risk 2 改写"本 round 不 demo F1 — 只锁 schema；首次 demo 由后续 round 自然 trigger"
- (b) metrics.yaml 字段值不是 list 而是 enum：`design_decisions_diverged_during_apply: NONE | List[...]` — 区分"CC 自查无 drift"和"未自查"

### [P1-8] cross-round drift 关联 round 不全 — 漏 W2 / W4 是否消费 flags

**位置**：design.md:156-172 (Decision 6) + spec.md:156 + tasks 8.3 (cross_round_field_drift_resolved)

**现状**：Decision 6 列 "LapTelemetrySample.flags (W1→W3)"。本 round 工件多处把 W3 单独列为 flags 消费方。

**问题**：
1. 本 round 工件 spec.md 修订 `lap-telemetry-chart-components` (W2 capability) — 因为 W2 的 GrepGateTest §8.7 也漏了 flags（B1 修订）。如果 W2 也漏了 → W2 也是 consumer drift round
2. W4 `lap-timing-gps-filter-pipeline` capability — telemetry binary writer (`bridgeGpsToLapTiming` 写 binary sample)。binary sample 的 flags byte (协议 17-byte sample 的固有 1 byte，参 W3 review-l2-opus-b angle 1 第 36 行 "GpsBinaryFormat.kt:62, 75, 89 — flags 是 17-byte sample 协议的固有 1 byte") — W4 是否消费 flags？工件 0 行检查
3. 本 round metrics.yaml 写 "LapTelemetrySample.flags (W1→W3)" 漏掉 W2 / W4 → cross-round drift 跟踪不全

**修订建议**：
- (a) Decision 6 加严：列出"W1 后所有 LapTelemetry / LapTelemetrySample 的 consumer round" 表格 — `wire-laptime-to-gps-filter (W4)` 是否消费 flags / `chart-and-map-components (W2)` 是否消费 flags
- (b) cross_round_field_drift_resolved 字段值改为 `["LapTelemetrySample.flags (W1→W2, W3, W4; W3 fixed; W2/W4 verified no consumption)"]`（如 verify 结果）
- (c) tasks 加新 task 9.x "grep W4 viewModel + GpsBinaryFormat verify 是否有 flags 字段消费 → 若有则本 round 加 W4 spec 修订；若无则 metrics 显式声明"

---

## P2 发现

### [P2-1] tasks 11 章节碎片化 — apply 期无法逐 batch 启停

**位置**：tasks.md 全文

**现状**：tasks §1-§11 共 ~50 个 sub-task，按 A/B/C/D/E/F 类分散。设计 Migration Plan §"Apply 顺序"是 1.F1 → 2.A → 3.C → 4.B → 5.D → 6.E 顺序，但 tasks.md 章节结构是 §1 F1 → §2 A → §3 C → §4 B → §5 D → §6 E → §7 测试 gate → §8 review/归档 → §9 自检 → §10 backlog → §11 worked example — apply 时如果某个 sub-task 中断（如 B2 实施期发现 P1-3 缺失），重启 apply 不知道从哪 sub-task 续。

**修订建议**：tasks 加 §0 "apply 启动检查清单" 列出 6 大 batch 的进入条件 + 出口条件；每个 §X 加 "完成此 §X 后立即跑 ./gradlew :Y:test --tests=Z 而不等到 §7 测试 gate 才一次跑"

### [P2-2] E1 (timestamp 反例) 与 spec.md:43-45 反例 scenario 重复

**位置**：spec.md:36-40 (正例 cleaned.timestamp == raw.timestamp) + 42-45 (反例 误把 timestamp 一并替换)

**现状**：spec scenario 既有正例又有反例；W4 archive spec 也已有同款反例（mimo 落地过 R1 反例）— 本 round 加 case 是 LapFilterIntegrationTest 添加正面 case lock，但 spec scenario 层面正反例已存在，描述冗余。

**修订建议**：tasks 6.1 done condition 改为"补正面 case 'cleaned.timestamp == raw.timestamp' (mimo 跳过)"，spec scenario 层面减一条（保留反例足够）

### [P2-3] C4 (W2 design 性能 baseline) 选项偷懒 — 推到 Tier 2 真机签收 = "性能 baseline 永远不存在"

**位置**：tasks 3.5 (C4) + Risk 6

**现状**：tasks 3.5 给 (A) 跑微测 + (B) 透明声明 "无量化性能 baseline，由 Tier 2 真机首次组屏签收" 二选一。Risk 6 mitigation 说"低 — 性能 baseline 不是 release blocker"。

**问题**：(B) 选项实际上等于"把性能 baseline 推给一个不存在的 round" — Tier 2 真机签收的指标是行为，不是性能 baseline；放弃量化 baseline 后下次"是否性能足够" 没参照。这是 v3 高频盲点 #13 (dead spec / 卸责借口) 软变种。

**修订建议**：tasks 3.5 加 (C) 选项 "用 1500 sample mock data 跑 contract test 1 次，以 ms 为单位录到 design.md（不必 sub-ms 精度，仅 ballpark）" — 半小时投入避免 dead 死循环

### [P2-4] Risk 3 (A2 ContractTest expected 值 drift) 影响低估

**位置**：Risk 3 + tasks 2.2 (A2)

**现状**：Risk 3 说"低 — ContractTest 没多少 case 直接依赖 606 字面量"。

**问题**：A2 公式从 `60_000 / 99 = 606` 改为 `(i.toLong() * lapDurationMs) / (n - 1)` 后，sample[99].elapsedMsInLap **从 59_994 改成 60_000** — 这是行为变更。任何 ContractTest 内 `assertEquals(59_994L, samples.last().elapsedMsInLap)` 风格的 hardcode 都会 fail。Risk 3 应明确"apply 阶段必须 grep ContractTest 文件 hardcode 数值清单 + 全部更新"。

**修订建议**：tasks 2.2 done condition 加"grep `feature/test/src/test/.../*ContractTest.kt` 出现 `59_994` / `606` 字面量必须 0 命中（除非 test 是新加 case 引用旧值反例）"

### [P2-5] D2 / D3 case 编号选择 — 设计期就近名 vs 测试期连续名

**位置**：tasks 5.2 (D2) / 5.3 (D3) / 4.4 (B4 case G) + 现有 LapAlignmentTest.kt 函数名

**现状**：现有测试函数名 caseA / caseB / caseC1-3 / caseD1-5 / caseE / caseF1-F2 已用 13 个名字。本 round 新加 case G / H / I 是字母连续。

**问题**：测试命名 case 序列（A→F）≠ design 段决策序列（A1-A5 / B1-B4 / C1-C4 / D1-D3 / E1-E5 / F1）。reviewer 在工件层 grep "case D2" 会看到两个语境（spec scenario "D2" + tasks 类编号 "D2"）— 见 P1-5 已提，本条降级为 P2 重复 anchor。

**修订建议**：参 P1-5 修订即可

---

## §A 上轮 P0/P1 修订到位检查（不适用 — 这是第 1 轮）

第 1 轮无上轮可比，跳过。

## §B 生产代码 grep pattern 与实际代码对齐 verify

实际 verify 结果：

| spec / tasks 锚点 | 实际生产代码 | 对齐状态 |
|---|---|---|
| LapAlignment.interpolate flags 字面量 `s_k.flags / s_{k+1}.flags` | line 179-202 函数变量名 `s0 / s1` | **MISMATCH (P0-3)** |
| LapAlignment.kt fallbackRefSamples + @Suppress 删除 | line 120 实际有 `@Suppress("UNUSED_PARAMETER") fallbackRefSamples` | 对齐 ✓（D1） |
| SpeedTimeChart n=1 silent canvas 外渲染 | line 151 `if (coords.size >= 2)` 守卫已存在 | **PARTIAL MISMATCH (P0-1)** |
| LapLiveStateDeriver:159 注释字面值 ≥3 | line 159 实际写"向前 1 秒窗口内 invalidating event 必须 ≥ 3 个" | 对齐 ✓（E2） |
| GrepGateTest §8.4 windowed(300) | line 91-104 实际 `content.substring(start, minOf(start + 300, content.length))` (类似 windowed 滑窗) | 对齐 ✓（C2） |
| GrepGateTest §8.7 锁 7 字段缺 flags | line 138-150 锁 7 字段未含 flags | 对齐 ✓（B1） |
| W4 metrics.yaml actual_days = null | 实际 `actual_days: null  # TODO(user)` | 对齐 ✓（E3） |
| W4 archive tasks.md §12 归档后状态 | 实际只有 §1-§10 | **MISMATCH (P0-2)** |
| LapTelemetrySample 第 8 字段 `flags: Int = 0` | line 21 实际有 | 对齐 ✓（B1） |

3 项 mismatch 已升 P0；其他 7 项对齐。

## §C/§D Fresh eyes scope 假闭环 + memo 同步检查

**Fresh eyes 视角的 scope 假闭环风险**：

1. **F1 governance 条款本 round 内 demo 闭环**？— 工件自承认 Decision 7 选项 C 不 demonstrate，仅靠 metrics.yaml 字段空 list 透明声明 — 实际**未闭环**（P0-4 / P1-7）
2. **B 类 LapTelemetrySample.flags drift 是否真覆盖所有 caller**？— 工件只列 W3 LapAlignment.interpolate + resampleByGridFallback；漏 clamp / 精确命中（P1-1）+ 漏 W2 / W4 capability 是否消费 flags（P1-8）
3. **W2 archive 工件层修订是否能在 worktree 内推进**？— archive 目录是主区 absolute path（参 tasks 2.1 etc.），本 round worktree HEAD = 4326e11 — worktree 内能写主区 absolute path 文件吗？git worktree 模式下 worktree 与主区共享 .git 但 working tree 是分离的；写主区 archive 路径 = 写 working tree 之外文件 = 仅文件系统层面修改（不被 git 追踪）— 工件 §"impact" 第 22 行说 "archive 工件 md 在 worktree 推进 + 人工 cp 到主区 archive 目录" — 但 worktree 内 absolute path 是同一个文件系统（macOS 文件系统），写主区 archive 路径 = 直接写主区目录，**不需要 cp** — 工件描述含混；应该是 "worktree 进程**直接**修改主区 archive 路径文件"（同 inode）；当本 round worktree 在分支 feature/phase1-hardening-... 上时，这些主区 archive .md 文件被修改但 git 不追踪（.git/info/exclude *.md），主区 working tree 没有 staged diff — **看似 OK 实际有边界 case**：如果主区当时正在跑别的 round 的 git operation，主区 working tree 可见性会受影响

**memo 同步检查**：

- W3 archive review-l2-opus-a.md / -b.md 已 dump 在主区 — 本 round 工件 ✓ 引用
- W2 archive review-l2-opus-a.md / -b.md 已 dump — 本 round 工件 ✓ 引用
- W4 archive 没有 review trail md（mimo 跳过 L2，本 round task 6.3 把 review_findings_l2 写进 metrics.yaml 即可）
- CLAUDE.md `v3 高频盲点 #16` 加入了"跨 round 共享字段扩展未触发已合回 round drift mini-review" — 本 round 是首例落地；**工件 spec.md:175-177 反例 scenario "跨 round 字段扩展未走 #16 流程必须触发 review" 是合规但不可执行**（"L2 review 期 MUST 抓到 governance violation" — review 期人工抓不能自动化 enforce） — v3 高频盲点 #3 (不可执行测试) 又来一次

---

## 是否放行

**NO**——本轮无法 plateau（large 复杂度需要 3-5 轮，此轮 P0 揭示设计骨架有错位）。

**立即修清单（启动 R2 review 前必修）**（≤ 5 条）：

1. **P0-1**：proposal Why §"silent canvas 外渲染" 描述加 caveat (当前生产代码 line 151 守卫已存在)；spec C1 反例 scenario 改写为触摸 lapDurationMs=1L surprise；tasks 3.1 done condition verify 当前代码状态
2. **P0-2**：tasks 6.5 (E5) 改"修订 W4 archive tasks.md §12" 为"新增 §11 归档后状态" + verify W2 archive 是否有同名约定 → 统一命名
3. **P0-3**：spec.md:73-75 (B 类 flags grep gate) + tasks 4.2 done condition 把字面量统一为 `flags = if (alpha < 0.5) s0.flags else s1.flags`（与生产代码变量名 s0/s1 一致）；加严"等价三元表达式 + flags"宽松规则
4. **P0-4**：design Decision 7 加新选项 E (`/opsx:ff` skill 自动加 metrics.yaml schema 字段) 并审视是否选；transparently 把 Decision 7 选项 C 标记为"本 round 不 demonstrate F1 — 留待后续 round 自然 trigger"；Risk 2 mitigation 加严 "CC apply 期 per-task drift 自查"
5. **P1-1**：spec.md:20-28 边界 clamp 反例 scenario + tasks 4.4 case G 加 sub-G4/G5 锁 clamp / 精确命中路径

**Follow-up backlog 清单**（不阻塞但需沉淀）：

- P1-2 spec α=0.5 边界 case G2.5（小修）
- P1-3 tasks 2.4 done condition 加严 grep 0 命中 `var segmentStart = -1`（小修）
- P1-4 把 Decision 5 降级为 OQ5（编辑工件结构）
- P1-5 spec.md "D2 加严" 改 "Tasks 5.2 加严"（命名清晰化）
- P1-6 cross_round_field_drift_resolved schema 含义 documented + 已归档 round 是否回填 OQ
- P1-7 metrics.yaml 字段值改 enum（NONE / List）
- P1-8 Decision 6 列出 W1 后所有 consumer round 表格
- P2-1 tasks §0 启动检查清单
- P2-2 spec scenario 层减一条（小修）
- P2-3 tasks 3.5 加 (C) 选项 "ballpark 微测"
- P2-4 tasks 2.2 加 grep ContractTest 0 命中 59_994 / 606
- P2-5 同 P1-5

---

## Adversarial 收尾

CC 主会话工件**冗长但有结构性盲点**：
- **F1 governance 条款无法 self-demonstrate**（Decision 7 选项 C 自承认 dead）— governance 立条件依赖被 enforce，而 enforce 路径仅靠"CC 自查"——本 round 前一个 round (W4 mimo) 失败的根本原因恰恰就是 CC 不在 mimo apply 流程内 → F1 在 mimo 模式下完全 dead
- **B 类 flags drift 修订 scope 不完整**（P1-1 漏 clamp / 精确命中路径 + P1-8 漏 W2/W4 consumer round verify）
- **多处工件锚点与生产代码 mismatch**（P0-1 SpeedTimeChart 守卫 / P0-2 W4 §12 / P0-3 变量名 s_k vs s0）— 这是 v3 高频盲点 #4 (行号锚点 rebase 漂移) 在本 round 的首次显现，因为本 round 工件大量引用 W2/W3/W4 archive 文档而 archive 文档已经在 mimo 实施期与现实代码 drift

工件在"做了多少事"层面充分（22 项 P0/P1 涵盖 mimo 三个 round 的债务），但在"做对没"层面有结构性问题。尤其是 F1 governance 条款的 self-demonstrate 闭环失败——governance 立条件不能由它自己 demonstrate，应让下一个 round 自然 trigger 来证明（不是本 round 写空 list 自我闭环）。

建议 CC 主会话进入 R2 前先把**5 个立即修**全部修订，并 fresh eyes 检查"F1 governance 条款是否要从本 round 拆出来另立 round（governance-only round）"——把 mimo 债务消化和 governance 立条件分两个 round 处理可能更干净。
