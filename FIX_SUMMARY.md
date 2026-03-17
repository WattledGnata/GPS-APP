# GPS蓝牙数据传输修复总结

## 修复时间
2026-03-18 凌晨

## 问题描述

### 核心问题
1. **数据格式错误**: 模拟器发送20字节，协议要求28字节
2. **字节序错误**: 使用小端序，协议要求大端序
3. **字节偏移错误**: 所有字段位置错误
4. **卫星数显示异常**: vivo显示60-0循环（应为12）

### 用户反馈
- "显示连接了。但是两边的卫星数量数对不上"
- "8 0.0"
- "现在vivo的卫星数在60-0一直降低但是循环"
- "数据发送模拟这边，卫星数频率这些，都可以实时调整"

## 修复方案

### 1. 模拟器数据生成��� (GpsDataGenerator.kt)

**修改前:**
```kotlin
fun generateGpsMainData(): ByteArray {
    val data = ByteArray(20)  // ❌ 20字节
    // 小端序
    // 错误的字节偏移
}
```

**修改后:**
```kotlin
fun generateGpsMainData(): ByteArray {
    val data = ByteArray(28)  // ✓ 28字节

    // Byte 0: 同步位
    data[0] = (syncCounter and 0x07).toByte()

    // Byte 1-4: 时间 (big endian)
    data[1] = ((timeMs shr 24) and 0xFF).toByte()
    // ...

    // Byte 5: 定位质量(高2位) + 卫星数(低6位)
    val fixAndSat = ((fixQuality shl 6) or (satellites and 0x3F))
    data[5] = fixAndSat.toByte()

    // Byte 6-9: 纬度 (big endian, 度 * 10,000,000)
    // ...
}
```

### 2. 接收端解析器 (RaceChronoParser.kt)

**修改前:**
```kotlin
if (data.size < 20) {  // ❌ 期望20字节
    Log.e(TAG, "Invalid size: ${data.size}, expected 20")
}

// 错误的字节偏移
val satellites = data[3].toInt() and 0x3F  // ❌ 错误位置
```

**修改后:**
```kotlin
if (data.size < 28) {  // ✓ 期望28字节
    Log.e(TAG, "Invalid size: ${data.size}, expected 28")
}

// 添加原始数据日志
val hexDump = data.joinToString("") { "%02X".format(it) }
Log.d(TAG, "Raw GPS Data (28 bytes): $hexDump")

// 正确的字节偏移
val satellites = data[5].toInt() and 0x3F  // ✓ Byte 5
```

### 3. 蓝牙连接日志 (BleConnection.kt)

**新增功能:**
```kotlin
private fun logReceivedData(uuid: UUID, data: ByteArray) {
    val hexDump = data.joinToString("") { "%02X".format(it) }
    when (uuid) {
        GPS_MAIN_UUID -> {
            Log.d(TAG, "Received GPS Main Data (${data.size} bytes): $hexDump")
        }
        // ...
    }
}
```

## 测试工具

### 自动化测试脚本

1. **deploy.sh** - 快速部署
   ```bash
   ./deploy.sh  # 自动安装APK到两台设备
   ```

2. **test_ble_data.sh** - 测试监控
   ```bash
   ./test_ble_data.sh  # 实时显示双方日志
   ```

3. **analyze_logs.sh** - 结果分析
   ```bash
   ./analyze_logs.sh  # 检查数据一致性
   ```

### 验证标准

✅ **成功标志:**
- 原始数据hex完全一致
- 卫星数显示12（不是60-0）
- 频率正常计算（不是0.0）
- 速度正常显示

❌ **失败标志:**
- 数据长度不是28字节
- hex数据不一致
- 卫星数循环或错误
- 频率/速度为0

## 技术细节

### RaceChrono协议格式 (28字节大端序)

