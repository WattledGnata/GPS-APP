## Context

### 当前状态

**A22 待消除的代码（UI 层）**：

- `LapDebugExecutionScreen.kt:197-237` `rememberStartFinishTimingCardState`（**名叫 remember 但不是 `@Composable`**，每次 recomposition 全量执行）
- `:239-255` `calculateDistanceSince(samples, crossingTimestampMillis)` —— `samples.filter` + 全量 `zipWithNext` haversine
- `:257-275` private `haversineDistanceMeters` —— 三角函数实现

每次 recomposition 时序：line 213 拿 latest accepted crossing → line 232 `calculateDistanceSince(samples.orEmpty(), latestAcceptedCrossing.timestampMillis)` → 遍历 samples 求和 → 转 label。25Hz × 7500 sample/圈，约每秒 187,500 次 haversine。

**engine 当前结构**（`LapTimingEngine.kt:51-303`）：

- `fun processSample(session: LapSession, track: Track, previousSample: GpsSample, currentSample: GpsSample): LapSession`（**4 参数，`previousSample` 是显式入参**）
  - line 61-63：`session.status` 白名单守卫（A19）
  - line 65-74：`if (currentSample.ts < previousSample.ts) return session`（A21 ts 回跳早退）
  - line 76：`updatedSamples = session.samples + currentSample`
  - line 77-86：startFinishDetector + verbose log
  - line 87-96：accepted 进 `handleStartFinishCrossing(...)`
  - line 98：`val targetGate = expectedGate(...) ?: return session.copy(samples = updatedSamples)`（**no target gate** 路径）
  - line 100-107：进 `handleSectorCrossing(...)`
- `handleStartFinishCrossing` line 110-206
  - line 134-148：activeLap == null（**首次开圈**）→ new `ActiveLap(...)`
  - line 150-205：activeLap != null（**闭圈**）→ 构造 `LapRecord` + `session.copy(activeLap = new ActiveLap(nextLapIndex))`
- `handleSectorCrossing` line 225-303
  - line 233：`activeLap == null` 时 `session.copy(samples = updatedSamples)`（已被 detector 路径排除，理论不会触发）
  - line 287-289：**期待门 rejected** → `session.copy(samples, crossingEvents)`
  - line 292-303：**期待门 accepted** → `session.copy(activeLap = activeLap.copy(passedGateIds, sectorEntries))`

**ActiveLap 结构**（`ActiveLap.kt:15-21`）：5 字段 `lapIndex / startedAtMillis / passedGateIds / sectorEntries / sampleStartIndex`。

### 约束

- `LapRecord` schema 不动（A51 / A56 规划项负责）
- 不引入跨经度 helper（A44 `wrappedDeltaLon` 已在 `core/domain` 但本 round 不跨层消费）
- 不做 composable 微优化（不引入 `derivedStateOf`）
- 不持久化（A56 边界）
- `processSample` 返回签名保持 `LapSession`

## Goals / Non-Goals

**Goals**：

- engine 是 **唯一 producer**：`ActiveLap.distanceMetersSinceStart` 只能由 `LapTimingEngine.processSample` 写入；UI 只读不自算
- **5 类返回路径**全部正确携带 `activeLapWithDistance`，不留任何"距离停滞"的合法采样路径
- `haversineDistanceMeters` 从 UI 层迁出，可被 engine 复用且**可单元测试**
- UI 层 `calculateDistanceSince` + private `haversineDistanceMeters` **完全删除**（机器 grep 可核销）
- 7500 samples 性能 smoke 防波动设计，能在 CI / 本地两种环境稳定通过

**Non-Goals**：

- 不改 `LapRecord` schema / 不做 A51 / A35
- 不引入 composable 缓存
- 不引入 `wrappedDeltaLon` / 跨经度正确距离
- 不固化长期 GPS 点阵持久化（A56 边界）
- 不统一 engine 判圈几何 vs UI 距离累积口径

## Decisions

### D1 · `haversineDistanceMeters` 落位：`feature/test/.../usecase/GeoMath.kt`，`internal` 可见性

**决策**：在 `feature/test/src/main/java/com/blazepush/feature/test/usecase/GeoMath.kt` 新建 file-level 公共工具：

