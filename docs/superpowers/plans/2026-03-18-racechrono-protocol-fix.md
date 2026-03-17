# RaceChrono协议修复计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标:** 修复GPS模拟器和接收应用，使其完全符合RaceChrono BLE协议规范

**架构:**
- 模拟器：生成28字节大端序GPS数据并通过BLE广播
- 接收端：解析28字节大端序GPS数据并正确显示
- 双方添加详细日志以便调试

**技术栈:** Android BLE, Jetpack Compose, Kotlin

---

## 问题分析

### 当前状态
- ❌ 模拟器发送20字节小端序数据
- ❌ ���析器期望20字节（line 81），协议要求28字节
- ❌ 字节偏移错误导致卫星数读到错误位置
- ❌ 缺少原始数据日志

### 协议要求（28字节大端序）
```
Byte 0:    同步位 (3位)
Byte 1-4:  小时开始时间 (int32, big endian)
Byte 5:    定位质量(高2位) + 卫星数(低6位)
Byte 6-9:  纬度 (int32, big endian, 度 * 10,000,000)
Byte 10-13: 经度 (int32, big endian, 度 * 10,000,000)
Byte 14-17: 海拔 (int32, 米 * 100)
Byte 18-21: 速度 (int32, km/h * 100)
Byte 22-25: 方位角 (int32, 度 * 100)
Byte 26:   HDOP (0.1单位)
Byte 27:   VDOP (0.1单位)
```

---

## 任务列表

### Task 1: 修复模拟器数据生成器（28字节）

**Files:**
- Modify: `simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt`

- [ ] **Step 1: 修改generateGpsMainData返回28字节数组**

```kotlin
fun generateGpsMainData(): ByteArray {
    val data = ByteArray(28) // 改为28字节

    // Byte 0: 同步位 (低3位)
    data[0] = (syncCounter and 0x07).toByte()

    // Byte 1-4: 小时开始时间 (big endian)
    val timeMs = getTimeSinceHourStart()
    data[1] = ((timeMs shr 24) and 0xFF).toByte()
    data[2] = ((timeMs shr 16) and 0xFF).toByte()
    data[3] = ((timeMs shr 8) and 0xFF).toByte()
    data[4] = (timeMs and 0xFF).toByte()

    // Byte 5: 定位质量(高2位) + 卫星数(低6位)
    val fixAndSat = ((fixQuality shl 6) or (satellites and 0x3F))
    data[5] = fixAndSat.toByte()

    // Byte 6-9: 纬度 (big endian, 度 * 10,000,000)
    val latInt = (currentLatitude * 10000000.0).toInt()
    data[6] = ((latInt shr 24) and 0xFF).toByte()
    data[7] = ((latInt shr 16) and 0xFF).toByte()
    data[8] = ((latInt shr 8) and 0xFF).toByte()
    data[9] = (latInt and 0xFF).toByte()

    // Byte 10-13: 经度 (big endian, 度 * 10,000,000)
    val lonInt = (currentLongitude * 10000000.0).toInt()
    data[10] = ((lonInt shr 24) and 0xFF).toByte()
    data[11] = ((lonInt shr 16) and 0xFF).toByte()
    data[12] = ((lonInt shr 8) and 0xFF).toByte()
    data[13] = (lonInt and 0xFF).toByte()

    // Byte 14-17: 海拔 (big endian, 米 * 100)
    val altInt = (altitude * 100.0).toInt()
    data[14] = ((altInt shr 24) and 0xFF).toByte()
    data[15] = ((altInt shr 16) and 0xFF).toByte()
    data[16] = ((altInt shr 8) and 0xFF).toByte()
    data[17] = (altInt and 0xFF).toByte()

    // Byte 18-21: 速度 (big endian, km/h * 100)
    val speedInt = (currentSpeed * 100.0).toInt()
    data[18] = ((speedInt shr 24) and 0xFF).toByte()
    data[19] = ((speedInt shr 16) and 0xFF).toByte()
    data[20] = ((speedInt shr 8) and 0xFF).toByte()
    data[21] = (speedInt and 0xFF).toByte()

    // Byte 22-25: 方位角 (big endian, 度 * 100)
    val bearingInt = (bearing * 100.0).toInt()
    data[22] = ((bearingInt shr 24) and 0xFF).toByte()
    data[23] = ((bearingInt shr 16) and 0xFF).toByte()
    data[24] = ((bearingInt shr 8) and 0xFF).toByte()
    data[25] = (bearingInt and 0xFF).toByte()

    // Byte 26: HDOP (0.1单位)
    data[26] = ((hdop * 10.0).toInt().toByte())

    // Byte 27: VDOP (0.1单位)
    data[27] = ((vdop * 10.0).toInt().toByte())

    return data
}
```

