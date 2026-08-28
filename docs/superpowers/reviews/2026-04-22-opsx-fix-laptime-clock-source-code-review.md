# Code Review：`fix-laptime-clock-source-integrity` v2 实施（2026-04-22）

> 被评审对象：对方按照
> `docs/superpowers/reviews/2026-04-22-opsx-fix-laptime-clock-source-review.md`
> 的 P0/P1/P2 清单完成的代码落地（未 commit，`git status` 可见）。
>
> 评审范围：代码实现 + 新增/变更测试。Spec 和 proposal 本身已由 `openspec-chinese validate --strict` 通过，本文不重复 spec 评审。
>
> 评审结论：**整体高质量，但有 1 条 🔴 与 3 条 🟡 需处理；其中 🔴 是本轮 spec 的一处 Scenario 遗漏，实施方严格按 spec 执行，漏洞责任在上一轮评审（我）。需补 spec + 代码 + 测试三位一体修复，然后才能合流。**

---

## 零、总体落地情况

### P0/P1/P2 清单核对

| 项 | 要求 | 实施位置 | 状态 |
|---|---|---|---|
| P0.1 | parser 未同步写 sentinel `Long.MIN_VALUE`，不 fallback 本地时钟 | `RaceChronoParser.kt:266-274` | ✅ 落地 + 测试覆盖 |
| P0.2 | 分层守卫（filter / preTriggerBuffer / bridge / launchStatus） | `GpsDataFilter.kt:35-50`、`TestSessionViewModel.kt:120,206,218,319,388` | 🔴 **Running 分支遗漏**（见 C.1） |
| P0.3 | `GpsSample` 不加 `isTimeSynced` 字段 | `GpsSample.kt` 未改 | ✅ 按要求未加 |
| P1.4 | 失联恢复时 `lastLapGpsSample = null` 重置前驱 | `TestSessionViewModel.kt:319-323` | ✅ 落地 + 测试覆盖（E2E 8.5） |
| P1.5 | `LapQualityFlag.ProtocolDesyncGap` + engine 闭圈扫描 `>200ms` gap | `LapQualityFlag.kt:8`、`LapTimingEngine.kt:94-104` | ✅ 落地，但**阈值硬编码**（见 C.2） |
| P2.5 | simulator 非 replay 走会话相对单调时钟 + replay 缺 sample 抛异常 | `GpsDataGenerator.kt:25,49,136-145` | ✅ 落地，注入式 `clock` 很干净 |
| P2.5 | 跨小时 `dateAndHour` 递增 | `GpsDataGenerator.kt:162-181`（靠 `Calendar.getInstance()` 自动推进） | ✅ 逻辑正确（Non-goal 声明过于保守，实际支持） |
| P2.6 | 接收端不读真实日历字段 | 核对 `RaceChronoParser` 代码路径：未用 `Date.year/Calendar.get(YEAR)` 做业务判断 | ✅ 通过 |
| 新增 | `updateLaunchStatus` 改用 `SystemClock.elapsedRealtime` | `TestSessionViewModel.kt:101,120,388` | ✅ 落地 + 测试覆盖 |
| 新增 | 端到端契约测试（STATIC 20ms / REPLAY 5ms / 冷启动不开圈 / 短失联恢复 / ProtocolDesyncGap） | `EndToEndLapTimingContractTest.kt` 共 6 个用例 | ✅ 高质量，见下评 |

### 评审亮点（实施方自己加分的地方）

1. **字节码常量池扫描** (`EndToEndLapTimingContractTest.kt:449-468`)：证明 `GpsDataGenerator` 和 `LapTimingEngine` 不引用 `currentTimeMillis`。Mockito 无法 mock `java.lang.System`，这招用 `Class.getResourceAsStream` 读 .class 常量池扫 `"currentTimeMillis"` 字符串 —— 既稳定又不依赖 agent，是对 "核心链路不得读本地时钟" 契约的最硬断言。parser 因为 frequency / tracking 非时间戳路径仍保留 `System.currentTimeMillis()`，测试显式豁免 parser 类并在注释里写明豁免理由，边界非常清晰。

2. **Clock 注入** (`GpsDataGenerator.kt:25`)：把 `clock: () -> Long = { SystemClock.elapsedRealtime() }` 作为构造参数注入，生产默认值、测试注入 `FakeClock`。既保持了生产行为不变，又让端到端测试能严格控制时钟步进。

