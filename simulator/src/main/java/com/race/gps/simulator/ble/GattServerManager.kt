package com.race.gps.simulator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * GATT服务器管理器
 * 负责管理BLE GATT服务器和特征值
 */
class GattServerManager(private val context: Context) {

    companion object {
        private const val TAG = "GattServerManager"

        // RaceChrono GPS UUIDs
        val SERVICE_UUID: UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
        val GPS_MAIN_DATA_UUID: UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        val GPS_TIME_DATA_UUID: UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")

        // Descriptor UUIDs
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 心跳检测配置
        private const val HEARTBEAT_INTERVAL_MS = 5000L // 5秒
        private const val INACTIVITY_TIMEOUT_MS = 15000L // 15秒无响应超时
    }

    private var gattServer: BluetoothGattServer? = null
    private var mainDataCharacteristic: BluetoothGattCharacteristic? = null
    private var timeDataCharacteristic: BluetoothGattCharacteristic? = null

    // 设备活跃状态追踪
    private val deviceActivityMap = mutableMapOf<String, Long>()
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices.asStateFlow()

    private val _isServerReady = MutableStateFlow(false)
    val isServerReady: StateFlow<Boolean> = _isServerReady.asStateFlow()

    /**
     * 获取活跃设备列表（有最近活动的设备）
     */
    val activeDevices: Set<String>
        get() = deviceActivityMap.filter { (address, lastActivity) ->
            System.currentTimeMillis() - lastActivity < INACTIVITY_TIMEOUT_MS
        }.keys

    /**
     * GATT服务器回调
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(
            device: android.bluetooth.BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            Log.d(TAG, "onConnectionStateChange: ${device.address}, status=$status, newState=$newState")

            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Device connected: ${device.address}")
                    _connectedDevices.value = _connectedDevices.value + device.address
                    deviceActivityMap[device.address] = System.currentTimeMillis()
                }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected: ${device.address}")
                    _connectedDevices.value = _connectedDevices.value - device.address
                    deviceActivityMap.remove(device.address)
                }
                else -> {
                    Log.d(TAG, "Connection state changed: $newState")
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "onServiceAdded: status=$status, uuid=${service.uuid}")
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                _isServerReady.value = true
            }
        }

        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "onCharacteristicReadRequest: ${characteristic.uuid}")
            gattServer?.sendResponse(
                device,
                requestId,
                android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                offset,
                characteristic.value
            )
        }

        override fun onDescriptorReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "onDescriptorReadRequest: ${descriptor.uuid}")
            gattServer?.sendResponse(
                device,
                requestId,
                android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.value
            )
        }

        override fun onDescriptorWriteRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "onDescriptorWriteRequest: ${descriptor.uuid}, value=${value.contentToString()}")
            descriptor.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
            }
        }
    }

    /**
     * 启动GATT服务器
     */
    @SuppressLint("MissingPermission")
    fun startServer(): Boolean {
        // 清理现有连接
        stopServer()

        // 启动心跳检测
        startHeartbeat()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null")
            return false
        }

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            return false
        }

        // 创建服务
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // 创建GPS主数据特征值
        mainDataCharacteristic = BluetoothGattCharacteristic(
            GPS_MAIN_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        mainDataCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        // 创建GPS时间特征值
        timeDataCharacteristic = BluetoothGattCharacteristic(
            GPS_TIME_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        timeDataCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        service.addCharacteristic(mainDataCharacteristic)
        service.addCharacteristic(timeDataCharacteristic)

        val success = gattServer?.addService(service) ?: false
        Log.d(TAG, "Service added: $success")

        if (success) {
            _isServerReady.value = true
        }

        return success
    }

    /**
     * 启动心跳检测
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                val now = System.currentTimeMillis()
                val staleDevices = deviceActivityMap.filter { (address, lastActivity) ->
                    now - lastActivity > INACTIVITY_TIMEOUT_MS
                }.keys

                staleDevices.forEach { address ->
                    Log.w(TAG, "设备 $address 长时间无响应，移除连接")
                    _connectedDevices.value = _connectedDevices.value - address
                    deviceActivityMap.remove(address)

                    // 尝试断开该设备的连接
                    val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
                    gattServer?.cancelConnection(device)
                }

                // 记录心跳包数据（空数据）
                if (deviceActivityMap.isNotEmpty()) {
                    Log.d(TAG, "发送心跳包，活跃设备: ${deviceActivityMap.keys}")
                    notifyMainData(ByteArray(0)) // 发送空数据作为心跳包
                }
            }
        }
    }

    /**
     * 停止GATT服务器
     */
    @SuppressLint("MissingPermission")
    fun stopServer() {
        heartbeatJob?.cancel()
        heartbeatJob = null

        deviceActivityMap.clear()

        // 断开所有连接
        gattServer?.let { server ->
            server.connectedDevices.forEach { device ->
                try {
                    server.cancelConnection(device)
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting device ${device.address}", e)
                }
            }
        }

        gattServer?.close()
        gattServer = null
        _isServerReady.value = false
        _connectedDevices.value = emptySet()
    }

    /**
     * 通知GPS主数据
     */
    @SuppressLint("MissingPermission")
    fun notifyMainData(data: ByteArray) {
        if (!isServerReady.value) return

        mainDataCharacteristic?.value = data

        // 只通知活跃设备
        activeDevices.forEach { address ->
            try {
                val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
                val enabled = mainDataCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.value?.get(0) ?: 0
                if ((enabled.toInt() and 0x01) == 0x01) {
                    gattServer?.notifyCharacteristicChanged(device, mainDataCharacteristic, false)
                    // 更新设备活跃时间
                    deviceActivityMap[address] = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying device $address", e)
            }
        }
    }

    /**
     * 通知GPS时间数据
     */
    @SuppressLint("MissingPermission")
    fun notifyTimeData(data: ByteArray) {
        if (!isServerReady.value) return

        timeDataCharacteristic?.value = data

        // 只通知活跃设备
        activeDevices.forEach { address ->
            try {
                val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
                val enabled = timeDataCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.value?.get(0) ?: 0
                if ((enabled.toInt() and 0x01) == 0x01) {
                    gattServer?.notifyCharacteristicChanged(device, timeDataCharacteristic, false)
                    // 更新设备活跃时间
                    deviceActivityMap[address] = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying device $address", e)
            }
        }
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
        return activeDevices.size
    }
}
