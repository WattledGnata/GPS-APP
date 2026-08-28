# Spec Delta: ble-connection

> Capability: **BLE 设备连接的全生命周期管理**。涵盖 `BleConnection`（GATT
> 协议层）+ `BluetoothDataSource`（数据聚合层）+ `BleDeviceManager`（设备
> 管理层）三个类的协作契约 + parser 失败信号传递。
>
> 核心原则：
>
> 1. **单一职责**："谁负责什么"由本 spec 的 Requirement 1（职责矩阵）锁定。
>    数据超时归 `BleConnection`；设备切换清旧归 `BluetoothDataSource`；
>    冷启动重连归 `BleDeviceManager`；**无"假连接恢复层"**（`ConnectionManager`
>    已整体删除）。
> 2. **GATT 资源释放唯一位置**：`BluetoothGatt.close() + bluetoothGatt = null`
>    只在 `BleConnection.onConnectionStateChange(STATE_DISCONNECTED)` 回调内
>    执行，不论 disconnect 是主动（`disconnect()`）、数据超时（`startDataTimeoutCheck`）
>    还是远端触发。
> 3. **`isConnected` 语义收敛**：`GpsData.isConnected == true` 的充要条件是
>    "GATT 连上 + 最近一次 parse 成功（`errorMessage == null`）"。parser
>    层通过 `errorMessage` 字段传递失败信号，`BluetoothDataSource` 消费该
>    字段做语义决策。
>
> 依赖关系：
>
> - R1（死代码清零）是 R2~R5 所有动作的前提：`BleConnection` 超时路径补完整
>   GATT 释放（R2）、`disconnect` close 挪回调（R3）、`BluetoothDataSource`
>   清旧连接（R5）都依赖"`ConnectionManager` 不再存在、`BleConnection` 是
>   GATT 生命周期唯一持有者"的契约。
> - R6（冷启动 fallback 扫描）独立于 R1~R5，但同批处理：R6 的 Scenario 既
>   覆盖代码行为（`startScan()` 被调），也覆盖原 review `2026-04-22-gps-ingestion-and-filter-review.md § 11.6`
>   文档修订（A46）—— 代码事实与文档叙述必须一致。
>
> 不影响已闭环的 change：
>
> - `fix-laptime-clock-source-integrity`（战役 A）：parser `isTimeSynced` 字段
>   单源派生、sentinel `Long.MIN_VALUE` —— 本 change 对 parser 的改动仅在
>   **失败路径**写 `errorMessage`，不涉及 `isTimeSynced` / `timestampMillis`
>   / sentinel 机制
> - `fix-gps-data-filter-signal-loss-and-anomaly-hygiene`（战役 C filter）：
>   `GpsDataFilter` 信号丢失重置顺序 / 异常帧隔离 —— 本 change 不触碰 filter
> - `fix-lap-timing-engine-entry-hardening`（战役 C engine）：engine 入口
>   白名单 / ts 单调守卫 —— 本 change 不触碰 engine

## ADDED Requirements

### Requirement: 死代码清零与连接职责单一化（A23 + A42）

`ConnectionManager.kt` MUST 整体删除。项目中 MUST NOT 存在任何对
`ConnectionManager` 符号的引用（类定义、导入、DI 注册、实例化、方法调用
均为零）。连接相关职责 MUST 按以下单一矩阵归属：

| 职责 | 归属类 |
|---|---|
| GATT 连接（`BluetoothGatt` 持有与释放） | `BleConnection`（唯一） |
| 数据接收超时检测 + GATT 超时释放触发 | `BleConnection.startDataTimeoutCheck` |
| 数据聚合与 `isConnected` 语义 | `BluetoothDataSource` |
| 设备切换时旧连接清理 | `BluetoothDataSource.connect()` |
| 冷启动自动重连 / fallback 扫描 | `BleDeviceManager.autoReconnectLastDevice` |

MUST NOT 新增任何"假连接恢复层"类或接口；MUST NOT 在 `BleConnection` 之外
的模块持有 `BluetoothGatt` 引用。

关键属性：

