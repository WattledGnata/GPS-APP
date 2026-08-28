# Design: future-sector-derivation

## Context

`getLapTelemetry`（`core/data/.../repository/TelemetryRepository.kt:284-321`）当前对 `sectorBoundaries` 写死 `listOf(lapStartWallClock)`。经调查（proposal Why §数据源调查结论），sector 过线**确有记录**：TFIC 赛道定义 2 个 Sector 门，engine 检测并写 `CrossingEvent(gateType=Sector)`，ViewModel 无差别持久化到 Room（带 wallClock）。reader 第 286 行已把全部 crossing 读进 `crossings` 变量，只是没用 sector crossing 派生 boundaries。本 round 在已有数据上做纯 reader 派生。

关键现状数据（design 决策前置硬数据）：

- `crossings` 变量（第 286 行）= `crossingDao.queryBySessionId(sessionId)`，含 StartFinish + Sector，accepted true/false 混杂，每条带 `gateType: String` + `crossingWallClockTimestampMs: Long?`。
- lap 窗口 `[lapStartWallClock, lapEndWallClock]` 已在第 291-292 行从 accepted StartFinish 配对算出。
- SectorBar 消费契约（`SectorBar.kt:18-35` `computeSectorBounds`）：`sectorBoundaries` 是 wallClock 升序列表，首项 == lapStart；相邻对 `windowed(2)` 画段；末元素 < lapEnd 时自动补 lapEnd（L29）；空列表回退单段 `[0, totalWidth]`（L25-27）。
- LapTelemetry 头注释（`LapTelemetry.kt:26`）已声明"sectorBoundaries 首项 == lapStartWallClock"——本 round 派生 MUST 维持该不变式。

---

## Decision 1: sector boundary 数据源 = lap 窗口内 accepted Sector crossing 的 crossingWallClockTimestampMs

**选择**：从 reader 已读的 `crossings` 中 filter `gateType.equals("Sector", ignoreCase=true) && accepted && crossingWallClockTimestampMs != null`，再 filter wallClock 落在 `[lapStartWallClock, lapEndWallClock)` 半开窗口，按 wallClock 升序取出，前置 `lapStartWallClock` 组成 `sectorBoundaries`。

**Alternatives 对比**：

| 方案 | 描述 | 取舍 |
|---|---|---|
| **A（选中）· lap 窗口内 accepted Sector crossing wallClock** | filter Sector + accepted + wallClock 非空 + 窗口内 → 升序 → 前置 lapStart | ✅ 数据源真实（engine 已写 sector 过线插值毫秒 wallClock）；✅ 与 StartFinish 窗口同时钟域（都用 `crossingWallClockTimestampMs`，无跨时钟域混用）；✅ 复用已读内存，0 额外 DB 查询；✅ robust 回退（空集→单段） |
| **B · 按 Track.sectorGates 几何位置等距切分圈时长** | 不用 crossing，按赛道 sector 门数量把 lapDurationMs 等分 | ❌ 伪造数据（等分≠真实过线时刻，sector 时长本就不均匀，这正是用户想看的）；❌ reader 在 core/data 无法访问 `feature/test` 的 Track 模型（模块边界）；❌ 违反"绝不伪造假 sector 数据"原则 |
| **C · 用 crossingTimestampMs（GPS 协议时钟）排序** | sector crossing 也有 GPS 协议时钟字段 | ❌ 跨时钟域：lap 窗口用 wallClock，若 sector 用 GPS 协议时钟（mod 3,600,000 回绕 + hourStart 切换），窗口判定 `>= lapStart && < lapEnd` 会把 sector wallClock 与 GPS 时钟混比 → 错位（`unify-lap-count-pairing-semantics` round 已确立"圈配对身份统一 wallClock"的教训，sector 必须同源 wallClock） |
| **D · 用 SectorEntry.crossedAtMillis（in-memory engine 内部时刻）** | engine 的 ActiveLap.sectorEntries 有 crossedAtMillis | ❌ reader 是离线读 Room，拿不到 in-memory ActiveLap；❌ crossedAtMillis 是 GPS 协议时钟域插值毫秒（不是 wallClock），与 lap 窗口跨时钟域 |

