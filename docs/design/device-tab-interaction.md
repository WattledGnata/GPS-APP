# Device Tab Interaction Design

Device 是四个底部 tab 里的基础设施页。
它不是普通设置页，而是所有测试能力的可信度来源：用户要能在这里回答“现在能不能测”“不能测是哪里坏了”“我要怎么修”。

底部 tab：

```text
Test | Laps | Records | Device
```

其中 Device 负责：

- BLE GPS 设备扫描、连接、断开、重连。
- GPS fix / 采样率 / 卫星 / HDOP / 数据新鲜度。
- 测试可用性解释。
- 权限与设备异常处理。
- 常用设置入口。
- 高级诊断入口。

## Page Role

Device tab 的首页不应该是密密麻麻的调试数据。

它应该有三层信息：

1. **能不能测**：大状态、一句话结论。
2. **一眼确认**：BLE 连接、卫星数、采样率、质量这类轻量指标。
3. **更细的数据**：HDOP、RSSI、数据新鲜度、协议、坐标、日志等放到下一层详情页。

## Visual Style

沿用 Track Tech 风格：

- 黑色/石墨背景。
- 切角面板。
- 紫色标题和当前态边框。
- 绿色表示 Ready / Connected / Good。
- 红色表示 Disconnected / Missing permission / Bad quality。
- Cyan 表示 BLE / satellite / telemetry。
- 数码字体只用于关键数值，例如 `25Hz`, `12`, `0.8`。

不要把 Device 做成 Android 系统设置页。
它仍然应该像赛车仪表盘里的设备状态屏。

## Device Home Layout

参考 360dp 宽手机，内容可滚动，底部 tab 固定。

```text
┌────────────────────────────────────┐
│ Device                       ⚙     │
│                                    │
│ ┌────────────────────────────────┐ │
│ │        READY TO TEST            │ │
│ │  GPS locked · BLE connected     │ │
│ │  25Hz · Quality Good            │ │
│ └────────────────────────────────┘ │
│                                    │
│ ┌──────────┬──────────┬──────────┐ │
│ │   BLE    │   SATS   │  RATE    │ │
│ │Connected │    12    │  25Hz    │ │
│ └──────────┴──────────┴──────────┘ │
│                                    │
│ ┌────────────────────────────────┐ │
│ │ Connected Device                │ │
│ │ RaceChrono GPS                  │ │
│ │ Ready for Test            Scan  │ │
│ └────────────────────────────────┘ │
│                                    │
│ ┌────────────────────────────────┐ │
│ │ GPS Details                     │ │
│ │ Quality Good · More metrics     │ │
│ └────────────────────────────────┘ │
│                                    │
│ ┌────────────────────────────────┐ │
│ │ Diagnostics                     │ │
│ │ Protocol OK · Last packet ...   │ │
│ └────────────────────────────────┘ │
│                                    │
│ ┌────────────────────────────────┐ │
│ │ Settings                        │ │
│ │ Units · Voice · Auto reconnect  │ │
│ └────────────────────────────────┘ │
└────────────────────────────────────┘
```

## Sections

### 1. Header

Content:

- Title: `Device`
- Right action: gear icon for Settings.

Click:

- Gear opens Settings screen or settings bottom sheet.

### 2. Readiness Hero

Purpose:

- Give one clear conclusion.
- Avoid forcing user to interpret HDOP/satellites first.

States:

```text
READY TO TEST
GPS locked · BLE connected
25Hz · Quality Good
```

```text
CONNECT GPS DEVICE
No BLE GPS connected
Scan nearby devices
```

```text
WAITING FOR GPS LOCK
BLE connected · satellites 4/6
Move device near open sky
```

```text
SIGNAL UNSTABLE
GPS data stale · quality poor
Check device placement
```

Visual:

- Ready: green status dot + purple/cyan accent.
- Warning: red/orange edge.
- Waiting: purple/cyan edge.

Click:

- If disconnected: opens scan sheet.
- If connected: opens GPS detail / diagnostics.

### 3. Quick Status Grid

Device 首页保留轻量指标，让用户不用进详情也能快速确认 GPS 状态。

