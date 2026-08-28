# OpenSpec Change Review：`fix-laptime-clock-source-integrity`（2026-04-22）

> 被评审对象：
> ```
> openspec/changes/fix-laptime-clock-source-integrity/
> ├── proposal.md                             （Why / What / Impact / Alternatives / Non-goals）
> ├── tasks.md                                （6 组任务 + 合流前 8 条硬门槛）
> └── specs/laptime-clock-source/spec.md      （5 个 Requirement，15+ Scenario）
> ```
>
> 评审立场：本 change 对应本报告 `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md` 第 **八** 节（发射端 + 接收端双端时间戳污染），用户实测反馈"圈速时间偏长"的 root cause 修复。
>
> 本文只评审 5 条设计决策 + 建议新增一条 Requirement 3.5。Proposal 的 Why/Impact/Non-goals 等结构本身不评审，默认合理。
>
> 产出用途：发给对方，作为"是否可以走 `/opsx:apply`"的决策依据。

---

## 零、总体评价表

| 决策 | 描述 | 评审结论 | 必须修改 |
|---|---|---|---|
| 1 | `GpsData.isTimeSynced: Boolean = false` 默认 false | ✅ 接受，需补充 | 定义 `timestamp` 在未同步时的 sentinel 值 |
| 2 | 守卫范围限定在 `bridgeGpsToLapTiming` | ❌ **反对** | 改为分层守卫（见第 3 节），新增 Requirement 3.5 |
| 3 | `GpsSample` 也加 `isTimeSynced` 字段 | ❌ **反对** | 删掉该字段，UI 直接读 `GpsData.isTimeSynced` |
| 4 | `isTimeSynced` 短暂 true→false 不杀圈 | ✅ 接受，需补充 | 恢复同步时重置前驱样本 + `LapRecord.qualityFlags` 增加 `ProtocolDesyncGap` |
| 5 | STATIC 模式 `dateAndHour` 不反映真实日历 | ✅ 接受，需补充 | 跨小时 dateAndHour 必须递增、接收端不得读真实日历 |

**处置建议**：

- 2 条反对项先让对方改 proposal + spec，不改会把 bug 换个姿势引回来（决策 2）或增加代码维护负担（决策 3）。
- 3 条补充项可以在 spec 里加 Scenario 锁定，也可以作为 tasks.md 的子项。
- 全部修订完成后再走 `/opsx:apply`。

---

## 一、决策 1（接受，需补充）：`isTimeSynced=false` 时 `timestamp` 的值必须明确

### 原决策

`GpsData.isTimeSynced: Boolean = false`，默认 false。冷启动到收到第一个时间包之前，圈速链路完全跳帧。

### 接受理由

- 符合对抗复审 8.7 方案 B 的推荐实现。
- 冷启动"等待协议时间同步"窗口通常 <1 秒（BLE enableNotification 完成后第一帧时间包即可到达），真实用户可接受。
- 收益：彻底切断"接收端本地时钟伪造 GPS ts"的通道。

### 补充要求

**Proposal 和 spec 都没说：`isTimeSynced=false` 时 `GpsData.timestamp` 字段填什么。**

这是个陷阱。如果 `isTimeSynced=false` 时仍然 `timestamp = System.currentTimeMillis()`，那 `isTimeSynced` 只是一层贴纸 —— 任何一个消费者忘了看 `isTimeSynced`，立刻把污染的 ts 用下去。这等同于把对抗复审第 **八** 节的原始问题换个姿势复现。

**要求**：Spec 的 **Requirement 2**（parser 不 fallback）增加一条 Scenario：

> **Scenario：parser 在协议未对齐时写入 sentinel 而非本地系统时钟**
>
> - WHEN 协议时间包尚未到达 `protocolTimeReference == null`
> - OR 主包 syncBits 与最近 time 包不匹配
> - THEN 返回的 `GpsData.timestamp = Long.MIN_VALUE`（或同义 sentinel，例如 `-1L`）
> - AND 返回的 `GpsData.isTimeSynced = false`
> - AND **不得**读取 `System.currentTimeMillis()`

