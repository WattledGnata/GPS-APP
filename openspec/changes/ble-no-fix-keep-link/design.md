## Context

接收端 `BleConnection`（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`）是 GATT 资源唯一所有者。其握手**之后**有一个 10 秒数据看门狗 `startDataTimeoutCheck()`（约 297-313 行）：协程 `delay(DATA_TIMEOUT_MS = 10000L)` 后若 `System.currentTimeMillis() - lastDataTime > 10000`，执行 `bluetoothGatt?.disconnect()` + `_connectionState.value = DISCONNECTED`。看门狗只在收到首帧后由 `handleCharacteristicChange()`（约 173-189 行，唯一调用点 188 行）上膛，每帧更新 `lastDataTime` 并重启。

真机固件 blazepush-peter 无卫星不发帧（握手注释 144-154 行已记录），丢星 = 蓝牙静默 → 看门狗误判死链拆掉。模拟器全量透传不触发，故 bug 只在真机出现（详见 proposal Why）。

**连接态传导链**（apply 期 grep 已锚定）：
`BleConnection.connectionState`（StateFlow，:72）→ `BluetoothDataSource._connectionState`（:42-43，经 :69-76 collect 传导）→ `GpsDataRepository.connectionState`（:14）→ `GpsDataViewModel.connectionState`（:32）。

**GPS 数据传导链**：`BluetoothDataSource._dataFlow: StateFlow<GpsData>`（:38-39，唯一输出口）→ `GpsDataRepository.gpsDataFlow` → `GpsDataViewModel.gpsData`（:30）。`handleIncomingData()`（:124-139）是数据入口；其成功/失败分支已有"显式翻转 isConnected"的先例（:131-137，因 parser `currentData.copy(...)` 会保留前帧字段）。

**下游 connectionState 消费者**（本 round 不得回归）：
- `GpsDataViewModel.kt:83-86`：`connectionState.filter { it == DISCONNECTED }.collect { resetStats() }`（gps-runtime-stats spec :85/94/101）。
- `GpsDataViewModel.kt:59-60`：DISCONNECTED 清 `_connectedDeviceName`。
- `GpsDetailsScreen.kt:520`：`isConnected = connectionState == CONNECTED` 驱动 UI 文案。

`GpsData`（`core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt:9-50`）是 **in-memory domain model，非 Room @Entity**，新增字段无 schema migration。现有字段已有 `fixQuality: Int = 0` / `isTimeSynced: Boolean = false` 带默认值的先例。

## Goals / Non-Goals

**Goals:**
- 数据静默（无帧）MUST NOT 拆 GATT 链路、MUST NOT 发 `DISCONNECTED`；链路保持 `CONNECTED`。
- 静默升级为一个**软"数据陈旧"信号**，供 UI 显示"等待卫星/信号丢失"而非"已断开"。
- 卫星恢复推帧后软信号在下一帧自动清除，数据流无缝续上（无重连、无重握手）。
- 真断开（GATT 回调 / 用户主动）路径不变，`DISCONNECTED` 仍正常发射、下游 `resetStats()` 等契约不回归。

**Non-Goals:**
- 不改 RaceChrono BLE 帧协议字段/编码（公共协议边界）。
- 不做 ② 中途断开 auto-reconnect 自愈（`BleDeviceManager.autoReconnectLastDevice` 死逻辑）——单独 follow-up `ble-mid-session-auto-reconnect`（见 tasks §10 + `docs/design/ble-mid-session-auto-reconnect-deferred.md`）。
- 不动发射端 simulator。
- 不引入"信号质量/卫星数/HDOP 阈值触发任何连接动作"的逻辑（本 round 只处理"静默 vs 死链"语义，不碰质量判定）。

## Decisions

### Decision 1：看门狗保留在 BleConnection，但到期改为置软状态、不再拆链

`startDataTimeoutCheck()` 到期分支 MUST 移除 `bluetoothGatt?.disconnect()` 与 `_connectionState.value = DISCONNECTED`，改为把新增的 `dataStale: StateFlow<Boolean>` 置 `true`。`handleCharacteristicChange()` 收到任意帧时 MUST 置 `dataStale = false`（与现有 `lastDataTime` 重置同处）。`connectionState` 在静默期间保持 `CONNECTED` 不变。

BleConnection 新增暴露 `val dataStale: StateFlow<Boolean>`（mirror 现有 `connectionState` 暴露形态，:71-72），由 BluetoothDataSource collect（mirror :69-76 既有 collect 模式），不改 BleConnection 构造函数签名（避免波及构造点 `BluetoothDataSource.kt:64` 与反射契约测试）。

- **Alternative A：把看门狗整体迁到 BluetoothDataSource**。拒绝：BleConnection 已持有 `lastDataTime`/`timeoutJob` 且与 GATT 回调（`handleCharacteristicChange`）耦合上膛/重启，迁移会重复一套计时 plumbing 并跨层重接，污染更大。
- **Alternative B：删除看门狗，无软状态**。拒绝：StateFlow 保留最后一帧值，静默期 UI 会显示**冻结的旧值**（旧速度/旧卫星数）且无任何提示，用户无法区分"还在跑"与"丢星了"。

### Decision 2：软状态载体 = 新增 `GpsData.isStale: Boolean = false` 字段

`BluetoothDataSource` collect `bleConnection.dataStale`，把最新值写入 `_dataFlow.value = _dataFlow.value.copy(isStale = <latest>)`，`connectionState` 不动。`handleIncomingData()` 的成功**与**失败两分支 MUST 显式置 `isStale = false`（任意帧到达即非陈旧；且 parser `currentData.copy(...)` 会保留前帧 `isStale`，不显式翻转会让"静默→true"在下一帧残留，复刻 :131-137 已记录的 isConnected 契约陷阱）。

- **Alternative A：复用 `isConnected = false`**。拒绝：`isConnected` 语义是"最近一次 parse 成功"（:110 契约 + gps-runtime-stats 依赖），静默 ≠ parse 失败，混用破坏既有契约。
- **Alternative B：新增 `ConnectionState.STALE` 枚举值**。拒绝：链路此刻真实是 `CONNECTED`，新增枚举强制所有 `when(connectionState)` 消费者改动，且让 `filter { it == DISCONNECTED }` 等判定脆弱；陈旧度与连接态正交，不该塞进同一枚举。

### Decision 3：不设 app 层硬拆链兜底，真死链交给 GATT supervision 回调

不保留任何 app 层"超长超时后硬 disconnect"。真链路死亡（设备关机/出范围）由 BLE 协议栈 supervision timeout 经 `onConnectionStateChange(STATE_DISCONNECTED)`（BleConnection.kt:101-109，A40 统一释放路径）判定，本就存在，保留不动；用户主动 `disconnect()`（:228-231）路径不变。

- **Alternative：保留一个很长（60-120s）的兜底硬拆**。拒绝：重新引入同一类误杀（>60s 的隧道/维修区仍会被拆），而真死链 GATT 回调已覆盖，兜底冗余且有害。
- **[Risk] 设备半开链路**（链路看着活、实际死、且 GATT 栈迟迟不报 DISCONNECTED）→ 链路会停在 `CONNECTED + isStale=true` 不自愈 → **Mitigation**：(a) isStale 让 UI 显式提示"信号丢失"，用户可手动重连；(b) 自愈交给已立项的 follow-up `ble-mid-session-auto-reconnect`（本 round 范围外，透明声明）。

### Decision 4：UI 消费 isStale，丢星不显示"已断开"

`feature/test` 中 GPS 状态/质量 UI MUST 反映 `isStale`（如 `DataQualityCard` / `GpsDetailsScreen` / 状态标签），丢星时呈"等待卫星/信号丢失"语义，**不得**呈"已断开"（设备仍连着）。具体文案 apply 期定，grep 消费点对齐。

- **Alternative：UI 不改，仅底层不拆链**。拒绝：底层 CONNECTED 但 UI 显示冻结旧值会误导用户以为数据仍有效。

## Risks / Trade-offs

- **[Risk] 半开链路滞留**（见 Decision 3）→ **Mitigation**：isStale 可视提示 + 手动重连 + follow-up 自愈 round。
- **[Risk] isStale 残留**（parser copy 保留前帧）→ **Mitigation**：Decision 2 强制两分支显式翻转 false + spec 反例 scenario 锁死。
- **[Risk] gps-runtime-stats 回归**（误删真断开的 DISCONNECTED）→ **Mitigation**：本 round 只删"静默→DISCONNECTED"一条路径，GATT 回调 / 用户主动两条 DISCONNECTED 路径保留；spec 加反例 scenario 锁"真断开 DISCONNECTED 仍发射"；apply 期跑 gps-runtime-stats 相关测试不回归。
- **[Risk] dataStale StateFlow 与 connectionState 双流时序**（静默瞬间到 true、新帧瞬间到 false 的抖动）→ **Mitigation**：StateFlow equality 去重 + `handleCharacteristicChange` 先置 lastDataTime/cancel timeoutJob 再 false，mirror 既有 A24 race guard 思路（`ensureActive()`，:303）。
- **[Trade-off] 新增 GpsData 字段**：in-memory model，无 Room migration；但 apply 期 MUST grep 确认无序列化/positional 消费 GpsData 的路径（如 binary writer / replay）会因字段新增错位（预期无，GpsData 是 live flow model，持久化走 LapTelemetrySample 等独立类型——apply 期 #16 自查 verify）。

## Migration Plan

无 schema / Room migration（`GpsData` 是 in-memory domain model）。部署即生效。回滚：还原 `startDataTimeoutCheck` 的 disconnect 分支 + 移除 isStale/dataStale 即可，无数据残留。

## Open Questions

- 静默阈值是否维持 10s：本 round 维持 10s（不再拆链后，10s 出"陈旧"提示更灵敏，无害）；若真机路测觉得太敏感再调，记入 metrics。
- isStale 的 UI 具体文案/视觉：apply 期结合现有 `DataQualityCard`/`GpsDetailsScreen` 风格定，真机 gate 验证。
