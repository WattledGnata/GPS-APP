## Context

V2 圈速实时屏 `LapLiveScreen.Lap2x2Dashboard` 渲染 4 个 tile：DELTA / CURRENT / LAST / BEST。DELTA tile 显示"实时秒差"对比 best 圈，是赛道驾驶最重要的反馈仪表 —— 让用户在圈进行中知道"这圈是否在赶超 best"。

现状：`LapLiveStateDeriver.derive` 的 `deltaToBestMs` 派生（line 100-104）是错位减法（当前圈 elapsedMs 减 best 圈完整时长），数学上无物理意义；用户实测体感"第二圈起一直显示绿色巨大负数"，UI 误导用户认为持续 PB。

根因来自 add-lap-session-phase1 round 期间的 placeholder 简化（CC 当时没沉淀 deferred memo）；本 round 重做正确实现，对齐工业 telemetry（RaceChrono / Harry's LapTimer / VBOX / AiM）的 distance-based projection 范式。

约束：

- V2 一期"圈速 session 实时模板只展示相对最佳差值、当前圈计时、上一圈、最佳圈、当前圈号"（CLAUDE.md UI 视觉约束 + `docs/design/track-tech-v2-cc-guidance.md`）；**本 round 不动这个 4-tile 信息架构**
- GPS 协议、binary 持久化、`LapTimingEngine` / `LapSession` / `LapRecord` / `GpsSample` 数据契约 —— **不动**
- 与并行 A round（`fix-lap-binary-ts-hygiene`）共享 `TestSessionViewModel.kt` 但函数级不重叠；A 改 `bridgeGpsToLapTiming` 内部，本 round 加顶层 state field

## Goals / Non-Goals

**Goals:**

- 圈进行中的 DELTA tile 显示 **物理上有意义** 的秒差：当前圈到达 best 圈"同一进度位置"时，跟 best 圈到达该位置时的累计时间差
- 数字平滑：5Hz 显示下相邻帧 delta 抖动 < 5ms（线段投影连续，无离散网格抖动）
- 时钟域纯净：currentLapElapsedMs 与 best 圈 elapsedMs 都用 GPS sample ts 域，不混 wall clock（避免 BLE 链路延迟污染 50-200ms）
- 第一圈无 best → DELTA muted `--:--.---`
- PB 刷新瞬间 reference 切换 sync，下帧无缝
- 失效场景（GPS 跳变 / 严重变线 / 信号丢失）→ stale 状态，维持上一帧 delta + UI muted，不闪烁乱跳
- 算法纯函数 + 单元测试覆盖

**Non-Goals:**

- 不做跨 session PB 比较（user §1 拍板：车况 / 天气 / 配置不一致无参考性）
- 不做 sector-based delta（独立后续 round）
- 不做赛道里程预计算 / 100 段固定采样（user 在 brainstorm 阶段拍板放在 sector / heatmap / 缩略图等后续 round）
- 不加 EMA / 滑动平均（第一版默认不加，留 follow-up backlog "如真机抖动明显再加 250-500ms EMA"）
- 不改 LapTimingEngine 输出契约
- 不改 BLE / GPS 协议、binary 持久化、Room schema

## Decisions

### Decision 1: 算法选型 —— Polyline Segment Projection（不是最近 GPS 点）

**对比**：

| 算法 | 数学连续性 | delta 抖动 | 实现复杂度 |
|---|---|---|---|
| **A 最近 GPS 点 index** | 离散（每帧只能选 idx 或 idx+1） | ±20ms 网格抖动（25Hz GPS 点间距 ~40ms ts） | 低（~30 行） |
| **B 投影到最近线段（采用）** | 连续（在两点间插值） | 抖动 < 5ms（仅 GPS 噪声残留） | 中（~80 行） |

**选择 B**。理由：

- 5Hz 显示下用户能直接看到"数字小幅抖"；线段投影把抖动压到 GPS 噪声本身的下限
- 工业 telemetry（RaceChrono / Harry's / VBOX / AiM）全部用 polyline projection，已被实战验证
- 性能差距可忽略（单帧搜索 400 点 × 平方距离 + 投影计算 ~15µs，5Hz 下 75µs/秒，0.0075% CPU）

**算法描述**：

1. 当前 GPS 点 → 米坐标 `(curX, curY)`（用 best 圈 lap start GPS 中心点做局部平面投影）
2. 从 `prevMatchedIdx` 起 `±200` 帧窗口内，对每个 segment `[i, i+1]` 做点到线段投影：
   ```
   segDx = xs[i+1] - xs[i]
   segDy = ys[i+1] - ys[i]
   segLenSq = segDx² + segDy²
   t = ((curX - xs[i]) * segDx + (curY - ys[i]) * segDy) / segLenSq
   t = t.coerceIn(0, 1)
   projX = xs[i] + t * segDx
   projY = ys[i] + t * segDy
   distSq = (curX - projX)² + (curY - projY)²
   ```