**实现约束**：下游消费者忘记检查 `isTimeSynced` 时，`Date(Long.MIN_VALUE)` 会报出可见的诊断值（1970 年前的日期），或者整数减法产生巨大的负值，**快速失败优于静默污染**。

---

## 二、决策 3（反对）：`GpsSample.isTimeSynced` 是死字段

### 原决策

`GpsSample` 也加 `isTimeSynced` 字段，让 UI 判断无需反查 `GpsData`。

### 反对理由

**逻辑推演**：

1. `bridgeGpsToLapTiming` 在决策 2 / 分层守卫下，未同步帧根本进不了 `LapTimingEngine`，也进不了 `LapSession.samples`。
2. 所以 `LapSession.samples` 里每一个 `GpsSample` 的 `isTimeSynced` 恒为 `true`。
3. **字段永远为 true 即为死字段**。

### UI 真正的用法

- "当前圈轨迹 / 遥测" → 读 `LapSession.samples`（全部已同步）→ 不需要再看 `GpsSample.isTimeSynced`。
- "等待协议同步 vs 等待起点" label → 读 `GpsDataViewModel.gpsData.collectAsState().value.isTimeSynced`（**原始上游实时状态**，不是历史快照）。

两个用途都不需要 `GpsSample.isTimeSynced`。

### 要求

Spec 和实现都**删掉** `GpsSample.isTimeSynced` 字段。UI 层的"等待协议同步" label 由 `GpsDataViewModel.gpsData.isTimeSynced` 直接提供。

**对应 Requirement 5**（UI 层区分"等待协议时间同步"与"等待起点"）的 Scenario 需要明确：

> **Scenario：LapDebug 执行页根据 `GpsDataViewModel.gpsData.isTimeSynced` 切换提示文案**
>
> - WHEN `gpsData.isTimeSynced == false`
> - THEN 起终点计时卡片 statusLabel 显示 "等待协议时间同步"
> - WHEN `gpsData.isTimeSynced == true AND activeLap == null`
> - THEN statusLabel 显示 "等待起点"
> - WHEN `activeLap != null`
> - THEN statusLabel 显示 "当前圈进行中"

---

## 三、决策 2（反对 → 改为分层守卫）：`TestSessionViewModel` 整条链路都依赖 `timestamp`

### 原决策

守卫范围限定在 `bridgeGpsToLapTiming`，不改加减速测试（`processFilteredData`）。

### 反对理由：污染会同时伤两个链路

加减速测试的触发判定虽然核心输入是 `speed / acceleration`，**但整条数据处理链路都在消费 `GpsData.timestamp`**：

| 消费点 | 代码位置 | 污染后果 |
|---|---|---|
| `GpsDataFilter.calculateAcceleration` 的 `dt = (current.ts - prev.ts) / 1000` | `GpsDataFilter.kt:120` | dt 跨时钟跳变 → `acceleration` 瞬间变成几千 G，`consecutiveTriggerCount` 乱掉 |
| `GpsDataFilter.isPhysicalConstraintViolation` 的 `maxDelta = ... × dt` | `GpsDataFilter.kt:149` | dt 巨大 → `maxDelta` 巨大 → 真实速度跳变判"非异常" |
| `GpsDataFilter.checkPositionVelocityConsistency` 的 dt 守卫 | `GpsDataFilter.kt:181-182` | `dt > 0.2` 分支判错 |
| `TestSessionViewModel.updatePreTriggerBuffer` 的 `cutoffTime` | `TestSessionViewModel.kt:199` | 预触发 2 秒窗口边界错，buffer 清空或永不清 |
| `TestSessionViewModel.startTest` 的 `session.startTime` | `TestSessionViewModel.kt:278` | 测试会话起点带污染，整条 0-100 耗时错 |
| `TestSessionViewModel.updateLaunchStatus` 的 `lastDataAge` | `TestSessionViewModel.kt:366` | 数据年龄误算，launchStatus 误报"数据过期" |

只 gate bridge 会把这些字段的污染留给加减速测试，用户实测发现"圈时偏长"修完后，下次可能换个姿势报"0-100 时间不准"。

