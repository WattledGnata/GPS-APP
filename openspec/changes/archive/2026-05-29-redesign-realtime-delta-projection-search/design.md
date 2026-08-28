## Context

I round (`add-realtime-lap-delta`，archive/2026-05-02) 的 `RealtimeDeltaCalculator.projectDelta` 用前向滑窗优化（`prevMatchedIdx ± forwardWindowFrames=200`）避免每帧 O(n) 全量搜索 reference polyline。这套设计的隐含**核心假设**：

> 连续两帧之间 prevMatchedIdx 变化幅度 < forwardWindowFrames（200 帧 ≈ ±260 米路径）

2026-05-05 W4 hotfix B 真机回放 verify 暴露此假设在 4 个场景全部破裂：

| 场景 | prevMatchedIdx 跳跃 | 用户体感 |
|---|---|---|
| **lap N → lap N+1（非 PB）** | reference 末尾 idx → 0 idx，跨整个 reference（典型 1500+ 帧）| DELTA `-125.20s` 灰色卡住 |
| **GPS 信号丢失重连** | 卡在丢失前位置，重连后实际位置不同 | DELTA 数值跳变 / stale |
| **track 切换** | reference 是 trackA，user 已切 trackB | DELTA 完全无意义 |
| **GPS jitter 大跳变** | 单帧 lat/lon 偏移触发 failoverDistanceM=50m | 进 stale 分支保留错误 cached delta |

`maybeRebuildReference` 仅在 reference 首建 / PB 刷新 reset prevMatchedIdx。其他 3 个场景全部不重置。spec 也无任何反例 scenario 锁死这些边界（v3 高频盲点 #5 实战体现）。

**真机回放数据点**（2026-05-05 vivo V2405A 泊寓 10 分钟）：
- `wrong_dir` log 0 条 + `banner_show` log 0 条 → W4 hotfix B 路径已修
- DELTA `-125.20s` 灰色现象与 lap 切换瞬间高度相关（用户主诉）
- DELTA 中段 `10s → 5s` 跳变，与 W4_DIAG 抓到的 `12:38:37` 一帧 GpsDataFilter 中段 outlier 输出（latDM=100m + fSpd 翻倍）时间高度相关 → 上游 filter Bug Y 修复后频次会降，但本 round 算法层应对极端 outlier 的 graceful 行为仍需重设计

## Goals / Non-Goals

**Goals:**

- 修底层算法 `projectDelta` 使其在 4 个边界场景下都不产生明显错误的 delta（如 -125s）
- 修 UI `LapLiveScreen` DELTA tile 显示行为，避免在算法 stale + cache 错误时把 cached `prevDeltaMs` 显示为灰色错值
- 在 spec 反例 scenario 锁死 4 个边界场景的契约，避免下次实施期假设破裂复现
- design 期实测华为 8KE0219522008434 性能 baseline，作为 alternative 决策定量依据
- 真机 verify gate：华为 8KE02 回放泊寓数据，DELTA `-125.20s` 灰色 + `10→5` 跳两个现象消失

**Non-Goals:**

- 不动 RaceChrono BLE 协议（公共协议）
- 不动 Room schema（无 entity 字段变化）
- 不动 reference 建立逻辑 (`buildReferenceLapIndex`)：lapStartTsMs / refLat / refLon / xs / ys / elapsedMs 数组结构保持原契约
- 不动 `LapLiveStateDeriver` 的 deltaToBestMs / deltaIsStale 入参契约（保持 ViewModel 算 + Deriver 消费的分工）
- 不修 GpsDataFilter Bug X / Bug Y（独立 follow-up round 处理）
- 不修上游 W4 hotfix B（已独立 commit）
- 不引入 KD-tree / R-tree 等空间索引依赖（如需要走 Open Question 走 Tier 3 follow-up）

## Decisions

### Decision 1：搜索策略——三选一（待 design 期性能 baseline 实测后 user 拍板）

