# 蓝牙外接 GPS 电量显示 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 BLE GATT 层发现 Battery Service（`0x180F`/`0x2A19`），通过独立 `StateFlow<Int?>` 通道传递电量百分比到 UI，在 Device 首页 ConnectedDeviceCard 中展示电池图标 + Mechanical 百分比数字。

**Architecture:** 新增数据通道与 GPS 数据流平行：`BleConnection._batteryPercent` → `BluetoothDataSource.batteryPercent` → `GpsDataRepository.batteryPercent` → `GpsDataViewModel.batteryPercent` → `DeviceHomeScreen.BatteryIndicator`。Battery Service 发现逻辑在 GPS 通知启用完成后触发，不插入现有 GATT 操作序列。

**Tech Stack:** Kotlin coroutines + StateFlow, Android BLE GATT, Jetpack Compose + Material Icons (Extended), Track Tech V2 组件库 (MetricNumber/CutCornerPanel)

## Global Constraints

- **NO** `GpsData` 修改 — 电量不是 GPS 定位数据，独立通道
- **NO** Room schema migration — 不持久化电量
- **NO** 公共协议修改
- `parseBatteryPercent` 必须返回 `Int?`（null = 无电量/非法值，不是 0%）
- 电池图标 + Mechanical 数字，字体规则遵循 CLAUDE.md "UI 视觉约束" 节
- 所有新增 .kt 文件首行加 `// @IgnoreFormatCheck`
- Compose 屏内 `maxLines = 1, overflow = TextOverflow.Ellipsis` 对所有新增 Text

---

### Task 1: BleConnection — 集成 Battery Service GATT 发现与数据读取

**Files:**
- Modify: `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`

**Interfaces:**
- Produces: `BleConnection.batteryPercent: StateFlow<Int?>` — 电量百分比（null = 无电量能力/未读到）
- Produces: `BleConnection.parseBatteryPercent(value: ByteArray?): Int?` — companion 纯函数，供单测

- [ ] **Step 1: 在 companion object 中新增 Battery Service UUID 常量**

在 `BleConnection.kt` 的 companion object 中，`CCCD_UUID` 之后、`CONNECTION_TIMEOUT_MS` 之前，插入：

```kotlin
@Suppress("PropertyName")
private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")

@Suppress("PropertyName")
private val BATTERY_LEVEL_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
```

- [ ] **Step 2: 新增 `_batteryPercent` StateFlow 字段**

在 `_dataStale` 字段之后插入：

```kotlin
// 外接 GPS 设备电量百分比（null = 无此服务 / 未读到 / 非法值）。
// 连接后由 Battery Service (0x180F) 的 Battery Level (0x2A19) 特征提供。
@Suppress("PropertyName")
private val _batteryPercent = MutableStateFlow<Int?>(null)
val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()
```

- [ ] **Step 3: 新增 `parseBatteryPercent` companion 纯函数**

在 companion object 末尾（`DATA_TIMEOUT_MS` 之后、`}` 之前）插入：

```kotlin
/**
 * 解析 BLE Battery Level (0x2A19) 特征值。
 * @return 0..100 的百分比，非法值或空数据返回 null。
 */
fun parseBatteryPercent(value: ByteArray?): Int? {
    val percent = value?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
    return percent.takeIf { it in 0..100 }
}
```

- [ ] **Step 4: 新增 `setupBattery` 私有方法**

在 `processNextDescriptor` 方法之后插入：

```kotlin
/**
 * 发现并配置 Battery Service (0x180F)。在 GPS 通知启用完毕后调用，
 * 避免与 GPS CCCD 写操作产生 GATT 并发冲突。
 * 优先订阅 Notify/Indicate；仅支持 READ 时主动读一次。
 */
private fun setupBattery(gatt: BluetoothGatt) {
    val service = gatt.getService(BATTERY_SERVICE_UUID)
    if (service == null) {
        Log.d(TAG, "Battery Service (0x180F) not found — device has no battery reporting")
        // 服务不存在 = 确认无电量能力，保持 null（与初始值一致，不重复设）
        return
    }
    val characteristic = service.getCharacteristic(BATTERY_LEVEL_UUID)
    if (characteristic == null) {
        Log.d(TAG, "Battery Level (0x2A19) not found")
        return
    }

    val supportsNotify =
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val supportsIndicate =
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

    if (supportsNotify || supportsIndicate) {
        val enabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!enabled) {
            Log.e(TAG, "Failed to enable Battery Level notification")
            gatt.readCharacteristic(characteristic)
            return
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "CCCD not found for Battery Level — fallback to read")
            gatt.readCharacteristic(characteristic)
            return
        }
        cccd.value = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val writeSuccess = gatt.writeDescriptor(cccd)
        if (!writeSuccess) {
            Log.e(TAG, "Failed to write Battery CCCD — fallback to read")
            gatt.readCharacteristic(characteristic)
        }
    } else {
        Log.d(TAG, "Battery Level: no notify/indicate — read once")
        gatt.readCharacteristic(characteristic)
    }
}
```