3. 选 `distSq` 最小的 segment + 投影比例 t；
4. 用 t 在 `elapsedMs[i]` 与 `elapsedMs[i+1]` 之间线性插值得 `bestElapsedMsAtSameProgress`：
   ```
   bestElapsed = elapsedMs[i] + t * (elapsedMs[i+1] - elapsedMs[i])
   ```
5. `deltaMs = currentLapElapsedMs - bestElapsed`

### Decision 2: 前向窗口 ±200 帧（非全量 / 非 100 段分组）

**对比**：

| 方案 | 单帧搜索点数 | 鲁棒性 | 代码 |
|---|---|---|---|
| 全量扫描 | 2250 | 最稳但变线时易跨段误匹配 | 简单 |
| 100 段分组 | ~66（相邻 3 段） | 中（限于 ±1 段） | 复杂（额外预分段 + segment 索引） |
| **前向窗口 ±200 帧（采用）** | 400 | 中（容忍 ±260m 偏移） | 中 |
| 单调步进 amortized O(1) | 1-2 | 弱（变线易卡） | 简单 |

**选择前向窗口**。理由：

- 200 帧 ≈ 8 秒 ≈ 260 米搜索半径，足以容忍正常变线（外内线 5-10m）+ GPS 噪声 + 短时偏移
- TFIC / Boyu 等赛道 hairpin 间距 > 60m，远小于 260m → 不会跨段误匹配
- 单调步进虽然 CPU 几乎零但变线一卡就 fallback，鲁棒性弱
- 100 段分组的复杂度收益不抵 —— CPU 已经够便宜，结构复杂只增加心智负担

### Decision 3: 失效阈值 50m + Stale 状态 5 帧门

**失效判定**：投影距离 `sqrt(distSq) > 50m` 视为失效；`projectDelta` 返回 null。

**为什么 50m**：

- TFIC / Boyu 实测 hairpin 间距 > 60m → 50m 阈值不会触发"误进 stale"
- 允许中等变线 + GPS 噪声（5-15m）不被误判
- 业界经验：30m 太严正常变线就 stale；80m 太松真异常通过

**Stale 状态 5 帧门**：

- 单帧失效 → 维持 prevDeltaMs，stale 计数 +1（**不立刻进 stale**）
- 累计 5 帧（5Hz 下 1 秒）失效 → isStale = true，UI 字色降 TextMuted
- 中间任意一帧成功 → stale 计数重置为 0
- 设计依据：避免 GPS 单帧异常引起 UI 闪烁；1 秒持续失效才是"真问题"

### Decision 4: 首圈完成立即建 reference + PB 刷新同步重建

**首圈 reference 建立**：

- `LapSession.completedLaps` 由空变 size = 1 时，**立即用唯一完成圈作 reference**（`buildReferenceLapIndex(completedLaps[0])`）
- 第二圈一开始（即第二次 startFinish 冲线后）即可显示相对该 reference 的 delta
- 这跟 user §2 拍板"实时秒差先有一个完整圈成绩后才展示"语义一致 —— "一个完整圈" = 首圈完成

**注**：旧的 design 早期版本曾写"第二圈完成才建第一个 reference"是 CC 写矛盾了（spec 同时要求 size >= 1 立即建），Codex P1-3 review 抓到。当前已统一拍定**首圈完成立即建**。

**PB 刷新触发重建**：`LapSession.completedLaps` 中出现某个新完成圈，其 `durationMillis < 当前 reference.lapDurationMs`。

**动作**（atomic update）：

1. 在 ViewModel 收到 LapSession 更新的 collect 回调里同步重建 `ReferenceLapIndex`
2. 重置 `prevMatchedIdx = -1`，下一帧 projectDelta 走前向窗口起点 0
3. 重置 `staleFrameCount = 0`
4. **`prevDeltaMs` 保留**（避免 stale 体验空白）

**重建开销**：2250 点 × (米坐标转换 + 累计距离 + ts 偏移) ≈ 100µs。在 ViewModel coroutine 同步做完，UI 下一帧无缝切换。

**特殊情况 - 第一帧 currentLapElapsedMs ~ 0**：

- 首圈完成瞬间，第二圈刚开始：`currentLapElapsedMs = currentSampleTs - lastCrossing.ts ≈ 0`
- 当前 GPS 位置接近 best 圈起点（车刚冲过 startFinish）
- projectDelta 投影到 reference 起点附近，bestElapsed ≈ 0
- delta ≈ 0 ms，绿/红中性 → 这是正确语义

### Decision 5: 时钟域分离 —— delta 用 GPS sample ts，CURRENT tile 用 ticker