```kotlin
package com.blazepush.feature.test.usecase

internal fun haversineDistanceMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDelta = Math.toRadians(endLatitude - startLatitude)
    val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
    val startLatitudeRadians = Math.toRadians(startLatitude)
    val endLatitudeRadians = Math.toRadians(endLatitude)
    val a = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
        kotlin.math.cos(startLatitudeRadians) * kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDelta / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusMeters * c
}
```

**Rationale**：

- 放 `feature/test/usecase/`：`LapTimingEngine` 同模块同包，import 路径短；不污染 `core/domain`（domain 暂无几何工具的统一栖息地）
- `internal`：仅供 `feature/test` 模块内 engine + UI 复用（UI 删除后实际只 engine 一个消费方），不暴露给其他模块
- 实现体直接搬 UI 层原代码（`LapDebugExecutionScreen.kt:257-275`），数学公式不变；删除 UI 私有版本

**Alternatives 考虑**：

- (a) `core/domain/usecase/GeoMath.kt`：拒收。当前 `core/domain` 还没有几何工具栖息处；A44 `wrappedDeltaLon` 已落 `core/domain/usecase/GpsDataFilter` 内部 file-private，本 round 不打开"domain 公共几何工具栈"的口子（属于 future round 的统一时机决策）
- (b) `LapTimingEngine` companion object：拒收。函数与 engine 状态无关，放 companion 隐式提示"只 engine 用"，但 UI 层重构为读字段后实际就是只 engine 用——OK 也行，但 file-level top-level 更直观

### D2 · `activeLapWithDistance` 在 `processSample` 顶部集中构造，分支统一消费

**决策**：在 `processSample` 主函数内、`updatedSamples = session.samples + currentSample` 之后、所有 detector 调用之前，**集中构造一次** `activeLapWithDistance`，作为后续所有保留 active lap 的返回路径的**唯一替换源**：

```kotlin
// processSample 现有签名 MUST 不变（A19/A21 守卫已建立在该签名上）：
fun processSample(
    session: LapSession,
    track: Track,
    previousSample: GpsSample,
    currentSample: GpsSample,
): LapSession {
    // line 61-63 现有 status 白名单守卫（A19）保持不动
    // line 65-74 现有 ts 回跳早退（A21，路径 (a)）保持不动 —— `return session`，不入 samples 不累距

    val updatedSamples = session.samples + currentSample

    // A22：相邻 samples 流的 haversine 增量。
    // 增量来源 MUST 是 session.samples.lastOrNull()（**samples 流口径**），
    // 而**不是** processSample 的 previousSample 参数（虽然此处通常指向同一
    // GpsSample，但语义来源应明示从 samples 流走，与 UI 旧 samples.zipWithNext()
    // 同源；future 若 previousSample 因 ts 守卫等改变，distance 仍跟 samples 流）。
    val activeLapWithDistance: ActiveLap? = session.activeLap?.let { current ->
        val prev = session.samples.lastOrNull()
        if (prev != null) {
            current.copy(
                distanceMetersSinceStart = current.distanceMetersSinceStart +
                    haversineDistanceMeters(prev.latitude, prev.longitude, currentSample.latitude, currentSample.longitude)
            )
        } else {
            current  // 理论不发生：activeLap 存在 → 开圈帧已入 samples（sampleStartIndex 守卫）
        }
    }

    val startFinishDetection = detector.detect(...)  // line 77 现有逻辑
    if (startFinishDetection.accepted) {
        // 路径 (b)/(c)：handleStartFinishCrossing 内部新 ActiveLap 时显式 distanceMetersSinceStart=0.0
        // closing active lap 不显式累入闭圈帧（见 D3）
        return handleStartFinishCrossing(
            session = session,
            track = track,
            updatedSamples = updatedSamples,
            previousSample = previousSample,
            currentSample = currentSample,
            detection = startFinishDetection,
        )
    }

    val targetGate = expectedGate(track, session.nextExpectedGateIndex)
        ?: return session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)  // 路径 (d)

    return handleSectorCrossing(
        session = session,
        track = track,
        previousSample = previousSample,
        currentSample = currentSample,
        updatedSamples = updatedSamples,
        targetGate = targetGate,
        activeLapWithDistance = activeLapWithDistance,  // **新增传参** —— 见 D3 (e)/(f)
    )
}
```

**handleSectorCrossing 签名 MUST 加 `activeLapWithDistance: ActiveLap?` 参数**（**P1-2 修补**）：

