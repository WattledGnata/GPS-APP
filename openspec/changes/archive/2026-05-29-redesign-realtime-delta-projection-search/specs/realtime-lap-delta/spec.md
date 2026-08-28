## ADDED Requirements

### Requirement: projectDelta 在 4 边界场景输出 graceful 行为

`RealtimeDeltaCalculator.projectDelta` 在以下 4 个边界场景 MUST 输出"graceful"行为，**不得**输出明显错误的 `deltaMs`（定义为 `|deltaMs| > reference.lapDurationMs * 1.5`）：

1. **lap N → lap N+1 切换瞬间**：当前圈 `currentLapElapsedMs ≈ 0`，但 GPS 物理位置可能在 reference 任意 idx（含末段），算法 MUST 投影到正确 segment 而**不是**误匹配 reference 末段产生 `deltaMs ≈ -lapDurationMs`。Alt B 全量扫描天然满足（无 prevMatchedIdx 连续性假设）。
2. **GPS 信号丢失 → 重连**：重连后 GPS 物理位置可能已在赛道其他位置，算法 MUST 重新搜索整个 reference 而**不是**延续旧 cache 误匹配。Alt B 无跨帧 idx cache，天然满足。
3. **track 切换**：reference 是 trackA 但 user 已切 trackB，算法层 MUST 检测到不匹配并返回 null（让上游 ViewModel 触发 reference 重建 OR clear），**不得**强行投影输出无意义 delta
4. **GPS jitter 极端跳变**：单帧 lat/lon 偏离 reference > 50m（`failoverDistanceM` 阈值），算法 MUST 返回 null 进 stale 分支；Alt B stale 时 `outDeltaMs = null`（不维持旧值误导），UI 显示 `--` 占位。

#### Scenario: lap N → lap N+1 切换瞬间不输出 ≈ -lapDuration

- **WHEN** 用户完成 lap N（建立 reference，lapDurationMs = 60_000）+ 开启 lap N+1，第一帧 GPS 物理位置在赛道起点（应对应 reference idx 0 附近 elapsedMs ≈ 0），currentLapElapsedMs = 100ms（lap N+1 刚开圈 100ms）
- **THEN** projectDelta MUST 投影到 reference idx 0 附近（bestElapsed ≈ 0），输出 deltaMs ≈ +100ms
- **AND** **不得**返回 deltaMs ≈ -59_900ms（即误匹配 reference 末尾）

#### Scenario: lap 切换误匹配反例锁死（Alt B 实现）

- **WHEN** 用户完成 lap N 后开启 lap N+1，新 lap 第一帧 GPS 物理位置在 reference idx 0 附近，currentLapElapsedMs ≈ 0
- **THEN** projectDelta 输出的 `matchedIdx` MUST 在 [0, 2] 范围内（即真实匹配到 reference 起点附近）
- **AND** `deltaMs` MUST 满足 `abs(deltaMs) < 1000`（不得返回 ≈ -lapDurationMs）
- **AND** 单测 `lap switch instant - no minus lapDuration regression` 锁死此契约

#### Scenario: GPS 信号丢失重连后全量扫描（Alt B 天然满足）

- **WHEN** GPS 信号丢失后恢复，恢复瞬间 GPS 物理位置已跳到赛道另一处
- **THEN** projectDelta 全量扫描 `0..(size-2)` MUST 找到当前物理位置对应的正确 segment
- **AND** Alt B 无跨帧 idx cache，无需额外 reset 机制（与 Alt A 的 reset 触发源 #4 不同）

#### Scenario: track 切换时 reference 失效检测

- **WHEN** reference 是 trackA（gate 在 lat=30.0, lon=120.0 附近），但 user 已切到 trackB（gate 在 lat=31.0, lon=121.0 附近，与 trackA 距离 > 100km），新帧 GPS 物理位置在 trackB
- **THEN** projectDelta 投影距离 `projDistanceM` 极大（> 100km），返回 null（`failoverDistanceM` 默认 50m 阈值已覆盖此 case）
- **AND** ViewModel 上游 MUST 监听 `_currentSelectedTrack.value.id` 变化触发 reference clear（让下次跑出新 best lap 才重建）

