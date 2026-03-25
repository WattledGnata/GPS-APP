# Agent Team Governance Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 agent team 治理设计落地为项目可执行规则文件与 team 配置，形成可直接用于后续任务的协作约束。

**Architecture:** 采用双层规则结构：项目根 `CLAUDE.md` 承载最小硬规则，`.claude/PROJECT_RULES.md` 承载完整治理细则，`.claude/android-dev-team.json` 承载可执行角色与流程配置。落地过程先写规则、再重构 team 配置、最后做一致性核对，避免规则与配置脱节。

**Tech Stack:** Markdown, JSON, Claude Code project rules, multi-agent workflow governance

---

## 文件结构

### 新建文件
1. `CLAUDE.md` - 项目级高优先级硬规则入口
2. `.claude/PROJECT_RULES.md` - 完整治理细则

### 修改文件
1. `.claude/android-dev-team.json` - 按新治理模型重构 team 配置

### 验证对象
1. `CLAUDE.md`
2. `.claude/PROJECT_RULES.md`
3. `.claude/android-dev-team.json`
4. `git diff --stat`

---

## Task 0: 先产出验收基线

**Files:**
- Reference: `docs/superpowers/specs/2026-03-24-agent-team-governance-design.md`

- [ ] **Step 1: 提炼三文件落地清单**

先列出每个文件必须覆盖的最小内容：
- `CLAUDE.md`：只放硬规则
- `.claude/PROJECT_RULES.md`：完整治理细则
- `.claude/android-dev-team.json`：角色、流程、汇报、生命周期字段

- [ ] **Step 2: 提炼显式验收矩阵**

矩阵至少覆盖：
- 主从分离是否落地
- 测试前置是否落地
- 关键任务治理是否落地
- guardian 前置是否落地
- 完成门是否落地
- 进度心跳与超时恢复是否落地

- [ ] **Step 3: 用验收矩阵约束后续写作**

要求：后续每完成一个文件，都回到这份矩阵逐项核对，而不是只看 diff 或语法。

---

## Task 1: 落地项目根 CLAUDE.md 最小硬规则

**Files:**
- Create: `CLAUDE.md`
- Reference: `docs/superpowers/specs/2026-03-24-agent-team-governance-design.md`

- [ ] **Step 1: 写出 CLAUDE.md 内容草案**

内容必须覆盖：
- 主 Agent 仅做调度/审计/汇报，不直接实现
- 关键任务定义（BLE、协议、数据兼容、权限、结构、规则文件）
- 测试先于实现
- 关键任务必须 guardian 先分析
- 无验证不得宣称完成
- 禁止提交本地私有配置文件（如 `.idea/`、未纳入治理的个人 `.claude` 私有配置）

- [ ] **Step 2: 核对内容是否只保留硬规则**

检查点：
- 不复制整份设计文档
- 不写过长解释
- 每条规则都能直接约束会话行为

- [ ] **Step 3: 写入 `CLAUDE.md`**

- [ ] **Step 4: 读回并核对**

Run: `git diff -- CLAUDE.md`
Expected: 仅包含项目级硬规则，无冗余长篇说明

---

## Task 2: 落地 .claude/PROJECT_RULES.md 完整治理细则

**Files:**
- Create: `.claude/PROJECT_RULES.md`
- Reference: `docs/superpowers/specs/2026-03-24-agent-team-governance-design.md`

- [ ] **Step 1: 提炼完整细则结构**

必须包含章节：
- 角色体系
- 关键任务与普通任务流程
- Gate 规则
- 团队生命周期
- 阶段汇报与关键任务心跳
- 故障恢复与重派
- 测试前置与 TDD 适用范围
- 报告模板摘要

- [ ] **Step 2: 写出 PROJECT_RULES.md 初稿**

要求：
- 比 `CLAUDE.md` 详细
- 但比 design spec 更偏操作手册
- 用项目模块术语（`core/bluetooth`、`core/domain`、`core/data`、`feature/test`、`simulator`）

- [ ] **Step 3: 检查与 CLAUDE.md 的分层关系**

检查点：
- `CLAUDE.md` 是硬规则
- `PROJECT_RULES.md` 是细则
- 没有大段重复粘贴

- [ ] **Step 4: 写入 `.claude/PROJECT_RULES.md`**

- [ ] **Step 5: 读回并核对**

Run: `git diff -- .claude/PROJECT_RULES.md`
Expected: 结构清晰，能作为执行手册直接使用

---

## Task 3: 重构 android-dev-team.json 为新治理模型

**Files:**
- Modify: `.claude/android-dev-team.json`
- Reference: `CLAUDE.md`
- Reference: `.claude/PROJECT_RULES.md`

- [ ] **Step 1: 定义新的角色集合**

角色应对齐设计：
- `orchestrator`
- `implementer`
- `tester-validator`
- `reviewer-auditor`
- `product-analyst`
- `protocol-data-guardian`
- `build-structure-guardian`
- `ui-flow-analyst`

- [ ] **Step 2: 为每个角色补齐触发条件和退出边界**

