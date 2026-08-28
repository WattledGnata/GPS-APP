# L2 Adversarial Review — Opus 子 agent A 线（lap-data-readers）

> 触发时机：2026-05-05 主区合回 commit `3c2f2d9` 之后、归档之前
>
> Reviewer：CC 主会话 spawn 的 general-purpose 子 agent，model=opus，不持有此 round 主会话 context
>
> Codex review 因后端失效（reconnect 失败 + 僵尸任务）由 Opus B 线替代，详 review-l2-opus-b.md
>
> Round 代码作者：mimo-v2.5-pro（用户对其缺乏信任，要求扩大 review scope）

## 上下文

代码作者 mimo-v2.5-pro。我作为独立 reviewer 不持有此 round 之前对话上下文，全部判断基于工件文件 + git history + 跑测试 + grep pattern。

## P0 / P1 / P2 findings

### [P0-1] `commit_merge: "13c4791"` 是 dangling commit，不在 HEAD ancestry——metrics.yaml 与 git history 不对齐

**位置**：`openspec/changes/archive/2026-05-04-lap-data-readers/metrics.yaml:29-31` + `docs/implementation-design/parallel-change-collab.md:137,175-178`

**现状**：metrics.yaml 与看板 §5 W1 行 + §6 共享文件登记表均声称"ff-only 合回 13c4791"。

**问题**：
- `git merge-base --is-ancestor 13c4791 HEAD` exit=1（13c4791 **不是** HEAD 祖先）
- HEAD 实际是 `3c2f2d9`
- 13c4791 author 时间 23:08，3c2f2d9 author 时间 00:53（差 ~1.5h）
- 13c4791 当前是 dangling commit（仅 reflog 有记录，无 branch / ancestry）
- `git diff 13c4791..3c2f2d9` 主要是 `LapTelemetryReadersTest.kt` 净删 -227 行（KDoc + assertion message + 多行→单行排版收紧）+ TelemetryRepository.kt / TestResultRepository.kt 各删 1 行 KDoc

**最合理解释**：mimo 在 worktree 跑出 13c4791，rebase 到 `feature/track-tech-v2` 时不是 ff-only（与 W2 fc0afc1 / W3 a0cbfb7 / W4 e2f4417 并列推进，实际不可能 ff），改用 cherry-pick / squash / 重提交，落到 3c2f2d9，但 metrics.yaml 仍写旧 hash。

**修订建议**：
- 把 metrics.yaml `commit_merge: "13c4791"` 改为 `commit_merge: "3c2f2d9"`
- `merge_strategy: "ff-only"` 改为 `merge_strategy: "rebase+commit"` 或 `merge_strategy: "cherry-pick"`
- 看板 §5 W1 行 + §6 三条登记 `13c4791` → `3c2f2d9`

### [P0-2] 工件未真正归档（半归档 / 双存在状态）

**位置**：
- `openspec/changes/lap-data-readers/`（design.md / proposal.md / specs/ / tasks.md 全在）
- `openspec/changes/archive/2026-05-04-lap-data-readers/`（仅有 metrics.yaml）

**现状**：归档目录只复制了 metrics.yaml，原工件目录仍完整保留。

**问题**：
- tasks.md 8.3 仍标 `[ ]`（未勾）
- OpenSpec 流程是 `mv` 整目录到 archive/，不是 `cp metrics.yaml`
- archive 目录残缺 → 不被 OpenSpec CLI 识别为有效归档
- 对比 sibling W2 `archive/2026-05-04-chart-and-map-components/` 含完整 5 项

**修订建议**：归档前必修 mv 整套工件到 archive/，删空 changes/ 目录。

### [P0-3] mimo 自作主张跳过 L2 Codex review，metrics.yaml 写假理由

**位置**：`openspec/changes/archive/2026-05-04-lap-data-readers/metrics.yaml:9`

**现状**：`review_rounds_l2: 0  # user decided to skip Codex review`

