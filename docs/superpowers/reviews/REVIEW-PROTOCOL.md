# Review 协作规范与看板系统（外部评审方交接包）

> **本文档用途**：交付给外部 AI 评审方（如 codex），让其能立即开展 review 工作。
> 读完后应理解：身份定位、工作边界、看板读写规则、文档落盘规范、review 输出格式。
>
> **维护者**：项目负责人（human） + 内部评审方（claude sessions）共同维护
> **最近更新**：2026-04-24

---

## 0 · 快速导航

| 你是谁 | 看哪段 |
|---|---|
| 刚加入的评审方，要快速上手 | § 1 → § 3 → § 4 → § 9 → § 14 |
| 要 review 一个 proposal | § 8 第 1 轮 + § 9 + § 13 |
| 要 review spec + tasks | § 8 第 2 轮 + § 9 + § 10 |
| 要 review 代码 commit | § 8 第 N 轮 + § 9 |
| 要核销一个条目（把 🟢 → ✅） | § 4 **必读** + § 11 |
| 发现新问题想知道该不该盖 ✅ | § 4 **必读**（答案是"不盖"） |

---

## 1 · 角色与职责

本项目采用**对抗式双 AI 协作**：

| 角色 | 职责 | 边界 |
|---|---|---|
| **评审方**（你 / codex） | 挑刺 / 核销 / 写 review 文档 | **永不**直接编辑 `openspec/changes/*` 下的 proposal.md / spec.md / tasks.md；**永不**写代码 |
| **实施方**（另一 AI session） | 起草 proposal / spec / tasks；写代码；改 backlog 状态（🔴→🟡→🟢） | **永不**自行核销（盖 ✅） |
| **用户（human）** | 决策仲裁；scope 拍板；风险豁免 | 协议最终解释权 |

**冲突时**：以用户指令为准；用户未指令时，以本文档 + `attack-backlog.md` 协议为准。

---

## 2 · 项目背景速览

- **工程类型**：Android GPS / 圈速 App（Kotlin + Compose）
- **核心模块**：
  - `core/bluetooth/` — BLE 接收 / RaceChrono 协议解析
  - `core/domain/` — 领域模型
  - `feature/test/` — 圈速判圈引擎 / UI
- **BLE 协议**：RaceChrono 20-byte GPS 主包 + 3-byte 时间包，25Hz 默认
- **spec-driven 工具**：OpenSpec 中文版（`openspec-chinese` CLI）
  - 标准结构：`openspec/changes/<change-name>/{proposal.md, tasks.md, specs/<capability>/spec.md}`
  - 生命周期：active → `/opsx:apply` → `openspec/changes/archive/`
- **战役制**：攻击点按主题分组到战役
  - A = laptime clock source 时钟源完整性
  - B = detector 量纲 / 米空间投影
  - C = lap timing engine / 圈速精度与闭圈
  - D = RaceChrono parser
  - E = 穿线插值精度（已并入 C）
  - F = UI 性能
  - G = BLE 连接生命周期
  - H = parser 尾巴
  - I = UI 一致性
  - Z = 文档修订
- **评审历史定锚**：`docs/superpowers/reviews/` 下按日期 `YYYY-MM-DD-*.md` 归档

---

## 3 · 核销状态机

每个攻击点（`A{N}`）走以下状态机：

```
[ 🟣 proposal-needed ] --立 proposal + 核销 spec--> [ 🔴 pending ]
                                                            |
[ 🔴 pending ] --认领--> [ 🟡 in_progress ] --完成 + commit--> [ 🟢 pending_review ]
                                ^                                    |
                                |                                    |
                                +-- ❌ rejected (附理由) <--核销失败--+
                                                                     |
                                                                     +--✅ resolved (迁第五节) <--核销成功
```

| 标识 | 含义 | 谁可改 |
|---|---|---|
| 🔴 `pending` | 评审方新增，待实施方认领 | 评审方创建；实施方改 → 🟡 |
| 🟡 `in_progress` | 实施方认领，施工中 | 实施方改（附 commit hash）→ 🟢 |
| 🟢 `pending_review` | 实施方完成，待评审方核销 | 评审方核销 → ✅ 或 ❌ |
| ✅ `resolved` | 评审方核销通过 | 迁入第五节存档 |
| ❌ `rejected` | 核销不通过，附理由退回 | 实施方改 → 🟡 重攻 |
| 🟣 `proposal-needed` | 功能规划级，需先起 proposal | 实施方起 proposal 后改 → 🔴 |
| 🔮 `future-battle` | 未来战役预留占位（第六节） | 触发条件满足后迁第一节 🔴 |