3. **测试覆盖范围**：E2EPipeline 甚至用反射改写 `syncCounter` 来模拟"协议失联"（`EndToEndLapTimingContractTest.kt:539-556`），断言"失联 3 帧即使位置跨 gate 也不伪造开圈" —— 这条对应 review P1.4 的核心不变量，设计精准。

---

## 一、🔴 C.1：`processFilteredData` 的 `TestState.Running` 分支漏守卫（**必修**）

### 证据

`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:214-233`

```kotlin
private fun processFilteredData(filteredData: FilteredGpsData) {
    when (val state = _testState.value) {
        is TestState.Preparing -> {
            // Requirement 3.5 (a)：未同步帧不触发测试转 Running
            if (!filteredData.raw.isTimeSynced) return   // ✅ 已守卫
            if (_countdownSeconds.value == 0) {
                if (checkTriggerCondition(filteredData, state.template)) {
                    startTest(state.template, state.carModel, filteredData)
                }
            }
        }
        is TestState.Running -> {
            state.session.addFilteredDataPoint(filteredData)    // ⚠️ 无守卫！
            if (state.session.template.shouldEnd(filteredData.raw)) {
                finishTest(state.session)
            }
        }
        else -> { /* 其他状态不处理 */ }
    }
}
```

### 污染路径

1. 测试已启动（Preparing → Running），session.dataPoints 正常累积。
2. **Running 期间突然失联**（`isTimeSynced` 转 false，例如信号抖动、time 包丢包）。
3. Filter 在 isTimeSynced=false 时返回"零加速度快照"（见 `GpsDataFilter.kt:36-49`）：
   - `acceleration = 0.0`
   - `confidence = 0.0`
   - `timestamp = raw.timestamp` = **sentinel `Long.MIN_VALUE`**
4. `processFilteredData` 的 Running 分支**无守卫**：
   - `state.session.addFilteredDataPoint(filteredData)` 把这个污染快照追加进 `TestSession.dataPoints`。
   - `TestSession.addFilteredDataPoint` 用 `filteredData.timestamp - session.startTime` 计算 `elapsedTime`（`TestModels.kt:140-146`）。
   - `elapsedTime = Long.MIN_VALUE - session.startTime` → **极大的负数或正数（溢出）**，被塞进 `DataPoint.elapsedTime`。
5. 测试结束后 `CalculateResultUseCase` 基于 `dataPoints` 算 0-100 用时等结果 → **结果数据被污染**。

### 根源分析（spec 漏洞）

上一轮 review `2026-04-22-opsx-fix-laptime-clock-source-review.md` 的第 **三** 节 Scenario 3.5.3 原文：

```
Scenario 3.5.3：未同步时不开始新测试
- WHEN `_testState.value is TestState.Preparing`
- AND `filteredData.raw.isTimeSynced == false`
- THEN `checkTriggerCondition` 不被调用，或直接返回 false
```

**只覆盖 Preparing，未覆盖 Running**。实施方严格按 Scenario 执行，漏洞责任在 spec（我）。

### 要求修复

**Spec 侧**：`openspec/changes/fix-laptime-clock-source-integrity/specs/laptime-clock-source/spec.md` 的 Scenario 3.5.3 扩展：

```
Scenario 3.5.3：未同步时 Preparing/Running 两分支都不消费污染帧
- GIVEN filteredData.raw.isTimeSynced == false
- WHEN _testState.value is TestState.Preparing
  - THEN checkTriggerCondition 不被调用
- WHEN _testState.value is TestState.Running
  - THEN state.session.addFilteredDataPoint 不被调用
  - AND template.shouldEnd 不被判定
- 任一状态下，未同步帧不得改变 session 的 dataPoints / 状态转移
```

**代码侧**：`TestSessionViewModel.kt:225-230` 加守卫：

```kotlin
is TestState.Running -> {
    if (!filteredData.raw.isTimeSynced) return   // ← 新增
    state.session.addFilteredDataPoint(filteredData)
    if (state.session.template.shouldEnd(filteredData.raw)) {
        finishTest(state.session)
    }
}
```

**测试侧**：`TestSessionViewModelTrackLapTest` 已有 `processFilteredData_preparingPhase_doesNotTriggerWhenUnsynced`（line 504-537），补对称的：