- **A42 自动闭环**：`ConnectionManager.init` 的空 collect (`bluetoothDataSource.connectionState.collect { ... /* 空注释 */ }`)
  随文件删除消失，不再占用 IO 协程
- **职责边界反例**：`BleDeviceManager` MUST NOT 持有 `BluetoothGatt`；
  `BluetoothDataSource` MUST NOT 直接调 `gatt.disconnect/close`（一切 GATT
  操作经 `BleConnection` 方法）

#### Scenario: ConnectionManager.kt 文件不存在于代码库

- **GIVEN** 项目根目录为 `/Users/wattledgnata/traeProjects/gps-app`
- **WHEN** 执行 `find core/bluetooth/src/main -type f -name "ConnectionManager.kt"`
- **THEN** 输出为空（v1 下输出为 `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt`，v2 为空）

#### Scenario: 全仓 grep ConnectionManager 零命中

- **GIVEN** 项目根目录为 `/Users/wattledgnata/traeProjects/gps-app`
- **WHEN** 执行 `grep -R "ConnectionManager" core/ feature/ app/ 2>/dev/null`
- **THEN** 输出为空
- **AND** 作为反证，v1 下最少命中 2 行（`ConnectionManager.kt:12` 类定义 +
  `ConnectionManager.kt:17` TAG 常量）

#### Scenario: AppModule 不注册 ConnectionManager factory / single

- **GIVEN** `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- **WHEN** 执行 `grep -n "ConnectionManager" feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- **THEN** 输出为空
- **AND** `:core:bluetooth:assembleDebug` 编译成功（无未解析的 ConnectionManager 符号）

#### Scenario: GATT 资源唯一所有者是 BleConnection

- **GIVEN** `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/` 下所有源文件
- **WHEN** 执行 `grep -rn "BluetoothGatt" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/`
- **THEN** 对 `BluetoothGatt` 字段的持有（例如 `private var bluetoothGatt: BluetoothGatt?`）
  MUST 仅出现在 `BleConnection.kt`
- **AND** `BluetoothDataSource.kt` / `BleDeviceManager.kt` / `BleDeviceScanner.kt`
  MUST NOT 持有 `BluetoothGatt` 字段

---

### Requirement: 数据超时必须释放 GATT 并消除 cancel/restart race（A24）

`BleConnection.startDataTimeoutCheck` MUST 满足：

1. `delay(DATA_TIMEOUT_MS)` 结束后在访问 `lastDataTime` 与修改 `_connectionState`
   之前，MUST 调用 `ensureActive()`（或等效的 `isActive` 检查），以保证已被
   `timeoutJob?.cancel()` 取消的协程**不**再跑完 body
2. 判定数据超时成立后，MUST 调用 `bluetoothGatt?.disconnect()` 触发 Android
   官方 disconnect 异步流程
3. MUST 显式 `_connectionState.value = ConnectionState.DISCONNECTED` 让下游
   即刻感知（回调链路异步，下游不能等回调）
4. MUST NOT 直接调 `bluetoothGatt?.close()` 或 `bluetoothGatt = null`（close
   + null 统一由 `onConnectionStateChange(STATE_DISCONNECTED)` 回调处理，见 R3）

关键属性：

- **race 消除**：25Hz `handleCharacteristicChange` 每帧 `timeoutJob?.cancel() +
  startDataTimeoutCheck()`，`cancel()` 对已出 delay 进入 body 的协程不保证
  即时停。`ensureActive()` 是 Kotlin coroutines 的标准取消感知点，处在 body
  开头能防止"假超时触发"
- **与 R3 的路径统一**：无论超时触发还是主动 `disconnect()` 触发，GATT 资源
  释放都经 `onConnectionStateChange` 回调 —— 单一释放路径避免 close 时机错位

#### Scenario: 超时成立时 GATT disconnect 被调用且 state 变 DISCONNECTED

- **GIVEN** `BleConnection` 已连接（state CONNECTED），`lastDataTime = 0L`
  （模拟 DATA_TIMEOUT_MS 前无任何数据）
