# Proposal Review：`fix-lap-timing-engine-entry-hardening`（2026-04-24）

> 被评审对象：
> ```
> openspec/changes/fix-lap-timing-engine-entry-hardening/
> ├── proposal.md        （Why / What / Impact / Alternatives / Non-goals）
> └── specs/lap-timing-engine/    （空，待写 spec.md）
> ```
>
> 评审立场：战役 C engine 入口夯实，闭环 attack-backlog A19 / A21 / A38。按协作契约，proposal / spec 层**在代码落地前**把关。
>
> 产出用途：发给对方，决定 proposal / spec 能否定稿走 `/opsx:apply`。本轮**不是核销代码**，是核销设计。

---

## 零、总体评价表

| 设计项 | 评审结论 | 必须修改 |
|---|---|---|
| 总体方向：入口守卫双层防御（bridge + engine） | ✅ 对 | — |
| A19 守卫用黑名单枚举（Finished/Cancelled/Idle） | 🔴 **反对** | 改白名单（只放行 Ready/Recording），未来新增状态默认被拦 |
| A38 守卫顺序调整描述 | 🔴 **语义缺失** | 必须明确首样本分支仍要赋 `lastLapGpsSample`，否则起圈永远失败 |
| A21 engine 入口 ts 守卫对比基准（`session.samples.lastOrNull()`） | 🟡 建议改 | 用 `previousSample.timestampMillis` 更直接，避免"samples 与 previousSample 不同步"理论风险 |
| `dropWhile → filter` 严格语义 | ✅ 通过 | 无副作用改进，无条件接受 |
| Non-goals 未声明 A33 | 🟡 建议补 | 显式说明"A33 测试断言补齐不在本 change"，避免误解 |
| A34 顺手清理决策 | 🟡 建议补 | A34 和 A38 在同一函数（`bridgeGpsToLapTiming` 首样本分支），决策"顺手清理"或"明确不碰" |
| spec.md + tasks.md 未写 | ⚪ 阻塞 | 本文列出 Spec 必须覆盖的 Scenario 清单，供对方起草 |

**处置建议**：
- 2 条 🔴 先改 proposal，不改会在实施时产生可预见的 bug（A19）或破坏起圈路径（A38）
- 3 条 🟡 按需采纳；对 🟡 的任何拒绝请给出技术反驳，评审方接受后归档
- spec.md + tasks.md 按本文第四节的清单起草，起草后再审一轮

---

## 一、🔴 A19 守卫改白名单（必改）

### Proposal 写法（黑名单）

```kotlin
if (session.status == LapSessionStatus.Finished ||
    session.status == LapSessionStatus.Cancelled ||
    session.status == LapSessionStatus.Idle) {
    return session
}
```

### 对抗分析

- `LapSessionStatus` 当前枚举值：`Idle / Ready / Recording / Finished / Cancelled`
- 黑名单拦 3 个，放行 `Ready / Recording`
- **未来新增状态**（例如 `Paused` / `Interrupted` / `Pending`）会**默认被放行** —— 如果实施方忘了更新 engine 守卫，新状态下 engine 会静默处理样本

**这是经典的"开放默认不安全"反模式**。A19 的 Why 本来就是"engine 契约防御到位"，守卫必须能抵抗"未来添加枚举成员被遗忘"的场景。黑名单违背此精神。

### 要求改白名单

```kotlin
// A19 入口守卫：只放行 Ready / Recording 两个应接受样本的状态。
// 白名单语义：未来新增 LapSessionStatus 枚举值默认被拦，除非显式决定接受。
if (session.status != LapSessionStatus.Ready &&
    session.status != LapSessionStatus.Recording) {
    return session
}
```

或更紧凑：
```kotlin
if (session.status !in setOf(LapSessionStatus.Ready, LapSessionStatus.Recording)) {
    return session
}
```

**语义等价**（对当前 5 个枚举），**防御面更广**（对未来枚举）。Proposal Alternatives 已经说 "`Ready` / `Recording` 是唯二应接受样本的状态" —— 白名单正是这句话的精确表达。

### Spec Scenario 要求

必须至少 4 条 Scenario 覆盖枚举矩阵：

