# L1 Adversarial Review — Opus 子 agent R2（phase1-hardening-w2-w3-w4-mimo-debt）

> 触发时机：2026-05-05 CC 主会话已修订 R1 5 项立即修后，进入 R2 plateau 探测
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> 复杂度：large（22 项 P0/P1 + 跨 3 capability + governance 条款 + 跨 round 共享字段 drift）
>
> 本轮重点：**修订残留 verify + 形态变化连锁 + memo 同步初查**（CLAUDE.md "Review v3 各轮关键差异化角度"表，第 2 轮）
>
> 工件作者：CC 主会话（Opus）

---

## 总结

| 优先级 | 数量 | 摘要 |
|---|---|---|
| **P0** | 2 | P0-1 修订漏 cursor 渲染分支 silent canvas 外 / B 类 design.md Decision 2 没同步 R1 P0-3 caveat（design ↔ spec 不一致） |
| **P1** | 5 | W3 spec 字段表其他字段（bearingDeg / accelerationG）未同步 s_k→s0 caveat / R1 P1-8 升级（W4 binary writer 不写 flags 默认 0 是 silent 信号丢失，scope 残漏）/ tasks 5.2 / 5.3 case H/I 与现有 caseD1-D5 命名风险（R1 P1-5 backlog 未实施）/ tasks 1.1 done condition `MUST grep 自检` 字面量过宽 / spec 反例 scenario 锁 `samples[kMin]` 字面量但 grep gate 没在 done condition 里写明 |
| **P2** | 4 | W3 spec scenario "α=0.5 边界" R1 P1-2 backlog 未实施 / R1 P1-3 grep 0 命中 segmentStart 未实施 / proposal §"为什么是现在 (c)" 已加 transparency 但 §a/§b 句首仍读起来像"立刻收益" 修辞反差 / metrics.yaml schema NONE vs List enum 仍是 list（R1 P1-7 未实施） |

**总：2 P0 + 5 P1 + 4 P2 = 11 项**（R1 是 17 项，R2 减少但**仍有结构性 P0 漏修**）

**plateau 信号**：**NO** — 本轮发现 2 个新 P0（其中 1 个是 R1 P0-1 修订未覆盖的 cursor 渲染分支，1 个是 R1 P0-3 修订引发的 design ↔ spec 不一致连锁）+ 5 个 R1 backlog 未升级或未修。建议至少 R3 收尾。

---

## P0 发现

### [P0-R2-1] R1 P0-1 修订漏 cursor 渲染路径 — n=1 cursor line silent canvas 外

**位置**：
- 工件：proposal.md:41 / spec.md:13-17 / tasks 3.1 / 3.2
- 生产代码：`feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt:161-167`

**现状**：R1 P0-1 修订动作锁了两条路径：(a) 触摸 callback 不调 onCursorChange / (b) Composable 体内 early-return placeholder 避免 chart line 渲染。但 SpeedTimeChart.kt 还有**第三条 silent canvas 外渲染路径**：

```kotlin
// SpeedTimeChart.kt:161-167
if (cursorAbsoluteTs != null) {
    val cursorIdx = samples.indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }
    if (cursorIdx >= 0 && cursorIdx < coords.size) {
        val cursorX = coords[cursorIdx].x  // <-- coords[0].x 由 computeChartCoordinates 计算
        drawLine(TrackTechColors.Purple, Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 1f)
    }
}
```

`coords[0].x` 由 `computeChartCoordinates` (line 57-78) 计算，公式 `x = (sample.elapsedMsInLap / bounds.lapDurationMs) * canvasSize.width`。当 n=1 + samples[0].elapsedMsInLap > 0：
- `computeChartBounds` (line 51-54) 返回 lapDurationMs = 1L（n=1 走 `else 1L` 分支）
- `coords[0].x = elapsedMsInLap_30000 / 1L * canvasWidth_500 = 15_000_000 px` → 远超 canvas（**视觉 silent canvas 外渲染 — 与 W2 review-l2-opus-a P1-1 line 65 描述完全一致**）

如果外部 cursorAbsoluteTs 通过其他 chart 同步到 samples[0].absoluteTsMs（多组件 cursor 联动场景必然发生）→ cursor line 在 canvas 外画 1 px 紫色竖线（不可见但 GPU draw call 触发 + 占据 detector 注册）。

**问题**：
1. R1 P0-1 修订动作的 (a) early-return placeholder（与 isEmpty 同分支）实际上**会**避免 cursor 渲染分支（因为 early-return 直接 `return@Box` 跳过整个 Canvas），所以**修订动作本身能 cover** — 但 spec.md:13-17 / tasks 3.1 done condition 没显式列 cursor 渲染分支作为反例 scenario，**测试也没锁**这条路径
2. tasks 3.2 (C1 测试) 仅锁 `computeChartCoordinates 返回 emptyList` + 触摸 callback 计数=0，**没锁** cursor 渲染分支：当 n=1 + cursorAbsoluteTs == samples[0].absoluteTsMs 时 cursor line 是否被画 / 画在哪里
3. R1 P0-1 修订把 W2 review-l2-opus-a P1-1 原始描述（line 65 "x = 30_000_000 px 远超 canvas"）当作"已被 line 151 守卫避免"忽略，但 line 151 守卫只保护 chart line 渲染分支，**cursor 渲染分支没保护**

如果实施期 CC 选择修订动作 (b)（触摸路径在 size <= 1 时 return）而非 (a)（Composable 体内 early-return）→ chart line 不画 + 触摸 callback 不触发 + **cursor line 仍 silent canvas 外渲染** — 这是 R1 P0-1 修订未根除的 silent path。

