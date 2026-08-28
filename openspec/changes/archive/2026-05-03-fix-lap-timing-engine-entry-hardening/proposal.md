# fix-lap-timing-engine-entry-hardening

战役 C engine 入口夯实，闭环 attack-backlog **A19 / A21 / A38** 三条主项 + **A34**
顺手清理（A34 与 A38 改造窗口交汇，零边际成本一并闭环）。目标是把"喂样本前拦住"
的防御层统一做厚。

## Why

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
§ 1.3 / § 1.5 / § 1.14 揭示 `LapTimingEngine` 与 `TestSessionViewModel.bridgeGpsToLapTiming`
共用"入口无守卫"问题，三条在时间单调 / 会话状态两个轴上联动：

### A19：engine 对 `LapSessionStatus` 无感知（§ 1.5）

当前 `LapTimingEngine.processSample(session, track, previousSample, currentSample)`
入口没有任何 `session.status` 校验。生产链路靠 `TestSessionViewModel.isLapRecording`
布尔守卫（`bridgeGpsToLapTiming` 第 319 行 `if (!isLapRecording) return`）挡住
Finished / Cancelled / Idle，**engine 本身对会话状态完全无感知**。

复现路径：

1. 会话被 `stopTestSession()` 置 `isLapRecording = false`，但 `_lapSession.value`
   仍是 `status = Finished` 的 LapSession
2. 测试代码 / 未来重构直接调 `lapTimingEngine.processSample(finishedSession, ...)`
3. `handleStartFinishCrossing` 第 97-98 行 `session.copy(status = Recording, ...)`
   把 Finished 硬改回 Recording，`samples / crossingEvents / completedLaps` 无上限增长

**后果**：生产暂时不出问题（bridge 守卫挡住），但 engine 契约漏：一旦
`isLapRecording` 守卫被绕过（单元测试、以后多调用点、代码重构），engine 会
静默改写 Finished 会话。防御层深度不足。

### A21：`crossingEvents.dropWhile` 依赖时间戳单调（§ 1.3）

`handleStartFinishCrossing` 第 136 行：

```kotlin
crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis }
```

`dropWhile` 只在**第一个**满足 `ts >= startedAt` 的元素处停止，**后续元素不再检查**。
若事件流里某个已处理的老事件（时间戳 < `startedAtMillis`）夹在新事件之间（时间戳
回跳场景），它会被保留。

复现路径：

1. 协议 syncBits 切换 / time 包重到达导致 `protocolTimestamp` 回跳
2. engine 收到回跳后的 currentSample，`handleSectorCrossing` 可能产出 `timestampMillis < activeLap.startedAtMillis` 的 CrossingEvent
3. 下一圈闭圈时 `dropWhile` 在第一个"正常"事件处停下，回跳事件被保留 → 上一圈
   的 LapRecord.crossingEvents 包含**本圈之前**的异常事件，事件归属跨圈错乱

战役 A 的 sentinel（`Long.MIN_VALUE`）把"未同步帧"整帧拦在 bridge 之外，已
部分缓解此场景。但已同步帧之间的 ts 回跳（A38 场景）仍会穿透到 engine。

### A38：`bridgeGpsToLapTiming` 无时间戳单调守卫（§ 1.14）

当前 `bridgeGpsToLapTiming` 第 341 行只校验：

```kotlin
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession
    return
}
```

`<= 0L` 守卫负责拦 Long.MIN_VALUE / 0（战役 A 残留），但**不校验 `currentSample.timestampMillis < previousSample.timestampMillis`**（已同步帧之间 ts 回跳）。

复现路径：

1. 两帧 GPS 都 `isTimeSynced = true` 通过 324-328 行的 sentinel 守卫
2. 协议 syncBits 切换 / time 包重到达让第二帧 `protocolTimestamp` 小于第一帧
3. engine 收到 `currentSample.ts < previousSample.ts`，`GateCrossingDetector.dtSeconds` 符号反转
4. 原本 `accepted=true` 的过线变 `WrongDirection`（或反之），判圈错乱