```kotlin
@Test
fun processFilteredData_runningPhase_ignoresUnsyncedFrames() = runTest {
    // 1. enterSmartLaunch + 提供足够同步帧让测试转入 Running
    // 2. 期间喂 5 帧 isTimeSynced=false（含污染 sentinel timestamp 与 zero acceleration）
    // 3. 断言 session.dataPoints.size 不增长
    // 4. 断言 _testState.value 仍是 Running（未错误 finishTest）
}
```

### 处置级别

**P0 阻塞合流**。这不是"最小侵入"的代价问题，是直接吃进污染数据导致测试结果错。改动量仅 1 行代码 + 1 个测试用例。

---

## 二、🟡 C.2：`ProtocolDesyncGap` 200ms 阈值硬编码（建议改）

### 证据

`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt:93-96`

```kotlin
val trajectory = updatedSamples.drop(activeLap.sampleStartIndex)
val hasDesyncGap = trajectory.zipWithNext().any { (a, b) ->
    (b.timestampMillis - a.timestampMillis) > 200L
}
```

### 问题

- 阈值 `200L` 基于"25Hz × 5 帧 = 200ms 丢失视为异常"。
- Replay 5Hz 正常采样间隔**就是 200ms**。真实回放样本 ts 差可能是 199、200、201（浮点舍入、replay JSON 精度）。`> 200L` 对 201 触发、对 199/200 不触发 —— **边界抖动**。
- Simulator `setFrequency(5)` 或其它低频模式，同样的问题。
- 当前生产只跑 25Hz + 5Hz replay，5Hz 路径是否真的会闭圈走到 engine？查 `bridgeGpsToLapTiming` 是按 `GpsData` 逐帧调 engine，5Hz 会进 —— 所以这个坑真实存在。

### 要求

选一：

**方案 A（推荐，轻量）**：Spec Requirement "ProtocolDesyncGap" 明确声明 "阈值针对 25Hz 设计，低频采样可能假阳性；检测目的是粗粒度质量提示，非精确质量度量"，代码加注释。代价：Replay 5Hz 场景可能偶报 flag。

**方案 B（更好）**：把阈值改成 "相对采样间隔倍数"：
- `LapTimingEngine` 构造增加 `expectedIntervalMillis: Long = 40L` 参数；
- `GAP_THRESHOLD = expectedIntervalMillis * 5L`；
- `TestSessionViewModel` 按 ViewModel 已知频率传入。
- 缺点：engine 需要感知频率，增加耦合。

**方案 C（不动代码）**：不改，合流，backlog 单独开 change 处理。接受 replay 场景偶发 flag。

### 处置级别

🟡 不阻塞合流，但要在 spec 或 backlog 里留痕，否则回放调试时看到 flag 会引发新一轮排查。

---

## 三、🟡 C.3：Filter 恢复同步后首帧仍然用旧 `previousRaw` 算 acceleration（联动放大）

### 证据

`core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt:52-63`

```kotlin
// 1. 计算加速度
val acceleration = calculateAcceleration(raw)          // 用旧 previousRaw（失联前那帧）

// 2. 物理约束检查
val isAnomaly = isPhysicalConstraintViolation(raw)     // 同样用旧 previousRaw

// 2.5. 信号丢失重置检查
val dtFromPrevious = previousRaw?.let { (raw.timestamp - it.timestamp) / 1000.0 } ?: 0.0
if (dtFromPrevious > 0.2) {
    previousRaw = null
    previousPosition = null
}
```

### 问题

- 对抗复审 `2026-04-22-lap-timing-and-gps-adversarial-review.md` **2.4** 已报：重置发生在加速度/异常检测**之后**，导致信号丢失恢复的首帧用"旧 previousRaw + 超长 dt"算 `acceleration = dv / 超长秒`（稀释）和 `maxDelta = 90 × 超长秒`（巨大容差 → 非异常误判）。
- 本 change 明确 out-of-scope（只管时钟完整性，不管 filter 顺序）。
- **但它与 C.1 叠加放大**：
  - C.1 修复前：Running 期间失联恢复首帧 → filter 返回稀释 acceleration + isAnomaly=false → 被 Running 分支 `addFilteredDataPoint` 吃进 session.dataPoints。
  - C.1 修复后：Running 分支守卫只看 `raw.isTimeSynced`。恢复首帧 `isTimeSynced=true` → 仍然会进。守卫**挡不住**"用旧基准算的稀释 acceleration"。