**修订建议**：
- (a) spec.md:13-17 加新反例 scenario "n=1 + cursorAbsoluteTs 匹配 → cursor line MUST NOT 画在 canvas 外"
- (b) tasks 3.1 done condition 加 verify "Composable 体内 early-return 必须发生在 cursor line 渲染分支之前"（即 `if (samples.isEmpty() || samples.size <= 1) { 占位文字; return@Box }` 而**不**仅在触摸 detector 内 return）
- (c) tasks 3.2 加 case "n=1 + cursorAbsoluteTs match → drawLine 调用计数 = 0 OR cursorX coerce 到 canvasWidth/2"（用 mock DrawScope 计数 drawLine 调用）

### [P0-R2-2] R1 P0-3 修订引发 design ↔ spec 形态不一致 — Decision 2 仍用 `s_k.flags / s_{k+1}.flags`

**位置**：
- 工件：design.md:76 + design.md:80（Decision 2 选项 B / 选项 C）
- 工件：spec.md:13（W3 字段表 flags 行 - R1 P0-3 已加 caveat）

**现状**：R1 P0-3 修订给 W3 spec.md:13 字段表的 flags 行加了 caveat "实际生产代码变量名是 `s0 / s1`"，并把反例 scenario grep gate 字面量改为 `s0.flags / s1.flags`。但 **design.md Decision 2 没同步**：

- design.md:76 选项 B（决议选项）："最近邻 — `if (alpha < 0.5) s_k.flags else s_{k+1}.flags`"
- design.md:80 选项 C：`s_k.flags or s_{k+1}.flags`
- design.md 整个 Decision 2 没有"实际生产代码变量名是 s0/s1"caveat

**问题**：
1. **design ↔ spec 不一致**：reviewer 读 design Decision 2 看到 `s_k / s_{k+1}` 数学符号 + 决议选 B → 实施期照 design 写代码，写出 `flags = if (alpha < 0.5) s_k.flags else s_{k+1}.flags` → 生产代码无 `s_k`/`s_{k+1}` 变量名 → **编译 fail**。CC 必须读 spec.md 才看到 caveat → 但 design 是**实施期主参考**（spec 是 normative 行为锁）
2. **CLAUDE.md 高频盲点 #15 实战**："memo 与工件不同步"——R1 P0-3 修订只更了 spec，没更 design，等价于"工件多轮 review 修订后 design 没同步"
3. **memo 同步检查**：W3 archive review-l2-opus-a.md / review-l2-opus-b.md **没有**"s_k vs s0 命名约定"的描述（R1 P0-3 是本 round R1 fresh eyes 发现），所以 design 不会"自动"从 archive review trail 拉到 caveat — 必须本 round 工件主动同步

**修订建议**：
- (a) design.md:76 选项 B 决议体内加 caveat "**实际生产代码变量名 s0/s1**（参 LapAlignment.kt:179-202 interpolate 函数签名）；spec 用数学符号 s_k/s_{k+1} 描述 normative，实施期照生产变量名 s0/s1"
- (b) design.md:80 选项 C 反例 reject 理由保持 s_k/s_{k+1} 数学符号 OK（描述被 reject 的方案不需要严格变量名），但加注 "（注：所有 design 内 s_k/s_{k+1} 数学符号在生产代码中映射为 s0/s1）"
- (c) 同步 **OQ3** "W3 archive 工件层是否要写'v3 #16 已修复 by phase1-hardening-w2-w3-w4-mimo-debt' follow-up note"是否也包含 s0/s1 命名约定 — proposal 没说，但既然 W3 archive design.md 也是 s_k/s_{k+1} 数学符号，本 round B3 task 改 W3 archive design.md 时也应同步加 s0/s1 caveat

---

## P1 发现

### [P1-R2-1] W3 spec 字段表其他字段（bearingDeg / accelerationG / speedKmh / lat / lon）也用数学符号 — R1 P0-3 修订连锁不完整

**位置**：spec.md:9-12（字段表 bearingDeg / accelerationG / speedKmh / lat / lon 行）+ spec.md:32-57（多个反例 scenario）

**现状**：R1 P0-3 修订只给 **flags 行**加了"实际生产代码 s0/s1"caveat。但其他字段同样用 `s_k.f / s_{k+1}.f` 数学符号：

```
spec.md:9   speedKmh / lat / lon | s_k.f * (1-α) + s_{k+1}.f * α
spec.md:11  bearingDeg | if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg
spec.md:12  accelerationG | (b1) 近端非 null（α<0.5 时近端 = s_k）...
spec.md:32-56  反例 scenario WHEN 描述都用 s_k.X / s_{k+1}.X 字面量
```

实际 LapAlignment.kt:185-189 `interpolate` 函数：

```kotlin
val speedKmh = s0.speedKmh * (1 - alpha) + s1.speedKmh * alpha   // line 185
val lat = s0.lat * (1 - alpha) + s1.lat * alpha                    // line 186
val lon = s0.lon * (1 - alpha) + s1.lon * alpha                    // line 187
val elapsedMsInLap = round(s0.elapsedMsInLap * (1 - alpha) + s1.elapsedMsInLap * alpha).toLong()  // line 188
val bearingDeg = if (alpha < 0.5) s0.bearingDeg else s1.bearingDeg  // line 189
```