### 但一刀切也不行：`GpsData` 非 ts 字段仍然可用

**用户提出的关键观察**：

> 加减速的 gate 是速度值，圈速的 gate 是经纬度坐标，两者判定对象完全不同。

**正确解读**：

- `GpsData.speed / latitude / longitude / satellites / hdop` 来自协议主包解析（byte 4-19），和 `timestamp` **无关**。即便协议时间未对齐，这些数值字段依然可信。
- UI 遥测、连接状态指示、`SmartTestLauncher.checkLaunchConditions` 里的"速度区间 + 连接状态"判定**只看数值，不看时间**。
- 如果在 `TestSessionViewModel.gpsData.collect` 入口一刀切 return，冷启动前几秒 UI 会完全冻结（速度不显示、launchStatus 不更新）—— 这是不必要的代价。

### 修正方案：分层守卫

把"gate 位置"从 `collect 入口` 下移到**按字段类型分层**：

```
GpsData (parser 输出)
├── speed / latitude / longitude / satellites / hdop ───────── 始终可用（数值字段）
│       └─> UI 实时遥测卡片
│       └─> SmartTestLauncher.checkLaunchConditions（只看速度区间 + 连接状态）
│
└── timestamp (sentinel 当 isTimeSynced=false)
        └── 时间 delta 计算：全部要守卫
            ├── GpsDataFilter.process 入口：未同步时输出零加速度快照，不更新 previousRaw
            ├── preTriggerBuffer：未同步时不 append
            ├── processFilteredData：未同步时不转 Running、不判触发
            ├── startTest / session.startTime：未同步时拒绝开始测试
            ├── updateLaunchStatus：lastDataAge 改用 SystemClock.elapsedRealtime
            └── bridgeGpsToLapTiming：未同步时 skip（原方案保留）
```

### 新增 Requirement 3.5（推荐插入在 Requirement 3 之后）

> **Requirement 3.5：时间戳依赖的分层守卫**
>
> 下游消费者对 `GpsData.timestamp` 的依赖分为两类，必须分别处理：
>
> **(a) 时间 delta 计算类** —— 在 `isTimeSynced=false` 时跳过本次计算、不更新内部时间状态：
>
> - `GpsDataFilter.process`
> - `TestSessionViewModel.updatePreTriggerBuffer`
> - `TestSessionViewModel.processFilteredData`（Preparing / Running 分支转移）
> - `TestSessionViewModel.startTest`（session 起点）
> - `TestSessionViewModel.bridgeGpsToLapTiming`（保留，与 Requirement 3 一致）
>
> **(b) 纯数值消费类** —— 不读 `timestamp`，在 `isTimeSynced=false` 时**继续正常工作**：
>
> - UI 遥测卡片（显示 speed / lat / lon / satellites / hdop）
> - `SmartTestLauncher.checkLaunchConditions` 的速度区间 + 连接状态判定
> - `GpsDataViewModel.gpsData` 的 StateFlow 发射本身
>
> **`updateLaunchStatus` 的 `lastDataAge`**：改用 `SystemClock.elapsedRealtime() - lastReceivedAtElapsed`，与 `GpsData.timestamp` 解耦，恒定可用。
>
> ---
>
> **Scenario 3.5.1：GpsDataFilter 在未同步时不做时间 delta 计算**
> - WHEN `raw.isTimeSynced == false`
> - THEN `filter.process(raw)` 返回 `FilteredGpsData(speed=raw.speed, lat=raw.latitude, lon=raw.longitude, acceleration=0.0, confidence=0.0, consistencyFactor=1.0, isAnomaly=false, isPositionAnomaly=false)`
> - AND filter 内部的 `previousRaw / previousPosition / speedWindow / latWindow / lonWindow / bearingWindow` 均不更新
>
> **Scenario 3.5.2：未同步时预触发 buffer 不累积**
> - WHEN `filteredData.raw.isTimeSynced == false`
> - THEN `preTriggerBuffer` 不 append 该帧
>
> **Scenario 3.5.3：未同步时不开始新测试**
> - WHEN `_testState.value is TestState.Preparing`
> - AND `filteredData.raw.isTimeSynced == false`
> - THEN `checkTriggerCondition` 不被调用，或直接返回 false
>
> **Scenario 3.5.4：UI 遥测层在未同步时仍显示速度/位置/卫星数**
> - WHEN `gpsData.isTimeSynced == false`
> - AND `gpsData.satelliteCount > 0`
> - THEN 遥测卡片显示 `gpsData.speed` 和 `gpsData.latitude / longitude`
> - AND 起终点计时卡片 statusLabel 显示 "等待协议时间同步"
>
> **Scenario 3.5.5：launchStatus 的 lastDataAge 不依赖 GpsData.timestamp**
> - WHEN 最近一次 `gpsData.collect` 回调触发
> - THEN 内部变量 `lastReceivedAtElapsed = SystemClock.elapsedRealtime()`
> - AND `updateLaunchStatus` 使用 `SystemClock.elapsedRealtime() - lastReceivedAtElapsed` 计算 `lastDataAge`
> - AND 不再消费 `gpsData.timestamp`

