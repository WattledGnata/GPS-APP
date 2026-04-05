# Simulator Connected Device Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 GPS 模拟器页面新增一张“已接入设备”卡片，展示发射端视角下已接入 central 设备的 MAC 地址以及 Connected / Active 状态。

**Architecture:** 复用现有 `GattServerManager` 中的连接集合与活跃判断，不改 BLE 主流程，只新增一层面向 UI 的设备状态模型。`SimulatorViewModel` 负责把底层连接/活跃状态整理成列表，`SimulatorScreen` 负责渲染独立卡片。

**Tech Stack:** Kotlin, Android ViewModel, StateFlow, Jetpack Compose, JUnit4

---

## File Structure

- Modify: `simulator/src/main/java/com/blazepush/simulator/ble/GattServerManager.kt`
  - 暴露每个已接入设备的 active 判断结果，保持 BLE 连接/通知逻辑不变。
- Modify: `simulator/src/main/java/com/blazepush/simulator/ble/GpsPeripheralManager.kt`
  - 向上暴露设备详情流，供 ViewModel 订阅。
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
  - 新增 UI 使用的设备状态数据结构，并同步到底层连接信息。
- Modify: `simulator/src/main/java/com/blazepush/simulator/ui/SimulatorScreen.kt`
  - 新增“已接入设备”卡片并渲染列表。
- Create: `simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt`
  - 为设备列表映射逻辑提供 TDD 回归测试。

---

### Task 1: 建立设备状态映射模型

**Files:**
- Create: `simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt`
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`

- [ ] **Step 1: 写失败测试，定义 UI 设备状态映射规则**

```kotlin
package com.blazepush.simulator.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedDeviceUiStateTest {

    @Test
    fun `maps connected addresses and active addresses into ordered device items`() {
        val items = mapConnectedDevices(
            connectedDevices = linkedSetOf("AA:BB:CC:00:11:22", "11:22:33:44:55:66"),
            activeDevices = setOf("11:22:33:44:55:66")
        )

        assertEquals(
            listOf(
                ConnectedDeviceUiState(
                    address = "11:22:33:44:55:66",
                    isConnected = true,
                    isActive = true
                ),
                ConnectedDeviceUiState(
                    address = "AA:BB:CC:00:11:22",
                    isConnected = true,
                    isActive = false
                )
            ),
            items
        )
    }
}
```

- [ ] **Step 2: 运行测试，确认它因符号不存在而失败**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest`
Expected: FAIL，提示 `ConnectedDeviceUiState` 或 `mapConnectedDevices` 未定义。

- [ ] **Step 3: 在 ViewModel 文件中添加最小设备状态模型与映射函数**

在 `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt` 顶部 `SimulatorUiState` 前加入：

```kotlin
data class ConnectedDeviceUiState(
    val address: String,
    val isConnected: Boolean,
    val isActive: Boolean
)

internal fun mapConnectedDevices(
    connectedDevices: Set<String>,
    activeDevices: Set<String>
): List<ConnectedDeviceUiState> {
    return connectedDevices
        .map { address ->
            ConnectedDeviceUiState(
                address = address,
                isConnected = true,
                isActive = activeDevices.contains(address)
            )
        }
        .sortedWith(
            compareByDescending<ConnectedDeviceUiState> { it.isActive }
                .thenBy { it.address }
        )
}
```

并把 `SimulatorUiState` 扩展为：

```kotlin
data class SimulatorUiState(
    val hasPermissions: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isAdvertising: Boolean = false,
    val isServerReady: Boolean = false,
    val connectedDevices: Set<String> = emptySet(),
    val connectedDeviceItems: List<ConnectedDeviceUiState> = emptyList(),
    val currentScenario: TestScenario = TestScenario.STATIC,
    val isReplayMode: Boolean = false,
    val dataSourceLabel: String = "手动模拟",
    val frequency: Int = 10,
    val satellites: Int = 12,
    val initialSpeed: Float = 0f,
    val currentSpeed: Float = 0f,
    val currentLatitude: Double = 60.1725,
    val currentLongitude: Double = 24.9375,
    val speedMode: SpeedMode = SpeedMode.STATIC,
    val targetSpeed: Float = 60f,
    val speedAcceleration: Float = 2.0f,
    val speedStatus: String = "静止 (0 km/h)"
)
```

