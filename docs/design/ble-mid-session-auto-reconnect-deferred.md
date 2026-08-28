# BLE 中途断开 auto-reconnect 自愈 — 延期立项设计 memo

> ✅ **主体已消化（2026-06-06 盘点确认，无需按原名立项）**：推荐方案 A 已被两个 round 合力落地——
> ① 会话内意外断开自动重连（connectionState 观察 + 指数退避 1s×2 封顶 30s + userInitiated 区分 +
> 切设备/主动断开取消重连）由 `fix-ble-auto-reconnect` round 实现（commit `14b27c5`，
> `BluetoothDataSource` 层）；② 地址持久化 + 冷启动重连（`lastDeviceAddress` TODO）由
> `ble-device-memory` round 实现（commit `99fb87b`，Room v7→v8 + `autoReconnectLastDevice` 接线，
> archive/2026-06-06）。**唯一残留**：§3 方案 C 增量「半开链路探活」（isStale 持续 N 秒 → 主动
> disconnect 触发重连）未做，需真机标定 N，低优先级；若将来立项仅消化该增量。

> 触发来源：`ble-no-fix-keep-link` round（丢星不拆链）实施期识别出的②号牵扯问题。本 round 聚焦"静默不拆链"，auto-reconnect 死逻辑单独延期立项。建议 round 名：`ble-mid-session-auto-reconnect`。
> 本 memo 目标：下次 `/opsx:ff ble-mid-session-auto-reconnect` 直接读本文件即可起草 proposal/design，不回溯对话。

## 1. 现状

接收端 BLE 重连逻辑全部集中在 `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt`：

- `autoReconnectLastDevice()`（`:52-105`）：**只在 `init{}`（`:44-47`）被调用一次**——即 `BleDeviceManager` 构造时（app 启动 / DI 创建）。运行期连接中途断开后，**没有任何 `connectionState` 观察者**重新触发它。
- 即便被触发，`lastDeviceAddress`（`:64`）**硬编码 `null`**（`// TODO: 从Repository获取上次连接的设备地址` 未实现）→ 永远走 else 分支 `startScan()`（`:90-96`），**不会重连回原设备**，只是弹出扫描列表让用户手动点。
- `connect()`（`:129-147`）只在用户主动点击设备时调用。

连接态传导链（供观察者挂载点参考）：
`BleConnection.connectionState` → `BluetoothDataSource.connectionState`（`:42-43`）→ `GpsDataRepository.connectionState`（`GpsDataRepository.kt:14`）→ `GpsDataViewModel.connectionState`（`GpsDataViewModel.kt:32`）。

设备地址持久化：当前**无**。`connect()` 内 `// TODO: 连接成功后保存设备信息到Repository`（`:141-142`）也未实现。需要一个持久化点（DataStore / Room / SharedPreferences）存 last connected address。

## 2. 数据证据

- 真链路死亡（设备关机 / 走出范围）：BLE 协议栈 supervision timeout 经 `BleConnection.onConnectionStateChange(STATE_DISCONNECTED)`（`BleConnection.kt:101-109`）上报 → `connectionState` 发 `DISCONNECTED` → 链路被 close+null（A40）。**此后没有任何东西重连** → 用户必须手动重扫重连。
- `ble-no-fix-keep-link` round 完成后：丢星不再误拆链（静默→isStale 软状态），所以"丢几秒星"不再触发断开。但"真断开"（关机/超距/半开链路被 BLE 栈最终判死）后仍无自愈。
- 半开链路风险（`ble-no-fix-keep-link` design Decision 3 Risk）：链路看着活、实际死、GATT 栈迟迟不报 DISCONNECTED → 停在 `CONNECTED + isStale=true`。这种情况 auto-reconnect **也救不了**（因为没收到 DISCONNECTED），需配合一个"isStale 持续 N 秒后主动探活/重连"策略——属本 follow-up 的可选增强。

## 3. 方案对比

| 方案 | 描述 | 优 | 劣 |
|---|---|---|---|
| **A. connectionState 观察者 + 持久化地址重连** | 在 `BleDeviceManager`（或 Repository 层）挂 `connectionState` 观察者，检测到**非用户主动**的 `DISCONNECTED` → 用持久化的 last address 自动 `connect()`，带退避（backoff）+ 上限 | 直接自愈；复用现有 connect 链路 | 需区分"用户主动断开"vs"意外断开"（否则用户点断开后又被自动连回）；需落地地址持久化 |
| **B. 仅落地地址持久化 + 手动重连一键化** | 不做自动，只把 last address 存好，断开后 UI 给"重连"按钮一键连回 | 改动最小；用户掌控 | 不是真自愈，跑圈中断开仍需用户手动点 |
| **C. A + 半开链路探活** | A 基础上加"isStale 持续 N 秒 → 主动 disconnect 触发 DISCONNECTED → 走 A 重连" | 覆盖半开链路 | 复杂度最高；N 的选择需真机标定；有"探活误杀长隧道"风险 |

## 4. 推荐方案 + 分析