### 实现代价

- `GpsDataFilter.process` 入口加一个分支：~10 行。
- `TestSessionViewModel` 改三处（preTriggerBuffer / processFilteredData 的 Preparing 分支 / updateLaunchStatus 的 lastDataAge）：~15 行。
- **UI 冷启动体验**：速度表、连接状态、launchStatus 正常显示；只是"开始测试"按钮在时间包到达前不响应。这符合真实 0-100 测试"需要时间戳可信才能开始"的物理意义。

---

## 四、决策 4（接受，需补充）：短暂失联不杀圈，但要补前驱重置 + 质量标记

### 原决策

`isTimeSynced` 短暂 true→false 时不杀圈，`activeLap` 保留。符合真实测圈设备的"暂停而非取消"语义。

### 接受理由

- 符合用户直觉：卫星信号短暂丢失不应该让整圈作废。
- `LapRecord.durationMillis = finishedAtMillis - startedAtMillis`，如果起止 ts 都是协议时间戳（已同步），durationMillis 本身正确。中间段 GPS 样本丢失只影响轨迹完整性，不影响圈时。

### 补充要求

#### 4.1 恢复同步时重置前驱样本（**必补**）

**隐患场景**：

- T0：`isTimeSynced=true`，车在弯心 A，`lastLapGpsSample = 弯心 A 样本`。
- T0 ~ T5s：`isTimeSynced=false`，跳帧 125 个样本。车实际上绕过弯心 B、出弯、开回直道。
- T5s：`isTimeSynced=true` 恢复，`currentSample = 直道某点`。
- **如果不重置前驱**，`detector.detect(previous=弯心 A 样本, current=直道某点)` 会跑一次"超长位移"的线段相交判定，可能跨过起终点 gate 和多个 sector gates → **伪造一次"自动过线"，凭空多出一个 LapRecord**。

**要求**：

在 `TestSessionViewModel` 里监听 `gpsData.isTimeSynced` 的 true→false→true 转换，或更简单：每次 `bridgeGpsToLapTiming` 入口发现 `!isTimeSynced` 时同步把 `lastLapGpsSample = null`，下次恢复时首样本不喂 engine（走 `TestSessionViewModel.kt:316-319` 的首样本分支）。

```kotlin
private fun bridgeGpsToLapTiming(gpsData: GpsData) {
    val config = _lapRunConfig.value ?: return
    if (_currentMode.value != TestMode.LapDebug || !isLapRecording) return

    if (!gpsData.isTimeSynced) {
        lastLapGpsSample = null   // 恢复同步时强制重置前驱
        return
    }
    // ...原逻辑
}
```

**对应 Requirement 3 的 Scenario 增补**：

> **Scenario 3.X：失联恢复后的首个同步帧不喂 detector**
> - GIVEN 圈速会话处于 Recording 状态，`lastLapGpsSample != null`
> - WHEN 收到 `gpsData.isTimeSynced == false` 的帧
> - THEN `lastLapGpsSample` 被置为 null
> - WHEN 再次收到 `gpsData.isTimeSynced == true` 的帧
> - THEN `lapTimingEngine.processSample` 不被调用（因为 previousSample == null，走首样本分支）

