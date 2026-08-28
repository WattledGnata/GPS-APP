## Why

V2 圈速实时屏 `LapLiveScreen` 的 DELTA tile 显示"实时秒差"对比 best 圈，但当前 baseline `LapLiveStateDeriver.kt:100-104` 实现是错位减法：

```kotlin
val deltaToBestMs = if (currentLapTimerMs != null && bestLapTimeMs != null) {
    currentLapTimerMs - bestLapTimeMs
} else null
```

`currentLapTimerMs` 是当前圈**已用时间**（从最近一次冲线到现在，0 起步），`bestLapTimeMs` 是最佳圈**完整用时**。两个量纲完全不同，相减没物理意义。

用户实测体感：第二圈起 DELTA tile 一直显示绿色巨大负数（`-1:29.500` 之类，仿佛持续 PB 90 秒），到当前圈 timer 真正超过 best 时（即将冲线、且这圈比 best 慢的极少数瞬间）才闪一下红色 → 用户被骗 90% 时间。

根因是该实现是 add-lap-session-phase1 round 期间的 placeholder，没细化也没沉淀 deferred memo，留作技术债。本 round 重做"实时秒差"为正确语义，对齐工业 telemetry（RaceChrono / Harry's LapTimer / VBOX / AiM）的 distance-based projection 范式。

## What Changes

新增 capability `realtime-lap-delta`：

### 算法（语义 B：distance-based projection）

- **参照圈**：当前 session 内 `bestLapTimeMs` 对应的完整圈轨迹（`LapSession.completedLaps.minBy { durationMillis }.trajectory`）。**不跨 session**（不同 session 车况 / 天气 / 配置不一致，无参考性）
- **触发条件**：至少 1 个完整圈才有 reference，第 1 圈进行中 DELTA tile 显示 muted `--:--.---`
- **每帧匹配**：当前 GPS 点投影到 best 圈 polyline，找最近"投影点"（不是最近 GPS 点 —— 离散 GPS 点法会让 delta 在 ±20ms 网格里抖，工业 telemetry 都用 polyline projection）
- **时间投影**：用投影比例在 segment 两端的 `timestampMillis - lapStartMillis` 之间线性插值，得到 `bestElapsedMsAtSameProgress`
- **delta**：`deltaMs = currentLapElapsedMs - bestElapsedMsAtSameProgress`；正 = 慢（红），负 = 快（绿）

### 数据结构

新建 `feature/test/src/main/.../usecase/ReferenceLapIndex.kt`（含投影原点 + toLocalMeters helper，让调用方零状态把任意 GPS 点转到与 reference 同一坐标系；时间原点严格用 `bestLap.startedAtMillis` 避免 trajectory 首点采样间隔偏移）：

```kotlin
internal data class ReferenceLapIndex(
    val refLat: Double,            // 投影原点纬度（trajectory.first().latitude）
    val refLon: Double,            // 投影原点经度（trajectory.first().longitude）
    val xs: FloatArray,            // best 圈每点本地米坐标 x（相对 refLat/refLon 平面投影）
    val ys: FloatArray,            // 同上 y
    val cumDistanceM: FloatArray,  // 累计距离（首点 0，单调非降）
    val elapsedMs: LongArray,      // 相对 bestLap.startedAtMillis 的偏移（首点 ≥ 0，反映 trajectory 首点滞后 crossing 的真实采样间隔）
    val lapStartTsMs: Long,        // bestLap.startedAtMillis（开圈 crossing 时间，**不是** trajectory.first().timestampMillis）
    val lapDurationMs: Long,
) {
    fun toLocalMeters(lat: Double, lon: Double): Pair<Float, Float> =
        LocalPlaneProjection.toMeters(refLat, refLon, lat, lon)
}

internal fun buildReferenceLapIndex(bestLap: LapRecord): ReferenceLapIndex?
```

### 算法核心 pure function

新建 `feature/test/src/main/.../usecase/RealtimeDeltaCalculator.kt`：

```kotlin
internal data class DeltaProjection(
    val deltaMs: Long,
    val matchedIdx: Int,         // 命中 segment 起点 idx，作为下帧 prevIdx
    val projDistanceM: Float,    // 投影距离（米），用于 fallback 失效判断
)

internal fun projectDelta(
    reference: ReferenceLapIndex,
    currentLapElapsedMs: Long,        // 用 GPS sample ts 域（gpsData.timestamp - lastAcceptedCrossing.timestampMillis），不混 wall clock
    currentX: Float,
    currentY: Float,
    prevMatchedIdx: Int,              // -1 = 走前向窗口起点 0
    forwardWindowFrames: Int = 200,   // ±200 帧 ≈ ±260m 搜索半径
    failoverDistanceM: Float = 50f,   // 投影距离超过此值视为失效，返回 null
): DeltaProjection?                   // null = 失效（投影距离 > failoverDistanceM）
```

