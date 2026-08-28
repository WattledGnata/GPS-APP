# 圈速 + GPS 接入链路对抗式复审（2026-04-22）

> 分支：`feature_ctg_20260405_laptime_mainline`
>
> 审查范围：`docs/superpowers/reviews/2026-04-21-lap-timing-review.md` 和
> `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md` 已覆盖的代码模块。
>
> 审查立场：假装不知道项目背景与设计意图，把两份 review 的结论当作假设，主动去源码里找反例，目标是：
>
> 1. 验证 review 声称"已锁定"的不变量是否真的无缝；
> 2. 找 review 漏掉或误判的深层问题；
> 3. 找声称"修过"但实际没修干净的残留。
>
> 每条问题都附：**证据（文件:行号）/ 复现路径 / 影响 / 修复方向 / 测试建议**。
>
> **补遗（用户实测反馈）**：2026-04-22 用户实测报告"圈速时间偏长"，经本报告排查确认为**发射端 simulator + 接收端 parser 双端时间戳污染**导致，详见本文第 **八** 节。该问题提级为**最高优先级 P0**。

---

## 零、方法论与记法

- 严重度标签：
  - 🔴 **P0** — 正确性或稳定性严重问题，会直接导致功能失败、资源泄漏或主线程阻塞，必须在合流前修。
  - 🟠 **P1** — 逻辑/语义缺陷，在当前路径不致命，但在典型边界或未来扩展上必然显形。
  - 🟡 **P2** — 代码异味、死代码、测试断言松、UI 误导，逐条排期即可。
- 证据一律用 `文件:行号`，代码段保持原文缩进。
- 每条问题结尾用 `⇢ review 映射` 标注它与已有 review 的对照关系：
  - `review 已提` — 事实成立，本报告对其深化；
  - `review 漏` — review 完全未提；
  - `review 错` — review 叙述与代码事实不符。

---

## 一、圈速模块（Lap Timing）

### 🔴 1.1 `directionalSpeedMps` 存在量纲错位，`minDirectionalSpeedMps` 启用即全拒 ⇢ review 漏

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt:41-65`

```kotlin
val movementX = current.latitude - previous.latitude    // 单位：度
val movementY = current.longitude - previous.longitude  // 单位：度
val directionScore =
    (movementX * gate.passDirection.x) + (movementY * gate.passDirection.y)

if (directionScore <= 0.0) { /* WrongDirection */ }

val dtSeconds = (current.timestampMillis - previous.timestampMillis) / 1000.0
val directionalSpeedMps =
    if (dtSeconds > 0.0) directionScore / dtSeconds
    else Double.POSITIVE_INFINITY

if (gate.minDirectionalSpeedMps != null
    && directionalSpeedMps < gate.minDirectionalSpeedMps) {
    /* TooSlow */
}
```

**证据补强**：`feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt:41,54,66`

```kotlin
passDirection = GeoVector(x = -0.0002602757878550089, y = -0.000023506987175358924)
// 幅值 ≈ 2.6e-4，数量级与 "GeoPoint 差向量（度）" 吻合，不是单位向量
```

**量纲推导**：

1. `movement` 单位 = 度（两个经纬度相减）。
2. `passDirection` 在 preset 里是"两点相减量级"（~2.6e-4），并未归一化，本身单位也是**度**。
3. 点积 `directionScore` = 度 × 度 = **度²**。
4. `directionalSpeedMps = directionScore / dtSeconds` 单位 = **度²/秒**。
5. 它却被用来与声称是 `m/s` 的 `minDirectionalSpeedMps` 直接比较（见 `TimingGate.kt:10`）。

**复现路径（未来启用后必触发）**：

1. 按记忆中的"TFIC 起终点约 120 km/h" 规则，给 `PresetTracks.kt` 的起终点填写 `minDirectionalSpeedMps = 13.9`（50 km/h ≈ 13.9 m/s，甚至保守一点 30 km/h ≈ 8.3 m/s 也同理）。
2. 实车数据：25 Hz、车速 120 km/h。
3. 典型 `movement.latitude ≈ 1.4e-5 度`，`movement.longitude ≈ 1.4e-5 度`。
4. `directionScore ≈ 1.4e-5 × 2.6e-4 ≈ 3.6e-9`。
5. `dtSeconds ≈ 0.04`。
6. `directionalSpeedMps ≈ 9e-8 度²/秒` —— **远小于 8.3**。
7. `detector.detect` 返回 `TooSlow`，**永远无法开圈 / 闭圈**。

**影响**：

- 当前 `minDirectionalSpeedMps = null`（见 PresetTracks.kt），下限路径被短路绕过，所以**生产现象"看起来正常"**。
- 一旦有人按 review 9.5 的建议把下限填进来，这一项回归**直接锁死整条链路**，且不会抛任何异常（静默 rejection）。
- 这是真正的"定时炸弹"——埋在 review 的 backlog 里。

**修复方向（三选一，按代价升序）**：

1. **改字段语义**（最小改动）：把 `minDirectionalSpeedMps` 重命名为 `minDirectionalProjectionDegPerSec`，并在 UI/配置层加一段换算公式说明。不推荐，语义混乱。
2. **改实现把单位对齐到 m/s**：在 detector 内部，把 `movement` 通过球面公式（或本地米坐标系）换算成米，`passDirection` 同步归一化为单位向量。
3. **detector 内部加"米坐标系预投影"**（推荐）：以 gate 线中心点为原点做本地 ENU 投影，`prev/current/gate` 全部换算到米，然后所有判定包括线段相交、方向点积、速度投影都在米空间内进行。同时顺便消除 review 9.4 提到的"欧氏相交跨纬度失真"。

**测试建议**：

- 新增 `GateCrossingDetectorTest.minDirectionalSpeedMps_enforcementMatchesMps`：
  - 构造 `gate.minDirectionalSpeedMps = 10.0`（m/s），用真实 TFIC 几何和 25Hz / 120 km/h 的位移量，断言 `detection.accepted == true`。
  - 再用 20 km/h ≈ 5.5 m/s 的位移，断言 `detection.reason == CrossingReason.TooSlow`。
- 当前这条测试存在即意味着修复到位，缺失则意味着量纲仍然错。

---

### 🔴 1.2 `FileLogger` 在 Main dispatcher 上 25 Hz 同步写盘 ⇢ review 漏

**证据（调度器）**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:112-123`

```kotlin
viewModelScope.launch {
    gpsDataViewModel.gpsData.collect { gpsData ->
        val filteredData = gpsDataFilter.process(gpsData)
        ...
        bridgeGpsToLapTiming(gpsData)
    }
}
```

`viewModelScope` 在没有显式指定 dispatcher 时默认使用 `Dispatchers.Main.immediate`（`androidx.lifecycle` 约定）。

**证据（写盘）**：`feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt:24-32`

```kotlin
fun d(tag: String, message: String) {
    val timestamp = dateFormat.format(Date())
    val logLine = "$timestamp D/$tag: $message\n"
    logFile?.let {
        FileWriter(it, true).use { writer ->
            writer.append(logLine)
        }
    }
}
```

- `FileWriter(..., true).use { ... }` 每次调用都 open → append → flush → close，完全同步。
- 没有异常捕获。

**证据（调用密度）**：

| 位置 | 频率 |
|---|---|
| `TestSessionViewModel.kt:310-313` bridgeGpsToLapTiming 入口日志 | 每样本 1 次 |
| `LapTimingEngine.kt:30-33` 主路径 startFinish detect 日志 | 每样本 1 次 |
| `LapTimingEngine.kt:164-167` sector detect 日志 | 有 activeLap 时每样本 1 次 |
| `TestSessionViewModel.kt:328-331` lapTimingResult 日志 | 非首样本每次 1 次 |

即使丢掉首样本，**每个 GPS 样本稳定触发 3~4 次 `FileLogger.d`**。25 Hz 下 = **每秒 75~100 次主线程同步文件 open+write+close**。

**影响**：

- 主线程 I/O 直接导致 Compose recomposition、触摸事件处理变慢。结合 1.9 的 UI 全量 haversine，长圈次下会有肉眼可见的掉帧。
- 无异常捕获：磁盘满 / FS 只读 / `filesDir` 删除（测试设备 recover）时，`FileWriter(...)` 抛 `IOException` 未捕获，会沿协程向上传播至 `gpsData.collect`，**整条 GPS→Lap 链路静默退出**。
- 文件增长：没有 rotation、没有大小上限。按 25 Hz × 3 行 × 假设 200 字节估算，1 小时 ≈ 54 MB。长时间跑会吃满 app filesDir。

**修复方向**：

1. 把 `FileLogger.d` 实现改成"单生产者 + 独立 coroutine + Channel"：业务线程只做 `channel.trySend(logLine)`，`Dispatchers.IO` 上一个常驻协程消费队列、批量 flush（例如累积 64 行或 200 ms flush 一次）。
2. 加文件大小上限 + rotation（例如 2 个文件轮换，各 5 MB）。
3. `FileWriter(...)` 外层套 `try { ... } catch (e: IOException) { Log.w(TAG, "logger write failed", e) }`，永远不把 I/O 异常传播给业务流。
4. 短期可操作的最小修改：把 `bridgeGpsToLapTiming` / engine 里"每帧都打"的日志改成**有条件打**（只在状态变化、accepted crossing、异常场景打）。生产只记关键事件，不记每帧原始数据。

**测试建议**：

- 加一个 JVM 单测：`FileLoggerTest.d_whenFileWriteThrows_doesNotPropagate` — 注入一个会抛 `IOException` 的 `FileWriter`，断言 `d()` 不再 throw。
- 性能回归（Robolectric 或真机）：`FileLoggerLoadTest.d_called1000TimesIn1s_finishesWithinBudget` — 确保批量 1000 次调用在 100 ms 内返回，强制引入异步/批量实现。

---

### 🔴 1.3 闭圈时 `crossingEvents` 切片依赖时间戳单调递增 ⇢ review 漏

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:73, 108`

```kotlin
val updatedEvents = session.crossingEvents + crossingEvent      // 一直 append
...
// 闭圈时切出属于本圈的事件：
crossingEvents = updatedEvents.dropWhile {
    it.timestampMillis < activeLap.startedAtMillis
}
```

**问题**：`dropWhile` 遇到**第一个** `ts >= activeLap.startedAtMillis` 的元素就停止检查，之后的元素**不再校验谓词**直接保留。这对"时间戳单调非降"的数组才正确。

**时间戳来源不可靠**：

- `RaceChronoParser.kt:257-261`：协议时间包未到或 `syncBits` 不匹配时，主包时间戳落回 `System.currentTimeMillis()`。
- 切换 sync（hour 边界、重连）瞬间，`protocolTimeReference` 被重写，下一帧的时间戳可能出现短暂回跳或相对跳跃。
- BLE notify 回调顺序由协议栈保证，但如果 `handleCharacteristicChange` 在两个 UUID 间交织（GPS main + time），+ parser 本身多线程访问`gpsDataTimestamps`（`synchronized`）隔离，**主包和时间包的到达顺序不保证"先 time 后 main"**。

**复现路径**：

1. 车辆 A 圈 → 穿起终点生成事件 E1 (ts=100_000)
2. 协议时间 syncBits 在第二圈换 hour → 下一帧 main 落回 `System.currentTimeMillis()` = 例如 `999_998`（刚好倒退 2 ms，但相对 E1 向前跳了几小时）。
3. 实际上更容易复现的是时钟**前跳**：协议时间刚对齐进入本圈，某一帧落回系统时钟（落后协议时钟几百 ms），被当作 `activeLap.startedAtMillis` 之前的事件。
4. `dropWhile` 看到一个 `ts < startedAtMillis` 的元素，但**它在数组中排在 `startedAtMillis` 之后**。dropWhile 在第一个 `ts >= startedAtMillis` 处已经停，后面即便出现 `ts < startedAtMillis` 也保留。
5. 结果：**上一圈残留事件泄漏进本圈 LapRecord**，或反之。

**影响**：

- `LapRecord.crossingEvents` 归属错乱 → Result 页展示的每圈事件数与实际不符。
- 如果后续用 `crossingEvents` 做分析（扇区质量、重试判定），分析结论错。
- 不会 crash，属于静默错误。

**修复方向**：

1. 把切片从 `dropWhile` 改为 `filter { it.timestampMillis >= activeLap.startedAtMillis }`（改 O(n)，数量级相同，但语义严格）。
2. 在 `processSample` 入口加严格的时间单调校验：如果 `currentSample.timestampMillis < session.samples.lastOrNull()?.timestampMillis ?: Long.MIN_VALUE`，直接丢弃当前样本并记录一条诊断日志，不推进 engine。
3. 更根源的修法：在 `TestSessionViewModel.bridgeGpsToLapTiming` 加时钟倒退守卫（见 1.14），让 engine 永远收到单调非降的 ts。

**测试建议**：

- `LapTimingEngineTest.processSample_outOfOrderCrossingEvents_doNotLeakAcrossLaps`：构造一个上圈 accepted → 某一中间事件 ts 人为回跳 → 下圈 accepted，断言闭出的 `LapRecord.crossingEvents` 严格只包含 `ts >= activeLap.startedAtMillis` 的事件。

---

### 🟠 1.4 `handleSectorCrossing` 多门同帧被穿时只处理一个 ⇢ review 漏（review 9.8 只提复杂度）

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:139-161`

