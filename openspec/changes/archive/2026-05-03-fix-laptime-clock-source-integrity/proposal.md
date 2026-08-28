# fix-laptime-clock-source-integrity

> **修订版 v2**（2026-04-22）：按 `docs/superpowers/reviews/2026-04-22-opsx-fix-laptime-clock-source-review.md` 的 P0/P1/P2 修订整合。核心变动：
> - **sentinel**：parser 未同步时 `timestamp = Long.MIN_VALUE`（快速失败优于静默污染）
> - **分层守卫**：不再只 gate `bridgeGpsToLapTiming`；所有消费 `GpsData.timestamp` 的时间 delta 计算都要守卫；纯数值消费者保持正常工作
> - **删除 `GpsSample.isTimeSynced`**：UI 改读 `GpsDataViewModel.gpsData.isTimeSynced` 上游实时状态
> - **失联恢复前驱重置** + **`ProtocolDesyncGap` 质量标记**：防止跨几秒跳帧被 detector 误判为超长位移过线
> - **Non-goal** 明确：本 change 仅保证会话 < 1 小时，跨小时 `dateAndHour` 递增留后续 change

## Why

2026-04-22 用户实测反馈 **"圈速时间偏长"**，对抗式 review 第八节（`docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md` 8.1–8.6）定位 root cause：**发射端 simulator 与接收端 gps-app 双端都在用本地 `System.currentTimeMillis()` 伪造 GPS 采样时间戳**。

- 发射端 `GpsDataGenerator.currentTimestampMillis()` 非 replay 模式 100% 使用发射手机系统时钟；replay 模式缺 sample 时静默 fallback 到系统时钟。
- 接收端 `RaceChronoParser.parseGpsData` 在协议 `syncBits` 不匹配 或 `protocolTimeReference == null` 时**静默** fallback 到接收手机系统时钟。
- 下游 `GpsData.timestamp` 被 `GpsDataFilter` / `preTriggerBuffer` / `updateLaunchStatus` / `bridgeGpsToLapTiming` / engine / UI **无差别消费**，`LapRecord.durationMillis` 与加减速测试 `session.startTime` 都建立在跨时钟拼接值之上。

**机理**（对抗 review 8.3 case A）：开圈帧协议已对齐，闭圈帧协议失配 fallback →
```
durationMillis = t_close - t_open
              = T_recv(n_close) - T_send(n_open)
              ≈ 真实圈时 + D(BLE 链路延迟) + Δclock(两机时钟漂移)
```
`D ≥ 0` 永远为正，所以**偏差单向偏正、圈时恒定偏长**，与用户观察吻合。同理影响加减速测试的 `preTriggerBuffer.cutoffTime` 和 `session.startTime`——本 change 修完圈时偏长后，下次实测若继续用系统时钟污染，"0-100 时间不准"只会换个姿势复现。

**关键**：任一端留 fallback 路径，下次实测换个姿势就会复现。修复**必须两端同批 + 全链路分层守卫**，缺一不可。

## What

### 1. 发射端 simulator 时间戳单源化

- 把 `GpsDataGenerator.currentTimestampMillis()` 从 `System.currentTimeMillis()` 改为 `android.os.SystemClock.elapsedRealtime()` 派生的**会话相对时钟**（`STATIC` 及其他非 replay 场景）
- `REAL_TRACK_REPLAY` 场景严格使用 `replayTimestampMillis`，缺失时 `throw IllegalStateException`，**去除所有静默 fallback 分支**
- 时间包 `dateAndHour` 的生成改用同一时钟源，`yearOffset = 0`（虚拟 2000-01-01 起点），不反映真实日历

### 2. 接收端 parser 在协议未对齐时写入 sentinel 而非系统时钟

- `RaceChronoParser.parseGpsData` 在 `protocolTimeReference == null` 或 `syncBits` 不匹配时：
  - **写入 `timestamp = Long.MIN_VALUE`**（sentinel，避免下游忘检查时静默污染）
  - 写入 `isTimeSynced = false`
  - **不调用** `System.currentTimeMillis()`