### A21 + A38 依赖关系：双层防御互补

A38 是"污染源拦截"（bridge 入口拒收 ts 回跳帧），A21 是"engine 兜底深度防御"
（即使 bridge 被绕过 / 重构，engine 自己也能检测 ts 回跳并丢弃样本）。两者
**不是替代关系**：

- 只修 A38：生产链路安全，但单元测试 / 未来 bridge 被改 / 其他调用方直接喂 engine 时，engine 仍会把回跳帧算进去
- 只修 A21：bridge 层污染的 _lapSession / lastLapGpsSample 不再回退，下一帧对比基准错乱
- 两层都修：bridge 截断源头 + engine 兜底，符合"纵深防御"原则，这也是对抗
  review `§ 1.14` 明确要求"`bridgeGpsToLapTiming` 入口 + engine 入口 两处都加"

A19 与 A21/A38 在代码位置和语义上独立，但同为"入口守卫"系列，合并一个 change
走闭环最经济。

## What

### 1. A19：engine 入口 `LapSessionStatus` 白名单守卫

`LapTimingEngine.processSample` 第一行加：

```kotlin
BEFORE:
fun processSample(session, track, previousSample, currentSample): LapSession {
    val updatedSamples = session.samples + currentSample
    ...

AFTER:
fun processSample(session, track, previousSample, currentSample): LapSession {
    // A19 入口守卫：白名单语义，只放行 Ready / Recording 两个应接受样本的状态
    // 未来新增 LapSessionStatus 枚举值（Paused / Interrupted / ...）默认被拦，
    // 除非显式决定接受 —— 防御"新增枚举被遗忘"反模式
    if (session.status !in setOf(LapSessionStatus.Ready, LapSessionStatus.Recording)) {
        return session
    }
    val updatedSamples = session.samples + currentSample
    ...
```

**放行状态**：`Ready` / `Recording`（唯二两个应接受样本的状态）。

**为什么不用黑名单**：`LapSessionStatus` 当前枚举是 `Idle / Ready / Recording / Finished / Cancelled`。若拦 3 个放行 2 个的黑名单写法，未来新增枚举（如 `Paused`）会**默认被放行**，等于回到 A19 原问题。白名单是"开放默认不安全"反模式的正解。

### 2. A38：`bridgeGpsToLapTiming` 入口时间单调守卫 + 顺手清理 A34

当前代码（第 340-344 行）顺序：

```kotlin
BEFORE:
lastLapGpsSample = currentSample   // (A) 无条件先赋值
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession  // (C) 冗余：StateFlow 相同引用不 emit，死码 = A34
    return
}
val updatedSession = lapTimingEngine.processSample(...)
```

改造为"三段式"，每个分支对 `lastLapGpsSample` 动作语义明确：

```kotlin
AFTER (三段式)：

// 段 1 — 首样本分支：early return 但**仍赋 lastLapGpsSample**
//        为下一帧准备 previousSample，不赋会卡死起圈路径
//        顺手清理 A34：删除 `_lapSession.value = currentSession`
//        （StateFlow 相同引用不 emit，纯死码；留着未来换 SharedFlow 会爆）
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    lastLapGpsSample = currentSample
    return
}

// 段 2 — A38 时间单调守卫：回跳帧整帧丢弃 + **不**更新 lastLapGpsSample
//        保持前帧作为下一帧的 previousSample，避免 ts 回跳污染基准
//        与 A13 "异常帧不更新 previousRaw" 模式一致
if (currentSample.timestampMillis < previousSample.timestampMillis) {
    FileLogger.d(TAG, "bridgeGpsToLapTiming: ts regression, drop sample prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}")
    return
}

// 段 3 — 正常推进：更新 lastLapGpsSample + 喂 engine
lastLapGpsSample = currentSample
val updatedSession = lapTimingEngine.processSample(...)
_lapSession.value = updatedSession
_latestLapRecords.value = updatedSession.completedLaps
```

