## ADDED Requirements

> 说明：本 capability 处理"BLE 链路存活"与"数据新鲜度"的语义分离。它 **supersede** 已归档 change `2026-05-03-fix-ble-connection-lifecycle` 的 `ble-connection` Requirement 2 中"数据超时 → 释放 GATT（disconnect）"的行为——该行为对"无卫星不发帧"的真机固件（blazepush-peter）是误杀（详见 proposal Why）。归档 Requirement 2 的 A24 race guard（`ensureActive()`）与 Requirement 3 的 A40 close 统一回调释放路径 **保留不变**，仅"到期动作"从"disconnect"改为"置软陈旧状态"。

### Requirement: 数据静默 MUST NOT 拆链

握手完成（`connectionState == CONNECTED`）后，若数据看门狗 `BleConnection.startDataTimeoutCheck()` 判定超过 `DATA_TIMEOUT_MS` 未收到任何特征帧，系统 SHALL NOT 调用 `bluetoothGatt?.disconnect()`，SHALL NOT 将 `connectionState` 置为 `DISCONNECTED`。系统 MUST 改为将新增的 `BleConnection.dataStale: StateFlow<Boolean>` 置为 `true`，`connectionState` MUST 维持 `CONNECTED`。

收到任意后续特征帧时（`handleCharacteristicChange()`），系统 MUST 将 `dataStale` 置回 `false`，且数据流 MUST 无缝续上（不触发 GATT 重连/重握手）。

#### Scenario: 静默超时不拆链、置 dataStale

- **GIVEN** `BleConnection` 处于 `CONNECTED`，反射注入 `scope = TestScope`（沿用 `BleConnectionTest` 既有惯例）并设 `lastDataTime = 0L`
- **WHEN** `invokePrivate("startDataTimeoutCheck")` 后 `advanceTimeBy(DATA_TIMEOUT_MS + 1)` 推进虚拟时钟越过阈值且无新帧
- **THEN** mock `BluetoothGatt.disconnect()` **从未**被调用
- **AND** `connectionState.value == ConnectionState.CONNECTED`（**未**变 `DISCONNECTED`）
- **AND** `dataStale.value == true`

#### Scenario: 静默后再收到帧，dataStale 清除、链路续上

- **GIVEN** 上一场景后 `dataStale.value == true`、`connectionState == CONNECTED`
- **WHEN** 触发 `onCharacteristicChanged`（喂一帧合法数据，走 `handleCharacteristicChange`）
- **THEN** `dataStale.value == false`
- **AND** `connectionState.value == ConnectionState.CONNECTED`（全程未断）
- **AND** `BluetoothGatt.disconnect()` 仍从未被调用

#### Scenario: 反例锁死——超时分支不得含 disconnect/DISCONNECTED（源码结构断言）

- **GIVEN** `BleConnection.kt` 源码中 `private fun startDataTimeoutCheck()` 函数体
- **WHEN** 静态扫描该函数体（沿用 `startDataTimeoutCheck_rapidCancelRestart_sourceHasEnsureActiveGuard` 的源码截取手法，定位 `fun startDataTimeoutCheck` 到下一个 `private fun`/`}` 边界）
- **THEN** 函数体内 **不含** `bluetoothGatt?.disconnect()`（或等价 `.disconnect(` 调用）
- **AND** 函数体内 **不含** `ConnectionState.DISCONNECTED` 赋值
- **AND** 函数体内 **含** `dataStale` 置 `true` 的语句（防"空实现什么都不做"trivially pass：必须证明改成了置软状态，而非单纯删掉动作）

### Requirement: 真断开路径与下游契约 MUST 不回归

本 round 仅删除"静默 → DISCONNECTED"一条 `DISCONNECTED` 发射路径。GATT 协议栈回调路径与用户主动断开路径 SHALL 原样保留，`connectionState` 在这两条路径下 MUST 仍发射 `DISCONNECTED`，从而下游 `gps-runtime-stats`（`GpsDataViewModel` 订阅 `connectionState.filter { it == DISCONNECTED }` → `resetStats()`）等契约 MUST 不回归。