**问题**：
1. spec 只给 flags 行加 caveat，其他字段不加，等于"flags 字段被特殊对待" — 但**所有字段都该一致**
2. tasks 4.2 done condition 也只锁 flags 字面量；如果未来某次重构把 `s0.bearingDeg` 改回 `s_k.bearingDeg` 数学变量名 → spec 反例 scenario "WHEN s_k.bearingDeg=350" 是 normative 描述，但 grep gate 没锁 bearingDeg 也得用 s0/s1 → silent regress
3. R1 P0-3 修订做的是"flags 行字面量与生产代码对齐"，但**所有字段共享同一函数 interpolate(s0, s1, ...)** → 应该是字段表层面统一加 caveat（一次性说明"以下所有字段反例 grep gate 用 s0/s1 实际变量名"）

**修订建议**：
- (a) spec.md:5-7 字段插值表前加全局 caveat "**所有字段的 normative 描述用数学符号 `s_k / s_{k+1}`，实际生产代码 `LapAlignment.kt:179-202 interpolate(s0, s1, ...)` 函数变量名是 `s0 / s1`；本 spec 反例 scenario 中的 grep gate 字面量统一用 `s0 / s1`**"
- (b) 删除 spec.md:13 的 flags-only caveat（统一移到字段表前 global caveat）
- (c) 反例 scenario WHEN 段（spec.md:32-56）保持数学符号 s_k/s_{k+1} 描述（这是 normative 数学语言），但反例 grep gate 字面量（spec.md:78 等）必须用 s0/s1

### [P1-R2-2] R1 P1-8 backlog 未升级 — W4 binary writer 不写 flags 字段是 silent 信号丢失（scope 残漏）

**位置**：
- 工件：design.md:156-172（Decision 6）+ proposal.md:32（B 类描述）+ tasks 8.3 + 11.1
- 生产代码：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:855-863`（writeSample TelemetrySample 构造）+ `core/data/src/main/java/com/blazepush/core/data/local/binary/GpsBinaryFormat.kt:75`（encodeSample buf.put(sample.flags.toByte())）

**现状**：R1 P1-8 backlog 提"Decision 6 列出 W1 后所有 consumer round 表格" — 没升级到 P0 / P1。本 R2 fresh eyes 验证 W4 binary writer 路径：

1. **写路径**（TestSessionViewModel.kt:855-862）：
   ```kotlin
   telemetryRepository.writeSample(
       TelemetrySample(
           tsDeltaMs = ...,
           lat = gpsData.latitude,
           lon = gpsData.longitude,
           speedKmh = gpsData.speed,
           bearingDeg = gpsData.bearing,
           // <-- 没传 flags 字段 → 默认 0
       )
   )
   ```
2. **binary 格式**（GpsBinaryFormat.kt:62 + line 75）：17-byte sample 含 1-byte flags
3. **读路径**（TelemetryRepository.kt:295）：`flags = sample.flags`（来自 binary sample.flags = 0 默认值）→ 装回 LapTelemetrySample.flags = 0

**问题**：
1. W4 binary writer **结构性丢弃** flags 信号——所有 lap session binary sample 的 flags byte 都是 0；后续 lap-data-readers (W1) 把 binary 重建为 LapTelemetrySample 时 flags 字段全 0 → 喂给 LapAlignment.interpolate → 走 R1 修订的 `flags = if (alpha < 0.5) s0.flags else s1.flags` 路径 → 全 0
2. R1 修订的"flags 最近邻策略"在 W4 binary 路径上**永远 noop**（因为源数据 flags 全 0），等价于"修了一个不会被触发的 P1"
3. UI 层若用 flags 做"是否手动标记"判断 → 全部错认为"无标记"——v3 #6 哨兵风险**未解决**
4. design.md Decision 6 + spec.md:78 + tasks 8.3 / 11.1 写 `cross_round_field_drift_resolved: ["LapTelemetrySample.flags (W1→W3)"]` 把 W3 当作唯一 consumer drift round → **漏 W4 writer + W1 binary reader**

**修订建议**：
- (a) Decision 6 加严：列出 W1 后 LapTelemetrySample 全部 producer + consumer：(W1: 类型 + binary reader 写入 flags from binary) / (W3: LapAlignment 重采样 flags) / (**W4: binary writer 不传 flags → 永久默认 0**)
- (b) 本 round 加新 task 4.5 (B5)：`feature/test/.../viewmodel/TestSessionViewModel.kt:855-862` writeSample 内加 `flags = currentSample.flags` 字段传递（如果 flags 来自 GPS data 还是其他源由 W1 / Phase 2 决定，本 round 至少加透明声明）；**或**透明声明"本 round 不修 W4 writer，因 flags 信号源不在本 round scope（GPS data 没 flags 字段）；W4 binary 永远 0 是当前 baseline，未来 Phase 2 加 flags 信号源时一并修 binary writer + reader 链路"
- (c) cross_round_field_drift_resolved 字段值改 `["LapTelemetrySample.flags: W1→W3 (resolved by this round); W4 binary writer permanent default 0 (out of scope, deferred to Phase 2)"]`

### [P1-R2-3] tasks 5.2 / 5.3 case H/I 命名与现有 caseD1-D5 冲突未解决（R1 P1-5 backlog 未实施）

**位置**：tasks 5.2 (D2 加 case H) + 5.3 (D3 加 case I) + LapAlignmentTest.kt:151, 158, 166, 175, 183（caseD1-D5 实际占用）

**现状**：R1 P1-5 backlog 提"spec.md 'D2/D3 加严' 改 'Tasks 5.2 / 5.3 加严' 命名"，CC 主会话没修。本 R2 verify：

- LapAlignmentTest.kt 现有 caseD1 / caseD2 / caseD3 / caseD4 / caseD5（占用 5 个 D 编号）
- 本 round tasks 5.2 加 case "H bearingDeg 跨 360°" → 函数名 `caseH_bearingWrap360`（R1 P1-5 建议）
- 本 round tasks 5.3 加 case "I elapsedMsInLap 浮点边界" → 函数名 `caseI_elapsedFloatBoundary`
- 本 round tasks 4.4 加 case "G flags 重采样最近邻" + sub-G1/G2/G3/G4/G5 → 函数名 `caseG1_flagsAlphaSmallTakesS0` 等

**问题**：
1. 现有 caseD1-D5 = "D 类参数检查"（refIdxNegative / refIdxTooLarge / emptyLaps / refLapSingleSample / refLapAllSamePosition）
2. 本 round 工件多次说"D 类 = W3 trivial"（指 task §5 D 类，含 D1/D2/D3 三项），但现有测试 caseD1-D5 是 spec layer 的 D 类（不是 task layer 的 D 类）—— **task 编号 vs 测试函数名 vs spec scenario 编号三套并存**
3. 实施期 CC 读 tasks 5.2 看到"caseH_bearingWrap360" → 写 `@Test fun caseH_bearingWrap360()` → review 期看 LapAlignmentTest 函数列表 caseA / caseB / caseC1-3 / caseD1-5 / caseE / caseF1-2 / caseG1-5 / caseH / caseI → 顺序看 OK 但**任何 reviewer 看到 "D 类 trivial" 描述都会先想到 caseD1-D5 是不是被改了**
4. spec.md:43, 60 仍写"D2 加严"/"D3 加严"——R1 P1-5 backlog 没实施

**修订建议**：
- (a) spec.md:43 把"D2 加严"改为"Tasks 5.2 加严"；spec.md:60 把"D3 加严"改为"Tasks 5.3 加严"
- (b) tasks 5.2 done condition 显式给函数名 `caseH_bearingWrap360`；tasks 5.3 给 `caseI_elapsedFloatBoundary`
- (c) 提议（low priority）：把"D 类 W3 trivial"在工件层改名为"D' 类 W3 trivial（tasks §5）"或"§5 W3 trivial"（与 task §5 锚定），避免 D 类编号歧义；这是 P2 改进

### [P1-R2-4] tasks 1.1 done condition `MUST grep 自检` 字面量过宽 — 实施期可绕过

**位置**：tasks.md:3（task 1.1 done condition）

**现状**：tasks 1.1 done condition：

> grep `CLAUDE.md` 出现 `#17` + `实施期偏离` + `MUST 暂停 apply` + `MUST grep 自检` + `每完成 1 task` + `actionable directive` + `governance 局限` 七个字面量