1. `processSample_onFinishedSession_returnsUnchanged`
2. `processSample_onCancelledSession_returnsUnchanged`
3. `processSample_onIdleSession_returnsUnchanged`
4. `processSample_onReadySession_acceptsSampleAndStartsLap`（起圈路径正反）
5. `processSample_onRecordingSession_acceptsSampleAndAdvances`

前 3 条用 v1/v2 的 `samples.size` 差异硬区分：v1 增长、v2 不变。

---

## 二、🔴 A38 守卫顺序调整的语义缺失（必改）

### Proposal 写法

```kotlin
BEFORE:
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession
    return
}

AFTER:
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    _lapSession.value = currentSession
    return
}
// A38 时间单调守卫：已同步帧之间的 ts 回跳帧整帧丢弃，不更新 lastLapGpsSample
if (currentSample.timestampMillis < previousSample.timestampMillis) {
    return
}
```

> 当前代码顺序是"先赋 lastLapGpsSample，再 previousSample == null 判定"，需要调整为"先 previousSample == null 判定 → ts 单调守卫 → 最后赋 lastLapGpsSample"。

### 对抗分析

当前真实代码：

```kotlin
lastLapGpsSample = currentSample       // (A) 第 340 行，**无条件**先赋值
if (previousSample == null || ...) {
    _lapSession.value = currentSession
    return                             // (B) 首样本早退
}
// engine 调用路径
```

当前语义：**所有路径**（包括首样本早退）都让 `lastLapGpsSample = currentSample`，为下一帧做基准。

Proposal 改为"最后赋 `lastLapGpsSample`"，如果 **首样本分支不赋值**，下一帧 `previousSample` 取到 null → 又走首样本早退 → **永远起不了圈**。

Proposal 描述模糊 —— "最后赋 lastLapGpsSample" 一刀切的话会破坏起圈路径。

### 要求精确写法

spec 必须明确三段：

```kotlin
AFTER (精确三段)：

// 1. 首样本守卫：early return 但**仍赋 lastLapGpsSample**，为下一帧做基准
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    lastLapGpsSample = currentSample
    _lapSession.value = currentSession
    return
}

// 2. A38 时间单调守卫：回跳帧整帧丢弃 + **不**更新 lastLapGpsSample
//    保持前帧作为下一帧的 previousSample，避免 ts 回跳污染基准
if (currentSample.timestampMillis < previousSample.timestampMillis) {
    FileLogger.d(TAG, "bridgeGpsToLapTiming: ts regression, drop sample prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}")
    return
}

// 3. 正常路径：更新 lastLapGpsSample + 喂 engine
lastLapGpsSample = currentSample
val updatedSession = lapTimingEngine.processSample(...)
...
```

三个分支的 `lastLapGpsSample` 语义分别是：

| 分支 | 条件 | `lastLapGpsSample` 动作 | 为什么 |
|---|---|---|---|
| 首样本 | `previousSample == null` | **赋 currentSample** | 为下一帧准备 previousSample，不赋会卡死起圈 |
| ts 回跳 | `current.ts < previous.ts` | **不赋**（保持前帧） | 回跳帧是污染源，不让它成为下一帧的基准 |
| 正常 | 以上都不成立 | **赋 currentSample** | 推进 |

与 A13 "异常帧不更新 previousRaw" 模式一致，语义必须精确写进 spec。

### Spec Scenario 要求

至少 3 条：

1. `bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame`：首样本早退后，下一帧能正常起圈
2. `bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`：v1 喂进 engine 污染；v2 整帧丢弃，`lapSession.samples` 不增长，`lastLapGpsSample` 保持前帧
3. `bridgeGpsToLapTiming_afterRegressionDropped_nextForwardSampleIsProcessedAgainstPreviousFrame`：回跳后的下一帧若 ts 前进，engine 以回跳**前**的帧为 previousSample 处理（而非以回跳帧为 previousSample）

---

## 三、🟡 A21 engine 入口守卫对比基准（建议改）

### Proposal 写法

```kotlin
val lastSampleTs = session.samples.lastOrNull()?.timestampMillis
if (lastSampleTs != null && currentSample.timestampMillis < lastSampleTs) {
    return session
}
```

### 对抗分析

守卫用 `session.samples.lastOrNull()` 作为基准。**但 engine 的实际基准是 `previousSample`（签名参数）**，两者可能不同步：

