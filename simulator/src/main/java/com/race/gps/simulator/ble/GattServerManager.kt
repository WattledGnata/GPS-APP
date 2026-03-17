package com.race.gps.simulator.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    }

    private var gattServer: BluetoothGattServer? = null
    private var mainDataCharacteristic: BluetoothGattCharacteristic? = null
    private var timeDataCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectedDevices = MutableStateFlow<Set<String>>(emptySet())
    val connectedDevices: StateFlow<Set<String>> = _connectedDevices

    private val _isServerReady = MutableStateFlow(false)
    val isServerReady: StateFlow<Boolean> = _isServerReady

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
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: ${device.address}")
                _connectedDevices.value = _connectedDevices.value + device.address
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: ${device.address}")
                _connectedDevices.value = _connectedDevices.value - device.address
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
            BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        // 创建GPS时间特征值
        timeDataCharacteristic = BluetoothGattCharacteristic(
            GPS_TIME_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        timeDataCharacteristic?.addDescriptor(
            BluetoothGattDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        )

        service.addCharacteristic(mainDataCharacteristic)
        service.addCharacteristic(timeDataCharacteristic)

        val success = gattServer?.addService(service) ?: false
        Log.d(TAG, "Service added: $success")
        return success
    }

    /**
     * 停止GATT服务器
     */
    @SuppressLint("MissingPermission")
    fun stopServer() {
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
        _connectedDevices.value.forEach { address ->
            val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            val enabled = mainDataCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.value?.get(0) ?: 0
            if ((enabled.toInt() and 0x01) == 0x01) {
                gattServer?.notifyCharacteristicChanged(device, mainDataCharacteristic, false)
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
        _connectedDevices.value.forEach { address ->
            val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
            val enabled = timeDataCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)?.value?.get(0) ?: 0
            if ((enabled.toInt() and 0x01) == 0x01) {
                gattServer?.notifyCharacteristicChanged(device, timeDataCharacteristic, false)
            }
        }
    }
}
