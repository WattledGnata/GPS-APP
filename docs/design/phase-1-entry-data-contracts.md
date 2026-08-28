# Phase 1 Entry · 数据契约 sketch（4 worktree 并行起步）

> 本文件由 `.git/info/exclude` 的 `*.md` 规则自动排除 → **不进远端 git**，仅本地有效。
>
> Phase 0 已闭合（chore commit `e2a42a1` / 2026-05-04）。Phase 1 启动 4 worktree 并行：
> 数据 (W1) + UI 组件 (W2) + 算法 (W3) + 独立修复 (W4)。
>
> 本 sketch 定义 4 round **共享的接口契约**——让 W2/W3 可以在 mock 数据上推进，无需等 W1 实施完成。
> W1 实施完成后，W2/W3 把 mock 替换为真实 API 调用即可。

---

## 1. 共享数据类（W1 owns，W2/W3 mock 可用）

### LapTelemetry（W1 新建于 `core/domain/.../model/`）

```kotlin
/**
 * 单圈完整 telemetry 切片，由 lap-data-readers round 引入。
 *
 * 用途：详情屏 chart cursor 拖动 + 多圈比较的统一数据载体。
 * 来源：repository.getLapTelemetry(sessionId, lapIndex) 从 binary + entity 重建。
 */
data class LapTelemetry(
    val sessionId: String,
    val lapIndex: Int,                    // 0-based
    val lapStartWallClock: Long,           // 真壁钟 ms（从 crossing event 取，A round + §8.3 已修对齐）
    val lapEndWallClock: Long,             // 真壁钟 ms
    val lapDurationMs: Long,               // == lapEnd - lapStart
    val samples: List<LapTelemetrySample>, // 按 absoluteTs 升序
    val sectorBoundaries: List<Long>,      // 各 sector 起点 absoluteTs（含起点 = lapStartWallClock，不含终点）
    val trackId: String?,                  // 来自 entity.trackId（C round 已加）
    val trackNameSnapshot: String?,        // 来自 entity.trackNameSnapshot
)

data class LapTelemetrySample(
    val absoluteTsMs: Long,        // 真壁钟 ms（A round + §8.3 修复后保证）
    val elapsedMsInLap: Long,      // == absoluteTsMs - lapStartWallClock
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,
    val accelerationG: Double?,    // optional：sample 间派生（W3 算法可填，W1 不强制）
)
```

### PerformanceTelemetry（W1 owns，与 SpeedCurve 真实数据契约对齐 / 合并 deferred memo #5）

```kotlin
/**
 * 0-100 / 100-0 完整 dataPoints 切片，由 lap-data-readers round 引入（合并 speed-curve-real-data-persistence memo）。
 *
 * 用途：PerformanceResultScreen SpeedCurve / GForceChart 真实数据；详情屏可选展示。
 * 来源：repository.getDataPointsForResult(testId) 从 PERFORMANCE_TEST binary 顺序读。
 */
data class PerformanceTelemetry(
    val testId: String,
    val testStartWallClock: Long,
    val testEndWallClock: Long,
    val samples: List<LapTelemetrySample>,  // 复用 LapTelemetrySample 类型，elapsedMsInLap 在此场景为 elapsedMsInTest
)
```

---

## 2. Repository API（W1 owns）

```kotlin
// core/data/.../repository/TelemetryRepository.kt 追加

/**
 * 读取指定 session 的指定圈数完整 telemetry。
 * 跨 binary samples + crossing events 派生 sectorBoundaries。
 * 圈数越界 / session 不存在 / binary 文件缺失 → 返回 null。
 */
suspend fun getLapTelemetry(sessionId: String, lapIndex: Int): LapTelemetry?

/**
 * 读取指定 PERFORMANCE_TEST 测试的完整 dataPoints。
 * 合并 deferred memo #5（speed-curve-real-data-persistence）。
 * 走 readPerformanceSamples 顺序读路径 + entity 元数据派生 wallClock 起止。
 */
suspend fun getDataPointsForResult(testId: String): PerformanceTelemetry?
```

**实施 caveat（W1 必读）**：
- `getLapTelemetry` 内部用 `readLapSamples(filePath, crossing[i].timestampMs, crossing[i+1].timestampMs)` 截单圈 —— 依赖 §8.3 (fix-lap-crossing-clock-hygiene) 已让 crossingTimestampMs 走 wallClock，本 round **必须 verify** crossing event entity 实际字段名 + 时钟域
- 单元测试 MUST 加：A `getLapTelemetry` 正常路径返回 N 圈 / B `getLapTelemetry` 越界返回 null / C `getDataPointsForResult` 返回 PERFORMANCE_TEST 全帧 / D crossing 越界 / E binary 文件缺失返回 null
- 与 §8.4 (M round) 同 pattern 加 grep gate 防回退

---

## 3. UI 组件库接口（W2 owns，基于 LapTelemetry / PerformanceTelemetry mock）