**问题**：
1. `MUST grep 自检` 字面量过短 + 不唯一 — CLAUDE.md 现有"MUST grep"字面量已多次出现（如盲点 #4 "MUST 在 tasks §1 加 grep 强制 verify 锚点" / #16 "MUST 在发起字段扩展的 round design 决策段列..."）
2. `每完成 1 task` 字面量也可能在其他 cross-reference 已存在（"每完成 1 task 后 MUST 与 design Decision 比对"是 actionable directive 子条目，但 CLAUDE.md 文档别处可能也有 "每完成 1 task" 字面量）
3. R1 P0-4 修订动作要求"actionable directive 子条目" 必须是**block 级**（多行措辞），不只是出现字面量 — 但 done condition 用"出现 7 个字面量"判定 → 实施时只要把 7 字面量散落在文档不同位置（不组成 actionable directive）也能 pass
4. 没要求"7 字面量必须聚集在同一 #17 条款 block 内" — 实施期可能在不同位置加 7 个字面量分别引用，绕过 actionable directive 的本意

**修订建议**：
- (a) tasks 1.1 done condition 改为"grep 在 #17 条款 block（从 `16. ` 之后到 `### v3 流程结束信号` 之前）内出现以下 7 个字面量 + block 内含 ≥ 5 行措辞描述 actionable directive"
- (b) 加 sub-step：tasks 1.1.a "drafted #17 条款体内文字"（在 tasks 工件内 inline 草稿措辞，这样 review 期能 verify 措辞是否真 actionable）
- (c) 加 verify 子条款 done condition："`awk '/^16\./,/^### Review v3 流程结束信号/' CLAUDE.md | grep -c 'MUST grep'` ≥ 1"（block 范围限制）

### [P1-R2-5] spec 反例 scenario 锁 `samples[kMin]` 字面量但 grep gate 未在 done condition 写明

**位置**：spec.md:84-88（精确命中重复距离区间反例）+ tasks 4.4 case G5（done condition）

**现状**：spec.md:88 反例描述：

> 同时 LapAlignment.kt 精确命中分支（line 160-164 附近 `if (idx >= 0) return samples[kMin]`）MUST 不出现 `samples[kMin].copy(flags = 0)` 字面量

但 tasks 4.4 case G5 done condition：

> 5 个新 @Test 方法 + ./gradlew :core:domain:test --tests "*LapAlignmentTest*" 全绿

**问题**：
1. spec normative 描述里有 grep gate（line 88 "MUST 不出现 ... 字面量"），但 tasks 4.4 done condition 只验测试全绿 + @Test 方法数 — **没有 grep gate 验证**
2. 类似地 spec.md:84 clamp 反例 scenario 描述：

   > 同时 grep gate 扫 LapAlignment.kt findSampleAtDistance MUST 在 clamp 边界两处出现 `return samples[0]` / `return samples[n - 1]` 字面量（恰好 2 次；命中 `samples[0].copy(flags = 0)` → fail）

   tasks 4.2 done condition 也没有 verify 这个 grep gate
