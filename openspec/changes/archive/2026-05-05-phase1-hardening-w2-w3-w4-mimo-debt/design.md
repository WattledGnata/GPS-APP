## Context

Phase 1 W1-W4 全部由 mimo-v2.5-pro 实施，2026-05-04 → 2026-05-05 串行合回 `feature/track-tech-v2`。token 恢复后跑 Opus 双线 hostile L2 review 揭示 mimo 模式的结构性债务（详见 proposal Why 段）。本 round 集中消化 22 项 P0/P1 + 加 F1 governance 条款（实施期偏离 design 决策必须暂停 apply）+ 修复 v3 高频盲点 #16 实战首例（LapTelemetrySample.flags drift）。

**当前 baseline 关键事实**：

- W4 P0-1 hotfix B 改动在主区工作目录（4 字段 copy + W4_DIAG 临时 log），**未** commit；本 round 在 worktree 内推进，不动主区
- W2/W3 metrics.yaml review trail 已沉淀（user 在主区已修订，未 commit）；本 round worktree 拉 4326e11，看不到这些 metrics 修订；本 round 引用 review trail 时直接读主区 archive `review-l2-opus-{a,b}.md` absolute path
- `.git/info/exclude` 把 `*.md` 全排除：archive 目录所有 md（proposal/design/spec/tasks/review trail）不进 git，仅 metrics.yaml 进 git。本 round 在 worktree 内写的工件 md 也都是 ignored；归档时本 round metrics.yaml 进 git
- `LapTelemetrySample.flags: Int = 0` 字段由 W1 round 落地（commit `f6aed72` + `3c2f2d9` 链路），W3 `LapAlignment.kt` 的 `interpolate` / `resampleByGridFallback` 没有同步适配 — flags 字段重采样后默认 0（哨兵值）
- W4 spec R1 反例 scenario 已锁"误把 timestamp 一并替换" → 但 `LapFilterIntegrationTest` 没有真正加这个 case（mimo 跳过实施）；hotfix B 修回 4 字段后契约已对齐，本 round 加 contract test lock

**stakeholder**：

- CC 主会话（Opus）：本 round orchestration + apply 期决策
- Opus 子 agent：L1 adversarial review + L2 adversarial review
- user：F1 条款修订全局 vs 仅本工程拍板 + W4 真机 verify 拍板 + push 顺序拍板
- 已合回 round W2/W3/W4 author（mimo）：本 round 修订其工件，但不需 mimo session 参与 — 工件层修订纯文档操作

## Goals / Non-Goals

**Goals**：

1. 修复 W2 chart-and-map-components 的 8 项 P1 实施期债务（A2/A3/A4/A5/B1/C1/C2/C3）
2. 修复 W3 lap-comparison-time-align 的 4 项 P1 + 3 项 trivial 实施期债务（B2/B3/B4/D1/D2/D3）
3. 修复 W4 wire-laptime-to-gps-filter 的 5 项 P1 + metadata 补全（E1/E2/E3/E4/E5）
4. 写 F1 governance 条款（实施期偏离 design 决策必须暂停 apply 走 OpenSpec 修订）+ metrics.yaml schema 增字段 `design_decisions_diverged_during_apply` + 在本 round 内 demo 该条款（自查 + 透明 declare）
5. 修复 v3 高频盲点 #16 实战首例（LapTelemetrySample.flags drift）— W3 LapAlignment 加 flags 最近邻策略 + LapAlignmentTest 加 case G 锁
6. 重新 verify W2 design 性能 baseline（mimo 写的与生产差 15-75x 的假数据）

**Non-Goals**：