```kotlin
private fun handleSectorCrossing(
    session: LapSession,
    track: Track,
    previousSample: GpsSample,
    currentSample: GpsSample,
    updatedSamples: List<GpsSample>,
    targetGate: TimingGate,
    activeLapWithDistance: ActiveLap?,  // **新增** —— 由 processSample 集中构造后传入
): LapSession {
    val activeLap = session.activeLap ?: return session.copy(samples = updatedSamples)
    // ... detector 逻辑 line 234-285 保持不动 ...

    if (!expectedGateDetection.accepted) {
        // 路径 (e) sector rejected：携带累距
        return session.copy(samples = updatedSamples, crossingEvents = updatedEvents,
            activeLap = activeLapWithDistance)
    }

    // 路径 (f) sector accepted：从 activeLapWithDistance 派生（不是 session.activeLap.copy）
    return session.copy(
        samples = updatedSamples,
        nextExpectedGateIndex = session.nextExpectedGateIndex + 1,
        crossingEvents = updatedEvents,
        activeLap = activeLapWithDistance!!.copy(  // !! 安全：line 233 `session.activeLap ?: return` 已守卫
            passedGateIds = activeLap.passedGateIds + targetGate.id,
            sectorEntries = activeLap.sectorEntries + SectorEntry(...),
        ),
    )
}
```

**handleStartFinishCrossing 签名 MUST 不变**（路径 (b)/(c) 都是新 ActiveLap = 0.0，无需 activeLapWithDistance；现有 4 参数 `session/track/updatedSamples/previousSample/currentSample/detection` 保持）。

**Rationale**：

- **单点构造**：避免在 6 类返回路径里各自重复 haversine 计算，O(1) 性能 + 单点测试
- **`session.samples.lastOrNull()` 而非 `previousSample` 参数**：本质相同 reference（current = `session.samples.lastOrNull() before append`），但语义上**距离来源是 samples 流**（与 UI 旧 `samples.zipWithNext()` 同源），与 detector 用的"上一帧"概念分开。Future 若 `previousSample` 来源因 ts 回跳守卫等改变，distance 仍跟 samples 流走
- **保留 `null` 语义**：active lap 不存在时根本不构造 → 后续路径 (a)/(d-no-active) 自然不消费

**Alternatives 考虑**：

- (a) 在每条 copy 路径分别加 `activeLap = session.activeLap.copy(distanceMetersSinceStart = ...)`：拒收。6 处重复代码，必出现某条路径漏改的 bug（即评审方 Finding 2 的核心担忧）
- (b) 把累距挪到 `ActiveLap.append(currentSample)` 实例方法：拒收。`ActiveLap` 是 immutable data class（`fix-lap-timing-closure-and-precision-contract` R2/R3 已确立），不应附加副作用 API

### D3 · 5 类返回路径的具体携带方式

继承 D2 的 `activeLapWithDistance`，每条路径如下：

| ID | 当前位置 | 改造前 | 改造后 |
|---|---|---|---|
| **(a) ts 回跳早退** | `processSample` line 73 | `return session` | **不变**（current 不入 samples，距离不动） |
| **(b) 首次开圈** | `handleStartFinishCrossing` line 142-147 `ActiveLap(lapIndex=1, ...)` | 4 字段 | 构造时显式 `distanceMetersSinceStart = 0.0`（第 6 字段，default 0.0 故无需写出但为可读性建议写出） |
| **(c) 闭圈** | `handleStartFinishCrossing` line 150-205 | line 192-205 `session.copy(activeLap = ActiveLap(nextLapIndex, ...))` | 同 (b)：新 lap `distanceMetersSinceStart = 0.0`。**不需要**对 closing active lap 累入：closing active lap 在 LapRecord 构造时不读 distance 字段（`LapRecord` 不存），且立即被 new ActiveLap 替换；**但**为语义一致性，可选地在闭圈前对 closing active lap 累入闭圈帧（影响仅是 closing snapshot 的瞬时值，无持久化效应）—— **决策：不显式累入闭圈帧**（避免 closing lap 的 distance 值在 active lap 替换前被任何 observer 短暂可见而引发歧义；contract 上 active lap 已结束）|
| **(d) no target gate** | `processSample` line 98 | `session.copy(samples = updatedSamples)` | `session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)` |
| **(e) sector rejected** | `handleSectorCrossing` line 287-289 | `session.copy(samples, crossingEvents)` | `session.copy(samples, crossingEvents, activeLap = activeLapWithDistance)` |
| **(f) sector accepted** | `handleSectorCrossing` line 292-303 | `activeLap = activeLap.copy(passedGateIds, sectorEntries)` | `activeLap = activeLapWithDistance!!.copy(passedGateIds = ..., sectorEntries = ...)` —— **从 `activeLapWithDistance` 派生**而非 `session.activeLap.copy`，避免覆盖累距 |

