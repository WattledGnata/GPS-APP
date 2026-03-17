# GPS蓝牙数据传输测试指南

## 问题修复���明

### 已修复的问题

1. **数据格式错误** - 模拟器从20字节改为28字节（符合RaceChrono协议）
2. **字节序错误** - 从小端序改为大端序（Big Endian）
3. **字节偏移错误** - 修正所有字段的字节位置
4. **卫星数显示错误** - 修复Byte 5的位操作（高2位定位质量+低6位卫星数）
5. **缺少调试日志** - 添加详细的hex dump和字段解析日志

### 协议格式（28字节大端序）

```
Byte 0:    同步位 (3位)
Byte 1-4:  小时开始时间 (int32, big endian)
Byte 5:    定位质量(高2位) + 卫星数(低6位)
Byte 6-9:  纬度 (int32, big endian, 度 * 10,000,000)
Byte 10-13: 经度 (int32, big endian, 度 * 10,000,000)
Byte 14-17: 海�� (int32, big endian, 米 * 100)
Byte 18-21: 速度 (int32, big endian, km/h * 100)
Byte 22-25: 方位角 (int32, big endian, 度 * 100)
Byte 26:   HDOP (0.1单位)
Byte 27:   VDOP (0.1单位)
```

## 测试步骤

### 方法1: 自动化测试（推荐）

```bash
# 1. 编译APK（如果还未编译）
./gradlew :simulator:assembleDebug
./gradlew :app:assembleDebug

# 2. 连接两台手机到电脑
# 确保USB调试已开启

# 3. 部署应用到设备
./deploy.sh

# 4. 运行测试和日志监控
./test_ble_data.sh
```

测试脚本会：
- 自动识别小米和vivo设备
- 安装对应的应用
- 启动日志监控
- 实时显示最新的日志输出

### 方法2: 手动测试

#### 小米手机（模拟器）

1. 打开 `GPSSimulator` 应用
2. 授予蓝牙和位置权限
3. 点击 **开始广播** 按钮
4. 确认显示 **广播中**

#### vivo手机（接收端）

1. 打开 `GPS测试` 应用
2. 授予蓝牙和位置权限
3. 点击 **扫描设备** 按钮
4. 找到小米设备并连接
5. 观察数据是否正常显示：
   - 卫星数: 12
   - 速度: 根据模拟器设置
   - 频率: 应该正常显示（不是0.0）

### 方法3: 查看日志

```bash
# 终端1: 监控小米模拟器日志
adb -s <小米设备ID> logcat -s GpsDataGenerator:D GattServerManager:D

# 终端2: 监控vivo接收端日志
adb -s <vivo设备ID> logcat -s BleConnection:D RaceChronoParser:D
```

## 验证标准

### ✅ 成功标志

1. **原始数据一致**
   - 小米发送的hex数据 = vivo接收的hex数据
   - 数据长度: 28字节（56个hex字符）

2. **字段解析正确**
   - 卫星数: 12（不是60-0循环）
   - 速度: 正常显示（根据模拟器设置）
   - 频率: 正常计算（不是0.0）
   - 纬度/经度: 合理数值

3. **日志输出示例**

**小米模拟器日志:**
```
GpsDataGenerator: Transmitting - Main: 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
GpsDataGenerator: Fields - Sync=0, Fix=1, Sats=12, Lat=60.1725897, Speed=15.0 km/h, Freq=10Hz
```

**vivo接收端日志:**
```
BleConnection: Received GPS Main Data (28 bytes): 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
RaceChronoParser: Parsed: Sync=0, Time=2868012, Fix=1, Sats=12, Lat=60.1725897, Lon=24.9376543, Alt=100.0m, Speed=15.0km/h, Bearing=45.0°, HDOP=1.0, VDOP=1.0
```

### ❌ 失败标志

1. **数据长度不匹配**
   - 收到的不是28字节
   - hex字符数不是56

2. **hex数据不一致**
   - 小米发送 ≠ vivo接收

3. **卫星数错误**
   - 显示为60-0循环
   - 显示为8或其他错误值

4. **速度/频率为0**
   - 速度显示0.0 km/h
   - 频率显示0.0 Hz

## 日志分析

测试完成后，运行日志分析脚本：

```bash
./analyze_logs.sh
```

该脚本会自动检查：
- 数据长度是否正确
- 原始数据是否一致
- 卫星数是否正确
- 统计发送/接收次数

## 故障排查

### 问题1: vivo显示卫星数60-0循环

**原因:** 字节偏移错误，读到了错误的位置

**检查:**
```bash
# 查看原始数据
grep "Raw GPS Data" vivo_receiver.log | tail -n 1
```

**解决:** 已在本次修复中解决

### 问题2: 频率显示0.0

**原因:** 可能是数据未正确接收或解析失败

**检查:**
```bash
# 查看接收次数
grep -c "Received GPS Main Data" vivo_receiver.log
```

**解决:** 确认连接正常，检查日志中的hex数据

### 问题3: 数据长度错误

**原因:** 模拟器或解析器使用了错误的数据长度

**检查:**
```bash
# 小米发送的数据长度
grep "Transmitting - Main:" xiaomi_simulator.log | tail -n 1 | wc -c
```

**解决:** 确保两边的代码都是28字节格式

## 文件说明

### 核心修复文件

- `simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt`
  - 28字节大端序数据生成
  - 添加详细日志

- `app/src/main/java/com/race/gps/data/service/parser/RaceChronoParser.kt`
  - 28字节大端序数据解析
  - 修正字节偏移

- `app/src/main/java/com/race/gps/bluetooth/BleConnection.kt`
  - 添加原始数据hex日志

### 测试脚本

- `deploy.sh` - 快速部署APK到两台设备
- `test_ble_data.sh` - 自动化测试和日志监控
- `analyze_logs.sh` - 日志分析脚本

### 相关文档

- `docs/RaceChrono_BLE_Protocol.md` - 完整协议规范
- `docs/superpowers/plans/2026-03-18-racechrono-protocol-fix.md` - 修复计划

## 下一步

测试通过后，可以考虑：

1. **添加更多测试场景**
   - 加速场景
   - 刹车场景
   - 不同速度范围

2. **性能优化**
   - 降低传输延迟
   - 优化频率计算

3. **UI改进**
   - 添加原始数据显示界面
   - 实时参数调整

## 联系方式

如有问题，请检查：
1. 两台设备的日志文件
2. 原始hex数据是否一致
3. 字节偏移是否正确
