## Context

v2 外接 GPS 硬件通过 BLE 标准 Battery Service（`0x180F`，特征 `0x2A19`）上报 1 字节电量百分比（`0..100`）。当前 `BleConnection` 只处理 RaceChrono GPS 服务（`00001ff8-...`）的两个特征，完全未发现 Battery Service。iOS 端已接入相同协议。

现有数据架构：`BleConnection`（GATT 持有者）→ `BluetoothDataSource`（聚合 + 自动重连）→ `GpsDataRepository`（薄封装）→ `GpsDataViewModel`（单例，所有 Screen 共享）。电量需要在 Device 首页 `ConnectedDeviceCard` 中展示（UX 决策已确认：方案 B — 与设备卡片绑定）。

约束：
- 不修改公共 RaceChrono BLE 协议
- 不修改 `GpsData`（电量不是 GPS 定位数据）
- 不持久化到 Room
- Battery Service 发现失败的设备视为无电量能力（`null`，不是 `0%`）

## Goals / Non-Goals

**Goals:**
- BLE 层发现/读取/订阅 Battery Service（`0x180F`/`0x2A19`）
- 独立 `StateFlow<Int?>` 通道传递电量，不污染 GPS 数据流
- Device 首页 `ConnectedDeviceCard` 中展示电池图标 + Mechanical 百分比数字
- 无 Battery Service 设备优雅降级：灰色 `BatteryUnknown` + `N/A`

**Non-Goals:**
- 不做低电量告警
- 不做电量历史 / 持久化
- 不在其他 Screen（LapLive / TestExecution / StatusStrip）展示
- 不在 Simulator（`GattServerManager`）模拟 Battery Service

## Decisions

### Decision 1: 独立 `StateFlow<Int?>` 通道，不修改 `GpsData`

**选**：新增 `BleConnection._batteryPercent: MutableStateFlow<Int?>`，经 `BluetoothDataSource` → `GpsDataRepository` → `GpsDataViewModel` 传导。

**理由**：电量是设备属性，不是 GPS 定位数据。`GpsData` 是 25Hz 高频更新 + parser 产出，混入低频电量（分钟级更新）会污染语义、增加 parser 测试表面积。

**拒绝的方案**：在 `GpsData` 中加 `batteryPercent: Int?` 字段。User 已在 UX 讨论中确认不要。

### Decision 2: Battery Service 发现追加在 GPS 通知启用完成后

**选**：在 `onDescriptorWrite` 中，GPS 两个 CCCD 全部写完 + handshake 判定 CONNECTED 之前，插入 `setupBattery(gatt)` 调用。

**理由**：BLE GATT 操作必须串行。插入现有 GPS 特征序列中间会打乱已工作的通知启用流程；在握手完成后追加是零风险的插入点。

**拒绝的方案**：在 `onServicesDiscovered` 中同时启动 GPS 和 Battery 的 CCCD 写链。GATT 串行约束下并发 CCCD 写可能导致 Android BLE stack 返回 `status=133`（GATT_ERROR）。

### Decision 3: 电量百分比类型用 `Int?` 而非 `Int` + 哨兵值

**选**：`null` = 无电量能力 / 未读到 / 非法值。`0` 仍表示电量 0%（红色警告图标）。

**理由**：符合技术文档建议（"业务层应使用 null，不要显示为 0%"），与 iOS 端语义一致（非法或缺失数据视为未知电量）。

**拒绝的方案**：`-1` 哨兵。NOT NULL DEFAULT 哨兵风险（CLAUDE.md v3 高频盲点 #6）：未来 UI 用旧数据时误命中。

### Decision 4: 电量图标从 Material Icons Extended 取 7 档电池图标

**选**：`BatteryFull` / `Battery6Bar` ~ `Battery1Bar` / `BatteryAlert`，按百分比映射。

**理由**：Material Icons Extended 提供 0-7 档电池图标，已在 Android Compose 生态广泛可用。细粒度图标比三档（Full/Std/Alert 从基础库）更直观。

**拒绝的方案**：只用基础 material-icons 的三档（`BatteryFull`/`BatteryStd`/`BatteryAlert`）。可工作但 7 档精度更符合赛道场景（用户可能关心 85% vs 55% 的区别）。

### Decision 5: 不做低电量告警

**选**：仅被动展示电量。不弹 Toast/Snackbar，不在 LapLiveScreen 或 TestExecutionScreen 告警。

**理由**：User 已在 UX 讨论中明确选 A（不做）。≤20% 变红已是足够的视觉提示，不增加跨屏传递的复杂度。

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| GATT 操作因新增 Battery CCCD 写延迟 GPS 通知启用 | Battery 操作在 GPS handshake 完成后触发，不插入 GPS 序列中间 |
| Material Icons Extended 的 `Battery1Bar`~`Battery6Bar` 在旧版 Compose 库不存在 | 编译验证；不可用时 fallback 到 `BatteryStd`/`BatteryFull`/`BatteryAlert` 三档简化映射 |
| Mechanical 字体 `%` 符号变形 | `%` 放入 `MetricNumber.unit` 参数，用 UiText 字体渲染 unit |