### LapLiveStateDeriver 仅消费 ViewModel 算好的 delta（不调 projectDelta）

> Codex P1-4 review 抓到的设计错误已修订：算法责任只在 ViewModel，Deriver 是纯派生。

- `LapLiveStateDeriver.derive` 入参新增 `deltaToBestMs: Long?` 与 `deltaIsStale: Boolean` 两个标量；保留 `currentTimeMs / currentDisplayTimeMs`（语义 = ticker 推动的 UI 显示时间，**baseline 行为不动**避免 5Hz replay 下 CURRENT tile 跳动 — Codex P2 抓到）
- `LapLiveState` 增加 `deltaIsStale: Boolean` 字段供 UI 渲染 muted 状态
- Deriver 不调 projectDelta，不读跨帧状态；仅做"两个入参直传 LapLiveState + 其它字段沿用 baseline"

### TestSessionViewModel 拥有 projectDelta 算法 + 跨帧状态

- 新建 `_realtimeDeltaState: MutableStateFlow<RealtimeDeltaState>`（reference + prevMatchedIdx + prevDeltaMs + staleFrameCount 4 字段）
- **首圈完成立即建 reference**（Codex P1-3 修订）：`completedLaps` 由空变 size = 1 → `buildReferenceLapIndex(completedLaps[0])`；下一帧即可显示 delta
- PB 刷新（新完成圈 durationMillis < reference.lapDurationMs）→ 重建 reference + reset prevMatchedIdx / staleFrameCount
- 每帧 GPS data 来到（在 GpsData StateFlow collect 路径）→ 米坐标转换（用 `reference.toLocalMeters(...)`）→ 调 projectDelta（ViewModel 是唯一调用方）→ atomic update state → 派生本帧 outDelta / outIsStale 喂给 deriver
- delta 内部用的 `currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`（GPS sample ts 域同源相减）；与 CURRENT tile 的 ticker 时间分离（Codex P2 修订）

### LapLiveScreen UI

`Lap2x2Dashboard` DELTA tile 渲染：

- `state.deltaToBestMs == null` → 显示 `--:--.---`（TextMuted）
- `deltaIsStale == true` → 显示 `prevDeltaMs` 数值但字色降级 TextMuted（保持上一帧数字 + 视觉标记 stale）
- 正常态 → 现有 Green/Red 分支

### 时钟域分离（delta 用 GPS sample ts；CURRENT tile 继续 ticker 外推）

> Codex P2 review 抓到的"切 currentLapTimerMs 到 GPS sample 域会让 5Hz replay 下 200ms 一跳"已修订：**只在 ViewModel 内部 delta 计算路径**用 GPS sample ts，CURRENT tile 显示路径保持 baseline ticker 外推不动。

- **delta 计算（仅 ViewModel 内部）**：`currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`，两个 ts 都是 GPS sample 时钟域同源相减；与 ReferenceLapIndex.elapsedMs[i]（同 GPS sample 域，相对 `bestLap.startedAtMillis`）配合，最终 `delta = currentLapElapsedMs - bestElapsed` 全在 GPS sample 域，无 wall clock 污染
- **CURRENT tile UI 显示路径**：`currentLapTimerMs = currentDisplayTimeMs - lastAcceptedCrossing.timestampMillis`，由 ViewModel ticker / `SystemClock.elapsedRealtime()` 推动 `currentDisplayTimeMs`，**baseline 行为保留** —— 5Hz replay 下两个 GPS 帧之间 CURRENT tile 仍能平滑递增
- 两个语义不同：前者是数学精度关键（必须 GPS sample 域），后者是体验流畅关键（必须 ticker 外推），分离最干净，不破坏既有 baseline 实现

### 单元测试

新建 `feature/test/src/test/.../usecase/RealtimeDeltaCalculatorTest.kt`：

- 同轨迹 → delta = 0ms（精度 ±5ms 容差）
- 当前圈慢 1s → delta = +1000ms
- 当前圈快 1s → delta = -1000ms
- GPS 点跳变（突然 100m 偏移）→ projectDelta 返回 null（失效）
- 无 best lap → reference null 路径，deltaToBestMs 应为 null
- PB 切换：圈 N 完成成为新 best → reference 重建 + prevMatchedIdx reset 为 -1
- stale N 帧门：5 帧失效后 isStale = true；中间 1 帧成功重置计数
- active lap 重置：开新圈时 prevMatchedIdx 重置为 -1（避免上次跑断状态污染）
- 时钟域同源：测试断言 `projectDelta` 内部用的 elapsedMs 是 (currentSampleTs - crossingTs) 而非 wall clock

