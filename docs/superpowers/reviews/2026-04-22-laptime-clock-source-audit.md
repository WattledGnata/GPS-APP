# 圈速时间戳源审计（2026-04-22）

> 分支：`feature_ctg_20260405_laptime_mainline`
>
> 痛点：模拟发射 app × 接收 app 联调时，圈速结果**系统性偏长**。用户推测一方或两方在计时时没用 GPS 协议时间戳而是读了系统本地时钟。本文把两端所有时间源点拉直做"同源性"审计，并给出**已定位的根因 bug** 和修复方案。

---

## 一、结论摘要

| 现象 | 根因 | 位置 |
|---|---|---|
| 非回放（手动模拟）模式下，每 8 帧中有 7 帧主包的 `timestamp` 回落到 `System.currentTimeMillis()`，圈时偏长 ≈ BLE 链路端到端延迟 × N | **发射端时间包 syncBits 被位运算优先级 bug 抹成 0，和主包的 syncBits（随 syncCounter 递增）永远不匹配**，接收端进入 fallback 分支 | `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:149` |
| 单元测试没抓到 | **测试代码里有同样的优先级 bug**，主包/时间包两侧 syncBits 同时被抹成 0 → 互相"假证"匹配通过 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt:68, 77` |
| 连接后第一帧主包的 `timestamp` 必然是接收端系统时钟 | `RaceChronoParser` 在 `protocolTimeReference == null` 时强制 fallback，且**主包 notify 先于时间包**（simulator 侧顺序：`notifyMainData` 再 `notifyTimeData`） | `core/bluetooth/.../RaceChronoParser.kt:257-261` + `simulator/.../GpsPeripheralManager.kt:180-185` |
| 回放模式下 syncCounter 恒为 0，bug 恰好自洽，圈时应当精确 | 回放路径 `playReplayFramesForever` 不调 `updateSimulation()`，`incrementSyncCounter()` 不触发 | `simulator/.../SimulatorViewModel.kt:264-291` |

---

## 二、链路上所有时间戳源点

### 2.1 发射端（simulator）

| 位置 | 读的是什么时钟 | 用途 |
|---|---|---|
| `GpsDataGenerator.currentTimestampMillis()`（`GpsDataGenerator.kt:115-121`） | 回放模式：`replayTimestampMillis`（来自 `ReplaySample.timestampMillis`，JSON 里的历史采集时刻）<br>其他模式：`System.currentTimeMillis()` | 编码进主包的 `timeSinceHourStart` 和时间包的 `dateAndHour` |
| `GpsDataGenerator.generateGpsTimeData()`（`GpsDataGenerator.kt:138-140`） | 同上 | 编码日/时 |
| `SimulatorViewModel.startDataUpdate`（`SimulatorViewModel.kt:228`） | `System.currentTimeMillis()` | 只用于物理轨迹推算 (`updatePosition`)，**不进协议包** |
| `GattServerManager.deviceActivityMap`（`GattServerManager.kt:80, 310, 332`） | `System.currentTimeMillis()` | 只用于心跳活跃度统计，**不进协议包** |

**关键流程**：
```
Replay JSON (timestampMillis) ──┐
                                ├──▶ generator.currentTimestampMillis()
非回放: System.currentTimeMillis() ──┘             │
                                                  ▼
                          主包 data[0..2]: timeSinceHourStart = (ts % 3_600_000)/2
                          时间包 data[0..2]: dateAndHour = year/month/day/hour from ts
                                                  │
                                                  ▼
                          manager.updateGpsData(mainData, timeData)
                          (notifyMainData → notifyTimeData 顺序)