```kotlin
val unexpectedGate = orderedSectorGates
    .asSequence()
    .filter { it.id != targetGate.id }
    .map { gate -> gate to detector.detect(previousSample, currentSample, gate) }
    .firstOrNull { (_, detection) -> detection.accepted }

if (unexpectedGate != null) {
    // 记 1 条 UnexpectedGateOrder，return
}

val detection = detector.detect(..., gate = targetGate)
// 只处理期待门
```

**语义缺陷**：

- 若一对 `(previous, current)` 同时跨过"非期待门 A"和"期待门 B"，代码只记录 A 的拒绝事件，然后 `return session.copy(...)`，**期待门 B 的穿线完全被吞掉**，既不进 `crossingEvents`，也不推进 `nextExpectedGateIndex`。
- 若同时跨过多个非期待门，只记第一个（`firstOrNull`），后续非期待门穿线也被吞掉。

**复现路径**：

- TFIC 当前只有 2 个 sector 门，相互距离远，25 Hz 下一帧位移 ~1.4 m，两门不会同帧被跨。**当前赛道不触发**。
- 未来场景：如果 replay 导入高速位移（>几十米/帧），或扩展到多门紧凑的赛道（u 型弯、短直道连续 sector），一帧跨多门的概率不可忽略。

**影响**：

- 低频但静默：某一圈的某一 sector 时间会缺失，闭圈时根据 `activeLap.sectorEntries.size != track.sectorGates.size` 被打 `IncompleteSectors`，表现为"圈时正常，但 sector 缺一"。开发者很容易把它误归为 gate 几何问题而不是 engine bug。

**修复方向**：

- `handleSectorCrossing` 改为：一次遍历所有 sector 门 detect，把所有 accepted 事件收集成列表；然后按"期待门 > 非期待门"顺序依次处理：
  - 期待门 accepted 则推进 `nextExpectedGateIndex`，追加 `SectorEntry`；
  - 非期待门 accepted 则记 `UnexpectedGateOrder`；
- 所有事件都写入 `crossingEvents`，不吞任何一个。

**测试建议**：

- `LapTimingEngineTest.processSample_multiGateCrossingInSingleStep_recordsAllEvents`：构造一个位移跨过"期待门 s1 + 非期待门 s2"的样本对，断言 `crossingEvents` 里既有 `s1 accepted` 也有 `s2 UnexpectedGateOrder`，且 `nextExpectedGateIndex` 已推进。

---

### 🟠 1.5 `LapTimingEngine.processSample` 不检查 `LapSessionStatus` ⇢ review 漏

**证据**：`LapTimingEngine.kt:22-54`

```kotlin
fun processSample(
    session: LapSession,
    track: Track,
    previousSample: GpsSample,
    currentSample: GpsSample
): LapSession {
    val updatedSamples = session.samples + currentSample
    // 没有 session.status 检查，直接开跑
    ...
}
```

- `status` 可以是 `Idle / Ready / Recording / Finished / Cancelled`（`LapSessionStatus.kt`）。
- engine 对 `Finished` / `Cancelled` 完全无感知，仍会 append sample、调 detector、改 session。

**当前生产不爆炸的原因**：`TestSessionViewModel.bridgeGpsToLapTiming` 用 `isLapRecording` 布尔值守卫（`TestSessionViewModel.kt:303`），`stopLapDebugSession` 同步把它置 false（155-159）。所以生产链路里 engine 不会被喂已结束 session。

**未来风险**：

- 测试或集成代码直接调 `engine.processSample(session, ...)` 时，`Finished` 状态不被识别，`samples`/`crossingEvents`/`completedLaps` 无上限增长 → 内存占用随时间线性增加。
- 如果后续把 `isLapRecording` 删除、改用单一的 `status` 字段驱动（状态机正规化），这个缺口立刻变成生产 bug。

**修复方向**：

- `processSample` 入口加守卫：
  ```kotlin
  if (session.status in setOf(LapSessionStatus.Finished, LapSessionStatus.Cancelled)) {
      return session
  }
  ```
- 同时检查 `Idle`：Idle 意味着 `selectLapDebugMode` 还没被调、`trackId` 未设，engine 不应该接受样本。

**测试建议**：

- `LapTimingEngineTest.processSample_onFinishedSession_returnsUnchanged`：构造 `status = Finished` 的 session，断言 `processSample` 返回的 `session` 与输入 `equals`（data class 结构等价）。

---

### 🟠 1.6 首样本丢帧导致 `sampleStartIndex` 与实际轨迹起点错位 ⇢ review 9.7 已提，本节深化

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:307-319`

```kotlin
val currentSample = gpsData.toLapGpsSample()
val previousSample = lastLapGpsSample
...
lastLapGpsSample = currentSample
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession
    return
}

val updatedSession = lapTimingEngine.processSample(
    session = currentSession,
    track = track,
    previousSample = previousSample,
    currentSample = currentSample
)
```

**与 engine 的交互**：

- 首样本：`lastLapGpsSample = currentSample`，但 `currentSample` **不入 `session.samples`**（直接 return）。
- 第二条样本：`previousSample = 首样本（未入 list）`，`currentSample = 第二条样本` → engine 内 `updatedSamples = session.samples + currentSample = [第二条样本]`。

**问题**：

- `updatedSamples.lastIndex = 0` 指向的是"第二条样本"；但 engine 内计算 `detector.detect(previousSample, currentSample, ...)` 用的 previousSample 是"首样本"，它从未出现在 `updatedSamples` 中。
- 开圈路径（`LapTimingEngine.kt:87`）：`ActiveLap(sampleStartIndex = updatedSamples.lastIndex)`。
- 闭圈路径（`LapTimingEngine.kt:107`）：`trajectory = updatedSamples.drop(activeLap.sampleStartIndex)`，`trajectory` 严格从"开圈那一帧"开始切片。
- **结果**：`trajectory` 永远不包含"首样本（开圈帧之前的 previous）"——这本身也是对的，因为首样本不属于开圈帧。但：
  - 当 `handleStartFinishCrossing` 再次 accepted 闭圈时，**闭圈样本同时落在上圈 trajectory 末帧和下圈 trajectory 首帧**（`sampleStartIndex = updatedSamples.lastIndex`）——这**是** sample 重叠 1 帧。
  - UI 里 `samples.filter { it.timestampMillis >= crossingTimestampMillis }`（`LapDebugExecutionScreen.kt:227`）会把这帧算进"当前圈路程"起始。

**影响**：

- `LapRecord.trajectory` 上圈最后一帧 和 下圈第一帧 是同一个 GpsSample 对象（位置、速度、ts 完全相同）。下游若做轨迹拼接（比如全程回放），会看到"在过线那一刻原地停了 1 帧"的视觉瑕疵。
- 长期：如果 review 把 "trajectory 无重叠" 作为隐含不变量（当前没写测试明确断言），后续按这个假设优化就会踩坑。

**修复方向**：

- **方案 A**（语义更正）：闭圈时 `new ActiveLap(sampleStartIndex = updatedSamples.lastIndex + 1)` —— 下圈从下一帧开始。代价：新圈第一帧真实样本到来前，`trajectory` 为空，UI "当前圈路程" 短暂显示 0 m。
- **方案 B**（记录侧自洽）：`LapRecord.trajectory` 截到闭圈帧但不 +1，`ActiveLap.sampleStartIndex` 下圈 +1。上下圈无重叠。
- **方案 C**（明确重叠是不变量）：在 LapRecord 的字段注释写清"闭圈帧同时属于上圈末帧和下圈首帧"，并加测试锁定。

**测试建议**：

- `LapTimingEngineTest.lapBoundary_sampleAppearsInBothTrajectories_or_not_depending_on_contract`：构造 2 圈完整闭环，断言"闭圈样本在上下圈 trajectory 中出现的次数符合契约"。

---

### 🟠 1.7 `segmentsIntersect` 对近平行段数值不稳定 ⇢ review 9.4 已提，本节深化

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt:75-99`

```kotlin
private fun segmentsIntersect(...): Boolean {
    val abx = bx - ax
    val aby = by - ay
    val cdx = dx - cx
    val cdy = dy - cy
    val denominator = (abx * cdy) - (aby * cdx)
    if (denominator == 0.0) {
        return false
    }
    ...
    val t = ((acx * cdy) - (acy * cdx)) / denominator
    val u = ((acx * aby) - (acy * abx)) / denominator
    return t in 0.0..1.0 && u in 0.0..1.0
}
```

**问题不在"严格平行"，而在"接近平行"**：

- 严格平行（`denominator == 0.0`）直接 return false，概率实际为 0（浮点不会精确等于 0）。
- 真正的数值风险：车辆位移方向与 gate 线接近平行时，`denominator` 是两个 ~1e-5 量级数的差，`denominator` 本身可能是 1e-10 量级。
- `t/u = (1e-8) / (1e-10) = 100` —— 被 `in 0.0..1.0` 筛掉 → **漏报**。
- 反向：若 `denominator` 正常量级，但 `t/u` 的分子有浮点误差，几乎在 1.0 边界时可能误判（边缘样本被接受或拒绝取决于第 15 位的浮点）。

**典型场景**：

- 车以极低速度平行滑过 gate（例如 pit 出入口或车主在起终点线附近缓慢操作）。这在当前"不启用速度下限"的配置下（1.1）正好是危险区。
- 车以高速斜切过 gate 的最端部（gate 线很长时，斜切的参数 u 接近 1.0）。

**影响**：

- 当前赛道规模下偶发漏报 / 误报；多赛道扩展后风险随赛道几何密度上升。
- review 9.4 说"多赛道/高纬度迁移时需升级"，其实本地同样存在。

**修复方向**（与 1.1 的修复可以合并）：

- 把所有坐标换到米坐标系（本地 ENU 或以 gate 线中点为原点的平面投影）后再做线段相交判定。米量级下 `denominator` 数量级通常 ~1~100 m²，数值稳定。
- 相交判定用"叉积同号 + 参数化"的标准形式，包含对共线/端点落在另一段上的显式处理。

**测试建议**：

- `GateCrossingDetectorTest.detect_nearParallelCrossing_isCorrect`：用实际 TFIC 的 gate 坐标 + 一个方向与 gate 线夹角 < 3° 的位移，断言 accepted。
- `GateCrossingDetectorTest.detect_tangentialContactAtGateEnd_isStable`：位移刚好切到 gate 端点，断言行为可预测（接受或拒绝必须一致，不能浮点抖动）。

---

### 🟡 1.8 `LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 缺对 `qualityFlags` 的断言 ⇢ review 9.x 错

**证据**：`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt:54-75`

```kotlin
@Test
fun processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors() {
    val startedSession = engine.processSample(...)   // 第一次起终点
    val finishedSession = engine.processSample(...)  // 第二次起终点，中间没穿任何 sector

    assertEquals(1, finishedSession.completedLaps.size)
    val lap = finishedSession.completedLaps.first()
    assertEquals(1, lap.lapIndex)
    assertEquals(267_000L, lap.durationMillis)   // 只断时长
    assertNotNull(finishedSession.activeLap)
    assertEquals(2, finishedSession.currentLapIndex)
    // 没有 assertEquals(listOf(IncompleteSectors), lap.qualityFlags)
}
```

**事实核对**：

- review 2026-04-21 9.x 明确声称"缺扇区强制闭圈 + `IncompleteSectors` 质量标记"由"2026-04-04 `lap-timing-start-finish-closure-fix` 固化，测试锁定于 `LapTimingEngineTest.processSample_missingSectorStillCompletesLapWithIncompleteFlag` 与 `...outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish`"。
- 实际 `LapTimingEngineTest.kt:139, 169` 的确有 `assertEquals(listOf(IncompleteSectors), ...)`。**但**这两条测试覆盖的是"穿了部分 sector 后闭圈"和"穿了乱序 sector 后闭圈"。
- "两次起终点中间**完全不穿任何 sector**"的纯路径，只有 `processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 覆盖，而它**没有**断言 `qualityFlags`。

**意味着**：

- 如果有人把 `LapTimingEngine.kt:93-97` 的逻辑误改成"缺扇区时不打 flag"，`LapTimingEngineTest.kt:54-75` 不会 fail。
- review 的"锁定"叙述成立在"部分缺扇区"路径，不成立在"完全无扇区"路径。

**修复方向**：

- 在 `processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 末尾追加：
  ```kotlin
  assertEquals(listOf(LapQualityFlag.IncompleteSectors), lap.qualityFlags)
  ```
- 等价地加一条独立测试 `processSample_completeLapWithZeroSectorCrossings_isFlaggedIncompleteSectors`。

---

### 🟡 1.9 `LapDebugExecutionScreen.calculateDistanceSince` 每次 recomposition 全量 haversine ⇢ review 9.6 已提相似点，本节为性能补充

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt:194-242`

```kotlin
internal fun rememberStartFinishTimingCardState(lapSession: LapSession?): StartFinishTimingCardState {
    ...
    return StartFinishTimingCardState(
        ...
        currentLapDistanceLabel = formatDistanceMeters(
            calculateDistanceSince(lapSession?.samples.orEmpty(), latestAcceptedCrossing.timestampMillis)
        ),
        ...
    )
}