- 仅当协议对齐时写入协议还原值 + `isTimeSynced = true`
- `parseGpsTimeData` 成功设置 `protocolTimeReference` 后，下一帧主包对齐时写 `isTimeSynced = true`
- `parser.reset()` 把"当前是否已同步"状态重置为 false
- 接收端 **不得** 读 `Date(ts).year` / `Calendar.get(YEAR/MONTH/DAY)` 做业务判断（因为 simulator 用虚拟 2000 年日历）

### 3. 数据模型：仅 `GpsData` 加 `isTimeSynced`，`GpsSample` 不加

- `GpsData` 新增 `isTimeSynced: Boolean = false` 字段
- `GpsSample`（`feature/test/.../model/laptiming/GpsSample.kt`）**不**加该字段——因为 `bridgeGpsToLapTiming` 已拦未同步帧，`LapSession.samples` 里每一个 `GpsSample` 永远 `isTimeSynced == true`，加字段 = 死字段
- `LapQualityFlag` 新增成员 `ProtocolDesyncGap`

### 4. 分层守卫 — 按消费类型区分

**(a) 时间 delta 计算类** 在 `isTimeSynced == false` 时跳过本次计算、不更新内部时间状态：

- `GpsDataFilter.process`：返回"零时间 delta 快照"（speed/lat/lon 从 raw 透传，acceleration = 0，confidence = 0），内部状态 `previousRaw / previousPosition / 四个窗口` 不更新
- `TestSessionViewModel.updatePreTriggerBuffer`：不 append 未同步帧
- `TestSessionViewModel.processFilteredData`：`Preparing` 分支在未同步帧时不调 `checkTriggerCondition`
- `TestSessionViewModel.bridgeGpsToLapTiming`：跳帧 + `lastLapGpsSample = null`（失联恢复时首帧走首样本分支，避免 detector 对跨几秒位移做线段相交）

**(b) 纯数值消费类** 在 `isTimeSynced == false` 时正常工作：

- UI 实时遥测（`speed / latitude / longitude / satelliteCount / hdop`）
- `SmartTestLauncher.checkLaunchConditions`（速度区间 + 连接状态 + 卫星数 + hdop 判定）
- `GpsDataViewModel.gpsData` StateFlow 自身发射

**(c) 独立时钟的消费者**：

- `TestSessionViewModel.updateLaunchStatus` 的 `lastDataAge` 改用 `SystemClock.elapsedRealtime()` 与 `gpsData.timestamp` 解耦

### 5. UI 根据上游实时同步状态显示 statusLabel

- `LapDebugExecutionScreen` 按优先级（`activeLap != null` > `gpsData.isTimeSynced == true` > 其他）决定 `statusLabel`：
  - `activeLap != null` → "当前圈进行中"
  - `activeLap == null && gpsData.isTimeSynced == true` → "等待起点"
  - `activeLap == null && gpsData.isTimeSynced == false` → "等待协议时间同步"
- 通过 `GpsDataViewModel.gpsData.collectAsState()` 读上游，**不**读 `lapSession.samples` 的字段

### 6. engine 闭圈扫描 `ProtocolDesyncGap`

- `LapTimingEngine.handleStartFinishCrossing` 在构造 `LapRecord` 时扫描 `trajectory` 相邻样本 ts 差
- 若存在任意相邻差 `> 200ms`（5 个正常 40ms 帧），`LapRecord.qualityFlags` 追加 `ProtocolDesyncGap`
- `durationMillis` 本身为起止 ts 差，不扣除失联段

### 7. 端到端契约测试

- 新增 `EndToEndLapTimingContractTest.staticMode_lapDurationMatchesSenderClockDelta`
- 新增 `EndToEndLapTimingContractTest.replayMode_lapDurationMatchesReplayClock`
- 新增 `EndToEndLapTimingContractTest.endToEndNeverCallsSystemCurrentTimeMillis`（mock 验证调用次数为 0）
- 扩充 `RaceChronoParserProtocolTimeTest`：未同步时 `timestamp == Long.MIN_VALUE` 断言
- 新增 `GpsDataGeneratorTest`：STATIC 模式不访问 `System.currentTimeMillis`；REPLAY 缺样本抛异常
- 新增 `GpsDataFilterTest.process_notTimeSynced_returnsZeroDeltaSnapshot`（注意 filter 测试还有 package 迁移坑，战役 D 处理）

## Impact

### 协议兼容性