#### 4.2 LapRecord 记录失联 gap（**必补**）

**问题**：跳帧段的 `GpsSample` 不进入 `session.samples`，`LapRecord.trajectory` 中相邻 ts 差会远超一帧。UI 回放时轨迹出现"瞬移"段，用户无从得知"是因为信号丢了"还是"是数据 bug"。

**要求**：

1. `LapQualityFlag` 枚举增加 `ProtocolDesyncGap`：
   ```kotlin
   enum class LapQualityFlag {
       LowAccuracy,
       SparseSamples,
       SuspectedJitter,
       IncompleteSectors,
       ProtocolDesyncGap   // 新增
   }
   ```

2. `LapTimingEngine.handleStartFinishCrossing` 在闭圈构造 `LapRecord` 时，扫描 `trajectory` 相邻 ts 差，若存在 `Δts > 阈值`（建议 200ms，即 5 个正常 40ms 帧），添加 `ProtocolDesyncGap` 到 `qualityFlags`。

3. 可选增强：`LapRecord` 加 `desyncGapMillis: Long`（累计失联时长），用于 UI 显示 "圈内累计失联 X.Xs"。

**对应 Requirement 3 的 Scenario 增补**：

> **Scenario 3.Y：圈内短暂失联后恢复累计，LapRecord 打 ProtocolDesyncGap 标记**
> - GIVEN 圈速会话处于 Recording 状态
> - WHEN 圈内发生一段 `isTimeSynced=false` 持续 ≥ 200ms，随后恢复
> - AND 车辆继续推进并最终闭圈
> - THEN 生成的 `LapRecord.qualityFlags` 包含 `ProtocolDesyncGap`
> - AND `LapRecord.durationMillis` 为起止 ts 差（不扣除失联段）

---

## 五、决策 5（接受，需补充）：dateAndHour 契约

### 原决策

STATIC 模式的 `generateGpsTimeData` 不反映真实日历，用会话相对时钟派生 year/month/day/hour，`yearOffset = 0`。Simulator 只需保证 syncBits 对齐即可。

### 接受理由

- Simulator 不需要模拟真实 GPS 的 UTC 语义，只要接收端 parser 能正确拼出单调 ts 即可。
- `yearOffset = 0` 对应 2000-01-01 起点，这个虚拟起点简化了实现。

### 补充要求：跨小时契约

**隐患**：`timeSinceHourStart` 编码在 main 包的低 21 bit，单位 2ms，最大表达范围 = `2^21 × 2ms = 4_194_304 ms ≈ 69.9 分钟`。超过 1 小时会回绕。

如果 simulator 会话 ≥ 1 小时：

- 第 59 分 59 秒：`timeSinceHourStart ≈ 3_599_000`，`dateAndHour = 0`（2000-01-01 00:00），`protocolTimestamp = hourStart + 3_599_000`。
- 第 60 分 01 秒：`timeSinceHourStart ≈ 1_000`（刚回绕），如果 `dateAndHour` 仍 = 0 → `protocolTimestamp = hourStart + 1_000` —— **比上一帧回跳了 3_598_000 ms ≈ 1 小时**。
- engine 看到 ts 倒退，触发本报告第 1.3 节（crossingEvents dropWhile 依赖单调）和第 1.14 节（时间戳倒退无守卫）的隐患。

**要求**：

**Spec 的 Requirement 1**（发射端时间戳必须来自会话相对单调时钟）增补一条 Scenario：

> **Scenario 1.X：长会话跨小时时 dateAndHour 正确递增**
>
> - GIVEN simulator 会话持续时长 ≥ 1 小时
> - WHEN 会话内发送第 N 帧
> - THEN 该帧的 `dateAndHour` 编码 = `floor(sessionElapsedMs / 3_600_000)`（按小时递增）
> - AND 该帧的 `timeSinceHourStart` 编码 = `(sessionElapsedMs mod 3_600_000) / 2`（单位 2ms）
> - AND 接收端解析 `hourStartMillis + timeSinceHourStart` 保持严格单调递增
>
> **Scenario 1.Y：跨 24 小时 day 正确递增**
>
> - GIVEN simulator 会话持续时长 ≥ 24 小时
> - WHEN 会话跨天边界
> - THEN `day` 按 `floor(sessionElapsedMs / 86_400_000)` 递增
> - AND hour 对 24 取模

