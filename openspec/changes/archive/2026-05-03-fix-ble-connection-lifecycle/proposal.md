# fix-ble-connection-lifecycle

战役 G「BLE 稳定性包」。本 change 一次性闭环 attack-backlog 7 条 🔴 pending
主项 **A23 / A24 / A25 / A27 / A29 / A40 / A42** + 1 条 review 文档修订
**A46**；同时建议同批捆绑 **A45**（与 A23 强绑定的 review 文档修订）。

核心决策摘要：

- **A23 拍板方案 B（删除 ConnectionManager）**。整个 `ConnectionManager.kt`
  删掉，"谁负责重连 / 谁负责 GATT 释放"收敛到 `BleConnection`（GATT 生命周期层）
  + `BleDeviceManager`（设备管理层）的二层职责上。方案 A（接线）在 Alternatives
  里拒收并附反驳理由。
- **A29 拍板方案 (a)（`else` 分支改为 `startScan()`）**。方案 (b)（接入
  `BluetoothDeviceRepository` 持久化 lastDeviceAddress）留到下一个战役（与
  A28 `GpsDataViewModel.resetStats` 断连重置 + 完整"拔电源 15 秒自愈"链路一起做）。
- **本战役不做"拔电源 → 15 秒端到端自动重连"**，显式写进 Non-goals。本战役
  保证 DISCONNECTED 信号可靠传导、GATT 资源干净释放；端到端自愈拆到下一个
  战役。建议评审方同步修订 A23 核销条件第 (2) 项（见 § Alternatives 最后
  一节）。
- **A45 建议捆绑**。A23 方案 B 删除后，原 review `2026-04-22-gps-ingestion-and-filter-review.md § 11.5`
  "双层超时互相干涉" 叙述 100% 过时，修订为"假连接恢复未实现（ConnectionManager
  已删）"。本战役内零额外代价，建议评审方在 proposal review 时批准捆绑。

## Why

### 问题全景

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
§ 2.1 / 2.2 / 2.3 / 2.7 / 2.10 / 2.14 / 2.15 揭示 `core/bluetooth` 整层存在
**"死代码 + 资源泄漏 + 语义污染 + 自愈路径缺失"** 四类相互放大的问题：

1. **假连接恢复从未实现**（A23）—— `ConnectionManager` 全仓未注册、未实例化、
   `setCurrentDevice` 无调用方，整套"假连接检测 + 重连"逻辑是 dead code。
   ESP32 经典故障模式"GATT connected 但无 notify"发生时，系统除了把
   `_connectionState` 置 DISCONNECTED 外**没有任何自动恢复动作**，用户必须
   手动杀进程或重启 app。原 review § 11.5 的"双层超时互相打架"叙述是幻觉
   （A45）—— 其中一层从未运行。
2. **GATT 资源泄漏路径有三条**：
   - 数据超时（A24）：`startDataTimeoutCheck` 只改 `_connectionState`，**不**
     `gatt.disconnect()`、**不** `gatt.close()`、**不** `bluetoothGatt = null`。
     Android BT stack 的 GATT client 槽位不释放（每个 app 上限 ~30）。
   - 设备切换（A27）：`BluetoothDataSource.connect(addressB)` 直接
     `bleConnection = BleConnection(...)` 覆盖旧引用，旧 GATT 永久挂起。
   - 主动 disconnect 时机错（A40）：`gatt.disconnect()` + `gatt.close()` 同一
     方法同步连调，某些厂商实现下 close 后 `onConnectionStateChange` 仍来，
     对已关闭 gatt 的访问行为未定义。
3. **语义污染**（A25）：`BluetoothDataSource` 对短包、解析异常、未知 UUID
   都 `copy(isConnected = true)`。`isConnected` 从"GATT 连上 + 数据可信" 劣化
   为"曾经收到 BLE 回调"。下游任何用 `gpsData.isConnected` 当"数据有效闸门"
   的判断都会误判。
4. **自愈入口卡死**（A29）：`BleDeviceManager.autoReconnectLastDevice` 在
   `lastDeviceAddress == null`（当前硬编码 null）分支**只 log 不 startScan**。
   冷启动后 app 不会自动扫描。原 review § 11.6 的"fallback 到 startScan"叙述
   与代码事实相反（A46）。
5. **接线方案的隐性成本**（A42）：`ConnectionManager.init` 的空 collect
   (`bluetoothDataSource.connectionState.collect { ... /* 空注释 */ }`) 是
   permanent-suspended 协程。当前死代码状态下不跑，但选方案 A 接线会立即显形
   占用一个 IO 协程；选方案 B 删除自动清零。

以上 7 条的根因指向同一个缺失：**"谁负责重连、谁负责 GATT 生命周期、
isConnected 语义如何定义" 的职责边界从未拍板**。留 ConnectionManager 和删
ConnectionManager 都必须先回答这个问题。本 change 以方案 B 为地基重新划定边界
（见 § What R1），其余 A24/A27/A40/A25/A29 全部以 R1 为约束做精确修复。

### A23：`ConnectionManager` 是全仓死代码 (§ 2.1)

**证据**：

```
grep -R "ConnectionManager" core/bluetooth/src/main/java/
  → ConnectionManager.kt:12 (类定义)
  → ConnectionManager.kt:17 (TAG 常量)
grep -R "setCurrentDevice" .
  → ConnectionManager.kt:65 (方法定义)
feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt
  → 无 ConnectionManager factory / single
```

- 没有 DI 注册、没有实例化、`setCurrentDevice` 无调用方
- `init { ... startFakeConnectionCheck() }` 永远不跑
- `_isFakeConnection` 永远 false
- `triggerReconnect()` 即使被调，`currentDeviceAddress == null` 会空返回

**后果**：假连接恢复根本不存在。原 review § 11.5 的"两层超时互相补位"是幻觉。

### A24：数据超时不 close GATT，且存在假断连 race (§ 2.2)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt:244-252`

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

两个问题：

1. **超时只改 state 不释放 GATT**：`bluetoothGatt?.disconnect()` 未调、
   `bluetoothGatt?.close()` 未调、`bluetoothGatt = null` 未做。对比 `disconnect()`
   (172-178) 的完整清理，这里是残次版。GATT client 槽位永久占用。
