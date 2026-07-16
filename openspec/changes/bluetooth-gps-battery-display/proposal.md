## Why

v2 版本外接 GPS 硬件通过 BLE 标准 Battery Service（`0x180F`）上报设备电量，但当前 GPS App 完全未读取、未展示。用户在赛道日无法判断外接 GPS 剩余电量——一旦中途没电，跑圈/测试数据直接中断且无预警窗口。iOS 端已接入同一协议，Android 端补齐此能力可保持双端一致。

## What Changes

- BLE GATT 层新增 Battery Service（`0x180F`）发现与 Battery Level（`0x2A19`）特征读取/订阅
- 电量数据通过独立 `StateFlow<Int?>` 通道传递（不修改 `GpsData`，不污染 GPS 定位数据流）
- `GpsDataViewModel` 暴露 `batteryPercent: StateFlow<Int?>` 供 UI 消费
- Device 首页 `ConnectedDeviceCard` 新增电池指示器：电池图标（7 档）+ Mechanical 百分比数字
- 无 Battery Service 的设备优雅降级：灰色图标 + `N/A`
- 断连/切换设备自动清空电量为 null

## Capabilities

### New Capabilities
- `ble-device-battery`: BLE 外接 GPS 设备电量读取、传递与 UI 展示。涵盖 GATT 层 Battery Service 集成、数据通道、ConnectedDeviceCard 电池指示器及无电量硬件的降级行为。

### Modified Capabilities
（无）

## Impact

| 模块 | 影响 |
|------|------|
| `core/bluetooth` | `BleConnection` 新增 Battery Service GATT 发现与特征处理；`BluetoothDataSource` 代理 `batteryPercent` StateFlow；`GpsDataRepository` 透传 |
| `core/domain` | 无修改（电量不入 `GpsData`） |
| `core/data` | 无修改（不持久化电量到 Room） |
| `feature/test` | `GpsDataViewModel` 暴露 `batteryPercent`；`DeviceHomeScreen` 新增 `BatteryIndicator` Composable |
| `app` | 无修改 |
| `simulator` | 本轮不做（simulator 连接真实设备场景本身有真实电量；纯 simulator 场景后续再补） |