**问题**：projectDelta 每帧调用一次（25Hz），reference 典型 1500 frames。当前 `prevMatchedIdx ± 200` 滑窗优化（O(window)=O(400)）的连续性假设破裂在 4 个边界场景。

**Alternatives**：

| Alt | 描述 | Pro | Con |
|---|---|---|---|
| **A. 保留 prevMatchedIdx + 加多重 reset 触发路径** | 在 `_realtimeDeltaState` 加 `lastSeenLapCount`/`lastSeenTrackId`/`lastGpsLostTs`，当其改变时 reset prevMatchedIdx = -1 + prevDeltaMs = null。calculator 行为不变。| 改动局限在 `RealtimeDeltaState` 字段 + ViewModel reset 路径；性能不退化；与现有 spec 增量兼容 | 引入 N 条 reset 路径（4 边界 × 触发判定），每条都得在 ViewModel 显式监听 + 测试覆盖；未来新边界还得回来加路径；prevMatchedIdx 在"GPS 高速跨段（如急弯）"理论上仍可能 < 200 帧失败（边角 case） |
| **B. 去优化转 stateless 全量 O(n) 搜索** | 删除 prevMatchedIdx + forwardWindowFrames + RealtimeDeltaState.prevMatchedIdx 字段；每帧从 segment 0 扫到 size-2 找最近投影。calculator 变纯函数无跨帧状态。| 根除"连续性假设"破裂；spec 简化（不需 4 边界 scenario）；测试简化；calculator 真正的纯函数；未来新边界 0 改动 | 性能从 O(400) 退化到 O(1500)，3.75x 慢；25Hz 下 75ms/s（CPU 负载从 ~2% 升到 ~7.5%，待实测验证）；如果性能不通过 → 回退到 A 或 C |
| **C. 转 KD-tree O(log n) 搜索** | 在 `buildReferenceLapIndex` 期建 2D KD-tree（lat/lon 叶节点），每帧 O(log n) 找最近邻 + 邻域 segment 投影。stateless。| 性能 O(log 1500)≈11 步 ≪ O(400)；根除连续性假设；扩展到长 reference (5000+ frames) 仍稳健 | 引入空间索引复杂度（自实现 ~150 行 OR 引入第三方依赖）；首次 reference 建立 +O(n log n) 一次性成本；与 polyline segment 投影模型不完全契合（KD-tree 是点最近邻，segment 投影需要先找最近点再扩到邻域 segment 集合，需要额外 wiring） |

**推荐方案**（pre-design baseline 立场，待性能数据修订）：**B**。

**理由**：
- W4 hotfix B 真机回放已证明现有 prevMatchedIdx 优化是漏洞之源，根本性消除胜过补丁
- 性能 baseline 待实测，但**先验估算**（intel-level CPU benchmarks for simple float ops × Android ARM）：1500 frames × 25Hz × 8 floats/frame ≈ 300_000 ops/s，现代手机微秒级（< 1ms/帧），远低于 40ms 帧间预算
- KD-tree (C) 是 over-engineering，仅在性能 baseline 不通过时才考虑

**Open Question OQ1**：性能 baseline 实测在 design 期 §7 完成（华为 8KE0219522008434），结果决定 A/B/C 最终选择。

---

### Decision 2：reset 触发契约（仅在 Alt A 选择时生效）

**问题**：如选 A，需明确 reset 触发的全部场景 + 实施位置（ViewModel collect 路径 vs Deriver vs Calculator）。

**契约草案**：在 `_realtimeDeltaState` 触发 reset prevMatchedIdx = -1 / prevDeltaMs = null 的场景：