**问题（baseline）**：`LapLiveStateDeriver.derive(currentTimeMs: Long, ...)` 入参是 wall clock，与 `crossing.timestampMillis`（GPS sample ts）混域相减，BLE 链路延迟 50-200ms 污染 elapsedMs。

**修复（不破坏 CURRENT tile 平滑显示）**：

- **delta 计算路径**（仅在 ViewModel 内部）：
  - `currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`
  - 两个 ts 都是 GPS sample 时钟域，同源相减
  - ReferenceLapIndex.elapsedMs[i] 也是 GPS sample ts 域（`trajectory[i].timestampMillis - bestLap.startedAtMillis`）
  - 因此 `delta = currentLapElapsedMs - bestElapsed` 三个量都在 GPS sample 时钟域，无 wall clock 污染

- **CURRENT tile 显示路径**（保持 baseline 行为）：
  - `currentLapTimerMs = currentDisplayTimeMs - lastAcceptedCrossing.timestampMillis`
  - `currentDisplayTimeMs` 由 ViewModel ticker / `SystemClock.elapsedRealtime()` 推动（每帧 UI tick 喂入）
  - 这样 CURRENT tile 在两个 GPS 帧之间也能平滑递增，5Hz replay 下不出现 200ms 一跳

**为什么分离**（Codex P2 review 抓到）：

- 如果 currentLapTimerMs 切到 GPS sample 域（只在 GPS 帧来到时更新），5Hz replay 下数字会一卡一跳，UX 退步
- baseline ticker 外推已经修复过这个问题，不能为了"时钟域纯净"反悔
- delta 计算是数学精度关键 → 必须 GPS sample 域；UI 显示流畅是体验关键 → 必须 ticker 外推。两者诉求不同，分离最干净

**LapLiveStateDeriver 入参签名**：

```kotlin
fun derive(
    session: LapSession?,
    currentDisplayTimeMs: Long,    // ticker 推动，用于 currentLapTimerMs（不动 baseline 行为）
    gpsData: GpsData,
    connectionState: ConnectionState,
    dataQuality: DataQuality,
    deltaToBestMs: Long?,          // ViewModel 已用 GPS sample 域算好的 delta
    deltaIsStale: Boolean,
): LapLiveState
```

### Decision 6: 算法责任放在 ViewModel —— Deriver 不调 projectDelta

**问题（Codex P1-4 抓到）**：早期 spec/tasks 同时要求 LapLiveStateDeriver 与 TestSessionViewModel 都调 projectDelta，会出现：

- 同一帧双计算（CPU 浪费）
- ViewModel 已 atomic update 跨帧状态，Deriver 又用旧 prevMatchedIdx 重新算 → UI 用一份结果，跨帧 state 用另一份 → 状态漂移
- Deriver 算出新 matchedIdx 没有路径返回给 ViewModel

**选择**：算法责任**完全交给 ViewModel**，Deriver 只读结果。

- ViewModel 每帧 GPS data 来到 → 调 projectDelta 一次 → atomic update `_realtimeDeltaState` → 派生本帧的 `outDelta` / `outIsStale` 两个标量
- Deriver 入参带这两个标量（不再带 reference / prevMatchedIdx / staleFrameCount），不调 projectDelta，仅组装 LapLiveState

**对比的另一选项**：让 Deriver 调 projectDelta 并返回"新 state + LapLiveState"二元组，ViewModel 接收后原子写回。

| 方案 | 优 | 劣 |
|---|---|---|
| **算法在 ViewModel（采用）** | 职责清晰：Deriver 是纯派生，ViewModel 是 state owner；algorithm 只跑一次 | derive 入参字段名变化（baseline 改造） |
| 算法在 Deriver + 返回 next state | Deriver 单元测试更完整 | Deriver 不再纯派生（修改了状态机概念）；增加返回值复杂度 |

聚合 state 数据类 `RealtimeDeltaState` 只在 ViewModel 内可见，仍然原子 update：

```kotlin
internal data class RealtimeDeltaState(
    val reference: ReferenceLapIndex?,    // null = 无 best
    val prevMatchedIdx: Int = -1,
    val prevDeltaMs: Long? = null,
    val staleFrameCount: Int = 0,
)
```

### Decision 7: UI Stale 状态用字色降级（不加额外标签）

**对比**：

| 方案 | 优 | 劣 |
|---|---|---|
| **字色 Green/Red → TextMuted（采用）** | 单一变量，简洁；用户立刻看到"数字 grey 了 → 不可信" | 需要用户先理解约定 |
| 加 `"STALE"` 副标 | 显式 | UI 信息密度增加；翻译/i18n 心智成本 |
| 直接显示 `"--"` | 最简 | 失去"上一次有效 delta"的信息 |

**选择字色降级**。Track Tech V2 已经用字色（accentColor）作主要语义信号，stale 字色降级符合现有视觉语言。

