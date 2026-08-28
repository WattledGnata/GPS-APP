# 蓝牙外接 GPS 电量显示 — UX 与数据架构设计

## 背景

v2 版本外接 GPS 硬件通过 BLE 标准 Battery Service（`0x180F`）上报设备电量。部分 v2 设备具备此能力，部分没有。当前 GPS App 未读取也未展示电量信息。

技术接入文档：`外接GPS电量Android接入说明.md`（2026-07 微信交付），核心要点：

- BLE 标准 `0x2A19`（Battery Level）特征，1 字节 `0..100` 百分比
- 连接后读取一次 + 订阅 Notify/Indicate，后续被动接收
- 无此服务的设备 → 电量视为 `null`（不是 `0%`）
- 断连或切换设备 → 清空为 `null`
- iOS 端已采用相同约定

## 目标

- 在 Device 首页的设备卡片中展示外接 GPS 电量（电池图标 + 百分比数字）
- 无电量硬件的设备优雅降级：灰色图标 + `N/A`
- 不增加用户认知负担，不需要用户区分"哪个版本硬件"

## 非目标（本轮不做）

- 不做低电量告警（Toast / Snackbar / 变色提醒）
- 不在 TrackTechStatusStrip / LapLiveScreen / TestExecutionScreen 展示电量
- 不持久化电量到 Room
- 不记录电量历史曲线
- 不在扫描列表展示电量（扫描时尚未连接，无法读取）
- 不在 Simulator（GattServerManager）模拟 Battery Service —— simulator 连接真实设备场景本身就有真实电量；纯 simulator 场景可后续再补

---

## UX 设计（4 项决策，已与用户确认）

### Decision 1：展示位置 — ConnectedDeviceCard

电量展示在 Device 首页的 `ConnectedDeviceCard` 内，与设备名、连接状态指示灯同行或相邻行。

不放入 `QuickStatusRow`（BLE/SATS/RATE 三卡行），理由：
- 电量是设备属性，不是 GPS 信号质量指标
- 三卡行在小屏（vivo V2405A）上已经紧凑
- `ConnectedDeviceCard` 是设备身份的天然容器

### Decision 2：无电量硬件 — 灰色图标 + `N/A`

当设备没有 `0x180F` Battery Service 时（`batteryPercent == null` 且已确认 services discovered 完成）：

- 电池图标使用 `Icons.Filled.BatteryUnknown`（Material Icons 的问号电池），灰色（`TrackTechColors.onSurfaceSecondary`）
- 数字区域显示 `N/A`（Score 字体，小号，灰色）
- 不做闪烁/loading，不给用户假期待

### Decision 3：视觉样式 — 图标 + Mechanical 数字

| 元素 | 样式 |
|------|------|
| 电池图标 | Material `Icons.Filled.Battery*`（0-5 档，按百分比映射），颜色按阈值：>20% 白 / ≤20% 红 |
| 百分比数字 | `MetricNumber(value = "$pct", unit = "%", size = Small, kind = Mechanical)` — DSEG7 七段字体 |
| 布局 | 水平 Row：电池图标 → Spacer(4.dp) → MetricNumber |

**图标档位映射**（Material Icons 内置 7 档电池图标）：
- `100` → `BatteryFull`
- `90-99` → `Battery6Bar`
- `70-89` → `Battery5Bar`
- `50-69` → `Battery4Bar`
- `30-49` → `Battery3Bar`
- `10-29` → `Battery2Bar`
- `1-9` → `Battery1Bar`
- `0` → `BatteryAlert`（红色）

**非标准电量处理**：
- `value > 100` → 视为 `null`（等同 N/A），不映射到图标
- `value == 0` → 仍展示 `0`%（可能是真没电），但图标用 `BatteryAlert` 红色
- `null` → `N/A` + `BatteryUnknown` 灰色

### Decision 4：不做低电量告警

纯被动展示，不为 ≤20% / ≤10% 触发任何主动通知。用户在实际使用中自然会看到 Device 页。

---

## 数据架构设计

### 数据模型

**不修改 `GpsData`**。电量不是 GPS 定位数据，不应污染 GPS 数据流。

**新增独立的电量 StateFlow**，路径与 `connectionState` 平行：

```
BleConnection (GATT 层)
  └─ _batteryPercent: MutableStateFlow<Int?>
       │
BluetoothDataSource
  └─ batteryPercent: StateFlow<Int?>
       │
GpsDataRepository
  └─ batteryPercent: StateFlow<Int?>
       │
GpsDataViewModel
  └─ batteryPercent: StateFlow<Int?>
       │
DeviceHomeScreen → ConnectedDeviceCard (Compose UI)
```

### BLE GATT 集成点：`BleConnection`

在现有 `onServicesDiscovered()` 回调中，**在现有 GPS 特征通知启用之后**，追加 Battery Service 发现逻辑：

