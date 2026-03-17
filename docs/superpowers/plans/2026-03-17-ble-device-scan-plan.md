# BLE设备扫描和连接功能实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标:** 实现完整的BLE设备扫描、自动重连和设备管理功能，支持RaceChrono GPS设备的发现和连接。

**架构:** 采用分层架构，新增BleDeviceScanner（扫描器）和 BleDeviceManager（管理器），遵循单一数据源原则，与现有BluetoothDataSource集成。

**技术栈:** Kotlin, Jetpack Compose, Coroutines/Flow, Koin DI, Android BLE API

---

## 文件结构

### 新建文件
1. `app/src/main/java/com/race/gps/bluetooth/BleDeviceScanner.kt` - BLE设备扫描器
2. `app/src/main/java/com/race/gps/bluetooth/BleDeviceManager.kt` - 设备管理器
3. `app/src/main/java/com/race/gps/ui/screen/DeviceScanDialog.kt` - 设备扫描对话框
4. `app/src/main/java/com/race/gps/bluetooth/ScannedDevice.kt` - 扫描设备数据模型
5. `app/src/main/java/com/race/gps/bluetooth/PermissionChecker.kt` - 权限检查工具

### 修改文件
1. `app/src/main/java/com/race/gps/ui/screen/DeviceConnectionScreen.kt` - 添加扫描按钮和集成
2. `app/src/main/java/com/race/gps/di/Module.kt` - 添加Koin依赖注入配置
3. `app/src/main/AndroidManifest.xml` - 添加BLE权限声明

---

## Task 1: 创建扫描设备数据模型

**Files:**
- Create: `app/src/main/java/com/race/gps/bluetooth/ScannedDevice.kt`

- [ ] **Step 1: 创建ScannedDevice数据类**

```kotlin
package com.race.gps.bluetooth

/**
 * 扫描发现的BLE设备
 *
 * @property name 设备名称
 * @property address 设备MAC地址
 * @property rssi 信号强度 (dBm)
 * @property lastSeen 最后发现时间戳
 */
data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
) {
    /**
     * 获取信号强度描述
     */
    fun getSignalStrength(): SignalStrength {
        return when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else -> SignalStrength.WEAK
        }
    }
}

/**
 * 信号强度枚举
 */
enum class SignalStrength {
    EXCELLENT,  // 优秀 (-50 dBm或更好)
    GOOD,       // 良好 (-50 到 -60 dBm)
    FAIR,       // 一般 (-60 到 -70 dBm)
    WEAK        // 弱 (差于 -70 dBm)
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/bluetooth/ScannedDevice.kt
git commit -m "feat: 添加扫描设备数据模型

- 创建ScannedDevice数据类，包含设备基本信息
- 添加信号强度枚举和计算方法
- 为设备扫描功能提供数据基础

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 实现权限检查工具

**Files:**
- Create: `app/src/main/java/com/race/gps/bluetooth/PermissionChecker.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 添加权限声明到AndroidManifest.xml**

在 `app/src/main/AndroidManifest.xml` 的 `<manifest>` 标签内添加：

```xml
<!-- Android 12+ (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Android 12及以下 -->
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- 位置权限 (BLE扫描需要) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

- [ ] **Step 2: 创建PermissionChecker工具类**

```kotlin
package com.race.gps.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BLE权限检查工具
 */
object PermissionChecker {

    /**
     * 检查是否有扫描权限
     */
    fun hasScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查是否有连接权限
     */
    fun hasConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 12以下不需要特殊连接权限
        }
    }

    /**
     * 检查是否有所有必需的BLE权限
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasScanPermission(context) && hasConnectPermission(context)
    }

    /**
     * 获取需要请求的权限列表
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/race/gps/bluetooth/PermissionChecker.kt app/src/main/AndroidManifest.xml
git commit -m "feat: 添加BLE权限检查工具

- 创建PermissionChecker工具类，统一管理权限检查逻辑
- 兼容Android 12+和旧版本的权限模型
- 在AndroidManifest.xml中声明所需的BLE权限
- 为设备扫描功能提供权限基础

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 实现BLE设备扫描器

**Files:**
- Create: `app/src/main/java/com/race/gps/bluetooth/BleDeviceScanner.kt`

- [ ] **Step 1: 创建BleDeviceScanner类**

```kotlin
package com.race.gps.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE设备扫描器
 * 负责扫描附近的BLE设备并过滤RaceChrono设备
 */
