## Context

V2 Records tab 当前两条历史列表都已接真实 Repository 数据：
- PERFORMANCE 子页 RecentRuns：消费 `TestSessionViewModel.recentRuns: StateFlow<List<TestResultSummary>>`（最近 N 条加减速测试记录）
- LAPS 子页 SESSION HISTORY：消费 `recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>>`（当前赛道最近 N 条圈速 session）

但**没有删除入口**。用户失败 / 重复 / 测试用 session 只能整体清 app data 一次性删全。本 round 加最小可用"长按列表行 → AlertDialog 确认 → 删除"。

PERFORMANCE 侧的 cascade 删除链路已经齐全（`TestResultRepository.deleteResult` 含 binary 路径白名单），LAPS 侧需要从头建：DAO `@Delete` + crossing 关联清理 + binary 清理。

## Goals / Non-Goals

**Goals:**

- Records → PERFORMANCE / LAPS 两条列表的每一行都支持"长按 → 确认 → 删除"
- 删除是 **cascade**：lap session 删除一并清 crossing_events 行 + binary 文件；test record 沿用现有 `deleteResult`（已含 binary cascade）
- AlertDialog 风格沿用 baseline Material3（参考 `LapLiveScreen.EndConfirmationDialog`），不引入 V2 cut-corner 自定义
- 删除后 list 自动刷新（Room Flow 是 reactive，repository 不需要主动 emit）
- binary 路径清理用 `/telemetry/` 路径白名单防穿越（沿用 `TestResultRepository.deleteResult` 已有的安全策略）

**Non-Goals:**

- 不做批量删除 / 多选 mode（user 拍板暂只单条）
- 不做撤销 / undo snackbar
- 不做 V2 cut-corner 自定义 dialog
- 不在详情屏（`PerformanceResultScreen` / `LapSessionDetailScreen`）加删除按钮（user 拍板）
- 不做"30 天回收站"机制
- 不改 Room schema
- 不动 PERFORMANCE 测试记录 cascade（`deleteResult` 已存在，复用）

## Decisions

### Decision 1: 删除范围 PERFORMANCE + LAPS 都做（user 拍板 B）

**对比**：

| 方案 | 工作量 | 风险 |
|---|---|---|
| **A** PERFORMANCE only | 小（半小时） | 用户立刻问"为啥圈速不能删" |
| **B PERFORMANCE + LAPS（采用）** | 中（~1.5 小时） | 多写 LAPS 侧 cascade + 单测 |

**理由**：user 拍板。"历史成绩"语义包含两类，不做 LAPS 立刻就要补一轮。

### Decision 2: 仅列表长按入口（不动详情屏）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **仅列表长按（采用）** | 入口集中在 list 一处，行为统一 | 详情屏看到记录想删要 back 出去 |
| 列表 + 详情屏 ⋮ 菜单 | 任何位置都能删 | 工作量翻倍，PerformanceResultScreen / LapSessionDetailScreen 都要改 |

**理由**：user 拍板。详情屏入口是 polish，留 follow-up backlog。

### Decision 3: AlertDialog 用 baseline Material3 风格

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **Material3 AlertDialog（采用）** | baseline 已有 `EndConfirmationDialog` 范式参考；零新组件；行业标准 UX | 不是 V2 cut-corner 风格，跟 V2 视觉系统轻微违和 |
| V2 cut-corner 自定义 dialog | 视觉统一 | 需新建 Composable + 处理弹层动画 + 暗角阴影；scope 过大 |

**理由**：user 拍板"暂时就长按出确认弹窗"——简单为先。Material3 AlertDialog 在 V2 体系内被多次接受（`EndConfirmationDialog` / `BleScanBottomSheet` 等），不算视觉断裂。

### Decision 4: lap session cascade 删除顺序

**链路**：

```
deleteSession(sessionId):
  1. 读 entity（拿 binaryFilePath）
  2. crossingDao.deleteCrossingsBySessionId(sessionId)  // 关联表先删（TelemetryRepository 字段名 crossingDao）
  3. sessionDao.deleteSession(entity)                   // 主表后删（TelemetryRepository 字段名 sessionDao）
  4. binary file delete（路径白名单 /telemetry/）
```

**为什么这个顺序**：

- 关联 crossing 先于主 session 删除：避免 Room 外键约束（如果将来加）抛 FK violation；当前没外键也保持"先关联后主"的好习惯
- binary 文件最后删：先确认 db 删除成功，再清 fs；如果 binary 删失败 → db 已成功 → 文件残留但 db 一致（GC 时可清）；如果 db 删失败 → binary 没动 → 跟操作前完全一致（可重试）
- 三步都是 suspend，在 ViewModel coroutine 顺序执行（不需要事务原子性，因为单 session 删除失败回滚意义不大）