三个分支对 `lastLapGpsSample` 的动作对照：

| 分支 | 条件 | `lastLapGpsSample` 动作 | 理由 |
|---|---|---|---|
| 首样本 | `previousSample == null` | **赋 currentSample** | 为下一帧准备 previousSample，不赋会永远起不了圈 |
| ts 回跳 | `current.ts < previous.ts` | **不赋**（保持前帧） | 回跳帧是污染源，不让它成为下一帧基准 |
| 正常推进 | 以上都不成立 | **赋 currentSample** | 推进正常路径 |

**A34 决策 = 方案 A 顺手清理**：A34（`_lapSession.value = currentSession` 死码）恰好
在 A38 要改造的首样本分支内，顺手清理零额外代价。backlog 少一条 🔴 pending，
未来若 `_lapSession` 换成 `SharedFlow` 不会有"每帧都 emit"的 bug 引爆点。

### 3. A21：engine 入口 ts 单调守卫 + `dropWhile` 改 `filter`

两处改动：

**3.1 `processSample` 入口 ts 单调守卫（A38 的兜底，对比基准用 `previousSample.timestampMillis`）**：

```kotlin
AFTER (在 A19 守卫之后):
// A21 深度防御：bridge 层若被绕过或重构，engine 兜底拦 ts 回跳
// 对比基准用 previousSample（方法参数），而非 session.samples.lastOrNull()：
// 1. engine 契约就是"对比 previous 和 current"，守卫直接对应契约；
// 2. previousSample 是参数永远非空，无需 null 兜底；
// 3. 未来若调用方让 previousSample 指向 session.samples 之外的历史帧，
//    用 samples.last 作为基准会对比错位；用 previousSample 永远正确。
// 4. 与 A38（bridge 层 current < previous 拦截）语义对称。
if (currentSample.timestampMillis < previousSample.timestampMillis) {
    FileLogger.d(TAG, "processSample: ts regression, drop prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}")
    return session
}
```

**3.2 `dropWhile` → `filter` 严格语义**：

```kotlin
BEFORE:
crossingEvents = updatedEvents.dropWhile { it.timestampMillis < activeLap.startedAtMillis }

AFTER:
crossingEvents = updatedEvents.filter { it.timestampMillis >= activeLap.startedAtMillis }
```

`filter` 逐个元素判定，彻底消除"只检查到第一个满足的元素"的单调假设。

## Impact

### 协议与数据模型