- 典型调用链下，`previousSample == session.samples.last()`
- 但"绕过 bridge / 重构后的调用方" 可能让 `previousSample` 指向 `session.samples` 之外的历史帧
- 守卫比 `samples.last.ts`，engine 用 `previousSample.ts` → **对比基准错位**

更直接的语义：**engine 的契约是"current 必须 >= previous"**，守卫应直接用 `previousSample.timestampMillis`：

```kotlin
if (currentSample.timestampMillis < previousSample.timestampMillis) {
    return session
}
```

### 优势

1. **语义最自然**：engine 只负责"对比 previous 和 current"，不依赖 session 历史
2. **首次起圈自动正确**：previousSample 是方法参数，永远非空（不需要 `lastSampleTs != null` 兜底）
3. **与 A38 语义对称**：bridge 层 `current < previous` 拦截，engine 层 `current < previous` 兜底 —— 同一契约表达两次

### 拒绝理由空间

如果对方坚持用 `session.samples.lastOrNull()`，需要给出反证："为什么 `session.samples.last.ts` 比 `previousSample.ts` 更合理"。评审方接受技术理由即可。

### Spec Scenario 要求

`processSample_timestampRegressionSample_returnsUnchanged`：
- 构造 `previousSample.ts = 500`, `currentSample.ts = 400`
- 断言：返回原 session（引用相等或字段相等），`session.samples.size` 不增长，`session.crossingEvents.size` 不增长

---

## 四、🟡 Non-goals 声明 A33（建议补）

### 背景

昨晚评审方建议 "A19 / A21 / A33 / A38" 四条一批。Proposal 只做 A19 / A21 / A38 三条，A33 被拆到后续 change。

