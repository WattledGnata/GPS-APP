# realtime-lap-delta Specification

## Purpose
TBD - created by archiving change add-realtime-lap-delta. Update Purpose after archive.
## Requirements
### Requirement: ReferenceLapIndex 数据结构

`feature/test/src/main/java/com/blazepush/feature/test/usecase/ReferenceLapIndex.kt` MUST 提供 `internal data class ReferenceLapIndex` 结构，封装 best 圈轨迹的预计算索引（含投影原点，使调用方可以把任意 GPS lat/lon 转到同一坐标系）：

```kotlin
internal data class ReferenceLapIndex(
    val refLat: Double,            // 投影原点纬度（用 trajectory.first().lat）
    val refLon: Double,            // 投影原点经度（用 trajectory.first().lon）
    val xs: FloatArray,            // best 圈每点本地米坐标 x（相对 refLat/refLon）
    val ys: FloatArray,            // best 圈每点本地米坐标 y
    val cumDistanceM: FloatArray,  // 累计距离（首点 0，单调非降）
    val elapsedMs: LongArray,      // 相对 bestLap.startedAtMillis 的 ts 偏移（首点 ≥ 0，单调非降）
    val lapStartTsMs: Long,        // bestLap.startedAtMillis（开圈 crossing 时间，GPS sample ts 域）
    val lapDurationMs: Long,       // 完整圈用时
) {
    /** 把任意 GPS lat/lon 转成与 reference 同一坐标系的本地米坐标 */
    fun toLocalMeters(lat: Double, lon: Double): Pair<Float, Float> =
        LocalPlaneProjection.toMeters(refLat, refLon, lat, lon)
}
```

MUST 提供 `internal fun buildReferenceLapIndex(bestLap: LapRecord): ReferenceLapIndex?`：

- 输入 `LapRecord`（含 `trajectory: List<GpsSample>` + `startedAtMillis` + `durationMillis`）
- trajectory.size < 2 → 返回 null
- refLat / refLon 用 trajectory.first() lat/lon 作平面投影中心
- **lapStartTsMs = bestLap.startedAtMillis**（开圈 crossing 时间，**不是** trajectory.first().timestampMillis；trajectory 首点通常晚一个采样间隔，会引入固定秒差偏移）
- elapsedMs[i] = `trajectory[i].timestampMillis - bestLap.startedAtMillis`（GPS sample ts 域，**首点 ≥ 0** 反映 trajectory 首点相对 crossing 的真实滞后）
- cumDistanceM[i] = sum of segment lengths from index 0 to i

#### Scenario: ReferenceLapIndex builder 输出契约

- **WHEN** 输入 trajectory 包含 100 个 GPS sample（5s 持续，25Hz），bestLap.startedAtMillis = 1000 + trajectory.first().timestampMillis = 1020
- **THEN** 返回的 ReferenceLapIndex MUST 满足：
  - `xs.size == 100`，`ys.size == 100`，`cumDistanceM.size == 100`，`elapsedMs.size == 100`
  - `refLat == trajectory.first().latitude`，`refLon == trajectory.first().longitude`
  - `lapStartTsMs == 1000`（bestLap.startedAtMillis，**不是** 1020）
  - `cumDistanceM[0] == 0f`
  - `elapsedMs[0] == 20L`（首点滞后 crossing 20ms，**不是** 0）
  - `cumDistanceM` 单调非降
  - `elapsedMs` 单调非降

#### Scenario: toLocalMeters 投影一致性

- **WHEN** ref.refLat = 30.0, ref.refLon = 120.0；调 `ref.toLocalMeters(30.0, 120.0)`
- **THEN** 返回 (0f, 0f)（同点投影到原点）

#### Scenario: 空或单点 trajectory 返回 null

- **WHEN** 输入 trajectory.size < 2
- **THEN** 返回 null

### Requirement: projectDelta 纯函数

`feature/test/src/main/java/com/blazepush/feature/test/usecase/RealtimeDeltaCalculator.kt` MUST 提供 `internal fun projectDelta(...)` pure function：

```kotlin
internal data class DeltaProjection(
    val deltaMs: Long,
    val matchedIdx: Int,
    val projDistanceM: Float,
)

internal fun projectDelta(
    reference: ReferenceLapIndex,
    currentLapElapsedMs: Long,
    currentX: Float,
    currentY: Float,
    prevMatchedIdx: Int,
    forwardWindowFrames: Int = 200,
    failoverDistanceM: Float = 50f,
): DeltaProjection?
```

行为契约：