---

### Requirement: 跨帧状态在边界场景 MUST reset（Alt B 简化版）

选 Alt B（stateless 全量搜索）实施后，`RealtimeDeltaState` MUST 仅保留 `reference / prevDeltaMs / staleFrameCount` 三字段（**MUST NOT** 含 prevMatchedIdx），且跨帧状态 MUST 在以下触发源按规则 reset：

1. **reference 首建**（已存在）—— `maybeRebuildReference` 当 `current == null` 时 reset `staleFrameCount = 0`
2. **PB 刷新**（已存在）—— `maybeRebuildReference` 当 `newBest.durationMillis < current.lapDurationMs` 时 reset `staleFrameCount = 0`
3. **stale 时 outDeltaMs = null**（Alt B 新行为）—— 失效帧 ≥ STALE_FRAME_THRESHOLD 时 `outDeltaMs = null`，UI 显示 `--`（不维持旧值）；Alt A 的 prevMatchedIdx reset 触发源 3/4 均由全量扫描天然根除
4. **track 切换**（待 follow-up）—— 监听 `_currentSelectedTrack.value.id` 变化 + reference = null，当前实现依赖 `maybeRebuildReference` 自然重建，正式 reset 在后续 round 补
5. **prevDeltaMs 写入无条件**（Alt B 实际不需要 cache guard）—— Alt B 成功路径投影到最近 segment 始终是全局最优，异常 delta 兜底由 `failoverDistanceM=50m` 覆盖，无需 1.5× 过滤；触发源 6 保留为防御性描述

**注**：Alt A 的 6 个触发源中，prevMatchedIdx 相关的 #3/4 已由 Alt B 全量扫描根除。

#### Scenario: lap 切换（非 PB）触发 prevMatchedIdx reset

- **WHEN** session.completedLaps.size 从 1 增加到 2，新完成圈 durationMillis 大于 reference.lapDurationMs（不是 PB）
- **THEN** _realtimeDeltaState.prevMatchedIdx 被 reset 为 -1
- **AND** _realtimeDeltaState.staleFrameCount 被 reset 为 0
- **AND** _realtimeDeltaState.reference 不变（不重建）

#### Scenario: track 切换清空 reference

- **WHEN** _currentSelectedTrack.value.id 从 "trackA" 变为 "trackB"
- **THEN** _realtimeDeltaState.reference 被 set 为 null
- **AND** _realtimeDeltaState.prevDeltaMs 被 set 为 null
- **AND** 下一帧 GPS data 来到时 outDeltaMs = null（直到新 best lap 在 trackB 上完成才重建 reference）

#### Scenario: GPS 信号阶跃恢复 reset

- **WHEN** 连续 5 帧 gpsData.satelliteCount == 0（信号丢失）后第 6 帧 satelliteCount > 0（恢复）
- **THEN** 恢复后第 1 帧的 _realtimeDeltaState.prevMatchedIdx 被 reset 为 -1
- **AND** projectDelta 重新从全量 reference 搜索（或等价机制）

#### Scenario: prevDeltaMs cache 异常兜底（反例锁死）

- **WHEN** projectDelta 返回 DeltaProjection(deltaMs = -125_000, matchedIdx = 1500, projDistanceM = 30f)，reference.lapDurationMs = 60_000，则 |deltaMs| / lapDurationMs = 2.08 > 1.5
- **THEN** _realtimeDeltaState.prevDeltaMs MUST NOT 被写入 -125_000（拒绝异常 cache）
- **AND** staleFrameCount 增加 1（视同 projectDelta 失败）
- **AND** 下游 outDeltaMs 用上一帧合理 prevDeltaMs（或 null 如未曾成功）

---

### Requirement: LapLiveScreen DELTA tile 在 stale + cache 异常时显示占位