**推荐 A（connectionState 观察者 + 持久化地址 + 退避重连），半开链路探活（C 的增量）作为本 round 内可选 Decision 延后真机标定再定。**

关键设计点：
- **区分主动 vs 意外断开**：`disconnect()`（用户主动）前置一个 `userInitiated` 标志，观察者只对"非 userInitiated 的 DISCONNECTED"触发重连。或更干净：让主动断开走一个不经过"重连观察者"的专用路径。
- **退避策略**：重连间隔指数退避（如 1s/2s/4s/8s，上限 8s），重连尝试上限（如连续 N 次失败后停手、回落到扫描 UI + 通知用户）。数学：退避总时长 = Σ min(2^k, 8) s，N=6 约 1+2+4+8+8+8=31s 窗口，覆盖常见短时遮挡/超距往返而不无限耗电。
- **地址持久化**：`connect()` 成功（首次进入 CONNECTED）后存 address；app 重启走 `autoReconnectLastDevice()` 时读它（顺带激活 `:62-64` 那个 TODO）。
- **与 BLE 栈协同**：重连用 `device.connectGatt`，与首连同路径；注意 Android 对同设备快速重连有节流，退避正好缓解。

性能 / 功耗：退避 + 上限避免"断开后无限高频重连"的耗电与扫描风暴；重连不涉及高频数据路径，无 25Hz 级别开销。

## 5. 实施约束（MUST 条款）

1. **MUST 区分用户主动断开**：用户点"断开"后 MUST NOT 被自动重连回（需 userInitiated 标志或专用路径）。
2. **MUST 退避 + 上限**：禁止无退避的紧密重连循环；MUST 有失败上限后停手并通知用户。
3. **MUST 复用现有 connect 链路**（`BluetoothDataSource.connect` / `BleConnection.connect`），MUST NOT 在管理层私建第二条 GATT 持有路径（`BleConnectionLifecycleContractTest` 反射契约：GATT 字段只在 `BleConnection`）。
4. **MUST 落地 last address 持久化**，激活 `BleDeviceManager.kt:62-64` / `:141-142` 两个 TODO。
5. **MUST NOT 改 RaceChrono 帧协议**（公共协议边界）。
6. 若做 C（半开探活）：MUST 把 isStale→主动断开的阈值 N 设为可配 + 真机标定，MUST 透明声明"探活会主动断一次健康链路"的取舍。

## 6. 单元测试覆盖

- 意外 DISCONNECTED → 触发重连（mock connectionState 发 DISCONNECTED，断言 connect 被调，地址 = 持久化值）。
- 用户主动 disconnect → **不**触发重连（断言 connect 未被调）。
- 退避序列正确（虚拟时钟 + TestDispatcher，断言重连尝试时刻符合退避表）。
- 重连失败上限 → 停手 + 通知（断言 N 次后不再 connect）。
- 地址持久化往返（connect 成功写入 → 读回一致）。
- 反射契约：重连逻辑未在管理层引入 `BluetoothGatt` 字段（`BleConnectionLifecycleContractTest` 仍绿）。

## 7. 与 `ble-no-fix-keep-link` round 的协同关系

- `ble-no-fix-keep-link` 删除"静默→DISCONNECTED"路径后，意外 DISCONNECTED 只剩"真链路死亡（GATT 回调）"+"用户主动"两条 → 本 round 的"意外断开重连"语义**更干净**（不会被丢星误触发）。
- 本 round 复用 `ble-no-fix-keep-link` 引入的 `GpsData.isStale` 作为半开链路探活（方案 C）的输入信号。
- 两 round 都遵守 GATT 唯一所有者反射契约（`BleConnectionLifecycleContractTest`）。

## 8. 不并入 `ble-no-fix-keep-link` round 的理由

- scope 隔离：`ble-no-fix-keep-link` 是"止血"（不再误拆链），auto-reconnect 是"自愈"（断了能回来），两件事正交，合并会让 round 跨度过大、风险面叠加。
- 风险隔离：auto-reconnect 涉及"区分主动/意外断开""退避""地址持久化"多个新决策点 + 可能引入 DataStore/Room 依赖（持久化）；混入止血 round 会拖慢核心修复落地与真机验证。
- 依赖顺序：先止血（堵住丢星误断）让真机能稳定连着，才好验证 auto-reconnect 的"真断开重连"场景（否则丢星误断会污染重连测试信号）。

## 9. 立项节奏估算

- 复杂度：medium（2-3 module：core/bluetooth 重连观察者 + 持久化层 + 可能 feature 层重连 UI/通知；可能引入 DataStore 依赖）。
- 估时：方案 A 约 1-1.5 天（含退避 + 持久化 + 单测）；加 C（半开探活）+0.5 天（含真机标定 N）。
- 立项时机：`ble-no-fix-keep-link` 真机验证通过、合回主 feature 分支后插入。若真机路测发现半开链路频发，C 的优先级上调。
- 前置依赖：无硬前置（`ble-no-fix-keep-link` 不是编译依赖），但建议在其后立项以获得干净的"意外断开"语义 + isStale 信号。