private fun calculateDistanceSince(samples: List<GpsSample>, crossingTimestampMillis: Long): Double {
    val samplesSinceCrossing = samples.filter { it.timestampMillis >= crossingTimestampMillis }
    if (samplesSinceCrossing.size < 2) return 0.0
    var totalMeters = 0.0
    for (index in 1 until samplesSinceCrossing.size) {
        totalMeters += haversineDistanceMeters(...)
    }
    return totalMeters
}
```

**问题**：

- 名叫 `rememberStartFinishTimingCardState` 但不是 `@Composable`、不用 `remember { ... }`。它只是普通函数。
- 每次 `lapSession` State 变化都会触发 recomposition（25 Hz），**全量**从头 filter + 从头 haversine 整个 `samples` 列表。
- 单圈 5 分钟 × 25 Hz ≈ 7500 样本，单次 recomposition 做 7500 次 `haversineDistanceMeters`（每次包含 4 个三角函数）。
- 25 Hz 触发 → 每秒 7500 × 25 ≈ 187,500 次 haversine 调用。叠加 1.2 的主线程 I/O，UI 几乎不可能流畅。

**修复方向**：

1. 把 `rememberStartFinishTimingCardState` 真正做成 `@Composable` + `remember(lapSession?.samples, latestAcceptedCrossing)` 缓存。
2. 改"增量累积"：engine 在 `ActiveLap` 里维护一个 `distanceMetersSinceStart` 字段，每次 append sample 时增量加一次 haversine；UI 只读该字段，不自算。
3. 引擎和 UI 统一使用同一个距离定义（解决 review 9.6 提到的"UI haversine vs 引擎欧氏叉积"不同源问题，同时顺便修掉 1.1 的量纲问题——把位置比较统一到米坐标系）。

**测试建议**：

- `LapDebugExecutionScreenStateTest.currentLapDistance_matchesIncrementalSum` — 提供已知的 sample 列表，断言 UI 状态里的 `currentLapDistanceLabel` 等于手算的累积米数。
- 性能回归：Robolectric 或 benchmark library 下测 7500 样本 recomposition 耗时 < 16 ms。

---

### 🟡 1.10 `bridgeGpsToLapTiming` 首样本分支的冗余赋值 ⇢ review 漏

**证据**：`TestSessionViewModel.kt:315-319`

```kotlin
lastLapGpsSample = currentSample
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession   // 把 StateFlow 已有值再赋一次
    return
}
```

- `MutableStateFlow` 在 `value = x` 时，如果 `x equals` 当前值则**不触发订阅者**（StateFlow 合约）。所以 `_lapSession.value = currentSession`（就是刚刚 `.value` 读出来的值）既不会 emit 也不会改 state，整行是死码。
- 如果未来有人把 `_lapSession` 换成 `SharedFlow`，这行会突然变成"每帧都 emit"，形成 bug 引爆点。

**修复方向**：

- 直接删除 `_lapSession.value = currentSession` 这行。

---

### 🟡 1.11 UI `currentLap` 显示与会话真实状态不符 ⇢ review 漏

**证据**：`LapDebugExecutionScreen.kt:42`

```kotlin
val currentLap = (lapSession?.currentLapIndex ?: 0) + 1
```

**语义**：

- `lapSession.currentLapIndex = 0`（`Ready` 状态，尚未过第一次起终点）→ UI 显示 `Lap 1`。
- 第一次过起终点开圈后 `currentLapIndex = 1` → UI 显示 `Lap 2`（严格说第一次过线才是真正开圈，显示 `Lap 1` 更合理，但这里 +1 偏了一格）。

**和状态卡片的冲突**：

- `StartFinishTimingCard` 在 `Ready` 状态显示 `statusLabel = "等待起点"`（`LapDebugExecutionScreen.kt:206`）。
- 但顶部 `"当前圈: Lap 1"` 明显暗示"已经在第 1 圈"。

**影响**：用户看到 "Lap 1 + 等待起点" 会困惑"我是在跑 Lap 1 还是还没开始？"

**修复方向**：

- `currentLap` 只在 `activeLap != null` 时显示；`activeLap == null` 时显示 "未开圈 / 等待起点"。

---

### 🟡 1.12 `LapTimingEngine.expectedGate` 和 `handleSectorCrossing` 对 sector 重复排序 ⇢ review 漏

**证据**：

- `LapTimingEngine.kt:200-201`：`track.sectorGates.sortedBy { it.sequenceIndex }.getOrNull(nextExpectedGateIndex - 1)`
- `LapTimingEngine.kt:137`：`val orderedSectorGates = track.sectorGates.sortedBy { it.sequenceIndex }`

每个样本都两次独立 `sortedBy`。对 2 个 sector 可忽略，但：

1. Sort 本身不昂贵；真正的问题是**两处的排序结果没有被显式断言一致**。如果将来其中一处被换成 `sortedByDescending` 或 `sortedBy { -it.sequenceIndex }`（复制粘贴错误），不会立刻 fail 测试，但行为会错。
2. 每次 recomposition 都重建一个新 list，小量但稳定的 GC 压力。

**修复方向**：

- 缓存在 `Track` 里：`Track` 构造时计算 `val orderedSectorGates = sectorGates.sortedBy { it.sequenceIndex }` 字段；engine 直接用。
- 或者在 `LapTimingEngine` 里按 `track.id` cache（注意 track 的 identity 稳定性）。

---

### 🟡 1.13 `ReplayAlignedTrackCatalog` 的 lazy 首次访问在调用方线程 ⇢ review 漏

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt:34-41`

```kotlin
private val replayAlignedTrack: Track? by lazy {
    runCatching {
        buildReplayAlignedTrack(
            replayJson = replayTrackSource.loadReplayJson(),
            vbo = replayTrackSource.loadTrackVbo()
        )
    }.getOrNull()
}
```

- `by lazy` 默认 `LazyThreadSafetyMode.SYNCHRONIZED`，首次访问的线程承担初始化成本。
- 看 `TestSessionViewModel` 的构造：`_availableTracks = MutableStateFlow(trackCatalog.getAllTracks())`（`TestSessionViewModel.kt:69`），ViewModel 构造发生在 `ViewModelProvider.get(...)`，通常在主线程。
- `AssetReplayTrackSource.loadReplayJson()` = `context.assets.open(...).bufferedReader().use { it.readText() }` + 后续 Gson parse —— 两次磁盘 I/O + 字符串解析，在主线程上。

**影响**：

- 冷启动进入 LapDebug 页第一次访问 track 列表时，主线程会卡顿（asset 读+Gson parse 数十 ms 级）。
- 如果 replay JSON 将来增大（当前 5 Hz 已经不小），卡顿更明显。
- review 之前几份都没提。

**修复方向**：

- `buildReplayAlignedTrack` 改为 `suspend fun`，`TrackCatalog` 接口扩展 `suspend fun getAllTracks(): List<Track>`；在 ViewModel 的 `init` 用 `viewModelScope.launch(Dispatchers.IO) { _availableTracks.value = trackCatalog.getAllTracks() }` 异步加载。
- 或者最简单：把 `by lazy` 改为 `by lazy(mode = LazyThreadSafetyMode.NONE)` 并在 App 启动的 IO coroutine 里预热一次。

---

### 🟡 1.14 `bridgeGpsToLapTiming` 对时间戳倒退没有守卫 ⇢ review 漏

**证据**：`TestSessionViewModel.kt:301-326`（见 1.3 引用）+ `RaceChronoParser.kt:257-261`

- parser 返回的 ts 在协议 syncBits 不匹配时会回落到 `System.currentTimeMillis()`。
- `bridgeGpsToLapTiming` 直接把 `gpsData.timestamp` 喂给 engine，没有任何"相对于上次 sample 是否单调"的校验。
- engine 对时间单调也无感知（见 1.3）。

**复现路径**：

1. 开圈后 `activeLap.startedAtMillis = 协议时钟 ts = 1_773_477_876_690L`。
2. 某一帧协议 syncBits 不匹配 → 回落到 `System.currentTimeMillis()` = 假设 `1_773_477_876_400L`（比开圈时间小）。
3. engine 继续处理 → engine 内 `dt = currentSample.ts - previousSample.ts` 可能为负。
4. `GateCrossingDetector.kt:55-56`：`dtSeconds = 负数`，`directionalSpeedMps = directionScore / dtSeconds` → **符号反转**，可能把 `accepted` 路径变 `WrongDirection` 或反之。
5. engine 把该样本 append 到 `session.samples`，后续切片、UI 计算（1.9）都在"时间非单调"的数组上进行。

**修复方向**：

- `bridgeGpsToLapTiming` 开头：如果 `currentSample.timestampMillis < (previousSample?.timestampMillis ?: 0L)`，丢弃该样本 + 打一条诊断日志（非每帧高频）。
- 或在 `TestSessionViewModel` 里维护一个 `lastAcceptedLapGpsTs`，低于它的样本直接 skip，包括首样本。

**测试建议**：

- `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`。

---

### 🟡 1.15 `LapTimingEngine` 日志行包含完整样本坐标，隐私/体量 ⇢ review 漏

**证据**：`LapTimingEngine.kt:30-33, 164-167`

```kotlin
FileLogger.d(
    TAG,
    "targetGate=${...}, prev=(${previousSample.latitude},${previousSample.longitude},...), current=(...)"
)
```

- 每帧日志把经纬度写到 `filesDir/debug_log.txt`。
- 25 Hz × 长圈数 × 完整精度 = 大量 GPS 轨迹以明文存盘。
- 隐私层面：用户车辆位置完整记录。体量层面：3 行/样本 × 每行 ~200 字节 × 25 Hz × 1 小时 ≈ 54 MB/小时。
- `FileLogger.init` 每次启动清空老日志（`FileLogger.kt:20`），所以不会无限增长。但单次会话的体量已经足够把 ADB pull / 崩溃上报搞慢。

**修复方向**：

- `FileLogger` 加日志级别控制（`FileLogger.setLogLevel(...)`）。
- 1.2 的批量化 + 有条件打印同步落实，默认只在状态转移、accepted crossing 时打。

---

## 二、GPS 接入与滤波模块（BLE → Parser → Filter → ViewModel）

### 🔴 2.1 `ConnectionManager` 是彻底的死代码 ⇢ review 11.5 错

**证据（DI 未注册）**：`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`

```
factory { GpsDataFilter() }
factory { LapTimingEngine(get()) }
factory { AnomalyDetector() }
factory { DataInterpolator() }
...
// 没有 ConnectionManager
```

**证据（全仓无实例化）**：

```
grep -R "ConnectionManager" .
  → 只有 ConnectionManager.kt:12 (类定义)
  → ConnectionManager.kt:17 (TAG 常量)
grep -R "setCurrentDevice" .
  → 只有 ConnectionManager.kt:65 (方法定义)
```

**结论**：

- `ConnectionManager` 没有被 DI 注册、没有被任何类构造、没有被任何地方调 `setCurrentDevice`。
- 它的 `init { ... startFakeConnectionCheck() }` 永远不跑。
- `_isFakeConnection` 永远为 `false`。
- `triggerReconnect()` 即使被调，也因 `currentDeviceAddress == null` 而空返回（没人 setCurrentDevice）。

**review 2026-04-22 11.5 声称**：

> `ConnectionManager` 和 `BleConnection` 双层超时互相干涉 —— 先到的那个改了 `_connectionState`，另一个的前提条件就失效。

**事实**：

- 当前系统里只有 `BleConnection` 一层在跑假连接检测（且它也不 close GATT，见 2.2）。
- 没有任何"双层互相干涉"。review 11.5 的叙述与代码事实相反。
- 真正的问题是：**假连接恢复根本没实现**。ESP32 经典故障模式"GATT 显示 connected 但无 notify"发生时，系统除了把 `_connectionState` 置 DISCONNECTED 之外，没有任何恢复动作（不 close GATT、不重连、不通知用户）。

**影响**：

- 用户必须手动杀进程或重启 app 才能恢复连接。
- review 11.5 的假设"两层超时能互相补位"是幻觉，按这个假设做 backlog 决策（例如 "合并到一层"）会把本就没跑的一层删掉，而没有补实际缺失的恢复路径。

**修复方向**：

1. 决定是否保留 ConnectionManager：
   - **保留**：在 `AppModule.kt` 里 `single { ConnectionManager(get(), get()) }`，并且在 `BleDeviceManager.connect(deviceAddress)` 里调 `connectionManager.setCurrentDevice(deviceAddress)`；把 `BleConnection.startDataTimeoutCheck` 的超时动作改成"只 log 不改状态"（把恢复责任收敛到 ConnectionManager）。
   - **删除**：把 `ConnectionManager.kt` 整个文件删掉，在 `BleConnection.startDataTimeoutCheck` 里加真正的 `disconnect() + close() + reconnect()`。
2. 任一方案都必须有明确的"谁负责重连"单一职责。

**测试建议**：