#### Scenario: GATT 回调 STATE_DISCONNECTED 仍走 A40 释放路径

- **GIVEN** `BleConnection` 持有 mock `BluetoothGatt`
- **WHEN** 触发 `gattCallback.onConnectionStateChange(gatt, status, BluetoothProfile.STATE_DISCONNECTED)`
- **THEN** `connectionState.value == ConnectionState.DISCONNECTED`
- **AND** `bluetoothGatt.close()` 被调用且引用置 null（A40 统一释放路径，归档 Requirement 3 不回归——复用既有 `onConnectionStateChange_stateDisconnected_closesGattAndNullsReference` 断言）

#### Scenario: 用户主动 disconnect 仍触发断开

- **GIVEN** `BleConnection` 处于 `CONNECTED`
- **WHEN** 调用公开 `disconnect()`
- **THEN** `bluetoothGatt.disconnect()` 被调用（close 留给回调路径，归档 Requirement 3 不回归——复用既有 `disconnect_doesNotCloseGattBeforeStateDisconnectedCallback` 断言）

#### Scenario: 真断开发射的 DISCONNECTED 仍能驱动 resetStats（ripple 锁）

- **GIVEN** `GpsDataViewModel` 已按 `gps-runtime-stats` spec 订阅 `connectionState.filter { it == DISCONNECTED }`
- **WHEN** `connectionState` 经真断开路径（GATT 回调 / 用户主动）发射 `DISCONNECTED`
- **THEN** `resetStats()` 仍被触发（真断开语义未被本 round 误删；本 round 不修改 `GpsDataViewModel` 订阅逻辑，仅保证 `DISCONNECTED` 仍从真断开路径发射）

### Requirement: isStale 软状态语义——与 isConnected 正交、任意帧清除、不残留

`GpsData` SHALL 新增 `isStale: Boolean = false` 字段，语义为"链路 CONNECTED 但当前无新数据帧（很可能在等卫星）"。`isStale` 与 `isConnected`（语义"最近一次 parse 成功"）SHALL 正交：二者 MUST NOT 互相复用或耦合翻转。

`BluetoothDataSource` MUST collect `bleConnection.dataStale` 并把最新值写入 `_dataFlow.value.copy(isStale = ...)`，且 MUST NOT 改动 `connectionState`。`handleIncomingData()` 的 parse 成功 **与** parse 失败两分支 MUST 显式置 `isStale = false`（任意帧到达即非陈旧；且 parser 的 `currentData.copy(...)` 会保留前帧 `isStale`，不显式翻转会让"静默置 true"在下一帧错误残留）。

#### Scenario: parse 成功帧清 isStale

- **GIVEN** `BluetoothDataSource` 的 `_dataFlow` 当前 `isStale = true`（模拟刚经历静默），mock `RaceChronoParser` 对主包返回 `errorMessage == null` 的成功结果
- **WHEN** `handleIncomingData(gpsMainUuid, ByteArray(20))`
- **THEN** `dataFlow.value.isStale == false`
- **AND** `dataFlow.value.isConnected == true`（既有 R4 契约不破）

#### Scenario: parse 失败帧也清 isStale（数据在流，只是这帧坏）

- **GIVEN** `_dataFlow` 当前 `isStale = true`，喂一个短包（parser 走失败分支返回 `errorMessage != null`）
- **WHEN** `handleIncomingData(gpsMainUuid, ByteArray(10))`
- **THEN** `dataFlow.value.isStale == false`（收到帧 = 非陈旧，无论 parse 成败）
- **AND** `dataFlow.value.isConnected == false`（既有 R4 契约：短包 isConnected 保持 false，不破）

#### Scenario: 反例锁死——dataStale=true 后下一帧若不显式翻转则残留（证明翻转生效）

- **GIVEN** 通过 `setDataFlow(GpsData.Empty.copy(isStale = true))` 把 `_dataFlow` 置为 `isStale = true`
- **WHEN** `handleIncomingData` 喂一帧 parse 成功数据
- **THEN** `dataFlow.value.isStale == false`（**若实现依赖 parser `copy` 而不显式置 false，此断言会因残留 true 而 fail** —— 锁死显式翻转）
