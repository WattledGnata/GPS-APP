## Why

蓝牙 GPS 真机上"用着用着断":跑圈中过隧道/桥洞/进维修区丢失卫星几秒后，设备被 app 主动断开且卡死在断开态、需人工重扫重连。

根因不在握手、也不在任何卫星质量判断，而在握手**之后**的一个 10 秒数据看门狗 `BleConnection.startDataTimeoutCheck()`：协程 `delay(DATA_TIMEOUT_MS = 10000L)` 后若 `System.currentTimeMillis() - lastDataTime > 10000`，执行 `bluetoothGatt?.disconnect()` + `_connectionState.value = DISCONNECTED`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`，约 297-313 行）。该看门狗只在收到首帧数据后上膛（唯一调用点在 `handleCharacteristicChange()` 约 188 行）。

**为什么现在才暴露（双端固件差异，是本 bug 的前提）**：发射端有两类固件策略——
- **模拟器**：把 GPS 芯片信号**全量翻译**透传，即使 0 卫星也持续推帧 → 蓝牙永不静默 → 看门狗永不触发 → **此 bug 在模拟器上复现不了**。
- **真机 blazepush-peter**：固件**过滤**，无卫星不发帧 → 丢星 = 蓝牙彻底静默 → 10 秒看门狗把"设备没数据可推"误判成"链路死了"并拆掉 GATT。

也就是说，10 秒看门狗隐含假设"对端会持续推帧"，这只对模拟器成立、对真机固件不成立。握手路径此前已专门改成"握手完成即判 CONNECTED，不等数据帧"（`BleConnection.kt` 约 144-154 行注释明确写了适配 blazepush-peter"无卫星不输出"），但握手**之后**的数据看门狗这条路径漏改，导致"连得上、用着丢星就断"。

## What Changes

- 去掉数据看门狗的硬拆链行为：10 秒收不到帧时**不再** `gatt.disconnect()`、**不再**置 `connectionState = DISCONNECTED`，GATT 链路保持 `CONNECTED`。
- 改为对外暴露一个**软"数据陈旧/丢星"状态**（链路仍连着），表达"当前没有新数据帧（很可能在等卫星）"，供 UI 显示"等待卫星/信号弱"而非"已断开"。
- 卫星恢复推帧后，该软状态在收到下一帧时**自动清除**，数据流无缝续上（无需重连、无 GATT 重握手）。
- 真链路死亡（设备关机/走出范围）仍由 BLE 协议栈 supervision timeout 经 `onConnectionStateChange(STATE_DISCONNECTED)` 回调判定并走 A40 统一释放路径（`BleConnection.kt` 约 101-109 行，**保留不动**）。
- **协议兼容性**：不涉及 RaceChrono BLE 帧协议字段/编码的任何修改；仅改接收端连接生命周期行为与数据层软状态字段。无双端改动（发射端 simulator 不动）。

## Capabilities

### New Capabilities

- `ble-connection-liveness`: BLE 链路存活与数据新鲜度的语义分离。规定"数据静默（无帧）" MUST NOT 被当作链路死亡而拆链；静默只升级为一个软"数据陈旧"信号；真链路死亡的唯一判定来源是 GATT 协议栈回调（及用户主动断开）。覆盖看门狗行为、软状态置位/清除时机、与 `connectionState` 的关系。

### Modified Capabilities

<!-- 无 spec-level requirement 变更。下游 capability `gps-runtime-stats` 消费 connectionState 的 DISCONNECTED 信号（其 spec.md:85/94/101 要求 DISCONNECTED 时 resetStats），但该 requirement 文本不变——本 round 只改"DISCONNECTED 何时被发射"（不再因丢星发射），不改"收到 DISCONNECTED 后做什么"。故 gps-runtime-stats 列入 Impact 跨 capability 交互，不作为 Modified Capability。 -->

## Impact

**受影响模块/代码**：
- `core/bluetooth/.../BleConnection.kt`：`startDataTimeoutCheck()` 改为只置软状态、不拆链、不发 DISCONNECTED；软状态需经回调/数据层透出（具体透出路径在 design 定）。
- `core/bluetooth/.../BluetoothDataSource.kt`：`handleIncomingData()` / `_dataFlow` 承载软"数据陈旧"状态；收到新帧时清除。
- `core/domain/.../GpsData`（model）：可能新增软状态字段（字段选型在 design 用 ≥2 alternatives 定，倾向新增独立 `isStale`/`noRecentData` 而非复用 `isConnected`，避免污染"最近一次 parse 成功"契约）。
- `feature/test` 下消费 `connectionState`/`dataFlow` 的 UI（apply 期 grep 消费点）：丢星文案从"已断开"改为"等待卫星/信号弱"。

**跨 capability ripple（必须验证不回归）**：
- `gps-runtime-stats`（`openspec/specs/gps-runtime-stats/spec.md:85,94,101`）：`GpsDataViewModel.init` 订阅 `connectionState`，迁入 `DISCONNECTED` 时 `resetStats()`。当前丢星误拆链会**错误触发** resetStats（运行时统计被无故清零）；本 round 丢星不再发 DISCONNECTED → 该误重置顺带消失（正向）。但 MUST 保证：真断开（GATT 回调 / 用户断开）时 DISCONNECTED 仍正常发射、resetStats 仍按原契约触发——本 round spec 加反例 scenario 锁死"真断开路径的 DISCONNECTED 不被误删"。

**不在本 round 范围（单独 follow-up）**：
- ② 中途断开后 auto-reconnect 死逻辑：`BleDeviceManager.autoReconnectLastDevice()` 仅在 `init` 跑一次、且 `lastDeviceAddress` 硬编码 `null`（`BleDeviceManager.kt:46,64`），真断开后不会自愈。本 round 堵住"丢星误断"后，该问题紧迫性下降，单独立项 `ble-mid-session-auto-reconnect`。本 round MUST 在 `tasks.md` §10 backlog 挂 link + 沉 `docs/design/ble-mid-session-auto-reconnect-deferred.md` 完整 memo。

**测试影响**：
- `core/bluetooth` 模块单测：新增"收到帧→静默>10s→再收到帧"序列断言（connectionState 全程 CONNECTED + 软状态正确置位/清除）。
- 真机验证 gate：本 round 核心场景只能在真机 blazepush-peter 复现（模拟器固件全量透传，复现不了丢星静默），MUST 真机验证。
