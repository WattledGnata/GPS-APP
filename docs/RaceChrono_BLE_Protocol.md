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

### 3.1 GPS 主数据格式

GPS 主数据通过 `00000003-0000-1000-8000-00805f9b34fb` 特性传输，数据格式如下：

| 字段 | 字节偏移 | 大小 | 数据类型 | 描述 |
|------|---------|------|---------|------|
| 同步位 | 0 | 1 | uint8_t | 3位同步计数器，取值范围 0-7 |
| 小时开始时间 | 1 | 4 | int | 从小时开始的毫秒数 |
| 定位质量和卫星数 | 5 | 1 | uint8_t | 高2位：定位质量，低6位：卫星数量 |
| 纬度 | 6 | 4 | int32_t | 纬度，单位：度 * 10,000,000 |
| 经度 | 10 | 4 | int32_t | 经度，单位：度 * 10,000,000 |
| 海拔 | 14 | 4 | int32_t | 海拔，编码格式 |
| 速度 | 18 | 4 | int32_t | 速度，编码格式 |
| 方位角 | 22 | 4 | int32_t | 方位角，单位：度 * 100 |
| HDOP | 26 | 1 | uint8_t | 水平精度因子，单位：0.1 |
| VDOP | 27 | 1 | uint8_t | 垂直精度因子，单位：0.1 |

### 3.2 定位质量值

| 定位质量 | 描述 |
|---------|------|
| 0 | 无效定位 |
| 1 | GPS 定位 |
| 2 | DGPS 定位 |

### 3.3 数据解析示例

以下是如何解析 GPS 主数据的示例：

```kotlin
private fun parseGpsData(data: ByteArray) {
    if (data.size < 28) {
        return // 数据长度不足
    }
    
    val syncBits = data[0].toInt() and 0x07 // 提取低3位
    val timeSinceHourStart = data.getInt(1)
    val fixQuality = (data[5].toInt() shr 6) and 0x03 // 提取高2位
    val satellites = data[5].toInt() and 0x3F // 提取低6位
    val latitude = data.getInt(6)
    val longitude = data.getInt(10)
    val altitude = data.getInt(14)
    val speed = data.getInt(18)
    val bearing = data.getInt(22)
    val hdop = data[26].toInt()
    val vdop = data[27].toInt()
    
    // 转换为实际值
    val actualLatitude = latitude / 10000000.0
    val actualLongitude = longitude / 10000000.0
    val actualAltitude = altitude / 100.0
    val actualSpeed = speed / 100.0
    val actualBearing = bearing / 100.0
    val actualHdop = hdop / 10.0
    val actualVdop = vdop / 10.0
}
```

## 4. 连接流程

1. 扫描 BLE 设备，查找广播 `00001ff8-0000-1000-8000-00805f9b34fb` 服务 UUID 的设备
2. 连接到设备
3. 发现服务和特性
4. 启用 `00000003-0000-1000-8000-00805f9b34fb` 特性的通知
5. 接收并解析 GPS 数据

## 5. 设备信息

- 设备名称：`RaceChronoDIY`
- 广播间隔：根据 BLE 规范自动调整
- 传输频率：25Hz（每 40ms 发送一次数据）

## 6. 硬件实现

ESP32 固件代码可参考：`RaceChrono_ESP32_M9N.ino`

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
5. 注册特性变更监听器
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

## 11. 附录

### 11.1 ESP32 固件主要代码片段

```cpp
// RaceChrono BLE DIY Service UUID
#define SERVICE_UUID        "00001ff8-0000-1000-8000-00805f9b34fb"

// RaceChrono BLE DIY Characteristics UUIDs
#define GPS_MAIN_CHAR_UUID  "00000003-0000-1000-8000-00805f9b34fb"
#define GPS_TIME_CHAR_UUID  "00000004-0000-1000-8000-00805f9b34fb"
```

### 11.2 数据结构定义

```cpp
// GPS data structure
struct GpsData {
  uint8_t syncBits;          // 3 bits sync counter
  int timeSinceHourStart;    // Time in milliseconds since hour start
  uint8_t fixQuality;        // 2 bits: 0=invalid, 1=GPS, 2=DGPS
  uint8_t satellites;        // 6 bits: number of satellites (0-63)
  int32_t latitude;          // Latitude in degrees * 10,000,000
  int32_t longitude;         // Longitude in degrees * 10,000,000
  int altitude;              // Encoded altitude
  int speed;                 // Encoded speed
  int bearing;               // Bearing in degrees * 100
  uint8_t hdop;              // Horizontal dilution of precision * 10
  uint8_t vdop;              // Vertical dilution of precision * 10
};
```

## 12. 联系方式

如有任何问题或建议，请联系开发团队。