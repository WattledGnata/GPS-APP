# RaceChrono BLE 协议文档

## 1. 概述

本文档描述了 RaceChrono DIY GPS 设备使用的 BLE 协议，该协议由 ESP32 固件实现，用于与 RaceChrono 应用或自定义 Android 应用通信。

## 2. BLE 服务和特性

### 2.1 服务 UUID

| 服务类型 | UUID |
|---------|------|
| RaceChrono DIY 主服务 | `00001ff8-0000-1000-8000-00805f9b34fb` |

### 2.2 特性 UUID

| 特性类型 | UUID | 权限 |
|---------|------|------|
| GPS 主数据 | `00000003-0000-1000-8000-00805f9b34fb` | READ, NOTIFY |
| GPS 时间数据 | `00000004-0000-1000-8000-00805f9b34fb` | READ, NOTIFY |

## 3. 数据格式

### 3.1 GPS 主数据格式（20 字节）

GPS 主数据通过 `00000003-0000-1000-8000-00805f9b34fb` 特性传输，总长度 20 字节。

| 字段 | 字节偏移 | 大小 | 数据类型 | 描述 |
|------|---------|------|---------|------|
| 时间戳 | 0-2 | 3 | bitfield | Byte 0 高3位: syncBits; Byte 0 低5位 + Byte 1-2: timeSinceHourStart (每个单位=2ms) |
| 定位质量 | 3 | 1 | bitfield | 高2位: fixQuality; 低6位: satellites |
| 纬度 | 4-7 | 4 | int32 (big endian) | 纬度，单位：度 * 10,000,000 |
| 经度 | 8-11 | 4 | int32 (big endian) | 经度，单位：度 * 10,000,000 |
| 海拔 | 12-13 | 2 | uint16 (big endian) | 特殊编码，详见 3.3 节 |
| 速度 | 14-15 | 2 | uint16 (big endian) | 特殊编码，详见 3.3 节 |
| 方位角 | 16-17 | 2 | uint16 (big endian) | 方位角，单位：度 * 100 |
| HDOP | 18 | 1 | uint8 | 直接值 * 0.1 |
| VDOP | 19 | 1 | uint8 | 直接值 * 0.1 |

**字节布局详解：**

```
Byte 0: [syncBits:3][timeSinceHourStart高5位:5]
Byte 1: [timeSinceHourStart[15:8]]
Byte 2: [timeSinceHourStart[7:0]]
Byte 3: [fixQuality:2][satellites:6]
Byte 4-7: latitude (big endian int32)
Byte 8-11: longitude (big endian int32)
Byte 12-13: altitude (big endian uint16, 特殊编码)
Byte 14-15: speed (big endian uint16, 特殊编码)
Byte 16-17: bearing (big endian uint16)
Byte 18: HDOP
Byte 19: VDOP
```

### 3.2 GPS 时间数据格式（3 字节）

GPS 时间数据通过 `00000004-0000-1000-8000-00805f9b34fb` 特性传输，总长度 3 字节。

| 字段 | 字节偏移 | 大小 | 数据类型 | 描述 |
|------|---------|------|---------|------|
| 日期时间 | 0-2 | 3 | bitfield | Byte 0 高3位: syncBits; Byte 0 低5位 + Byte 1-2: dateAndHour |

**编码公式：**

```
dateAndHour = (year - 2000) * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
```

**解码公式：**

```
yearOffset = dateAndHour / 8928
remainder = dateAndHour % 8928
month = remainder / 744
remainder2 = remainder % 744
day = remainder2 / 24
hour = remainder2 % 24
year = 2000 + yearOffset
```

### 3.3 定位质量值

| 定位质量 | 描述 |
|---------|------|
| 0 | 无效定位 |
| 1 | GPS 定位 |
| 2 | DGPS 定位 |

### 3.4 海拔和速度特殊编码