**Rationale**：方案 A 是唯一既"用真实过线数据"又"与现有 lap 窗口同时钟域（wallClock）"又"复用内存零额外查询"的方案。拒绝 B 因为它伪造数据违反核心原则且跨模块；拒绝 C/D 因为跨时钟域混用（重蹈 `fix-lap-crossing-clock-hygiene` / `unify-lap-count-pairing-semantics` 已修复的双时钟域污染覆辙）。

---

## Decision 2: accepted 过滤——只取 accepted=true 的 Sector crossing

**选择**：filter `accepted == true`。

**Alternatives**：

| 方案 | 取舍 |
|---|---|
| **只取 accepted=true（选中）** | ✅ accepted Sector crossing = engine 判定真实过了该 sector 门（期待门顺序正确 + 方向正确 + 速度达标）；engine 给它填了插值毫秒 wallClock（`handleSectorCrossing` 第 278-279 行 accepted 分支用 `interpolatedMillis`）；rejected sector（`UnexpectedGateOrder` / `WrongDirection`）不代表真实分段点 |
| **取全部 Sector（含 rejected）** | ❌ rejected sector crossing 的 wallClock 用 `currentSample.timestampMillis` 降级（第 280-282 行），不是过线插值时刻，且 `UnexpectedGateOrder`（乱序过门）会引入错误的 boundary，污染 SectorBar 分段 |

**Rationale**：与 StartFinish 窗口派生（第 288 行 `it.accepted`）完全对齐——只信任 engine accepted 的过线。rejected 是 engine 显式标记的"不算数过线"，不应成为 sector 分段边界。

---

## Decision 3: 窗口判定用半开区间 [lapStartWallClock, lapEndWallClock)

**选择**：`wallClock >= lapStartWallClock && wallClock < lapEndWallClock`。

**Alternatives**：

| 方案 | 取舍 |
|---|---|
| **半开 [start, end)（选中）** | ✅ `>= start` 容纳圈内全部 sector，同时排除"恰在圈起点"的退化 sector（圈起点已是 sectorBoundaries[0]，若有 sector 门恰好与 start-finish 重合会重复）；`< end` 排除圈终点 + 下一圈的 sector（圈终点由 SectorBar 自动补 lapEnd，不该出现在 boundaries 里；下一圈 sector 属别的圈窗口）。与 engine 圈归属判定 `>= startedAt && < finishedAt`（`LapTimingEngine.kt:184-185`）同款半开区间，全栈一致 |
| **闭区间 [start, end]** | ❌ 圈终点 wallClock 可能恰等于某 sector 过线（罕见但理论存在），且下一圈首个 sector 若 wallClock == lapEnd 会被误纳入；SectorBar 末段补 lapEnd 后会出现 size-0 段 |
| **不做窗口过滤（信任 lapIndex 标注）** | ❌ `CrossingEventEntity.lapIndex` 是 engine 写的 1-based 引擎内部编号，与 wallClock 配对身份（`unify-lap-count-pairing-semantics` 确立的 zero-based lapIndex）**不同源**——用它过滤会重蹈"圈编号语义分歧"覆辙；wallClock 窗口才是唯一权威 |

**Rationale**：半开区间与 engine 圈归属判定 + reader StartFinish 窗口语义全栈一致，避免边界重复/越界，且不依赖跨 round 不同源的 `lapIndex` 字段。

---

## Decision 4: #16 跨 round 共享字段 drift 分析（本 round 核心，user 要求 review 的原因）

`LapTelemetry.sectorBoundaries`（`core/domain/.../model/LapTelemetry.kt:39`）是公共 domain 数据契约字段。本 round 把它从"恒为单元素 `[lapStart]`"变为"可多元素 `[lapStart, s1, s2, ...]`"，属 v3 高频盲点 **#16（跨 round 共享字段扩展未触发已合回 round drift 检查）**。MUST 列消费此字段的已合回 round + 逐个分析"从单段变多段是否破坏"。

**消费 `sectorBoundaries` 的全部站点（grep `sectorBoundaries` feature/ core/ 已核实）**：