---

## 4 · 核销闭环原则（Non-negotiable）

> **核销 = 闭环**。评审方在核销时发现新问题 —— 必须 ❌ `rejected`，**不得盖 ✅**。

**规则**：

1. **新发现的问题 = 拒绝核销**。无论新问题是：
   - bug
   - 测试断言松
   - 代码异味
   - 未审代码（评审方只读 commit message 没读 diff —— 不能盖章）
   —— 都要 ❌ 退回，原条目留在 🟢 或回到 🟡，直到**新问题要么在本次战役内修完、要么评审方显式豁免**。

2. **评审方豁免的前提**：
   - 显式在条目里写明 "**本次核销豁免**：新发现 X 拆为独立条目 Y，不阻塞本次闭环"
   - 豁免仅限 **P2 级别**（代码异味、文档修订、注释级），**不适用 P0/P1**
   - 评审方事后发现豁免错误（豁免项引出更多问题），可以**事后撤销**把条目拉回 🟢

3. **实施方的 pushback 权益**：
   - 实施方对"新发现"可以 pushback（技术上不认可 / 超出 change scope）
   - 评审方和实施方必须就"是否属于本次闭环"达成一致才能继续
   - 不一致时**默认严格** —— 不盖 ✅

4. **违反本原则的后果**：
   - 已盖章但未闭环的条目 = **🟡 技术债**，将在下次复审时被拉回 🟢
   - 评审方 review 文档结论（例如 "🔴 暂不合流"）与 backlog 状态（例如 ✅）冲突时，**以 review 文档为准**，backlog 状态错了改 backlog

**历史教训**：本项目 2026-04-22 初始化时违反过此原则（A2/A3/A4/A5/A31 盖了 ✅ 但对应 v2 code review 明确"🔴 暂不合流"），已按本原则第 4 项降级。**你不要重蹈覆辙**。

---

## 5 · 文档分层原则

| 信息类型 | 落盘位置 | 谁维护 |
|---|---|---|
| 攻击点最新状态、最近动作索引、未闭合项清单 | `docs/superpowers/reviews/attack-backlog.md` | 评审方 + 实施方共同，按状态机 |
| **单轮 review 的 P2/P3 清单、推导过程、量级分析** | `docs/superpowers/reviews/YYYY-MM-DD-<主题>-<类型>-review.md` | **评审方，写完冻结不回改** |
| OpenSpec 契约本身（MUST / Scenario） | `openspec/changes/<change>/specs/<capability>/spec.md` | 实施方 |
| 任务分解与 commit 策略 | `openspec/changes/<change>/tasks.md` | 实施方 |
| 决策背景（为什么选 A 不选 B） | `openspec/changes/<change>/proposal.md` "决策 N" 段落 | 实施方起草 → 评审方挑刺 → 实施方改 |
| **规格更新驱动清单**（review 结论 → 具体修订指令） | `openspec/changes/<change>/review-v<N>-patches.md` | **评审方**（交付给实施方执行） |
| 未来战役预留占位 | `attack-backlog.md` 第六节 + 触发它的 review 文档 | 评审方 |

**核心原则**：
- **backlog 只写状态 + 最近动作索引**，不复制 review 推导过程
- **review 文档写完即冻结**，新一轮另起新文件
- **驱动清单（patches）和 change 同目录归档**

---

## 6 · Review 文档命名规范

```
docs/superpowers/reviews/YYYY-MM-DD-<主题或 change 名>-<类型>-review.md
```

**类型枚举**：

| 类型后缀 | 触发时机 | 典型内容 |
|---|---|---|
| `-proposal-review.md` | 实施方起草 proposal 后 | 决策完整性 / 风险表 / scope 边界挑战 |
| `-spec-tasks-review.md` | 实施方 spec + tasks 完成后 | Scenario 精度 / 契约自洽 / tasks 覆盖映射 |
| `-code-review.md` | 代码 commit 后 | 代码正确性 / 契约对齐 / 测试强度 |
| `-audit.md` | 定向审计某段代码 | 针对性挑刺，不一定对应具体 change |
| `-adversarial-review.md` | 宏观对抗式扫描（多 change / 多模块） | 战略层问题 / 跨 change 一致性 |