3. **不可执行 spec**（v3 高频盲点 #3）软变种：spec 描述了 grep gate 行为，但 tasks done condition 没要求实施 — 等于 spec 是空头支票；只有 case G4/G5 单测在测，没有 grep gate 在长期防回退

**修订建议**：
- (a) tasks 4.2 done condition 加："grep `core/domain/.../usecase/LapAlignment.kt` 出现 `return samples[0]` 字面量 = 1 + 出现 `return samples[n - 1]` 字面量 = 1 + 0 命中 `samples[0].copy(flags = 0)` / `samples[n - 1].copy(flags = 0)` 字面量"
- (b) tasks 4.4 case G5 done condition 加："grep `core/domain/.../usecase/LapAlignment.kt` 出现 `return samples[kMin]` 字面量 = 1 + 0 命中 `samples[kMin].copy(flags = 0)`"
- (c) 把 grep gate 加到 GrepGateTest（与 §8.7 LapTelemetrySample 字段同模式）作为长期防回退

---

## P2 发现

### [P2-R2-1] W3 spec scenario "α=0.5 边界" R1 P1-2 backlog 未实施

**位置**：spec.md:62-69（case G α<0.5 / α≥0.5 反例）

**现状**：R1 P1-2 提"加 case G2.5 'α 严格等于 0.5 边界归属 deterministic'"，CC 主会话没加。当前 spec 只有 α<0.5 (case G α=0.3) + α≥0.5 (case G α=0.6) 两个反例 — 缺 α=0.5 边界 case。

**问题**：α=0.5 严格等于点的归属在生产代码 `if (alpha < 0.5) s0.flags else s1.flags` 中是 s1 (α=0.5 落到 else 分支)。如果未来重构改成 `if (alpha <= 0.5)` → 等价但**单测无法 detect**。

**修订建议**：spec.md:69 后加 case G2.5 "α 严格等于 0.5 边界 deterministic"：α=0.5, s_k.flags=1, s_{k+1}.flags=2 → 重采样 flags == 2（按 `< 0.5` 取右侧），断言 ≠ 1。tasks 4.4 加 sub-G2.5。

### [P2-R2-2] R1 P1-3 backlog 未实施 — tasks 2.4 done condition 加 grep 0 命中 `var segmentStart = -1`

**位置**：tasks 2.4 done condition

**现状**：R1 P1-3 提"既有 inline fold 必须删除 → grep 0 命中 `var segmentStart = -1`"，CC 主会话没加。当前 tasks 2.4 done condition：

> grep AccelTimeChart.kt 出现 `internal fun computeAccelSegments` 字面量 + Composable 体内出现 `computeAccelSegments(samples)` 调用 + 单元测试 5 case ... 全绿

**问题**：done condition 只要求"出现新函数 + 调用" → 实施时如果**新增不删旧**（既有 line 85-99 inline fold 保留 + 同时新增 `computeAccelSegments` 调用）→ 双路径并存 silent regress。

**修订建议**：tasks 2.4 done condition 加"grep `var segmentStart = -1` 命中数 = 0（既有 inline fold MUST 删除，避免双路径并存）"。

### [P2-R2-3] proposal §"为什么是现在 (c)" 修辞反差 — transparency 与 "立即收益" 句首并列

**位置**：proposal.md:20

**现状**：proposal.md:20 §"为什么是现在 (c)"：

> (c) F1 governance 条款是结构性约束，越早写入越能在后续 Phase 1/Phase 2 round 中守住 design 决策（**L1 R1 P0-4 transparency**：本 round 自身不 self-demonstrate F1 — Decision 7 选项 C 决议明示。F1 真 worked example 取决于后续 round 自然 trigger 实施期 design drift 时是否真的暂停 apply 走 OpenSpec 修订流程；本 round 仅做"CLAUDE.md 写入条款 + metrics.yaml schema 字段 + 加严 actionable directive"三件落地工作）

**问题**：句首"越早写入越能在后续 round 中守住 design 决策"是**强 claim**，括号内 transparency caveat 是**弱 claim**——并列时句首给 reviewer 留下"立即收益"印象，需要读完括号才知道"本 round 不 demonstrate"。R1 P0-4 修订加了 caveat 但没改强 claim 句首。

**修订建议**：proposal.md:20 句首改为"F1 governance 条款是结构性约束（**worked example 取决于后续 round 自然 trigger**），越早 baseline 落地越能让后续 round apply 期有自查锚点（条款生效仍依赖 CC 自查 + 不 enforce mimo 模式）"。

### [P2-R2-4] R1 P1-7 backlog 未实施 — metrics.yaml schema NONE vs List enum 仍是 list

**位置**：tasks 1.2 (CLAUDE.md schema 加字段) + tasks 8.3 (本 round metrics.yaml 写入)

**现状**：R1 P1-7 提"`design_decisions_diverged_during_apply` 字段值改 enum (NONE / List[...])"，CC 主会话没改。当前 tasks 1.2：

> `design_decisions_diverged_during_apply: []` 注释"本 round apply 期是否触发 #17 条款；空 list 透明声明无 drift"

**问题**：R1 P1-7 指出"`[]` 与字段不存在不可区分"。CC 主会话用注释"透明声明"试图回避，但**字段值 + 注释**仍是同一 list 形态——retrospective 聚合时仍无法 deterministic 区分"已自查无 drift" vs "未自查"。

**修订建议**：tasks 1.2 字段值从 `[]` 改 `NONE`（特殊标记）—— Yaml enum：`design_decisions_diverged_during_apply: NONE | List[DecisionDriftEntry]`。或保留 `[]` 但加 sister 字段 `apply_self_check_executed: true` 显式声明 self-check 跑过。

