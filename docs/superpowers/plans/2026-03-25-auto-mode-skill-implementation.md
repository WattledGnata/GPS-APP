# Auto Mode Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个手动触发的 personal skill `/auto-mode`，可在当前 session 内切换“连续执行、少确认”模式，并保留高风险动作确认边界。

**Architecture:** 采用“协议层 + 可行性验证优先”的实现方式。`SKILL.md` 负责定义触发条件、toggle 语义、行为边界与红旗；在真正实现状态层之前，先验证 personal skill 的加载机制、后续回合是否会自动消费状态，以及当前环境是否提供可靠的 session 唯一标识。只有这些前提成立，才实现 session 级状态切换；否则交付为“可用的协议型 skill + 明确限制说明”的降级版本。

**Tech Stack:** Claude Code personal skills、Markdown、可选 shell 辅助脚本、本地文件系统

---

### Task 1: 验证 personal skill 的发现与加载机制

**Files:**
- Create: `/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md`
- Reference: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-03-25-auto-mode-skill-design.md`

- [ ] **Step 1: 写一个 failing 的可加载性检查清单**

先明确最小验收：

```text
1. ~/.claude/skills/auto-mode/ 存在
2. SKILL.md 存在并带合法 frontmatter
3. skill 名为 auto-mode
4. description 只描述触发条件，不描述流程
5. 该目录结构在当前环境下能被识别为 personal skill 候选
```

- [ ] **Step 2: 运行目录检查，确认当前失败**

Run:

```bash
ls -la "/Users/wattledgnata/.claude/skills/auto-mode"
```

Expected:

```text
ls: ... No such file or directory
```

- [ ] **Step 3: 创建最小 skill 骨架**

写入最小 frontmatter 与标题：

```markdown
---
name: auto-mode
description: Use when the user wants the current session to keep executing an already-clear local workflow without repeated confirmation, while still stopping for high-risk actions
---

# Auto Mode
```

- [ ] **Step 4: 验证 skill 骨架格式正确**

Run:

```bash
ls -la "/Users/wattledgnata/.claude/skills/auto-mode" && python3 - <<'PY'
from pathlib import Path
p = Path('/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md')
text = p.read_text()
assert text.startswith('---\nname: auto-mode\ndescription: ')
assert '\n# Auto Mode\n' in text
print('PASS')
PY
```

Expected:

```text
PASS
```

- [ ] **Step 5: 验证当前环境是否真的会发现该 personal skill**

检查目标不是“文件存在”，而是“该 skill 可被当前环境识别”。可接受证据至少满足以下之一：

```text
1. 当前环境中存在已生效 personal skill 的目录约定与实际可见先例
2. 当前环境文档或既有配置能证明 ~/.claude/skills/<name>/SKILL.md 会被识别
3. 实际调用 /auto-mode 或按 skill 名触发时，系统能识别该 skill
```

判定规则：

```text
- 若三类证据都拿不到，不得宣称“skill 可加载”，只能进入降级说明
- 若只能证明目录约定，不能证明运行时加载，必须记录为“静态结构已就绪，加载闭环未证实”
```

- [ ] **Step 6: Commit**

```bash
git add "/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-03-25-auto-mode-skill-implementation.md"
git commit -m "docs: refine auto-mode skill loading plan"
```

### Task 2: 用 TDD 完成 skill 协议层文档

**Files:**
- Modify: `/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md`
- Reference: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-03-25-auto-mode-skill-design.md`

- [ ] **Step 1: 写 failing 内容检查，锁定必须出现的协议段落**

检查 `SKILL.md` 至少覆盖这些内容：

```text
- 单命令 toggle 语义：第一次开启，再次关闭
- 仅当前 session 生效
- 默认可连续推进 / 默认停下确认 / 需结合上下文判断
- 高风险动作边界
- Red Flags
- Common Mistakes 或 Common Rationalizations（命名固定一种）
- 常见误区：不会绕过权限系统，不会影响其他已运行 session
- 若平台不支持持续状态消费，需明确降级限制
```