检查点：
- 常驻角色与条件角色区分清楚
- guardian 的触发条件与关键任务定义一致
- orchestrator 边界与 `CLAUDE.md` 一致

- [ ] **Step 3: 删除旧角色和旧流程描述**

移除旧的：
- `architect`
- `developer`
- `algorithm-master`
- `pm`
- `ui-designer`
- `reviewer`
- `bug-hunter`
- `tester`

同时删除不再符合设计的长 prompt 和旧 reporting flow。

- [ ] **Step 4: 为 orchestrator 写严格边界 prompt**

必须写明：
- 不直接写代码
- 不做无边界大规模读码
- 只做任务分类、角色召唤、证据核对、汇报
- 允许有限只读核对

- [ ] **Step 5: 为 implementer 写实现边界 prompt**

必须写明：
- 必须基于任务单和验收标准实现
- 不得越权扩改
- 不得自行宣布完成
- 必须提交实现报告

- [ ] **Step 6: 为 tester-validator 写测试前置与验证 prompt**

必须写明：
- 需求拆解后优先输出测试方案/验收用例/回归清单
- 适用场景优先 TDD
- 基础验证与完成验证分离
- 必须产出验证证据

- [ ] **Step 7: 为 reviewer-auditor 写审计 prompt**

必须写明：
- 检查边界、规则、兼容性说明
- 审查结论只能是通过 / 有条件通过 / 退回修改

- [ ] **Step 8: 为 guardian 角色写专项 prompt**

必须区分：
- `protocol-data-guardian`：BLE、协议、解析、持久化、历史兼容
- `build-structure-guardian`：项目结构、Gradle、规则、team 配置
- `product-analyst`：需求与验收标准
- `ui-flow-analyst`：权限交互与用户流程

- [ ] **Step 9: 分步重写流程字段**

依次重写：
- 普通任务流程
- 关键任务流程
- 非编码任务快速通道
- 测试前置节点
- guardian 前置节点
- 完成门约束

- [ ] **Step 10: 分步重写汇报与生命周期字段**

依次重写：
- 阶段汇报要求
- 关键任务心跳
- 超时探测与重派
- 按任务召唤 / 按任务解散

- [ ] **Step 11: 写入 `.claude/android-dev-team.json`**

- [ ] **Step 12: 做 JSON 语法自检**

Run: `python -m json.tool .claude/android-dev-team.json >/dev/null`
Expected: exit 0

- [ ] **Step 13: 做 team 配置语义自检**

用两个样例逐项走查字段：
- 普通任务样例：纯规则文档调整
- 关键任务样例：BLE 协议解析兼容调整

Expected:
- 普通任务不会默认召唤 guardian
- 关键任务会触发 guardian + 测试前置 + 完成门

---

## Task 4: 一致性核对与最小验证

**Files:**
- Verify: `CLAUDE.md`
- Verify: `.claude/PROJECT_RULES.md`
- Verify: `.claude/android-dev-team.json`

- [ ] **Step 1: 用验收矩阵逐项核对三份文件**

逐项核对：
- `CLAUDE.md` 是否只保留硬规则
- `.claude/PROJECT_RULES.md` 是否覆盖角色 / Gate / 生命周期 / 心跳 / 故障恢复
- `android-dev-team.json` 是否体现普通 / 关键任务分流、先立项再召队、测试前置、guardian 前置、完成门

- [ ] **Step 2: 运行配置格式验证**

Run: `python -m json.tool .claude/android-dev-team.json >/dev/null`
Expected: exit 0

- [ ] **Step 3: 查看目标文件最终 diff**

Run: `git diff -- CLAUDE.md .claude/PROJECT_RULES.md .claude/android-dev-team.json`
Expected: 仅包含治理落地相关内容，没有无关改动

- [ ] **Step 4: 查看目标文件状态**

Run: `git status --short -- CLAUDE.md .claude/PROJECT_RULES.md .claude/android-dev-team.json`
Expected: 只显示这三个目标文件的新增 / 修改状态

- [ ] **Step 5: 生成人工审阅摘要**

摘要至少包含：
- 新增了哪些文件
- 新 team 角色有哪些
- 哪些是关键硬规则
- 哪些地方留待后续实战再补

---

## Task 5: 提交落地结果

**Files:**
- Stage: `CLAUDE.md`
- Stage: `.claude/PROJECT_RULES.md`
- Stage: `.claude/android-dev-team.json`

- [ ] **Step 1: 仅核对目标文件状态**

Run: `git status --short -- CLAUDE.md .claude/PROJECT_RULES.md .claude/android-dev-team.json`
Expected: 仅显示目标文件状态，不依赖工作区其他无关文件是否干净

- [ ] **Step 2: 提交**

```bash
git add CLAUDE.md .claude/PROJECT_RULES.md .claude/android-dev-team.json
git commit -m "chore: 落地 agent team 治理规则与项目配置"
```

- [ ] **Step 3: 提交后仅核对目标文件**

Run: `git status --short -- CLAUDE.md .claude/PROJECT_RULES.md .claude/android-dev-team.json`
Expected: 无输出，表示目标文件已提交；其他用户保留改动不受影响