- ❌ 不动 W4 hotfix B 主区工作目录改动（hotfix B 已生效；本 round 仅锁 contract test 防回退；hotfix B 自身的 commit 由 user 拍板）
- ❌ 不动 W4_DIAG 临时诊断 log（W4 真机 verify pass 后 CC 主会话独立 strip）
- ❌ 不动 add-realtime-lap-delta round（DELTA tile prevMatchedIdx 跨 lap 未重置 → 已独立立项 `redesign-realtime-delta-projection-search`）
- ❌ 不立项 Tier 2 round（lap-detail-screen-with-cursor / lap-comparison-screen-with-cursor）— 等本 round 闭环
- ❌ 不修订全局 `~/.claude/CLAUDE.md`（Review v3 经验沉淀边界明确：先在本工程；其他工程主动来拿）
- ❌ 不改 RaceChrono BLE 协议 / telemetry binary 格式 / Room schema / replay JSON / entry sketch §1 LapTelemetrySample 类型签名（B 类是消费方修订，不动类型本身）
- ❌ 不重写 W4 design Decision 1+2（hotfix B 已修回 4 字段 = 与 design 锁死契约一致，本 round 仅在 spec R1 加 lock case）

## Decisions

### Decision 1：F1 governance 条款的精确措辞 + 写入位置

**问题**：CLAUDE.md "Review v3" 节如何措辞"实施期偏离 design 决策必须暂停 apply 走 OpenSpec 修订"？写在 §"v3 高频盲点列表"后追加 #17，还是在 "Plateau 判定" 节后单独成节？

**选项 A**：作为 v3 高频盲点 #17 追加（统一在盲点表）
- 优点：统一沉淀点，CC 自查盲点表时能扫到
- 缺点：盲点表是"审查时查"的清单，不强调"实施期立即暂停"；条款的"暂停 apply"是 actionable directive 不是审查清单项

**选项 B**：在 "Review v3 流程结束信号 + apply 启动条件" 节追加
- 优点：本节已在讨论 apply 期 / L2 review 流程；自然衔接
- 缺点：本节侧重"流程结束 / 启动"，"中途暂停"不在原本 scope

**选项 C**：单独成节"实施期 design drift 处置流程"（在 "v3 高频盲点列表" 之前）
- 优点：actionable directive 独立成节，强调严肃性
- 缺点：CLAUDE.md 已经很长，新增一节增加阅读负担

**选项 D**：作为 #17 追加 + 同时在 "Review v3 流程结束信号 + apply 启动条件" 节加 cross-reference
- 优点：盲点表统一沉淀 + 流程节有 actionable 指引；双锚点
- 缺点：略冗余

**决议**：**选项 D** — 主条款写入"v3 高频盲点列表" #17，附带 actionable directive 子条目（"暂停 apply / 走 OpenSpec 修订流程 / metrics.yaml 显式声明"）；在"Review v3 流程结束信号 + apply 启动条件"节加 cross-reference link "MUST 同时遵守 #17 实施期 design drift 处置"。

**rationale**：盲点表已经是 CC 主会话每次 round apply 启动前的自查清单，新条款放盲点表内最容易被自查触发；流程节的 cross-ref 兜底"启动 apply 后中途发生 drift 的处置"。

**rejected**：选项 A 单点不够（没有 actionable）；选项 B 不够 prominent；选项 C 增加读取负担。

### Decision 2：LapTelemetrySample.flags 重采样策略（B 类）

**问题**：W3 `LapAlignment.interpolate` 把网格点 `d* ∈ [d_k, d_{k+1}]` 重采样为 sample 时，`flags: Int = 0` 字段如何处理？

**选项 A**：默认 0（当前行为，mimo 跳过 → flags 默认 0 哨兵）
- 缺点：v3 #6 + #16 实战，UI 层若用 flags 做"是否手动标记"判断会全部错认

**选项 B**：最近邻 — `if (alpha < 0.5) s_k.flags else s_{k+1}.flags`
- 优点：与 R6 现有 bearingDeg 最近邻策略一致；语义保持原 sample 标记不丢
- 缺点：flags 是 bitmask（Int），最近邻可能让某些标记位"间歇消失"（如 bit0=valid_speed 在 grid 上每隔几个点消失）

