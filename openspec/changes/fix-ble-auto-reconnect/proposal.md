# Proposal: fix-ble-auto-reconnect

## Why

2026-06-03 vivo V2405A 路测:BLE 真断开(23:09:09,debug_log `connectionState -> DISCONNECTED`)后 App 无任何重连动作,用户在车内手动操作 2 分 24 秒后(23:11:33)才恢复连接,期间圈速数据断流。代码级现状(已逐行核实):

1. `BleConnection.connect()`(core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt:235)用 `connectGatt(context, false, gattCallback)`——`autoConnect=false`,系统层无自动重连。
2. `onConnectionStateChange(STATE_DISCONNECTED)`(同文件 108-116)只置状态 + close GATT,无任何重试。
3. `BleDeviceManager.autoReconnectLastDevice`(BleDeviceManager.kt:52-105)仅冷启动跑一次,且 `lastDeviceAddress` **硬编码 null(TODO 未实现)**——实际永远走扫描 fallback,连冷启动重连都是空架子。
4. `BluetoothDataSource`(状态权威,持有 BleConnection 与 deviceAddress)对 DISCONNECTED 状态无响应逻辑。

跑山/赛道场景设备瞬断(供电抖动/超距后返回)是常态,断链即断数据流,手动重连在驾驶中不可行也不安全。

## What Changes

- `BluetoothDataSource` 增加**会话内意外断开自动重连**:监听到 DISCONNECTED 且非用户主动断开时,指数退避(1s 起,×2,封顶 30s)无限重试 `connect(lastRequestedAddress)`,直到连上 / 用户主动断开 / 切换设备。
- 用户主动 `disconnect()` 与切设备 `connect(新地址)` 取消挂起的重连并复位退避计数;连接成功(CONNECTED)复位退避计数。
- 重连尝试经现有 `connectionState` 流传导(CONNECTING↔DISCONNECTED),feature 层既有 `BleLiveness` FileLogger 锚点(GpsDataViewModel)自动落盘全部状态转移,无需新增跨模块日志。
- `BluetoothDataSourceTest` 补重连场景单测(注入式调度规避真实 delay)。

非目标(透明声明):**冷启动自动重连**(`BleDeviceManager` 的 lastDeviceAddress TODO)不在本 round——需要 `BluetoothDeviceRepository`(core/data)跨模块接线(core/bluetooth 仅依赖 core/domain,模块图不动),且需核实设备保存链路与 DAO 排序字段;列 §10 backlog `cold-start-reconnect-wiring`,本次侦查结论(repo 方法清单/依赖方向)已沉淀在 design Context。

## Capabilities

### New Capabilities
- `ble-auto-reconnect`: BLE 意外断开的会话内自动重连——退避策略、用户意图区分(主动断开/切设备不重连)、重连状态可观测。

### Modified Capabilities
<!-- 无:ble-connection-liveness(数据静默软陈旧)的 requirements 不变,重连只在真断开(DISCONNECTED)后介入,与丢星不拆链正交 -->

## Impact

- **代码**:`core/bluetooth/.../BluetoothDataSource.kt`(单文件 +约 50 行);`BluetoothDataSourceTest.kt`(新增场景)。
- **不碰**:`BleConnection`(GATT 生命周期 A40 契约不动)、`BleDeviceManager`、模块依赖图、公共协议、UI。
- **行为变化**:断链后 UI 状态将周期性 DISCONNECTED→CONNECTING 闪变(重连尝试),既有 BLE banner 视觉上等效"重连中";设备真不在场时后台持续退避重试(30s 间隔,BLE connectGatt 单次开销低,耗电可忽略)。