**关键 (f) 细节**：现有 `activeLap = activeLap.copy(...)` 用 `session.activeLap`（line 233 提取），改后必须改用 `activeLapWithDistance!!.copy(...)`。`!!` 安全：进入 sector accepted 分支前已通过 line 233 `session.activeLap ?: return session.copy(...)` 守卫，`session.activeLap != null` 保证 `activeLapWithDistance != null`。

**关键 (c) 细节**：`handleStartFinishCrossing` 内部还需要把 `processSample` 顶部构造的 `activeLapWithDistance` 传进来吗？看现有签名（line 110-117）只传 `previousSample / currentSample / updatedSamples / detection`。

**决策**：`handleStartFinishCrossing` 不接收 `activeLapWithDistance`：

- **首次开圈**（activeLap == null）：`activeLapWithDistance == null`，但闭圈分支不进，新 ActiveLap 直接 `distanceMetersSinceStart = 0.0`
- **闭圈** 路径：closing active lap 的累距状态被丢弃（new ActiveLap 重置 0.0），无需传

为了精确控制：**修改 `handleStartFinishCrossing` 签名加 `activeLapWithDistance: ActiveLap?` 参数仅供未来扩展**——这一步**本 round 不做**（避免改 helper 签名扩散），等到真有 closing snapshot 消费方再加。

### D4 · UI 层最终形态

**`rememberStartFinishTimingCardState` 改造**（`LapDebugExecutionScreen.kt:197-237`）：

```kotlin
internal fun rememberStartFinishTimingCardState(
    lapSession: LapSession?,
    isTimeSynced: Boolean,
): StartFinishTimingCardState {
    val acceptedStartFinishCrossings = lapSession
        ?.crossingEvents
        ?.filter { it.accepted && it.gateType == TimingGateType.StartFinish }
        .orEmpty()

    val hasActiveLap = lapSession?.activeLap != null
    val baselineStatusLabel = when {
        hasActiveLap -> "当前圈进行中"
        isTimeSynced -> "等待起点"
        else -> "等待协议时间同步"
    }

    val latestAcceptedCrossing = acceptedStartFinishCrossings.lastOrNull()
        ?: return StartFinishTimingCardState(
            lastLapElapsedLabel = "--",
            currentLapElapsedLabel = formatElapsedMillis(0L),
            currentLapDistanceLabel = formatDistanceMeters(0.0),
            lastStartFinishTimeLabel = "--",
            statusLabel = baselineStatusLabel,
        )

    val previousAcceptedCrossing = acceptedStartFinishCrossings.dropLast(1).lastOrNull()
    val latestSampleTimestampMillis = lapSession?.samples?.lastOrNull()?.timestampMillis
        ?: latestAcceptedCrossing.timestampMillis
    val currentLapElapsedMillis = latestSampleTimestampMillis - latestAcceptedCrossing.timestampMillis

    // A22：UI 只读 engine 字段，不再 calculateDistanceSince(samples, ...)
    val currentLapDistance = lapSession?.activeLap?.distanceMetersSinceStart ?: 0.0

    return StartFinishTimingCardState(
        lastLapElapsedLabel = previousAcceptedCrossing
            ?.let { formatElapsedMillis(latestAcceptedCrossing.timestampMillis - it.timestampMillis) }
            ?: "--",
        currentLapElapsedLabel = formatElapsedMillis(currentLapElapsedMillis),
        currentLapDistanceLabel = formatDistanceMeters(currentLapDistance),
        lastStartFinishTimeLabel = formatTimeOfDay(latestAcceptedCrossing.timestampMillis),
        statusLabel = baselineStatusLabel,
    )
}
```

**删除**：