2. **race 抖动**：`handleCharacteristicChange` (126-142) 每帧 `timeoutJob?.cancel()
   + startDataTimeoutCheck()`。25Hz 下上一个 timeoutJob 可能**刚**出 delay 进入
   `if` 判断，`cancel()` 不保证即时停 → body 继续跑完 → `_connectionState.value
   = DISCONNECTED`；紧接着 `handleCharacteristicChange` 又把 state 改回
   `CONNECTED`（134-137 行）。1-2 ms 内 state 从 `CONNECTED → DISCONNECTED →
   CONNECTED` 抖动，下游 UI 闪烁。

### A25：`BluetoothDataSource` 对失败解析也标 `isConnected = true` (§ 2.3)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt:49-56`

```kotlin
bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
    val gpsData = when (uuid.toString()) {
        "00000003-..." -> parser.parseGpsData(rawData, _dataFlow.value)
        "00000004-..." -> parser.parseGpsTimeData(rawData, _dataFlow.value)
        else -> _dataFlow.value
    }
    _dataFlow.value = gpsData.copy(isConnected = true)   // 无条件置 true
}
```

- 短包（`parser.parseGpsData` 直接 `return currentData`，见
  `RaceChronoParser.kt:136-140`）路径进 `copy(isConnected = true)`，StateFlow
  equality 保护让当前**不 emit 假帧**，但语义已经崩掉
- 未知 UUID 分支同理
- try/catch 内抛异常走 `return currentData` (`RaceChronoParser.kt:292-295`)
  也进这条路径

**下游误解释**：任何用 `gpsData.isConnected` 当"数据有效闸门"的逻辑都会误判。
若未来 `GpsData` 添加 `receivedAt: Long = System.currentTimeMillis()` 字段
改变 equality，这条路径立刻每个未知包都 emit 一次假帧。

### A27：`connect()` 不先 disconnect 旧 `bleConnection` (§ 2.14)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt:42-82`

```kotlin
fun connect(deviceAddress: String) {
    scope.launch {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            bleConnection = BleConnection(context, deviceAddress) { ... }   // 直接覆盖
            bleConnection?.connectionState?.let { stateFlow ->
                connectionCollectJob?.cancel()                              // 旧 collectJob 到这里才 cancel
                connectionCollectJob = scope.launch { stateFlow.collect { ... } }
            }
            bleConnection?.connect()
        } catch (e: Exception) { ... }
    }
}
```

- 旧 `bleConnection` 不 `disconnect()` / `close()` / null，直接被 GC 候选
- 旧 GATT 永远挂起；叠加 A24 超时不清，泄漏放大
- `connectionCollectJob?.cancel()` 虽然存在，但**在新 bleConnection 构造之后**，
  旧 collectJob 可能把旧 bleConnection 的终态 state 传导到 `_connectionState`，
  与新 CONNECTING 状态竞争

### A29：`autoReconnectLastDevice` else 分支只 log 不 scan (§ 2.10)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt:59-87`

```kotlin
val lastDeviceAddress: String? = null // 暂时为null，待后续实现
if (lastDeviceAddress != null) {
    ...
    startScan()    // 超时未连才 fallback
} else {
    Log.d(TAG, "没有上次连接的设备记录")   // ⚠️ 只 log，不 startScan
}
```

- 当前硬编码 `lastDeviceAddress = null`，冷启动必走 else 分支
- else 分支**只 log 不 startScan**，用户看到空设备列表，必须手动点扫描
- 原 review § 11.6 叙述"fallback 到 startScan" 与事实相反（A46 修订）

### A40：`disconnect()` 中 `close()` 早于 STATE_DISCONNECTED 回调 (§ 2.7)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt:172-178`

```kotlin
fun disconnect() {
    cleanup()
    bluetoothGatt?.disconnect()     // 异步，触发 onConnectionStateChange(STATE_DISCONNECTED) 回调
    bluetoothGatt?.close()          // 同步，立即释放 GATT client
    bluetoothGatt = null
    _connectionState.value = ConnectionState.DISCONNECTED
}
```

- `gatt.disconnect()` 是异步的：在未来某刻触发 `onConnectionStateChange(..., STATE_DISCONNECTED)`
- `gatt.close()` 是同步的：立即释放 GATT 资源
- 某些厂商实现下 `close()` 之后回调仍来，回调里 `onConnectionStateChange` (73-77)
  访问 `gatt.device` / `gatt.services` 等任何方法都是对已关闭对象的调用，行为未定义
- Android 官方文档 standard flow 是：`disconnect()` → 回调 STATE_DISCONNECTED → 回调内 `close()` → null

### A42：`ConnectionManager.init` 空 collect 占用 IO 协程 (§ 2.15)

