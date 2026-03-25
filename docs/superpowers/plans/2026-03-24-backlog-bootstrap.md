# Backlog Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在项目中创建 `docs/backlog.md`，并把现有 roadmap 中已明确的事项整理成 backlog 初版。

**Architecture:** 采用单文件 backlog 作为执行入口，保留 `docs/superpowers/specs/` 作为复杂需求设计展开。实施时先建立 backlog 模板与协作规则，再从现有 roadmap 提炼条目并放入 `todo / done / accepted` 分区，最后人工核对格式与内容一致性。

**Tech Stack:** Markdown, existing project docs, backlog workflow, spec-linked documentation

---

## 文件结构

### 新建文件
1. `docs/backlog.md` - 项目共享需求清单主入口

### 参考文件
1. `docs/superpowers/specs/2026-03-24-backlog-collaboration-design.md` - backlog 协作机制设计
2. `docs/roadmap/2026-03-23-future-features-roadmap.md` - 当前可迁移需求来源
3. `docs/CHANGELOG.md` - 已落地改动历史，用于辅助判断状态

### 验证对象
1. `docs/backlog.md`
2. `git diff -- docs/backlog.md`

---

## Task 1: 搭建 backlog 主文档骨架

**Files:**
- Create: `docs/backlog.md`
- Reference: `docs/superpowers/specs/2026-03-24-backlog-collaboration-design.md`

- [ ] **Step 1: 写出文档头部和使用说明**

内容至少包括：
- backlog 的定位：项目需求的默认执行入口
- 明确写出开始新需求时优先查看 `docs/backlog.md`
- 四级优先级：P0 / P1 / P2 / P3
- 四状态流转：todo / doing / done / accepted
- “重要需求可链接 spec”的说明

- [ ] **Step 2: 写出 backlog 维护规则**

规则至少包括：
- 开始新需求前先看 backlog
- 同优先级下优先选择更明确、依赖更少、已有 spec 的条目
- 用户人工调整优先级时，以用户调整为准
- 实现中改为 `doing`
- 代码完成改为 `done`
- 只有用户明确验收通过后才能改为 `accepted`
- 验收未通过时，可从 `done` 回退到 `doing`
- 接新需求时参考历史条目、spec、roadmap

- [ ] **Step 3: 写出文档分区骨架**

分区至少包括：
- 当前优先级总览（仅做摘要导航，不做第二套状态源）
- TODO
- DOING
- DONE（待验收）
- ACCEPTED（已验收）

- [ ] **Step 4: 读回 `docs/backlog.md`，确认骨架完整**

Run: `git diff -- docs/backlog.md`
Expected: 出现清晰的 backlog 头部、规则和状态分区，明确其为默认执行入口，并包含状态回退和验收门槛说明

---

## Task 2: 从 roadmap 提炼 backlog 初始条目

**Files:**
- Modify: `docs/backlog.md`
- Reference: `docs/roadmap/2026-03-23-future-features-roadmap.md`
- Reference: `docs/CHANGELOG.md`

- [ ] **Step 1: 提炼 roadmap 中仍未完成的明确事项**

至少提炼出这些条目：
- 添加设置页面让用户选择模式
- 在 TestSessionViewModel 中补全语音播报触发点
- 视频拍摄 + 图层叠加
- 完整圈速记录功能

- [ ] **Step 2: 评估条目粒度是否过大**

检查点：
- 若一个条目同时包含两个以上可独立交付能力，评估是否拆成多个 backlog 条目
- 若暂不拆分，至少补充清楚当前范围边界
- 若条目明显跨模块且实现周期长，标记后续建议补 spec

- [ ] **Step 3: 为每个未完成条目写标题、优先级和状态**

要求：
- 标题直接可用于后续对话引用
- 优先级与 roadmap 一致或基于现状做保守调整
- 状态初始应为 `todo`

- [ ] **Step 4: 为每个未完成条目补影响范围和验收标准**

要求：
- 影响范围应具体到模块、目录或功能区
- 每个条目至少写 2 条用户可直接判断的验收标准
- 验收标准不能只是实现动作描述

- [ ] **Step 5: 为每个未完成条目补关联历史和 spec 策略**

字段策略固定为：
- 必填字段：标题、优先级、状态、影响范围、验收标准、关联历史
- 条件字段：`关联 spec` 仅在已有或明确需要时出现；无 spec 时直接省略，不写“无”