`LapLiveScreen.Lap2x2Dashboard` 的 DELTA tile MUST 在以下情况显示 `--`（占位）而**不是**显示数字：

- `state.deltaToBestMs == null`（已存在）
- **新增**：`state.deltaIsStale == true && |state.deltaToBestMs| > LAP_DURATION_TYPICAL_MS_DEFAULT * 1.5`（数值显著异常时不显示，避免误导用户）

`LAP_DURATION_TYPICAL_MS_DEFAULT` 默认值待 OQ2 拍板（建议 90_000ms 或动态用 `reference.lapDurationMs`）。

#### Scenario: stale + 数值合理仍显示

- **WHEN** state.deltaToBestMs = -2_500（合理 lap delta）+ state.deltaIsStale = true + LAP_DURATION_TYPICAL_MS_DEFAULT = 90_000
- **THEN** DELTA tile value = "-2.50 s"
- **AND** accentColor = TextMuted（保留 baseline 灰色行为）

#### Scenario: stale + 数值异常显示占位（反例锁死 -125 现象）

- **WHEN** state.deltaToBestMs = -125_200（异常）+ state.deltaIsStale = true + LAP_DURATION_TYPICAL_MS_DEFAULT = 90_000
- **THEN** DELTA tile value = "--"
- **AND** accentColor = TextMuted
- **AND** 用户视觉上看不到 "-125.20 s" 字样（这是 user 真机 verify gate 的核心反例）

#### Scenario: 非 stale + 数值异常仍显示（不当场掩盖）

- **WHEN** state.deltaToBestMs = -125_200 + state.deltaIsStale = false（这种情况应是上游 cache invalidation 已生效不会发生，但作为防御性 contract）
- **THEN** DELTA tile value = "-125.20 s"（暴露问题，不掩盖）
- **AND** accentColor = Green（按数值正负染色，保留 baseline 行为）

---

## MODIFIED Requirements

### Requirement: projectDelta 纯函数

`feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt` MUST 提供 `internal fun projectDelta(...)` pure function。

**算法策略**（design Decision 1 拍板后填充最终签名）：

- **如 Alt B（stateless 全量 O(n)，推荐）**：删除 `prevMatchedIdx` 和 `forwardWindowFrames` 入参，每帧从 segment 0 扫到 size-2 找最近投影。新签名：

```kotlin
internal fun projectDelta(
    reference: ReferenceLapIndex,
    currentLapElapsedMs: Long,
    currentX: Float,
    currentY: Float,
    failoverDistanceM: Float = 50f,
): DeltaProjection?
```

- **如 Alt A（保留 prevMatchedIdx + reset 触发契约）**：保留旧签名（含 prevMatchedIdx + forwardWindowFrames），但行为契约新增"4 边界场景 graceful"约束（参 ADDED Requirement: projectDelta 在 4 边界场景输出 graceful 行为）

行为契约（共性，无论 Alt A/B/C）：

- 选投影距离最小的 segment + 投影比例 `t ∈ [0, 1]`
- `bestElapsed = elapsedMs[i] + t * (elapsedMs[i+1] - elapsedMs[i])`
- `deltaMs = currentLapElapsedMs - bestElapsed`
- `projDistanceM = sqrt(distSq)`
- 若 `projDistanceM > failoverDistanceM` → 返回 null（失效）
- 4 边界场景 MUST 输出 graceful 行为（参 ADDED Requirement）

#### Scenario: 同轨迹 delta = 0

- **WHEN** 当前 GPS 点 = best 圈的某个 trajectory 点 + currentLapElapsedMs = 该点 elapsedMs
- **THEN** projectDelta 返回 DeltaProjection
- **AND** abs(deltaMs) ≤ 5（容差 5ms 容许投影插值数值误差）

#### Scenario: 当前圈慢 1s

- **WHEN** 当前 GPS 点 = best 圈第 50 帧位置 + currentLapElapsedMs = best 圈第 50 帧 elapsedMs + 1000
- **THEN** abs(deltaMs - 1000) ≤ 5