1. `gatt.getService(0x180F)` → null → 确认无电量能力，设 `_batteryPercent = null`，结束
2. `service.getCharacteristic(0x2A19)` → null → 同上
3. 检查 PROPERTY_NOTIFY / PROPERTY_INDICATE → 订阅 CCCD + 读一次
4. 仅 PROPERTY_READ → 只读一次，不订阅
5. 回调中 `parseBatteryPercent(value)` → `_batteryPercent.value = percent`

**GATT 操作串行约束**：Battery Service 的 CCCD 写和 readCharacteristic 操作 **追加在现有 `processNextDescriptor()` 队列之后**，不插入现有 GPS 特征序列中间，避免打乱已工作的 GPS 通知启用流程。

### 解析函数

```kotlin
private fun parseBatteryPercent(value: ByteArray?): Int? {
    val percent = value?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
    return percent.takeIf { it in 0..100 }
}
```

无效值（>100、空数据）→ 保留上一次有效值，不更新 `_batteryPercent`。

### 断连/切换行为

- `BleConnection.disconnect()` / `cleanup()` → `_batteryPercent.value = null`
- `BleConnection.connect()` 新设备 → 初始 `null`，待 `onServicesDiscovered()` 后确定

### ViewModel 暴露

`GpsDataViewModel` 新增：

```kotlin
val batteryPercent: StateFlow<Int?> = gpsDataRepository.batteryPercent
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
```

### ConnectedDeviceCard UI 变更

在现有设备名 + 状态圆点行下方，新增一行：

```kotlin
// 电池行（仅在有连接意图时显示）
if (connectionState == ConnectionState.CONNECTED || 
    connectionState == ConnectionState.CONNECTING) {
    BatteryIndicator(batteryPercent)
}
```

`BatteryIndicator` 为 private composable：
- `batteryPercent != null` → 电池图标（按档位）+ Mechanical 数字 + `%`
- `batteryPercent == null` 且 `connectionState == CONNECTED`（services 已发现） → 灰色 `BatteryUnknown` + `N/A`
- `CONNECTING` + `null` → 不显示（服务尚未发现，无法判断有无电量能力）

---

## 边缘情况与异常处理

| 场景 | 行为 |
|------|------|
| 设备无 `0x180F` 服务 | `servicesDiscovered` 后确认 null → `N/A` |
| `0x180F` 存在但无 `0x2A19` | 同上，视为无电量能力 |
| 特征仅支持 READ 不支持 NOTIFY/INDICATE | 只主动读一次，后续不更新 |
| 收到 `>100` 或空数据 | 丢弃本条，保留上一次有效值 |
| 连接中收到非法值，此前从未有有效值 | 保持 null |
| 断连 | 立即清 null |
| 切换到另一台设备 | 旧设备断连清 null → 新设备 services discovered 后更新 |
| BLE 连接但 CCCD 写入失败 | 尝试主动读一次（与文档推荐流程一致） |
| 电量 0% | 展示 `0%` + `BatteryAlert` 红色图标（真没电 vs null 语义不同） |

---

## 测试策略

### 单元测试

- `parseBatteryPercent()` 纯函数测试：
  - 正常值 `0x55` → `85`
  - 边界 `0x00` → `0`，`0x64` → `100`
  - 非法 `0x65` (101) → `null`
  - 空 `byteArrayOf()` → `null`
  - `null` → `null`
- `BleConnection` 生命周期测试：
  - `servicesDiscovered` 后 `_batteryPercent` 非 null（mock 有 Battery Service）
  - `servicesDiscovered` 后 `_batteryPercent` = null（mock 无 Battery Service）
  - `disconnect()` 后 `_batteryPercent` = null

### Compose UI 测试

- `BatteryIndicator`：有值展示图标+数字，null 展示 N/A
- `ConnectedDeviceCard`：已连接时有电池行，未连接时无

### 真机验证

- vivo V2405A（小屏）：确认 ConnectedDeviceCard 加入电池行后不换行/不溢出
- 华为 8KE0219522008434：正常展示电量（如设备支持）或 N/A（如不支持）
- 连接的 GPS 设备需是 v2 硬件（支持 Battery Service）才能看到实际电量数字

---

## 风险

| 风险 | 缓解 |
|------|------|
| GATT 操作串行队列因新增 Battery 操作而延迟 GPS 通知启用 | Battery 操作追加在现有队列末尾，不插入 GPS 序列中间；GPS 特征先于 Battery 完成通知启用 |
| Material Icons 的 `Battery6Bar` 等 6 档图标在部分 Android 版本不存在 | `BatteryStd`/`BatteryFull`/`BatteryAlert` 是通用图标，作为 fallback；构建时目标 API 35 应包含全套 |
| Mechanical 字体显示 `%` 符号会变形 | `%` 放入 `unit` 参数（而非 value 字符串），`MetricNumber` 用 UiText 字体渲染 unit |

---

## 与现有规则的兼容性

- 不涉及公共协议（GPS 接收链路 / replay 协议）修改
- 不涉及 Room schema migration
- 不涉及跨 capability ripple
- 纯新增数据通道 + UI，属于 small 复杂度，走加速通道