或者 Spec 明确声明边界：

> **非目标**（Non-goal）：本 change 仅保证会话 < 1 小时的 STATIC 模式行为。≥ 1 小时会话需后续 change 处理跨小时递增逻辑。

两种选法任一都可接受，但**不能不写**。

### 接收端配合契约

**Spec 的 Requirement 2**（接收端 parser 不 fallback）增补：

> **Scenario 2.X：接收端不假设 protocolTimestamp 对应真实日历**
>
> - 接收端**不得**调用 `Date(timestamp).year / Calendar.get(YEAR/MONTH/DAY)` 做任何业务判断
> - 接收端**不得**假设 `timestamp` 大于当前真实时间或小于某历史阈值
> - `timestamp` 被视为单调递增的 long 值，仅用于 delta 计算

这条防止未来有人看到"2000 年的 ts"就去做"时钟异常检查"，导致 simulator 场景下被误判。

---

## 六、给对方的最终修改清单

按优先级排列：

### P0（必须修，不修不能走 `/opsx:apply`）

1. **Requirement 2 Scenario 增补**（对应决策 1）：明确 parser 未同步时 `timestamp = Long.MIN_VALUE`，不读 `System.currentTimeMillis()`。
2. **Requirement 3 → 新增 Requirement 3.5**（对应决策 2）：分层守卫，包含 5 条 Scenario（3.5.1 ~ 3.5.5）。
3. **删除 `GpsSample.isTimeSynced` 字段**（对应决策 3）：所有涉及 `GpsSample` 的改动回滚，Requirement 5 的 Scenario 改为读 `GpsDataViewModel.gpsData.isTimeSynced`。

### P1（强烈建议修）

4. **Requirement 3 Scenario 增补**（对应决策 4.1）：失联恢复后首个同步帧重置前驱，不喂 detector。
5. **Requirement 3 Scenario 增补**（对应决策 4.2）：`LapQualityFlag.ProtocolDesyncGap` 新增 + engine 闭圈时扫描 trajectory gap。

### P2（必须明确，可选实现策略）

6. **Requirement 1 Scenario 增补**（对应决策 5）：跨小时 / 跨天 `dateAndHour` 递增契约。若选择不实现，则在 Non-goals 里明确"仅支持 < 1 小时会话"。
7. **Requirement 2 Scenario 增补**（对应决策 5 接收端配合）：接收端不得读真实日历字段。

---

## 七、流转建议

- 对方收到本 review 后：
  1. 按 P0 三条改 proposal + spec。
  2. 按 P1 两条增补 Scenario。
  3. 按 P2 两条在 Scenario 或 Non-goals 中二选一。
- 改完后**重新贴本 review 的第零节总体评价表**，逐项打钩。
- 全部打钩后走 `/opsx:apply`。
- `/opsx:apply` 执行时 tasks.md 的 6 组任务 + 8 条合流门槛保持不变，本 review 只改 proposal 和 spec，不改 tasks。

---

## 八、与对抗复审报告的对应关系

本 review 覆盖对抗复审 **八** 节（双端时间戳污染）的修复 spec，关联条目：

| 本 review 章节 | 对抗复审条目 | 说明 |
|---|---|---|
| 决策 1 补充 | 8.2 | parser fallback 是 root cause 之一，sentinel 是根治方案 |
| 决策 2 → Req 3.5 | 8.4（`dataAge`）+ 8.6（engine 信任 ts）+ 2.4（filter dt 顺序） | 分层守卫同时覆盖这三条 |
| 决策 3（删字段） | — | 纯设计精简，不对应现有 bug |
| 决策 4.1 前驱重置 | 1.4（多门同帧）+ 1.14（ts 倒退守卫） | 失联期位移跨 gate 会复现这两条 |
| 决策 4.2 gap 标记 | — | 新增质量维度，非修复原有 bug |
| 决策 5 跨小时契约 | 1.3（dropWhile 依赖单调） | 若不修，跨小时时 crossingEvents 归属错乱 |