- 真机测试场景：连接 ESP32 → 拔掉 ESP32 电源（模拟假连接）→ 断言 15 秒内 app 自动重连并恢复数据（前提是 ESP32 重新上电）。
- 单测：`BleDeviceManagerTest.onDataTimeout_triggersReconnectLoop`。

---

### 🔴 2.2 `BleConnection.startDataTimeoutCheck` 不 close GATT，且存在"假断连-假连接"抖动 race ⇢ review 11.3 已提，本节深化 race

**证据 1（不 close GATT）**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt:244-252`

```kotlin
private fun startDataTimeoutCheck() {
    timeoutJob = scope.launch {
        delay(DATA_TIMEOUT_MS)
        if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
            Log.w(TAG, "数据接收超时，触发重连")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
```

- 超时后只改 `_connectionState.value`。
- `bluetoothGatt?.disconnect()` 没调，`bluetoothGatt?.close()` 没调，`bluetoothGatt = null` 没做。
- 对比 `BleConnection.disconnect()`（172-178）的完整清理流程，这里是残次版。
- `BluetoothGatt` 实例泄漏，底层 Android BT stack 的 GATT client 槽位不释放（每个 app 有上限 ~30）。

**证据 2（race）**：`BleConnection.kt:126-142`

```kotlin
private fun handleCharacteristicChange(characteristic: ..., value: ByteArray) {
    logReceivedData(characteristic.uuid, value)
    onDataReceived(characteristic.uuid, value)
    lastDataTime = System.currentTimeMillis()

    if (_connectionState.value != ConnectionState.CONNECTED) {
        _connectionState.value = ConnectionState.CONNECTED
    }

    timeoutJob?.cancel()           // (A) 取消旧 job
    startDataTimeoutCheck()        // (B) 启动新 job
}
```

**race**：

- T1：上一个 timeoutJob 在 IO dispatcher 的 delay 中。
- T2：某一帧到达触发 `handleCharacteristicChange`，行 (A) 在 BLE 回调线程调 `timeoutJob?.cancel()`。
- T3：但 timeoutJob 的 delay 刚好在 T2 之前微秒级就到点了 → 已经进入 `if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS)` 判断。
- T4：`cancel()` 对已经出 delay、正在执行同步代码的协程不保证立即停止，body 继续跑完 → `_connectionState.value = DISCONNECTED`。
- T5：紧接着行 (B) 启动新 timeoutJob + `lastDataTime` 刚被更新，`_connectionState.value = CONNECTED`（回到 126-138 的 if 条件判断）。

**现象**：`_connectionState` 在 1-2 ms 内：`CONNECTED → DISCONNECTED → CONNECTED`，产生瞬间抖动。

**下游连锁**：

- `BluetoothDataSource._connectionState` 通过 `connectionCollectJob` 桥接（60-66），会把抖动原样传导。
- `GpsDataViewModel.connectionState` 的 `stateIn(... WhileSubscribed(5000))` 会把抖动暴露给 UI。

**修复方向**：

1. `startDataTimeoutCheck` 内：
   ```kotlin
   timeoutJob = scope.launch {
       delay(DATA_TIMEOUT_MS)
       ensureActive()   // 取消感知
       if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
           Log.w(TAG, "数据接收超时")
           _connectionState.value = ConnectionState.DISCONNECTED
           bluetoothGatt?.let {
               it.disconnect()
               it.close()
           }
           bluetoothGatt = null
       }
   }
   ```
   加 `ensureActive()` 消除 race；真正释放 GATT 资源。
2. 职责边界与 2.1 联动：决定"是 BleConnection 自己 close GATT + 触发外部重连"还是"交给 ConnectionManager 负责重连"，必须二选一不能都做。

**测试建议**：

- `BleConnectionTest`（新加 Robolectric 或 fake GATT 测试）：`startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected`。

---

### 🔴 2.3 `BluetoothDataSource` 对失败解析和未知 UUID 都 `copy(isConnected = true)`，污染下游一致性 ⇢ review 11.4 已提，本节深化传播链

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt:49-56`

```kotlin
bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
    val gpsData = when (uuid.toString()) {
        "00000003-..." -> parser.parseGpsData(rawData, _dataFlow.value)
        "00000004-..." -> parser.parseGpsTimeData(rawData, _dataFlow.value)
        else -> _dataFlow.value
    }
    _dataFlow.value = gpsData.copy(isConnected = true)
}
```

**两个路径都有问题**：

1. **未知 UUID 分支**：`gpsData = _dataFlow.value`（当前值再赋一次 + isConnected=true）。对 StateFlow 来说，`data class equals` 成立就不 emit，所以"未知 UUID"本身不会产生假帧。但**它隐藏了一个真实风险**：未来任何地方只要 `GpsData.equals` 被重写或某字段被改（比如加一个 `receivedAt: Long = System.currentTimeMillis()`），这条路径会开始每个未知包都 emit 一次假帧。
2. **短包（<20 字节）分支**：`parser.parseGpsData` 直接 `return currentData`（`RaceChronoParser.kt:136-140`），即返回 `_dataFlow.value` 的同一实例 → 经 `copy(isConnected = true)` → 值与当前相同（isConnected 已经 true），**StateFlow 不 emit**，OK。
3. **但**：解析主包时 try/catch 内部抛异常走 `return currentData`（`RaceChronoParser.kt:292-295`）。同样返回 "旧 currentData"，StateFlow 不 emit。
4. **下游误解释**：`GpsData.isConnected = true` 被 `ConnectionManager`（虽然死代码）、`LaunchStatus` 等当成"数据有效"的闸门。一旦链路中有人用 `_dataFlow.value.isConnected` 判断"能不能开始圈速"，短包/异常解析连续发生时，`isConnected` 仍为 true，但实际 `timestamp/latitude/longitude` 可能是几百毫秒前的旧值。

**复现路径（具体的下游放大）**：

- `TestSessionViewModel.updateLaunchStatus` 读 `gpsData` → `smartTestLauncher.checkLaunchConditions(gpsData, connectionState, lastDataAge, ...)`。
- `lastDataAge = System.currentTimeMillis() - lastDataTime`，`lastDataTime = gpsData.timestamp`（`TestSessionViewModel.kt:115`）。
- 如果短包场景下 parser 不更新 `timestamp`，`lastDataTime` 被冻结 → `lastDataAge` 持续增大 → UI 显示"数据年龄越来越老"但 `isConnected = true`。**状态自相矛盾**。

**修复方向**：

1. 在 `parser.parseGpsData` 的短包分支 / catch 分支 显式返回 `currentData.copy(errorMessage = "short-packet")`（或 `copy(lastParseOk = false)` 新字段），并让 `BluetoothDataSource` 在 `errorMessage != null` 时**不**把 `isConnected` 强置 true。
2. 把 "isConnected" 的语义收敛为"GATT 状态连上 + 最近一次 parse 成功"；解析失败就 false。
3. 或者 `BluetoothDataSource` 不自己管 `isConnected`，只由 `BleConnection.connectionState` 一条路径控制。

---

### 🟠 2.4 `GpsDataFilter` 信号丢失重置顺序错 ⇢ review 漏

**证据**：`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt:30-45`

```kotlin
fun process(raw: GpsData): FilteredGpsData {
    // 1. 计算加速度（用旧 previousRaw）
    val acceleration = calculateAcceleration(raw)

    // 2. 物理约束检查（用旧 previousRaw）
    val isAnomaly = isPhysicalConstraintViolation(raw)

    // 2.5. 信号丢失重置
    val dtFromPrevious = previousRaw?.let { (raw.timestamp - it.timestamp) / 1000.0 } ?: 0.0
    if (dtFromPrevious > 0.2) {
        previousRaw = null
        previousPosition = null
    }

    // 2.6. 位置-速度一致性检验（此时 previousRaw 已 null，早退）
    val (consistencyFactor, isPositionAnomaly) = checkPositionVelocityConsistency(raw)
    ...
}
```

**问题**：

- "信号丢失重置"的本意是"跳过本帧与前帧的关联计算，把本帧作为新起点"。
- 但 `calculateAcceleration`（第 32 行）和 `isPhysicalConstraintViolation`（第 35 行）已经在**重置之前**跑过，用的是"隔了大半秒的旧 previousRaw"。
- 在 `isPhysicalConstraintViolation` 里：
  ```kotlin
  val dt = (current.timestamp - prev.timestamp) / 1000.0
  val maxDelta = maxDeltaPerSecond * dt
  return speedDelta > maxDelta
  ```
  `dt` 很大（0.3s, 0.5s, 1s...），`maxDelta = 90 × 1 = 90 km/h`（加速情况下）。**只要速度跳变小于 90 km/h，就判非异常** —— 完全失效。
- 类似地，`calculateAcceleration = dv / dt`，大 dt 会把真实的"信号丢失期间发生的速度跳变"稀释成小加速度，下游的 `consecutiveTriggerCount` 基于加速度阈值，会漏触发或误触发。

**影响**：

- 信号丢失恰恰是最容易产生假速度的场景（GPS 重新锁星时 speed 可能瞬间从 0 跳到真实值），这里的保护反而最弱。
- 对加减速测试的影响：车在隧道出入口等丢星场景，触发条件判定会错。

**修复方向**：

- 调整顺序，把重置放在加速度/异常判定之前：
  ```kotlin
  val dtFromPrevious = previousRaw?.let { (raw.timestamp - it.timestamp) / 1000.0 } ?: 0.0
  if (dtFromPrevious > 0.2) {
      previousRaw = null
      previousPosition = null
  }
  val acceleration = calculateAcceleration(raw)
  val isAnomaly = isPhysicalConstraintViolation(raw)
  ...
  ```
- 这样重置后 `calculateAcceleration/isPhysicalConstraintViolation` 都从"previousRaw == null"分支早退（各自返回 0.0 / false），语义与"本帧作为新起点"一致。

**测试建议**：

- `GpsDataFilterTest.process_signalLossLongerThanThreshold_acceleratesFromNewBaseline`。
- `GpsDataFilterTest.process_signalLoss_thenLargeSpeedJump_isNotSuppressedByStalePreviousRaw`：信号丢失后的第一帧不应该因为旧 previousRaw 而错误判定为"非异常"。

---

### 🟠 2.5 `GpsDataFilter` 把异常帧无条件当成下一帧基准，污染链式计算 ⇢ review 漏

**证据**：`GpsDataFilter.kt:81-82`

```kotlin
previousRaw = raw
previousPosition = raw.latitude to raw.longitude
```

- 无论 `isAnomaly` / `isPositionAnomaly` 是否为 true，这两行都执行。
- 下一帧的 `calculateAcceleration`、`isPhysicalConstraintViolation`、`checkPositionVelocityConsistency` 都以"坏点"为参考。

**复现路径**：

1. 帧 N：GPS 跳飞，lat/lon 漂出 10 m，被判 `isPositionAnomaly = true`。
2. 帧 N+1：真实位置，但 `previousPosition` 是帧 N 的漂点。
3. `checkPositionVelocityConsistency` 算出的 `distanceM` 是"帧 N 漂点 → 帧 N+1 真实点"的距离 = 10 m/1 帧 = 几百 km/h。
4. `ratio = speedDiff / tolerance` 爆炸 → 帧 N+1 也被标 `isPositionAnomaly = true`。
5. 链式污染：一次 GPS 跳飞导致连续 N 帧被误标异常，直到跳飞点被窗口挤出（中位数滤波把漂点从输出里吸收，但**内部状态里依然是漂点**）。

**对下游的影响**：

- 置信度 `confidence` 连续多帧走低 → UI 质量灯黄/红，但实际数据良好。
- 加减速测试的触发逻辑基于 `filteredData.acceleration`，漂点阶段的 acceleration 被污染。

**修复方向**：

- 异常帧不更新 `previousRaw/previousPosition`：
  ```kotlin
  if (!isAnomaly && !isPositionAnomaly) {
      previousRaw = raw
      previousPosition = raw.latitude to raw.longitude
  }
  ```
- 但要注意：连续异常帧（持续漂）会导致 `previousRaw` 永远停留在更久之前的旧值，`dt` 越拉越大 → 触发 2.4 同类问题。需要配合 "上次更新距今超过某阈值就强制更新" 规则。

**测试建议**：

- `GpsDataFilterTest.process_singleSpikeThenRecovery_doesNotLeakAnomalyToNextFrame`。

---

### 🟠 2.6 `GpsDataFilter.isAnomaly` 分支实际没有差异化行为 ⇢ review 漏

**证据**：`GpsDataFilter.kt:60-70`

```kotlin
val outputSpeed = when {
    isAnomaly && speedWindow.size >= 3 -> {
        speedWindow.median()        // 分支 A
    }
    speedWindow.size >= 3 -> {
        speedWindow.median()        // 分支 B，与 A 完全相同
    }
    else -> raw.speed
}
```

- 两个分支体完全相同。`isAnomaly` 在 outputSpeed 层没有任何作用。
- 字段注释"异常点：用窗口内非异常点的中位数（这里简化为用中位数）"坦白承认了"简化没做"。

**问题**：