```kotlin
// feature/test/.../ui/components/SpeedTimeChart.kt

/**
 * 时间轴速度曲线（单圈或单测试）。x = elapsedMsInLap/Test，y = speedKmh。
 * cursor 联动通过 cursorAbsoluteTs 参数（外部状态）+ onCursorChange callback。
 */
@Composable
fun SpeedTimeChart(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,
    onCursorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun AccelTimeChart(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,
    onCursorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun SectorBar(
    sectorBoundaries: List<Long>,        // 单圈起点 + 各 sector 起点
    lapStartWallClock: Long,
    lapEndWallClock: Long,
    cursorAbsoluteTs: Long?,
    modifier: Modifier = Modifier,
)

@Composable
fun TrackPolylineMap(
    samples: List<LapTelemetrySample>,
    cursorAbsoluteTs: Long?,            // 高亮当前位置点
    modifier: Modifier = Modifier,
)
```

**W2 开发期 mock 数据生成器**（建议放 `feature/test/src/test/.../ui/components/MockTelemetry.kt`，仅测试可用）：

```kotlin
fun mockSingleLap(n: Int = 100, lapDurationMs: Long = 60_000): LapTelemetry { /* 合成正弦波速度 + 圆周轨迹 */ }
fun mockMultiLap(n: Int = 3): List<LapTelemetry> { /* 3 圈不同 pace */ }
```

**W2 不依赖 W1 实施完成**——chart 组件库消费 `List<LapTelemetrySample>` 类型，类型一旦定义即可独立开发 + Compose preview。

---

## 4. 多圈对齐算法（W3 owns，pure function）

```kotlin
// core/domain/.../usecase/LapAlignment.kt 新建

/**
 * 多圈按 distance 重采样对齐——让多圈在"同一空间位置"的 cursor 显示
 * 三圈各自的 elapsed time / speed / lat-lon。
 *
 * 输入：多圈 telemetry，参考圈 index（如 best lap），目标 distance 步长 m
 * 输出：LapAlignmentResult data class（含 samplesPerLap + distanceStepMeters + refTotalDistMeters + gridSize + referenceLapIndex）
 *       + gridIndexFor(distanceMeters) / distanceAtGridIndex(gridIndex) 双向 helper
 *
 * Pure function——不依赖 Android Context / Room / Repository。
 * 测试用 mock LapTelemetry 即可，不依赖 W1 实施。
 */
object LapAlignment {
    fun alignByDistance(
        laps: List<LapTelemetry>,
        referenceLapIndex: Int,
        distanceStepMeters: Double = 5.0,
    ): LapAlignmentResult
}

data class LapAlignmentResult(
    val samplesPerLap: List<List<LapTelemetrySample>>,
    val distanceStepMeters: Double,
    val refTotalDistMeters: Double,
    val gridSize: Int,
    val referenceLapIndex: Int,
) {
    fun gridIndexFor(distanceMeters: Double): Int  // EMPTY 返回 -1；否则 clamp 到 [0, gridSize-1]
    fun distanceAtGridIndex(gridIndex: Int): Double // == gridIndex * distanceStepMeters
    companion object { val EMPTY = ... }
}
```

**W3 单测覆盖**：A 三圈不同 pace / B 单圈输入 / C 距离过短 / D 参考圈越界 / E 累计距离含重复值 / F 比较圈样本退化 fallback。

---

## 5. B round wire-laptime-to-gps-filter（W4 owns，独立修复）

不消费上述任何接口，scope 独立：

- 改 `feature/test/.../viewmodel/TestSessionViewModel.kt:bridgeGpsToLapTiming` 内部把入参从 `gpsData` 切到 `gpsDataFilter.process(gpsData)` 输出
- 接通后 `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 阈值降回 1
- 详细设计 `docs/design/laptime-gps-filter-integration-deferred.md` 已 9 章完整

与 W1/W2/W3 文件级 0 交叉（W1 改 core/data + core/domain；W2 新建 ui/components；W3 新建 core/domain/usecase；W4 改 ViewModel 内部函数体）。

---

## 6. 合回顺序

| Tier | Round | 何时合 |
|---|---|---|
| Tier 1 (并行) | W1 lap-data-readers / W2 chart-and-map-components / W3 lap-comparison-time-align / W4 wire-laptime-to-gps-filter | 各自闭环即合（顺序由 user 拍板，避免 push 时 kt-format-checker 顺序冲突）|
| Tier 2 | lap-detail-screen-with-cursor（消费 W1 + W2）| Tier 1 W1 + W2 都合后 |
| Tier 2 | lap-comparison-screen-with-cursor（消费 W1 + W2 + W3）| Tier 1 W1 + W2 + W3 都合后 |

Tier 2 依赖 Tier 1 的真实 API 实施，不能在 mock 上跑（属"集成层"）。

---

## 7. 协同约束

- **类型契约稳定性**：W1 round 的 `LapTelemetry` / `LapTelemetrySample` / `PerformanceTelemetry` / Repository API 签名一旦定义就**不再改动**——W2/W3 mock 基于此契约。如果 W1 实施期发现签名需调整 → user 拍板 + 同步通知 W2/W3 session
- **共享文件**：W1 改 `core/data/.../repository/TelemetryRepository.kt`（追加方法）；W2 / W3 不改 repository 任何函数 → rebase 友好
- **看板 §6 共享文件登记**：W1 启动时登记 TelemetryRepository.kt 的"追加方法"+ W4 登记 TestSessionViewModel.kt 的"bridgeGpsToLapTiming 内部修改"

---

**用法**：4 个 Claude Code session 启动时，第一句给它读本 sketch + 自己 round 的 deferred memo / proposal 起草路径，然后跑 `/opsx:ff <round-name>`。
