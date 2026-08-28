# Proposal: ble-device-memory

## Why

对标 BlazePush 1.3.0 固件配套 App 的三项蓝牙易用性改进(iOS 用户体验反馈,user 2026-06-06 拍板纳入,G 值算法属固件侧不做)。当前 gps-app 蓝牙链路的代码现状(已逐行核实):

1. **连接记忆是空架子**:`bluetooth_devices` 表(address PK + name)、`BluetoothDeviceDao`、`BluetoothDeviceRepository` 全部存在且已注册 Koin(`AppModule.kt:71/94`),但**生产代码零调用**——连接成功后没有任何链路写表,Repository 的 CRUD 是 dead code。
2. **冷启动自动重连未实现**:`BleDeviceManager.autoReconnectLastDevice()`(`core/bluetooth/.../BleDeviceManager.kt:52-105`)的 `lastDeviceAddress` 硬编码 null(64 行 TODO),实际永远 fallback 到扫描;`connect()` 内 141 行 TODO"连接成功后保存设备信息"同样未实现。这正是 `fix-ble-auto-reconnect` round(2026-06-04 闭环)透明声明的非目标 backlog `cold-start-reconnect-wiring`。
3. **无别名、无设备管理**:表无 alias / lastConnectedAt 字段;`BleScanBottomSheet` 只展示固件广播名 + MAC + RSSI,多台同名设备无法区分;已保存设备记录无任何查看/清除入口。

用户场景:车主有多台 BlazePush GPS 设备(或朋友车上也有),固件广播名相同分不清;每次上车都要手动扫描+点连接,驾驶场景操作不便;换设备后旧记录残留。

## What Changes

- **设备持久化打通**:连接成功(connectionState 首次 CONNECTED)后写 `bluetooth_devices` 表,记录 address / 固件名 / 最近连接时间;`BluetoothDeviceEntity` 加 `alias`(nullable)+ `lastConnectedAtMs`(nullable,不用 0 哨兵——盲点 #6)两列,Room **v7→v8 migration**(ALTER TABLE ×2,nullable 无 DEFAULT,旧行 NULL = 无别名/无连接记录)。
- **冷启动自动连接**:`BleDeviceManager.autoReconnectLastDevice()` 接通真实数据源——查最近连接设备(lastConnectedAtMs 最大者)自动 connect,超时/无记录 fallback 扫描(既有逻辑骨架 66-96 行不变,只填 64 行的 null)。core/bluetooth 不依赖 core/data,接线走 **Koin 闭包注入**(模块依赖图不动——`fix-ble-auto-reconnect` design 已沉淀此侦查结论)。
- **设备别名**:用户可对已保存设备备注自定义名;扫描列表(`BleScanBottomSheet`)与 Device 主屏(`DeviceHomeScreen` ConnectedDeviceCard)显示别名优先(alias > 固件名 > MAC)。
- **"上次连接"提示**:扫描列表 join 已保存设备表,最近连接设备行加 "Last connected" 徽标(替换/并列既有 DeviceLabel 分类标签位)。
- **设备记录管理**:Device 主屏新增"Saved devices"入口(`TrackTechRow` 区,DeviceHomeScreen.kt:214 附近),进入已保存设备列表——支持改别名、删除单条记录。
- **FileLogger 埋点**(road-test-first 模式安全网):设备写表 / 冷启动重连决策(查到谁、连接结果)/ 别名变更 / 记录删除,全部落 `FileLogger.d("BleDeviceMemory", ...)`。

## Capabilities

### New Capabilities
- `ble-device-memory`: BLE 设备记忆——连接成功持久化、设备别名、冷启动自动连接上次设备、扫描列表"上次连接"标识、已保存设备记录管理(改名/删除)。

### Modified Capabilities
- `room-migration-chain`: 新增 migration7To8 requirement(bluetooth_devices 加 alias + lastConnectedAtMs 两 nullable 列,migrationChain 覆盖范围延伸到 v8)。

## Impact

- **core/data**:`BluetoothDeviceEntity`(+2 字段)、`BluetoothDeviceModel`(+2 字段)、`EntityMapper`、`BluetoothDeviceDao`(+按时间查最近/更新别名/touch 时间)、`BluetoothDeviceRepository`(+对应方法)、`AppDatabase`(version 8 + migration7To8 + migrationChain)。
- **core/bluetooth**:`BleDeviceManager`(构造加闭包参数 + autoReconnectLastDevice 填实 + connect 成功后保存)——**不碰** `BleConnection` / `BluetoothDataSource`(A40 契约与会话内重连逻辑不动)。
- **feature/test**:`AppModule.kt`(bluetoothModule 闭包接线)、`GpsDataViewModel`(暴露已保存设备 flow + 别名/删除操作)、`BleScanBottomSheet`(别名显示 + Last connected 徽标)、`DeviceHomeScreen`(别名显示 + Saved devices 入口)、新增已保存设备管理 UI(sheet/dialog)。
- **不碰**:GPS 接收公共协议、replay 协议、`BleConnection` GATT 生命周期、会话内指数退避重连(`BluetoothDataSource`)。
- **行为变化**:冷启动从"必扫描"变为"优先自动连上次设备,失败再扫描"——设备不在场时多一段 10s 重连等待(既有 RECONNECT_TIMEOUT_MS 骨架)再进扫描,首次使用(无记录)行为不变。
- **流程档位**:road-test-first(user 2026-06-06 授权)——去 Codex、不调 Opus 子 agent、CC 单遍自审 + FileLogger 埋点 + 真机攒批路测;复杂度 medium(Room migration 命中强制升级场景,在本模式下意味着 CC design 期格外谨慎 + 自审加深,不意味恢复子 agent review)。