- 和 `docs/superpowers/specs/2026-03-21-gps-data-filter-design.md` 的设计意图存在脱节：spec 可能要求"异常时用窗口非异常点的中位数"（剔除异常后计算），现实是"和正常点一样走 median"。
- 异常判定仅在 `calculateConfidence`（confidence × 0.5）里反映，没有"丢弃/插补/强制回退"的语义。
- `speedWindow.add(raw.speed)` 在异常时也加入 → 异常帧进入窗口，后续几帧的中位数会被污染。

**修复方向**（两选一）：

1. **删除分支**：承认没做差异化，把 `when` 简化为：
   ```kotlin
   val outputSpeed = if (speedWindow.size >= 3) speedWindow.median() else raw.speed
   ```
   然后在 `isAnomaly=true` 时**不** append 到窗口：
   ```kotlin
   if (!isAnomaly) { speedWindow.add(raw.speed) }
   ```
2. **实现差异化**：`isAnomaly=true` 时，输出用"上次非异常的 outputSpeed"（需要维护 `lastStableSpeed` 状态），不改窗口状态。

**测试建议**：

- `GpsDataFilterTest.process_anomalyFrame_doesNotPollutePosteriorMedianOutput`。

---

### 🟠 2.7 `BleConnection.disconnect()` 流程中 `close()` 调用时机早于 `STATE_DISCONNECTED` 回调 ⇢ review 漏

**证据**：`BleConnection.kt:172-178`

```kotlin
fun disconnect() {
    cleanup()                       // 1. 取消 timeoutJob
    bluetoothGatt?.disconnect()     // 2. 触发异步 callback
    bluetoothGatt?.close()          // 3. 立刻 close
    bluetoothGatt = null
    _connectionState.value = ConnectionState.DISCONNECTED
}
```

**问题**：

- `gatt.disconnect()` 是异步的：它触发 Android BT stack 在未来某个时刻回调 `onConnectionStateChange(..., newState = STATE_DISCONNECTED)`。
- `close()` 是同步的：立即释放 GATT 资源，系统把这个 client 槽位回收。
- 某些厂商实现下，`close()` 之后再收到 `onConnectionStateChange` 回调，回调里对 `gatt` 的任何访问（`gatt.device`、`gatt.services`）都是对已关闭对象的访问，行为未定义。
- 另外：`onConnectionStateChange(STATE_DISCONNECTED)` 分支（73-78）里已经调 `cleanup()`，但没动 `bluetoothGatt`。`disconnect()` 流程里的 `cleanup() + close() + null` 先到，之后回调再进，回调里 `_connectionState.value = ConnectionState.DISCONNECTED` 赋了一次（但 MutableStateFlow equality 阻止 emit），OK但逻辑冗余。

**标准做法（Android 官方文档）**：

```
1. gatt.disconnect()
2. 等待 onConnectionStateChange 回到 STATE_DISCONNECTED
3. 在回调里 gatt.close()
4. 置 null
```

**修复方向**：

- `disconnect()` 只做 `gatt.disconnect()`，不做 `close()`。
- 把 `close()` 挪到 `onConnectionStateChange(STATE_DISCONNECTED)` 分支里。
- 配合 2.2 的超时路径一起修。

---

### 🟡 2.8 `TestSessionViewModelTest.kt` 也停留在旧 package，review 11.1 漏列 ⇢ review 漏

**证据**：`app/src/test/java/com/blazepush/viewmodel/TestSessionViewModelTest.kt:1-9`

```kotlin
package com.blazepush.viewmodel

import com.blazepush.domain.model.GpsData      // 旧 package
import com.blazepush.domain.model.TestTemplate
import com.blazepush.domain.usecase.FilteredGpsData
import com.blazepush.domain.usecase.GpsDataFilter
```

- `com.blazepush.domain.*` 在源码里已经迁到 `com.blazepush.core.domain.*`。
- 这个测试文件与 `GpsDataFilterTest.kt` 同病，编译失败。
- review 2026-04-22 11.1 只点名 `GpsDataFilterTest`，漏了这一条。

**影响**：

- ViewModel 集成层的"GPS 过滤 + 触发条件"回归保护同样为 0。
- 修复 11.1 时如果只迁 `GpsDataFilterTest`，这条还会留在 `compileDebugUnitTestKotlin` 失败列表里。

**修复方向**：

- 两个文件一起迁到新 package 路径：
  - `GpsDataFilterTest` → `core/domain/src/test/java/com/blazepush/core/domain/usecase/`
  - `TestSessionViewModelTest` → `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/`（如果引用了 ViewModel 本身）或者 `core/domain/src/test/...`（如果实际只测 filter 集成）。

---

### 🟡 2.9 `AnomalyDetector` / `DataInterpolator` 在 DI 里注册了但没消费者 ⇢ review 11.2 已提，本节补 DI 事实

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt:83-85`

```kotlin
factory { AnomalyDetector() }
factory { DataInterpolator() }
```

- 两个 factory 注册，但全仓 `by inject<AnomalyDetector>()` / `get<AnomalyDetector>()` 搜索无命中（除了自身定义）。
- 这意味着 **DI 容器会构造但没人取**，是纯浪费。

**review 11.2 描述**：

> 只有定义，没有任何生产者调用。

**事实补充**：

- 不只是"没有调用"，连 DI 都知道它们存在（不像 `ConnectionManager` 连 DI 都没管）。这种"半接线"更容易让后来者误以为"已经启用"。
- `DataSmoothing` 在 `GpsDataViewModel` 的构造参数里，但只调 `reset()`（`GpsDataViewModel.kt:168`），`reset()` 调用方 `resetStats()` 自己全仓无调用方 —— 真正意义上也是闲置。

**修复方向**：

- 删除 `AppModule.kt` 中的 `AnomalyDetector` / `DataInterpolator` factory，同时删除对应实现文件（前提是正文确认不再用）。
- 或者接入：`GpsDataFilter` 内组合 `AnomalyDetector` 给出更精细的异常原因（`stale/jump/range/sat_low/zero_coord`），`DataInterpolator` 在 2.4 的信号丢失场景插补缺失样本。

---

### 🟡 2.10 `BleDeviceManager.autoReconnectLastDevice` 的 `else` 分支不 fallback 到扫描 ⇢ review 11.6 错

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt:59-87`

```kotlin
val lastDeviceAddress: String? = null // 暂时为null，待后续实现

if (lastDeviceAddress != null) {
    Log.d(TAG, "尝试自动重连设备: $lastDeviceAddress")
    delay(1000)
    bluetoothDataSource.connect(lastDeviceAddress)
    var waited = 0L
    while (waited < RECONNECT_TIMEOUT_MS) {
        delay(500)
        waited += 500
        if (connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "自动重连成功")
            return@launch
        }
    }
    Log.w(TAG, "自动重连超时，开始扫描其他设备")
    startScan()
} else {
    Log.d(TAG, "没有上次连接的设备记录")   // ⚠️ 只 log，不 startScan
}
```

**review 11.6 描述**：

> `lastDeviceAddress` 目前硬编码为 `null`，TODO 未落地，自动重连实际不工作，会直接 fallback 到 `startScan()`。

**事实**：

- 只有 `lastDeviceAddress != null && 超时未连` 路径才 `startScan()`（line 84）。
- 只有 `catch Exception` 分支才 `startScan()`（line 91）。
- **`lastDeviceAddress == null` 分支不 startScan**。当前硬编码就是 null → 冷启动时 ViewModel 构造触发 init → autoReconnectLastDevice → 命中 else 分支 → 只 log。

**真实行为**：

- 用户冷启动进入 app，app **不会**自动开始扫描；用户必须手动点"扫描"按钮才能看到设备列表。
- review 11.6 的叙述与事实相反，按它的假设（"会 fallback startScan"）做后续决策就会错。

**修复方向**：

1. 要么把 else 分支改成 `startScan()`，让行为与 review 叙述一致。
2. 要么接入 TODO：从 `BluetoothDeviceRepository`（`core/data/` 下已有）读 last device address。
3. 同时修订 review 2026-04-22 11.6 的描述。

---

### 🟡 2.11 `RaceChronoParser` 累计距离是 parser 内部死状态 ⇢ review 漏

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:219-255`

```kotlin
if (fixQuality > 0 && satellites >= 3) {
    if (!hasStartedTracking) { ... }
    else {
        if (lastLat != null && lastLon != null) {
            Location.distanceBetween(lastLat, lastLon, currentLatitude, currentLongitude, results)
            val distanceStep = results[0]
            if (speedKmh > 1.0) {
                totalDistance += distanceStep / 1000.0   // km
            }
        }
        lastLatitude = currentLatitude
        lastLongitude = currentLongitude
    }
}
```

**证据 2**：`totalDistance` 不写回 `GpsData`，不通过任何 API 暴露。grep `totalDistance` 只命中 parser 内部。

**结论**：`totalDistance` 是 parser 的私有状态，计算后**没人读**。

**附加问题**：

- 阈值 `satellites >= 3` 与 `isTestReady = satellites >= 6 && hdop < 2.0`（275 行）不一致。两套独立规则没法对齐。
- `speedKmh > 1.0` 的 1 km/h 过滤对低速段偏严格。

**修复方向**：

- 删除 `totalDistance / hasStartedTracking / startTime` 三组字段和相关逻辑。
- 如果未来需要"实时里程"功能，交给 domain 层新 usecase，而不是 parser 副产物。

---

### 🟡 2.12 `RaceChronoParser.parseGpsTimeData` 把 `isTestReady = true` 的写入与主包闸门冲突 ⇢ review 漏

**证据**：`RaceChronoParser.kt:98-102`

```kotlin
if (!currentData.isTestReady) {
    currentData.copy(isTestReady = true)
} else {
    currentData
}
```

- 时间包到达时，无论 satellites/hdop 如何，都把 `isTestReady = true`。
- 下一帧主包到来时，`isTestReady = satellites >= 6 && hdop < 2.0`（275 行）可能又把它改回 false。

**复现路径**：

1. 冷启动，GPS 刚加电，`satellites = 2`，`isTestReady = false`。
2. 时间包先到 → `isTestReady = true` 写入 `_dataFlow`。UI 短暂显示"准备就绪"。
3. 主包到达，`satellites = 2`，`isTestReady = false` 覆盖。UI 改回"未就绪"。
4. 反复闪烁直到真正锁星。

**修复方向**：

- 时间包里不写 `isTestReady`。`isTestReady` 只由主包根据卫星数 + HDOP 决定。
- 时间包只负责设置 `protocolTimeReference`，不影响数据质量字段。

---

### 🟡 2.13 `GpsDataViewModel.updateDataStats` 频率指标永不自动重置，长时间运行退化为累积平均 ⇢ review 11.9 已提相关，本节补完整链

**证据 1**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt:86-101`

```kotlin
if (dataCountStartTime == 0L) {
    dataCountStartTime = now
}
...
dataCount++
val elapsedSeconds = (now - dataCountStartTime) / 1000.0
val frequency = if (elapsedSeconds > 0) dataCount / elapsedSeconds else 0.0
```

**证据 2（resetStats 无调用方）**：`GpsDataViewModel.kt:164-169`

```kotlin
fun resetStats() {
    dataCount = 0
    dataCountStartTime = 0L
    lastDataTime = 0L
    dataSmoothing.reset()
}
```

grep `resetStats` 全仓 → 只命中定义。

**结论**：

- `dataCountStartTime` 第一次收到数据时锁定，此后永不重置（ViewModel 生命周期内）。
- 设备连接 1 小时后，`elapsedSeconds ≈ 3600`，`frequency = dataCount / 3600`。若 dataCount 达到 90_000（25 Hz × 3600 s），`frequency = 25.0`。
- 如果此刻突然开始丢帧（比如只收到 10 Hz），短期内的 `frequency` 变化：`(90_000 + 10 × 10) / 3610 ≈ 24.93`，肉眼不可见。
- UI 的质量灯基于这个 frequency，**永远显示"良好频率"**。

**附加问题**：

- `expectedInterval = 100L`（100ms = 10Hz）与 ESP32 实际 25Hz 矛盾。review 11.9 已指出。但叠加本条，frequency 数字和 packetLossRate 口径同时失真。

**修复方向**：

1. 把 frequency 统计从"累积平均"改为"滑动窗口"（和 `RaceChronoParser` 的 1 秒窗口一致），或直接复用 `RaceChronoParser.gpsFrequency`（它已经是滑窗计算，ViewModel 可以直接订阅读）。
2. `expectedInterval = 40L`（40ms = 25Hz）并常量化到一个单源（例如 `GpsConstants.EXPECTED_SAMPLE_INTERVAL_MS`），parser / ViewModel / DataQualityEvaluator 共享。
3. 在 `BleConnection` 断开时调 `gpsDataViewModel.resetStats()`（当前没有链路调它）。

**测试建议**：

- `GpsDataViewModelTest.frequency_reflectsRecentDropRateNotLifetimeAverage`。

---