- **WHEN** 调用 `startDataTimeoutCheck()` 后 `delay(DATA_TIMEOUT_MS)` 结束
- **THEN** `bluetoothGatt.disconnect()` 被调用一次
- **AND** `_connectionState.value == ConnectionState.DISCONNECTED`
- **AND** `bluetoothGatt.close()` **未**被 `startDataTimeoutCheck` 直接调用
  （close 留给回调处理）
- **AND** `bluetoothGatt` 字段此刻**非 null**（引用释放由回调负责）

#### Scenario: cancel 发生在 delay 结束后 body 执行前，body 不改 state（硬区分 v1/v2）

- **GIVEN** `BleConnection` 已连接（state CONNECTED），`handleCharacteristicChange`
  在短时间内触发 100 次连续 `timeoutJob?.cancel() + startDataTimeoutCheck()`
  循环
- **AND** 测试环境让每次 `delay(DATA_TIMEOUT_MS)` 恰在 `cancel()` 到达前 1ms
  结束（模拟高频 cancel race）
- **WHEN** 100 次循环跑完
- **THEN** `_connectionState.value` 始终保持 `ConnectionState.CONNECTED`（**不**
  产生任何 DISCONNECTED 瞬间）
- **AND 硬区分 v1**：v1 无 `ensureActive()`，100 次循环中至少出现 1 次
  DISCONNECTED → CONNECTED 的抖动（bouncing count > 0）；v2 有 `ensureActive()`，
  抖动 count == 0

---

### Requirement: disconnect 的 close 时机对齐 Android 生命周期（A40）

`BleConnection.disconnect()` MUST 只调 `bluetoothGatt?.disconnect()`，
MUST NOT 同步调 `bluetoothGatt?.close()`，MUST NOT 同步执行 `bluetoothGatt = null`。

`BleConnection.onConnectionStateChange(STATE_DISCONNECTED)` 分支 MUST 调
`bluetoothGatt?.close()` 并执行 `bluetoothGatt = null`，作为 GATT 资源
释放的**唯一位置**。

关键属性：

- **统一释放路径**：主动 `disconnect()`、数据超时 `startDataTimeoutCheck`、
  远端主动断开三条路径全部经 `onConnectionStateChange(STATE_DISCONNECTED)`
  回调释放资源
- **遵守 Android 官方 standard flow**：某些厂商实现下 close 后仍收到回调，
  回调访问已 closed gatt 行为未定义

#### Scenario: 主动 disconnect 后 close 推迟到回调

- **GIVEN** `BleConnection` 已连接（state CONNECTED），`bluetoothGatt` 非 null
- **WHEN** 调用 `disconnect()`，立即同步检查 `bluetoothGatt` 字段
- **THEN** `bluetoothGatt.disconnect()` 被调用一次
- **AND** `bluetoothGatt.close()` 此刻**未**被调用
- **AND** `bluetoothGatt` 字段此刻**非 null**（引用未释放）
- **AND 硬区分 v1**：v1 下 `disconnect()` 返回后 `bluetoothGatt.close()` 已被
  调用且 `bluetoothGatt == null`；v2 下未调用且非 null

#### Scenario: STATE_DISCONNECTED 回调触发 close + null

- **GIVEN** `BleConnection` 已调 `disconnect()` 或 `startDataTimeoutCheck` 超时，
  `bluetoothGatt` 此刻非 null
- **WHEN** Android BT stack 触发 `gattCallback.onConnectionStateChange(gatt,
  status, BluetoothProfile.STATE_DISCONNECTED)` 回调
- **THEN** `bluetoothGatt.close()` 被调用一次
- **AND** `bluetoothGatt` 字段变为 `null`
- **AND** `_connectionState.value == ConnectionState.DISCONNECTED`
- **AND** `cleanup()` 被调用（取消 timeoutJob、清 pendingCharacteristics 等）

#### Scenario: 数据超时触发的 disconnect 也走同一条回调释放路径

- **GIVEN** `startDataTimeoutCheck` 数据超时成立触发了 `bluetoothGatt.disconnect()`（R2）
- **WHEN** Android BT stack 触发 `STATE_DISCONNECTED` 回调
- **THEN** 回调内 `bluetoothGatt.close()` + `bluetoothGatt = null` 照常执行
  （与主动 disconnect 路径完全一致，不区分触发来源）