本 review 通过 P0/P1 清单的修订，确保修复 8.1~8.6 的同时不引入 1.3 / 1.4 / 1.14 的新实例。

---

## 九、落地执行反馈（2026-04-22）

Spec / proposal / tasks 已按 P0/P1/P2 修订完成为 v2 并通过 `openspec-chinese validate --strict`，随后走 `/opsx:apply` 执行了完整实施：

**第零节总体评价表落地情况**：

| 决策 | v2 落实 | 实施交付 |
|---|---|---|
| 1 sentinel | ✅ `Long.MIN_VALUE` | `RaceChronoParser` 已写 sentinel，`RaceChronoParserProtocolTimeTest` 扩充 4 条 |
| 2 分层守卫 → Req 3.5 | ✅ 新增 | `GpsDataFilter.process` 入口守卫；`TestSessionViewModel` 分层守卫（preTriggerBuffer / Preparing / lastReceivedAtElapsed / bridge） |
| 3 删 `GpsSample.isTimeSynced` | ✅ 未加 | UI 经 `LapDebugExecutionScreen(isTimeSynced)` 参数透传；`LapDebugExecutionScreenStateTest` 扩充 3 条 |
| 4.1 失联前驱重置 | ✅ | `bridgeGpsToLapTiming` 未同步分支置 `lastLapGpsSample = null`；`TestSessionViewModelTrackLapTest` 4 条新增覆盖 |
| 4.2 `ProtocolDesyncGap` | ✅ | `LapQualityFlag` 加成员；`LapTimingEngine.handleStartFinishCrossing` 扫描 > 200ms；`LapTimingEngineTest` 2 条新增 |
| 5 跨小时契约 | ✅ Non-goal | Proposal 明确本 change 仅支持 < 1 小时会话；接收端"不假设真实日历" Scenario 已进 spec |

**实施产出**：
- 生产代码：`GpsData.kt` / `LapQualityFlag.kt` / `RaceChronoParser.kt` / `GpsDataGenerator.kt` / `GpsDataFilter.kt` / `TestSessionViewModel.kt` / `LapTimingEngine.kt` / `LapDebugExecutionScreen.kt` / `TestExecutionScreen.kt`
- 新增测试：`GpsDataTest` / `LapQualityFlagTest` / `GpsDataGeneratorTest` / `GpsDataFilterTest`（临时位置） / `EndToEndLapTimingContractTest` / 对 `RaceChronoParserProtocolTimeTest` / `TestSessionViewModelTrackLapTest` / `LapDebugExecutionScreenStateTest` / `LapTimingEngineTest` 扩充
- 构建配置：`core/bluetooth` / `simulator` / `feature/test` 各加 `testOptions { unitTests.isReturnDefaultValues = true }` 和 `mockito-inline`（支持 JVM 单测里 Android 静态方法降级为默认值）

**合流前硬门槛（tasks 10.1–10.8）**：全部通过，`openspec-chinese validate --strict` 通过。

**未完成项**：
- 10.9 真机冒烟（华为 8KE0219522008434 × DP011011255100142）需用户手动执行
- 10.10 "双端必须一次合流"的约束需要合并策略保证（不可单独合发射端或接收端）

**实施过程中的折中**（都在 tasks.md 条目里用括号文档化）：
- Mockito 5 禁止 mock `java.lang.System`，`endToEndNeverCallsSystemCurrentTimeMillis` 改为字节码常量池扫描，仅覆盖 `GpsDataGenerator` 和 `LapTimingEngine`；`RaceChronoParser` 的 frequency / tracking 统计路径本就使用 `System.currentTimeMillis`（不污染 `GpsData.timestamp`），豁免（B 组评审保留）
- Replay 端到端测试（8.3）采用 fake replay sample 替代完整 JSON 扫描，控制测试复杂度
- `GpsDataFilterTest` 新增用例临时落在 `feature/test` 模块，待战役 D 迁移后统一归位 `core:domain`