1. **reference 首建** —— `maybeRebuildReference` `current == null` 分支（已存在）
2. **PB 刷新** —— `maybeRebuildReference` `newBest.durationMillis < current.lapDurationMs` 分支（已存在）
3. **lap 切换（非 PB）** —— **新增**：监听 `_lapSession.value.completedLaps.size` 增加事件
4. **GPS 信号丢失阶跃** —— **新增**：监听 `gpsData.satelliteCount == 0` 持续 ≥ N 帧后恢复信号的瞬间
5. **track 切换** —— **新增**：监听 `_currentSelectedTrack.value.id` 变化
6. **prevDeltaMs cache 异常** —— **新增**：当 `|prevDeltaMs| > lapDurationMs * 2` 时 invalidate cache（防御性兜底，避免错误值卡住灰色显示）

**Alternatives**：
- A1（推荐）：在 ViewModel 加新 collect 协程监听 6 触发源，统一 atomic update `_realtimeDeltaState`
- A2：每个触发源独立 launch 协程（race 风险）
- A3：把 reset 逻辑下推到 Calculator（破坏 Calculator 纯函数语义，拒绝）

仅在 Decision 1 选 A 时具体落地此 Decision。Decision 1 选 B 或 C 时此契约**整体作废**——stateless 算法无 cache 可 reset。

---

### Decision 3：UI 显示行为修订

**问题**：当前 `LapLiveScreen.kt:200-209` `formatDelta(state.deltaToBestMs)` 直接显示 `prevDeltaMs`（cached value），即使 cached 值是 `-125200ms` 这种明显异常值。stale 时只是字色变灰（TextMuted），数字依然显示。

**新契约（建议）**：

- 当 `state.deltaIsStale && |state.deltaToBestMs| > $LAP_DURATION_TYPICAL_MS * 1.5` → DELTA tile 显示 `--`（与 `state.deltaToBestMs == null` 同视觉），不显示数字
- 即 stale + 数值离谱 → 不显示，避免误导
- stale + 数值合理（如 `-2.5s` cached）→ 保留灰色显示（用户体验上更一致，知道"刚才有效，现在卡住"）

**Alternatives**：
- B1（推荐）：上述契约
- B2：stale 一律显示 `--`（不分数值合理性；过激进，丢"上一帧体验空白"）
- B3：stale 显示数值不变（保持当前行为；user 体感故障保留，拒绝）

`LAP_DURATION_TYPICAL_MS` 默认值待定（OQ2）。

---

### Decision 4：性能 baseline 实测计划

**位置**：design 期内 §7 Migration Plan 之前完成；如未完成不能进入 specs / tasks 阶段。

**实测内容**：
1. 构造 reference (lap)，size = 1500 frames（典型赛道一圈帧数）
2. 在华为 8KE0219522008434 上跑 micro-benchmark：每秒调 `projectDelta` 25 次（模拟 25Hz GPS 流），计时单次调用耗时 + 总 CPU 时间占比
3. 跑 3 个 alternative 版本：
   - Alt A: 保留 prevMatchedIdx 滑窗（baseline 现状）
   - Alt B: stateless 全量 O(n)
   - Alt C: KD-tree O(log n)（如时间允许）
4. 输出对比表：单次平均耗时 / 99 分位耗时 / CPU 占比

**Done condition**：3 个 alternative 中至少 2 个有定量数据 + user 看完数据拍板 alternative。

**实施位置**：临时 androidTest（feature/test 模块）+ printout 到 logcat / FileLogger。benchmark 文件不进 git（`.git/info/exclude *.md` 但 .kt 进 git → benchmark 用一次后 strip）。

---

### Decision 5：Spec 反例 scenario 锁死 4 个边界场景

无论 alternative 选 A/B/C，spec 都需要锁死 4 边界场景的预期行为：