Three equal cards:

- BLE
- Satellites
- Rate / Quality

Each card:

- Icon.
- State label.
- One supporting metric.

Examples:

```text
BLE
Connected
RaceChrono
```

```text
SATS
12
Ready
```

```text
RATE
25Hz
Good
```

Alternative third card:

```text
QUALITY
Good
HDOP 0.8
```

首页建议优先展示：

- BLE: connected / disconnected。
- Satellites: `12`，因为它是普通用户也能理解的 GPS 可用性指标。
- Rate 或 Quality: `25Hz` / `Good`，用于说明外置 GPS 数据流是否稳定。

不要在首页同时展开 HDOP、Fresh、RSSI、Protocol、坐标。它们进入 GPS Details。

Click:

- BLE card opens Connected Device section / scan.
- Satellites card opens GPS Details.
- Quality card opens explanation of readiness thresholds.

### 4. Connected Device Panel

Content when connected:

- Device name.
- Connection state.
- Short readiness text.
- Last connected time / auto reconnect state if available.
- Actions: `Disconnect`, `Scan`.

RSSI and protocol can appear in GPS Details / Diagnostics instead of DeviceHome, unless the user explicitly opens detail.

Content when disconnected:

- Empty state: `No GPS device connected`.
- Primary action: `Scan Devices`.
- Secondary note: permissions if missing.

Scan behavior:

- Scan opens a bottom sheet, not a full-screen page.
- List nearby devices with name, address suffix, RSSI, and last seen.
- Clicking device starts connection.
- During connection, row shows `Connecting...`.
- On success, sheet can collapse and Device home shows connected state.

### 5. GPS Details Entry

Device 首页只放 GPS Details 入口和 1 行摘要。

Example:

```text
GPS DETAILS
Quality Good · 12 sats · 25Hz
```

Click:

- Opens GPS Details screen.

### 6. GPS Details Screen

This is the expandable user-facing GPS detail page.

It should use repeatable `MetricSection` + `MetricTile` blocks, so new metrics can be added without redesigning the page.

```text
GPS Details
  ├─ Summary
  │   ├─ Ready / Not Ready
  │   └─ Reason
  ├─ Signal
  │   ├─ Satellites
  │   ├─ HDOP
  │   ├─ Fix state
  │   └─ Quality
  ├─ Data Stream
  │   ├─ Rate
  │   ├─ Freshness
  │   ├─ Dropped packets
  │   └─ Last packet
  ├─ Position
  │   ├─ Latitude
  │   ├─ Longitude
  │   ├─ Altitude
  │   └─ Bearing
  └─ Device
      ├─ RSSI
      ├─ Protocol
      ├─ Address
      └─ Firmware
```

Metrics:

- Satellites: `12`
- HDOP: `0.8`
- Sample rate: `25Hz`
- Data freshness: `42ms`
- Speed source: external GPS
- Fix state: fixed / not fixed

Threshold language:

- Satellites good: `>= 6`
- HDOP good: `< 2.0`
- Rate good: target `25Hz`
- Freshness good: recent packet within the app-defined window

Do not expose every raw protocol value here.

### 7. Diagnostics Panel

This is for advanced users and debugging.

Collapsed by default.

Summary:

- Protocol status.
- Last packet age.
- Parser errors / dropped packets if available.
- Firmware or device metadata if available.

Click:

- Opens Advanced Diagnostics.

This can include a dense page later:

- raw NMEA/RaceChrono packet status,
- packet rate graph,
- location coordinates,
- altitude,
- bearing,
- speed,
- parser error counts,
- log export.

### 8. Settings Panel

Device tab includes settings entry, but settings is not a bottom tab.

First-level settings:

- Units: km/h, mph.
- Voice prompts: on/off.
- Auto reconnect: on/off.
- Keep screen awake during test: on/off.
- Professional settings.

Professional settings:

- Readiness thresholds.
- GPS protocol mode.
- Logging.
- Debug overlays.

## Page Stack