- [ ] **Step 5: 在 `onDescriptorWrite` 中追加 Battery Service 触发 + CCCD 读回**

在 `onDescriptorWrite` 方法体内，`isWritingDescriptor = false` 之后、`processNextDescriptor(gatt)` 之前，插入：

```kotlin
// Battery Level CCCD 写完毕 → 读取当前电量
if (status == BluetoothGatt.GATT_SUCCESS &&
    descriptor.characteristic?.uuid == BATTERY_LEVEL_UUID
) {
    descriptor.characteristic?.let { gatt.readCharacteristic(it) }
}
```

在 `processNextDescriptor(gatt)` 调用之后、`if (pendingCharacteristics.isEmpty() ...)` 判定之前，插入：

```kotlin
// GPS 通知全部启用完成 → 追加 Battery Service 发现
if (pendingCharacteristics.isEmpty() && !isWritingDescriptor &&
    _connectionState.value != ConnectionState.CONNECTED
) {
    setupBattery(gatt)
}
```

- [ ] **Step 6: 在 gattCallback 中新增 `onCharacteristicRead` 重写**

在 `onCharacteristicChanged` 的 deprecated 重载之后、`handleCharacteristicChange` 之前，插入：

```kotlin
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    status: Int
) {
    if (status == BluetoothGatt.GATT_SUCCESS &&
        characteristic.uuid == BATTERY_LEVEL_UUID
    ) {
        val percent = parseBatteryPercent(value)
        if (percent != null) {
            _batteryPercent.value = percent
            Log.d(TAG, "Battery level read: $percent%")
        }
    }
}

@Deprecated("Deprecated in API 33")
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int
) {
    if (status == BluetoothGatt.GATT_SUCCESS &&
        characteristic.uuid == BATTERY_LEVEL_UUID
    ) {
        val percent = parseBatteryPercent(characteristic.value)
        if (percent != null) {
            _batteryPercent.value = percent
            Log.d(TAG, "Battery level read (deprecated): $percent%")
        }
    }
}
```

- [ ] **Step 7: 在 `onCharacteristicChanged` 中处理 Battery Level 通知**

在 `handleCharacteristicChange` 方法体内，`logReceivedData` 调用之前，插入：

```kotlin
// Battery Level 通知：独立处理，不进入 RaceChronoParser 数据流
if (characteristic.uuid == BATTERY_LEVEL_UUID) {
    val percent = parseBatteryPercent(value)
    if (percent != null) {
        _batteryPercent.value = percent
    }
    return
}
```

- [ ] **Step 8: 在 `cleanup` 方法中清空电量状态**

在 `cleanup` 方法末尾（`lastDataTime = 0L` 之后），插入：

```kotlin
_batteryPercent.value = null
```

- [ ] **Step 9: 编译验证**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :core:bluetooth:compileDebugKotlin --offline 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt
git commit -m "feat(ble): integrate Battery Service (0x180F) GATT discovery and level reading

Add batteryPercent StateFlow<Int?> to BleConnection — discovers BLE standard
Battery Service on servicesDiscovered, subscribes to notify/indicate, and
parses 0x2A19 Battery Level characteristic (0-100% or null).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: BluetoothDataSource — 代理 batteryPercent StateFlow

**Files:**
- Modify: `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt`

**Interfaces:**
- Consumes: `BleConnection.batteryPercent: StateFlow<Int?>`
- Produces: `BluetoothDataSource.batteryPercent: StateFlow<Int?>`

- [ ] **Step 1: 新增 `_batteryPercent` 字段**

在 `BluetoothDataSource.kt` 中 `_connectionState` 字段之后插入：

```kotlin
// 外接 GPS 设备电量百分比（null = 无此服务 / 未读到）
@Suppress("PropertyName")
private val _batteryPercent = MutableStateFlow<Int?>(null)
val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()
```

- [ ] **Step 2: 在 `doConnect` 中收集 BleConnection 的 batteryPercent**

在 `doConnect` 方法内，`bleConnection?.dataStale?.collect` 的 `launch` 块之后（该 launch 块的 `}` 之后），插入：

```kotlin
launch {
    bleConnection?.batteryPercent?.collect { pct ->
        _batteryPercent.value = pct
    }
}
```

- [ ] **Step 3: 在 `disconnect` 中清空电量**

在 `disconnect` 方法内，`_dataFlow.value = ...` 之后插入：

```kotlin
_batteryPercent.value = null
```

- [ ] **Step 4: 编译验证**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :core:bluetooth:compileDebugKotlin --offline 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt
git commit -m "feat(ble): proxy batteryPercent StateFlow through BluetoothDataSource

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: GpsDataRepository + GpsDataViewModel — 暴露电量到 UI 层