## Risks / Trade-offs

- **[GPS 残留噪声引起小幅抖动]** → 线段投影消除离散网格抖动后，GPS 自身 1-3m 噪声仍可能让 delta 在 ±20-50ms 抖。**Mitigation**：第一版不加 EMA，真机看体感；如果显著，加 250-500ms EMA 是 follow-up 单独 round（10 行代码）

- **[hairpin 误匹配未被前向窗口完全消除]** → 极端赛道（如 hairpin 间距 < 50m）可能仍跨段。**Mitigation**：失效阈值 50m + stale 5 帧门已经覆盖；剩余 < 1% 圈率可接受。如果遇到具体赛道反复触发，调常量 `FORWARD_WINDOW_FRAMES = 100`（缩到 ±130m）

- **[第一帧 / abort 圈恢复的全量搜索代价]** → prevMatchedIdx = -1 时窗口起点 = 0 + 200 帧 = 200 点，仍是子集；不是真的全量。如要全量定位，需要主动改动：扫 0..size-1。**Mitigation**：第一帧用 forward-only 200 点其实够了（车在赛道起点附近，best 圈也在起点附近）。如果不够，加一个全量 fallback 触发（投影距离 > 50m 时扩展到全量）

- **[reference 重建期间一帧空窗]** → PB 刷新瞬间 reference 重建，如果重建 + reset prevMatchedIdx 不在同一 sync block 完成，可能下帧 derive 拿到旧 reference + 新 prevMatchedIdx。**Mitigation**：聚合到单一 RealtimeDeltaState atomic update；ViewModel 用 `_realtimeDeltaState.update {}` 保证原子

- **[本地米坐标投影误差]** → 局部平面投影（用 lap start GPS 作中心）在 < 5km 范围内最大误差 < 0.1m，赛道场景内忽略。**Mitigation**：写进 `buildReferenceLapIndex` 注释；不引入精确测地线距离（会让代码复杂度翻倍且收益为零）

- **[A round 合回时序]** → A 改 `TestSessionViewModel.bridgeGpsToLapTiming` 内部 line 596 公式；本 round 加顶层 `_realtimeDeltaState` field。函数级不重叠，rebase 应该 clean。**Mitigation**：本 round 启动前在看板 §6 登记 `TestSessionViewModel.kt` 占用，注明"加顶层 field 与 A 不重叠"；A 合回后 rebase 跟上

## Migration Plan

无 schema / 协议 migration。

部署步骤：

1. 主区开 worktree `.worktrees/add-realtime-lap-delta`，切到 `feature/track-tech-v2`
2. 看板 §5 登记本 round，§6 登记 `TestSessionViewModel.kt` 共享文件占用
3. 实施 tasks.md
4. 编译 + 单测全绿
5. 真机验证（华为 8KE0219522008434，跑 ≥ 3 圈观察 DELTA 行为）
6. commit + ff-only 合回主区
7. push 等 user 拍板顺序

回滚策略：

- 全部新增文件 + 1 个修改文件（LapLiveStateDeriver.kt）+ 1 个修改文件（LapLiveScreen.kt UI）+ 1 个修改文件（TestSessionViewModel.kt 加 field）。回滚就是 revert commit
- 不动数据持久化层 → 数据无回滚成本

## Open Questions

无。所有 decisions 已对齐 + Codex review 第一轮反馈已消化（patch 进 spec/design）：

**Brainstorm 阶段（user + CC + Codex）拍板**：

- algorithm = polyline projection（user 拍 Codex 方案）
- reference = session-internal best（user §1）
- 第一圈完成立即建 reference，第二圈起即可显示 delta（user §2）
- 语义 B（user §3）
- 失效阈值 50m + stale 5 帧门（CC 推荐 + Codex 同意）
- delta 计算用 GPS sample ts 域（Codex 强调）；CURRENT tile UI 显示继续用 ticker（baseline 行为保留，Codex P2 抓到不要破坏）
- UI 字色降级 stale（CC 推荐）

**Codex review 第一轮（apply 前）反馈已 patch**：

- P1-1 ReferenceLapIndex 加 refLat/refLon + toLocalMeters helper（投影原点同坐标系）
- P1-2 lapStartTsMs 用 bestLap.startedAtMillis 而非 trajectory.first().ts（避免一个采样间隔的固定偏移）
- P1-3 首圈完成立即建 reference（修 design 与 spec 自相矛盾）
- P1-4 算法责任放 ViewModel，Deriver 不调 projectDelta（避免双计算 + 状态漂移）
- P2 时钟域分离：delta 用 GPS sample ts；CURRENT tile 继续 ticker 外推

EMA 平滑作为 follow-up backlog 项（不进本 round）。100 段分组留作 sector / heatmap 后续 round。