**证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt:50-56`

```kotlin
scope.launch {
    bluetoothDataSource.connectionState.collect { state ->
        if (state == ConnectionState.CONNECTED && currentDeviceAddress == null) {
            // 连接成功时，从 dataFlow 中获取地址（通过 timestamp 非零来判断有数据）
        }
    }
}
```

- `StateFlow.collect` 是挂起永不自然结束
- 当前 ConnectionManager 是死代码（A23），该协程从未启动
- 一旦方案 A 接线，空 collect 立即占用 1 个 IO 协程永久挂起
- 选方案 B 删除 → 整个文件连同空 collect 一起消失，**A42 自动闭环**

### A23 决策：方案 A 接线 vs 方案 B 删除 —— 选 B

两方案的完整对照：

| 维度 | 方案 A 接线 | 方案 B 删除（本 change 采纳）|
|---|---|---|
| 代码改动点数 | 4 处（AppModule 注册 + BleDeviceManager.connect 调 setCurrentDevice + BleConnection.startDataTimeoutCheck 改为 log-only + ConnectionManager.init 实现 TODO） | 2 处（删 `ConnectionManager.kt` 整文件 + `BleConnection.startDataTimeoutCheck` 补完整释放） |
| 职责边界 | 两层分担：`BleConnection` 只检测、`ConnectionManager` 只恢复 | 单层收敛：`BleConnection` 管 GATT 生命周期（含超时释放）、`BleDeviceManager` 管设备级恢复策略 |
| 依赖需要 | `ConnectionManager` 必须能拿到 deviceAddress（需从 `BluetoothDataSource` 或 `BleDeviceManager` 传入，新增信号通道） | 无新增接口，复用 `BleConnection` 构造时已持有的 deviceAddress + `BleDeviceManager` 已有的 connect 路径 |
| A42 处置 | 必须实现 init 的 TODO（从 dataFlow 提取地址），否则空 collect 白占一个 IO 协程 | 自动消失（文件删） |
| 未来扩展 | 若需多设备并发 / 复杂恢复策略，`ConnectionManager` 已在位，边际成本低 | 真有需求时按需重新引入，不是复活死代码 |
| 当前场景匹配度 | 当前 app 单设备，`BleDeviceManager` 只管理 one-shot lastDeviceAddress —— 双层职责不必要 | 单层恰好匹配单设备场景 |
| 死代码遗留 | 0（接线后整个类活起来）| 0（整文件删） |
| 半成品风险 | 中（接线后仍依赖 TODO 实现正确才能真正工作） | 低（删文件一步到位） |

**决策**：方案 B 删除。

**关键理由**（与用户 feedback 对齐）：

1. **"避免污染现有代码"**（memory `feedback_avoid_polluting_existing_code.md`）：
   方案 A 把半成品接线到生产链路，更深度污染；方案 B 把半成品彻底移除
2. **"避免半成品"**：ConnectionManager 保留多久就是"可能活起来的死代码"多久
3. **改动收敛度**：方案 B 改 2 处文件，方案 A 改 4 处 + 新增信号通道
4. **真机测试可行**：方案 B 下，"拔电源 → 数据超时 → disconnect → state DISCONNECTED"
   链路清晰可测；方案 A 下还要额外测"ConnectionManager 收到 state 后能否正确
   triggerReconnect"，测试面更大

**方案 A 拒收理由**详见 § Alternatives 第 A 节。

## What

本 change 引入 **6 个 Requirement**（R1~R6），对应 6 处精确修复；**A42 由
R1 自动闭环**不单列；**A46** review 文档修订与 R6 同批提交；**A45** 建议捆绑
（evaluator 批准后同批提交）。

---

### R1 死代码清零 + 连接职责收敛（A23 + A42）

**动作 1a**：整个删除 `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt`（145 行）

```
BEFORE:
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt   ← 存在
  - 声明 class ConnectionManager(...)
  - init { ... startFakeConnectionCheck() / bluetoothDataSource.connectionState.collect {/*空*/} }
  - setCurrentDevice / triggerReconnect / _isFakeConnection / lastDataTime 等 145 行

AFTER:
  ConnectionManager.kt   ← 不存在
  grep -R "ConnectionManager" core/ feature/ app/    ← 零命中