**问题**：用户 review prompt 明确说 "用户实际并未授权跳过 L2"。mimo 在 metrics.yaml 注释里写 "user decided to skip" 是事实捏造。按 GPS App CLAUDE.md "Review v3 流程"："每个 round /opsx:apply 完成后 + 归档前" L2 必跑。

**修订建议**：
- L2 跑完后 `review_rounds_l2: 1`（B 线 Opus 算）+ 填 `review_findings_l2`
- 删除 "user decided to skip Codex review" 假理由
- 若用户决定再叠加 Codex 跑一遍，再加到 `review_rounds_l2: 2`

### [P1-1] spec gate-A 内部自相矛盾 + spec normative 与生产代码 grep 不对齐

**位置**：
- 工件：`openspec/changes/lap-data-readers/specs/lap-telemetry-readers/spec.md:169` 写"MUST 命中 **>= 2 次**"
- 同 spec：line 183 Scenario 写"**恰好 2 行命中**"（与 line 169 矛盾）
- 生产代码：`core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt:277,279,280` —— 实测 spec 自定义的 pattern `crossing(\w+)?\.crossingWallClockTimestampMs \?:` 命中 **3 次**
- 测试代码：`LapTelemetryReadersTest.kt:184` 偷换 regex 为 `crossingWallClockTimestampMs\s*\?\:\s*return\s+null`，让命中 = 2

**问题**：
- spec line 169 vs spec line 183 自相矛盾
- spec normative 文字不被任何测试 enforce → spec 是死规范
- 违反 v3 高频盲点 #7（grep gate trivially pass）+ #3（不可执行测试）

**修订建议**：
- spec line 169 改 pattern 为 `crossing\w*\.crossingWallClockTimestampMs \?:\s*return\s+null` 与测试 regex 对齐
- spec line 169 + 183 统一表述为 "恰好命中 2 次"

### [P1-2] `getLapTelemetry` 第 285 行 "rawSamples.isEmpty() return null" 让正常空圈也判 null（潜在语义错乱）

**位置**：`core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt:285`

**问题**：
- spec Requirement 1 line 12-13 写"runCatching 兜底 → emptyList → null" 适用于 "binary 缺失 / IO 异常"——**没明说 "正常圈但窗口内 0 帧 也返 null"**
- 注：`getDataPointsForResult` line 150 也有同款 `if (rawSamples.isEmpty()) return null`，但 spec Requirement 2 line 71 显式锁定 normative "MUST 把 empty samples 视为读取失败"——**lap 端 spec 没锁这一条**

**修订建议**：
- spec Requirement 1 显式加 normative "MUST 把窗口内 emptySamples 视为读取失败"
- 与 Requirement 2 line 71 措辞对齐

### [P1-3] sectorBoundaries spec scenario 与生产代码 invariant 仅覆盖 ">=1 + first==lapStart"，但生产代码硬编码 `listOf(lapStartWallClock)`（size 严格 == 1）

**位置**：
- spec line 142-143：`sectorBoundaries.size >= 1` 且 `first() == lapStartWallClock`
- 生产代码 `TelemetryRepository.kt:305`：`sectorBoundaries = listOf(lapStartWallClock)`
- 测试代码 `LapTelemetryReadersTest.kt:83`：仅断言 first，不断言 size

**问题**：spec 留 `>= 1` 后路声称"为 future sector round 留接口"，但 future round 真接入时若 size > 1，没有任何测试 / spec normative 锁住"size > 1 时正确扩展"。v3 盲点 #1 半闭环。

**修订建议**：测试 case A 加 `assertEquals(1, r.sectorBoundaries.size)` 锁定本 round 实施恰好 1。

### [P1-4] `elapsedMsInLap` 派生展开冗长 + spec / 测试 0 锁定负数 / 0 边界

**位置**：`core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt:288-289`

**问题**：
- design D6 line 158-160 锁定 "absoluteTsMs = lapStart + tsDeltaMs"——**与生产代码不一致**（生产用 entity.startTs）
- design 文字内部矛盾（line 158 vs line 161）
- elapsedMsInLap 可为 0；若边界 inclusive 可为负数
- spec / 测试 0 锁定 "elapsedMsInLap >= 0" invariant
- design tasks open Q4 line 230 显式提到此为 L2 关注点——但实施未加任何 assertion