### 要求

- 本 change 不改（保持 out-of-scope 边界）。
- 但必须在本次合流 notes 里标注："C.1 修复后，失联恢复首帧的 filter 稀释问题依然存在，由对抗复审 2.4 跟踪"。
- 建议下一个 change `fix-laptime-filter-reorder`（如果还没有）立刻排期。

### 处置级别

🟡 不阻塞本 change 合流，但要留痕。

---

## 四、🟡 C.4：`RaceChronoParser.isCurrentlyTimeSynced` 字段冗余

### 证据

`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:38-40, 266-274`

```kotlin
// 协议时间当前是否已对齐；由 parseGpsData 基于 syncBits 匹配结果刷新。
// 冷启动、syncBits 失配、reset() 后都必须为 false，下游看到 sentinel 才能守卫。
private var isCurrentlyTimeSynced: Boolean = false
...
// 在 parseGpsData 里：
if (reference != null && reference.syncBits == syncBits) {
    protocolTimestamp = reference.hourStartMillis + timeSinceHourStart
    isCurrentlyTimeSynced = true
} else {
    protocolTimestamp = Long.MIN_VALUE
    isCurrentlyTimeSynced = false
}
```

### 问题

- 该字段只在 `parseGpsData` 内部写 + 立即读 + 立即赋给 `GpsData.isTimeSynced`。**不跨方法使用**。
- 它不是 parser 持久状态（下一次 parseGpsData 调用会基于 `reference.syncBits == syncBits` 重新判定，不读旧值）。
- 结论：**字段冗余**，本质是局部变量。

### 要求

简化为局部变量：

```kotlin
val (protocolTimestamp, isTimeSynced) = if (reference != null && reference.syncBits == syncBits) {
    (reference.hourStartMillis + timeSinceHourStart) to true
} else {
    Long.MIN_VALUE to false
}
```

相应删掉 `isCurrentlyTimeSynced` 字段和 `reset()` 里对它的清零（line 59）。

### 处置级别

🟡 代码整洁度，不影响功能，可与 C.1 同批修或独立清理。

---

## 五、测试质量评审

| 测试 | 覆盖点 | 评价 |
|---|---|---|
| `EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta` | STATIC 10秒圈时 ≤20ms | ✅ 构造合理，fake clock 严格单调，通过 position swap 实现干净的"开圈-闭圈"事件 |
| `...replayMode_lapDurationMatchesReplayClock` | REPLAY 圈时 ≤5ms | ✅ 手工构造 11 个 replay sample 避开完整 tianfu JSON 依赖 |
| `...coldStartOnlyMainNoTimePacket_engineDoesNotStartLap` | 冷启动不开圈 | ✅ 显式断言 `timestamp == Long.MIN_VALUE` 与 `isTimeSynced == false`，守卫契约锁定 |
| `...shortTimeDesyncRecoversWithoutSpuriousCrossing` | 失联 3 帧跨 gate 不伪造开圈 | ✅ 用反射改 `syncCounter` 精准模拟协议失联，并断言 `activeLap` 始终 null — 精准验证 P1.4 |
| `...endToEndCoreClockSourceIntegrity_generatorAndEngineNotInvolveSystemClock` | 字节码扫描 | ✅ 非常硬的 contract test，防止未来再次引入 `System.currentTimeMillis` |
| `...lapWithProtocolDesyncGap_laprecordFlagged` | P1.5 的 flag | ✅ 完整跑通"开圈 → 失联 5 帧 → 恢复 10 帧 → 闭圈"，断言 flag 存在 |
| `TestSessionViewModelTrackLapTest.processFilteredData_preparingPhase_doesNotTriggerWhenUnsynced` | Preparing 守卫 | ✅ 构造 10 帧未同步样本验证守卫 |
| `...launchStatus_lastDataAgeUsesElapsedRealtime_notGpsTimestamp` | launchStatus 改用 elapsedRealtime | ✅ 反射读 `lastReceivedAtElapsed`，断言它 ≠ `gpsData.timestamp` |
| **缺** | **Running 分支守卫** | ❌ **需补** —— 见 C.1 |

### 测试覆盖盲区