**选项 C**：双边 OR — `s_k.flags or s_{k+1}.flags`
- 优点：bitmask 标记保留性最强（任一端有标记 → 网格点也有）
- 缺点：违反"重采样取最近源 sample"语义；标记可能"扩散"到原本不属于的网格点

**选项 D**：最近邻 + 加注释禁止把 flags 用于 bitmask 标记
- 优点：与 bearingDeg 一致 + 透明声明语义边界
- 缺点：flags 是 Int，未来若 W1 改成 bitmask 用法 → drift

**决议**：**选项 B** — 最近邻策略，与 R6 bearingDeg 最近邻一致。

**rationale**：W1 当前 `flags: Int = 0` 仅作 placeholder（默认 0 表示"无标记"），还没有 bitmask 语义。最近邻最简单、与现有 R6 角度型字段策略一致。如果将来 W1 改 flags 为 bitmask → 触发新 round 修订重采样策略（届时再走 OpenSpec 流程，不预先猜测）。

**实施 caveat（L1 R2 P0-R2-2 修订）**：本 design Decision 2 用数学符号 `s_k / s_{k+1}` 描述策略——**实际生产代码 `LapAlignment.kt:179-202` `interpolate(s0, s1, alpha, lapStartWallClock)` 函数变量名是 `s0 / s1`**。spec 反例 scenario 中的 grep gate 字面量 MUST 用 `s0 / s1`（与生产代码一致）；本 design 数学符号 `s_k / s_{k+1}` 仅用于 normative 策略描述，apply 期写代码 MUST 映射到 `s0 / s1`。同款约定 apply 到 R6 字段表所有字段（bearingDeg / accelerationG / speedKmh / lat / lon）— 详见 W3 spec.md 字段表前 global caveat。

**rejected**：选项 A（哨兵风险 + v3 #6）；选项 C（语义错：bitmask 标记可能扩散到原本不属于的网格点）；选项 D（过度防御 — 未来 round 走流程即可）。

### Decision 3：computeAccelSegments 纯函数抽取位置（A4/A5/C3）

**问题**：A4/A5/C3 都需要"基于 sample 列表 + accelerationG nullable 计算 IntRange list（segment 列表）"的纯函数。该函数放哪里？

**选项 A**：抽到 `AccelTimeChart.kt` 同文件 `internal fun`
- 优点：与 R10 现有"每组件 MUST 提供至少 1 个内部纯函数"模式一致
- 缺点：函数对 SpeedTimeChart 也可能有用（partial-null 跳点策略对 speedKmh 非 null 字段不需要，所以这条不适用）

**选项 B**：抽到独立 utility 文件 `feature/test/.../ui/components/ChartSegments.kt`
- 优点：未来扩展（如多字段 segment 计算）方便
- 缺点：W2 round 全部组件都是 self-contained，新增 utility 文件违反"避免污染"原则；当前只 AccelTimeChart 用

**决议**：**选项 A** — 抽到 `AccelTimeChart.kt` 同文件 `internal fun computeAccelSegments(samples: List<LapTelemetrySample>): List<IntRange>`。

**rationale**：与 R10 一致；当前唯一 caller 是 AccelTimeChart 自己；未来若需复用走重构（OpenSpec round）。

**rejected**：选项 B（YAGNI + 污染）。

### Decision 4：GrepGateTest §8.4 滑窗修订算法（C2）

**问题**：当前 §8.4 用 300 字符 contextWindow 跨多 Text 块 trivially-pass。如何修订？

**选项 A**：Per-Text 块栈式匹配 — 找 `Text\(` 起始 → 用 paren balance 算法找闭合 `)` → 验证块内含 maxLines + Ellipsis
- 优点：精确锁定 per-Text-call scope；不会跨 Text 命中
- 缺点：Kotlin `Text(...)` 内可能含嵌套 `Text(` 调用（如 buildAnnotatedString 内 Text）→ paren balance 可能出错