**Files:**
- Modify: `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/GpsDataRepository.kt`
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`

**Interfaces:**
- Consumes: `BluetoothDataSource.batteryPercent: StateFlow<Int?>`
- Produces: `GpsDataRepository.batteryPercent: StateFlow<Int?>`
- Produces: `GpsDataViewModel.batteryPercent: StateFlow<Int?>`

- [ ] **Step 1: GpsDataRepository — 暴露 batteryPercent**

在 `GpsDataRepository.kt` 中，`connectionState` 属性之后插入：

```kotlin
val batteryPercent: StateFlow<Int?> = bluetoothDataSource.batteryPercent
```

- [ ] **Step 2: GpsDataViewModel — 暴露 batteryPercent**

在 `GpsDataViewModel.kt` 中，`savedDevices` 属性之后插入：

```kotlin
// 外接 GPS 设备电量百分比（null = 无电量能力 / 未读到）
val batteryPercent: StateFlow<Int?> = gpsDataRepository.batteryPercent
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null,
    )
```

- [ ] **Step 3: 编译验证**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :core:bluetooth:compileDebugKotlin :feature:test:compileDebugKotlin --offline 2>&1 | tail -5
```

预期：BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add core/bluetooth/src/main/java/com/blazepush/core/bluetooth/GpsDataRepository.kt \
        feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt
git commit -m "feat(ble): expose batteryPercent through repository and ViewModel

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: DeviceHomeScreen — BatteryIndicator UI 组件

**Files:**
- Modify: `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt`
- May modify: `feature/test/build.gradle.kts`（如 `material-icons-extended` 未依赖）

**Interfaces:**
- Consumes: `GpsDataViewModel.batteryPercent: StateFlow<Int?>`

- [ ] **Step 1: 检查 material-icons-extended 依赖**

```bash
grep "material-icons-extended" /Users/wattledgnata/traeProjects/gps-app/feature/test/build.gradle.kts
```

如无输出，在 `dependencies` 块中添加：
```kotlin
implementation("androidx.compose.material:material-icons-extended")
```

- [ ] **Step 2: 新增 `BatteryIndicator` Composable**

在 `DeviceHomeScreen.kt` 文件末尾（`ConnectedDeviceCard` 之后）插入：

```kotlin
/**
 * 外接 GPS 设备电量指示器。
 * - 有值：电池图标（按百分比映射 7 档）+ Mechanical 百分比数字 + "%"
 * - null 且已连接（设备无电量服务）：灰色 BatteryUnknown + "N/A"
 *
 * 图标映射：>=95=BatteryFull, >=80=Battery6Bar, >=60=Battery5Bar,
 *           >=40=Battery4Bar, >=20=Battery3Bar, >=10=Battery2Bar,
 *           >=1=Battery1Bar, ==0=BatteryAlert
 * 颜色：>20% 白色，<=20% TrackTechColors.Red，N/A 灰色
 */
@Composable
private fun BatteryIndicator(batteryPercent: Int?) {
    val (icon, tint) = when (batteryPercent) {
        null -> Icons.Filled.BatteryUnknown to TrackTechColors.TextMuted
        in 95..100 -> Icons.Filled.BatteryFull to TrackTechColors.TextPrimary
        in 80..94 -> Icons.Filled.Battery6Bar to TrackTechColors.TextPrimary
        in 60..79 -> Icons.Filled.Battery5Bar to TrackTechColors.TextPrimary
        in 40..59 -> Icons.Filled.Battery4Bar to TrackTechColors.TextPrimary
        in 20..39 -> Icons.Filled.Battery3Bar to TrackTechColors.TextPrimary
        in 10..19 -> Icons.Filled.Battery2Bar to TrackTechColors.Red
        in 1..9 -> Icons.Filled.Battery1Bar to TrackTechColors.Red
        0 -> Icons.Filled.BatteryAlert to TrackTechColors.Red
        else -> Icons.Filled.BatteryUnknown to TrackTechColors.TextMuted
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = if (batteryPercent != null) "Battery $batteryPercent%" else "Battery unknown",
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (batteryPercent != null) {
            MetricNumber(
                value = batteryPercent.toString(),
                unit = "%",
                size = MetricSize.Small,
                kind = MetricKind.Mechanical,
                valueColor = tint,
            )
        } else {
            Text(
                text = "N/A",
                style = TrackTechTypography.ScoreSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
```

- [ ] **Step 3: 在 `ConnectedDeviceCard` 中集成 BatteryIndicator**

在函数签名中新增参数 `batteryPercent: Int? = null`，在状态圆点行之后、`Spacer(Modifier.height(14.dp))` 之前插入：