| 站点 | round 来源 | 合回状态 | 读 sectorBoundaries 的方式 | 单段→多段是否破坏 |
|---|---|---|---|---|
| **生产者** `TelemetryRepository.getLapTelemetry:317` | W1 `lap-data-readers`（archive/2026-05-04） | archived | 写入端（本 round 改这里） | — 本 round 修改点 |
| **消费 1** `SectorBar.kt:39,45-46,54-67`（生产组件） | W2 `chart-and-map-components`（合回 fc0afc1） | done | `computeSectorBounds(boundaries, lapStart, lapEnd, width)` → `windowed(2)` 画段；`sectorBoundaries.first() != lapStartWallClock` 时仅 Log.w 警告（不崩）；cursor 落段高亮 | ✅ **无破坏，本就为多段设计**。`computeSectorBounds`（L18-35）显式处理 `boundaries.size < 2` / `boundaries.isEmpty()` / `allBounds.last() < lapEnd` 补 lapEnd 三种情况——单段是它的退化分支，多段是它的正常分支。多段反而是它被设计出来要画的目标 |
| **消费 2** `core/domain/.../usecase/LapAlignment.kt`（W3 算法） | W3 `lap-comparison-time-align`（合回 a0cbfb7） | done（待 push） | **grep `sectorBoundaries` 在 LapAlignment.kt 0 命中** | ✅ **无破坏，根本不读**。LapAlignment 输入 `List<LapTelemetry>` 但只消费 `samples`（按 distance 重采样网格），输出 `LapAlignmentResult`（无 sectorBoundaries 字段，见 `LapAlignment.kt:14-46`）。它对 sectorBoundaries 透明，size 从 1 变 N 对它完全无感 |
| **测试** `MockTelemetry.kt:54,63,85` | W2 测试 | done | mock 已造**3 元素** sectorBoundaries（`[lapStart, lapStart+d, lapStart+2d]`），多圈 offset 平移 | ✅ **已是多段**，W2 测试早已用多段 mock 验证 SectorBar，本 round 让生产 reader 与 mock 对齐（消除 mock 多段但生产单段的契约漂移） |
| **测试** `LapTelemetryReadersTest.kt:84` | W1 测试 | archived | `assertEquals(r.lapStartWallClock, r.sectorBoundaries.first())` | ✅ **不破坏**。本 round 派生后首元素仍 == lapStart（Decision 1 前置 lapStart），该断言继续 pass；本 round 在同套件补多段 case |
| **测试** `LapAlignmentTest.kt`（多处） | W3 测试 | done | 构造 LapTelemetry 时填 `sectorBoundaries = listOf(...)` 作为输入 fixture | ✅ 不破坏（W3 不读该字段，fixture 值无所谓；本 round 不改 W3 测试） |

**#16 结论**：本 round 是 #16 的**良性扩展**——没有任何已合回 round 假设 `sectorBoundaries.size == 1`。SectorBar（唯一真消费者）本就为多段设计，单段是它的退化情形；LapAlignment 完全不读该字段；W2 mock 早已用 3 元素 sectorBoundaries。本 round 实际上是**消除生产 reader 与 W2 mock 之间的契约漂移**（W2 测试用多段 mock 验证 SectorBar，但生产 reader 一直喂单段 → SectorBar 多段代码路径在生产从未被触发，本 round 才真正激活它）。

**apply §10 backlog drift mini-review 触发**（CLAUDE.md #16 条款）：本 round design 已列完整 producer/consumer 表，apply 期 MUST grep `sectorBoundaries` 复核无新增消费方在本 round 立项后冒出（detail 屏 round 若已开始可能新增消费）；并 verify SectorBar 多段路径单测覆盖。

---

## Decision 5: 回退语义——空 sector 集回退 listOf(lapStartWallClock)，不回归现有单段行为

**选择**：当 lap 窗口内无 accepted 非空 wallClock 的 Sector crossing（无 sector 门赛道 / debug 宽容闭合缺 sector / 历史 session sector wallClock 全 null），`sectorBoundaries = listOf(lapStartWallClock)`（与 baseline 完全一致）。

**Rationale**：

- "回退单段"不等于"回归 bug"——它是**正确的退化行为**：没有 sector 过线数据时，SectorBar 画 1 段全圈条是合理的（用户看到"这条赛道/这一圈无分段信息"），比报错或空白好。
- 与 SectorBar `computeSectorBounds` 的空列表/单元素退化分支（L25-29）天然衔接：传单元素 → 它补 lapEnd → 画 1 段。
- 诚实边界：博裕 loop（extraPresetTracks）若未定义 sector 门 → 该赛道所有圈回退单段，这是数据真实情况，不伪造。

---

## FileLogger 计划（road-test-first 模式 MANDATORY 安全网）