#### Scenario: 当前圈快 1s

- **WHEN** 当前 GPS 点 = best 圈第 50 帧位置 + currentLapElapsedMs = best 圈第 50 帧 elapsedMs - 1000
- **THEN** abs(deltaMs - (-1000)) ≤ 5

#### Scenario: GPS 跳变触发失效

- **WHEN** 当前 GPS 点偏离 best 圈轨迹 > 50m（例如赛道外 100m）
- **THEN** projectDelta 返回 null

#### Scenario: 跨整 reference 大跳搜索（替换原"空 forward 窗口边界处理"）

- **WHEN** 当前 GPS 点对应 reference 末段 idx ≈ size-1（lap 切换或 GPS 信号阶跃恢复场景），无论"上一帧"投影位置在哪
- **THEN** projectDelta MUST 能正确投影到 reference 末段（matchedIdx ≈ size-1）
- **AND** 如选 Alt B：纯函数无 prevMatchedIdx 入参，自动满足
- **AND** 如选 Alt A：调用方 ViewModel MUST 在边界场景 reset prevMatchedIdx = -1（参 ADDED Requirement: 跨帧状态在边界场景 MUST reset），projectDelta 才能跨整 reference 搜索

---

### Requirement: TestSessionViewModel 拥有 projectDelta 算法 + 跨帧状态

`TestSessionViewModel` MUST 维护 `_realtimeDeltaState: MutableStateFlow<RealtimeDeltaState>`，并是**唯一调用 projectDelta 的位置**（Deriver 不调）。

**RealtimeDeltaState 字段集**（Alt B 实施后确认）：

- **Alt B（已实施）**：`reference / prevDeltaMs / staleFrameCount`（prevMatchedIdx 已删除，根除连续性假设）

跨帧更新规则（共性）：

- 每帧 GPS data 来到 + `reference != null` + `lastAcceptedCrossing != null` 时：
  1. 米坐标转换：`(curX, curY) = reference.toLocalMeters(gpsData.latitude, gpsData.longitude)`
  2. `currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`（GPS sample ts 域同源相减）
  3. 调 `projectDelta(reference, currentLapElapsedMs, curX, curY)` —— Alt B 无 prevMatchedIdx 参数
  4. atomic update `_realtimeDeltaState`
  5. 派生本帧 outputs（用于喂给 deriver）：
     - 成功 → `outDelta = projection.deltaMs, outIsStale = false, prevDeltaMs = projection.deltaMs`
     - 失败 + staleFrameCount + 1 < STALE_FRAME_THRESHOLD → `outDelta = prevDeltaMs, outIsStale = false`
     - 失败 + staleFrameCount + 1 >= STALE_FRAME_THRESHOLD → `outDelta = null, outIsStale = true`（Alt B：stale 时不维持旧值）
     - 失败 + prevDeltaMs == null（从未成功过）→ `outDelta = null, outIsStale = false`

reference 生命周期（保留 + 新增）：

- **首圈完成立即建立 reference**（保留）
- **PB 刷新**（保留）
- **track 切换**：reference set 为 null（**新增**）
- **active lap 重置**（保留）

跨帧状态 reset 触发（参 ADDED Requirement: 跨帧状态在边界场景 MUST reset，6 个触发源）。

#### Scenario: 首圈完成立即建 reference（保留）

- **WHEN** session 首圈完成 + completedLaps.size 由 0 变 1
- **THEN** _realtimeDeltaState.reference 用该唯一完成圈建立
- **AND** 下一帧 GPS data 即可派生 delta

#### Scenario: PB 刷新触发 reference 重建（保留）

- **WHEN** 当前 reference.lapDurationMs = 90000 + 新完成圈 durationMillis = 88000
- **THEN** _realtimeDeltaState.reference 被重建为 88s 圈的 ReferenceLapIndex

#### Scenario: ViewModel 单帧只调一次 projectDelta（保留）