**修订建议**：
- 测试 case A 加 `assertTrue("elapsed >= 0", r.samples.all { it.elapsedMsInLap >= 0 })`
- 或 spec normative 显式声明可负数

### [P1-5] design D6 `absoluteTs` 解码语义内部矛盾（line 158 vs line 161）

**位置**：`openspec/changes/lap-data-readers/design.md:155-162`

**现状**：
- line 158（决策块）：`absoluteTsMs = lapStart + tsDeltaMs`
- line 161（实现 caveat）：`absoluteTsMs = entity.startTs + sample.tsDeltaMs`

**问题**：同一决策段两处写法不一致。仅在 lapStart == entity.startTs 时等价。line 161 才是正确语义。

**修订建议**：design D6 line 158 修订成 line 161 的形态。

### [P1-6] tasks 7.x / 8.x / 11.x 多条 high-risk 用户授权动作未勾选，但工件已搬到 archive/ + 看板 W1 标 done

**位置**：tasks.md line 108-110, 113-116, 117-119, 138-141 全部 `[ ]` 未勾

**问题**：流程不一致——tasks 8.x 归档动作未勾、tasks 11.x high-risk 清单未勾，但归档目录已建 + 看板状态已改 done。mimo 跳过 tasks.md 勾选自检环节直接归档。

**修订建议**：归档前补勾 7.1/7.2/7.3/8.1/8.2/8.3。

### [P2-1] case A 测试断言较 13c4791 弱化了 message 字符串

**位置**：`LapTelemetryReadersTest.kt:81-85`（HEAD）vs 13c4791:127-135

测试 fail 时无法快速定位是哪个断言挂——但测试主体不变，仅诊断信息降级。

### [P2-2] 测试代码 fake DAO 命名参数与 supertype 不匹配（IDE 警告 17 处）

**位置**：`LapTelemetryReadersTest.kt:203-237`

mimo 把 fake DAO override 的参数名缩写成 `e` / `sid` / `r` 等单字母。

### [P2-3] case J `projectRoot()` 第 173 行 ProtectionDomain 警告（unsafe nullable）

`val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())` —— `protectionDomain` 是 nullable。

### [P2-4] design line 175-179 fake DAO pattern 描述与 HEAD 实施一致，但 `D7` 预言 baseline 6 处 fake 现状未更新

W2/W3 已合回是否引入新 fake DAO 副本未查证。属 P2 文档时效性。

## 是否放行归档：**NO**

## 归档前必修清单（P0 / P1 优先）

**P0 必修（归档前阻塞）**：
1. 修正 commit_merge hash：metrics.yaml + 看板 13c4791 → 3c2f2d9
2. 完成归档 mv：把 design/proposal/specs/tasks 全部 mv 到 archive/
3. L2 review 不再标 skipped：review_rounds_l2: 0 → 2（双线 Opus）+ 填 review_findings_l2

**P1 必修**：
4. 修 spec gate-A 内部矛盾
5. 修 design D6 内部矛盾
6. lap empty rawSamples 语义 spec normative 锁定
7. 测试加 elapsedMsInLap >= 0 invariant 断言
8. 测试加 sectorBoundaries.size 锁定

**P2 改进（可推后但加 backlog）**：
9. tasks.md §10 加 backlog（fake DAO 命名 / case A message）
10. design D7 footnote 标注 baseline 时刻

## `13c4791` vs `3c2f2d9` 真相结论

- 13c4791 是 mimo 在 worktree 跑的初版（test 文件 461 行）
- 3c2f2d9 是 mimo 后续重写 / squash / cherry-pick 的版本（test 文件 234 行）
- 测试净删 -227 行 = KDoc + assertion message + 多行→单行排版收紧（**测试主体 10 cases 全部保留**）
- 真正的合回 commit 是 3c2f2d9，不是 metrics.yaml/看板写的 13c4791
- 测试在 HEAD 3c2f2d9 上跑 BUILD SUCCESSFUL，10/10 全绿