- 在 `prevMatchedIdx ± forwardWindowFrames` 范围内（边界 `coerceIn(0, size-1)`），对每个 segment `[i, i+1]` 做点到线段投影
- 选投影距离最小的 segment + 投影比例 `t ∈ [0, 1]`
- `bestElapsed = elapsedMs[i] + t * (elapsedMs[i+1] - elapsedMs[i])`
- `deltaMs = currentLapElapsedMs - bestElapsed`
- `projDistanceM = sqrt(distSq)`
- 若 `projDistanceM > failoverDistanceM` → 返回 null（失效）
- `prevMatchedIdx == -1` → 起点用 0，向前扫 `forwardWindowFrames` 帧

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

#### Scenario: 空 forward 窗口边界处理

- **WHEN** prevMatchedIdx = -1（首帧）+ reference.size = 1000
- **THEN** projectDelta 在 [0, 200] 范围内搜索（不抛异常）

### Requirement: LapLiveStateDeriver 重做 deltaToBestMs 派生（仅消费 ViewModel 已算结果）

`LapLiveStateDeriver.derive` 入参 MUST 扩展为如下形式（**不**直接调 projectDelta；projectDelta 由 TestSessionViewModel 在每帧 GPS data 来到时同步调一次，结果作为入参传给 derive）：

```kotlin
fun derive(
    session: LapSession?,
    currentDisplayTimeMs: Long,     // wall-clock / elapsedRealtime ticker，用于 currentLapTimerMs UI 平滑显示（与 baseline 一致，**不**改）
    gpsData: GpsData,
    connectionState: ConnectionState,
    dataQuality: DataQuality,
    deltaToBestMs: Long?,           // ViewModel 已算好的 delta（成功投影直接给值；失效时维持上一帧值）
    deltaIsStale: Boolean,          // ViewModel 已算好的 stale 标志
): LapLiveState
```

`LapLiveState` MUST 增加 `deltaIsStale: Boolean` 字段。

**职责边界（apply 阶段不可漂移）**：

- **ViewModel** 拥有 projectDelta 算法 + 跨帧状态 `RealtimeDeltaState`（reference / prevMatchedIdx / prevDeltaMs / staleFrameCount）+ stale 5 帧门 + reference 重建。每帧 GPS data 来到时**单次调用** projectDelta，原子 update 状态后产出该帧的 `deltaToBestMs` / `deltaIsStale` 两个值
- **Deriver** 是纯派生函数，**不**调 projectDelta，**不**读跨帧状态。它只消费 ViewModel 算好的 deltaToBestMs / deltaIsStale 两个标量值，组装到 LapLiveState 里

**为什么这么分**：避免 Deriver 与 ViewModel 在同一帧重复调 projectDelta（双计算 + 状态漂移）。算法只跑一次（在 ViewModel），结果向下游 fan-out。

派生约束：

- `currentLapTimerMs` MUST 沿用 baseline 公式（`currentDisplayTimeMs - lastAcceptedCrossing.timestampMillis`），由 ViewModel 通过 ticker / elapsedRealtime 在 GPS 帧之间外推保持平滑（**这部分本 round 不动**）
- LapLiveState.deltaToBestMs / deltaIsStale 直接来自入参（不再调 projectDelta）
- 其它字段（lastLapTimeMs / bestLapTimeMs / currentLapNumber / abnormalState）派生逻辑保持 baseline

#### Scenario: 无 reference 第一圈 → muted

- **WHEN** ViewModel 入参 deltaToBestMs = null + deltaIsStale = false
- **THEN** LapLiveState.deltaToBestMs = null
- **AND** LapLiveState.deltaIsStale = false

#### Scenario: 入参直传

- **WHEN** ViewModel 入参 deltaToBestMs = -500 + deltaIsStale = false
- **THEN** LapLiveState.deltaToBestMs = -500
- **AND** LapLiveState.deltaIsStale = false

#### Scenario: stale 入参直传

- **WHEN** ViewModel 入参 deltaToBestMs = -500 + deltaIsStale = true
- **THEN** LapLiveState.deltaToBestMs = -500
- **AND** LapLiveState.deltaIsStale = true

#### Scenario: Deriver 不直接调 projectDelta

- **WHEN** 在 `LapLiveStateDeriver.kt` 中查找
- **THEN** **不**出现 `projectDelta(` 调用（算法位于 ViewModel）
- **AND** **不** import `RealtimeDeltaCalculator`

### Requirement: TestSessionViewModel 拥有 projectDelta 算法 + 跨帧状态