- [ ] **Step 2: 添加同步计数器状态**

```kotlin
private var syncCounter = 0

private fun incrementSyncCounter() {
    syncCounter = (syncCounter + 1) and 0x07 // 0-7循环
}
```

- [ ] **Step 3: 在updateSimulation中更新同步计数器**

```kotlin
private fun updateSimulation() {
    incrementSyncCounter()
    // ... 其余逻辑
}
```

- [ ] **Step 4: 编译测试**

Run: `./gradlew :simulator:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt
git commit -m "fix: 修改GPS数据生成器为28字节大端序格式"
```

---

### Task 2: 修复接收端解析器（28字节）

**Files:**
- Modify: `app/src/main/java/com/race/gps/data/service/parser/RaceChronoParser.kt`

- [ ] **Step 1: 修改数据长度检查**

```kotlin
if (data.size < 28) {
    Log.e(TAG, "Invalid GPS main data size: ${data.size}, expected 28")
    return currentData
}
```

- [ ] **Step 2: 添加原始数据hex dump日志**

```kotlin
if (shouldLog) {
    val hexDump = data.joinToString("") { "%02X".format(it) }
    Log.d(TAG, "Raw GPS Data (28 bytes): $hexDump")
}
```

- [ ] **Step 3: 修改字节偏移量解析**

```kotlin
// Byte 0: 同步位 (低3位)
val syncBits = data[0].toInt() and 0x07

// Byte 1-4: 小时开始时间 (big endian)
val timeSinceHourStart = ((data[1].toInt() and 0xFF) shl 24) or
        ((data[2].toInt() and 0xFF) shl 16) or
        ((data[3].toInt() and 0xFF) shl 8) or
        (data[4].toInt() and 0xFF)

// Byte 5: 定位质量(高2位) + 卫星数(低6位)
val fixQuality = (data[5].toInt() shr 6) and 0x03
val satellites = data[5].toInt() and 0x3F

// Byte 6-9: 纬度 (big endian, 度 * 10,000,000)
val latInt = ((data[6].toInt() and 0xFF) shl 24) or
        ((data[7].toInt() and 0xFF) shl 16) or
        ((data[8].toInt() and 0xFF) shl 8) or
        (data[9].toInt() and 0xFF)
val currentLatitude = latInt / 10000000.0

// Byte 10-13: 经度 (big endian, 度 * 10,000,000)
val lonInt = ((data[10].toInt() and 0xFF) shl 24) or
        ((data[11].toInt() and 0xFF) shl 16) or
        ((data[12].toInt() and 0xFF) shl 8) or
        (data[13].toInt() and 0xFF)
val currentLongitude = lonInt / 10000000.0

// Byte 14-17: 海拔 (big endian, 米 * 100)
val altInt = ((data[14].toInt() and 0xFF) shl 24) or
        ((data[15].toInt() and 0xFF) shl 16) or
        ((data[16].toInt() and 0xFF) shl 8) or
        (data[17].toInt() and 0xFF)
val altitudeMeters = altInt / 100.0

// Byte 18-21: 速度 (big endian, km/h * 100)
val speedInt = ((data[18].toInt() and 0xFF) shl 24) or
        ((data[19].toInt() and 0xFF) shl 16) or
        ((data[20].toInt() and 0xFF) shl 8) or
        (data[21].toInt() and 0xFF)
val speedKmh = speedInt / 100.0

// Byte 22-25: 方位角 (big endian, 度 * 100)
val bearingInt = ((data[22].toInt() and 0xFF) shl 24) or
        ((data[23].toInt() and 0xFF) shl 16) or
        ((data[24].toInt() and 0xFF) shl 8) or
        (data[25].toInt() and 0xFF)
val bearingDegrees = bearingInt / 100.0

// Byte 26: HDOP (0.1单位)
val hdop = (data[26].toInt() and 0xFF) / 10.0

// Byte 27: VDOP (0.1单位)
val vdop = (data[27].toInt() and 0xFF) / 10.0
```

- [ ] **Step 4: 更新日志输出**

