# GPS蓝牙数据传输测试检查清单

## 测试前准备 ✅

- [ ] 两台手机已连接到电脑
  ```bash
  adb devices
  ```
  应该看到至少2台设备

- [ ] 应用已编译
  ```bash
  ./gradlew :simulator:assembleDebug
  ./gradlew :app:assembleDebug
  ```

- [ ] 应用已部署
  ```bash
  ./deploy.sh
  ```

## 测试步骤 ✅

### 小米手机（模拟器）

- [ ] 打开 "GPSSimulator" 应用
- [ ] 授予蓝牙权限
- [ ] 授予位置权限
- [ ] 点击 "开始广播" 按���
- [ ] 确认显示 "广播中"

### vivo手机（接收端）

- [ ] 打开 "GPS测试" 应用
- [ ] 授予蓝牙权限
- [ ] 授予位置权限
- [ ] 点击 "扫描设备" 按钮
- [ ] 在列表中找到小米设备
- [ ] 点击连接
- [ ] 确认连接成功

## 验证检查点 ✅

### 数据传输验证

- [ ] 运行日志分析
  ```bash
  ./analyze_logs.sh
  ```

- [ ] 检查数据长度
  - 模拟器发送: 28字节 ��
  - 接收端接收: 28字节 ✅

- [ ] 检查原始数据一致性
  - 小米hex = vivohex ✅

- [ ] 检查数据传输次数
  - 发送次数 > 0 ✅
  - 接收次数 > 0 ✅

### 字段解析验证

- [ ] 卫星数显示
  - 预期: 12
  - 实际: ___
  - 状态: ✅ / ❌

- [ ] 速度显示
  - 预期: 根据模拟器设置（默认15.0 km/h）
  - 实际: ___
  - 状态: ✅ / ❌

- [ ] 频率显示
  - 预期: 10.0 Hz（根据设置）
  - 实际: ___
  - 状态: ✅ / ❌

- [ ] 纬度显示
  - 预期: 60.1725XXX
  - 实际: ___
  - 状态: ✅ / ❌

- [ ] 经度显示
  - 预期: 24.9375XXX
  - 实际: ___
  - 状态: ✅ / ❌

## 日志验证 ✅

### 小米模拟器日志

应该看到类似输出：
```
GpsDataGenerator: Transmitting - Main: 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
GpsDataGenerator: Fields - Sync=0, Fix=1, Sats=12, Lat=60.1725897, Speed=15.0 km/h, Freq=10Hz
```

检查：
- [ ] 数据长度为28字节（56个hex字符）
- [ ] 卫星数为12
- [ ] 速度不为0
- [ ] 频率不为0

### vivo接收端日志

应该看到类似输出：
```
BleConnection: Received GPS Main Data (28 bytes): 002B4C12000C38EA7A600038E9768400000000F42400000005DC0000000C0A0000000A
RaceChronoParser: Parsed: Sync=0, Time=2868012, Fix=1, Sats=12, Lat=60.1725897, Lon=24.9376543, Alt=100.0m, Speed=15.0km/h, Bearing=45.0°, HDOP=1.0, VDOP=1.0
```

检查：
- [ ] 接收到28字节数据
- [ ] 原始hex与模拟器一致
- [ ] 卫星数解析为12
- [ ] 速度解析正确
- [ ] 所有字段值合理

## 故障排查 ❌

### 如果卫星数仍然显示60-0循环

可能原因：
- [ ] 代码未正确更新
- [ ] APK未重新安装
- [ ] 字节偏移仍有错误

解决方法：
```bash
# 1. 确认代码版本
git log --oneline -n 3

# 2. 重新编译
./gradlew clean
./gradlew :simulator:assembleDebug :app:assembleDebug

# 3. 重新部署
./deploy.sh

# 4. 清除应用数据
adb -s <设备ID> shell pm clear com.race.gps.simulator
adb -s <设备ID> shell pm clear com.race.gps
```

### 如果频率仍显示0.0

可能原因：
- [ ] 数据未正确接收
- [ ] 频率计算逻辑错误
- [ ] 时间戳记录问题

检查方法：
```bash
# 查看接收次数
grep -c "Received GPS Main Data" vivo_receiver.log

# 查看解析日志
grep "Parsed:" vivo_receiver.log | tail -n 5
```

### 如果hex数据不一致

可能原因：
- [ ] 传输过程中数据损坏
- [ ] BLE通知未正确启用
- [ ] 特征值写入失败

检查方法：
```bash
# 对比双方最新数据
echo "小米:"
grep "Transmitting - Main:" xiaomi_simulator.log | tail -n 1
echo ""
echo "vivo:"
grep "Received GPS Main Data" vivo_receiver.log | tail -n 1
```

## 测试完成 ✅

### 成功标准

全部满足以下条件即为测试通过：

- [x] 数据长度正确（28字节）
- [x] 原始数据一致
- [x] 卫星数正确（12）
- [x] 速度正常显示
- [x] 频率正常计算
- [x] 日志输出完整

### 测试通过后

1. **保存日志**
   ```bash
   mkdir -p test_results
   cp xiaomi_simulator.log test_results/
   cp vivo_receiver.log test_results/
   ```

2. **记录测试结果**
   - 测试日期: __________
   - 测试人员: __________
   - 测试结果: ✅ 通过 / ❌ 失败
   - 备注: ___________________________________

3. **下一步**
   - 如果通过: 可以进行更多场景测试
   - 如果失败: 查看故障排查章节

## 快速命令参考

```bash
# 部署
./deploy.sh

# 测试监控
./test_ble_data.sh

# 日志分析
./analyze_logs.sh

# 查看设备
adb devices -l

# 查看小米日志（实时）
adb -s <小米ID> logcat -s GpsDataGenerator:D

# 查看vivo日志（实时）
adb -s <vivoID> logcat -s RaceChronoParser:D

# 清除logcat
adb -s <设备ID> logcat -c

# 重启应用
adb -s <设备ID> shell am force-stop <包名>
adb -s <设备ID> shell monkey -p <包名> 1
```

## 联系与支持

如有问题：
1. 查看 `TESTING_GUIDE.md` 详细文档
2. 查看 `FIX_SUMMARY.md` 修复说明
3. 检查日志文件中的错误信息
4. 确认两台设备的hex数据是否一致

---

**最后更新**: 2026-03-18
**版本**: 1.0