altitude 和 speed 字段使用 2 字节 uint16，但使用 bit 15 作为 overflow 标志：

**海拔（Altitude）:**

- Bit 15 = 0（无溢出）: `alt = raw / 100.0 - 500.0`
  - raw 范围: 0-32767, 对应 -500.0m 到 277.67m
- Bit 15 = 1（有溢出）: `alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0`
  - raw 范围: 32768-65535, 对应 277.68m 到 6052.7m

**速度（Speed）:**

- Bit 15 = 0（无溢出）: `speed = raw / 100.0`
  - raw 范围: 0-32767, 对应 0 到 327.67 km/h
- Bit 15 = 1（有溢出）: `speed = ((raw & 0x7FFF) * 10) / 100.0`
  - raw 范围: 32768-65535, 对应 327.68 到 6553.5 km/h

### 3.5 数据解析示例

#### GPS 主数据解析（20 字节）

```kotlin
private fun parseGpsData(data: ByteArray) {
    if (data.size < 20) {
        return // 数据长度不足
    }

    // Byte 0: sync + time high
    val syncBits = (data[0].toInt() shr 5) and 0x07
    val timeHigh = data[0].toInt() and 0x1F
    val timeMid = data[1].toInt() and 0xFF
    val timeLow = data[2].toInt() and 0xFF
    val timeSinceHourStart = ((timeHigh shl 16) or (timeMid shl 8) or timeLow) * 2  // 每个单位=2ms

    // Byte 3: fix + satellites
    val fixQuality = (data[3].toInt() shr 6) and 0x03
    val satellites = data[3].toInt() and 0x3F

    // Byte 4-7: latitude (big endian int32)
    val latInt = ((data[4].toInt() and 0xFF) shl 24) or
                 ((data[5].toInt() and 0xFF) shl 16) or
                 ((data[6].toInt() and 0xFF) shl 8) or
                 (data[7].toInt() and 0xFF)
    val latitude = latInt / 10000000.0

    // Byte 8-11: longitude (big endian int32)
    val lonInt = ((data[8].toInt() and 0xFF) shl 24) or
                 ((data[9].toInt() and 0xFF) shl 16) or
                 ((data[10].toInt() and 0xFF) shl 8) or
                 (data[11].toInt() and 0xFF)
    val longitude = lonInt / 10000000.0

    // Byte 12-13: altitude special encoding
    val altRaw = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
    val altitude = if ((altRaw and 0x8000) == 0) {
        (altRaw and 0x7FFF) / 100.0 - 500.0
    } else {
        ((altRaw and 0x7FFF) * 10) / 100.0 - 500.0
    }

    // Byte 14-15: speed special encoding
    val speedRaw = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
    val speed = if ((speedRaw and 0x8000) == 0) {
        (speedRaw and 0x7FFF) / 100.0
    } else {
        ((speedRaw and 0x7FFF) * 10) / 100.0
    }

    // Byte 16-17: bearing (big endian uint16)
    val bearingInt = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
    val bearing = bearingInt / 100.0

    // Byte 18-19: HDOP/VDOP
    val hdop = data[18].toInt() / 10.0
    val vdop = data[19].toInt() / 10.0
}
```

#### GPS 时间数据解析（3 字节）

```kotlin
private fun parseGpsTimeData(data: ByteArray) {
    if (data.size < 3) {
        return // 数据长度不足
    }

    val syncBits = (data[0].toInt() shr 5) and 0x07
    val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or
                      ((data[1].toInt() and 0xFF) shl 8) or
                      (data[2].toInt() and 0xFF)

    val yearOffset = dateAndHour / 8928
    val remainder = dateAndHour % 8928
    val month = remainder / 744
    val remainder2 = remainder % 744
    val day = remainder2 / 24
    val hour = remainder2 % 24
    val year = 2000 + yearOffset
}
```

## 4. 连接流程