---

## §A 上轮 P0/P1 修订到位检查（R1 5 项立即修 verify）

| R1 项 | 修订动作 | 实际状态 | verify 方法 |
|---|---|---|---|
| **P0-1** SpeedTimeChart caveat + spec R3 反例 + tasks 3.1/3.2 | 加 caveat ✓ + spec R3 反例改写 ✓ + tasks 3.1 done condition 加严 ✓ | **部分 OK** — 3 路径修订只 cover chart line + 触摸 callback，**漏 cursor 渲染分支 silent canvas 外**（见 P0-R2-1） | grep proposal.md:41 "L1 R1 P0-1 修订 caveat" + spec.md:31-41 反例 scenario / 检查 SpeedTimeChart.kt:161-167 cursor 渲染分支 |
| **P0-2** tasks 6.5 改"新增 §11" + W2 §12 命名约定 | tasks 6.5 改"新增 §11" ✓ + verify W2 archive §12 存在 ✓（W2 archive tasks.md:119 实际是 §12）+ MUST NOT §12 ✓ | **OK** ✓ | grep tasks.md:61 "新增 §11" + W2 archive tasks.md grep "^## " 显示 §12 |
| **P0-3** W3 spec flags 行 caveat + grep gate s0/s1 + 双锚点 | spec.md:13 加 caveat ✓ + 双锚点 ✓ + tasks 4.2 done condition 加严 ✓ | **部分 OK** — flags 行修订 ✓ 但 design.md Decision 2 没同步（见 P0-R2-2）+ 其他字段（bearingDeg / accelerationG / speedKmh / lat / lon）行未同步 caveat（见 P1-R2-1）| grep design.md:76 "s_k.flags" 仍存在；grep spec.md:9-12 其他字段行 grep "s_k.f" 仍存在 |
| **P0-4** Decision 7 transparency + Risk 2 actionable + OQ5 | Decision 7 transparency ✓ + Risk 2 actionable directive ✓ + OQ5 加 ✓ + tasks 1.1 governance 局限透明声明 ✓ | **OK** ✓（但 tasks 1.1 done condition 字面量过宽 — P1-R2-4） | grep design.md:190 "transparently 承认" + Risk 2 mitigation actionable directive 子条目 |
| **P1-1** spec R6 边界处理 + 反例 scenario clamp / 精确命中 + tasks 4.4 case G4/G5 | spec R6 加严"对象引用而非 .copy()" ✓ + 加 2 个反例 scenario ✓ + tasks 4.4 加 sub-G4/G5 ✓ | **部分 OK** — sub-G4/G5 加了，但 grep gate 未在 done condition 写明（spec 描述 grep gate 但 tasks 没要求实施 — P1-R2-5） | grep spec.md:82-88 反例 scenario 存在 + tasks 4.4 done condition 仅 "全绿" 没 grep |

**§A 总评**：5 项 R1 立即修中 **2 项完全 OK + 3 项部分 OK**（P0-1 漏 cursor / P0-3 design 没同步 / P1-1 grep gate 未实施）。**部分 OK 比率 60%**——本 R2 应该至少把这 3 个连锁问题也修了。

---

## §B 生产代码 grep pattern 验证（R1 修订动作引入新 mismatch verify）

| 锚点 | 工件描述 | 实际生产代码 | 对齐状态 |
|---|---|---|---|
| LapAlignment.kt:179-202 interpolate flags 字面量 | spec.md:78 grep gate `flags\s*=\s*if\s*\(\s*alpha\s*<\s*0\.5\s*\)\s*s0\.flags\s*else\s*s1\.flags` | line 192-201 LapTelemetrySample 构造体内**0 行 flags 字段**（依赖默认值 0） | **MISMATCH (R1 known)** — 修订点确认 |
| design.md Decision 2 选项 B | `if (alpha < 0.5) s_k.flags else s_{k+1}.flags`（数学符号）| line 189 `bearingDeg = if (alpha < 0.5) s0.bearingDeg else s1.bearingDeg`（s0/s1）| **MISMATCH (P0-R2-2)** — design 没同步 |
| spec.md:11 bearingDeg 行 | `if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg`（数学符号）| line 189 实际 s0/s1 | **MISMATCH (P1-R2-1)** — 字段表其他字段未同步 |
| spec.md:9 speedKmh / lat / lon 行 | `s_k.f * (1-α) + s_{k+1}.f * α` | line 185-187 实际 s0/s1 | **MISMATCH (P1-R2-1)** — 字段表其他字段未同步 |
| SpeedTimeChart.kt cursor 渲染 line 161-167 | proposal.md:41 / spec.md:13-17 / tasks 3.1 cover 触摸 callback + chart line 渲染 | **没 cover** cursor line drawLine 路径 | **MISSING COVERAGE (P0-R2-1)** |
| W4 binary writer flags 字段（TestSessionViewModel.kt:855-862）| 工件 0 行检查 | TelemetrySample 构造**没传 flags** → 默认 0 写入 binary | **MISSING COVERAGE (P1-R2-2)** — R1 P1-8 升级 |
| LapAlignment.kt fallbackRefSamples 死参数 | tasks 5.1 done condition 删除 | line 120 `@Suppress("UNUSED_PARAMETER") fallbackRefSamples` 仍存在 | **OK** ✓（待 apply 期实施 D1） |
| LapLiveStateDeriver.kt:159 注释 ≥3 | tasks 6.2 改 ≥1 | line 159 仍是"≥ 3 个" + LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 1 | **OK** ✓（待 apply 期实施 E2）|
| GrepGateTest §8.4 windowed(300) | tasks 3.3 改 paren balance | line 91-104 实际 `content.substring(start, minOf(start + 300, ...))` | **OK** ✓（待 apply 期实施 C2）|
| GrepGateTest §8.7 锁 7 字段缺 flags | tasks 4.1 加 flags | line 138-150 锁 7 字段未含 flags | **OK** ✓（待 apply 期实施 B1）|
| W4 metrics.yaml actual_days = null | tasks 6.3 改 1.0 + comment | 实际 `actual_days: null  # TODO(user)` | **OK** ✓（待 apply 期实施 E3）|
| W4 archive tasks.md §11 归档后状态 | tasks 6.5 新增 §11 | 实际只有 §1-§10 | **OK** ✓（待 apply 期实施 E5）|