- [ ] **Step 2: 运行内容检查，确认当前失败**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
text = Path('/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md').read_text()
required = [
    '再次调用',
    '仅当前 session 生效',
    '默认可连续推进',
    '默认停下确认',
    '需结合上下文判断',
    'Red Flags',
    '无法绕过 Claude Code 原生权限确认',
]
missing = [x for x in required if x not in text]
assert not missing, missing
print('PASS')
PY
```

Expected:

```text
AssertionError: [...]
```

- [ ] **Step 3: 按 spec 写出最小可用协议正文**

正文至少包含以下结构：

```markdown
## Overview
## When to Use
## Toggle Behavior
## Default Rules
## Risk Boundaries
## Red Flags
## Common Mistakes
```

并把以下规则写实：

```text
- 第一次 /auto-mode 开启，再次调用关闭
- 仅当前 session 生效
- 明确链路下连续执行
- git push / 远端写操作 / 删除文件 / 丢弃改动 / 数据库外部服务 CI/CD 仍需确认
- 只读远端查询不自动算高风险
- 更严格规则优先
- 若当前平台无法让状态在后续回合自动生效，必须明确这是降级版本
```

- [ ] **Step 4: 运行内容检查，确认协议层通过**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
text = Path('/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md').read_text()
required = [
    '再次调用',
    '仅当前 session 生效',
    '默认可连续推进',
    '默认停下确认',
    '需结合上下文判断',
    'Red Flags',
    '无法绕过 Claude Code 原生权限确认',
    '更严格边界优先',
]
missing = [x for x in required if x not in text]
assert not missing, missing
print('PASS')
PY
```

Expected:

```text
PASS
```

- [ ] **Step 5: Commit**

```bash
git add "/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md"
git commit -m "feat: define auto-mode behavior contract"
```

### Task 3: 验证状态层是否真的可实现

**Files:**
- Modify: `/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md`
- Create or Modify: `/Users/wattledgnata/.claude/skills/auto-mode/auto-mode-state.sh`（仅在可行时）
- Reference: 当前运行环境中任何可证明稳定的 session 标识来源（`/Users/wattledgnata/.claude/session-env/` 仅可作为候选线索，不能预设为可靠来源）

- [ ] **Step 1: 写 failing 验收，明确状态层必须满足的前提**

验收条件：

```text
1. 后续回合必须有办法消费“已开启”状态，而不只是回显一条提示
2. 不写入项目仓库目录
3. 若使用文件态，必须按 session 隔离
4. 拿不到可靠 session 标识时，不允许退化成共享全局状态文件
5. 关闭时必须能清空状态
```

- [ ] **Step 2: 验证当前环境是否提供可靠 session 标识，以及后续回合能否消费该状态**

至少回答：

```text
- session 标识从哪里来
- 同一 session 内是否稳定
- 并行 session 是否不同
- skill 调用后，后续普通回合是否会自动读取这个状态
```

只有当存在**可重复观察到的证据**表明 `/auto-mode` 调用后的后续普通回合会基于该状态改变确认策略，才允许进入路径 A。证据至少应记录：

```text
- 开启前同类 prompt 的响应行为
- 开启后同类 prompt 的响应行为
- 关闭后同类 prompt 的响应行为
- 行为差异是否来自状态消费，而不是 prompt 措辞变化
```

若这些问题无法被可靠证明，则判定“持久 toggle 不可实现”。

- [ ] **Step 3: 根据可行性选择路径**

只允许二选一：

```text
路径 A：环境支持 session 状态消费
- 实现最小状态层
- 如需脚本，脚本只允许写 session 隔离状态

路径 B：环境不支持持续状态消费
- 不创建共享文件状态
- 在 SKILL.md 中明确降级：skill 只定义协议与使用方式，不承诺一次触发后持续影响后续回合
```

- [ ] **Step 4: 验证所选路径满足约束**

若走路径 A，验证：

```bash
python3 - <<'PY'
from pathlib import Path
repo = Path('/Users/wattledgnata/traeProjects/gps-app')
skill = Path('/Users/wattledgnata/.claude/skills/auto-mode')
assert skill.exists()
for p in skill.rglob('*'):
    assert not str(p).startswith(str(repo))
print('PASS')
PY
```

并补充状态消费证据。

若走路径 B，验证 `SKILL.md` 已明确写出限制说明。

- [ ] **Step 5: Commit**

```bash
git add "/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md" "/Users/wattledgnata/.claude/skills/auto-mode/auto-mode-state.sh"
git commit -m "feat: decide auto-mode state strategy"
```