**历史命名示例**（供参考）：

```
2026-04-21-lap-timing-review.md
2026-04-22-lap-timing-and-gps-adversarial-review.md
2026-04-22-opsx-fix-laptime-clock-source-review.md
2026-04-22-opsx-fix-laptime-clock-source-code-review.md
2026-04-24-opsx-fix-lap-timing-engine-entry-hardening-proposal-review.md
2026-04-24-opsx-fix-lap-timing-closure-and-precision-contract-spec-tasks-review.md
```

**命名规则细则**：
- 日期用 ISO 8601（今天的日期，不是 review 对象的日期）
- `opsx-` 前缀表示基于 OpenSpec change 的 review
- 主题名允许长，用 `-` 分隔
- 不含空格、不含大写拉丁字母（除 README/PROTOCOL 这类元文件）

---

## 7 · 优先级分层（P0 / P1 / P2 / P3）

| 级别 | 含义 | 盖 ✅ 影响 |
|---|---|---|
| **P0** | 阻塞发布：安全问题、数据破坏、核心路径崩溃 | **永不豁免**，必修 |
| **P1** | 功能性严重问题：契约违反、逻辑错误、测试失效、会导致用户可见 bug | **永不豁免**，必修 |
| **P2** | 阻塞核销：Scenario 断言 FAIL、tasks 遗漏测试、契约自洽性 | 修完才可盖 ✅，可豁免但需显式声明 + 另起 🔴 条目 |
| **P3** | 建议修：表达不清、工具链缺失、可读性、风格 | 可进入 apply，留任务回头改 |

**特殊情况**：
- **proposal 遗留**：proposal 已发现但未修复的问题，下一轮 review 时带入统计（例如 `proposal V3 遗留 P3`）
- **跨 change 依赖**：某 P2 依赖另一 change 先完成，可降级为"依赖阻塞"而非真阻塞本 change；必须在 review 文档里显式标注依赖关系

---

## 8 · Review 轮次框架

对同一个 change，评审分多轮：

```
[实施方: 起 proposal]
    ↓
[第 1 轮: proposal review]  ← 评审方挑刺，重点在"方向对不对 / 决策完不完整"
    ↓
[实施方: 改 proposal → V2]
    ↓ (迭代直到 proposal 通过)
[实施方: 写 spec + tasks]
    ↓
[第 2 轮: spec + tasks review]  ← 重点在"Scenario 精度 / 契约自洽 / task 覆盖"
    ↓
[实施方: 改 spec + tasks → V2]
    ↓ (迭代直到 spec + tasks 通过)
[/opsx:apply → 归档 spec 到 specs/]
    ↓
[实施方: 写代码 + 测试]
    ↓
[第 N 轮: code review]  ← 重点在"代码实现与 spec 对齐 / 测试强度"
    ↓
[代码通过 → 核销 backlog 攻击点 🟢 → ✅]
```

**每轮 review 的重点不同**，不要混用：
- proposal 轮不挑 Scenario 细节（还没写）
- spec 轮不挑代码（还没写）
- code 轮不质疑 spec（spec 已定盘）；如 code 揭示 spec 本身有问题，应独立立新 change 或修订，而非在 code review 里改 spec

---

## 9 · Review 文档输出结构模板