**§B 总评**：**6 项对齐 ✓ + 4 项 mismatch（其中 2 项是 R1 修订未根除的连锁，2 项是 R2 fresh eyes 新发现）+ 2 项 missing coverage**。修订动作多数对齐，但**形态变化连锁**未充分覆盖。

---

## §C/§D Fresh eyes 新 P0/P1 + memo 同步检查

### Fresh eyes scope 假闭环 verify

1. **R1 修订 + R2 verify 后的 scope 闭环**：
   - **n=1 silent canvas 外**：R1 修订只 cover 2 路径 → 漏 cursor 路径（P0-R2-1）
   - **flags drift 修订**：R1 修订只 cover W3 LapAlignment.interpolate → 漏 W4 binary writer（P1-R2-2）
   - **flags grep gate**：spec 描述 + tasks done condition 不一致（P1-R2-5）
   - **design ↔ spec 一致性**：Decision 2 没同步 P0-3 caveat（P0-R2-2）+ spec 字段表其他字段没同步（P1-R2-1）

2. **F1 governance 条款的 self-demonstrate 闭环**：R1 P0-4 已 transparency 处理 — Decision 7 选项 C 明示"本 round 不 self-demonstrate" + Risk 2 actionable directive 加严 + OQ5 deferred。**不再是 P0** ✓ — R2 不重新挑战该决议。

3. **B 类 LapTelemetrySample.flags drift 是否真覆盖所有 caller**：
   - W3 LapAlignment.interpolate ✓（B2 修订）
   - W3 LapAlignment.findSampleAtDistance clamp / 精确命中 ✓（R1 P1-1 修订 + 反例 scenario）
   - W3 LapAlignment.resampleByGridFallback ✓（强制 flags = 0 + design Decision 2 + spec scenario）
   - **W4 binary writer**（TestSessionViewModel.kt:855-862）✗ — 不传 flags，永久默认 0（P1-R2-2 升级）
   - W1 binary reader（TelemetryRepository.kt:295）— 读 flags 自 binary（如 binary 默认 0 则 flags=0）— 跟 W4 writer 配套：writer 不写 flags → reader 读 0 → 哨兵风险

4. **W2 archive 工件层修订是否能在 worktree 内推进**（R1 §C 检查项）：R1 已分析，结论"worktree 内 absolute path = 主区文件系统层面修改" — 不是 R2 关注点，跳过。

### memo 同步初查

- **CLAUDE.md 高频盲点 #16**（line 347）：已写入 ✓ — 但本 round 是 #16 实战首例，**memo 与本 round 工件**应有 link：
  - 本 round 修订 W3 archive 时是否在 W3 archive tasks.md §10 加 follow-up note "v3 #16 已修复 by phase1-hardening-w2-w3-w4-mimo-debt"？tasks 11.1 写要加，但 verify 是否已实施 — 待 apply 期。
  - **CLAUDE.md #16 实战来源**句尾"后续 hostile L2 review 才发现" — 是**陈述事实**，但本 round 修复后应在 #16 加 update：「Phase 1 hardening round phase1-hardening-w2-w3-w4-mimo-debt 已修复 W3 LapAlignment.interpolate 路径；W4 binary writer 仍永久默认 0（deferred to Phase 2）」 — 这是 memo 同步**缺失**项 ✗（CC 主会话工件没说 CLAUDE.md #16 要更新）

- **W3 archive review-l2-opus-a/-b**：已 dump，本 round 工件 ✓ 引用（spec.md:78 引用 LapAlignment.kt:179-202 + design 章节）

- **W2 archive review-l2-opus-a/-b**：已 dump，本 round 工件 ✓ 引用（A2 mimo intervalMs 公式来源 review-l2-opus-a P2-3）

- **W4 archive 没有 review trail md**：本 round task 6.3 把 review_findings_l2 写进 metrics.yaml — 但 R2 fresh eyes：**这等于把 review trail 沉淀点从 .md（不进 git）转移到 metrics.yaml（进 git）**，反而**比标准做法多 1 份 git history** — 可能是好事（永久沉淀）但工件没说为什么不也写一份 review-l2-opus-{a,b}.md（对称 W2/W3）

- **Decision 6 vs CLAUDE.md #16**：Decision 6 决议"在 metrics.yaml 增字段 cross_round_field_drift_resolved 显式声明，worked example 自然落到 archive 就够" — 但 #16 措辞要求"发起字段扩展的 round design 决策段列消费此字段的已合回 round 列表，并在 apply §10 backlog 加 follow-up 触发 drift mini-review" → **#16 把责任放在发起方 round（W1）**，本 round 把责任放在修复方 round（本 round）— 责任错位。**Decision 6 应额外 OQ：W1 archive design.md 是否该 retroactively 加"消费此字段的已合回 round 列表"？**（W1 已合回，按 #16 的 normative 要求应该加，但 CC 主会话没说要回填）