```
偏移   大小  字段            说明
0      1    同步位           3位同步计数器 (0-7)
1      4    小时开始时间      int32, big endian, 毫秒
5      1    质量+卫星数       高2位: 定位质量, 低6位: 卫星数(0-63)
6      4    纬度             int32, big endian, 度 * 10,000,000
10     4    经度             int32, big endian, 度 * 10,000,000
14     4    海拔             int32, big endian, 米 * 100
18     4    速度             int32, big endian, km/h * 100
22     4    方位角           int32, big endian, 度 * 100
26     1    HDOP            0.1单位
27     1    VDOP            0.1单位
```

### 关键修复点

1. **卫星数提取**
   ```kotlin
   // 正确方式
   val fixQuality = (data[5].toInt() shr 6) and 0x03
   val satellites = data[5].toInt() and 0x3F
   ```

2. **大端序解析**
   ```kotlin
   // 4字节大端序
   val value = ((data[0].toInt() and 0xFF) shl 24) or
               ((data[1].toInt() and 0xFF) shl 16) or
               ((data[2].toInt() and 0xFF) shl 8) or
               (data[3].toInt() and 0xFF)
   ```

## 使用说明

### 明早测试步骤

1. **准备环境**
   ```bash
   # 连接两台手机到电脑
   adb devices

   # 部署应用
   ./deploy.sh
   ```

2. **开始测试**
   ```bash
   # 运行测试监控
   ./test_ble_data.sh

   # 按照提示操作:
   # 小米: 打开GPSSimulator → 开始广播
   # vivo: 打开GPS测试 → 扫描设备 → 连接
   ```

3. **检查结果**
   ```bash
   # 分析日志
   ./analyze_logs.sh

   # 应该看到:
   # ✓ 数据传输一致
   # ✓ 卫星数解析正确 (12)
   # ✓ 模拟器发送数据 (X 次)
   # ✓ 接收端接收数据 (X 次)
   ```

## 文件清单

### 核心修复
- `simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt`
- `app/src/main/java/com/race/gps/data/service/parser/RaceChronoParser.kt`
- `app/src/main/java/com/race/gps/bluetooth/BleConnection.kt`
- `simulator/src/main/java/com/race/gps/simulator/ui/SimulatorScreen.kt`

### 测试工具
- `deploy.sh` - 部署脚本
- `test_ble_data.sh` - 测试监控
- `analyze_logs.sh` - 日志分析

### 文档
- `TESTING_GUIDE.md` - 测试指南
- `docs/RaceChrono_BLE_Protocol.md` - 协议规范
- `docs/superpowers/plans/2026-03-18-racechrono-protocol-fix.md` - 修复计划

## Git提交记录

```bash
# 第一次提交: 核心修复
1cdbb1a fix: 修复GPS模拟器和接收端为28字节大端序RaceChrono协议

# 第二次提交: 测试工具
63bcf91 test: 添加蓝牙数据传输自动化测试脚本和文档
```

## 预期结果

明早测试时应该看到：

### 小米模拟器日志
```
GpsDataGenerator: Transmitting - Main: 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
GpsDataGenerator: Fields - Sync=0, Fix=1, Sats=12, Lat=60.1725897, Speed=15.0 km/h, Freq=10Hz
```

### vivo接收端日志
```
BleConnection: Received GPS Main Data (28 bytes): 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
RaceChronoParser: Parsed: Sync=0, Time=2868012, Fix=1, Sats=12, Lat=60.1725897, Lon=24.9376543, Alt=100.0m, Speed=15.0km/h, Bearing=45.0°, HDOP=1.0, VDOP=1.0
```

### vivo应用显示
- 卫星数: **12** ✅ (不再是60-0循环)
- 速度: **15.0 km/h** ✅
- 频率: **10.0 Hz** ✅ (不再是0.0)

## 备注

- 所有修改已提交到git
- 两台设备已连接ADB
- 可以直接运行 `./test_ble_data.sh` 开始测试
- 测试完成后运行 `./analyze_logs.sh` 查看分析结果

---

**祝测试顺利！有问题随时查看日志或TESTING_GUIDE.md文档。**