```markdown
# <主题> · <轮次> review

- **日期**：YYYY-MM-DD
- **评审对象**：
  - <file path 1> V<n>（<行数>，<Scenario 数>）
  - <file path 2> V<n>
- **覆盖攻击点**：A<N1> + A<N2> + ...
- **评审方**：<标识，例如 "codex session">
- **实施方**：<标识>
- **轮次**：第 N 轮结构化 review（前 N-1 轮状态简述）
- **结论**：🟢 准予 / 🟡 有条件准予 / 🔴 拒绝合流 / 🔄 需重写
- **前置条件**：<P0/P1/P2 全闭合为前置>

## 0. 结论摘要
<总体评价表：维度 + 评级 + 结论，一句话 summary>
<趋势表：历轮 P0/P1/P2/P3 数量对比，收敛方向>

## 1. 🔴 P0 / P1（若有，必修阻塞）
### P0-1 · <问题短语>
- **位置**：<file:line>
- **问题**：<具体描述>
- **后果**：<如果不修会怎样>
- **修订建议**：<具体方向或模板>

## 2. 🟡 P2（必修，阻塞 /opsx:apply 或 核销）
<同 P0/P1 结构>

## 3. 🟡 P3（建议修，可进入 apply，留任务回头改）
<表格形式即可：# / 位置 / 问题 / 修订>

## 4. proposal / 上游遗留（若有）
<引用上一轮未闭合项>

## 5. 🟢 已充分认可（无需改动）
<列出评审方确认做对的设计点>

## 6. 给实施方的回复模板
<短版结论，用户可直接复制到消息给实施方>

## 7. 附录 · 原始推导记录（可选）
<量级分析、物理推导、数据源依据等>
```

**关键规则**：
- 每条 P 级问题都要有**可验证的位置**（file:line 或 Scenario ID）
- 修订建议要具体到**文本级**（"改成 X"），不要只说"有问题"
- "🟢 已充分认可"段落不是客套，是对实施方正确判断的显式肯定，帮助对方形成决策经验

---

## 10 · 规格更新驱动文档（review-v<N>-patches.md）

**触发时机**：当某轮 review 产生**多处具体修订指令**（≥5 项修订）时，应额外产出一份驱动文档，让实施方照着改。

**位置**：`openspec/changes/<change-name>/review-v<N>-patches.md`
- 与 change 同目录
- 和 change 一起进入归档
- 版本号 `v<N>` 对应 review 轮次（v2 = 第 2 轮 review 产出的 patches）

**结构**（固定模板）：

```markdown
# <change 名> · <轮次> review 规格更新驱动清单

> **用途**：把 <日期> <轮次> review 结论（N P0/P1/P2 + N P3）转为实施方可直接执行的规格文件更新指令。每条指令含 **文件路径 + 精确位置 + 原文 + 改写/新增内容**，逐条照改即可完成 V<N> 三件套。
> **上游 review 文档**：docs/superpowers/reviews/YYYY-MM-DD-*-review.md

## A · proposal.md 修订
### A1 · <修订短语>
- **文件**：<full path>
- **位置**：<line X-Y 或 section>
- **原文**：<代码块 / 引用>
- **改写后**：<代码块 / 引用>
- **验证**：<grep / 命令>

## B · spec.md 修订
<同 A 结构>

## C · tasks.md 修订
<同 A 结构>

## D · attack-backlog.md（评审方已落盘 / 实施方无操作）

## E · 改完后自检清单
- E1/E2/E3... 具体 grep / validate / 测试命令

## F · 提交下一轮 review 前的交付清单

## G · 本清单生命周期
```

**核心原则**：实施方打开 patches 文件后，**不需要回读 review 文档或 backlog**，照指令直接改文件即可产出 V<N> 三件套。

---

## 11 · Backlog 读写规则

### 11.1 文件结构

`docs/superpowers/reviews/attack-backlog.md` 当前六节：

```
一、🔴 pending — 新待认领
二、🟡 in_progress — 实施方施工中
三、🟢 pending_review — 待评审方核销
四、❌ rejected — 核销失败已退回
五、✅ 已核销存档
六、🔮 未来战役预留（量级驱动，尚未开战）
附录 · 编号总览
```

### 11.2 评审方可执行的 backlog 操作

| 操作 | 时机 | 动作 |
|---|---|---|
| **新增攻击点** | review 发现新问题 | 在第一节追加 `A<N>` 条目（格式见 backlog 零节"字段模板"），更新附录 |
| **核销成功** | P0/P1/P2 全闭合 | 条目迁第五节，浓缩为"ID + 一句话 + commit + 战役 + 显式豁免声明"，更新附录 |
| **核销失败** | 发现新问题 | 条目迁第四节，状态行写驳回理由 |
| **追加"最近动作"索引** | 新一轮 review 发出时 | 在条目 `- **状态**：` 行后追加 `- **最近动作**：<日期> <一句话> 详见 [<review 文档>]` |
| **新增未来战役占位** | 评审发现跨 change 量级分析 | 第六节追加 🔮 条目，必须含"触发条件 + 预计量级收益 + 升级路径指向 + 留档依据" |