**选项 B**：单 Text 紧随 N 行 maxLines/overflow 字面量
- 优点：实施简单
- 缺点：N 太小漏报、N 太大 trivially-pass

**选项 C**：组合 — Per-Text 栈式 + N 行 fallback（双层防护）
- 优点：双层抓回退
- 缺点：实施复杂

**决议**：**选项 A** — Per-Text 块栈式匹配，并在测试代码内显式 caveat "单文件单 Text 调用 + 不嵌套 Text"。

**rationale**：W2 4 个组件中没有嵌套 Text 调用（grep 验证）；选项 A 精确性最高；如果未来嵌套 Text 引入 → 触发新 round 升级算法。

**rejected**：选项 B（N 选择困难）；选项 C（YAGNI + 实施复杂）。

### Decision 5：W4 metrics.yaml `actual_days` 补全策略（E3）

**问题**：W4 mimo 实施时间不可知（mimo session 没记录），actual_days 怎么填？

**选项 A**：填 `null` + comment "mimo session 不可知"
- 优点：透明
- 缺点：metrics.yaml 后续聚合分析时缺数据

**选项 B**：估算 0.5（与 estimated_days 一致 + comment 估算依据）
- 优点：保留数据
- 缺点：精度低

**选项 C**：估算 1.0（mimo 实施 + hostile L2 review + hotfix B 整体投入）+ comment 估算依据
- 优点：包含 review 时间，metrics 反映"完整闭环投入"
- 缺点：与 estimated_days 0.5 比偏离 2x（mimo 模式效率问题透明声明）

**决议**：**选项 C** — 估算 1.0 + comment "mimo 实施约 0.3d + L2 hostile review 0.4d + hotfix B 0.3d 整体闭环"。

**rationale**：metrics 用于后续 retrospective（CLAUDE.md 约定每 5 round 跑一次），完整闭环时长比"仅 mimo 实施时间"更有信号；comment 拆分项让 retrospective 看清"mimo 模式 review 成本占比"。

**rejected**：选项 A（数据缺失）；选项 B（不含 review）。

### Decision 6：跨 round 共享字段扩展（v3 #16）的 follow-up 触发条件

**问题**：CLAUDE.md #16 已写"消费此字段的已合回 round 列表"约束，但本 round 自身就是 v3 #16 实战首例 — 是否在本 round design 里再细化？

**选项 A**：本 round 设计阶段不细化 — #16 措辞已够，留给后续 round 实战累积
- 优点：避免过度设计
- 缺点：本 round 是首例，缺 worked example 后续 round 可能误用

**选项 B**：本 round design 加 §"#16 实战示范"，以本 round 为例展示完整流程：发起字段扩展的 round → design 决策段列消费方 round → apply §10 backlog 加 follow-up trigger → 后续 hardening round 修复 + verify
- 优点：worked example 沉淀
- 缺点：design 文档变长

**决议**：**选项 B 的轻量版** — 不在 design 加新章节，但在 metrics.yaml 归档时增加字段 `cross_round_field_drift_resolved` 显式声明。worked example 自然落到 archive（review trail + tasks 记录）就够。

**rationale**：design 文档已经较长；archive 自然是 worked example 沉淀点；metrics 字段让后续 retrospective 能聚合。

**LapTelemetrySample.flags 全部 producer + consumer 列表（L1 R2 P1-R2-2 加严，W4 binary writer 残漏）**：