---

### Requirement: `isConnected` 语义收敛为 "GATT 连上 + parse 成功"（A25）

`BluetoothDataSource` 在回调内写 `_dataFlow.value` 时，MUST 满足：

- 当 `gpsData.errorMessage != null` 时，MUST **显式** 置 `isConnected = false`
  （保留 parser 设置的 `errorMessage`）。**不能只写 `_dataFlow.value = gpsData` 保留
  字段** —— parser 的失败路径是 `currentData.copy(errorMessage = ...)`，`copy` 保留
  了上一帧的 `isConnected` 字段；若不显式翻转 `isConnected=false`，"上一帧成功
  (isConnected=true) → 当前帧短包 / catch" 的路径会输出 `isConnected = true +
  errorMessage != null` 的状态自相矛盾，破坏"最近一次 parse 成功"契约。
- 当 `gpsData.errorMessage == null` 时，MUST 置 `isConnected = true` 且
  `errorMessage = null`
- 未知 UUID 分支 MUST 返回 `_dataFlow.value` 原值，MUST NOT 执行
  `copy(isConnected = true)`

**关键契约**：`GpsData.isConnected == true` 的充要条件是 "GATT 连上 + **最近一次**
parse 成功"。"最近一次" 意味着每帧失败都必须把 `isConnected` 翻转回 false，哪怕
前帧是成功态 —— 不能用"保留原值"兜底。

`RaceChronoParser` 的 **两个 parse 函数**（`parseGpsData` for GPS_MAIN + `parseGpsTimeData`
for GPS_TIME）MUST 对称地通过 `GpsData.errorMessage` 字段传递信号：

- **短包**（`parseGpsData` 的 `data.size < 20` / `parseGpsTimeData` 的
  `data.size < 3`）：`return currentData.copy(errorMessage = "short-packet")`
- **`try/catch` 捕获异常**：`return currentData.copy(errorMessage = "parse-error: ${e.message}")`
- **成功路径（契约闭合，关键）**：MUST 在 copy 中显式 `errorMessage = null`，**不能**
  依赖默认 copy 行为"保留前帧 errorMessage"。parser 的 `currentData` 参数是
  `_dataFlow.value`，如果前帧是失败态（errorMessage != null），不显式清会让当前
  成功帧仍 carry 前帧的 errorMessage → BluetoothDataSource 失败分支把"本帧 parse
  成功"误走失败分支置 isConnected=false → "短包之后第一帧成功"永远无法恢复
  isConnected=true 的**级联故障**。

**两路对称原则**：第五轮 review 指出只修 GPS_MAIN 一路漏了 GPS_TIME，教训是
parser 的每条调用路径都必须闭合契约；未来新增 UUID 分支（例如 GPS_V2 / GPS_EXT）
时 MUST 按此 spec 要求对称实现失败信号 + 成功清标记。

关键属性：

- **parser 契约无破坏**：`errorMessage` 字段已存在于 `GpsData`，本 change 仅
  扩展失败路径的使用
- **下游透明**：任何读 `isConnected` 的消费者（UI、`GpsDataViewModel` 等）
  无需改代码，语义自然收紧

#### Scenario: 短包进入回调不污染 isConnected（硬区分 v1/v2）

- **GIVEN** `BluetoothDataSource` 已连接，`_dataFlow.value.isConnected == false`
  （初始态）
- **WHEN** `BleConnection` 回调送入一个 UUID = GPS_MAIN 的短包（`data.size == 10`）
- **AND** `parser.parseGpsData(rawData, currentData)` 返回
  `currentData.copy(errorMessage = "short-packet")`
- **THEN** `_dataFlow.value.isConnected == false`（未被污染）
- **AND** `_dataFlow.value.errorMessage == "short-packet"`
- **AND 硬区分 v1**：v1 下 `_dataFlow.value.isConnected == true`（被强置）；
  v2 下保持 `false`

#### Scenario: parse 抛异常不污染 isConnected

- **GIVEN** `BluetoothDataSource` 已连接
- **WHEN** `BleConnection` 回调送入一个畸形主包，`parser.parseGpsData` 内
  `try/catch` 捕获 `NumberFormatException`
- **AND** parser 返回 `currentData.copy(errorMessage = "parse-error: ...")`
- **THEN** `_dataFlow.value.isConnected` 保持当前值（若当前为 false 则仍 false）
- **AND** `_dataFlow.value.errorMessage` 含 "parse-error" 前缀

#### Scenario: 上一帧成功后当前帧短包 MUST 把 isConnected 翻转回 false（硬断言 true → false）

- **GIVEN** `_dataFlow.value.isConnected == true`（模拟上一帧成功 parse 的结果）
- **AND** `_dataFlow.value.errorMessage == null`
- **WHEN** `BleConnection` 回调送入一个 UUID = GPS_MAIN 的短包（`data.size < 20`）
- **AND** `parser.parseGpsData(rawData, currentData)` 返回
  `currentData.copy(errorMessage = "short-packet")` —— 注意 parser 的 `copy` **保留**
  了 `currentData.isConnected == true` 字段（parser 只改 errorMessage）
- **THEN** `_dataFlow.value.isConnected == false`（BluetoothDataSource 失败分支
  **显式** `parseResult.copy(isConnected = false)` 把 parser 带回来的 true 翻转）
- **AND** `_dataFlow.value.errorMessage == "short-packet"`
- **AND 硬区分（第三轮 review 前 v2 vs 第四轮 review 修订后 v3）**：v2 失败分支
  直接 `_dataFlow.value = parseResult`，未显式翻转 → 本帧 `isConnected = true +
  errorMessage != null` 状态自相矛盾，契约破；v3 失败分支 `parseResult.copy(isConnected
  = false)`，"最近一次 parse 成功" 契约成立

#### Scenario: GPS_TIME 短包 MUST 通过 errorMessage 上抛，不污染 isConnected（对称 GPS_MAIN）

- **GIVEN** `_dataFlow.value.isConnected == true`（前帧成功态）
- **WHEN** `BleConnection` 回调送入一个 UUID = GPS_TIME 的短包（`data.size < 3`）
- **AND** `parser.parseGpsTimeData(rawData, currentData)` 返回
  `currentData.copy(errorMessage = "short-packet")`（与 GPS_MAIN 路径对称）
- **THEN** `_dataFlow.value.isConnected == false`（BluetoothDataSource 失败分支
  显式翻转）
- **AND** `_dataFlow.value.errorMessage == "short-packet"`
- **AND 硬区分（第五轮 review 前 vs 后）**：review 前 `parseGpsTimeData` 短包
  `return currentData`（不设 errorMessage）→ 下游 BluetoothDataSource 走成功
  分支 `copy(isConnected = true)` → 违反契约；review 后 parser 写 errorMessage
  → 下游走失败分支置 false，契约成立

#### Scenario: 短包后第一帧成功 MUST 让 isConnected 恢复 true（契约闭合）

- **GIVEN** 前帧 parseGpsData 短包 → `_dataFlow.value.errorMessage == "short-packet"`
  + `isConnected == false`
- **WHEN** 当前帧送入合法 20 字节 GPS_MAIN 包
- **AND** `parser.parseGpsData(...)` 成功路径返回 copy 显式 `errorMessage = null`
- **THEN** BluetoothDataSource 走成功分支 `parseResult.copy(isConnected = true,
  errorMessage = null)` → `_dataFlow.value.isConnected == true`
- **AND 硬区分（第五轮 review 前 vs 后）**：review 前 parser 成功路径 copy 不显式清
  errorMessage → 前帧 "short-packet" 被 carry → 下游失败分支 copy(isConnected =
  false) → "短包后永远无法恢复 isConnected=true" 级联故障；review 后 parser 成功
  路径显式 `errorMessage = null` → 级联切断，契约闭合

#### Scenario: 正常主包 parse 成功置 isConnected = true 且清 errorMessage

- **GIVEN** `BluetoothDataSource` 已连接，`_dataFlow.value.errorMessage == "short-packet"`
  （上一个短包残留）
- **WHEN** `BleConnection` 回调送入一个合法 20 字节主包，parser 成功解析
- **AND** parser 返回新的 `GpsData(..., errorMessage = null)`
- **THEN** `_dataFlow.value.isConnected == true`
- **AND** `_dataFlow.value.errorMessage == null`（上一次失败残留被清除）

#### Scenario: 未知 UUID 不翻转 isConnected（硬断言 false → 仍 false）

- **GIVEN** `_dataFlow.value.isConnected == false`（初始态或 disconnect 后重置态）
- **WHEN** `BleConnection` 回调送入一个 UUID ≠ GPS_MAIN 且 ≠ GPS_TIME 的未知包
- **THEN** `_dataFlow.value.isConnected` 仍为 `false`（未被强置 true）
- **AND** `_dataFlow.value.errorMessage` 保持 `null`（未被未知 UUID 回调污染）
- **AND 硬区分 v1**：v1 的未知 UUID 分支执行 `_dataFlow.value.copy(isConnected
  = true)` 把字段从 false 翻转为 true；v2 通过 `parseResult == null` 早退，
  `_dataFlow.value` 完全不被触碰
- **AND**（强化反证）即使 `_dataFlow.value.errorMessage == null`（正常态，
  parser 成功过一次），v2 仍 MUST 保持未知 UUID 不翻转 isConnected 的语义 ——
  避免 "GpsData equality 未来新增字段（如 `receivedAt: Long`）" 让 v1 的
  `copy(isConnected = true)` 每个未知包都产生一次假 emit 的结构风险

---

### Requirement: `connect()` 切设备前必须清旧连接（A27）

`BluetoothDataSource.connect(deviceAddress)` MUST 在 `scope.launch { ... }`
的 `try` 块开头按以下**严格顺序**清理旧连接，之后才创建新连接：

1. `connectionCollectJob?.cancel(); connectionCollectJob = null`
2. `bleConnection?.disconnect(); bleConnection = null`
3. `_connectionState.value = ConnectionState.CONNECTING`（重置）
4. 新建 `bleConnection = BleConnection(context, deviceAddress) { ... }` 并启
   collect + 调 connect（原有逻辑）

步骤 1 MUST 早于步骤 2：旧 `connectionCollectJob` 若晚 cancel，可能在新连接
创建窗口内把旧 state（CONNECTED / DISCONNECTED 终态）传导到 `_connectionState`，
与新 CONNECTING 状态竞争。

关键属性：

- **原子化**：从旧 bleConnection 释放到新 bleConnection 就绪之间，
  `_connectionState` 只经历"旧终态 → CONNECTING → 新流程"一条单调路径
- **幂等性**：`bleConnection?.disconnect()` 对 null 引用无副作用，重复调
  `connect(sameAddress)` 不出问题
- **引用释放独立于 close**：步骤 2 的 `bleConnection = null` 只释放 Kotlin
  引用，不等待底层 GATT 的 `close()`（close 由 R3 回调处理，异步，不阻塞）

#### Scenario: 连 A 后改连 B，旧 bleConnection.disconnect 被调（硬区分 v1/v2）

- **GIVEN** `BluetoothDataSource` 已 `connect("AA:AA:AA:AA:AA:AA")` 且
  `_connectionState.value == ConnectionState.CONNECTED`，`bleConnection` 指向
  实例 `oldBle`
- **WHEN** 调 `connect("BB:BB:BB:BB:BB:BB")`
- **THEN** `oldBle.disconnect()` 被调用一次（v1 不调用 → 旧 GATT 泄漏；v2 调用）
- **AND** 新建 `bleConnection` 指向新实例 `newBle` 且 `newBle.deviceAddress == "BB:BB:BB:BB:BB:BB"`
- **AND** `oldBle` 引用已失去（不再被 `bleConnection` 字段持有）

#### Scenario: 旧 collectJob 在新 bleConnection 构造前 cancel（硬区分 v1/v2）

- **GIVEN** 构造一个可观察 job lifecycle 的测试 double，让旧
  `connectionCollectJob` 是已知的 Job `oldJob`
- **WHEN** 调 `connect("BB:BB:BB:BB:BB:BB")`
- **THEN** 时序满足：`oldJob.isCancelled == true` **早于** `newBle` 构造完成
- **AND 硬区分 v1**：v1 下 `connectionCollectJob?.cancel()` 在 `bleConnection
  = BleConnection(...)` **之后**执行，旧 job 可能在 cancel 前把终态 emit
  进 `_connectionState`；v2 下严格先 cancel 再构造新实例

#### Scenario: 连 A 后再连 A（相同地址），路径幂等

- **GIVEN** `BluetoothDataSource` 已 `connect("AA:AA:AA:AA:AA:AA")` 且
  state == CONNECTED
- **WHEN** 再调一次 `connect("AA:AA:AA:AA:AA:AA")`
- **THEN** 旧 bleConnection.disconnect 被调 + 新建 bleConnection + CONNECTING
  state 设置
- **AND** 流程不报异常（`bleConnection?.disconnect()` 对非 null 调用走正常路径）

---

### Requirement: `autoReconnectLastDevice` else 分支必须 fallback 扫描（A29 + A46）

`BleDeviceManager.autoReconnectLastDevice` MUST 在 `lastDeviceAddress ==
null` 分支调 `startScan()`，MUST NOT 只 log。

原 review `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md § 11.6`
MUST 同批修订为与代码事实一致的叙述（A46）。

关键属性：

- **UX 影响**：冷启动时 app MUST 自动开始扫描（当前硬编码 `lastDeviceAddress
  = null`，必走 else 分支）
- **耗电等价**：原 `if-true` 分支（超时未连）已调 `startScan()`，本 change
  让 else 分支也走同一路径，扫描行为已存在
- **文档一致性**：代码事实 + review 叙述 + attack-backlog A46 三方同步

#### Scenario: lastDeviceAddress == null 分支调 startScan（硬区分 v1/v2）

- **GIVEN** `BleDeviceManager` 构造，`autoReconnectLastDevice` 内部硬编码
  `lastDeviceAddress: String? = null`
- **AND** `scanner` 是 fake 实例可观察 `startScan()` 被调
- **WHEN** `autoReconnectLastDevice()` 执行到 else 分支
- **THEN** `scanner.startScan()` 被调用一次
- **AND 硬区分 v1**：v1 下 else 分支只 `Log.d(TAG, "没有上次连接的设备记录")`，
  `startScan()` **不**被调；v2 下被调

#### Scenario: 冷启动 init 触发 else 分支 → startScan

- **GIVEN** `BleDeviceManager(context, bluetoothDataSource)` 刚构造
- **WHEN** `init { autoReconnectLastDevice() }` 执行完成
- **THEN** 在 `RECONNECT_TIMEOUT_MS` 之内，`scanner.startScan()` 被调用至少一次
- **AND** `autoReconnectInProgress` finally 块设回 false

#### Scenario: review 文档 § 11.6 已同步修订为新叙述

- **GIVEN** 文件 `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
- **WHEN** 执行 `grep -n "autoReconnectLastDevice 实质未实现，且 .else. 分支" "$file"`
- **THEN** 输出至少一行（含 § 11.6 新标题）
- **AND** 原叙述"每次冷启动都走扫描路径，没有'上次设备优先'能力"MUST 被
  修订为"每次冷启动走扫描路径（战役 G 修复后），`lastDeviceAddress` TODO
  留给下一战役接入 `BluetoothDeviceRepository`"

#### Scenario: review 文档 § 11.5 同批修订（A45 捆绑）

- **GIVEN** 评审方在 proposal review 时已确认 A45 捆绑
- **AND** 文件 `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
- **WHEN** 执行 `grep -n "11.5.*假连接恢复未实现.*ConnectionManager 已删" "$file"`
- **THEN** 输出至少一行
- **AND** 原叙述"`ConnectionManager` 和 `BleConnection` 双层超时互相干涉"
  MUST 不再出现在该文件中