1. 扫描 BLE 设备，查找广播 `00001ff8-0000-1000-8000-00805f9b34fb` 服务 UUID 的设备
2. 连接到设备
3. 发现服务和特性
4. 启用 `00000003-0000-1000-8000-00805f9b34fb` 和 `00000004-0000-1000-8000-00805f9b34fb` 特性的通知
5. 接收并解析 GPS 数据

## 5. 设备信息

- 设备名称：`RaceChronoDIY`
- 广播间隔：根据 BLE 规范自动调整
- 传输频率：25Hz（每 40ms 发送一次数据）

## 6. 硬件实现

### 6.1 硬件配置

- ESP32 开发板
- Ublox M9N GPS 模块
- GPS 波特率：115200
- GPS RX 引脚：13（ESP32 RX2）
- GPS TX 引脚：12（ESP32 TX2）

## 7. Android 应用集成

### 7.1 权限要求

| Android 版本 | 所需权限 |
|------------|---------|
| Android 12+ | BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION |
| Android 11 及以下 | BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION |

### 7.2 关键 API 调用

1. 初始化 BLE 适配器
2. 扫描带有指定服务 UUID 的设备
3. 连接到 GATT 服务器
4. 发现服务和特性
5. 注册特性变更监听器（根据 characteristic UUID 区分主数据和时间数据）
6. 解析接收到的数据

## 8. 数据更新频率

- BLE 数据传输频率：25Hz
- GPS 模块输出频率：可配置（默认 10Hz）
- 应用显示频率：根据设备性能自动调整

## 9. 故障排除

### 9.1 连接问题

- 确保设备已开启蓝牙
- 确保设备在蓝牙范围内
- 确保应用已获得所需权限

### 9.2 数据问题

- 卫星数量为 0：检查 GPS 天线连接
- 定位质量为 0：等待 GPS 定位完成
- 数据不稳定：检查电源供应和天线位置

## 10. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2024-01-17 | 初始版本 |
| 2.0 | 2026-03-22 | 更新为 ESP32 20字节协议格式；添加 GPS Time Data 格式；添加海拔/速度特殊编码说明 |

## 11. 附录

### 11.1 ESP32 固件 UUID 定义

```cpp
// RaceChrono BLE DIY Service UUID
#define SERVICE_UUID        "00001ff8-0000-1000-8000-00805f9b34fb"

// RaceChrono BLE DIY Characteristics UUIDs
#define GPS_MAIN_CHAR_UUID  "00000003-0000-1000-8000-00805f9b34fb"
#define GPS_TIME_CHAR_UUID  "00000004-0000-1000-8000-00805f9b34fb"
```

### 11.2 GPS 主数据结构体定义（ESP32 固件）

```cpp
// GPS Main Data structure (20 bytes)
// Byte 0: syncBits[7:5] + timeSinceHourStart[4:0]
// Byte 1: timeSinceHourStart[15:8]
// Byte 2: timeSinceHourStart[7:0]
// Byte 3: fixQuality[7:6] + satellites[5:0]
// Byte 4-7: latitude (big endian int32, degrees * 10,000,000)
// Byte 8-11: longitude (big endian int32, degrees * 10,000,000)
// Byte 12-13: altitude (big endian uint16, special encoding)
// Byte 14-15: speed (big endian uint16, special encoding)
// Byte 16-17: bearing (big endian uint16, degrees * 100)
// Byte 18: HDOP (raw value * 0.1)
// Byte 19: VDOP (raw value * 0.1)
```

### 11.3 GPS 时间数据结构体定义（ESP32 固件）

```cpp
// GPS Time Data structure (3 bytes)
// Byte 0: syncBits[7:5] + dateAndHour[4:0]
// Byte 1: dateAndHour[15:8]
// Byte 2: dateAndHour[7:0]
//
// dateAndHour = (year - 2000) * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
```

## 12. 联系方式

如有任何问题或建议，请联系开发团队。