- `LapDebugExecutionScreen.kt:239-255` `private fun calculateDistanceSince`
- `LapDebugExecutionScreen.kt:257-275` `private fun haversineDistanceMeters`
- 相关 import 若变孤立（`GpsSample` 若仅被这两个 fun 使用则 import 也清掉，tasks 阶段 grep 决定）

**注意**：`samples?.lastOrNull()?.timestampMillis` 用于 `currentLapElapsedMillis` 仍保留——这是单帧 ts 读取（O(1)），不是 O(N) 全量遍历，与 A22 性能目标无冲突。

### D5 · 7500 samples 性能 smoke 防波动设计

**P1-3 修补（2026-04-25 design review）**：阈值 **回到 proposal v2 已批准的 < 16ms**。design v1 漂移到 < 50ms 无实测数据支持，被驳回。本 round 信任三层防抖能压住 16ms；若 apply 阶段实测 flaky 再凭 v1/v2 实测对比申请新阈值（不在本 design 提前放宽）。

**威胁建模**：

| 抖动来源 | 缓解策略 |
|---|---|
| JVM JIT 预热（首次调用 100x 慢） | warm-up 调用 N=10 次后才 measure |
| GC 抖动 | 多次测量（M=5）取**中位数**（min 太乐观，mean 受 outlier 污染） |
| CI 机器负载差异 | 阈值 **< 16ms**（与 proposal 批准条件 (5) 对齐，60fps 标准） |
| `currentTimeMillis()` 精度仅 1ms 数量级 | 用 `System.nanoTime()` |
| 单次 `rememberStartFinishTimingCardState` 太短无法 measure | 内 loop K=10 次（取 average per call） |

**v1 vs v2 性能差距估算**（理论）：

- v1：7500 samples 全量 haversine，每次 4 trig + filter pass = 约 7500 × 5 ≈ 37,500 ops；JIT 优化后预估 5-30ms 量级（接近或超 16ms，正是 A22 攻击点本身）
- v2：单字段读 + lap session 中 acceptedStartFinishCrossings filter（事件数 ≪ samples，~50 量级）+ 几个 O(1) 字段读，预估 < 1ms

16ms 阈值在 v2 下**应留出 16x 安全间隙**（1ms vs 16ms），三层防抖足够压住 GC / CI 抖动。

**测试形态**：

```kotlin
// LapDebugExecutionScreenStateTest.kt（新建或追加）
@Test
fun rememberStartFinishTimingCardState_with7500Samples_completesUnder16msMedian() {
    val session = buildSessionWith7500SamplesAndDistanceMetersSinceStart()  // helper

    // warm-up：JIT 预热 10 次，丢弃测量
    repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }

    // measure: 5 次取中位数，每次内部 10x loop 取均值
    val measuredNsPerCall = (1..5).map {
        val start = System.nanoTime()
        repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }
        (System.nanoTime() - start) / 10
    }.sorted()
    val medianNs = measuredNsPerCall[2]
    val medianMs = medianNs / 1_000_000.0

    assertTrue(
        "7500 samples rememberStartFinishTimingCardState median ${medianMs}ms 应 < 16ms（60fps 帧预算）。" +
            "硬区分 v1：v1 全量 haversine 7500 × 4 trig + filter ≈ 37500 ops，预期 ≥ 5ms 接近或超阈值；" +
            "v2 单字段读 O(1)，预期 < 1ms",
        medianMs < 16.0,
    )
}
```

**Rationale**：

- **阈值 16ms（与 proposal v2 批准条件对齐）**：60fps 标准下用户感知的帧预算；评审方拒接受无实测放宽
- **三层防抖**：warm-up 10x + measure 5 次取中位 + 内 loop 10x average，能压住 JIT / GC / CI 单次抖动
- **若 apply 阶段实测 flaky**：本 round 不预防性放宽；等真实测数据出来对比 v1/v2 再申请，避免在无信息时决策

**Apply 阶段补救路径（若 16ms 实测不稳）**：

1. 在 LapTimingEngineTest / LapDebugExecutionScreenStateTest 同一 JVM 实测 v1 baseline（备份分支版本）vs v2，记录中位数
2. 若 v2 medianMs 持续 ~10ms+ 而 v1 ~5ms，证明阈值过紧；用实测数据起 mini change 申请放宽到 1.5x v2 实测中位
3. 若 v2 ~1ms 但 CI 偶发 20ms，说明环境抖动主导，可考虑增加 warm-up 次数 / 内 loop 次数（已是三层防抖，再加意义有限），或承认无法机器核销改回纯源码 grep 断言（删除 smoke）