| Round | 角色 | 路径 | 本 round 处置 |
|---|---|---|---|
| W1 `lap-data-readers` | 类型 producer + binary reader | `core/domain/.../model/LapTelemetry.kt:21` `flags: Int = 0` 字段 + `core/data/.../repository/TelemetryRepository.kt:295` `flags = sample.flags` | ✓ 已落地（W1 commit `f6aed72` + `3c2f2d9`） |
| W2 `chart-and-map-components` | 字段清单 grep gate consumer | `feature/test/.../GrepGateTest.kt §8.7` 字段清单 grep | **本 round B1 修订** — GrepGateTest 加 `val flags: Int` 字面量验证 |
| W3 `lap-comparison-time-align` | LapAlignment.interpolate consumer | `core/domain/.../usecase/LapAlignment.kt:179-202` interpolate / clamp / 精确命中 / resampleByGridFallback | **本 round B2 / B4 / P1-1 修订** — 加最近邻策略 + 反例 scenario lock |
| W4 `wire-laptime-to-gps-filter` | binary writer producer | `feature/test/.../viewmodel/TestSessionViewModel.kt:856` `TelemetrySample(tsDeltaMs, lat, lon, speedKmh, bearingDeg)` **不传 flags 字段** → binary writer 永久写 0 → reader 永久读 0 | **本 round 不修，deferred to Phase 2**（rationale 见下方）|

**W4 binary writer 残漏 rationale（不修原因）**：(1) flags 信号**源**不在本 round scope —— W4 collect block 内 `gpsData: GpsData` 类型来自 BLE GPS 协议，**协议本身无 flags 字段**（参 RaceChrono BLE protocol 主包 20 bytes 编码）；(2) flags 是"应用层标记"概念（如 cursor 高亮、用户手动 mark），需要由 UI 层/事件层产生 → 当前 Phase 1 baseline 没有这种事件；(3) 强行在 W4 writer 加 `flags = 0` 仅是显式重申默认值，对哨兵风险无 mitigation；(4) 真正解决路径在 Phase 2 加"用户标记事件 → flags bitmask 写入 binary"链路，跨 round / 跨模块。

**本 round 处置**：cross_round_field_drift_resolved 字段值显式包含 W4 unfixed 状态：`["LapTelemetrySample.flags: W1 producer (binary reader) + W2 grep gate consumer (this round B1) + W3 LapAlignment consumer (this round B2/B4); W4 binary writer permanent default 0 (unfixed, deferred to Phase 2 — flags signal source not yet defined in BLE GPS protocol)"]`。这符合 #16 "发起字段扩展的 round design 决策段列消费此字段的已合回 round 列表" — 但责任放修复方 round（本 round），与 #16 normative（责任放发起方 W1）的差异在 OQ6（见下）讨论。

**rejected**：选项 A（首例无沉淀）；选项 B 完整版（过度设计）。

### Decision 7：F1 条款本 round 内是否人为暂停 apply 触发演练

**问题**：F1 条款"实施期偏离 design 决策 MUST 暂停 apply 走 OpenSpec 修订"是 actionable governance。本 round 自身是否要人为制造一次"design drift" demo 该流程？

**选项 A**：不人为制造，仅写条款 — 等后续 round 自然 trigger
- 优点：避免人为污染本 round scope
- 缺点：条款 dead 时间长（直到下次 trigger）

**选项 B**：人为制造一次 — 在 apply 中段假装"发现 B 类 LapTelemetrySample 还需要扩展 X"，触发暂停 → mini-proposal → resume
- 优点：worked example 立刻沉淀
- 缺点：本 round scope 已 large；额外 demo 增加复杂度；user 已明确"严禁 mimo 模式"

**选项 C**：自然 trigger — apply 期间真的发现 design drift（如 B 类某字段需求扩展）就走流程；没发现就 metrics.yaml 显式声明 `design_decisions_diverged_during_apply: []`
- 优点：真实场景；不污染 scope
- 缺点：本 round 可能没 trigger → governance 条款落地但无 worked example