- **不改** `LapSession` / `LapSessionStatus` / `CrossingEvent` / `LapRecord` 公共数据模型
- **不改** `LapTimingEngine.processSample` 签名（参数列表、返回类型都保持不变）
- **不改** `TestSessionViewModel.bridgeGpsToLapTiming` 签名（仍是 private fun）

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| engine | `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` | `processSample` 入口加 A19 白名单 + A21.1 ts 单调守卫（基准 `previousSample.timestampMillis`）；`handleStartFinishCrossing` 第 136 行 `dropWhile` → `filter` |
| bridge | `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` | `bridgeGpsToLapTiming` 三段式改造（首样本 / ts 回跳 / 正常推进）+ 顺手清理 A34 死码 |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt` | 新增 A19（白名单 5 种状态）+ A21（ts 回跳兜底 + dropWhile→filter 严格语义）回归 |
| 测试 | `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt` | 新增 A38（首样本 + ts 回跳 + 回跳后恢复）回归 3 条 |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| `processSample(finishedSession / cancelledSession / idleSession, ...)` | 静默改写 status → Recording，`samples` 无上限增长 | 白名单拦下，返回原 session 不动 |
| `processSample(未来新增状态 session)` | 黑名单方案默认放行 → 同 Finished 同样的静默改写风险 | 白名单默认拦下，强制实施方显式决定是否接受 |
| bridge 收到 ts 回跳帧（已同步） | 继续喂 engine → detector dtSeconds 符号反转 → 判圈错乱 | 整帧丢弃 + 日志，`lastLapGpsSample` **保持前帧** |
| bridge 首样本帧 | `lastLapGpsSample = currentSample`（无条件，正确）+ `_lapSession.value = currentSession`（死码） | `lastLapGpsSample = currentSample` 保留 + 死码删除（A34） |
| engine 直接被喂 ts 回跳 sample（绕过 bridge） | 事件归属跨圈错乱（`dropWhile` 依赖单调） | 返回原 session 不动（对比基准用 `previousSample.ts`） |
| 闭圈时 crossingEvents 裁剪 | `dropWhile` 只检查到第一个满足 ts >= startedAt 的元素 | `filter` 逐个判定，严格语义 |
| 非异常路径（正常顺序、正常状态） | 不变 | 不变（所有新增守卫都是 early-return 保护，happy path 零额外分支） |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| A19 白名单太严，把 `Ready` 状态也拦了导致起圈失败 | 白名单显式放行 `Ready` + `Recording`；新增测试覆盖 Ready 首次起圈 + Recording 推进两条正向路径 |
| A21 engine 入口 ts 守卫用 `previousSample.timestampMillis` 作为基准 | previousSample 是方法参数永远非空，无需 null 兜底；与 engine 契约"对比 previous 和 current"一致；首次起圈 samples 空也不误拦（不依赖 samples） |
| A38 三段式改造首样本分支若漏赋 `lastLapGpsSample` 会卡死起圈路径 | 首样本分支显式赋值（段 1）+ 回归测试 `bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame` 锁定 |
| A34 顺手清理的死码删除破坏现有行为 | StateFlow equality 保护让 `_lapSession.value = currentSession` 对同引用不 emit，删除纯粹改 dead code；现有 `EndToEndLapTimingContractTest` 等回归测试会继续通过 |
| 双层守卫重复日志 | bridge 层日志带 `bridgeGpsToLapTiming: ts regression`，engine 层日志带 `processSample: ts regression`，便于追溯"污染在哪层被拦" |

### 回归保护要求

每个 Requirement 必须有**硬区分 v1/v2 行为**的测试（按用户偏好"测试断言路径级"）：

- **A19 × 5 条**（白名单枚举矩阵）：
  - `processSample_onFinishedSession_returnsUnchanged` — v1 samples 增长；v2 不变
  - `processSample_onCancelledSession_returnsUnchanged`
  - `processSample_onIdleSession_returnsUnchanged`
  - `processSample_onReadySession_acceptsSampleAndStartsLap`（正向：白名单放行，起圈路径）
  - `processSample_onRecordingSession_acceptsSampleAndAdvances`（正向：白名单放行，推进路径）
- **A21-1** `processSample_outOfOrderCrossingEvents_doNotLeakAcrossLaps`：
  - v1：上一圈 LapRecord.crossingEvents 含 ts < startedAt 的老事件（dropWhile 依赖单调）
  - v2：LapRecord.crossingEvents 严格 >= startedAt（`filter` 逐元素判定）
- **A21-2** `processSample_timestampRegressionSample_returnsUnchanged`：
  - 构造 `previousSample.ts = 500` + `currentSample.ts = 400` → v2 返回原 session，samples / crossingEvents 不增长
- **A21-3** `processSample_firstSampleOnEmptySession_noRegressionCheckApplies`：
  - 首次起圈（session.samples 空）时对比 `previousSample.timestampMillis`（方法参数，永远非空），不应因 session 空而误拦
- **A38 × 3 条**：
  - `bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame`：首样本早退后，下一帧能以首样本为 `previousSample` 正常起圈
  - `bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`：两帧 isTimeSynced=true 第二帧 ts 小于第一帧 → v1 喂进 engine；v2 整帧丢弃，`lapSession.samples` 不增长，`lastLapGpsSample` 保持第一帧
  - `bridgeGpsToLapTiming_afterRegressionDropped_nextForwardSampleIsProcessedAgainstPreviousFrame`：回跳帧被丢弃后，第三帧若 ts 前进，engine 以回跳**前**的帧为 previousSample 处理（而非以回跳帧为 previousSample）

## Alternatives

### A：只修 A38，省掉 A21 的 engine 入口守卫

**拒绝理由**：对抗 review § 1.14 明确"`bridgeGpsToLapTiming` 入口 + engine 入口两处
都加"（双层防御）。只修一层让 engine 契约漏掉，后续有人直接喂 engine（单元测试、
不同 ViewModel、重构）就还会踩坑。且 A21 的 `dropWhile → filter` 是**无前提改进**
（即使 ts 守卫不加，filter 严格语义也比 dropWhile 更防御），没有理由不改。

### B：A19 守卫里也拦 `Ready`，强制必须 `Recording`

**拒绝理由**：`Ready` 是"会话已创建但还没首次过起点线"的合法状态，engine 必须
接受第一帧 sample 并在首次起终点过线时把状态推到 Recording。拦 Ready 等于
堵死 `handleStartFinishCrossing` 里 `session.activeLap == null` 的起圈分支。

### C：A19 守卫用黑名单（拦 Finished/Cancelled/Idle，放行其他）

**拒绝理由**：黑名单是"开放默认不安全"反模式。`LapSessionStatus` 未来新增枚举
（如 `Paused`）会**默认被放行**，等于回到 A19 原问题——"engine 契约对未知状态
失守"。白名单（只放行 `Ready / Recording`）让未来新增状态默认被拦，强制
实施方显式决定是否接受，防御面严格更广。

### D：A21 engine 入口守卫用 `session.samples.lastOrNull()?.timestampMillis` 作基准

**拒绝理由**：engine 契约本身是"对比方法参数 `previousSample` 和 `currentSample`"，
守卫应该直接对应该契约。`session.samples` 是历史样本序列，理论上与 `previousSample`
同步但不是强保证：若未来有调用方让 `previousSample` 指向 `session.samples`
之外的帧（直接传历史帧、单元测试构造任意 session），对比基准会错位。用
`previousSample.timestampMillis` 是"参数即契约"的最简表达，且与 A38 的 bridge
层守卫语义对称（两层都比 `current < previous`）。

### E：合并到后续判圈契约战役（A20/A32）一起做

**拒绝理由**：A20（多门同帧）和 A32（闭圈帧归属）触及判圈核心算法，需要先拍
契约再动代码，风险远大于入口守卫。入口守卫属于"纯防御层加固"，和判圈契约
解耦；先闭环防御层可以降低后续契约改造的回归难度（有更严的测试锁定基线）。

### F：A34 死码不顺手清理，独立追踪

**拒绝理由**：A34 恰好在 A38 改造的首样本分支内，改动窗口天然交汇，"顺手清理
1 行 dead code"的边际成本为零；反之保留会让 backlog 多一条 🔴 pending，且
未来若 `_lapSession` 换成 `SharedFlow` 会引爆"每帧都 emit"bug。评审方接受
此交汇点的合并处理（见 review § 五）。

## Non-goals

- 不改 `GateCrossingDetector` 内部投影 / passDirection 算法（战役 B 已闭环）
- 不改 `GpsDataFilter` / `RaceChronoParser`（战役 A/C filter 已闭环）
- **不**补齐 A33（`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 未断言 `qualityFlags = listOf(IncompleteSectors)`）—— 属于"历史测试断言补齐"与入口守卫在代码位置和语义上独立；战役 C engine 夯实的下一个 change 处理判圈契约（A20/A32）时一并覆盖，避免本 change scope 扩散
- 不做多门同帧处理（A20，下一个 change）
- 不改闭圈帧归属契约 / sampleStartIndex（A32，下一个 change）
- 不动 `LapSessionStatus` 枚举值（不新增状态；白名单本身是"拒绝未知状态"，不是引入新状态）
- 不把 `bridgeGpsToLapTiming` 改成 suspend（协程迁移属于 A37 战役 F 性能）
- 不接入 `AnomalyDetector` 做异常原因分类（A30 独立战役）