```kotlin
// 电量指示器：仅已连接时显示
if (batteryPercent != null || connectionState == ConnectionState.CONNECTED) {
    Spacer(Modifier.height(10.dp))
    BatteryIndicator(batteryPercent = batteryPercent)
}
```

- [ ] **Step 4: 在 `DeviceHomeScreen` 调用点 collect 并传递 batteryPercent**

```kotlin
val batteryPercent by gpsViewModel.batteryPercent.collectAsState()
```

在 `ConnectedDeviceCard(...)` 调用处追加 `batteryPercent = batteryPercent,`

- [ ] **Step 5: 添加 Material Icons Extended 导入**

```kotlin
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
```

- [ ] **Step 6: 编译验证**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :feature:test:compileDebugKotlin --offline 2>&1 | tail -10
```

预期：BUILD SUCCESSFUL。如 `BatteryUnknown` / 细栏图标在 extended 库中不可用，则 fallback 到 `BatteryStd` + 颜色 + `BatteryAlert` 三档简化映射。

- [ ] **Step 7: Commit**

```bash
git add feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/DeviceHomeScreen.kt \
        feature/test/build.gradle.kts
git commit -m "feat(ui): add BatteryIndicator to ConnectedDeviceCard on Device home

Displays battery icon (7 bar levels) + Mechanical percentage number.
Grey BatteryUnknown + N/A when device has no Battery Service.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 单元测试

**Files:**
- Create: `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BleConnectionBatteryTest.kt`

**Interfaces:**
- Consumes: `BleConnection.parseBatteryPercent(ByteArray?): Int?`

- [ ] **Step 1: 创建 `parseBatteryPercent` 纯函数测试**

创建文件 `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BleConnectionBatteryTest.kt`：

```kotlin
// @IgnoreFormatCheck
package com.blazepush.core.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConnectionBatteryTest {

    @Test
    fun parseBatteryPercent_85() {
        assertEquals(85, BleConnection.parseBatteryPercent(byteArrayOf(0x55.toByte())))
    }

    @Test
    fun parseBatteryPercent_zero() {
        assertEquals(0, BleConnection.parseBatteryPercent(byteArrayOf(0x00)))
    }

    @Test
    fun parseBatteryPercent_100() {
        assertEquals(100, BleConnection.parseBatteryPercent(byteArrayOf(0x64.toByte())))
    }

    @Test
    fun parseBatteryPercent_over100_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf(0x65.toByte())))
    }

    @Test
    fun parseBatteryPercent_emptyArray_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf()))
    }

    @Test
    fun parseBatteryPercent_null_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(null))
    }

    @Test
    fun parseBatteryPercent_255_returnsNull() {
        assertEquals(null, BleConnection.parseBatteryPercent(byteArrayOf(0xFF.toByte())))
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :core:bluetooth:testDebugUnitTest --tests "com.blazepush.core.bluetooth.BleConnectionBatteryTest" --offline 2>&1 | tail -10
```

预期：7 tests passed, BUILD SUCCESSFUL

- [ ] **Step 3: 运行全量 core:bluetooth 测试确保无回归**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :core:bluetooth:testDebugUnitTest --offline 2>&1 | tail -10
```

预期：所有测试 PASS

- [ ] **Step 4: Commit**

```bash
git add core/bluetooth/src/test/java/com/blazepush/core/bluetooth/BleConnectionBatteryTest.kt
git commit -m "test(ble): add parseBatteryPercent unit tests (0-100, null, >100)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 真机验证 gate

**验证设备：** 华为 `8KE0219522008434`（大屏）、vivo `V2405A`（小屏）

- [ ] **Step 1: 构建 APK**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle :app:assembleDebug --offline 2>&1 | tail -5
```

- [ ] **Step 2: vivo V2405A 小屏溢出检查**

用户执行 `adb install`。验证：
- ConnectedDeviceCard 加入电池行后不换行
- 设备名有 Ellipsis 截断
- CutCornerPanel 不超出屏幕宽度

- [ ] **Step 3: 华为 8KE0219522008434 功能验证**

用户执行 `adb install`。验证：
- v2 GPS（有 Battery Service）：显示电池图标 + 百分比数字
- 电池颜色：>20% 白、≤20% 红
- 断连 → 电池行消失
- v1 GPS / 无 Battery Service：灰色 `BatteryUnknown` + `N/A`

---

### Task 7: 全量测试 + 合回

- [ ] **Step 1: 运行全量单元测试**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && gradle testDebugUnitTest --offline 2>&1 | tail -10
```

- [ ] **Step 2: 确认 feature 分支已 rebase 到最新**

```bash
cd /Users/wattledgnata/traeProjects/gps-app && git fetch origin && git rebase origin/feature/track-tech-v2
```

- [ ] **Step 3: 合回后清理 worktree（如在 worktree 内开发）**