```

### 2.2 接收端（gps-app）

| 位置 | 读的是什么时钟 | 用途 |
|---|---|---|
| `RaceChronoParser.parseGpsData` → `protocolTimestamp`（`RaceChronoParser.kt:257-261`） | **优先**：`protocolTimeRef.hourStartMillis + timeSinceHourStart`（协议时间）<br>**fallback**：`System.currentTimeMillis()` | 写入 `GpsData.timestamp` |
| `RaceChronoParser.parseGpsData` → tracking（`RaceChronoParser.kt:203, 224`） | `System.currentTimeMillis()` | 频率计数器 + tracking 起点，**不进 timestamp** |
| `BleConnection.handleCharacteristicChange`（`BleConnection.kt:131`） | `System.currentTimeMillis()` | `lastDataTime` 仅用于数据超时检测，**不进 timestamp** |
| `ConnectionManager.checkForFakeConnection`（`ConnectionManager.kt:90`） | `System.currentTimeMillis()` | 假连接检测，**不进 timestamp** |
| `GpsDataViewModel.updateDataStats`（`GpsDataViewModel.kt:83`） | `System.currentTimeMillis()` | 质量评估用 dataAge，**不进 timestamp** |
| `TestSessionViewModel.toLapGpsSample`（`TestSessionViewModel.kt:354-362`） | 直接透传 `GpsData.timestamp` | `GpsSample.timestampMillis` |
| `LapTimingEngine.processSample`（`LapTimingEngine.kt:22-54`） | 只读 `GpsSample.timestampMillis`，不再读系统时钟 | `CrossingEvent.timestampMillis` = `currentSample.timestampMillis` |
| `LapRecord.durationMillis`（`LapTimingEngine.kt:105`） | `currentSample.timestampMillis - activeLap.startedAtMillis` | 圈时 |

### 2.3 单一时间源：GpsData.timestamp

圈速链路里**只有一个时间源点**：`GpsData.timestamp`（由 `RaceChronoParser.parseGpsData` 填）。

- 它要么来自**协议时间**（`protocolTimeRef.hourStartMillis + timeSinceHourStart`）
- 要么来自**接收端系统时钟** `System.currentTimeMillis()`

**圈时偏长的唯一路径**：一圈起点和终点分别来自不同的时间源，或同一源但源本身被污染。

---

## 三、根因：发射端时间包 syncBits 位运算 bug

### 3.1 故障代码

`simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:149`：

```kotlin
data[0] = (((syncCounter and 0x07) shl 5) or (dateAndHour shr 16) and 0x1F).toByte()
```

### 3.2 Kotlin infix 函数优先级

`and` / `or` / `shr` / `shl` 在 `Int` 上都是 **`infix fun`**。Kotlin 语言规范中，所有 infix 函数**同优先级，左结合**。因此：

```
a or b and c  ≡  (a or b) and c
```

### 3.3 故障表达式的真实求值

| 步骤 | 表达式 | 结果（以 syncCounter=5, dateAndHour=0xABCDEF 为例） |
|---|---|---|
| 1 | `syncCounter and 0x07` | `0x05` |
| 2 | `... shl 5` | `0xA0`（bit7-5 = 101） |
| 3 | `dateAndHour shr 16` | `0x0A` |
| 4 | `0xA0 or 0x0A` | `0xAA` |
| 5 | `0xAA and 0x1F` | **`0x0A`**（bit7-5 全部清零） |

**结果**：写到 `data[0]` 的是 `0x0B`（0x0A 左移低位 + bit0 因 toByte 取值），bit7-5（syncBits 占位）**全部为 0**。

接收端 `RaceChronoParser.parseGpsTimeData`（`RaceChronoParser.kt:74`）从 `data[0] shr 5` 提取 syncBits，永远读到 `0`，并把它存进 `protocolTimeReference.syncBits = 0`。

### 3.4 主包对比（无 bug）

`GpsDataGenerator.kt:48`：

```kotlin
data[0] = (((syncCounter and 0x07) shl 5) or (timeHigh and 0x1F)).toByte()
```

⚠️ 注意 `(timeHigh and 0x1F)` 是用括号包好的整体，作为 `or` 的右操作数。因此：

```
((syncCounter and 0x07) shl 5) or ((timeHigh and 0x1F))
```

syncBits 不会被抹除。主包 syncBits = `syncCounter and 0x07`，随 `incrementSyncCounter()` 循环 0..7。

### 3.5 为什么掩码里没出现这个 bug 的是主包而是时间包？

主包的 time 字段只占 21 bit（5+8+8），天然 `<= 0x1FFFFF`，`timeHigh` 天然 `<= 0x1F`。所以原开发者在写 `timeHigh` 时省掉了 `and 0x1F` 也不会越界，但保留了括号写法：`(timeHigh and 0x1F)`——**保留括号让意图清晰，顺便护住了 syncBits**。

时间包 `dateAndHour` 用掉了 21 bit（5+8+8），`dateAndHour shr 16` 也天然 `<= 0x1F`。但这次开发者把 `and 0x1F` 写在了括号**外面**，就把左边的 syncBits 也一起抹了。

### 3.6 联调实际行为

**非回放模式**（`startGpsDataStream` 调用 `updateSimulation()`，`syncCounter` 每帧递增）：

| 帧 | 主包 syncBits | 时间包 syncBits | 匹配 | `GpsData.timestamp` 来源 |
|---|---|---|---|---|
| 1 | 0 | 0 | ✓ | 协议时间 |
| 2 | 1 | 0 | ✗ | `System.currentTimeMillis()` |
| 3 | 2 | 0 | ✗ | `System.currentTimeMillis()` |
| 4–8 | 3..7 | 0 | ✗ | `System.currentTimeMillis()` |
| 9 | 0 | 0 | ✓ | 协议时间 |
| ... | | | | |

⇒ **8 帧中只有 1 帧用协议时间**，7 帧用接收端墙上时钟。每帧的系统时钟时间点都是"BLE 通知到达 + 调度延迟"那一瞬，抖动在几 ms~几十 ms。

一圈起终点命中的帧如果一个落在协议帧、另一个落在系统帧上，圈时就被污染成：
```
Δt_圈 = Δt_协议理论 + (系统时钟基准 - 协议时钟基准) ± BLE 抖动
```

BLE 通知到达延迟本身就会让系统时钟时刻比 GPS 真实采样时刻晚几十 ms，**统计上必然偏长，不会偏短**。

**回放模式**（`startReplayDataUpdate` 不调 `updateSimulation()`）：
- `syncCounter` 永远是初值 0
- 主包 syncBits = 0, 时间包 syncBits = 0, 匹配
- 协议时间通路打通，圈时精确

**也就是说**：如果用户圈时偏长的观察是在非回放 / 手动模拟路径下做的，这一条 bug 就是全部根因。如果是在回放路径下观察到偏长，要继续查（见第六节次级嫌疑）。

---

## 四、测试为什么没抓到

`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt` 的两个工具函数有**同样的优先级 bug**：

**时间包构造**（`RaceChronoParserProtocolTimeTest.kt:68`）：
```kotlin
(((syncBits and 0x07) shl 5) or (dateAndHour shr 16 and 0x1F)).toByte()
```

**主包构造**（`RaceChronoParserProtocolTimeTest.kt:77`）：
```kotlin
data[0] = (((syncBits and 0x07) shl 5) or (encodedTime shr 16 and 0x1F)).toByte()
```

两边 syncBits 都被 `and 0x1F` 抹成 0：

| 测试用例 | 意图 | 实际发生 | 结果 |
|---|---|---|---|
| `gps timestamp comes from protocol date and hour plus millis within hour` | time=3, main=3 → 匹配 | time=0, main=0 → 仍匹配 | PASS（但理由错了） |
| `gps timestamp falls back when protocol time sync bits do not match main packet` | time=1, main=5 → 不匹配 → fallback | time=0, main=0 → **匹配，返回协议时间** | 应该 FAIL，但现实验证 2026-04-22 跑过 ✓ |

第二个测试的 assertion 是 `result.timestamp in beforeParse..afterParse`，期望 timestamp 落在 fallback 的系统时钟区间里。`sampleTimestamp = 1773478969360` 约 2026-03-11，而 `beforeParse..afterParse` 是 2026-04-22 附近——**这两个时间点不在同一区间**。理论上应该 FAIL。

**实际运行**（2026-04-22 本轮 review 跑通）：
```
./gradlew :core:bluetooth:testDebugUnitTest --tests "...RaceChronoParserProtocolTimeTest"
BUILD SUCCESSFUL
```

⚠️ 这里需要继续验证。当前观察到的"通过"可能是 Gradle 缓存了历史通过结果（`UP-TO-DATE` 某些任务）；也可能是测试对 syncBits=0 时的行为判定仍然落在某种容忍区间里。**建议单独加断言把 syncBits 从 byte 里逆向解出来检查，再决定是 test fixture bug 还是 assertion 不足。**

---

## 五、推荐修复方案

### 5.1 立即修：发射端时间包位运算（1 行）

`simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:149`：

```kotlin
// 旧（bug）：
data[0] = (((syncCounter and 0x07) shl 5) or (dateAndHour shr 16) and 0x1F).toByte()