- [ ] **Step 4: 运行测试，确认映射规则通过**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest`
Expected: PASS

- [ ] **Step 5: 提交本任务**

```bash
git add simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt
git commit -m "feat: add simulator connected device ui model"
```

### Task 2: 向 ViewModel 暴露 active 设备集合

**Files:**
- Modify: `simulator/src/main/java/com/blazepush/simulator/ble/GattServerManager.kt`
- Modify: `simulator/src/main/java/com/blazepush/simulator/ble/GpsPeripheralManager.kt`
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt`

- [ ] **Step 1: 写失败测试，覆盖 active 设备为空时的状态映射**

在 `ConnectedDeviceUiStateTest.kt` 追加：

```kotlin
@Test
fun `marks all connected devices inactive when active set is empty`() {
    val items = mapConnectedDevices(
        connectedDevices = setOf("AA:BB:CC:00:11:22"),
        activeDevices = emptySet()
    )

    assertEquals(
        listOf(
            ConnectedDeviceUiState(
                address = "AA:BB:CC:00:11:22",
                isConnected = true,
                isActive = false
            )
        ),
        items
    )
}
```

- [ ] **Step 2: 运行测试，确认新断言先失败**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest`
Expected: FAIL，如果当前实现还未把 active 集合真正接到 UI，同步修改前至少先验证新增测试能约束映射结果。

- [ ] **Step 3: 暴露 active 设备集合并把它同步到 UI 状态**

在 `GattServerManager.kt` 中把活跃集合从 getter 升级为 `StateFlow`：

```kotlin
private val _activeDevices = MutableStateFlow<Set<String>>(emptySet())
val activeDevices: StateFlow<Set<String>> = _activeDevices.asStateFlow()
```

更新以下位置：

```kotlin
BluetoothProfile.STATE_CONNECTED -> {
    _connectedDevices.value = _connectedDevices.value + device.address
    deviceActivityMap[device.address] = System.currentTimeMillis()
    _activeDevices.value = activeDevices
}

BluetoothProfile.STATE_DISCONNECTED -> {
    _connectedDevices.value = _connectedDevices.value - device.address
    deviceActivityMap.remove(device.address)
    notificationSubscriptions.clearDevice(device.address)
    _activeDevices.value = activeDevices
}
```

```kotlin
staleDevices.forEach { address ->
    _connectedDevices.value = _connectedDevices.value - address
    deviceActivityMap.remove(address)
    _activeDevices.value = activeDevices
    val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
    gattServer?.cancelConnection(device)
}
```

```kotlin
if (notificationSubscriptions.isEnabled(address, GPS_MAIN_DATA_UUID)) {
    gattServer?.notifyCharacteristicChanged(device, mainDataCharacteristic, false)
    deviceActivityMap[address] = System.currentTimeMillis()
    _activeDevices.value = activeDevices
}
```

```kotlin
if (notificationSubscriptions.isEnabled(address, GPS_TIME_DATA_UUID)) {
    gattServer?.notifyCharacteristicChanged(device, timeDataCharacteristic, false)
    deviceActivityMap[address] = System.currentTimeMillis()
    _activeDevices.value = activeDevices
}
```

```kotlin
deviceActivityMap.clear()
notificationSubscriptions.clearAll()
_activeDevices.value = emptySet()
```

在 `GpsPeripheralManager.kt` 中新增透传：

```kotlin
private val _activeDevices = MutableStateFlow<Set<String>>(emptySet())
val activeDevices: StateFlow<Set<String>> = _activeDevices
```

并在 `init` 中追加：

```kotlin
CoroutineScope(Dispatchers.Main).launch {
    gattServerManager.activeDevices.collect { devices ->
        _activeDevices.value = devices
    }
}
```

在 `SimulatorViewModel.kt` 的 `startAdvertising` 中增加订阅：

```kotlin
viewModelScope.launch {
    manager.activeDevices.collect { activeDevices ->
        val connected = _uiState.value.connectedDevices
        _uiState.value = _uiState.value.copy(
            connectedDeviceItems = mapConnectedDevices(connected, activeDevices)
        )
    }
}
```

并把现有 connectedDevices 订阅改成：

```kotlin
viewModelScope.launch {
    manager.connectedDevices.collect { devices ->
        val activeDevices = peripheralManager?.activeDevices?.value ?: emptySet()
        _uiState.value = _uiState.value.copy(
            connectedDevices = devices,
            connectedDeviceItems = mapConnectedDevices(devices, activeDevices)
        )
    }
}
```

在 `stopAdvertising()` 中补上：

```kotlin
_uiState.value = _uiState.value.copy(
    isAdvertising = false,
    isServerReady = false,
    connectedDevices = emptySet(),
    connectedDeviceItems = emptyList()
)
```

- [ ] **Step 4: 运行测试，确认 active 状态映射保持通过**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest`
Expected: PASS