拆分是**合理的**：A33（`LapTimingEngineTest.processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 未断言 `qualityFlags`）是"历史测试断言补齐"，与入口守卫在代码位置和语义上独立。

### 要求

Non-goals 显式添加一条：

> - **不**补齐 A33（`LapTimingEngineTest` `IncompleteSectors` 断言缺失）—— 属于"测试断言强度"问题，与入口守卫解耦；战役 C engine 夯实的下一个 change 专门处理判圈契约（A20/A32）时一并覆盖

避免评审方/实施方/未来翻阅者误以为"engine 守卫 change 已经覆盖 A33"。

---

## 五、🟡 A34 顺手清理决策（建议补）

### 背景

backlog **A34**：`bridgeGpsToLapTiming` 首样本分支冗余赋值 `_lapSession.value = currentSession`（P2 清理项，StateFlow equality 保护当前无害，但若未来换 SharedFlow 会爆）

### 决策点

A34 和 A38 都改 `bridgeGpsToLapTiming`，而且**正好在同一个首样本分支**。选择：

**方案 A（顺手清理）**：
```kotlin
if (previousSample == null || currentSample.timestampMillis <= 0L) {
    lastLapGpsSample = currentSample
    // A34: 删除 _lapSession.value = currentSession（StateFlow 相同引用不 emit，纯死码）
    return
}
```
- 代价：改动范围稍大 1 行
- 收益：A34 一并闭环，backlog 少一条

**方案 B（明确不碰）**：
- 保留现状，Non-goals 显式声明"不清理 A34（独立追踪）"
- 代价：A34 继续留在 🔴 pending
- 收益：本 change scope 最小

两种都可以，**但必须显式选**。不写会让评审方不清楚对方是否意识到这个交汇点。

---

## 六、Spec + Tasks 起草清单

### spec.md 必须覆盖的 Requirement

按建议 4 个 Requirement 组织：

**Requirement 1：engine 入口 `LapSessionStatus` 白名单守卫**
- Scenario 1.1 `processSample_onFinishedSession_returnsUnchanged`（硬区分 v1/v2）
- Scenario 1.2 `processSample_onCancelledSession_returnsUnchanged`
- Scenario 1.3 `processSample_onIdleSession_returnsUnchanged`
- Scenario 1.4 `processSample_onReadySession_acceptsSampleAndStartsLap`
- Scenario 1.5 `processSample_onRecordingSession_acceptsSampleAndAdvances`

**Requirement 2：engine 入口 ts 单调守卫**
- Scenario 2.1 `processSample_timestampRegressionSample_returnsUnchanged`
- Scenario 2.2 `processSample_firstSampleOnEmptySession_noRegressionCheckApplies`（首次起圈放行）

**Requirement 3：`crossingEvents` 闭圈裁剪改严格 `filter` 语义**
- Scenario 3.1 `lapClosed_crossingEventsStrictlyAboveStartedAt`（所有事件 ts >= startedAt）
- Scenario 3.2 `lapClosed_outOfOrderHistoricalEventIsFilteredOut`（构造一个 ts < startedAt 但排序在后的历史事件，验证被 filter 拒绝）

**Requirement 4：`bridgeGpsToLapTiming` 时间单调守卫 + 首样本分支 `lastLapGpsSample` 契约**
- Scenario 4.1 `bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame`
- Scenario 4.2 `bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp`
- Scenario 4.3 `bridgeGpsToLapTiming_afterRegressionDropped_nextForwardSampleIsProcessedAgainstPreviousFrame`

### tasks.md 建议结构

分 4 组，对应 4 个 Requirement。每组 3 项：spec 起草 → 代码实施 → 测试补齐。合流前门槛至少：

1. spec + proposal 双重审过，状态 🟢
2. 所有 Scenario 对应 Kotlin 测试全绿
3. `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*"` 全绿
4. `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModelTrackLapTest*"` 全绿
5. `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（战役 A 契约不被破坏）
6. `openspec-chinese validate fix-lap-timing-engine-entry-hardening --strict` 通过

---

## 七、流转建议

### 对方动作（按优先级）

1. **🔴 必改**：
   - A19 守卫改白名单（proposal § What 1.1 改写）
   - A38 顺序调整精确到三段式伪代码（proposal § What 2 改写）
2. **🟡 可接受/可反驳**：
   - A21 守卫基准改 `previousSample.timestampMillis`（附技术理由可保留原写法）
   - Non-goals 声明 A33
   - A34 顺手清理 vs 明确不碰 二选一
3. **⚪ 起草**：
   - `specs/lap-timing-engine/spec.md` 按本文第六节的 4 Requirement × N Scenario 起草
   - `tasks.md` 按 4 组 × 3 项起草

### 评审方动作

对方改完 proposal + 起草 spec + tasks 后：
- 评审方重审 proposal（必改项确认完成）
- 评审方审 spec（Scenario 是否按本文清单全覆盖 + 断言是否硬区分 v1/v2）
- 评审方审 tasks（合流门槛是否覆盖本文第六节清单）
- 通过后才能走 `/opsx:apply`

### 禁止动作

**不得在未完成 proposal 修订前动代码**。战役 A 的教训：Spec 漏覆盖 Running 分支，代码按漏的 Spec 落地后产生 A6（C.1）新问题。本 change 必须先把 Spec 写厚再动代码。

---

## 八、合流门槛清单（给对方对照）

| 项 | 状态 |
|---|---|
| proposal § What 1.1 A19 守卫改白名单 | ☐ |
| proposal § What 2 A38 顺序调整三段式伪代码 | ☐ |
| proposal § What 3.1 A21 守卫基准决策（改 or 反驳） | ☐ |
| proposal Non-goals 声明 A33 拆分 | ☐ |
| proposal Non-goals / What 2 声明 A34 顺手清理 or 不碰 | ☐ |
| `specs/lap-timing-engine/spec.md` 起草（4 Requirement） | ☐ |
| 至少 10 条 Scenario 覆盖（见第六节清单） | ☐ |
| `tasks.md` 起草 + 6 条合流门槛 | ☐ |
| 评审方 proposal 复审通过 | ☐ |
| 评审方 spec 复审通过 | ☐ |
| 评审方 tasks 复审通过 | ☐ |
| `openspec-chinese validate --strict` 通过 | ☐ |

全部打钩后才能走 `/opsx:apply` 动代码。

---

## 九、结语

Proposal 的**战略方向完全正确**（双层防御 + 入口守卫），写作质量高（Why/What/Alternatives/Non-goals 完整、有具体代码 diff、明确依赖关系）。挑出的 2 条 🔴 都不影响整体设计，只是"写法细节 → 实施时容易踩坑"。

A19 黑名单改白名单是 1 行代码、零风险升级；A38 顺序描述改三段式是纯文档精确化。两条都属于"proposal 阶段把关 → 代码阶段直接正确"的理想 case，正好验证"代码落地前就把关"这条纪律的价值。