**模块边界 caveat**：`core/data` 模块**不依赖 FileLogger**（FileLogger 在 `feature/test` 模块，见 `feature/test/.../FileLogger.kt`）。reader 直接 import FileLogger 会引入反向模块依赖（core/data → feature/test 违反分层）。因此：

- **reader 内不直接埋 FileLogger**（模块边界硬约束）。
- **诊断策略 = reader 返回值 self-describing**：`LapTelemetry.sectorBoundaries.size` 本身就是诊断信号（size==1 → 回退单段；size>1 → 派生 N-1 段）。路测 adb pull binary + crossing 表即可复算预期 sector boundary 数。
- **消费侧埋点 deferred 到 detail 屏 round**：`lap-detail-screen-with-cursor` round（在 `feature/test`，有 FileLogger）`LaunchedEffect` 调 getLapTelemetry 后，MUST 埋 `FileLogger.v("SectorDeriv", "lap=$lapIndex sectorBoundaries.size=${lt.sectorBoundaries.size}")` 记录派生结果。本 round 在 tasks §10 backlog 留 link。
- **本 round 单测充分**：road-test-first 的真机兜底由 detail 屏 round 攒批承接；本 round 是纯数据层 reader，单测（多段/回退/窗口排除/null排除/反例）即可锁死逻辑正确，真机 SKIP（纯 core/data 数据层，无 UI）。

---

## Risks

| Risk | 影响 | Mitigation |
|---|---|---|
| **R1 · sector crossing wallClock 与 lap 窗口跨时钟域** | 若误用 GPS 协议时钟会让窗口判定错位 | Decision 1/3 强制用 `crossingWallClockTimestampMs`（与 lap 窗口同源）；spec 反例 scenario 锁死"MUST NOT 用 crossingTimestampMs"；apply 期 grep `crossingTimestampMs` 在新增派生段零命中 |
| **R2 · 窗口外 sector 混入（相邻圈 sector 误纳入）** | 多圈 session 中圈 N 的 SectorBar 画出圈 N+1 的 sector | Decision 3 半开窗口 `< lapEnd` 排除；spec 反例 scenario「窗口外 sector MUST NOT 混入」+ 单测构造跨圈 sector crossing 断言只取本圈 |
| **R3 · 首元素不再 == lapStart 破坏 LapTelemetry 契约** | SectorBar L45 Log.w 警告 + 段计算偏移 | Decision 1 前置 `lapStartWallClock`；单测断言 `sectorBoundaries.first() == lapStartWallClock`（既有 case A 第 84 行 + 新 case 都覆盖） |
| **R4 · sector 过线 wallClock 恰等于 lapStart（退化重复）** | sectorBoundaries 出现 `[lapStart, lapStart, ...]` 重复首项 | Decision 3 `>= lapStart` 严格大于号？—— 用 `> lapStart` 排除恰等于起点的 sector？**决议：用 `>= lapStart` 但因前置已含 lapStart，对 sectorWallClocks 部分额外排除 `== lapStart` 的项**（spec normative 明确：sectorWallClocks MUST NOT 含 == lapStartWallClock 的项，避免重复首段）。单测 case 覆盖 sector wallClock == lapStart 退化 |
| **R5 · #16 detail 屏 round 并发新增消费方** | detail 屏 round 若在本 round 立项后开始可能新增 sectorBoundaries 消费 | apply §10 backlog drift mini-review：apply 期重新 grep `sectorBoundaries` 复核消费方表；看板 §6 登记 `getLapTelemetry` 与 detail 屏 R2 决策耦合（路线图 §3 已标"宜先于或并入 detail 屏"） |

---

## 模块 / 文件边界

- **独占路径**：`core/data/.../repository/TelemetryRepository.kt`（仅 `getLapTelemetry` 第 317 行附近派生段）+ `core/data/.../repository/LapTelemetryReadersTest.kt`（补 case）。
- **看板 §6 共享文件登记**：`TelemetryRepository.kt` 是历史多 round 共享文件，但本 round 只改 `getLapTelemetry` 函数体内一处，与 endSession（`unify-lap-count` 已合回）/ deleteSession（J round 已合回）函数级不重叠。apply 启动前看板 §5 研判：当前无并行 round 占用 `getLapTelemetry`。
- **不触碰**：feature/test 任何文件（detail 屏接线 deferred 到 detail 屏 round）、core/domain（LapTelemetry 字段不变，只是填充值从单段变多段）、engine、ViewModel、DAO、schema。