### 🟡 2.14 `BluetoothDataSource.connect` 不先 disconnect 旧 `bleConnection` ⇢ review 漏

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt:42-71`

```kotlin
fun connect(deviceAddress: String) {
    scope.launch {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            bleConnection = BleConnection(context, deviceAddress) { ... }   // 直接新建
            ...
            bleConnection?.connect()
        } catch (e: Exception) { ... }
    }
}
```

**问题**：

- 如果 `bleConnection` 不为 null（之前已连接过其它设备），直接 `bleConnection = BleConnection(...)` 会丢失旧引用，**旧 GATT 不 disconnect/close**，泄漏。
- 用户场景：扫描到多个设备，尝试连 A 失败，再尝试连 B → A 的 GATT 永远挂着。
- 叠加 2.2（超时不 close），泄漏扩大。

**修复方向**：

- `connect` 开头：
  ```kotlin
  bleConnection?.disconnect()
  bleConnection = null
  connectionCollectJob?.cancel()
  connectionCollectJob = null
  ```

---

### 🟡 2.15 `ConnectionManager.init` 的空 collect 占用一个 IO coroutine ⇢ review 漏

**证据**：`ConnectionManager.kt:50-56`

```kotlin
scope.launch {
    bluetoothDataSource.connectionState.collect { state ->
        if (state == ConnectionState.CONNECTED && currentDeviceAddress == null) {
            // 连接成功时，从 dataFlow 中获取地址（通过 timestamp 非零来判断有数据）
        }
    }
}
```

- collect body 是空注释。
- `connectionState.collect` 是 StateFlow 的挂起订阅，永不自然结束。
- 即使 ConnectionManager 当前是死代码（2.1），一旦接线，这段空 collect 会永久占用一个 IO coroutine。

**修复方向**：

- 要么删除整段，要么实现 TODO（从 dataFlow 提取最近一次 connected 设备地址填到 `currentDeviceAddress`）。

---

### 🟡 2.16 `GpsDataFilter.circularMedian` 命名与实现不符 ⇢ review 11.?（7.3 已提但未列入 11 节）

**证据**：`GpsDataFilter.kt:253-270`

- 实现是"所有角度的单位向量求和 → atan2"，本质是**向量均值**，不是中位数。
- 命名为 `circularMedian` 会让未来的维护者以为"取中位"，用错场景（比如希望对离群鲁棒，但这里的实现对离群不鲁棒）。

**修复方向**：

- 改名 `circularMean`，并在注释里明确说"对对称分布准确，对长尾分布会被拉向长尾"。
- 如果真的需要对离群鲁棒的 circular 统计量，查 `circular median angle`（例如 Fisher 1993）实现。

---

### 🟡 2.17 `GpsDataFilter.checkPositionVelocityConsistency` 跨经度不处理 ⇢ review 漏

**证据**：`GpsDataFilter.kt:186-187`

```kotlin
val deltaLatM = abs(current.latitude - prevPos.first) * 111320.0
val deltaLonM = abs(current.longitude - prevPos.second) * 111320.0 * Math.cos(latRad)
```

- `abs(Δlon)` 直接相减，不考虑 ±180° 经度绕回。
- TFIC 在 104°E，车不会跨 180°，**不触发**。但：
  - 如果未来赛道 asset 导入不同经度带，此处会产生几千米的假位移。
  - 一个完整的 filter 不应该对"赛道地理位置"做隐式假设。

**修复方向**：

- `val deltaLon = current.longitude - prevPos.second`
- `val wrappedDeltaLon = when { deltaLon > 180 -> deltaLon - 360; deltaLon < -180 -> deltaLon + 360; else -> deltaLon }`
- `val deltaLonM = abs(wrappedDeltaLon) * 111320.0 * cos(latRad)`

---

## 三、已有 review 文档的事实性错误

本节汇总本次复审中发现的"review 叙述与代码事实不一致"条目。修订建议每条附一个"应改为"的替代文本。

### 3.1 review `2026-04-22` 11.5 对 ConnectionManager 的描述与事实相反

**原文**：

> **11.5 `ConnectionManager` 和 `BleConnection` 双层超时互相干涉**
> 二者都在做"10s 无数据 → 视为断开"，但动作不同：`BleConnection` 只改状态，`ConnectionManager` 主动重连。先到的那个改了 `_connectionState`，另一个的前提条件就失效。

**事实**（本报告 2.1）：

- `ConnectionManager` 从未被 DI 注册，也从未被任何地方实例化 / `setCurrentDevice`。它的 `init { ... }` 不跑。它的假连接检测不生效。
- 真实现象不是"双层打架"，而是"只有 BleConnection 一层在做超时，且它不 close GATT"。

**建议改为**：

> **11.5 假连接恢复未实现（ConnectionManager 是死代码）**
> `ConnectionManager` 在 `AppModule` 里未注册，全仓没有实例化点，`setCurrentDevice` 也没调用方。它存在的所有逻辑都是 dead code。真实链路里只有 `BleConnection.startDataTimeoutCheck` 这一层的超时检测，且超时后只改 `_connectionState`、不 `disconnect() + close()`、不重连（见 11.3）。假连接故障（GATT connected 但无 notify）发生时，系统没有任何自动恢复动作。

### 3.2 review `2026-04-22` 11.6 对 autoReconnectLastDevice fallback 行为的描述错误

**原文**：

> **11.6 `BleDeviceManager.autoReconnectLastDevice` 实质未实现**
> `BleDeviceManager.kt:59` 硬编码 `lastDeviceAddress = null`，TODO 挂着。
> **影响**：每次冷启动都走扫描路径，没有"上次设备优先"能力。

**事实**（本报告 2.10）：

- `autoReconnectLastDevice` 在 `lastDeviceAddress == null` 分支**只 log 不 startScan**。
- 冷启动时**并不**自动走扫描路径。用户需要手动触发扫描。

**建议改为**：

> **11.6 `BleDeviceManager.autoReconnectLastDevice` 实质未实现，且 `else` 分支不 fallback**
> `BleDeviceManager.kt:59` 硬编码 `lastDeviceAddress = null`，TODO 挂着。`else` 分支（84-87 行）只 log 一行 "没有上次连接的设备记录"，不调 `startScan()`。
> **影响**：冷启动后 app 不会自动扫描，用户必须手动点"扫描"按钮才能看到设备。
> **修法**：要么把 else 分支改成 `startScan()`，要么接入 `BluetoothDeviceRepository` 读 last device address。

### 3.3 review `2026-04-22` 11.1 漏列 `TestSessionViewModelTest.kt`

**原文**：

> **11.1 `GpsDataFilterTest` 未随 package 迁移**
> 测试在旧 package `com.blazepush.domain.usecase`，源码在 `com.blazepush.core.domain.usecase`。编译即 fail。

**事实**（本报告 2.8）：`app/src/test/java/com/blazepush/viewmodel/TestSessionViewModelTest.kt` 也停留在 `com.blazepush.domain.*` 旧 package，同样编译失败。review 11.1 漏列。

**建议改为**：增补一句 "同一原因，`app/src/test/java/com/blazepush/viewmodel/TestSessionViewModelTest.kt` 也处于不可编译状态。"

### 3.4 review `2026-04-21` 九节"由测试锁定"的叙述强度不准确

**原文**（九节对 `IncompleteSectors` 的描述）：

> **9.x 缺扇区也强制闭圈**，但打 `IncompleteSectors` 质量标记 … 由 2026-04-04 `lap-timing-start-finish-closure-fix` 固化，测试锁定于 `LapTimingEngineTest.processSample_missingSectorStillCompletesLapWithIncompleteFlag` 与 `...outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish`。

**事实**（本报告 1.8）：

- 这两条测试都是"部分穿扇区"或"乱序穿扇区"的路径。
- "两次起终点中间完全不穿任何 sector"的纯路径，只有 `processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 覆盖，它**没有**断言 `qualityFlags`。

**建议改为**：显式区分路径。增补一条 "本次审查发现 'completely-without-sectors' 子路径不在 `qualityFlags` 锁定范围内，需要补断言"（对应的修复建议见 1.8）。

### 3.5 review `2026-04-21` 9.5 速度下限描述不足

**原文**：

> **9.5 速度下限门槛未启用**
> 所有门 `minDirectionalSpeedMps = null`。
> **风险**：低速掉头经过起终点会被判为 accepted。
> **建议**：结合 TFIC 起终点速度区间约束 给一个保守下限，比如 50 km/h。

**事实**（本报告 1.1）：

- 启用下限**会直接让所有穿线判 TooSlow**，因为 `directionalSpeedMps` 实际单位是 度²/秒，不是 m/s。
- 按 review 9.5 的建议填 50 km/h (= 13.9 m/s)，实际值 `~9e-8` 永远小于 `13.9`。

**建议改为**：新增一段 "启用前必须先修量纲错位（见本次对抗复审 1.1）。当前 detector 的 `directionalSpeedMps` 是度²/秒量纲，与字段名 `Mps` 不符，直接按 m/s 填入下限会锁死整条链路。"

---

## 四、优先级处置矩阵

| 优先级 | 编号 | 主题 | 建议处理窗口 |
|---|---|---|---|
| 🔴 P0 **实测** | 8.1 | 发射端 simulator 用本地系统时钟当 GPS ts | **本迭代最高优先** |
| 🔴 P0 **实测** | 8.2 | 接收端 parser 协议未对齐时 fallback 本地时钟 | **本迭代最高优先** |
| 🔴 P0 **实测** | 8.3 | 双端时钟拼接导致圈时系统性偏长 | **同批与 8.1/8.2 一起修** |
| 🔴 P0 | 8.4 | dataAge / packetLossRate 跨时钟计算失真 | 同批 |
| 🔴 P0 | 8.5 | ConnectionManager.lastDataTime 跨时钟 | 同批（待 2.1 决策） |
| 🔴 P0 | 8.6 | engine 信任跨时钟 ts，无 isTimeSynced 守卫 | 同批 |
| 🔴 P0 | 1.1 | detector 量纲错位（启用即锁死） | 本迭代合流前 |
| 🔴 P0 | 1.2 | FileLogger 主线程同步 I/O | 本迭代合流前 |
| 🔴 P0 | 2.1 | ConnectionManager 是死代码 | 本迭代决策（接线 or 删） |
| 🔴 P0 | 2.2 | BleConnection 超时不 close + race | 本迭代合流前 |
| 🔴 P0 | 2.3 | BluetoothDataSource 对错解析标 isConnected | 本迭代合流前 |
| 🟠 P1 | 1.3 | crossingEvents dropWhile 依赖单调 | 下迭代 |
| 🟠 P1 | 1.4 | 多门同帧被穿只处理一个 | 下迭代 |
| 🟠 P1 | 1.5 | engine 不检查 LapSessionStatus | 下迭代 |
| 🟠 P1 | 1.6 | sampleStartIndex 首样本交互 | 下迭代 |
| 🟠 P1 | 1.7 | segmentsIntersect 近平行不稳定 | 下迭代（与 1.1 合并修） |
| 🟠 P1 | 2.4 | filter 信号丢失重置顺序错 | 下迭代 |
| 🟠 P1 | 2.5 | filter 异常帧污染 previousRaw | 下迭代 |
| 🟠 P1 | 2.6 | filter isAnomaly 分支无差异 | 下迭代 |
| 🟠 P1 | 2.7 | BleConnection close 时机 | 下迭代 |
| 🟡 P2 | 1.8 | LapTimingEngineTest 断言松 | backlog |
| 🟡 P2 | 1.9 | UI 全量 haversine 性能 | backlog |
| 🟡 P2 | 1.10 | bridgeGpsToLapTiming 冗余赋值 | backlog |
| 🟡 P2 | 1.11 | UI Lap 显示与状态不符 | backlog |
| 🟡 P2 | 1.12 | sector gates 重复排序 | backlog |
| 🟡 P2 | 1.13 | ReplayAlignedTrackCatalog lazy 主线程 I/O | backlog |
| 🟡 P2 | 1.14 | 时间戳倒退无守卫 | backlog |
| 🟡 P2 | 1.15 | engine 日志含完整坐标 | backlog |
| 🟡 P2 | 2.8 | TestSessionViewModelTest 旧 package | backlog（与 review 11.1 合并） |
| 🟡 P2 | 2.9 | AnomalyDetector/DataInterpolator 半接线 | backlog |
| 🟡 P2 | 2.10 | BleDeviceManager else 不 fallback | backlog |
| 🟡 P2 | 2.11 | parser totalDistance 死状态 | backlog |
| 🟡 P2 | 2.12 | parseGpsTimeData 写 isTestReady | backlog |
| 🟡 P2 | 2.13 | frequency 累积平均退化 | backlog |
| 🟡 P2 | 2.14 | BluetoothDataSource.connect 不清旧连接 | backlog |
| 🟡 P2 | 2.15 | ConnectionManager 空 collect | backlog（与 2.1 合并） |
| 🟡 P2 | 2.16 | circularMedian 名实不符 | backlog |
| 🟡 P2 | 2.17 | 跨经度不处理 | backlog |

---

## 五、测试补齐清单（汇总）

按模块归集，供后续补测试或 PR review 时逐项核对。

### 5.1 `GateCrossingDetectorTest`

- [ ] `detect_minDirectionalSpeedMps_enforcementMatchesMps`（对应 1.1）
- [ ] `detect_nearParallelCrossing_isCorrect`（对应 1.7）
- [ ] `detect_tangentialContactAtGateEnd_isStable`（对应 1.7）

### 5.2 `LapTimingEngineTest`