```kotlin
if (shouldLog) {
    Log.d(TAG, "Parsed: Sync=$syncBits, Time=$timeSinceHourStart, " +
            "Fix=$fixQuality, Sats=$satellites, " +
            "Lat=${"%.7f".format(currentLatitude)}, " +
            "Lon=${"%.7f".format(currentLongitude)}, " +
            "Alt=${"%.1f".format(altitudeMeters)}m, " +
            "Speed=${"%.1f".format(speedKmh)}km/h, " +
            "Bearing=${"%.1f".format(bearingDegrees)}°, " +
            "HDOP=$hdop, VDOP=$vdop")
}
```

- [ ] **Step 5: 编译测试**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/race/gps/data/service/parser/RaceChronoParser.kt
git commit -m "fix: 修改解析器支持28字节大端序RaceChrono协议"
```

---

### Task 3: 模拟器添加发送数据日志

**Files:**
- Modify: `simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt`
- Modify: `simulator/src/main/java/com/race/gps/simulator/ble/GpsPeripheralManager.kt`

- [ ] **Step 1: 在GpsDataGenerator中添加日志方法**

```kotlin
private fun logTransmittedData(mainData: ByteArray, timeData: ByteArray) {
    val mainHex = mainData.joinToString("") { "%02X".format(it) }
    val timeHex = timeData.joinToString("") { "%02X".format(it) }
    Log.d("GpsDataGenerator", "Transmitting - Main: $mainHex, Time: $timeHex")

    // 解析关键字段用于日志
    val sync = mainData[0].toInt() and 0x07
    val fixAndSat = mainData[5].toInt() and 0xFF
    val fixQuality = (fixAndSat shr 6) and 0x03
    val satellites = fixAndSat and 0x3F

    val latInt = ((mainData[6].toInt() and 0xFF) shl 24) or
                 ((mainData[7].toInt() and 0xFF) shl 16) or
                 ((mainData[8].toInt() and 0xFF) shl 8) or
                 (mainData[9].toInt() and 0xFF)
    val lat = latInt / 10000000.0

    val speedInt = ((mainData[18].toInt() and 0xFF) shl 24) or
                   ((mainData[19].toInt() and 0xFF) shl 16) or
                   ((mainData[20].toInt() and 0xFF) shl 8) or
                   (mainData[21].toInt() and 0xFF)
    val speed = speedInt / 100.0

    Log.d("GpsDataGenerator", "Fields - Sync=$sync, Fix=$fixQuality, Sats=$satellites, Lat=$lat, Speed=$speed km/h")
}
```

- [ ] **Step 2: 在startGpsDataStream中调用日志**

```kotlin
fun startGpsDataStream(): Flow<Pair<ByteArray, ByteArray>> = flow {
    while (true) {
        updateSimulation()
        val mainData = generateGpsMainData()
        val timeData = generateGpsTimeData()

        // 添加日志
        logTransmittedData(mainData, timeData)

        emit(Pair(mainData, timeData))
        delay((1000L / frequency).toLong())
    }
}
```

- [ ] **Step 3: 编译测试**

Run: `./gradlew :simulator:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add simulator/src/main/java/com/race/gps/simulator/data/GpsDataGenerator.kt
git commit -m "feat: 添加模拟器发送数据详细日志"
```

---

### Task 4: 接收端添加原始数据日志

**Files:**
- Modify: `app/src/main/java/com/race/gps/bluetooth/BleConnection.kt`

- [ ] **Step 1: 在onCharacteristicChanged中添加原始数据日志**

```kotlin
override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic
) {
    val data = characteristic.value

    // 添加原始数据日志
    when (characteristic.uuid) {
        GPS_MAIN_DATA_UUID -> {
            val hexDump = data.joinToString("") { "%02X".format(it) }
            Log.d("BleConnection", "Received GPS Main Data (${data.size} bytes): $hexDump")
        }
        GPS_TIME_DATA_UUID -> {
            val hexDump = data.joinToString("") { "%02X".format(it) }
            Log.d("BleConnection", "Received GPS Time Data (${data.size} bytes): $hexDump")
        }
    }

    // 继续原有处理逻辑
    // ...
}
```

- [ ] **Step 2: 编译测试**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/race/gps/bluetooth/BleConnection.kt
git commit -m "feat: 添加接收端原始数据详细日志"
```

---

### Task 5: 模拟器UI添加实时参数显示

**Files:**
- Modify: `simulator/src/main/java/com/race/gps/simulator/ui/SimulatorScreen.kt`
- Modify: `simulator/src/main/java/com/race/gps/simulator/viewmodel/SimulatorViewModel.kt`

- [ ] **Step 1: 在SimulatorUiState中添加字段**