- **WHEN** 单次 GPS data 流入触发 ViewModel 处理
- **THEN** projectDelta 在该帧的 ViewModel 处理路径中调用次数 = 1

#### Scenario: 数值异常 cache 拒绝写入（新增反例锁死 -125 现象）

- **WHEN** projectDelta 返回成功 DeltaProjection(deltaMs = -125_000)，reference.lapDurationMs = 60_000，比值 2.08 > 1.5
- **THEN** _realtimeDeltaState.prevDeltaMs MUST NOT 被写入 -125_000
- **AND** staleFrameCount 增加 1（视同失败）
- **AND** 测试 MUST 用 mock projectDelta 返回 -125_000 + 断言 prevDeltaMs 不变 锁死此契约

---

### Requirement: LapLiveScreen DELTA tile 渲染 stale 状态

`LapLiveScreen.Lap2x2Dashboard` 的 DELTA tile MUST 按 `state.deltaIsStale` 分支 + 数值合理性渲染：

- `deltaToBestMs == null` → value = `--`，accentColor = TextMuted
- **新增**：`deltaIsStale == true && |deltaToBestMs| > LAP_DURATION_TYPICAL_MS_DEFAULT * 1.5` → value = `--`，accentColor = TextMuted
- `deltaIsStale == true` 且数值合理 → value = formatDelta(deltaToBestMs)（保留上一帧数字），accentColor = TextMuted（**不**用 Green/Red）
- `deltaIsStale == false` 且 deltaToBestMs != null → value = formatDelta(...)，accentColor = `< 0 ? Green : > 0 ? Red : TextPrimary`

#### Scenario: stale + 数值合理保留显示（保留 baseline）

- **WHEN** state.deltaToBestMs = -500 + state.deltaIsStale = true
- **THEN** DELTA tile accentColor = TrackTechColors.TextMuted
- **AND** value 仍显示 -0.500

#### Scenario: stale + 数值异常显示占位（新增反例锁死 -125 现象）

- **WHEN** state.deltaToBestMs = -125_200 + state.deltaIsStale = true
- **THEN** DELTA tile value = "--"
- **AND** accentColor = TextMuted

#### Scenario: 正常 negative delta 显示绿色（保留）

- **WHEN** state.deltaToBestMs = -500 + state.deltaIsStale = false
- **THEN** DELTA tile accentColor = TrackTechColors.Green

#### Scenario: 正常 positive delta 显示红色（保留）

- **WHEN** state.deltaToBestMs = +500 + state.deltaIsStale = false
- **THEN** DELTA tile accentColor = TrackTechColors.Red

---

### Requirement: 算法路径在受控参数下 5Hz 性能可忽略

projectDelta 单次调用在 JVM 单线程基准下 MUST 满足以下性能约束（具体阈值随 Alt 选择）：

- **如 Alt B（stateless 全量 O(n)）**：reference.size = 2250 时单次调用 < 1500µs（含全量 segment 投影 + segment 选择 + 时间插值）
- **如 Alt A（保留 prevMatchedIdx）**：reference.size = 2250、forwardWindowFrames = 200 时单次调用 < 50µs（保持原阈值）

性能 baseline 实测在 design Decision 4 / OQ1 完成（华为 8KE0219522008434）。

#### Scenario: Alt B 性能基准（informational）

- **WHEN** 单测中跑 1000 次 stateless projectDelta 调用 + 2250 点 reference
- **THEN** 总耗时 < 1500ms（平均每次 < 1500µs）
- **AND** 该测试可标 @Ignore 或仅作 manual run

#### Scenario: Alt A 性能基准（保留原阈值）

- **WHEN** 单测中跑 1000 次 prevMatchedIdx 优化 projectDelta 调用 + 2250 点 reference + forwardWindowFrames = 200
- **THEN** 总耗时 < 50ms（平均每次 < 50µs）

---

## REMOVED Requirements

无（本 round 不删除现有 Requirement，仅修订 + 新增反例 / 边界 / UI 占位规则）。