- [ ] `processSample_outOfOrderCrossingEvents_doNotLeakAcrossLaps`（对应 1.3）
- [ ] `processSample_multiGateCrossingInSingleStep_recordsAllEvents`（对应 1.4）
- [ ] `processSample_onFinishedSession_returnsUnchanged`（对应 1.5）
- [ ] `processSample_completeLapWithZeroSectorCrossings_isFlaggedIncompleteSectors`（对应 1.8）
- [ ] `lapBoundary_sampleAppearsInBothTrajectories_or_not_depending_on_contract`（对应 1.6）

### 5.3 `TestSessionViewModelTrackLapTest`

- [ ] `bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`（对应 1.14）

### 5.4 `LapDebugExecutionScreenStateTest`

- [ ] `currentLapDistance_matchesIncrementalSum`（对应 1.9）

### 5.5 `GpsDataFilterTest`（先把 2.8 的 package 迁移修掉再补）

- [ ] `process_signalLossLongerThanThreshold_acceleratesFromNewBaseline`（对应 2.4）
- [ ] `process_signalLoss_thenLargeSpeedJump_isNotSuppressedByStalePreviousRaw`（对应 2.4）
- [ ] `process_singleSpikeThenRecovery_doesNotLeakAnomalyToNextFrame`（对应 2.5）
- [ ] `process_anomalyFrame_doesNotPollutePosteriorMedianOutput`（对应 2.6）

### 5.6 `BleConnectionTest`（需要引入 fake GATT 或 Robolectric）

- [ ] `startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected`（对应 2.2）
- [ ] `disconnect_closesGattAfterStateDisconnectedCallback`（对应 2.7）

### 5.7 `BleDeviceManagerTest`

- [ ] `onDataTimeout_triggersReconnectLoop`（对应 2.1）
- [ ] `autoReconnectLastDevice_whenLastAddressNull_fallsBackToScan`（对应 2.10，修复后）

### 5.8 `FileLoggerTest`

- [ ] `d_whenFileWriteThrows_doesNotPropagate`（对应 1.2）
- [ ] `d_called1000TimesIn1s_finishesWithinBudget`（对应 1.2，性能回归）

### 5.9 `GpsDataViewModelTest`（新建）

- [ ] `frequency_reflectsRecentDropRateNotLifetimeAverage`（对应 2.13）

---

## 六、修订已有 review 文档的建议

### 6.1 `docs/superpowers/reviews/2026-04-21-lap-timing-review.md`

- **九节** 9.5 补充量纲警告（见本报告 3.5）。
- **九节** 9.x 关于 `IncompleteSectors` 的叙述收紧范围（见本报告 3.4）。

### 6.2 `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`

- **十一节** 11.1 增补 `TestSessionViewModelTest.kt` 同病（见本报告 3.3）。
- **十一节** 11.5 改写为"假连接恢复未实现"（见本报告 3.1）。
- **十一节** 11.6 修正 `else` 分支行为（见本报告 3.2）。

两份 review 修订完毕后再进入下一轮评审，避免 backlog 决策建立在错误前提上。

---

## 八、补遗：发射端 + 接收端双端时间戳污染（用户实测 P0）

> 触发来源：2026-04-22 用户实测反馈 —— "发射 app 或者接收 app 有一方甚至两方，在计时的时候没有用 GPS 原始数据里的时间戳，而是读了自己的系统本地时钟。导致计算下来的圈速时间偏长。"
>
> 本节把发射端 simulator 和接收端 gps-app 的完整时间戳链路还原，确认两端都有污染，并给出定位到代码行号的修复方案。

### 🔴 8.1 发射端 `GpsDataGenerator` 100% 使用发射手机本地系统时钟（非 replay 模式）⇢ review 漏

**证据 1（时间戳生成源）**：`simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:115-121`

```kotlin
private fun currentTimestampMillis(): Long {
    return if (scenario == TestScenario.REAL_TRACK_REPLAY) {
        replayTimestampMillis ?: System.currentTimeMillis()
    } else {
        System.currentTimeMillis()
    }
}
```

- 非 `REAL_TRACK_REPLAY` 场景（`STATIC` / 其它任何手动场景）：**直接返回发射手机 OS 的 `System.currentTimeMillis()`**。
- Replay 场景：用 `replayTimestampMillis`（从 `tianfu_track_replay_5hz.json` 读的 sample.timestampMillis）；如果 `replayTimestampMillis == null`（ReplayFrame 未及时调 `applyReplaySample`）也 fallback 到 `System.currentTimeMillis()`。

**证据 2（被编进协议主包的 `timeSinceHourStart`）**：`GpsDataGenerator.kt:46-52`

```kotlin
val timeMs = ((currentTimestampMillis() % 3_600_000L).toInt()) / 2
val timeHigh = (timeMs shr 16)
data[0] = (((syncCounter and 0x07) shl 5) or (timeHigh and 0x1F)).toByte()
data[1] = ((timeMs shr 8) and 0xFF).toByte()
data[2] = (timeMs and 0xFF).toByte()
```

`timeSinceHourStart` = （发射手机系统时钟毫秒 mod 3_600_000） / 2 —— 纯发射端本地时钟的派生值。

**证据 3（被编进协议时间包的 `dateAndHour`）**：`GpsDataGenerator.kt:135-151`

```kotlin
val calendar = java.util.Calendar.getInstance().apply {
    timeInMillis = currentTimestampMillis()   // ⚠️ 同样是发射手机本地时钟
}
val year = calendar.get(java.util.Calendar.YEAR)
val month = calendar.get(java.util.Calendar.MONTH) + 1
val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

val yearOffset = if (year > 2000) year - 2000 else 0
val dateAndHour = yearOffset * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
```

**证据 4（Replay 模式下 `replayTimestampMillis` 的来源）**：`GpsDataGenerator.kt:202-212` + `RaceChronoReplayParser.kt:44`

```kotlin
// GpsDataGenerator.applyReplaySample
fun applyReplaySample(sample: ReplaySample) {
    ...
    replayTimestampMillis = sample.timestampMillis
}

// RaceChronoReplayParser 把 VBO 的秒数乘 1000 转成 ms
timestampMillis = (parts[0].toDouble() * 1000).toLong()
```

Replay 的 `timestampMillis` 是 VBO 文件里记录的"赛道真实跑圈时 GPS 采样秒数 × 1000"，与手机系统时钟无关，也不是现实日期。例如 tianfu_track_replay_5hz 的 sample ts 可能是 `0, 200, 400, ...`（相对圈起点），也可能是某历史日期的 UTC ms。

**分场景结论**：

| simulator 场景 | 主包 `timeSinceHourStart` 来源 | 时间包 `dateAndHour` 来源 | 圈时是否可信 |
|---|---|---|---|
| `STATIC`（手动） | **发射端本地时钟** | **发射端本地时钟** | ❌ 不可信，见 8.3 |
| `REAL_TRACK_REPLAY`（正常） | Replay sample ts | Replay sample ts 推算 | ✅ 可信，前提是 sample 连续 |
| `REAL_TRACK_REPLAY`（帧丢失/ReplaySample 未设置） | **fallback 到发射端本地时钟** | **fallback 到发射端本地时钟** | ❌ 部分不可信 |

---

### 🔴 8.2 接收端 `RaceChronoParser` 在协议时间包未对齐时 fallback 到接收端本地时钟 ⇢ review 2026-04-22 "一" 节已提，本节串起因果

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:257-261`

```kotlin
val protocolTimestamp = protocolTimeReference
    ?.takeIf { it.syncBits == syncBits }
    ?.hourStartMillis
    ?.plus(timeSinceHourStart)
    ?: System.currentTimeMillis()
```

**fallback 触发条件**（任一成立）：

1. `protocolTimeReference == null` —— 冷启动后尚未收到任何 time 包。
2. `protocolTimeReference.syncBits != syncBits` —— 主包的 `syncBits` 和最后一次 time 包的 `syncBits` 对不上（因为 syncCounter 0~7 循环，任何 time 包丢失 ≥ 1 次都会触发）。

**关键观察**：

- **`syncCounter` 由发射端每帧自增**（`GpsDataGenerator.kt:126-128`），0-7 循环。
- 发射端 `startGpsDataStream` 每帧同时生成 mainData 和 timeData（`GpsDataGenerator.kt:235-247`），两者 sync 一致。
- BLE notify 是两条独立 characteristic：**time 包可能单独丢失**（尤其 MTU=31 时，两包不在同一个 ATT PDU），主包仍到达。
- syncBits 只有 3 位，连续丢 8 个 time 包后 syncBits 重新对上，但 `hourStartMillis` 已是 8 帧前的旧数据（如果跨小时边界就错了）。

**fallback 后接收端塞进 `GpsData.timestamp` 的值 = 接收端手机的 `System.currentTimeMillis()`**，与发射端时间、与 GPS 采样时刻都无关。

---

### 🔴 8.3 偏长现象的机理推导（逐步）

设：

- `T_send(n)` = 发射端生成第 n 帧时 `System.currentTimeMillis()` 的返回值（发射手机时钟）。
- `T_recv(n)` = 接收端 parser 处理第 n 帧时 `System.currentTimeMillis()` 的返回值（接收手机时钟）。
- `D(n)` = `T_recv(n) - T_send(n)` = BLE 传输 + 回调调度延迟 + 两机时钟漂移。
- `Δclock` = 两台手机系统时钟的常量漂移（即使 NTP 同步也有几十~几千 ms）。

**几种 timestamp 组合**：

| 协议对齐状态 | `GpsData.timestamp` 实际值 |
|---|---|
| 发射端 STATIC + 接收端已对齐 | 来源于主包 `timeSinceHourStart` = `T_send mod 小时`，`hourStartMillis` 也来源于 time 包（发射端时钟） → **完整等价于 `T_send`** |
| 发射端 STATIC + 接收端未对齐（冷启动或 time 包丢失） | `T_recv` ≈ `T_send + D` |
| 发射端 REPLAY + 接收端已对齐 | 来源于 Replay sample ts（赛道录制真实 GPS 时刻） |
| 发射端 REPLAY + 接收端未对齐 | `T_recv`（接收端时钟，与 replay 录制时刻完全无关） |

**圈时的计算**（`LapTimingEngine.kt:103-105`）：

```kotlin
durationMillis = currentSample.timestampMillis - activeLap.startedAtMillis
```

**偏长触发 case A（最常见，也与用户实测吻合）**：

1. 开圈样本到达时，接收端协议刚**已对齐**（上一条 time 包的 syncBits 匹配）：`t_open = T_send(n_open)`（发射端时钟）。
2. 闭圈样本到达前某个 time 包丢包或 syncCounter 在 8 周期内没发全，接收端某一帧 `syncBits` 不匹配 → parser fallback 到接收端时钟。
3. 恰好那一帧是 accepted 闭圈帧：`t_close = T_recv(n_close) ≈ T_send(n_close) + D(n_close)`。
4. `durationMillis = t_close - t_open = (T_send(n_close) - T_send(n_open)) + D(n_close) = 真实圈时 + D(n_close)`。

**`D(n_close)` 永远 ≥ 0**，所以**偏差单向偏正**，圈时恒定偏长。

**偏长触发 case B（replay 模式下）**：

1. 开圈帧协议已对齐 → `t_open = replay ts_open`（赛道真实录制时刻，例如 `1_234_000`）。
2. 闭圈帧 time 包丢失 → `t_close = 接收端 System.currentTimeMillis()` = 当前真实日期（例如 `1_773_477_876_000`）。
3. `durationMillis = 1_773_477_876_000 - 1_234_000 ≈ **1.77 万亿毫秒 = 56 年**。会直接显示离谱的圈时。
4. 如果只偶发一帧 fallback，则看似"偶发一圈特别长"；如果连续多帧，"看起来时间在向前飞"。

**偏长触发 case C（STATIC 模式，两端时钟漂移）**：

1. 开圈接收端已对齐 → `t_open = T_send(n_open)`（发射手机时钟）。
2. 闭圈接收端未对齐 → `t_close = T_recv(n_close) = T_send(n_close) + D + Δclock`。
3. 其中 `Δclock` 是发射 vs 接收手机系统时钟的恒定漂移。
4. `durationMillis = 真实圈时 + D + Δclock`。

如果 `Δclock > 0`（接收端时钟比发射端快），圈时偏长；反之偏短。**实测 "偏长" 说明 Δclock 主要为正 或 D 占主导（BLE 链路延迟必为正）**。

---

### 🔴 8.4 `GpsDataViewModel.updateDataStats` 的数据年龄计算也跨时钟 ⇢ review 11.9 已提相关，本节串起同类因果

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt:83-95`

```kotlin
val now = System.currentTimeMillis()    // 接收端时钟
...
val dataAge = if (data.timestamp > 0) {
    now - data.timestamp                // 接收端时钟 − 发射端时钟（协议对齐时）
} else {
    now - lastDataTime
}
```

- `data.timestamp` 如果是协议对齐状态得来 → 值 = **发射端时钟**。
- `now` = **接收端时钟**。
- `dataAge = 接收端 - 发射端 = D + Δclock`，即"BLE 链路延迟 + 两机时钟漂移"。
- UI 显示的"数据年龄"恒定偏正几十~几千 ms，即使链路正常。
- 一旦 `Δclock > packetLossRate 判定阈值（100ms × 2 = 200ms）`，app 会**持续误报丢包**（`GpsDataViewModel.kt:104-109`）。

---

### 🔴 8.5 `ConnectionManager.lastDataTime = gpsData.timestamp` 同样跨时钟 ⇢ review 漏（且 ConnectionManager 本身是死代码 2.1）

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt:42-46`