`TestSessionViewModel` MUST 维护 `_realtimeDeltaState: MutableStateFlow<RealtimeDeltaState>`，并是**唯一调用 projectDelta 的位置**（Deriver 不调）：

```kotlin
internal data class RealtimeDeltaState(
    val reference: ReferenceLapIndex?,
    val prevMatchedIdx: Int = -1,
    val prevDeltaMs: Long? = null,
    val staleFrameCount: Int = 0,
)
```

跨帧更新规则：

- 每帧 GPS data 来到 + `reference != null` + `lastAcceptedCrossing != null` 时：
  1. 米坐标转换：`(curX, curY) = reference.toLocalMeters(gpsData.latitude, gpsData.longitude)`
  2. `currentLapElapsedMs = gpsData.timestamp - lastAcceptedCrossing.timestampMillis`（GPS sample ts 域同源相减）
  3. 调 `projectDelta(reference, currentLapElapsedMs, curX, curY, prevMatchedIdx, ...)`
  4. atomic update `_realtimeDeltaState`：
     - 成功 → `prevMatchedIdx = projection.matchedIdx, prevDeltaMs = projection.deltaMs, staleFrameCount = 0`
     - 失败 → `staleFrameCount = (staleFrameCount + 1)`，prevMatchedIdx / prevDeltaMs 不变
  5. 派生本帧 outputs（用于喂给 deriver）：
     - 成功 → `outDelta = projection.deltaMs, outIsStale = false`
     - 失败 + prevDeltaMs != null + staleFrameCount + 1 < 5 → `outDelta = prevDeltaMs, outIsStale = false`
     - 失败 + staleFrameCount + 1 >= 5 → `outDelta = prevDeltaMs, outIsStale = true`
     - 失败 + prevDeltaMs == null（从未成功过）→ `outDelta = null, outIsStale = false`

reference 生命周期：

- **首圈完成立即建立 reference**：`completedLaps` 由空变为 size = 1 时，用该唯一完成圈作 reference（`buildReferenceLapIndex(completedLaps[0])`）；下一帧 derive 即可派生相对该 reference 的 delta
- **PB 刷新**：当某新完成圈的 `durationMillis < reference.lapDurationMs` 时，重建 reference 用该新 best 圈
- 重建 reference 时 atomic update：`reference = newRef, prevMatchedIdx = -1, staleFrameCount = 0`，**但 `prevDeltaMs` 保留**（让 stale 体验维持上一帧数字而非空白）
- active lap 重置（用户 abort / session 重启）→ `prevMatchedIdx = -1, staleFrameCount = 0`，prevDeltaMs 保留

#### Scenario: 首圈完成立即建 reference

- **WHEN** session 首圈完成 + completedLaps.size 由 0 变 1
- **THEN** _realtimeDeltaState.reference 用该唯一完成圈建立
- **AND** prevMatchedIdx = -1
- **AND** 下一帧 GPS data 即可派生 delta（不再 muted）

#### Scenario: PB 刷新触发 reference 重建

- **WHEN** 当前 reference.lapDurationMs = 90000 + 新完成圈 durationMillis = 88000
- **THEN** _realtimeDeltaState.reference 被重建为 88s 圈的 ReferenceLapIndex
- **AND** prevMatchedIdx 被 reset 为 -1
- **AND** prevDeltaMs 保留（不清空）

#### Scenario: 新完成圈不是 PB 不重建 reference

- **WHEN** 当前 reference.lapDurationMs = 88000 + 新完成圈 durationMillis = 92000（更慢）
- **THEN** reference 保持不变
- **AND** prevMatchedIdx / staleFrameCount 不被强制重置

#### Scenario: ViewModel 单帧只调一次 projectDelta

- **WHEN** 单次 GPS data 流入触发 ViewModel 处理
- **THEN** `projectDelta` 在该帧的 ViewModel 处理路径中调用次数 = 1（不是 0、不是 2）

#### Scenario: stale 5 帧门精确

- **WHEN** 连续 4 帧 projectDelta 失败（staleFrameCount 累计到 4，每帧 outIsStale = false）+ 第 5 帧仍失败
- **THEN** 第 5 帧 staleFrameCount 累到 5，outIsStale = true
- **AND** 下游 LapLiveState.deltaIsStale = true

#### Scenario: 单帧成功重置 stale 计数

- **WHEN** 累计 4 帧失败（staleFrameCount = 4）+ 第 5 帧成功
- **THEN** staleFrameCount 被 reset 为 0
- **AND** outIsStale = false