**决议**：**选项 C 加 transparency**（L1 R1 P0-4 修订）— 自然 trigger；metrics.yaml 显式声明实施期是否有 drift；如果有，走 OpenSpec 修订；如果没，metrics.yaml 字段为 `[]`。**transparently 承认**：本 round 自身不 self-demonstrate F1 — F1 真 worked example 取决于后续 round 自然 trigger 实施期 design drift 时是否真的暂停 apply。**本 round 仅做三件 governance 落地工作**：(1) CLAUDE.md "v3 高频盲点列表" 写入 #17 条款 + actionable directive；(2) metrics.yaml schema 加 `design_decisions_diverged_during_apply` + `cross_round_field_drift_resolved` 字段；(3) F1 条款本身要求"CC 主会话每次 apply 启动前 MUST grep 自检 round design 当前所有 Decision id；apply 期每完成 1 task 后 MUST 与 design Decision 比对，发生 drift 立即暂停"——把"自查"具体化到 actionable sub-step，避免变成抽象 self-check。

**rationale**：governance 条款应作为后续 round 的实施期 self-check 锚点，本 round 仅作 schema + 措辞写入。metrics.yaml 字段是 governance 落地的 verify 锚点（无论 `[]` 还是非空都证明"CC 主会话有自查"），但需要清楚区分"`[]` 表示已自查无 drift"vs"字段未定义表示未自查"。本 round 通过把字段 schema 加到 CLAUDE.md L2 metrics.yaml 模板里，让后续所有 round 默认带这两字段（缺失即 schema-violate）来 enforce。

**P0-4 caveat（governance 立条件局限）**：F1 条款 enforce 路径仅靠"CC 自查"——本 round 上一个 round (W4 mimo) 失败的根本原因恰恰是 CC 主会话不在 mimo apply 流程内 → F1 在 mimo 模式下完全 dead。本 round 无法**根治**该缺陷（要根治需 `/opsx:ff` skill 自动注入字段 + apply 期工具化抓 drift），属于"future enhancement"作为选项 E 列入 OQ 段供后续 round 评估；本 round 仅作 baseline 落地。

**rejected**：选项 A（条款 dead 完全无 actionable directive）；选项 B（污染 scope + mimo 模式禁止）。

**deferred 选项 E**（OQ5 见下）：`/opsx:ff` skill 自动加 metrics.yaml schema 字段到默认骨架 + apply 期工具化抓 drift（如 commit-time hook grep 已修改 task vs design）。把 governance 落到工具自动化层而非"自查"，但需要单独立 round 改 OpenSpec CLI 行为，不在本 round scope。

## Risks / Trade-offs

**Risk 1**：B 类 LapAlignment.kt 改动涉及已合回 round（W3） → Tier 2 round 行为可能 drift
- **Mitigation**：B4 case G 单测锁 flags 重采样最近邻语义；本 round 在 W3 archive 工件层加 follow-up note 标记"v3 #16 已修复 by phase1-hardening-w2-w3-w4-mimo-debt"；apply 期跑 `:core:domain:test` 全绿
- **残留风险**：Tier 2 round 立项时若没读 W3 archive note → 可能不知 flags 已经按最近邻处理。**Mitigation**：本 round metrics.yaml `cross_round_field_drift_resolved` 字段 + Phase 1 看板更新

**Risk 2**：F1 条款本 round 内若没真的 trigger → governance 条款"无 worked example"
- **Mitigation**（L1 R1 P0-4 加严）：(a) metrics.yaml `design_decisions_diverged_during_apply` 字段透明声明（即使为 `[]`）；(b) **CLAUDE.md F1 条款体内 MUST 写入 actionable directive 子条目**：「CC 主会话每次 apply 启动前 MUST grep 自检 round design 当前所有 Decision id 列表；apply 期每完成 1 task MUST 与 design Decision 比对（"该 task 是否仍按 Decision N 实施" / "实施期是否需要修订 Decision N"），发生 drift 立即暂停 apply 写'实施期决策修订 §'或起 mini-proposal → review 后 resume」；(c) 下次 round 自然 trigger 时立即 demo
- **残留风险**：**中**（L1 R1 P0-4 修订）— governance enforce 仅靠 CC 自查；W4 mimo 模式失败的根本原因是 CC 主会话不在 apply 流程内，本 round F1 条款在 mimo 模式下仍然 dead；根治路径在 OQ5（`/opsx:ff` skill 工具化 + commit hook drift 自动抓），但 OQ5 不在本 round scope。本 round 仅做 baseline 落地（条款 + schema + actionable directive 措辞），不claim self-demonstrate