不做的事（明确 out-of-scope）：

- **不**做跨 session 的 PB 比较（按 user 拍板：不同 session 车况/天气/配置无参考性）
- **不**做 sector-based delta（sector 时间分析是后续独立 round；本 round 只做整圈进度位置匹配）
- **不**做赛道里程预计算（segment 投影内部用累计距离即可，不需要把每个 GPS 点跟赛道几何模型对齐）
- **不**做 100 段固定采样分组（user 已在 brainstorm 阶段拍板：第一版用完整 trajectory + 前向窗口；100 段分组留 sector-based delta / heatmap / 缩略图采样等后续 round）
- **不**加 EMA / 滑动平均（第一版先看真机体感；如显著抖动后续 round 加 250-500ms EMA）
- **不**改 LapTimingEngine（trajectory / completedLaps 等输入数据契约保持）
- **不**改 BLE / GPS / RaceChrono 协议
- **不**改 binary 持久化（数据已在内存可达）

## Capabilities

### New Capabilities

- `realtime-lap-delta`: V2 圈速实时屏 DELTA tile 的 distance-based projection delta 计算契约 —— ReferenceLapIndex 数据结构、polyline segment 投影算法、前向窗口搜索、失效 fallback、stale 状态、时钟域同源约束、第一圈无 best 显示约定、PB 刷新 reference 切换约定

### Modified Capabilities

无。原 `LapLiveStateDeriver` 是 add-lap-session-phase1 round 内部状态派生，没单独 spec，因此不算 modified。本 round 通过 `realtime-lap-delta` 新 capability 覆盖该派生子集。

## Impact

### 受影响代码

- **新建**：
  - `feature/test/src/main/java/com/blazepush/feature/test/usecase/ReferenceLapIndex.kt`（数据结构 + builder）
  - `feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt`(`projectDelta` pure function + 米坐标投影 helper)
  - `feature/test/src/test/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculatorTest.kt`（约 10 cases）
  - `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReferenceLapIndexTest.kt`（builder 单测：累计距离单调 / 起点 0 / 米坐标转换 / 空 trajectory 边界）
- **修改**：
  - `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt`（重做 deltaToBestMs 派生 + 入参签名扩展 + LapLiveState 加 deltaIsStale 字段）
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（加 _realtimeDeltaState + reference 重建逻辑 + 每帧调 projectDelta）
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapLiveScreen.kt`（DELTA tile stale 状态分支）

### 不受影响

- `core/*` 全部模块、`simulator/*` 全部模块（不涉及协议 / 数据持久化）
- `app/*`、其它 home screen / detail screen
- `LapTimingEngine` —— 不动（trajectory / completedLaps 已是它的输出）
- `LapSession` / `LapRecord` / `GpsSample` 数据结构 —— 不动
- BLE / GPS 数据链路、RaceChrono BLE 协议
- Room schema / binary persistence

### 协议兼容性

无协议改动。

### 双端

仅接收端（gps-app）改动；发射端（simulator）不动。

### 多 change 并行协同

`LapLiveStateDeriver.kt` / `TestSessionViewModel.kt` / `LapLiveScreen.kt` 当前看板 §6 状态：

- `TestSessionViewModel.kt`：A round（fix-lap-binary-ts-hygiene）实施中，改 `bridgeGpsToLapTiming` 内部 anchor 公式（line 596 附近）。本 round 加顶层 `_realtimeDeltaState` field，**与 A 函数级不重叠**
- `LapLiveStateDeriver.kt`：本 round 独占
- `LapLiveScreen.kt`：本 round 独占

启动前需在看板 §5/§6 登记，A round 合回后 rebase 跟上。

### 测试影响

- 新增 ~80 行 pure function 单测（不依赖 Robolectric / Compose runtime）
- 现有 `:feature:test:testDebugUnitTest` 全套 MUST 零回归
- 真机验证（华为 8KE0219522008434）：跑 2-3 圈观察 DELTA tile 行为：
  1. 第 1 圈进行中 → muted `--:--.---`
  2. 第 1 圈完成瞬间 → reference 立即建立；第 2 圈第一帧 GPS 数据到达后 DELTA 即开始显示相对首圈的实时秒差（短暂跨越 1-2 帧 GPS 延迟期间可能仍 muted，但不应永久 muted 整个第 2 圈）
  3. 第 2 圈进行中 → 数字平滑变化，绿/红反映与 best 节奏的对比
  4. 故意外线变线 → delta 数字短暂 stale 后恢复
  5. 故意 GPS 信号丢失 → 维持上一帧 + 字色降 muted