- [ ] **Step 5: 提交本任务**

```bash
git add simulator/src/main/java/com/blazepush/simulator/ble/GattServerManager.kt simulator/src/main/java/com/blazepush/simulator/ble/GpsPeripheralManager.kt simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt
git commit -m "feat: expose simulator active device states"
```

### Task 3: 新增“已接入设备”卡片

**Files:**
- Modify: `simulator/src/main/java/com/blazepush/simulator/ui/SimulatorScreen.kt`
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt`

- [ ] **Step 1: 写失败测试，约束设备排序为 active 优先、地址次序稳定**

在 `ConnectedDeviceUiStateTest.kt` 追加：

```kotlin
@Test
fun `sorts active devices before inactive devices and then by address`() {
    val items = mapConnectedDevices(
        connectedDevices = setOf("CC:00:00:00:00:03", "AA:00:00:00:00:01", "BB:00:00:00:00:02"),
        activeDevices = setOf("CC:00:00:00:00:03")
    )

    assertEquals(
        listOf(
            "CC:00:00:00:00:03",
            "AA:00:00:00:00:01",
            "BB:00:00:00:00:02"
        ),
        items.map { it.address }
    )
}
```

- [ ] **Step 2: 运行测试，确认排序需求先失败**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest`
Expected: FAIL，如果之前未固定排序则会产生顺序不稳定。

- [ ] **Step 3: 在模拟器页面中渲染独立设备卡片**

在 `SimulatorScreen.kt` 中先把 `ControlCard` 调用修正为真实活跃数：

```kotlin
ControlCard(
    isAdvertising = uiState.isAdvertising,
    isServerReady = uiState.isServerReady,
    connectedDevices = uiState.connectedDevices.size,
    activeDeviceCount = uiState.connectedDeviceItems.count { it.isActive },
    onStartAdvertising = {
        if (uiState.hasPermissions) {
            viewModel.startAdvertising(context)
        } else {
            Toast.makeText(context, "缺少必要权限", Toast.LENGTH_SHORT).show()
        }
    },
    onStopAdvertising = { viewModel.stopAdvertising() }
)
```

紧接在 `ControlCard(...)` 后插入新卡片：

```kotlin
ConnectedDevicesCard(
    devices = uiState.connectedDeviceItems
)
```

在同文件新增：

```kotlin
@Composable
fun ConnectedDevicesCard(
    devices: List<com.blazepush.simulator.viewmodel.ConnectedDeviceUiState>
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "已接入设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (devices.isEmpty()) {
                Text(
                    text = "暂无接入设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9E9E9E)
                )
            } else {
                devices.forEach { device ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text("Connected") }
                            )
                            if (device.isActive) {
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text("Active") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
```

为了避免列表最后多一个空隙，把 `devices.forEach` 改成：

```kotlin
devices.forEachIndexed { index, device ->
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = device.address,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("Connected") }
            )
            if (device.isActive) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Active") }
                )
            }
        }
        if (index != devices.lastIndex) {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认映射测试依旧通过，并构建 simulator 模块**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.viewmodel.ConnectedDeviceUiStateTest :simulator:assembleDebug`
Expected: PASS + BUILD SUCCESSFUL

- [ ] **Step 5: 提交本任务**

```bash
git add simulator/src/main/java/com/blazepush/simulator/ui/SimulatorScreen.kt simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt simulator/src/test/java/com/blazepush/simulator/viewmodel/ConnectedDeviceUiStateTest.kt
git commit -m "feat: show connected devices in simulator ui"
```

---

## Self-Review

- Spec coverage: 方案 A 要求的独立“已接入设备”卡片、MAC 地址、Connected / Active 状态都由 Task 1-3 覆盖。
- Placeholder scan: 计划中所有步骤都给出了具体文件、测试代码、命令和预期结果，没有 TODO/TBD。
- Type consistency: `ConnectedDeviceUiState`、`mapConnectedDevices`、`connectedDeviceItems`、`activeDevices` 在所有任务中名称保持一致。
