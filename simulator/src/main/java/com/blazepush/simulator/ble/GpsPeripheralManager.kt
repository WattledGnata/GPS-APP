package com.blazepush.simulator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * GPS外设管理器
 * 负责管理BLE广播和GATT服务器
 */
class GpsPeripheralManager(private val context: Context) {

    companion object {
        private const val TAG = "GpsPeripheralManager"
        val SERVICE_UUID: UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        bluetoothManager.adapter
    }

    private val advertiser: BluetoothLeAdvertiser? by lazy {
        bluetoothAdapter?.bluetoothLeAdvertiser
    }

    private val gattServerManager = GattServerManager(context)

    private var advertiseCallback: AdvertiseCallback? = null
    private var dataUpdateJob: Job? = null

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices

    private val _isServerReady = MutableStateFlow(false)
    val isServerReady: StateFlow<Boolean> = _isServerReady

    init {
        // 监听GATT服务器的连接状态
        CoroutineScope(Dispatchers.Main).launch {
            gattServerManager.connectedDevices.collect { devices ->
                _connectedDevices.value = devices
            }
        }
        CoroutineScope(Dispatchers.Main).launch {
            gattServerManager.isServerReady.collect { ready ->
                _isServerReady.value = ready
            }
        }
    }

    /**
     * 检查BLE是否支持
     */
    fun isBleSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * 检查蓝牙是否开启
     */
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * 开始广播
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        if (_isAdvertising.value) {
            Log.w(TAG, "Already advertising")
            return
        }

        if (!isBleSupported()) {
            onError("BLE not supported")
            return
        }

        if (!isBluetoothEnabled()) {
            onError("Bluetooth not enabled")
            return
        }

        // 启动GATT服务器
        if (!gattServerManager.startServer()) {
            onError("Failed to start GATT server")
            return
        }

        // 设置广播参数
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // 设置广播数据
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        // 设置扫描响应数据
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .build()

        // 开始广播
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Advertising started successfully")
                _isAdvertising.value = true
                onSuccess()
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Advertising failed with error: $errorCode")
                val errorMessage = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    else -> "Unknown error: $errorCode"
                }
                _isAdvertising.value = false
                gattServerManager.stopServer()
                onError(errorMessage)
            }
        }

        advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    /**
     * 停止广播
     */
    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!_isAdvertising.value) return

        advertiseCallback?.let {
            advertiser?.stopAdvertising(it)
        }
        advertiseCallback = null
        _isAdvertising.value = false

        gattServerManager.stopServer()
        dataUpdateJob?.cancel()
        dataUpdateJob = null

        Log.d(TAG, "Advertising stopped")
    }

    /**
     * 更新GPS数据
     */
    fun updateGpsData(mainData: ByteArray, timeData: ByteArray) {
        if (!_isAdvertising.value) return

        gattServerManager.notifyMainData(mainData)
        gattServerManager.notifyTimeData(timeData)
    }

    /**
     * 获取连接的设备数量
     */
    fun getConnectedDeviceCount(): Int {
        return _connectedDevices.value.size
    }

    /**
     * 获取活跃设备数量
     */
    fun getActiveDeviceCount(): Int {
        return gattServerManager.getActiveDeviceCount()
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopAdvertising()
    }
}