```text
DeviceHome
  ├─ ScanDevicesBottomSheet
  ├─ GpsDetail
  ├─ AdvancedDiagnostics
  └─ Settings
       └─ ProfessionalSettings
```

首版可以只实现：

```text
DeviceHome
  ├─ ScanDevicesBottomSheet
  └─ Settings
```

GPS Detail 和 Advanced Diagnostics 可以先用占位入口，但首页的信息结构要预留。

## State Model

Device tab 至少要表达这些组合状态：

### All Ready

- BLE connected.
- GPS fixed.
- Quality good.
- Data fresh.

Primary message:

```text
READY TO TEST
```

### BLE Disconnected

- No connected device.

Primary message:

```text
CONNECT GPS DEVICE
```

Primary action:

```text
SCAN DEVICES
```

### Permission Missing

- Android BLE / Location permission missing.

Primary message:

```text
PERMISSION REQUIRED
```

Primary action:

```text
GRANT PERMISSION
```

### GPS Not Ready

- BLE connected but GPS does not pass readiness.

Primary message:

```text
WAITING FOR GPS LOCK
```

Supporting explanation:

```text
Satellites 4/6 · HDOP 2.8
```

### Data Stale

- BLE connected but incoming data stopped or is too old.

Primary message:

```text
SIGNAL LOST
```

Supporting explanation:

```text
Last packet 2.4s ago
```

## Click Targets

Minimum target size:

- Icons/buttons: `48dp`.
- Cards/panels: whole panel clickable when it represents a destination.

Clickable elements:

- Settings gear.
- Readiness Hero.
- BLE card.
- GPS card.
- Quality card.
- Scan Devices.
- Connected device row.
- Disconnect.
- GPS Signal panel.
- Diagnostics panel.
- Settings panel.

Non-clickable elements:

- Static metric labels unless inside a clickable panel.
- Decorative waveform, slashes, grids.

## Scroll Behavior

- DeviceHome is vertically scrollable.
- BottomNav is fixed.
- Readiness Hero and Core Status Grid should fit in the first viewport.
- ScanDevicesBottomSheet scrolls independently.
- Advanced Diagnostics can be dense and scroll-heavy.

## Relationship To Other Tabs

Device status is global.

Test and Laps should show compact status strips, but Device owns the detailed explanation.

```text
Test/Laps compact status strip
        │
        ▼
Device tab detailed status and repair path
```

Examples:

- Test page shows `GPS ready · 25Hz · Good signal`.
- User taps it.
- App opens Device tab or Device detail.
- Device explains `12 satellites · HDOP 0.8 · last packet 42ms`.

## Global Gating Behavior

Device tab owns connection and repair flows. Other tabs should not duplicate full device setup.

When device/GPS is not ready:

- Test tab still shows acceleration/braking cards, but primary start actions are disabled or changed to `Connect GPS First`.
- Laps tab still shows current track/track selection, but `Start Lap Session` is disabled or changed to `Connect GPS First`.
- Records tab remains usable because it does not require live GPS.
- Compact status strips in Test/Laps are clickable and navigate to Device.

Recommended disabled state:

```text
GPS DEVICE REQUIRED
Connect a BLE GPS receiver to start testing
[GO TO DEVICE]
```

Navigation:

```text
Test / Laps blocked action
        │
        ▼
Device tab
        │
        ▼
ScanDevicesBottomSheet
        │
        ▼
Connected + GPS Ready
        │
        ▼
Return to previous tab, or let user tap tab manually
```

首版可以简单处理：

- 用户在 Test/Laps 点不可用主按钮。
- App 切到 Device tab。
- Device tab 自动突出 `Scan Devices`。

后续再增强：

- 记住用户来自 Test 还是 Laps。
- 连接成功后显示 `Return to Test` / `Return to Laps`。
- 如果连接成功但 GPS 未 ready，留在 Device 并解释原因。

## Open Questions

- Should scan be a bottom sheet or full-screen page on very small devices?
- Should Device tab auto-open scan on first launch if no device is connected?
- Do we want a separate onboarding flow before DeviceHome, or is DeviceHome itself enough?
- How much raw GPS data should normal users see before entering Advanced Diagnostics?