---

## 是否放行 + plateau 信号判断

**NO — 仍未放行**。

**plateau 信号**：**NO**

**理由**：

1. **不满足 §A 完全到位**：5 项 R1 立即修中 3 项部分 OK（P0-1 漏 cursor / P0-3 design ↔ spec 不一致 / P1-1 grep gate 未在 done condition 实施）→ 应该再修
2. **不满足 §C/§D 无新 P0/P1**：本 R2 fresh eyes 找出 **2 个新 P0**（P0-R2-1 / P0-R2-2）+ 5 个新 P1（其中 R1 P1-2 / P1-3 / P1-5 / P1-7 / P1-8 backlog 未升级或未修）
3. **形态变化连锁**：R1 P0-3 修订引发 design.md Decision 2 + spec 字段表其他字段（bearingDeg / accelerationG / speedKmh / lat / lon）没同步——典型"R1 修订漏覆盖连锁"
4. **memo 同步**：CLAUDE.md #16 实战来源段没更新本 round 修复成果；Decision 6 把责任放修复方 round，与 #16 normative（责任放发起方 round）不一致

**何时 plateau**：本 R2 立即修 5 项 + 再跑 R3 验证形态连锁完全收敛 + 无新 fresh eyes P0/P1 → R3 应能 plateau。large 复杂度 3-5 轮 plateau 范围内。

---

## 立即修清单（R3 前必修，≤ 5 条）

1. **P0-R2-1**：proposal.md:41 + spec.md:13-17 + tasks 3.1 / 3.2 加 cursor 渲染分支 silent canvas 外的反例 scenario + done condition；明确"early-return placeholder MUST 在 cursor line 渲染分支之前"+ "tasks 3.2 加 case 锁 cursor line drawLine 计数 = 0 OR cursorX coerce 到 canvasWidth/2"
2. **P0-R2-2**：design.md:76 选项 B 决议体内加"实际生产代码变量名 s0/s1"caveat（与 spec.md:13 一致）；tasks 4.3（B3 修 W3 archive design.md）也同步加 s0/s1 caveat
3. **P1-R2-1**：spec.md:5-7 字段插值表前加全局 caveat（适用所有字段）"normative 描述用 s_k/s_{k+1}，反例 grep gate 字面量统一用 s0/s1"；删除 spec.md:13 flags-only caveat 移到 global
4. **P1-R2-2**：Decision 6 加严列出 W4 binary writer 是 unfixed consumer drift（永久默认 0）+ proposal §B 加 W4 writer 残漏透明声明 + cross_round_field_drift_resolved 字段值加 W4 writer note；考虑加新 task 4.5 (B5) 锁 W4 writer flags 字段或透明声明 deferred to Phase 2
5. **P1-R2-5**：tasks 4.2 done condition 加 grep gate "出现 `return samples[0]` = 1 + `return samples[n - 1]` = 1 + 0 命中 `samples[0].copy(flags = 0)`"；tasks 4.4 case G5 done condition 加 grep gate "出现 `return samples[kMin]` = 1 + 0 命中 `samples[kMin].copy(flags = 0)`"

## Follow-up backlog（不阻塞但需沉淀）

- **P1-R2-3** tasks 5.2 / 5.3 case H/I 函数命名 显式给出（小修）
- **P1-R2-4** tasks 1.1 done condition 加 block 范围限制（block 内 grep）
- **P2-R2-1** spec.md:69 后加 case G2.5 α=0.5 边界 deterministic
- **P2-R2-2** tasks 2.4 done condition 加 grep `var segmentStart = -1` = 0
- **P2-R2-3** proposal §"为什么是现在 (c)" 修辞反差修正
- **P2-R2-4** tasks 1.2 字段值 NONE vs List enum
- **memo 同步** CLAUDE.md #16 加 update note（本 round 修复 W3 + W4 deferred）
- **OQ6** Decision 6 是否 retroactively 让 W1 archive design.md 加"消费此字段的已合回 round 列表"？

---

## Adversarial 收尾

R1 修订 5 项立即修后，工件**主体结构稳定**，但仍有结构性盲点：

- **P0-R2-1 cursor 渲染漏覆盖** — 3 路径中只修 2 路径；R1 P0-1 修订**不彻底**，cursor 联动场景 silent canvas 外渲染仍存在
- **P0-R2-2 design ↔ spec 不一致** — R1 P0-3 修订只更 spec 没更 design + 字段表其他字段未同步 caveat — 等价于"修订后 memo drift"（CLAUDE.md 高频盲点 #15 实战）
- **P1-R2-2 W4 binary writer flags 残漏** — R1 P1-8 backlog 升级到 R2 P1：本 round 修复 W3 LapAlignment 的 flags drift，但 W4 binary writer 不传 flags 字段 → 永久默认 0 → R1 修订的"flags 最近邻策略"在 W4 binary 路径上**永远 noop** → 哨兵风险未根除

工件在"做了多少事"层面充分（22 项 P0/P1 涵盖 mimo 三个 round），但**修订动作的连锁**没充分覆盖（design ↔ spec / 多字段 / 多消费方 round / 多渲染路径）。CLAUDE.md "Review v3 各轮关键差异化角度"表第 2 轮重点是"修订残留 verify + 形态变化连锁 + memo 同步初查"——本 R2 找出 2 P0 + 5 P1 + 4 P2 是**符合预期**的（large 复杂度 3-5 轮 plateau）。

建议 CC 主会话进入 R3 前先把 5 个立即修全部修订 + verify R2 §B 表所有 mismatch / missing coverage 都覆盖；R3 应该能 plateau。