- **不改**协议字节布局：主包 20 字节、时间包 3 字节、字段偏移、编码方式全部保持不变
- **不改**`syncCounter` / `syncBits` / `timeSinceHourStart` / `dateAndHour` 字段语义，与真实 ESP32 固件保持互通
- 只改两端各自**对时间字段的计算来源**与**对齐失败时的行为**
- `docs/RaceChrono_BLE_Protocol.md` 的协议权威文档无需修订

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| simulator | `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt` | 改 `currentTimestampMillis()` 时钟源；去 fallback；构造注入 `clock: () -> Long` |
| 接收端 parser | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` | 去 `?: System.currentTimeMillis()`；写 `isTimeSynced` + `timestamp = Long.MIN_VALUE` sentinel |
| 数据模型 `GpsData` | `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt` | 加 `isTimeSynced: Boolean = false` |
| 数据模型 `LapQualityFlag` | `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapQualityFlag.kt` | 加 `ProtocolDesyncGap` 成员 |
| filter（分层守卫 a） | `core/domain/src/main/java/com/blazepush/core/domain/usecase/GpsDataFilter.kt` | 入口未同步分支返回零 delta 快照，内部状态不更新 |
| ViewModel 分层守卫 | `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt` | 加 `lastReceivedAtElapsed`；`updateLaunchStatus` 改用 `elapsedRealtime`；`updatePreTriggerBuffer` 和 `processFilteredData` 加未同步分支；`bridgeGpsToLapTiming` 跳帧 + 重置 `lastLapGpsSample` |
| engine（`ProtocolDesyncGap` 扫描） | `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt` | 闭圈时扫描 `trajectory` 相邻 ts 差 |
| UI | `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` | 统一通过 `GpsDataViewModel.gpsData` 读 `isTimeSynced` 决定 `statusLabel` |
| 测试 | `core/bluetooth/src/test/.../RaceChronoParserProtocolTimeTest.kt`（扩充）<br>`simulator/src/test/.../GpsDataGeneratorTest.kt`（新建）<br>`core/domain/src/test/.../GpsDataFilterTest.kt`（新用例，注：filter 测试有 package 迁移问题见战役 D）<br>`feature/test/src/test/.../EndToEndLapTimingContractTest.kt`（新建）<br>`feature/test/src/test/.../TestSessionViewModelTrackLapTest.kt`（扩充）<br>`feature/test/src/test/.../LapDebugExecutionScreenStateTest.kt`（扩充） | 按分层守卫覆盖 |

### 双端任务边界

- **strictly both or nothing**：发射端改造 + 接收端改造必须进入同一次合流。端到端契约测试是本 change 完成标准的硬门槛。
- 只修发射端或只修接收端会让端到端契约测试失败，且 `System.currentTimeMillis` 调用次数 == 0 的断言不通过。

### 兼容性与迁移

- `GpsData.isTimeSynced` 默认 `false`：首次启动所有历史依赖 `GpsData.timestamp` 但不检查 `isTimeSynced` 的代码**会持续跳过所有帧**，直到显式写入 `isTimeSynced = true`。
- 本 change 范围内所有需要守卫的消费者**必须同批**落地。不在范围内的相邻问题（见"不在本 change 范围"）保持现状。

### 风险与备选方案

| 风险 | 缓解 |
|---|---|
| `SystemClock.elapsedRealtime()` 在 Android Robolectric 单测里初值从 0 起，与 Kotlin JVM 测试行为不同 | 单测通过构造函数注入 `clock: () -> Long` 时钟源，测试时用 `FakeClock`，不直接依赖静态调用 |
| 冷启动 `isTimeSynced = false` 导致"圈速根本开不了圈" | UI 明确提示"等待协议时间同步"；time 包一到接收端立即在下一主包帧切 true；端到端测试断言"冷启动后首个 time 包 → 下一帧 engine 可工作" |
| `Long.MIN_VALUE` sentinel 被错误算术（如 `now - Long.MIN_VALUE`）会溢出 | 所有时间 delta 计算者都要 gate `isTimeSynced`，未同步时不做减法；端到端契约测试断言"`System.currentTimeMillis` 调用次数 == 0"间接保证 |
| 真实 ESP32 固件的 syncCounter 行为可能与 simulator 有细微差异 | 端到端契约测试只断言 **相对圈时差**，不断言绝对时间戳，兼容两种时钟源 |
| `isTimeSynced` 从 true 转 false（协议丢包）时失联位移跨 gate 伪造过线 | `bridgeGpsToLapTiming` 在 `isTimeSynced == false` 分支**重置** `lastLapGpsSample = null`，恢复时首帧走首样本分支不喂 detector；`LapRecord` 打 `ProtocolDesyncGap` 标记供回放时诊断 |
| 圈内 `ProtocolDesyncGap` 阈值 200ms 可能过于严格 | 200ms = 5 个正常 40ms 帧的容忍；短暂 1-2 帧丢包不会触发。若实测频繁误打，独立 change 调整阈值，不影响本 change 的时钟污染修复 |

### 不在本 change 范围的相邻问题（留待后续战役）

- detector 量纲错位（对抗 review 1.1）→ 战役 B
- FileLogger 主线程 I/O（对抗 review 1.2）→ 战役 E
- BLE GATT 泄漏与假连接（对抗 review 2.1–2.3）→ 战役 C
- filter 信号丢失重置顺序、异常帧污染（对抗 review 2.4–2.6）→ 战役 F
- engine crossingEvents 切片 / 时间戳倒退守卫 / 多门同帧（对抗 review 1.3 / 1.4 / 1.14）→ 战役 G（依赖本 change 的 `isTimeSynced`）
- `GpsDataFilterTest` / `TestSessionViewModelTest` package 迁移（对抗 review 2.8 / 3.3）→ 战役 D（**本 change 在 filter 里新增测试用例时会临时受此牵连，本 change 的 `GpsDataFilterTest.process_notTimeSynced_returnsZeroDeltaSnapshot` 由战役 D 归位后统一入主线**）
- 跨 1 小时会话的 `dateAndHour` 递增（本 change 的 Non-goals）→ 单独战役

## Alternatives

### A. 只修接收端（对抗 review 8.7 方案 A）

**拒绝理由**：发射端仍把本地系统时钟编进协议包。两部手机时钟漂移 Δclock 不会因为接收端守卫而消失——只是从"圈时偏长"变成"isTimeSynced 经常 false、无法开圈"，用户实测一样受阻。

### B. 只 gate `bridgeGpsToLapTiming`（初版 proposal 方案）

**拒绝理由**：用户实测 review 揭示 `GpsDataFilter / preTriggerBuffer / updateLaunchStatus / processFilteredData` 都消费 `GpsData.timestamp`，加减速测试路径的跨时钟污染被留下。修完圈时偏长后，下次实测换个姿势继续报"0-100 时间不准"。分层守卫是正解。

### C. 发射端保留 `REAL_TRACK_REPLAY` 之外所有场景都用系统时钟

**拒绝理由**：STATIC 场景是用户实测的主力路径。对抗 review 8.3 case A 的偏长就发生在 STATIC 模式。

### D. `isTimeSynced=false` 时 `GpsData.timestamp = 0L`（保留上次值）

**拒绝理由**：review 决策 1 指明，保留上次值或用 0 都是"贴纸防御"——下游忘了检查 `isTimeSynced` 就会用旧值或 epoch 时间做 delta，静默产生错误结果。`Long.MIN_VALUE` 作 sentinel 在下游忘检查时会**快速失败**（`Date(Long.MIN_VALUE)` 显示负日期，整数减法溢出），诊断成本远低于静默污染。

### E. 跨 1 小时会话支持 `dateAndHour` 递增

**推迟**：跨小时涉及 `timeSinceHourStart` 回绕、`dateAndHour` 递增、接收端拼接单调性，是独立题目。本 change 先把本地时钟污染堵死，时长边界以 Non-goal 明确声明。

## Non-goals

- 不引入新的时钟/时间同步协议（NTP、PTP 等）
- 不改变 BLE 协议的字节布局
- **不保证 ≥ 1 小时会话的行为**（`timeSinceHourStart` 回绕、`dateAndHour` 递增需独立 change）
- 不修理 engine 对时间戳单调的校验（战役 G）
- 不修理 FileLogger 主线程 I/O（战役 E）
- 不处理 ConnectionManager 的死代码（战役 C）
- 不迁移 `GpsDataFilterTest` / `TestSessionViewModelTest` package（战役 D；本 change 内 filter 新测试的最终归位依赖战役 D 完成）