### 11.3 评审方**不得**执行的 backlog 操作

- 自行把 🟡 / 🟢 迁到 ✅（除非评审通过 + 核销条件全验证）
- 修改实施方填写的 commit hash / 施工记录
- 删除已存档的 ✅ 条目
- 在 backlog 里复制 review 文档的推导过程（用索引链接代替）

---

## 12 · 典型工作流示例（spec + tasks 第 2 轮 review）

假设实施方通知你："战役 X 的 spec V1 + tasks V1 已就绪，请 review"。你的工作流：

```
Step 1 · 加载上下文（5-10 分钟）
  ├── 读 docs/superpowers/reviews/REVIEW-PROTOCOL.md（本文件，如已加载跳过）
  ├── 读 docs/superpowers/reviews/attack-backlog.md
  │     找对应攻击点的 "最近动作" 索引，找到上一轮 review 文档
  ├── 读上一轮 review 文档（proposal review）
  │     了解本 change 已经解决 / 遗留的问题
  ├── 读 openspec/changes/<change>/proposal.md
  │     了解决策背景
  ├── 读 openspec/changes/<change>/specs/<capability>/spec.md 全文
  └── 读 openspec/changes/<change>/tasks.md 全文

Step 2 · 结构化挑刺（核心）
  ├── 每个 Requirement 逐条读，检查：
  │   ├── Scenario 是否可测试（具体 GIVEN/WHEN/THEN）
  │   ├── 硬区分 v1/v2 是否明确（测试能否锁定升级）
  │   ├── Scenario 与 tasks 是否 1:1 映射（tasks 有没有遗漏测试）
  │   ├── 字段契约是否内部自洽（无矛盾条款）
  │   └── 边界场景是否覆盖（极值 / null / 越界 / 并发）
  ├── 对比 proposal 的决策，检查 spec 是否偏离
  └── 跑 spec 里的 grep / validate 自检命令（如果 tasks 有）

Step 3 · 深挖（可选但推荐）
  ├── 物理 / 算法层：量级分析、边界条件、收敛性
  ├── 工程层：依赖冲突、归档时序、跨 change 耦合
  └── 测试强度：死码保护、回归防御、对偶验证

Step 4 · 产出 review 文档
  ├── 路径：docs/superpowers/reviews/<today>-<change>-spec-tasks-review.md
  ├── 结构：按 § 9 模板
  └── 优先级分层：严格按 § 7

Step 5 · 产出驱动清单（若修订 ≥5 项）
  ├── 路径：openspec/changes/<change>/review-v2-patches.md
  └── 结构：按 § 10 模板

Step 6 · 更新 backlog
  ├── 在攻击点条目追加 "最近动作" 索引（指向 review 文档）
  ├── 状态保持不变（spec/tasks 阶段条目仍为 🔴，直到代码施工才 🟡）
  └── 若发现应立新攻击点，第一节追加 🔴 新条目

Step 7 · 给用户交付
  ├── 短 summary：N P0/P1 / N P2 / N P3 + 结论（🟢/🟡/🔴）
  ├── 关键修订指向（file:line 级）
  └── 回复模板（用户可复制给实施方）
```

---

## 13 · 评审方的自我约束（必守）

1. **不直接编辑** `openspec/changes/*/proposal.md` / `spec.md` / `tasks.md`
   - 这是实施方的领域；你通过 review 文档和 patches 清单驱动修订
2. **不写代码**
   - 你可以 grep / read / run tests，但不 Edit / Write 到 `.kt` / `.kts` / `.java` 等源码文件
3. **不自行决定"豁免"**
   - 豁免必须用户明示或评审方+实施方双向一致；否则默认严格
4. **发现新问题必须 reject**
   - 不得借"能不能下次修"之名盖 ✅（违反 § 4）
5. **不删历史**
   - 历史 review 文档 / 已核销条目 / 旧版 backlog 状态都是证据链，只追加不删除
6. **不预判用户意图扩展 scope**
   - 用户只叫你 review proposal，不要顺手 review 代码
7. **引用要精确**
   - 引用 file:line 要对得上当时的文件状态；若文件已被改，引用行号前加 `pre-V<N>:` 前缀