**Risk 3**：A2 mockSingleLap 公式改 → W2 已有 ContractTest 可能 expected 值 drift
- **Mitigation**：apply 阶段先重跑 W2 ContractTest 全部，发现哪个 expected 值依赖 `60_000/99 = 606` 整除 → 同步更新；spec scenario 已写新公式 `(i * lapDurationMs) / (n-1)` 作为 lock
- **残留风险**：低 — ContractTest 没多少 case 直接依赖 `606` 字面量，主要是 sample[99].elapsedMsInLap == 60_000 的断言会从"≈ 59_994"改成"严格 60_000"

**Risk 4**：C1 SpeedTimeChart n=1 守卫从 silent 改 placeholder → 视觉 spec 改变
- **Mitigation**：MODIFIED R3 spec scenario "n=1 单 sample" 改写为新行为（占位文字 "NO DATA"）；W2 SpeedTimeChartContractTest 加 case 锁
- **残留风险**：极低 — n=1 是边界 case，生产环境很少触发；视觉变化（点 → 占位文字）反而更安全

**Risk 5**：C2 GrepGateTest §8.4 paren balance 算法可能在嵌套 Text 调用时出错
- **Mitigation**：测试代码内 caveat "单文件单 Text 调用 + 不嵌套 Text"；apply 期 grep `Text\(` 验证 4 组件文件无嵌套
- **残留风险**：未来引入嵌套 Text → 触发新 round 升级算法（OpenSpec 流程）

**Risk 6**：C4 W2 design 性能 baseline 重 verify 需要跑实际 SpeedTimeChart 微测
- **Mitigation**：实施期跑 `androidTest` 或 JUnit + Robolectric 微测；如果环境不支持 → design 透明声明"无性能 baseline，由 Tier 2 真机首次签收"
- **残留风险**：低 — 性能 baseline 不是 release blocker；可以 transparent 声明 fallback

**Risk 7**：本 round 修订 W2/W3/W4 archive 工件 md 都是 ignored，归档 commit 仅 metrics.yaml 进 git
- **Mitigation**：archive 工件 md 在 worktree 推进 + 人工 cp 到主区 archive 目录（与现有 review trail 同位置）；git 视角上看 commit chain 仍是干净的（仅 metrics.yaml diff）
- **残留风险**：md 不进 git 是工程约定（CLAUDE.md "多 change 并行协同" 节明示）；本 round 不挑战该约定

## Migration Plan

**Apply 顺序（按依赖关系）**：

1. **F1 governance 落地**（CLAUDE.md + metrics.yaml schema）— 先 land governance 条款，让本 round 自身实施期能依赖该条款做 self-check
2. **A 类 W2 spec hardening**（A1/A2/A3/A4/A5）— 改 spec 工件 + MockTelemetry + AccelTimeChartContractTest + SpeedTimeChartContractTest
3. **C 类 W2 silent bug 修复**（C1/C2/C3/C4）— SpeedTimeChart 守卫 + GrepGateTest 修订 + AccelTimeChartContractTest IntRange 真断言 + design 性能 baseline 重 verify
4. **B 类 LapTelemetrySample.flags drift 修复**（B1/B2/B3/B4）— GrepGateTest 加 flags + LapAlignment 加最近邻 + design D6 + spec R6 字段表 + LapAlignmentTest case G
5. **D 类 W3 trivial**（D1/D2/D3）— LapAlignment.kt 删死参数 + LapAlignmentTest 加 case bearingDeg/elapsedMsInLap 边界
6. **E 类 W4 hotfix 后 P1**（E1/E2/E3/E4/E5）— LapFilterIntegrationTest 加 timestamp/binary case + LapLiveStateDeriver 注释 + W4 metrics.yaml 补全 + W4 archive design/spec/tasks sync