### Decision 5: TrackTechRow 加 onLongClick 用 combinedClickable

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **`combinedClickable(onClick = ..., onLongClick = ...)`（采用）** | Compose 标准 API；点击/长按都触发 ripple；现有 onClick 行为不破坏 | onLongClick 是可选参数，调用方零侵入 |
| pointer event 自己处理 | 完全控制 | 没必要，combinedClickable 够用 |
| 把 long press 放外层 Modifier | 容易跟 row 内子组件冲突 | combinedClickable 已封装好 |

**`combinedClickable` 是 `ExperimentalFoundationApi`**，需要 `@OptIn`。

### Decision 6: dialog 状态用 remember（不上 rememberSaveable）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **普通 `remember<DeleteCandidate?>`（采用）** | 简单，零序列化开销；本 round 改动量最小 | 配置变化（旋屏 / 切深色）丢 state，dialog 消失；用户需要重新长按 |
| `rememberSaveable<DeleteCandidate?>(null)` | 配置变化保留 dialog state | 需要 `Parcelable` 或 `mapSaver` 实现，本 round 多增约 30 行胶水代码 |

**理由**：本 round 是最小可用实现。V2 实时屏被强制横屏，Records tab 不强制方向，普通用户旋屏概率低；即使 dialog 消失也只是重新长按一下，UX 损失小。`rememberSaveable` 收益相对工作量不抵 → 留 follow-up backlog（task §12.4）。

```kotlin
sealed interface DeleteCandidate {
    val titleHint: String
    data class TestRecord(val id: String, override val titleHint: String) : DeleteCandidate
    data class LapSession(val id: String, override val titleHint: String) : DeleteCandidate
}
```

`titleHint` 用于 dialog 副标显示"删除：0-100 km/h, 4.21 s, May 18 10:35"。

## Risks / Trade-offs

- **[lap session cascade 部分失败]** → crossing 删了但 session 没删（极少概率，db 异常） → list 显示一条空 session，crossing query 返回空。**Mitigation**：cascade 顺序保证 worst-case 残留是"db inconsistent 但不 crash"，UI 仍可用；后续如要严谨可加事务包装（`@Transaction`）
- **[binary 文件删失败 / 文件已不存在]** → silent ignore（沿用 `deleteResult` 同款策略）。**Mitigation**：路径白名单 `canonicalPath.contains("/telemetry/")` 防穿越；`File.delete()` 返回 false 不抛异常
- **[长按误触]** → 用户实际只想点击进详情但触发了 long press。**Mitigation**：AlertDialog 二次确认就是这个功能的目的；实测 Compose `combinedClickable` 长按阈值 500ms，正常点击不会触发
- **[多 change 并行 RecordsHomeScreen 共享文件]** → 当前主区 RecordsHomeScreen 被 F round 重写过，本 round 改的是 row 的 onLongClick / dialog 状态，不动 view 数据派生 → 与 A round（fix-lap-binary-ts-hygiene）函数级不重叠。**Mitigation**：worktree 创建后 rebase 拉 A round 改动；看板 §6 登记
- **[FakeTelemetrySessionDao / FakeCrossingEventDao 编译破坏]** → 加 abstract 方法后既有 fake 不实现会编译失败。**Mitigation**：grep 所有 `: TelemetrySessionDao` / `: CrossingEventDao` 实现一并加 override（沿用 F round 的同款修复模式）

## Migration Plan

无 schema / 协议 migration（只加 DAO 方法）。

部署步骤：

1. 主区开 worktree `.worktrees/add-history-deletion`，切到 `feature/track-tech-v2`
2. 看板 §5 登记本 round；§6 登记 `RecordsHomeScreen.kt` / `TestSessionViewModel.kt` / `TelemetryRepository.kt` 共享文件占用
3. 实施 tasks.md 各阶段
4. 编译 + 单测全绿
5. 真机验证（华为 `8KE0219522008434`）：跑过测试 + 圈速 session → 长按删除 → 列表实时刷新 → 二次确认 dialog 行为正确
6. commit + ff-only 合回主区
7. push 等 user 拍板

回滚策略：

- 全部新增方法 + 1-2 个修改的 UI 文件。回滚 = revert 4-5 个 commit
- 不动 schema → 数据无回滚成本

## Open Questions

无。所有决策已与 user 在 explore 阶段对齐：

- 删除范围 = PERFORMANCE + LAPS（user 拍 B）
- 仅列表长按入口（不动详情屏）
- baseline Material3 AlertDialog
- cascade 严谨（crossing + binary）