### Requirement: LapLiveScreen DELTA tile 渲染 stale 状态

`LapLiveScreen.Lap2x2Dashboard` 的 DELTA tile MUST 按 `state.deltaIsStale` 分支渲染：

- `deltaToBestMs == null` → value = `--:--.---`（已存在），accentColor = TextMuted
- `deltaIsStale == true` → value = formatDelta(deltaToBestMs)（保留上一帧数字），accentColor = TextMuted（**不**用 Green/Red）
- `deltaIsStale == false` 且 deltaToBestMs != null → value = formatDelta(...)，accentColor = `< 0 ? Green : > 0 ? Red : TextPrimary`

#### Scenario: stale 时字色降 muted

- **WHEN** state.deltaToBestMs = -500 + state.deltaIsStale = true
- **THEN** DELTA tile accentColor = TrackTechColors.TextMuted
- **AND** value 仍显示 -0.500

#### Scenario: 正常 negative delta 显示绿色

- **WHEN** state.deltaToBestMs = -500 + state.deltaIsStale = false
- **THEN** DELTA tile accentColor = TrackTechColors.Green

#### Scenario: 正常 positive delta 显示红色

- **WHEN** state.deltaToBestMs = +500 + state.deltaIsStale = false
- **THEN** DELTA tile accentColor = TrackTechColors.Red

### Requirement: 时钟域纯净化（仅约束 delta 计算路径，不动 CURRENT tile UI 时间外推）

本 round 修改后**用于 delta 计算的 elapsedMs** MUST 从 GPS sample timestampMillis 域派生，不混 wall clock。该约束作用于 ViewModel 内 projectDelta 调用前的 `currentLapElapsedMs` 计算路径。

**显式不约束**（保持 baseline 平滑显示行为）：

- `currentLapTimerMs`（CURRENT tile UI 显示值）继续使用 baseline 的 `currentDisplayTimeMs - lastAcceptedCrossing.timestampMillis` 公式，由 ViewModel 通过 ticker / `SystemClock.elapsedRealtime()` 在 GPS 帧之间外推。这是为了避免 5Hz replay 下 CURRENT tile 200ms 一跳的低频跳动问题（baseline 已修复，本 round 不破坏）
- 这意味着 `currentLapTimerMs` 与 `delta` 内部用的 `currentLapElapsedMs` 是**两个不同语义的时间值**：前者用于平滑 UI 显示，后者用于精确秒差计算。两者不应混用

#### Scenario: ViewModel delta 计算路径只用 GPS sample ts

- **WHEN** 在 `TestSessionViewModel.kt` 中查找 projectDelta 调用前的 currentLapElapsedMs 派生
- **THEN** 仅出现 `gpsData.timestamp - lastAcceptedCrossing.timestampMillis`（或等价 GPS sample ts 减法）
- **AND** **不**出现 `System.currentTimeMillis()` / `SystemClock.elapsedRealtime()` 参与该 elapsedMs 计算

#### Scenario: CURRENT tile timer 仍由 ticker 平滑外推（baseline 行为保留）

- **WHEN** 在 `LapLiveStateDeriver.kt` 中查找 currentLapTimerMs 派生
- **THEN** 仍使用入参 `currentDisplayTimeMs`（wall clock / elapsedRealtime 来源）减 crossing.timestampMillis
- **AND** 该入参 / 调用方式与本 round 之前 baseline 一致（ViewModel ticker 驱动）

### Requirement: 算法路径在受控参数下 5Hz 性能可忽略

projectDelta 单次调用（forwardWindowFrames = 200，reference.size = 2250）在 JVM 单线程基准下 MUST < 50µs（含投影 + segment 选择 + 时间插值）。

#### Scenario: 性能基准（informational，不阻塞 CI）

- **WHEN** 单测中跑 1000 次 projectDelta 调用 + 2250 点 reference
- **THEN** 总耗时 < 50ms（平均每次 < 50µs）
- **AND** 该测试可标 @Ignore 或仅作 manual run（不进 CI 阻塞）

### Requirement: V1 dead code 不在本 round 验收范围

V1 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/` 下的 dead code 屏幕（`TestExecutionScreen.kt` / `LapDebugExecutionScreen.kt` 等，已经因 MainActivity 切到 V2 TrackTechAppShell 失去引用）MUST NOT 在本 round 修改。

#### Scenario: V1 屏幕 diff 为空

- **WHEN** 本 round 全部 commit 完成后查 git diff
- **THEN** `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestExecutionScreen.kt` 等 V1 文件 diff 为空