```kotlin
bluetoothDataSource.dataFlow.collect { gpsData ->
    if (gpsData.isConnected && gpsData.timestamp > 0) {
        lastDataTime = gpsData.timestamp      // ⚠️ 存的是"发射端时钟"（协议对齐）或"接收端时钟"（未对齐）
        checkForFakeConnection()
    }
}
```

然后：

```kotlin
else if (now - lastDataTime > INACTIVE_THRESHOLD) { ... }
```

`now = System.currentTimeMillis()` 是接收端时钟。`lastDataTime` 可能是发射端时钟。两者相减 = BLE 链路延迟 + Δclock，可能直接超过 10 秒 `INACTIVE_THRESHOLD` → **即使数据正常流动，也会被误判为"假连接"**。

（前提是 ConnectionManager 被接线。当前它是死代码，但这是 2.1 待修的问题。修 2.1 时必须同步修 8.5。）

---

### 🔴 8.6 接收端 `bridgeGpsToLapTiming` 把跨时钟 ts 直接喂给 `LapTimingEngine`

**证据**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:307-326`

```kotlin
val currentSample = gpsData.toLapGpsSample()   // GpsSample(timestampMillis = gpsData.timestamp, ...)
...
val updatedSession = lapTimingEngine.processSample(
    session = currentSession,
    track = track,
    previousSample = previousSample,
    currentSample = currentSample
)
```

`gpsData.timestamp` 就是上文 8.1~8.3 分析的"跨时钟拼接值"。engine 和下游 UI 完全信任这个值，既不校验单调（见 1.3、1.14），也不区分来源（见 8.2）。

---

### 🟢 8.7 修复方案

**总策略**：

1. **时间戳单源**：整条链路只信任一个时钟基，且该时钟基必须是"GPS 采样真实时刻"或明确等价的构造物。
2. **未知时间戳不静默回退**：协议未对齐时，宁可丢帧也不用本地时钟伪造。
3. **两端都要改**，缺一不可。

#### 发射端（simulator）

**方案 A（推荐，对齐真实设备固件行为）**：把 `timeSinceHourStart` 从"发射端系统时钟的派生值"改为"相对发射会话起点的 ms 偏移"。

```kotlin
// GpsDataGenerator.kt 新增
private val sessionStartMillis = SystemClock.elapsedRealtime()

private fun currentTimestampMillis(): Long {
    val offset = SystemClock.elapsedRealtime() - sessionStartMillis
    return when (scenario) {
        TestScenario.REAL_TRACK_REPLAY -> replayTimestampMillis
            ?: run {
                // ⚠️ 如果 replay 没提供 ts，应该跳过本帧，而不是 fallback 系统时钟
                throw IllegalStateException("Replay sample missing timestamp")
            }
        else -> offset    // 相对会话起点的单调 ms
    }
}
```

- 用 `SystemClock.elapsedRealtime()` 而不是 `System.currentTimeMillis()`：前者不受系统时间调整（NTP / 手动改表）影响，单调。
- 去掉静默 fallback：replay 模式缺 sample 直接 throw / skip，不用系统时钟兜底。

**方案 B（更彻底，对齐真实 GPS 设备）**：引入一个 "fake GPS clock" 模型，让 simulator 产出的 `timeSinceHourStart` 与"被模拟的 GPS 芯片时钟"语义一致。例如：
- `STATIC` 场景：从 `sessionStartMillis` 开始 40ms 一帧严格累加。
- `REAL_TRACK_REPLAY` 场景：严格用 replay sample ts，缺 sample 视为链路中断。
- 两种场景下 `hourStartMillis` 都由"fake GPS clock 的小时对齐值"生成，不经过 `Calendar.getInstance()` 读系统时钟。

#### 接收端（gps-app）

**方案 A（最小侵入）**：`RaceChronoParser.parseGpsData` 协议未对齐时**拒绝发射**一条伪数据。

```kotlin
// RaceChronoParser.kt:257-277 重构
val protocolTimestamp = protocolTimeReference
    ?.takeIf { it.syncBits == syncBits }
    ?.hourStartMillis
    ?.plus(timeSinceHourStart)

if (protocolTimestamp == null) {
    // 协议未对齐：不发射伪帧，返回原 currentData
    Log.d(TAG, "parse skipped: protocol time not synced (syncBits=$syncBits)")
    return currentData
}

currentData = currentData.copy(
    timestamp = protocolTimestamp,
    ...
    isTimeSynced = true,     // 新增字段，取代对 timestamp 的隐式信任
    ...
)
```

**方案 B（给下游选择权）**：`GpsData` 加字段 `isTimeSynced: Boolean`，`LapTimingEngine` / `TestSessionViewModel.bridgeGpsToLapTiming` / UI 在 `isTimeSynced == false` 时**主动跳过**本帧。

```kotlin
// GpsData.kt 加字段
data class GpsData(
    ...
    val isTimeSynced: Boolean = false,
    ...
)

// TestSessionViewModel.bridgeGpsToLapTiming 开头
if (!gpsData.isTimeSynced) {
    FileLogger.d(TAG, "bridgeGpsToLapTiming: skip unsynced frame, ts=${gpsData.timestamp}")
    return
}
```

- 与本报告 1.5（engine 的 status 守卫）和 1.14（时间戳倒退守卫）配合成完整的 "engine 只接受可信样本" 合约。

#### 协议层（双端共享）

- 若两端独立演化可能积累不一致，建议把 `syncBits/timeSinceHourStart/dateAndHour` 的编解码抽到 `core` 模块的 shared 代码，simulator 和 gps-app 都依赖同一份实现。
- `syncCounter` 范围 3 bit（0~7）在 BLE 丢包场景下有混淆风险，建议协议升级到 4 bit 或显式校验 `hourStartMillis` 新旧（例如接收端在对齐后缓存完整历史 sync 记录，不仅看当前值）。

---

### 🟢 8.8 测试补齐

#### 端到端契约测试（跨 simulator + gps-app）

- [ ] `EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta`
  - 发射端 STATIC 模式 40ms/帧 跑 10 秒，模拟两次过起终点。
  - 接收端记录 `LapRecord.durationMillis`。
  - 断言：`|durationMillis - 10_000| < 20ms`（容忍 BLE 抖动半帧）。
  - **当前这条测试不写，8.3 的 case A 就会在生产里重现，但开发者没有自动化提示**。

- [ ] `EndToEndLapTimingContractTest.replayMode_lapDurationMatchesReplayClock`
  - 用 `tianfu_track_replay_5hz.json` 作为 replay 输入，接收端端到端闭环。
  - 断言 `durationMillis` 与 replay 文件中"两次过起终点的 sample ts 差"精确相等（误差 < 5ms）。
  - 同时覆盖 review 9.2 / backlog "`ReplayLapTimingIntegrationTest` 复活"。

#### 接收端单测

- [ ] `RaceChronoParserProtocolTimeTest.parseGpsData_whenNotSynced_doesNotFallbackToSystemClock`
  - 构造 `parser.parseGpsData(mainData, GpsData.Empty)`，不喂 time 包。
  - 断言：返回的 `GpsData.timestamp` **不是** `System.currentTimeMillis()` 附近的值（例如返回 `-1L` 或 `isTimeSynced = false`）。

- [ ] `RaceChronoParserProtocolTimeTest.parseGpsData_whenSyncBitsMismatch_doesNotFallbackToSystemClock`
  - 先喂一个 syncBits=3 的 time 包，再喂一个 syncBits=5 的 main 包。
  - 断言同上。

- [ ] `TestSessionViewModelTrackLapTest.bridgeGpsToLapTiming_skipsFrameWhenTimeNotSynced`
  - 喂入 `GpsData.copy(isTimeSynced = false)` 的样本，断言 `lapSession.samples.size` 不变，engine 未被调用。

#### 发射端单测

- [ ] `GpsDataGeneratorTest.generateGpsMainData_staticMode_usesSessionRelativeClock`
  - 构造 `GpsDataGenerator(scenario = STATIC)`，手动注入 fake clock 使 `elapsedRealtime` 返回 `sessionStart + 1000L`。
  - 解析 mainData 的 `timeSinceHourStart`，断言它等于 `1000L / 2 = 500`。
  - 关键是断言它**不是**当前真实 `System.currentTimeMillis()` 的派生值。

- [ ] `GpsDataGeneratorTest.generateGpsMainData_replayMissingSample_throwsInsteadOfFallingBackToSystemClock`
  - 设置 `scenario = REAL_TRACK_REPLAY` 但不调 `applyReplaySample`。
  - 调 `generateGpsMainData()`，断言抛 `IllegalStateException`（方案 A 的行为）。

---

### 🔴 8.9 处置优先级

| 动作 | 位置 | 优先级 |
|---|---|---|
| 接收端 parser 不再 fallback System clock | `RaceChronoParser.kt:257-261` | **P0，首修** |
| 发射端 simulator STATIC 模式改用 elapsedRealtime | `GpsDataGenerator.kt:115-121` | **P0，同批修** |
| `GpsData` 增加 `isTimeSynced` 字段并贯穿下游 | `GpsData.kt` + engine + UI | P0，同批修 |
| 端到端契约测试（replay + static） | 新增 `EndToEndLapTimingContractTest` | P0，修复完当批补 |
| 协议编解码 shared 化 | `core/domain` 或 `core/bluetooth` | P1 |
| `syncBits` 扩位 / 抗丢包 | 协议升级 | P1 |

**与已有条目的联动修复顺序**：

1. 先修 8.1/8.2（本节）和 1.1（量纲）—— 它们决定了 `GpsData.timestamp` 和 detector 计算的基础正确性。
2. 再修 1.3/1.14（时间戳单调/倒退守卫）—— 以 `isTimeSynced` 为前提更合理。
3. 再修 2.1/2.2/2.3（BLE 层死代码与资源释放）—— 与时间戳无直接因果但与稳定性并列。

---

## 七、结语

两份 review 在"设计意图 / 数据模型 / 测试矩阵 / 已知裂缝"四块做得很充分，但**缺四件事**：

1. **量纲校对**（1.1 揭示）。
2. **运行时验证**（1.2 的主线程 I/O、2.1 的 DI 是否接线，都需要在 app 运行时层面验证，静态阅读看不到）。
3. **测试断言强度复核**（1.8 / 3.4 揭示"名字像锁定，断言不锁定"）。
4. **端到端时间戳契约**（第八节揭示，发射+接收双端都用本地时钟，review 只看到接收端一侧，且没意识到 simulator 本身也是污染源）。

本次对抗复审共列出 **37 条问题**（11 条 P0 + 8 条 P1 + 18 条 P2）和 **5 条 review 文档事实错误**。建议按第四节优先级矩阵（含第八节的 P0 子矩阵）逐项推进，第五节和 8.8 的测试清单作为"修复完成"的硬断言证据，第六节对 review 本身的修订必须先于下一轮评审。

**用户实测反馈闭环**：本报告第八节已定位"圈时偏长"的 root cause 至发射端 `GpsDataGenerator.currentTimestampMillis()` + 接收端 `RaceChronoParser.kt:257-261`，两端必须一次性修掉，否则任一侧留一条 fallback 路径，下个实测场景就会换个姿势复现。

---

**2026-04-22 落地闭环**：第八节 8.1–8.6 已进入 OpenSpec change **`fix-laptime-clock-source-integrity`** v2 并完成实施，产物位于 `openspec/changes/fix-laptime-clock-source-integrity/`。核心落地：
- 发射端 `GpsDataGenerator` 换 `SystemClock.elapsedRealtime()` 派生会话相对时钟 + 注入式 `clock: () -> Long`，Replay 缺 sample 显式抛 `IllegalStateException`
- 接收端 `RaceChronoParser` 协议未对齐时写 `timestamp = Long.MIN_VALUE`（sentinel）+ `isTimeSynced = false`，彻底移除 `?: System.currentTimeMillis()`
- `GpsData` 增 `isTimeSynced` 字段；`LapQualityFlag` 增 `ProtocolDesyncGap` 成员
- 分层守卫贯穿 `GpsDataFilter.process / preTriggerBuffer / processFilteredData / updateLaunchStatus / bridgeGpsToLapTiming`
- `LapTimingEngine` 闭圈扫描 `trajectory` 相邻 ts > 200ms 打 `ProtocolDesyncGap`
- UI `LapDebugExecutionScreen` 按 `activeLap != null / gpsData.isTimeSynced` 优先级区分"当前圈进行中 / 等待起点 / 等待协议时间同步"
- 新增 `EndToEndLapTimingContractTest`（6 测试）+ 各模块新增单元测试，合计 25+ 条 Scenario 锁定。
- 真机冒烟验证（华为 8KE0219522008434 × DP011011255100142）待用户执行；见 tasks.md 10.9。