| Scenario | 预期行为 |
|---|---|
| `lap N → lap N+1 切换瞬间` | DELTA tile 显示 `--`（占位）OR 算法直接给 deltaToBestMs = 当前圈进度差（具体取决于 alternative）；MUST NOT 显示 -125s 灰色 |
| `GPS 信号丢失重连` | DELTA tile 显示 `--`（GPS_SIGNAL_LOST banner 已优先级覆盖；此 scenario 仅锁信号恢复后 N 帧 DELTA 不延续旧值）|
| `track 切换` | DELTA tile 显示 `--`；reference 应清空待 user 跑出新 best lap 才重建 |
| `GPS jitter 大跳变` | DELTA tile 显示 `--`（与 stale 状态一致）；prevDeltaMs cache 不延续超出合理范围的值 |

每个 scenario MUST 含一个反例（"违反约束时测试 fail"），符合 CLAUDE.md v3 高频盲点 #5。

## Risks / Trade-offs

- **[R1] 性能 baseline 实测耽误 design 期**：micro-benchmark 需要在真机跑 + 部署 androidTest，可能 0.3 天 → 推到 OQ1 等数据后再 user 拍板 → Mitigation：先并行写 specs（不依赖 alternative 决策的部分）；OQ1 定后再补 design Decision 1 最终选择 + 写 tasks
- **[R2] Alt B（stateless）的 worst-case 性能未知**：1500 frames × 25Hz 估算 < 1ms/帧，但 Android 系统 GC / Compose 渲染抢占 / 复杂场景多 GPS 流并发可能让 99 分位 > 40ms 帧间预算 → Mitigation：实测 99 分位；如不通过回退 Alt A 或 C
- **[R3] Alt A 的 reset 路径漏盘**：6 触发源中可能漏 1 个未来出现的边界（如 user 暂停 LapSession 后恢复）→ Mitigation：spec 反例 scenario 4 个 + Decision 2 的"prevDeltaMs 异常兜底"作为 catch-all（值 > 合理上限就 invalidate）
- **[R4] Decision 3 的 LAP_DURATION_TYPICAL_MS 阈值难定**：不同赛道圈速从 30s 到 5min；阈值过高会让显著异常值仍显示，过低会让正常 lap 切换瞬间合理负值被误屏蔽 → Mitigation：OQ2 调研 user 历史最长圈速作为阈值，或改为相对 reference.lapDurationMs 的倍数（如 1.5x）
- **[R5] 跨 round 协同**：本 round 闭环时 hardening round / Bug X / Bug Y / banner-flash follow-up 可能并行 → Mitigation：本 round 只动 RealtimeDeltaCalculator + RealtimeDeltaState + LapLiveScreen DELTA 渲染分支；与其他 round 函数级 0 交叉
- **[R6] 真机 verify 信号显著度依赖 Bug Y 上游修复**：DELTA `10→5` 跳现象部分来自 Bug Y 的 GpsDataFilter 中段 outlier 输出；本 round 修后如 Bug Y 仍未修，回放可能仍能复现`10→5` 跳（因为投影到错误位置仍是错误位置）→ Mitigation：spec 锁定本 round 修的是"算法层应对极端 outlier 的 graceful 行为"，不要求消除来自上游 outlier 的所有 delta 跳（这是 Bug Y 的责任）

## Migration Plan

无 schema / 数据迁移。本 round 仅修改 feature/test 模块内部：

1. design 期完成性能 baseline → user 拍板 Decision 1 alternative
2. specs 期写 4 个反例 scenario + 修订现有 3 个 Requirement
3. tasks 期实施代码 + 单测 + 真机 verify
4. 闭环：直接 ff 合回 feature/track-tech-v2（不需 worktree，因为已知与并行 round 函数级 0 交叉；具体看板 §6 决议）

回滚策略：本 round 不破坏 ReferenceLapIndex 数据结构契约 + 不破坏 deltaToBestMs/deltaIsStale 入参契约。如真机 verify fail 可直接 revert 单 commit 回到 W4 hotfix B 后状态。

## Open Questions