- [ ] **Step 6: 结合 roadmap 和 changelog 列出疑似已落地项**

至少识别：
- 加速度阈值调整
- 语音播报中已完成的部分能力（若尚未整体验收，不应直接写成 accepted）

- [ ] **Step 7: 逐项判断疑似已落地项是否具备验收依据**

要求：
- 若没有明确用户验收依据，不得标记为 `accepted`
- 有代码落地但无验收依据的项目，放入 `done`
- 仅有用户明确验收证据的项目，才能放入 `accepted`

- [ ] **Step 8: 将已落地项放入合适分区**

要求：
- 已经完整通过用户验收的项目放入 `accepted`
- 代码已完成但尚未验收的项目放入 `done`
- 不确定是否已验收的项目，默认不要写成 `accepted`
- 对 `accepted` 条目补充简短验收依据说明

- [ ] **Step 9: 读回 backlog，确认条目字段和状态语义一致**

Run: `git diff -- docs/backlog.md`
Expected: 必填字段完整、条件字段策略一致，todo/done/accepted 分区语义正确，没有把“部分完成”误写成“已验收”

---

## Task 3: 校准 backlog 为后续自驱执行入口

**Files:**
- Modify: `docs/backlog.md`
- Reference: `docs/superpowers/specs/2026-03-24-backlog-collaboration-design.md`

- [ ] **Step 1: 为优先级总览补充当前队列摘要**

要求：
- 仅列出摘要导航，不重复完整条目正文
- 至少包含当前 P0、P1、P2/P3 条目标题摘要
- 单独标出当前待验收条目（如有）
- 明确正文分区才是状态源

- [ ] **Step 2: 写明同优先级下的默认选择规则**

至少包括：
- 同优先级下优先选择更明确的条目
- 同优先级下优先选择依赖更少的条目
- 同优先级下优先选择已有 spec 的条目
- 用户人工调整后以用户调整为准

- [ ] **Step 3: 检查 backlog 是否足以支持后续连续执行**

检查点：
- 是否能直接看出下一项候选需求
- 是否能直接看出哪些条目待验收
- 是否能直接识别哪些需求建议补 spec
- 是否能区分摘要导航与正文状态源

- [ ] **Step 4: 精简冗余文字，保留执行视角**

要求：
- 不复制整份 roadmap 内容
- 不把 backlog 写成大段背景报告
- 只保留执行真正需要的信息

- [ ] **Step 5: 最终核对文档可读性**

Run: `git diff -- docs/backlog.md`
Expected: `docs/backlog.md` 成为可持续维护的执行入口，结构清楚、字段稳定、摘要与正文职责分明、后续可以直接增删改条目

---

## Task 4: 完成前验证

**Files:**
- Verify: `docs/backlog.md`

- [ ] **Step 1: 逐项核对是否符合设计文档**

对照 `docs/superpowers/specs/2026-03-24-backlog-collaboration-design.md` 检查：
- 文件位置是否正确
- 是否明确体现 backlog 为默认执行入口
- 字段是否完整
- 状态流转是否一致
- 是否写明 `done -> doing` 回退规则
- 优先级是否一致
- 同优先级默认选择规则是否体现
- spec 链接策略是否体现

- [ ] **Step 2: 做内容正确性审查**

检查点：
- 每个条目的验收标准是否可由用户直接判断
- 影响范围是否具体到模块、目录或功能区
- 关联历史是否能说明条目来源
- 是否存在“部分完成却没有说明剩余范围”的条目
- 是否存在优先级已写但排序依据完全不明的条目

- [ ] **Step 3: 做 accepted 证据与条目粒度审查**

检查点：
- 所有 `accepted` 条目都能指出对应的用户验收依据
- 没有验收依据的已落地项是否被保守地放入 `done`
- 是否存在明显无法单轮实现或无法单次验收的大条目
- 若存在大条目，是否已拆分或注明建议补 spec

- [ ] **Step 4: 检查是否只修改了必要文件**

Run: `git diff --stat`
Expected: 仅新增 `docs/backlog.md`（以及如本轮需要的计划/设计文档），没有无关改动

- [ ] **Step 5: 准备交付说明**

交付说明至少包含：
- 新建了哪个 backlog 文件
- 从 roadmap 吸收了哪些初始条目
- 哪些条目处于 `todo / done / accepted`
- 后续新增需求应如何录入
- 本次整理中仍需人工确认的条目与原因
- 建议后续补 spec 或进一步拆分的条目