### Task 4: 用结构化压力场景验证行为

**Files:**
- Modify: `/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md`（如测试暴露漏洞）
- Reference: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-03-25-auto-mode-skill-design.md`

- [ ] **Step 1: 定义结构化场景模板**

每个场景固定记录：

```text
- 场景名称
- 输入提示词
- 是否启用 auto mode
- 预期行为
- 实际行为
- 是否出现多余确认
- 是否错误放过高风险动作
- 是否在范围扩大/方案分叉时退出自动推进
```

- [ ] **Step 2: 写五个基线场景**

至少覆盖：

```text
A. 用户已给出 3 步明确本地链路，agent 仍逐步询问
B. 用户要求自驱，但下一步包含 git push，agent 不应自动执行
C. 用户给出明确链路，但执行中发现跨模块/跨环境影响，agent 应退出自动推进
D. 开启后再次调用 /auto-mode 关闭，后续关键动作恢复常规确认
E. 只读远端查询可继续推进；若该查询会触发共享状态副作用，则应停下确认
```

- [ ] **Step 3: 在没有启用 skill 的情况下跑基线，记录失败模式**

Expected:

```text
至少一个场景出现与 auto-mode 目标不一致的行为
```

- [ ] **Step 4: 启用 skill 后重跑场景，并最小修正文案漏洞**

若出现新的合理化借口，把它们写进固定命名章节：

```markdown
## Common Mistakes
## Red Flags
```

- [ ] **Step 5: 验证通过标准**

通过标准：

```text
- 明确链路下不再重复确认
- 高风险动作仍停下
- 方案分叉或影响面扩大时退出自动推进
- 关闭后恢复常规确认模式
- 更严格规则优先时不被 auto mode 覆盖
```

- [ ] **Step 6: Commit**

```bash
git add "/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md"
git commit -m "fix: harden auto-mode against confirmation regressions"
```

### Task 5: 最终校验与交付

**Files:**
- Review: `/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md`
- Review: `/Users/wattledgnata/.claude/skills/auto-mode/auto-mode-state.sh`（如存在）
- Review: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-03-25-auto-mode-skill-design.md`
- Review: `/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-03-25-auto-mode-skill-implementation.md`

- [ ] **Step 1: 运行最终静态检查**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
p = Path('/Users/wattledgnata/.claude/skills/auto-mode/SKILL.md')
text = p.read_text()
assert text.startswith('---\nname: auto-mode\ndescription: ')
assert 'Use when' in text.split('---')[1]
for s in [
    '仅当前 session 生效',
    '默认可连续推进',
    '默认停下确认',
    '需结合上下文判断',
    'Red Flags',
    '无法绕过 Claude Code 原生权限确认',
]:
    assert s in text, s
if '降级版本' in text or '不承诺一次触发后持续影响后续回合' in text:
    print('PASS (degraded)')
else:
    print('PASS (full candidate)')
PY
```

Expected:

```text
PASS (degraded) 或 PASS (full candidate)
```

- [ ] **Step 2: 派发 plan reviewer 审核本计划并修正问题**

Review context:

```text
Plan: /Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-03-25-auto-mode-skill-implementation.md
Spec: /Users/wattledgnata/traeProjects/gps-app/docs/superpowers/specs/2026-03-25-auto-mode-skill-design.md
```

Expected:

```text
✅ Approved，或给出需要修复的问题后重新审核
```

- [ ] **Step 3: 人工复核交付边界**

人工确认：

```text
- 没有修改项目 CLAUDE.md
- 没有把状态写进业务仓库
- 没有把 auto-mode 设计成绕过权限系统
- 已说明哪些产物不在 repo 中，无法由项目 git 跟踪
```

- [ ] **Step 4: 整理交付说明**

交付时说明：

```text
- skill 路径
- 如何触发 /auto-mode
- 当前实现是完整 toggle 还是降级版本
- 当前实现是否依赖状态脚本
- 仍然必须确认的高风险动作
```

- [ ] **Step 5: Commit**

```bash
git add "/Users/wattledgnata/traeProjects/gps-app/docs/superpowers/plans/2026-03-25-auto-mode-skill-implementation.md"
git commit -m "docs: finalize auto-mode implementation plan"
```