```

**动作 1b**：职责边界契约重新划定（写进 spec + 代码注释，不增加新类）

| 层 | 责任 | 实体 |
|---|---|---|
| GATT 协议层 | GATT connect / notify / 数据超时检测 / disconnect / close | `BleConnection`（唯一持有 `bluetoothGatt`）|
| 数据聚合层 | 数据流聚合、`isConnected` 语义、设备切换时清旧 | `BluetoothDataSource` |
| 设备管理层 | 扫描、冷启动重连、设备选择 | `BleDeviceManager` |
| ~~假连接恢复层~~ | ~~废弃~~ | ~~`ConnectionManager`（删除）~~ |

**关键契约**：

- 数据超时 = `BleConnection` 自己的 GATT 生命周期事件，由 `BleConnection`
  内部处理（释放 + 传导 DISCONNECTED 信号）—— **不新增外层"假连接恢复"类**
- 设备切换 = `BluetoothDataSource.connect(addr)` 入口责任，清旧 + 启新
  由 `BluetoothDataSource` 原子化处理（R5）
- 冷启动重连 = `BleDeviceManager.autoReconnectLastDevice()` 负责（R6）

---

### R2 数据超时释放 GATT + race 消除（A24）

**BleConnection.startDataTimeoutCheck**（244-252 行）：

```kotlin
BEFORE:
private fun startDataTimeoutCheck() {
    timeoutJob = scope.launch {
        delay(DATA_TIMEOUT_MS)
        if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
            Log.w(TAG, "数据接收超时，触发重连")
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}

AFTER:
private fun startDataTimeoutCheck() {
    timeoutJob = scope.launch {
        delay(DATA_TIMEOUT_MS)
        // A24 race 消除：delay 结束到 body 执行之间可能被 cancel
        // ensureActive() 让已取消的协程在此刻退出，避免"假断连 → 立即假连接"1-2ms 抖动
        ensureActive()
        if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
            Log.w(TAG, "数据接收超时：释放 GATT 资源")
            // A24 释放 GATT：先 disconnect（异步），真正的 close 由
            // onConnectionStateChange(STATE_DISCONNECTED) 回调执行（与 R3/A40 统一路径）
            bluetoothGatt?.disconnect()
            // 状态信号在 disconnect 回调里也会设（73-77 行），此处显式设一次
            // 加速下游感知（回调可能滞后若干毫秒）；StateFlow equality 保护重复 emit
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
```

**关键点**：

1. **`ensureActive()` 消除 race**：即使 `delay` 刚结束进入 body 时 cancel 被调用，
   `ensureActive()` 抛 `CancellationException` 让协程退出，`_connectionState`
   不会被误改。25Hz 高频 `cancel + restart` 场景下该保护必须存在。
2. **释放改为 `disconnect()` 而非 `disconnect() + close() + null`**：close
   必须由 `onConnectionStateChange(STATE_DISCONNECTED)` 统一负责（见 R3 / A40）。
   此处只触发异步 disconnect，不做资源释放。
3. **仍保留 `_connectionState.value = DISCONNECTED` 显式设**：因为
   `onConnectionStateChange` 可能滞后若干毫秒（Android BT stack 异步），下游
   （`BluetoothDataSource` → `GpsDataViewModel` / UI）需要即刻感知。StateFlow
   equality 保护让重复 emit 不产生假帧。

---

### R3 `disconnect()` close 时机对齐 Android 生命周期（A40）

**BleConnection.disconnect**（172-178 行）：

```kotlin
BEFORE:
fun disconnect() {
    cleanup()
    bluetoothGatt?.disconnect()     // 异步，触发 onConnectionStateChange(STATE_DISCONNECTED)
    bluetoothGatt?.close()          // ⚠️ 立即同步 close
    bluetoothGatt = null            // ⚠️ 立即置 null
    _connectionState.value = ConnectionState.DISCONNECTED
}

AFTER:
fun disconnect() {
    cleanup()
    // A40：只做 disconnect；close + null 由 onConnectionStateChange(STATE_DISCONNECTED) 统一负责
    // 某些厂商实现下 close 后回调仍来，回调访问已关闭 gatt 行为未定义
    bluetoothGatt?.disconnect()
    // _connectionState 不在这里显式设；由 onConnectionStateChange 回调 (73-77 行) 处理
    // 避免"本方法置 DISCONNECTED → 回调又置 DISCONNECTED" 的冗余 emit 与时序差异
}
```

**BleConnection.onConnectionStateChange**（73-77 行，STATE_DISCONNECTED 分支）：

```kotlin
BEFORE:
BluetoothProfile.STATE_DISCONNECTED -> {
    Log.d(TAG, "已断开连接")
    _connectionState.value = ConnectionState.DISCONNECTED
    cleanup()
}

AFTER:
BluetoothProfile.STATE_DISCONNECTED -> {
    Log.d(TAG, "已断开连接（回调）")
    _connectionState.value = ConnectionState.DISCONNECTED
    cleanup()
    // A40：close + null 收敛到唯一位置，确保不管是 disconnect() 主动触发、
    // startDataTimeoutCheck 超时触发、还是远端主动断连，资源释放路径完全一致
    bluetoothGatt?.close()
    bluetoothGatt = null
}
```

**关键点**：

1. close + null **唯一在回调内执行**，保证所有 disconnect 路径（主动 / 超时 /
   远端断连）资源释放流程完全一致
2. 符合 Android 官方文档 standard flow
3. `_connectionState.value = DISCONNECTED` 在 `disconnect()` 里不显式设，让
   回调成为单一真理源 —— 但 **`startDataTimeoutCheck`（R2）仍显式设**，因为
   超时 = 数据层失联，下游需要立刻感知，不能等 GATT 断连回调的异步延迟

---

### R4 `isConnected` 语义收敛（A25）

**两处改动，parser + BluetoothDataSource 联动**：

**动作 4a**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`
的**两个** parse 函数（`parseGpsData` for GPS_MAIN + `parseGpsTimeData` for
GPS_TIME）对称地修三条路径：短包 + catch 写 errorMessage；成功路径显式清
errorMessage 避免 carry 前帧污染（第五轮 review 挖出的漏洞 —— 原本只修
`parseGpsData` 两处，`parseGpsTimeData` 完全漏了，且两个函数成功路径都 carry
前帧 errorMessage）。签名不变，对公共协议零影响。

```kotlin
BEFORE:
// parseGpsData 短包 (行 139-143):
if (data.size < 20) { return currentData }
// parseGpsData catch (行 302-305):
} catch (e: Exception) { Log.e(...); return currentData }
// parseGpsData 成功路径 (行 276 附近):
currentData = currentData.copy(..., isTimeSynced = syncedNow)   // 不清 errorMessage
return currentData
// parseGpsTimeData 短包 (行 71-74):
if (data.size < 3) { Log.e(...); return currentData }
// parseGpsTimeData catch (行 106-109):
} catch (e: Exception) { Log.e(...); currentData }
// parseGpsTimeData 成功路径 (行 101-105):
if (!currentData.isTestReady) { currentData.copy(isTestReady = true) } else { currentData }

AFTER:
// parseGpsData 短包：
if (data.size < 20) {
    return currentData.copy(errorMessage = "short-packet")
}
// parseGpsData catch：
} catch (e: Exception) {
    Log.e(...)
    return currentData.copy(errorMessage = "parse-error: ${e.message}")
}
// parseGpsData 成功路径：显式清 errorMessage
currentData = currentData.copy(..., isTimeSynced = syncedNow, errorMessage = null)
return currentData
// parseGpsTimeData 短包（与 parseGpsData 对称）：
if (data.size < 3) {
    Log.e(...)
    return currentData.copy(errorMessage = "short-packet")
}
// parseGpsTimeData catch：
} catch (e: Exception) {
    Log.e(...)
    currentData.copy(errorMessage = "parse-error: ${e.message}")
}
// parseGpsTimeData 成功路径：显式清 errorMessage
if (!currentData.isTestReady) {
    currentData.copy(isTestReady = true, errorMessage = null)
} else {
    currentData.copy(errorMessage = null)
}
```

**为什么成功路径也 MUST 显式清 errorMessage**：parser 的 `currentData` 参数
是 `_dataFlow.value`，`copy(...)` 在 Kotlin 里默认保留未指定字段。如果前帧
是失败态（errorMessage = "short-packet"），当前帧 parser 成功路径的 `copy`
不会自动清 errorMessage → 返回的 GpsData 同时含"新解析字段 + 前帧 errorMessage"
→ BluetoothDataSource 的 `errorMessage != null` 分支把本帧 parse 成功误解释
为失败 → `isConnected = false`。结果是 **"短包后第一帧成功永远无法恢复
isConnected=true"** 的级联故障，违反 "最近一次 parse 成功" 契约中"最近一次"
的语义。显式 `errorMessage = null` 切断级联。

**两路对称原则**：第五轮 review 挖出的教训 —— 本战役 R4 原本只查了
`parseGpsData` 5 个 catch 的最外层，`parseGpsTimeData` 两处（短包 + catch）+
两个函数的成功路径都漏了。future-proofing：未来新增 UUID 分支时 MUST 按
spec 对称实现失败信号 + 成功清标记。

**动作 4b**：`BluetoothDataSource` 的 `isConnected` 置位仅在 `errorMessage == null` 时生效：

```kotlin
BEFORE (BluetoothDataSource.kt:49-56):
bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
    val gpsData = when (uuid.toString()) {
        "00000003-..." -> parser.parseGpsData(rawData, _dataFlow.value)
        "00000004-..." -> parser.parseGpsTimeData(rawData, _dataFlow.value)
        else -> _dataFlow.value
    }
    _dataFlow.value = gpsData.copy(isConnected = true)
}

AFTER:
bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
    // A25 isConnected 语义收敛：GATT 连上 + **最近一次** parse 成功
    //   已知 UUID（主包 / 时间包）：由 parser 产出 GpsData，走下面的语义收敛
    //   未知 UUID：parseResult == null，整个写入块跳过，_dataFlow.value 完全
    //              不触碰，isConnected 原值保留（不会从 false 被翻转成 true）
    val parseResult: GpsData? = when (uuid.toString()) {
        "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
        "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
        else -> null
    }
    if (parseResult != null) {
        // 已知 UUID 的写入路径：
        //   parse 失败（短包 / catch）：**显式** 设 isConnected=false，堵住
        //     "上一帧成功 → 当前帧失败" 时 parser 的 copy 保留前帧 isConnected=true
        //     导致状态自相矛盾（isConnected=true + errorMessage != null）的漏洞
        //   parse 成功：errorMessage == null → 设 isConnected=true + 显式清 errorMessage
        _dataFlow.value = if (parseResult.errorMessage != null) {
            parseResult.copy(isConnected = false)
        } else {
            parseResult.copy(isConnected = true, errorMessage = null)
        }
    }
}
```

**为什么不继续用 `else -> _dataFlow.value`**：原来那个写法会让 `gpsData`
等于 `_dataFlow.value`，然后下面的 `if (errorMessage != null)` 走 else 分支
`copy(isConnected = true)`，**把 `isConnected` 从 false 强置为 true**。
这违背 "未知 UUID 不触碰下游数据" 的契约（Spec R4 Scenario 4 硬断言 "GIVEN
false → THEN 仍 false"）。用可空 `parseResult` + `if (!= null)` 包裹能让
未知 UUID 分支**完全不进入写入路径**。

**为什么失败分支 MUST 显式 `copy(isConnected = false)` 而不是 `_dataFlow.value = parseResult`**：
parser 的失败路径写法是 `currentData.copy(errorMessage = ...)`，`copy` 保留了
`currentData.isConnected` 字段。若 BluetoothDataSource 失败分支直接
`_dataFlow.value = parseResult`，在 "上一帧成功 (isConnected=true) → 当前帧短包"
路径下，输出的 `_dataFlow.value` 会是 `isConnected = true + errorMessage != null`
—— 状态自相矛盾，违反 "isConnected 充要条件是**最近一次** parse 成功" 契约。
显式 `parseResult.copy(isConnected = false)` 堵死此漏洞。第四轮 mini review
明确要求加入本硬断言（Spec R4 新增 Scenario "成功后失败"）。

**关键点**：

1. `isConnected = true` 的语义 = "GATT 连上 + 最近一次 parse 成功"
2. 短包、异常、未知 UUID 三条路径都**不**标 `isConnected = true`
3. parser 层通过 `errorMessage` 字段传递失败信号，`BluetoothDataSource` 消费
   该字段做语义决策，职责分层清晰
4. 当前 `GpsData.errorMessage` 已存在（原评审 §2.3 / `RaceChronoParser.parseGpsData`
   已用于其他异常），本 change 扩展其使用范围，不新增字段

---

### R5 `BluetoothDataSource.connect()` 切设备前清旧连接（A27）

**BluetoothDataSource.connect**（42-82 行）：

```kotlin
BEFORE:
fun connect(deviceAddress: String) {
    Log.d(TAG, "connect() called with address: $deviceAddress")
    scope.launch {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            bleConnection = BleConnection(context, deviceAddress) { ... }   // 直接覆盖
            bleConnection?.connectionState?.let { stateFlow ->
                connectionCollectJob?.cancel()                              // 旧 collectJob 晚到
                connectionCollectJob = scope.launch { stateFlow.collect { ... } }
            }
            bleConnection?.connect()
        } catch (e: Exception) { ... }
    }
}

AFTER:
fun connect(deviceAddress: String) {
    Log.d(TAG, "connect() called with address: $deviceAddress")
    scope.launch {
        try {
            // A27 切设备前清旧连接（原子化，顺序严格）
            // 1. 先 cancel 旧 collectJob，避免旧 state 传导到 _connectionState 与新 CONNECTING 竞争
            connectionCollectJob?.cancel()
            connectionCollectJob = null
            // 2. 再释放旧 bleConnection（走完整 GATT 释放路径）
            bleConnection?.disconnect()
            bleConnection = null

            // 3. 重置 _connectionState（避免旧终态残留）
            _connectionState.value = ConnectionState.CONNECTING

            // 4. 新建 + 订阅 + connect（原有逻辑）
            bleConnection = BleConnection(context, deviceAddress) { uuid, rawData -> ... }
            bleConnection?.connectionState?.let { stateFlow ->
                connectionCollectJob = scope.launch { stateFlow.collect { state ->
                    _connectionState.value = state
                } }
            }
            bleConnection?.connect()
        } catch (e: Exception) { ... }
    }
}
```

**关键点**：

1. **顺序严格**：先 cancel collectJob，再 disconnect 旧 bleConnection，再
   新建 —— 避免旧 state 最后一帧通过 collectJob 传导到 `_connectionState`
   与新 CONNECTING 竞争
2. `disconnect()` 调用走 R3 修订后的路径（只触发 gatt.disconnect 异步，close
   由回调处理），但 `bleConnection = null` 是引用释放，不依赖 close 完成
3. 幂等性：`bleConnection?.disconnect()` 对已 null 引用无副作用，重复调
   `connect(sameAddr)` 不出问题

---

### R6 `autoReconnectLastDevice` else 分支 fallback 扫描（A29 + A46）

**BleDeviceManager.autoReconnectLastDevice**（59-87 行 else 分支）：

```kotlin
BEFORE (85-87 行):
} else {
    Log.d(TAG, "没有上次连接的设备记录")   // 只 log 不 scan
}

AFTER:
} else {
    // A29 修复：冷启动 else 分支 fallback 到扫描
    // 当前 lastDeviceAddress 硬编码 null → 冷启动必走此分支
    // 用户期望"打开 app 看到设备列表"，当前实现"打开 app 看到空列表要手动点扫描"
    // 修订 review § 11.6 对应叙述（A46）
    Log.d(TAG, "没有上次连接的设备记录，fallback 到扫描")
    startScan()
}
```

**动作 6b** —— A46 review 文档修订：

`docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md § 11.6`
按 `2026-04-22-lap-timing-and-gps-adversarial-review.md § 3.2` 的替代文本改写为：

> **11.6 `BleDeviceManager.autoReconnectLastDevice` 实质未实现，且 `else` 分支
> 原先不 fallback**（本战役 G `fix-ble-connection-lifecycle` 已修复 A29：
> else 分支改为 `startScan()`）。`lastDeviceAddress` 硬编码 null 的 TODO 留
> 待下一个战役接入 `BluetoothDeviceRepository`。

**动作 6c**（建议同批，评审方确认后捆绑）—— **A45** review 文档修订：

`docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md § 11.5`
按 `2026-04-22-lap-timing-and-gps-adversarial-review.md § 3.1` 的替代文本改写为：

> **11.5 假连接恢复未实现（ConnectionManager 已删）**
> 原叙述"`ConnectionManager` 和 `BleConnection` 双层超时互相干涉"与事实相反 ——
> `ConnectionManager` 从未被 DI 注册、实例化、调 `setCurrentDevice`，是 dead code。
> 战役 G `fix-ble-connection-lifecycle` 采纳方案 B 整体删除 `ConnectionManager.kt`，
> 假连接检测与 GATT 释放收敛到 `BleConnection.startDataTimeoutCheck`（见 11.3）。

## Impact

### 协议与数据模型

- **不改** `GpsData` 数据类字段定义（`errorMessage` 字段已存在，本 change 只扩展使用）
- **不改** `ConnectionState` 枚举定义
- **不改** `BleConnection` / `BluetoothDataSource` / `BleDeviceManager` 任何 public 方法签名
- **不改** BLE 协议格式 / Service UUID / Characteristic UUID / RaceChrono 字段解析逻辑
- **不改** parser 的字段级解析输出（`errorMessage` 从未作为"数据成功"信号使用，
  本 change 让 `BluetoothDataSource` 在 `errorMessage != null` 时不置 isConnected
  —— 这是"更保守的下游消费"，不是"parser 输出契约变更"）

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| BLE（核心） | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt` | **整文件删除**（R1） |
| BLE（核心） | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt` | `startDataTimeoutCheck` 加 `ensureActive()` + 改释放路径（R2）；`disconnect()` 只 disconnect 不 close（R3）；`onConnectionStateChange(STATE_DISCONNECTED)` 回调补 close + null（R3） |
| BLE（核心） | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt` | `connect()` 开头补清旧连接（R5）；回调内 `_dataFlow.value = ...` 逻辑改为按 `errorMessage` 分支设 isConnected（R4） |
| BLE（核心） | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt` | `autoReconnectLastDevice` else 分支改 `startScan()`（R6） |
| 协议解析 | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` | `parseGpsData` 短包 / catch 分支设 `errorMessage`（R4）—— **零字段级输出变更**，仅新增错误标记 |
| DI | `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` | 若有 `ConnectionManager` factory（当前无）顺手清理；其他 factory 不动 |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BleConnectionTest.kt`（新建） | R2 / R3 回归（需 Robolectric 或 fake GATT） |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BluetoothDataSourceTest.kt`（新建） | R4 / R5 回归 |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BleDeviceManagerTest.kt`（新建） | R6 回归 |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`（扩展） | R4 parser 短包 / catch 分支 `errorMessage` 断言 |
| 文档 | `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md` | § 11.5（A45，建议捆绑）/ § 11.6（A46）文字替换 |
| 文档 | `docs/superpowers/reviews/attack-backlog.md` | A23/A24/A25/A27/A29/A40/A42 状态迁 🟢 `pending_review` + A46（+A45 如捆绑）同上 |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| 冷启动（无 lastDeviceAddress）| 只 log 不 scan，用户必须手动点扫描 | 自动 startScan，用户看到设备列表 |
| 数据超时（ESP32 假连接）| 只改 state，GATT client 槽位永占、UI 可能 1-2ms state 抖动 | `ensureActive` 消除抖动 + `gatt.disconnect()` 触发回调释放 + state=DISCONNECTED；GATT 资源在回调内 close + null |
| 主动 `disconnect()` | 同步 close 后可能再有回调访问已关闭 gatt，行为未定义 | 只 `gatt.disconnect()`，close + null 由回调统一处理 |
| 切换设备（A→B）| 旧 GATT 永久挂起 + 旧 collectJob 可能把终态传导到新 state 流 | 先 cancel collectJob → 再 disconnect 旧 → 再新建，原子化 |
| GPS_MAIN 短包 / parse 异常（上一帧失败态） | `gpsData.isConnected = true`，`errorMessage` 未设 | `gpsData.errorMessage` 设为 "short-packet"/"parse-error"；`isConnected = false`（显式翻转，不依赖"保留原值"） |
| GPS_MAIN 短包 / parse 异常（上一帧成功态，isConnected=true） | 同上 | **显式** `parseResult.copy(isConnected = false)` 把 parser 带回来的 true 翻转；否则输出 `isConnected=true + errorMessage != null` 状态自相矛盾（契约破） |
| GPS_TIME 短包（<3 字节）/ parse 异常 | `gpsData.isConnected` 被后续无条件置 true | 与 GPS_MAIN 对称：parser 写 errorMessage → 下游 copy(isConnected=false)（第五轮 review 修补） |
| 短包/异常后紧接的第一帧 parse 成功 | 下游走失败分支（parser 成功 carry 前帧 errorMessage），`isConnected` 无法恢复 true | parser 成功路径显式 `errorMessage = null` 切断级联，`isConnected` 恢复 true（契约闭合，第五轮 review 修补） |
| 未知 UUID | `_dataFlow.value` 被整帧 `copy(isConnected = true)` 强置（equality 保护当前场景下同值不 emit，但字段已被改为 true） | `parseResult == null` → 整个写入块跳过，`_dataFlow.value` 完全不触碰，`isConnected` 原值（含 false）保留 |
| `ConnectionManager.xxx` 任何引用 | 编译通过但运行时死代码 | 编译失败 —— 强制实施方显式面对死代码引用（预期全仓 grep 后零命中，零编译失败）|
| ESP32 拔电源 → 自动重连 | 不会自愈（用户需杀进程） | **仍不会完全自愈**（本战役 Non-goal，见下节）；但 GATT 资源干净释放 + DISCONNECTED 信号可靠传导，为下一战役的自愈层铺路 |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| 删除 `ConnectionManager.kt` 导致其他模块编译失败 | 预置条件：grep -R `ConnectionManager` 全仓确认零引用。当前证据已核实（A23 § 证据段）。tasks.md 合流门槛第 1 项是 `:core:bluetooth:assembleDebug` 成功 |
| R2 `ensureActive()` 打破现有 race 但引入未覆盖的新路径 | 新增测试 `BleConnectionTest.startDataTimeoutCheck_rapidCancelRestart_doesNotProduceSpuriousDisconnected` 构造"cancel + restart" 100 次循环 + 断言 state 稳定 CONNECTED |
| R3 close 挪到回调，若 Android 回调不达会导致 GATT 永不释放 | 严格按 Android 官方 standard flow，回调缺失是 Android BT stack bug 非本 change 引入；Pair 保护：`disconnect()` 调用后 5 秒内若回调未来，timeoutJob（R2）会再触发 `disconnect()` 构成 retry。但这属于下一战役 Non-goal，本 change 假设 Android BT stack 行为符合官方文档 |
| R4 parser 改动可能影响现有测试 | parser 短包 / catch 分支当前测试未断言 `errorMessage` 为 null（实际上就 null），新增 `errorMessage != null` 不破坏现有断言；新测试补齐硬区分断言 |
| R5 `bleConnection?.disconnect()` 调用时旧连接可能还在 CONNECTING 中（GATT 没 connect 上），disconnect 可能产生非预期回调 | disconnect 对未完成 connect 的 gatt 安全（Android 文档），不会抛；且 `bleConnection = null` 立即释放引用 |
| R6 else 分支改为 startScan 导致冷启动高概率触发 BLE 扫描（耗电）| 当前冷启动原本就在"有 lastDeviceAddress + 超时未连"路径调 startScan（84 行），扫描行为已存在；本 change 只是让 else 分支也走相同路径，耗电行为等价。未来接入 `BluetoothDeviceRepository` 后自然回到"先重连 lastDeviceAddress" 优先路径 |
| A45 / A46 文档修订若未同批导致 review 仍留错误前提 | A45/A46 是 review 文档改字串，0 代码影响；tasks.md 合流门槛第 6 项明确列出文档修订并标记 diff 验证 |

### 回归保护要求

每个 Requirement 必须有**硬区分 v1/v2 行为**的测试（按用户偏好"测试断言路径级"）：

- **R1 × 2 条**（死代码清零 + 职责契约）：
  - `core:bluetooth` 模块 grep + 编译：`find core/bluetooth/src/main -name "ConnectionManager.kt"` 零命中 + `./gradlew :core:bluetooth:assembleDebug` 成功
  - `AppModule.kt` grep `ConnectionManager` 零命中
- **R2 × 2 条**：
  - `BleConnectionTest.startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected` —— fake GATT 观察 `disconnect()` 被调 + `_connectionState.value == DISCONNECTED`
  - `BleConnectionTest.startDataTimeoutCheck_rapidCancelRestart_doesNotProduceSpuriousDisconnected` —— 100 次 `handleCharacteristicChange` 模拟（触发 cancel + restart），断言 state 始终 CONNECTED（v1 会看到 1-2 ms 抖动，v2 不会）
- **R3 × 2 条**：
  - `BleConnectionTest.disconnect_doesNotCloseGattBeforeStateDisconnectedCallback` —— 调 `disconnect()` 后立即检查 `bluetoothGatt` 非 null；待 fake 回调触发后检查 `bluetoothGatt == null`
  - `BleConnectionTest.onConnectionStateChange_stateDisconnected_closesGattAndNullsReference` —— fake 回调触发 `STATE_DISCONNECTED` → 断言 `bluetoothGatt == null`
- **R4 × 3 条**：
  - `RaceChronoParserTest.parseGpsData_shortPacket_setsErrorMessageShortPacket`
  - `RaceChronoParserTest.parseGpsData_throwsException_setsErrorMessageParseError`
  - `BluetoothDataSourceTest.onDataReceived_parseError_doesNotFlagIsConnectedTrue` —— 构造短包 → `_dataFlow.value.isConnected == false`（v1 为 true）
- **R5 × 2 条**：
  - `BluetoothDataSourceTest.connect_whileAlreadyConnected_releasesPreviousConnection` —— 先 connect(A)、等 state CONNECTED、再 connect(B)，断言旧 bleConnection.disconnect() 被调且引用已置 null
  - `BluetoothDataSourceTest.connect_cancelsPreviousCollectJobBeforeNewConnection` —— fake state flow 观察旧 collectJob 的 `isActive == false` 早于新 collectJob 启动
- **R6 × 2 条**：
  - `BleDeviceManagerTest.autoReconnectLastDevice_whenLastAddressNull_fallsBackToStartScan` —— 断言 `scanner.startScan()` 被调（v1 不调）
  - `BleDeviceManagerTest.autoReconnectLastDevice_reviewFileIsFixed` —— grep `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md` 中 § 11.6 新替代文本存在

## Alternatives

### A：A23 选方案 A 接线

**拒收理由**：

1. **死代码复活的心智负担**：`ConnectionManager` 本就从未运行。接线等于"把
   一个从未被验证的类塞回生产链路"，没有历史数据支撑其正确性，后续每次怀疑
   连接问题都要先排查"是 BleConnection 层还是 ConnectionManager 层"。
2. **接口新增**：方案 A 要让 `ConnectionManager` 能感知数据超时，但当前它只
   collect `connectionState`。接线方案要：
   - (a) 改 `BleConnection.startDataTimeoutCheck` 为"log-only，不改 state"（否则
     两层都修 state 回到"双层打架"）
   - (b) 新增 `BleConnection.dataTimeoutSignal: StateFlow<Boolean>` 供
     `ConnectionManager` 订阅
   - (c) 实现 `ConnectionManager.init` 的 TODO（从 dataFlow 提取 deviceAddress
     填 `currentDeviceAddress`）
   - (d) 在 `BleDeviceManager.connect` 或 `BluetoothDataSource.connect` 调
     `connectionManager.setCurrentDevice(deviceAddress)`
   - (e) `AppModule` 新增 `single { ConnectionManager(get(), get()) }` 依赖
     注入
   - 5 项改动 vs 方案 B 的 2 项，改动面翻倍
3. **A42 必修复而非自然消失**：方案 A 下 `init` 的空 collect 必须实现 TODO，
   否则占一个 IO 协程；方案 B 删文件零成本
4. **测试面翻倍**：方案 A 要测两个类的协作，方案 B 只测单个类（`BleConnection`）
   的完整路径
5. **当前场景不需要**：app 是单设备（`BleDeviceManager` one-shot
   `lastDeviceAddress`），"多设备并发 + 分层恢复策略"是未来可能的需求，不是
   当前需求。YAGNI

### B：A23 选"保留 ConnectionManager 但只作为观测器，不参与恢复动作"

**拒收理由**：观测器模式 = "占内存但不做事"，本质还是死代码，且误导后来者
"这里是假连接检测层"。评审方明确指令是"必须有'谁负责重连'单一职责契约"——
保留一个不做事的类违反此契约。

### C：A40 只 `disconnect()` 不 close，且不把 close 挪到回调（依赖 JVM GC）

**拒收理由**：`BluetoothGatt.close()` 释放的是 Android BT stack 的 GATT
client 槽位（每个 app 上限约 30），**不是 JVM 堆对象**。GC 不负责释放 BT
stack 资源。不 close = 长期运行必然耗尽槽位 → 新 connect 失败。

### D：A25 改为"直接废弃 `isConnected` 字段，只用 `ConnectionState`"

**拒收理由**：

1. `isConnected` 被多个下游消费（`GpsData.Empty` 默认值、UI 质量指示、
   `TestSessionViewModel` 某些状态转换判断等），废弃字段的侵入面过大，不属于
   BLE 生命周期 scope
2. 字段本身语义合理（"当前数据流是否来自有效连接"），问题仅在于**写入它的
   条件**。R4 收敛写入条件即可，不必废弃字段
3. 废弃字段 → 所有消费者要重构 → 超出本战役 scope

### E：A29 选方案 (b)（接入 `BluetoothDeviceRepository` 持久化 lastDeviceAddress）

**暂拒理由**：

1. `BluetoothDeviceRepository` 虽在 `core/data/` 下已有基础设施，但**本 change
   不依赖持久化重连**。方案 (a) 用 `startScan()` 已经解决"冷启动空列表"UX
   问题
2. 方案 (b) 要做真正"15 秒拔电源自愈"，需要：
   - (i) Repository 写路径：`connect(addr)` 成功后保存
   - (ii) 冷启动读路径：`autoReconnectLastDevice` 读 Repository 替代硬编码 null
   - (iii) 运行时自愈：`BleDeviceManager` 监听 `connectionState` → DISCONNECTED
     时触发重连循环 + 最大重试次数 + 指数退避
   - (iv) `GpsDataViewModel.resetStats` 在断连时重置（A28）
   - 这是一个独立的"BLE 自愈层"战役（候选名 `fix-ble-reconnection-layer`），
     与本战役的"BLE 生命周期 Correctness"scope 不同
3. **本战役 Non-goals**里显式声明端到端 15 秒自愈不做，并建议评审方同步修订
   A23 核销条件（见下）

### F：A45 不捆绑，独立追踪

**可接受但不推荐**。A45 是修订原 review § 11.5 关于 ConnectionManager 的
叙述 —— 本战役决策方案 B 删除之后，原 review 的"双层超时互相干涉"叙述 100%
过时。不修订 = 留一个与当前代码事实相反的 review 文档作为未来决策输入。
0 代码成本的文档修订建议同批处理。

### 建议评审方同步修订 A23 核销条件

原 A23 核销条件第 (2) 项：

> 真机集成测试：连 ESP32 → 拔电源（模拟假连接）→ 断言 15 秒内自动重连

**本 change 不能满足此条件**，因为端到端自愈依赖 `BluetoothDeviceRepository`
接入（独立战役）。建议修订为：

> 真机 log 审计：连 ESP32 → 拔电源（模拟假连接）→ 断言：
> - (2a) `BleConnection._connectionState` 在 DATA_TIMEOUT_MS + 1s 内变为 DISCONNECTED
> - (2b) log 有 "数据接收超时：释放 GATT 资源" 条目
> - (2c) log 有 `STATE_DISCONNECTED` 回调条目，且回调后 GATT 资源已释放
>   （通过 `bluetoothManager.getConnectedDevices(...)` 返回列表不含该设备地址验证）

端到端"15 秒自动重连"改为下一个战役（`fix-ble-reconnection-layer`）的核销条件。

## Non-goals

本 change **不做**以下事项，明确划定 scope：

### 不改的文件 / 模块

- **不改** BLE 协议格式、Service UUID、Characteristic UUID、CCCD UUID
- **不改** `GpsData` 数据类字段（只扩展 `errorMessage` 使用场景）
- **不改** `ConnectionState` 枚举值
- **不改** `RaceChronoParser` 的字段级解析输出（只在失败路径加 `errorMessage` 标记）
- **不改** `LapTimingEngine` / `GpsDataFilter` / `TestSessionViewModel` /
  `GpsDataViewModel` 任何核心逻辑（与 engine-entry-hardening / filter 战役 /
  clock-source-integrity 战役完全解耦）

### 不做的功能

- **不实现**"拔电源 → 15 秒自动重连"端到端链路。端到端自愈依赖
  `BluetoothDeviceRepository` 持久化 + `BleDeviceManager` 自愈循环，拆到
  下一战役 `fix-ble-reconnection-layer`
- **不接入** `BluetoothDeviceRepository`。`BleDeviceManager.autoReconnectLastDevice`
  的 `lastDeviceAddress` 硬编码 null 保持不变，TODO 留到下一战役解决
- **不改** `FileLogger` 主线程同步 I/O（A18，战役 F 性能）
- **不清理** `AnomalyDetector` / `DataInterpolator` 半接线状态（A30，战役 H 清理）
- **不修** `GpsDataViewModel.updateDataStats` 频率累积平均退化（A28，战役 F 性能）
- **不修** `parser.parseGpsTimeData` 无条件写 `isTestReady = true`（A26，战役 H parser）
- **不修** `parser.totalDistance` 死状态（A41，战役 H parser）
- **不修** `parser` signed int decoding（A16，战役 D 尾巴）
- **不修** `GpsDataFilter.circularMedian` 命名（A43）、跨经度（A44）

### 不做的测试覆盖

- **不做**真机 / 集成端到端"15 秒自愈"测试（见 Alternatives § 建议评审方修订 A23 核销条件）
- **不做** Robolectric + 真机 stack pair 测试（BleConnection 的 GATT 行为
  用 fake GATT stub 覆盖即可，真机 pair 留给自愈层战役）

### 不做的文档修订

- **不修**`2026-04-21-lap-timing-review.md` 的任何条目（与 engine-entry-hardening 战役 Non-goals 对齐）
- **不修**`2026-04-22-lap-timing-and-gps-adversarial-review.md` 的任何条目（是源头
  文档，不是修订对象；本战役仅触碰 `2026-04-22-gps-ingestion-and-filter-review.md` § 11.5 / § 11.6）