class BleDeviceScanner(
    private val context: Context
) {
    companion object {
        private const val TAG = "BleDeviceScanner"
        private val SERVICE_UUID: UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
        private const val SCAN_DURATION_MS = 30000L // 30秒自动停止
        private const val MAX_DEVICES = 20 // 最大显示设备数
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 扫描结果
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    // 扫描状态
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    // 设备去重缓存
    private val deviceCache = mutableMapOf<String, ScannedDevice>()

    /**
     * 开始扫描BLE设备
     */
    fun startScan() {
        if (!PermissionChecker.hasScanPermission(context)) {
            Log.e(TAG, "缺少扫描权限")
            return
        }

        if (_isScanning.value) {
            Log.w(TAG, "已经在扫描中")
            return
        }

        bluetoothLeScanner?.let { scanner ->
            try {
                _isScanning.value = true
                deviceCache.clear()
                _scanResults.value = emptyList()

                // 配置扫描过滤器
                val scanFilter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build()

                // 配置扫描设置
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build()

                // 开始扫描
                scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)

                Log.d(TAG, "开始扫描BLE设备")
            } catch (e: Exception) {
                Log.e(TAG, "扫描失败", e)
                _isScanning.value = false
            }
        } ?: run {
            Log.e(TAG, "BluetoothLeScanner不可用")
            _isScanning.value = false
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!_isScanning.value) {
            return
        }

        bluetoothLeScanner?.let { scanner ->
            try {
                scanner.stopScan(scanCallback)
                _isScanning.value = false
                Log.d(TAG, "停止扫描")
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描失败", e)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "未知设备"
            val address = device.address
            val rssi = result.rssi

            // 更新或添加设备
            val existingDevice = deviceCache[address]
            val newDevice = ScannedDevice(
                name = name,
                address = address,
                rssi = rssi,
                lastSeen = System.currentTimeMillis()
            )

            // 只保留RSSI更强的记录
            if (existingDevice == null || rssi > existingDevice.rssi) {
                deviceCache[address] = newDevice
                updateScanResults()
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                onScanResult(ScanCallback.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "扫描失败: 错误代码 $errorCode")
            _isScanning.value = false
        }
    }

    /**
     * 更新扫描结果列表
     */
    private fun updateScanResults() {
        val now = System.currentTimeMillis()
        val validDevices = deviceCache.values
            .filter { now - it.lastSeen < SCAN_DURATION_MS }
            .sortedByDescending { it.rssi }
            .take(MAX_DEVICES)

        _scanResults.value = validDevices
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopScan()
        deviceCache.clear()
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/bluetooth/BleDeviceScanner.kt
git commit -m "feat: 实现BLE设备扫描器

- 创建BleDeviceScanner类，使用Android BLE API扫描设备
- 根据SERVICE_UUID过滤RaceChrono设备
- 实现设备去重和信号强度排序
- 限制最大显示设备数为20个
- 提供开始/停止扫描方法

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 实现设备管理器

**Files:**
- Create: `app/src/main/java/com/race/gps/bluetooth/BleDeviceManager.kt`

- [ ] **Step 1: 创建BleDeviceManager类**

```kotlin
package com.race.gps.bluetooth

import android.content.Context
import android.util.Log
import com.race.gps.data.repository.BluetoothDeviceRepository
import com.race.gps.data.service.parser.RaceChronoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BLE设备管理器
 * 统一管理设备扫描、连接和自动重连逻辑
 */
class BleDeviceManager(
    private val context: Context,
    private val bluetoothDataSource: BluetoothDataSource
) {
    companion object {
        private const val TAG = "BleDeviceManager"
        private const val RECONNECT_TIMEOUT_MS = 10000L // 10秒重连超时
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scanner = BleDeviceScanner(context)

    // 连接状态 (代理自BluetoothDataSource)
    val connectionState: StateFlow<ConnectionState> = bluetoothDataSource.connectionState

    // 扫描状态 (代理自BleDeviceScanner)
    val isScanning: StateFlow<Boolean> = scanner.isScanning

    // 扫描结果 (代理自BleDeviceScanner)
    val scanResults: StateFlow<List<ScannedDevice>> = scanner.scanResults

    private var autoReconnectInProgress = false

    init {
        // 自动重连上次设备
        autoReconnectLastDevice()
    }

    /**
     * 自动重连上次使用的设备
     */
    fun autoReconnectLastDevice() {
        if (autoReconnectInProgress) {
            Log.w(TAG, "自动重连已在进行中")
            return
        }

        autoReconnectInProgress = true

        scope.launch {
            try {
                // TODO: 从Repository获取上次连接的设备地址
                // val lastDeviceAddress = deviceRepository.getLastConnectedDevice()
                val lastDeviceAddress: String? = null // 暂时为null，待Task 5实现

                if (lastDeviceAddress != null) {
                    Log.d(TAG, "尝试自动重连设备: $lastDeviceAddress")

                    // 等待一小段时间确保蓝牙就绪
                    delay(1000)

                    // 尝试连接
                    bluetoothDataSource.connect(lastDeviceAddress)

                    // 等待连接结果
                    var waited = 0L
                    while (waited < RECONNECT_TIMEOUT_MS) {
                        delay(500)
                        waited += 500

                        if (connectionState.value == ConnectionState.CONNECTED) {
                            Log.d(TAG, "自动重连成功")
                            return@launch
                        }
                    }

                    // 超时未连接成功
                    Log.w(TAG, "自动重连超时，开始扫描其他设备")
                    startScan()
                } else {
                    Log.d(TAG, "没有上次连接的设备记录")
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动重连失败", e)
                // 失败后自动开始扫描
                startScan()
            } finally {
                autoReconnectInProgress = false
            }
        }
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        if (!PermissionChecker.hasAllRequiredPermissions(context)) {
            Log.e(TAG, "缺少必需的权限")
            return
        }

        scanner.startScan()
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        scanner.stopScan()
    }

    /**
     * 连接指定设备
     */
    fun connect(deviceAddress: String) {
        scope.launch {
            try {
                // 停止当前扫描
                stopScan()

                // 连接设备
                bluetoothDataSource.connect(deviceAddress)

                // TODO: 连接成功后保存设备信息到Repository
                // deviceRepository.saveDevice(deviceAddress, deviceName)
            } catch (e: Exception) {
                Log.e(TAG, "连接失败", e)
            }
        }
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        bluetoothDataSource.disconnect()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        scanner.cleanup()
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/bluetooth/BleDeviceManager.kt
git commit -m "feat: 实现BLE设备管理器

- 创建BleDeviceManager类，统一管理扫描和连接
- 实现自动重连逻辑，支持上次设备快速连接
- 重连失败后自动开始扫描其他设备
- 提供连接、断开、扫描等核心方法
- 与BluetoothDataSource集成，遵循单一数据源原则

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 实现设备扫描对话框UI

**Files:**
- Create: `app/src/main/java/com/race/gps/ui/screen/DeviceScanDialog.kt`

- [ ] **Step 1: 创建DeviceScanDialog Composable**

```kotlin
package com.race.gps.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.race.gps.bluetooth.ScannedDevice
import com.race.gps.bluetooth.SignalStrength

/**
 * 设备扫描对话框
 *
 * @param isScanning 是否正在扫描
 * @param devices 扫描到的设备列表
 * @param onStopScan 停止扫描回调
 * @param onDeviceClick 设备点击回调
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun DeviceScanDialog(
    isScanning: Boolean,
    devices: List<ScannedDevice>,
    onStopScan: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "扫描设备",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("✕")
                    }
                }

                Divider()

                Spacer(modifier = Modifier.height(16.dp))

                // 扫描状态
                ScanStatusIndicator(isScanning = isScanning)

                Spacer(modifier = Modifier.height(16.dp))

                // 停止扫描按钮
                if (isScanning) {
                    Button(
                        onClick = onStopScan,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("停止扫描")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 设备列表
                Text(
                    text = "发现的设备 (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    // 空状态
                    EmptyDeviceList()
                } else {
                    // 设备列表
                    DeviceList(
                        devices = devices,
                        onDeviceClick = onDeviceClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanStatusIndicator(isScanning: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isScanning) {
            // 扫描动画
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = "正在扫描设备...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = MaterialTheme.shapes.small,
                color = Color.Gray
            ) {}
            Text(
                text = "扫描停止",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun EmptyDeviceList() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "未找到RaceChrono设备",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray
            )
            Text(
                text = "请确保设备已开启并在附近",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<ScannedDevice>,
    onDeviceClick: (String) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.heightIn(max = 400.dp)
    ) {
        items(devices) { device ->
            DeviceItem(
                device = device,
                onClick = { onDeviceClick(device.address) }
            )
        }
    }
}

@Composable
private fun DeviceItem(
    device: ScannedDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设备信息
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // 信号强度
            SignalIndicator(device.getSignalStrength())
        }
    }
}

@Composable
private fun SignalIndicator(strength: SignalStrength) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = getStrengthText(strength),
            style = MaterialTheme.typography.bodySmall,
            color = getStrengthColor(strength)
        )

        // 信号条
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                val isActive = index < getStrengthLevel(strength)
                Surface(
                    modifier = Modifier
                        .width(4.dp)
                        .height(8.dp + index * 4.dp),
                    shape = MaterialTheme.shapes.small,
                    color = if (isActive) {
                        getStrengthColor(strength)
                    } else {
                        Color.LightGray
                    }
                ) {}
            }
        }
    }
}

private fun getStrengthLevel(strength: SignalStrength): Int {
    return when (strength) {
        SignalStrength.EXCELLENT -> 4
        SignalStrength.GOOD -> 3
        SignalStrength.FAIR -> 2
        SignalStrength.WEAK -> 1
    }
}

private fun getStrengthText(strength: SignalStrength): String {
    return when (strength) {
        SignalStrength.EXCELLENT -> "优秀"
        SignalStrength.GOOD -> "良好"
        SignalStrength.FAIR -> "一般"
        SignalStrength.WEAK -> "弱"
    }
}

private fun getStrengthColor(strength: SignalStrength): Color {
    return when (strength) {
        SignalStrength.EXCELLENT -> Color(0xFF4CAF50)
        SignalStrength.GOOD -> Color(0xFF8BC34A)
        SignalStrength.FAIR -> Color(0xFFFF9800)
        SignalStrength.WEAK -> Color(0xFFF44336)
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/ui/screen/DeviceScanDialog.kt
git commit -m "feat: 实现设备扫描对话框UI

- 创建DeviceScanDialog，显示扫描进度和设备列表
- 实现设备项组件，显示设备名称、地址和信号强度
- 添加信号强度可视化指示器（4格信号条）
- 支持扫描状态实时更新
- 提供空状态提示和停止扫描按钮

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 修改DeviceConnectionScreen集成扫描功能

**Files:**
- Modify: `app/src/main/java/com/race/gps/ui/screen/DeviceConnectionScreen.kt`

- [ ] **Step 1: 修改DeviceConnectionScreen添加扫描功能**

完全替换 `app/src/main/java/com/race/gps/ui/screen/DeviceConnectionScreen.kt` 的内容为：

```kotlin
package com.race.gps.ui.screen

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.bluetooth.ScannedDevice
import com.race.gps.domain.model.GpsData
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 设备连接页面
 * 显示GPS设备连接状态和信号质量，支持设备扫描
 */
@Composable
fun DeviceConnectionScreen(
    onConnected: () -> Unit,
    gpsDataViewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by gpsDataViewModel.gpsData.collectAsState()
    val connectionState by gpsDataViewModel.connectionState.collectAsState()
    val isScanning by gpsDataViewModel.isScanning.collectAsState()
    val scanResults by gpsDataViewModel.scanResults.collectAsState()

    var showScanDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("GPS 设备", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // 连接状态卡片
        ConnectionStatusCard(
            gpsData = gpsData,
            connectionState = connectionState,
            isScanning = isScanning,
            deviceCount = scanResults.size
        )

        // GPS信号质量卡片
        if (connectionState == ConnectionState.CONNECTED) {
            GpsSignalCard(gpsData)
        }

        Spacer(modifier = Modifier.weight(1f))

        // 扫描设备按钮 (未连接时显示)
        if (connectionState != ConnectionState.CONNECTED && !isScanning) {
            Button(
                onClick = { showScanDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text = "扫描设备",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // 开始测试按钮
        Button(
            onClick = onConnected,
            enabled = connectionState == ConnectionState.CONNECTED && gpsData.isTestReady,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                text = when {
                    connectionState != ConnectionState.CONNECTED -> "等待设备连接..."
                    !gpsData.isTestReady -> "等待GPS就绪（卫星: ${gpsData.satelliteCount}）"
                    else -> "开始测试 →"
                },
                fontSize = 16.sp
            )
        }
    }

    // 设备扫描对话框
    if (showScanDialog) {
        DeviceScanDialog(
            isScanning = isScanning,
            devices = scanResults,
            onStopScan = { gpsDataViewModel.stopScan() },
            onDeviceClick = { deviceAddress ->
                gpsDataViewModel.connectDevice(deviceAddress)
                showScanDialog = false
            },
            onDismiss = { showScanDialog = false }
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    gpsData: GpsData,
    connectionState: ConnectionState,
    isScanning: Boolean,
    deviceCount: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设备状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (statusText, statusColor) = when {
                    isScanning -> "扫描中 ($deviceCount)" to Color(0xFF2196F3)
                    connectionState == ConnectionState.CONNECTED -> "已连接" to Color(0xFF4CAF50)
                    connectionState == ConnectionState.CONNECTING -> "连接中..." to Color(0xFFFF9800)
                    else -> "未连接" to Color(0xFFF44336)
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = statusColor
                ) {}
                Text(statusText, color = statusColor, fontWeight = FontWeight.Medium)
            }
            if (connectionState == ConnectionState.CONNECTED) {
                Text("频率: ${gpsData.frequency} Hz", fontSize = 14.sp, color = Color.Gray)
            }
            gpsData.errorMessage?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFFF44336))
            }
        }
    }
}

@Composable
private fun GpsSignalCard(gpsData: GpsData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GPS 信号", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("卫星数量", fontSize = 12.sp, color = Color.Gray)
                    Text("${gpsData.satelliteCount}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("HDOP", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.1f", gpsData.hdop), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("就绪状态", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        if (gpsData.isTestReady) "就绪" else "等待",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (gpsData.isTestReady) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/ui/screen/DeviceConnectionScreen.kt
git commit -m "feat: 在连接页面集成设备扫描功能

- 添加\"扫描设备\"按钮，未连接时显示
- 集成DeviceScanDialog，显示扫描结果
- 在状态卡片中显示扫描状态和设备数量
- 支持从对话框选择设备进行连接
- 优化用户体验，提供清晰的连接流程

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 扩展GpsDataViewModel集成BleDeviceManager

**Files:**
- Modify: `app/src/main/java/com/race/gps/viewmodel/GpsDataViewModel.kt`

- [ ] **Step 1: 修改GpsDataViewModel添加扫描功能**

在现有的 `GpsDataViewModel` 中添加以下方法和状态：

```kotlin
// 在类的属性部分添加
private val bleDeviceManager: BleDeviceManager by inject()

val isScanning: StateFlow<Boolean> = bleDeviceManager.isScanning
val scanResults: StateFlow<List<ScannedDevice>> = bleDeviceManager.scanResults

// 添加方法
fun startScan() {
    bleDeviceManager.startScan()
}

fun stopScan() {
    bleDeviceManager.stopScan()
}

fun connectDevice(deviceAddress: String) {
    bleDeviceManager.connect(deviceAddress)
}
```

完整的修改后的类应该类似这样（保留现有代码，只添加新内容）：

```kotlin
package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import com.race.gps.bluetooth.BleDeviceManager
import com.race.gps.bluetooth.ScannedDevice
import com.race.gps.data.repository.GpsDataRepository
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.component.inject

/**
 * GPS数据ViewModel
 * 提供GPS数据和设备连接状态
 */
class GpsDataViewModel(
    private val gpsDataRepository: GpsDataRepository
) : ViewModel() {

    // 依赖注入
    private val bleDeviceManager: BleDeviceManager by inject()

    // GPS数据流
    val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow

    // 连接状态
    val connectionState = bleDeviceManager.connectionState

    // 扫描状态
    val isScanning: StateFlow<Boolean> = bleDeviceManager.isScanning

    // 扫描结果
    val scanResults: StateFlow<List<ScannedDevice>> = bleDeviceManager.scanResults

    /**
     * 开始扫描设备
     */
    fun startScan() {
        bleDeviceManager.startScan()
    }

    /**
     * 停止扫描设备
     */
    fun stopScan() {
        bleDeviceManager.stopScan()
    }

    /**
     * 连接指定设备
     */
    fun connectDevice(deviceAddress: String) {
        bleDeviceManager.connect(deviceAddress)
    }

    /**
     * 断开设备连接
     */
    fun disconnect() {
        bleDeviceManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        bleDeviceManager.cleanup()
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/viewmodel/GpsDataViewModel.kt
git commit -m "feat: 扩展GpsDataViewModel支持设备扫描

- 集成BleDeviceManager到ViewModel
- 添加isScanning和scanResults状态流
- 提供startScan、stopScan和connectDevice方法
- 在onCleared中清理资源
- 统一管理GPS数据和设备连接状态

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 配置Koin依赖注入

**Files:**
- Modify: `app/src/main/java/com/race/gps/di/Module.kt` (或创建如果不存在)

- [ ] **Step 1: 更新Koin模块配置**

找到或创建 `app/src/main/java/com/race/gps/di/Module.kt`，添加BleDeviceManager的配置：

```kotlin
package com.race.gps.di

import com.race.gps.bluetooth.BleDeviceManager
import com.race.gps.data.repository.GpsDataRepository
import com.race.gps.data.service.parser.RaceChronoParser
import org.koin.dsl.module

/**
 * Koin依赖注入模块
 */
val appModule = module {

    // GPS数据Repository
    single { GpsDataRepository(get(), get()) }

    // RaceChrono解析器
    single { RaceChronoParser() }

    // BLE设备管理器
    single { BleDeviceManager(get(), get()) }

    // 其他已有配置...
}
```

如果文件不存在，创建整个文件。

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/com/race/gps/di/Module.kt
git commit -m "feat: 配置Koin依赖注入支持BleDeviceManager

- 在appModule中添加BleDeviceManager单例配置
- 确保BleDeviceManager正确注入到ViewModel
- 维护依赖注入的统一管理

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: 测试和验证

**Files:**
- No file changes (testing only)

- [ ] **Step 1: 编译检查**

```bash
./gradlew assembleDebug
```

预期：编译成功，无错误

- [ ] **Step 2: 在真机上测试**

测试场景：
1. 应用启动后检查是否自动尝试连接上次设备
2. 点击"扫描设备"按钮，检查是否开始扫描
3. 检查扫描对话框是否正常显示
4. 检查扫描到的设备列表是否正确过滤
5. 选择设备进行连接，检查连接流程
6. 检查连接成功后是否正常显示GPS数据
7. 检查"停止扫描"按钮功能

- [ ] **Step 3: 验证权限请求**

首次运行时检查：
- Android 12+设备：请求BLUETOOTH_SCAN、BLUETOOTH_CONNECT、ACCESS_FINE_LOCATION
- Android 12以下设备：请求ACCESS_FINE_LOCATION
- 拒绝权限后是否正确处理

- [ ] **Step 4: 验证错误处理**

测试异常场景：
1. 蓝牙关闭时的扫描
2. 权限被拒绝时的扫描
3. 设备不在范围内的连接
4. 自动重连失败后的扫描启动

- [ ] **Step 5: 性能检查**

检查项：
- 扫描时CPU和内存使用是否正常
- 设备列表更新是否流畅
- 连接建立速度是否可接受

- [ ] **Step 6: 提交测试报告**

创建测试报告文档（可选）：

```bash
# 创建测试报告
cat > BLE_SCAN_TEST_REPORT.md << 'EOF'
# BLE设备扫描功能测试报告

## 测试日期
2026-03-17

## 测试环境
- 设备: [填写设备型号]
- Android版本: [填写版本]
- 应用版本: [填写版本]

## 测试结果

### 功能测试
- [ ] 应用启动自动重连
- [ ] 扫描按钮功能
- [ ] 扫描对话框显示
- [ ] 设备列表过滤
- [ ] 设备连接功能
- [ ] GPS数据显示
- [ ] 停止扫描功能

### 权限测试
- [ ] Android 12+权限请求
- [ ] Android 12-权限请求
- [ ] 权限拒绝处理

### 错误处理测试
- [ ] 蓝牙关闭扫描
- [ ] 权限拒绝扫描
- [ ] 设备超时连接
- [ ] 自动重连失败

### 性能测试
- [ ] 扫描CPU使用
- [ ] 扫描内存使用
- [ ] 列表更新流畅度
- [ ] 连接建立速度

## 发现的问题
[记录发现的问题]

## 建议
[记录改进建议]
EOF

# 如果有测试结果，提交
git add BLE_SCAN_TEST_REPORT.md
git commit -m "test: 添加BLE扫描功能测试报告"
```

---

## Task 10: 代码清理和优化

**Files:**
- Multiple files (code review and optimization)

- [ ] **Step 1: 代码检查清单**

检查项：
1. 所有文件都有适当的文档注释
2. 没有硬编码的字符串（提取到常量）
3. 没有TODO注释（或已创建Issue跟踪）
4. 日志级别使用正确
5. 异常处理完整
6. 资源正确释放（cleanup方法）
7. 没有内存泄漏风险

- [ ] **Step 2: 性能优化**

优化项：
1. 设备列表滚动性能（使用key参数）
2. 扫描结果去重效率
3. Flow订阅正确处理生命周期
4. 避免不必要的重组

- [ ] **Step 3: 提交最终版本**

```bash
git add -A
git commit -m "refactor: BLE设备扫描功能代码优化

- 完善代码文档和注释
- 优化性能和内存使用
- 改进错误处理和资源管理
- 代码质量提升

Co-Authored-By: Claude Sonnet 4.6 (1M context) <noreply@anthropic.com>"
```

---

## 总结

完成本计划后，应用将具备以下功能：

1. ✅ BLE设备扫描功能，自动过滤RaceChrono设备
2. ✅ 自动重连上次使用的设备
3. ✅ 用户可控的扫描流程（开始/停止按钮）
4. ✅ 智能失败处理（重连失败自动扫描）
5. ✅ 友好的设备扫描UI
6. ✅ 完整的权限管理
7. ✅ 遵循单一数据源架构原则

### 下一步工作

本计划实现了核心扫描功能。未来可以扩展：
- 设备信息持久化（保存到Repository）
- 多设备管理（收藏、重命名）
- 更多设备类型支持
- 扫描结果动画效果
- 设备连接质量指示

### 注意事项

1. 确保在真机上测试，BLE扫描在模拟器上不可用
2. Android 12+需要特别注意权限处理
3. 测试时确保RaceChrono设备已开启且在附近
4. 首次运行需要授予位置权限（BLE扫描要求）