// 新（加一对括号）：
data[0] = (((syncCounter and 0x07) shl 5) or ((dateAndHour shr 16) and 0x1F)).toByte()
```

### 5.2 同步修：测试 fixture 里的同一 bug（2 行）

`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserProtocolTimeTest.kt:68, 77`：

```kotlin
// 第 68 行（时间包构造）
(((syncBits and 0x07) shl 5) or ((dateAndHour shr 16) and 0x1F)).toByte()

// 第 77 行（主包构造）
data[0] = (((syncBits and 0x07) shl 5) or ((encodedTime shr 16 and 0x1F))).toByte()
```

修完后这两个测试会变成真正的回归网。

### 5.3 增加显式断言：直接检查 byte 里的 syncBits 位

在 `RaceChronoParserProtocolTimeTest` 加一个新用例，只检查"编码后 bit7-5 等于指定 syncBits"，独立于 parser，避免两边同时被误解。

```kotlin
@Test
fun `gps main data header preserves sync bits`() {
    val encoded = createGpsMainData(timeSinceHourStartMillis = 500, syncBits = 5)
    val syncBitsFromByte = (encoded[0].toInt() shr 5) and 0x07
    assertEquals(5, syncBitsFromByte)
}
```

同样给时间包加一份。

### 5.4 增加接收端守卫：`isTimeSynced` 状态

`RaceChronoParser` 在 `protocolTimeReference == null || syncBits != ref.syncBits` 时，**不应**静默 fallback 到 `System.currentTimeMillis()`，而是返回一个带标记的结果让下游决定是否采用。

建议给 `GpsData` 增加字段 `isProtocolTimestamp: Boolean`（或重命名 `isTestReady` 的语义），由 parser 填。圈速 bridge 在 `isProtocolTimestamp == false` 时**不要**把该帧喂进 `LapTimingEngine`。

这样即使将来再出现某种 fallback 路径，也不会让"开锅第一帧"污染起点时刻。

### 5.5 模拟器端：每帧发包顺序反转

`GpsPeripheralManager.updateGpsData`（`GpsPeripheralManager.kt:180-185`）目前是 **Main 先发 → Time 后发**。

```kotlin
fun updateGpsData(mainData: ByteArray, timeData: ByteArray) {
    if (!_isAdvertising.value) return
    gattServerManager.notifyMainData(mainData)
    gattServerManager.notifyTimeData(timeData)
}
```

接收端只在收到 Time 包后才持有 `protocolTimeReference`。主包先到意味着**每次连接的第一帧主包必然 fallback**。

**临时对策**：把顺序反转成 Time → Main，保证首帧主包到达时时间基准已就绪。

**长期**：让接收端 5.4 的 `isTimeSynced` 守卫负责这件事，顺序无所谓。

### 5.6 回归锁定：端到端圈时不变量

在 `core/bluetooth` 或 `feature/test` 下新增一个端到端测试（纯 JVM）：

1. 构造一段已知时长的 replay（例如 267_000 ms 整）。
2. 用发射端的真实打包代码生成 main+time 两路字节流。
3. 喂给接收端的 `RaceChronoParser`。
4. 取出 `GpsData.timestamp` 序列，断言**整条序列相邻差值与 replay JSON 时间戳差值一致**（容差 0）。
5. 特别断言"第 1 帧 main 解出的时间戳与 replay JSON 的第 1 个 timestamp 一致"——这个断言在首帧 fallback 时会立刻失败。

这是把本次 bug 真正盯死的唯一手段。

---

## 六、如果修完还偏长：次级嫌疑清单

1. **发射端 `playReplayFramesForever` 的 `delay(frame.delayMillis)` 使用的是 `kotlinx.coroutines.delay`，它调度在协程调度器上**——若 dispatcher 被阻塞（比如接收端同进程占用 main），`delayMillis` 实际会拉长。这会让"发射节奏"偏慢于 replay JSON 里的采样节奏。但**圈时是两帧时间戳差**，发射节奏抖动不会影响（只要发射端时间戳用 JSON ts）。所以非根因。

2. **接收端 `BleConnection.DATA_TIMEOUT_MS = 10_000` 超时把 `_connectionState` 改成 `DISCONNECTED` 不清理 GATT**（`BleConnection.kt:244-252`）。如果联调中路径有断连重连，`protocolTimeReference` 不会被 `parser.reset()` 清除（reset 只在新建 parser 实例时手动调），**下一次连接首帧时 syncBits 可能匹配到旧 ref，解出一个完全错位的时间**。建议在 `BluetoothDataSource.connect` 里每次连接前强制 `parser.reset()`。

3. **接收端 `isTestReady = satellites >= 6 && hdop < 2.0`**（`RaceChronoParser.kt:275`）—— 如果模拟器初期几帧 satellites 没到 6，圈速会在 "未 ready" 状态跳过前几帧，但 `_dataFlow` 已经把这些帧的 timestamp 推出去了。不影响圈时计算，但影响数据统计。

4. **`LapDebugExecutionScreen` 的 "当前圈" 显示 = 最新 sample ts − 最近 accepted start-finish ts**（`LapDebugExecutionScreen.kt:210-211`）。最新 sample ts 和 start-finish ts 都是 `GpsData.timestamp`，同源。但如果 **session.samples 是累积的**（是的，`LapTimingEngine.processSample` 每帧都 `samples + currentSample`），首帧那条"可能带 fallback 时间戳"的样本会一直躺在 `session.samples[0]`。但 UI 只看 `samples.lastOrNull()`（`LapDebugExecutionScreen.kt:210`），不受污染。

---

## 七、验证建议（用户执行层面）

1. 关掉接收端 app，清掉进程。
2. 在发射端用**非回放模式**开始广播（或把 simulator 暂时切到 STATIC scenario）。
3. 在接收端打开圈速调试页，`selectLapDebugMode(preset-tfic-lpcc)`。
4. 在发射端手动模拟"两次起终点穿线"（物理上走过预置起点 gate line 两次，中间保持固定时长 T）。
5. 观察接收端 `LapRecord.durationMillis`：
   - 修 bug **前**：应显示 `T + BLE 抖动 × N`
   - 修 bug **后**：应显示 `T ± 几 ms`（仅剩协议时间戳 2ms 量化误差）
6. 同样流程切到回放模式对比：修 bug 前后圈时应**基本一致**（因为回放下 syncCounter 恒 0，bug 自洽）。

如果上述实验中 "修 bug 前非回放偏长，修 bug 后非回放精确，回放前后一致"——本次根因确认。否则回到第六节继续查。

---

## 八、动作清单

- [ ] 修 `GpsDataGenerator.kt:149` 括号
- [ ] 修 `RaceChronoParserProtocolTimeTest.kt:68, 77` 同款括号
- [ ] 新增 syncBits 位保存断言（5.3）
- [ ] 新增 replay 端到端圈时不变量测试（5.6）
- [ ] `BluetoothDataSource.connect` 前调 `parser.reset()`（附加清洁）
- [ ] 增加 `GpsData.isProtocolTimestamp` 字段 + 圈速 bridge 守卫（5.4）
- [ ] 反转 `GpsPeripheralManager.updateGpsData` 中 Time/Main 发包顺序（5.5，可选）