**测试 gate**：

- 单元测试：`./gradlew :feature:test:testDebugUnitTest :core:domain:test` 全绿
- Grep gate 测试：`GrepGateTest` 全绿（含 §8.4 paren balance 算法 + §8.7 flags 字段）
- 真机验证：vivo V2405A 小屏 verify C1 SpeedTimeChart n=1 占位文字不换行 + A4/A5 acceleration null/partial-null Compose preview 行为

**Rollback strategy**：

- 工件层（spec/design 修订）：直接 revert worktree commit
- 代码层（SpeedTimeChart 守卫 / LapAlignment flags 最近邻 / GrepGateTest 修订）：revert 单 commit
- F1 governance 条款：revert CLAUDE.md commit
- W4 metrics.yaml 补全：revert 单 commit（不影响 W4 archive 现有 commit chain）

**真机验证串行**：W4 hotfix B 主区改动等用户拍板真机 install；本 round C1/A4/A5 真机 verify 等 W4 真机 verify 完成 + user 放行。

## Open Questions

**OQ1（已决议）**：F1 条款是否同步写到全局 `~/.claude/CLAUDE.md`？
- **决议**：不写 — CLAUDE.md "经验沉淀边界" 节明示"Review v3 经验先沉淀在本工程；其他工程主动来拿"。本 round 仅写本工程 CLAUDE.md。

**OQ2（已决议）**：W4 metrics.yaml `actual_days` 估算依据
- **决议**：见 Decision 5，估算 1.0 + comment 拆分项

**OQ3**：W3 archive 工件层是否要写"v3 #16 已修复 by phase1-hardening-w2-w3-w4-mimo-debt" follow-up note？
- 倾向：写在 W3 archive `tasks.md §10 backlog`（与 W3 现有 `lap-alignment-trajectory-divergence-warning` follow-up memo 同位置）；不进 git（md ignored），但本地 archive 沉淀

**OQ4**：本 round 是否在 metrics.yaml 增 `cross_round_field_drift_resolved` 字段？
- 倾向：是（见 Decision 6）；retrospective 时聚合"v3 #16 触发频次"

**OQ5（L1 R1 P0-4 deferred）**：F1 governance 条款 root-cause 修复路径 — `/opsx:ff` skill 自动注入 metrics.yaml schema 字段 + commit-time hook grep 已修改 task vs design 自动抓 drift。把 governance 落到工具自动化层而非"CC 自查"。
- 倾向：本 round 不做（涉及修改 OpenSpec CLI 行为 + 加 git hook，scope 偏大）；立 follow-up round `automate-design-drift-detection`（medium 复杂度），等本 round / 下一个 mimo-style round 自然 trigger 时启动
- 备注：在本 round 执行期间 user 拍板 A 路径（本 round 不拆 governance-only round）；OQ5 是 P0-4 残留风险的 long-term 修复路径

**OQ6（L1 R2 P1-R2-2 / memo 同步检查 deferred）**：v3 高频盲点 #16 normative 把"列消费方 round 列表"责任放在**发起字段扩展的 round（W1）**，但本 round 把责任放在**修复方 round（本 round）**——责任错位。是否应该 retroactively 让 W1 archive design.md 加"消费此字段的已合回 round 列表"？
- 倾向：是 — W1 已合回但 archive design.md 不进 git（md ignored）所以 retroactive 修订成本极低；可在本 round task 11.1 顺带做一行 cross-link
- 影响：W1 archive design.md（本地修订）+ CLAUDE.md #16 实战来源段加 update note "Phase 1 hardening round phase1-hardening-w2-w3-w4-mimo-debt 已修复 W3 LapAlignment.interpolate 路径；W4 binary writer 仍永久默认 0 (deferred to Phase 2)"
- 决议：**是** — 加进 task 11.1 范围 + 加 task 1.4 update CLAUDE.md #16 实战来源段