- **无**：Running 期间失联 → session.dataPoints 不增长（C.1 必补）。
- **可选**：`GpsDataFilter` 在 isTimeSynced=true→false→true 切换后**内部窗口/previousRaw 是否正确冻结**的单测。当前 `GpsDataFilter.process` 的分层守卫依赖"未同步时不更新内部状态"，需要独立测试锁定此契约。

---

## 六、Spec 自身修订

### 6.1 Scenario 3.5.3 扩展（本 review 第一节）

必须补上 Running 分支。spec 文件位置：`openspec/changes/fix-laptime-clock-source-integrity/specs/laptime-clock-source/spec.md`。

### 6.2 Non-goals 过度保守

Proposal 声明 "仅支持 < 1 小时会话" 作为 Non-goal。但实际 `GpsDataGenerator.generateGpsTimeData` 依赖 `Calendar.getInstance().apply { timeInMillis = currentTimestampMillis() }`，而 Calendar 自动推进 hour/day，**跨小时 / 跨天都能正确编码**。Non-goal 无限制实际代码表现，建议删除或改写为 "`dateAndHour` 编码依赖 Calendar 时区推进行为，yearOffset 固定 0；真实日历语义不成立，接收端不得反查日期字段"。

---

## 七、合流决策

| 条件 | 状态 |
|---|---|
| P0 全部按 review 要求实施 | 🔴 C.1 未覆盖（责任在 spec 漏洞） |
| P1 全部按 review 要求实施 | ✅ 完成 |
| P2 全部按 review 要求实施 | ✅ 完成 |
| 新增测试覆盖 spec 所有 Scenario | 🔴 Scenario 3.5.3 扩展后需补 Running 测试 |
| `openspec-chinese validate --strict` 通过 | ✅ （对方声明） |
| 不引入新 `System.currentTimeMillis` 依赖 | ✅ 字节码扫描锁死 |

### 合流前必做（P0）

1. **Spec**：Scenario 3.5.3 扩展覆盖 Running 分支（本 review 第一节文本）。
2. **代码**：`TestSessionViewModel.kt:225-230` 添加 `if (!filteredData.raw.isTimeSynced) return`。
3. **测试**：`TestSessionViewModelTrackLapTest` 新增 `processFilteredData_runningPhase_ignoresUnsyncedFrames`。
4. **Proposal**：Non-goal 修订（第 6.2 节），或明确声明保留。

### 合流后可跟进（P2）

5. C.2：`ProtocolDesyncGap` 阈值改成可配置或在 spec 声明边界。
6. C.4：`isCurrentlyTimeSynced` 字段改为局部变量。
7. C.3：对抗复审 2.4 的 filter 顺序问题，新开 change `fix-laptime-filter-reorder`。

### 合流后必做

- 更新 `project_laptime_dual_clock_pollution.md` 记忆条目状态 → "已归档"，或删除并改为指向 `openspec/specs/laptime-clock-source/spec.md`。
- 对抗复审报告 `2026-04-22-lap-timing-and-gps-adversarial-review.md` 第八节的 P0 (8.1~8.6) 标记为"已闭环 by change fix-laptime-clock-source-integrity"。

---

## 八、总体评语

**实施质量远高于预期**。对方不仅严格按 spec 落地，还主动引入：

- 字节码常量池扫描作为终极契约 —— 防御性极强，阻断未来回归的最硬手段。
- `clock` 注入 + `FakeClock` —— 端到端时间语义可测。
- 反射改 `syncCounter` 模拟协议失联 —— 精准锁定 P1.4 的"失联不伪造穿线"不变量。

唯一的 🔴 C.1 是我上一轮 review 的 Scenario 漏洞，不是实施方的执行问题。**Scenario 3.5.3 只写 Preparing 不写 Running 是我的错**，实施方严格执行的结果必然漏这条路径。这正好说明"spec 的覆盖面直接决定实施的覆盖面" —— 以后写 Scenario 要逐个状态穷举。

**建议 C.1 修完 + 测试补完后直接合流**。Spec 侧的 Scenario 3.5.3 和 Non-goal 修订可以在同一个 PR 里一起带掉。

---

## 九、一句话决策

🔴 **暂不合流**：补完 C.1（spec + 代码 + 测试，共 3 处改动）后立即合流；C.2 / C.3 / C.4 不阻塞，进入 backlog。