但本 design 不在前置阶段写这些 fallback；信任三层防抖。

### D6 · ActiveLap 字段位置与默认值

**字段位置**：放在 `ActiveLap.kt` 现有 5 字段末尾，作为第 6 字段：

```kotlin
data class ActiveLap(
    val lapIndex: Int,
    val startedAtMillis: Long,
    val passedGateIds: List<String> = emptyList(),
    val sectorEntries: List<SectorEntry> = emptyList(),
    val sampleStartIndex: Int,
    val distanceMetersSinceStart: Double = 0.0,  // A22：本圈累计距离（米），engine 唯一 producer
)
```

**Rationale**：

- 末尾位置：现有 5 字段顺序保持，避免重排扰动 git blame
- `default 0.0`：构造方便（line 142-148 / 199-204 现有 4 字段构造可直接编译，不需在每个 ActiveLap(...) 处加 `distanceMetersSinceStart = 0.0`），但 design 仍**建议**在 engine 内 2 处构造点显式写出 `distanceMetersSinceStart = 0.0` 提升可读性（**tasks 实施时拍板**）

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| 闭圈路径 (c) 的 closing active lap distance 不累入闭圈帧，单调不减契约对该最后一帧不严格成立 | 接受 —— closing active lap 在 LapRecord 构造时不读 distance，立即被 new ActiveLap 替换；外部 observer 几乎无机会读到该瞬时值；contract 改写为"active lap 生命期内单调不减，闭圈瞬间该 lap 被替换不在范围内" |
| 7500 samples smoke 在 < 16ms 阈值下若 CI 极慢可能偶发失败 | D5 已加三层防抖（warm-up 10x + 中位数 5 次 + 内 loop 10x avg），理论 v2 ~1ms vs 阈值 16ms 留 16x 间隙应能压住；若 apply 期实测 flaky 走 D5 末尾的实测申请放宽路径，**不在 design 阶段无信息预先放宽** |
| 闭圈路径 (c)：`activeLapWithDistance` 已构造但 `handleStartFinishCrossing` 不接收该参数，closing active lap 的累距增量在 new ActiveLap 替换前从未被任何 observer 读到 | 接受 —— 这是 D3 (c) 决策的真实权衡：closing active lap 的瞬时 distance 不持久化（`LapRecord` 不存 distance 字段，A56 边界）、立即被 `ActiveLap(distanceMetersSinceStart=0.0)` 替换，把它从 helper 参数中移除避免任何 observer 读到模糊的"瞬时终值"；本 round 不为这个边界值 future-proof helper 签名（等真有消费方再加） |
| `internal fun haversineDistanceMeters` 跨文件复用需确认编译期可见性 | feature/test 是单一 module，`internal` = module-private 可被同 module 内 LapTimingEngine 自由调用 |

## Migration Plan

### 实施顺序（tasks 阶段细化）

1. **D6 字段段**：`ActiveLap.kt` 加字段（编译应继续通过，因 `default = 0.0`）
2. **D1 工具段**：新建 `GeoMath.kt` 含 `internal fun haversineDistanceMeters`
3. **D2 + D3 engine 段**：`processSample` 加 `activeLapWithDistance` 构造 + 5 类路径全部携带（一气做完，不分 commit）
4. **D4 UI 段**：`LapDebugExecutionScreen.kt` 改读 engine 字段 + 删除 2 个 private fun
5. **测试段**：6 条 path coverage scenarios + 1 条 smoke + UI state 单测
6. **回归段**：现有 `LapTimingEngineTest` / `LapDebugExecutionScreenStateTest`（若存在）应零回归

### Rollback 策略

单 commit 实施 → rollback = `git revert`。 如果只想退 UI 层而保留 engine 字段，技术上可行但不推荐（违反"engine 是唯一 producer / UI 只读"契约）。

## Open Questions

无未决。`tasks` 阶段需要 grep 确认的待核实项（不是设计决策）：

- `LapDebugExecutionScreenStateTest.kt` 是否已存在（搜：UI state 测试可能没单独文件）—— 不存在则新建
- `GpsSample` import 在 `LapDebugExecutionScreen.kt` 是否仅被即将删除的 2 个 private fun 使用 —— 是则一并清掉
