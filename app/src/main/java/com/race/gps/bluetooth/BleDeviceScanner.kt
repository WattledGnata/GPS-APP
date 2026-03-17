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
                // 直接处理扫描结果，不需要callbackType
                val device = result.device
                val name = device.name ?: "未知设备"
                val address = device.address
                val rssi = result.rssi

                val existingDevice = deviceCache[address]
                val newDevice = ScannedDevice(
                    name = name,
                    address = address,
                    rssi = rssi,
                    lastSeen = System.currentTimeMillis()
                )

                if (existingDevice == null || rssi > existingDevice.rssi) {
                    deviceCache[address] = newDevice
                    updateScanResults()
                }
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