```kotlin
data class SimulatorUiState(
    // ... 现有字段
    val showRawData: Boolean = false,
    val lastTransmittedHex: String = ""
)
```

- [ ] **Step 2: 在ViewModel中添加更新方法**

```kotlin
fun updateTransmittedData(hex: String) {
    _uiState.value = _uiState.value.copy(lastTransmittedHex = hex)
}

fun toggleRawDataDisplay() {
    _uiState.value = _uiState.value.copy(showRawData = !_uiState.value.showRawData)
}
```

- [ ] **Step 3: 在UI中添加原始数据显示Card**

```kotlin
@Composable
fun RawDataCard(
    showRawData: Boolean,
    hexData: String,
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "原始数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(onClick = onToggle) {
                    Text(if (showRawData) "隐藏" else "显示")
                }
            }

            if (showRawData && hexData.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hexData,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
```

- [ ] **Step 4: 在SimulatorScreen中添加Card**

```kotlin
// 原始数据显示
RawDataCard(
    showRawData = uiState.showRawData,
    hexData = uiState.lastTransmittedHex,
    onToggle = { viewModel.toggleRawDataDisplay() }
)
```

- [ ] **Step 5: 编译测试**

Run: `./gradlew :simulator:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add simulator/src/main/java/com/race/gps/simulator/ui/SimulatorScreen.kt
git add simulator/src/main/java/com/race/gps/simulator/viewmodel/SimulatorViewModel.kt
git commit -m "feat: 模拟器UI添加原始数据显示"
```

---

### Task 6: 编译并部署到两台设备

- [ ] **Step 1: 编译模拟器APK**

Run: `./gradlew :simulator:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 编译接收端APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 安装到小米手机**

```bash
adb -s <小米设备ID> install -r simulator/build/outputs/apk/debug/simulator-debug.apk
adb -s <小米设备ID> install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: 安装到vivo手机**

```bash
adb -s <vivo设备ID> install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: 清除logcat缓冲**

```bash
adb -s <小米设备ID> logcat -c
adb -s <vivo设备ID> logcat -c
```

---

### Task 7: 启动日志监控和测试

- [ ] **Step 1: 启动小米logcat监控**

```bash
adb -s <小米设备ID> logcat -s GpsDataGenerator:D GattServerManager:D BleConnection:D RaceChronoParser:D
```

- [ ] **Step 2: 启动vivo logcat监控**

```bash
adb -s <vivo设备ID> logcat -s BleConnection:D RaceChronoParser:D
```

- [ ] **Step 3: 小米手机启动模拟器，开始广播**

- [ ] **Step 4: vivo手机扫描并连接设备**

- [ ] **Step 5: 检查日志输出对比**

预期结果：
- 小米日志: `Transmitting - Main: [28字节hex], Time: [3字节hex]`
- vivo日志: `Received GPS Main Data (28 bytes): [28字节hex]`
- 两个hex字符串应该完全相同

- [ ] **Step 6: 检查vivo解析结果**

预期结果：
- 日志显示: `Parsed: Sync=X, Fix=1, Sats=12, Lat=60.1725XXX, Speed=XX.Xkm/h`
- 卫星数应为12（不是60-0循环）
- 频率应正常显示（不是0.0）

---

## 验证标准

✅ **成功标准:**
1. 模拟器生成28字节数据
2. 接收端接收28字节数据
3. 原始数据hex完全一致
4. 卫星数正确显示（12）
5. 速度正确显示
6. 频率正确计算（不为0.0）
7. 日志完整记录所有字段

❌ **失败标准:**
1. 数据长度不匹配
2. hex数据不一致
3. 卫星数循环或错误
4. 速度/频率显示为0
5. 日志显示解析错误

---

## 故障排查指南

### 如果hex数据一致但解析错误
→ 检查字节偏移量是否正确

### 如果卫星数仍然循环
→ 检查Byte 5的位操作是否正确

### 如果速度为0
→ 检查Byte 18-21的大端序解析

### 如果频率为0
→ 检查时间戳记录和频率计算逻辑

---

## 执行说明

此计划包含7个任务，按照以下顺序执行：
1. 修复模拟器数据格式（Task 1）
2. 修复接收端解析器（Task 2）
3. 添加模拟器日志（Task 3）
4. 添加接收端日志（Task 4）
5. 添加UI显示（Task 5）
6. 编译部署（Task 6）
7. 测试验证（Task 7）

每个任务完成后进行git提交，确保可以回滚。