---

## 14 · 当前活跃战役快照

*快照时点：2026-04-24。实际最新状态以 `attack-backlog.md` 为准。*

### 正在进行

| 战役 | change | 当前阶段 | 覆盖攻击点 |
|---|---|---|---|
| C 二期 | `fix-lap-timing-closure-and-precision-contract` | spec V1 + tasks V1 第 2 轮 review 已发，等实施方改 V2 | A15 / A20 / A32 / A33 |
| G | `fix-ble-connection-lifecycle` | /opsx:apply 已准予 | A23 / A24 / A25 / A27 / A29 / A40 / A42 / A45 / A46 |

### 已归档

| change | 核销 commits | 覆盖攻击点 |
|---|---|---|
| `fix-laptime-clock-source-integrity` | d71371b / 3ec0ad7 / 416f6e3 | A2 / A3 / A4 / A5 / A6 / A7 / A8 |
| `fix-lap-timing-engine-entry-hardening` | a2c9bae / 32d65c5 | A19 / A21 / A34 / A38 |

### 未来战役预留（第六节 🔮）

| 占位 change | 触发条件 | 预计量级收益 |
|---|---|---|
| `fix-gps-position-denoise` | 真机精度战役启动 | 真机 ±20-60ms → ±5-10ms |
| `fix-laptime-skip-frame-precision` | 真机回归发现 skip 场景圈时漂移 | 真机偶发 2-8ms → <1ms |
| `fix-laptime-low-freq-device-support` | 设备矩阵扩展到 1Hz 弱定位 | 1Hz 下 50-200ms → <10ms |

---

## 15 · 交接 checklist（codex 第一次上工前）

- [ ] 读完本文档（§ 1 → § 14）
- [ ] 浏览 `docs/superpowers/reviews/attack-backlog.md` 零节 + 附录总览
- [ ] 浏览最近 3 份日期前缀 review 文档了解历史挑刺风格
- [ ] 浏览一份完整的 `review-v<N>-patches.md`（参考：`openspec/changes/fix-lap-timing-closure-and-precision-contract/review-v2-patches.md`）
- [ ] 与用户对齐："本次你 review 什么 change / 轮次 / 交付形式"
- [ ] 明确你是否有 Edit 权限（推荐：只给你 Read + Write 到 `docs/superpowers/reviews/` 和 `openspec/changes/*/review-v<N>-patches.md`，其他路径只读）

---

## 16 · 常见陷阱（基于历史教训）

| 陷阱 | 后果 | 预防 |
|---|---|---|
| 看到 commit message 就盖 ✅ 不读 diff | 技术债累积，下次复审拉回 🟢 | § 4 规则 1：没读 diff 不盖章 |
| 把新发现问题以"下次修"名义豁免盖 ✅ | 闭环原则破坏 | § 4 规则 2：豁免仅限 P2 + 显式另起 🔴 条目 |
| Review 文档只写 "有问题" 不给修订建议 | 实施方无从下手，反复拉锯 | § 9 规则：每条 P 级必须含具体修订方向 |
| 把 review 推导塞进 backlog 条目 | backlog 膨胀，切 session 读不完 | § 5 原则：backlog 只写状态 + 索引 |
| 在 proposal 轮挑 Scenario 细节 / 在 spec 轮改代码 | 轮次错位，重复劳动 | § 8 规则：每轮重点不同 |
| 对归档 spec 直接 Edit | 破坏归档完整性 | 跨 change 修订用 `## MODIFIED Requirements` 段 |
| 引用 file:line 过时 | review 结论失准 | § 13 规则 7：必要时加 `pre-V<N>:` 前缀 |

---

## 17 · 联系 / 反馈

- 协议修订：用户主导，评审方可提议，提议写入 review 文档供讨论
- 模糊地带：遇到本文档未覆盖的场景，**默认保守**（§ 1 冲突规则），同时把问题抛给用户
- 文档本身的问题：直接向用户报告，不要擅自改本文件（本文件是协议，改动需用户确认）

---

**本文档是 onboarding 材料，不是法典。精神优先：对抗式挑刺 + 闭环核销 + 分层落盘。记住：你的职责是 reject 得有道理，approve 得有证据。**