- **OQ1**: 性能 baseline 数据。Decision 1 alternative 必须看到华为 8KE02 实测数据后由 user 拍板。design 期 §7 完成。
- **OQ2**: Decision 3 `LAP_DURATION_TYPICAL_MS` 阈值具体值（或转为 reference.lapDurationMs * 1.5 这种相对值）。倾向相对值。
- **OQ3**: Alt A 的 GPS 信号丢失"持续 ≥ N 帧"中 N 的具体值（建议 5 帧 = 200ms@25Hz，与现有 STALE_FRAME_THRESHOLD 对齐）。
- **OQ4**: 真机 verify gate 是否包含 W4 vivo V2405A 小屏机型（V2 视觉规则约束）？还是仅华为 8KE02？建议：算法 verify 仅华为；UI 显示行为 verify 加 vivo 小屏机型（DELTA tile 显示 `--` 占位的视觉规则需要 maxLines + Ellipsis 验证）。

---

## 实施期 OQ 决议（2026-05-29 · road-test-first 模式）

> user 2026-05-29 启用 Road-test-first 执行模式（CLAUDE.md 同名节）：去 Codex + 跳 Opus 子 agent 多轮 review，靠 FileLogger 持久日志 + 真机攒批路测兜底。原本"待真机 benchmark 后 user 拍板"的 OQ1 由 CC 在 road-test-first 授权下定夺，benchmark gate（"太慢"来源之一）改为路测实证。

- **OQ1 决议 = Alt B（stateless 全量 O(n)）**。理由：(1) Alt B 是 design 推荐方案 + 根因修复——删除 prevMatchedIdx 连续性假设，4 个边界 bug 同时根除；(2) 先验性能：1500 frames × 25Hz、每帧 O(1500) 简单 float 投影 ≈ 微秒级，即便比估算慢 10× 仍 < 1ms ≪ 40ms 帧预算；(3) Alt B 最简单（无跨帧 cache / 无 reset 路径 / spec 不需 Decision 2），代码与风险最小；(4) 性能由真机路测实证（路测若掉帧会暴露），不卡 benchmark gate。**Decision 2（reset 触发契约）整体作废**（stateless 无 cache 可 reset）。
- **OQ2 决议 = 相对阈值** `STALE_DISPLAY_RATIO = 1.5`，stale 判定用「最近投影距离 > failoverDistanceM(50m)」（Alt B 每帧重算，无 cached prevDeltaMs）。DELTA tile 在 stale（投影失效）时显示 `--`；非 stale 显示实算 delta。无 cross-frame cache 携带离谱值，故不需 `|delta| > lapDuration×1.5` 的 cache 兜底分支（该分支是 Alt A cache 场景产物）。
- **OQ3 决议 = N/A**：Alt B stateless 无"GPS 信号丢失持续 N 帧后 reset"概念（无 cache）；GPS 信号丢失由现有 GPS_SIGNAL_LOST banner 优先级覆盖，DELTA 自然走 stale→`--`。
- **OQ4 决议**：算法层正确性靠**单测 4 边界 scenario** + 真机攒批路测（华为 8KE02 回放泊寓数据验 -125s/跳变消失）；UI `--` 占位的小屏 maxLines+Ellipsis 视觉验证并入真机攒批（vivo V2405A）。不单独排真机 gate（road-test-first 攒批）。

**FileLogger 埋点要求（road-test-first 安全网）**：`projectDelta` MUST 埋以下持久日志（tag `RTDelta`）：
- 每次投影命中：`v("RTDelta", "proj idx=$bestIdx dist=${minDist}m elapsed=$bestElapsedMs delta=$deltaMs")`（25Hz 用 v 级可过滤）
- 投影失效（stale，minDist > failoverDistanceM）：`d("RTDelta", "stale: minDist=${minDist}m > $failoverDistanceM, delta=--")`
- reference 重建：`d("RTDelta", "ref rebuilt: laps=$lapCount dur=$lapDurationMs frames=${xs.size}")`
路测 adb pull `filesDir/debug_log.txt` 即可诊断 -125s/跳变是否复现 + 定位帧。
