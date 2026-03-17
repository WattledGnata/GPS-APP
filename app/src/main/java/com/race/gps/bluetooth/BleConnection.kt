package com.race.gps.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * BLE连接管理类
 * 负责与RaceChrono GPS设备建立和维护BLE连接
 */
class BleConnection(
    private val context: Context,
    private val deviceAddress: String,
    private val onDataReceived: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BleConnection"
        private val SERVICE_UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
        private val GPS_MAIN_UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        private val GPS_TIME_UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothGatt: BluetoothGatt? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 请求更大的MTU以支持28字节数据传输
                // 默认MTU是23，实际数据只有20字节
                // ��们需要至少31字节的MTU来传输28字节数据
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val mtuRequested = gatt.requestMtu(31)
                    Log.d(TAG, "Requesting MTU=31, result: $mtuRequested")
                } else {
                    enableNotifications(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
                enableNotifications(gatt)
            } else {
                Log.e(TAG, "Failed to change MTU, status: $status")
                // 即使MTU请求失败，也尝试启用通知
                enableNotifications(gatt)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            logReceivedData(characteristic.uuid, value)
            onDataReceived(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            logReceivedData(characteristic.uuid, characteristic.value)
            onDataReceived(characteristic.value)
        }

        private fun logReceivedData(uuid: UUID, data: ByteArray) {
            val hexDump = data.joinToString("") { "%02X".format(it) }
            when (uuid) {
                GPS_MAIN_UUID -> {
                    Log.d(TAG, "Received GPS Main Data (${data.size} bytes): $hexDump")
                }
                GPS_TIME_UUID -> {
                    Log.d(TAG, "Received GPS Time Data (${data.size} bytes): $hexDump")
                }
                else -> {
                    Log.d(TAG, "Received unknown characteristic data: $hexDump")
                }
            }
        }
    }

    fun connect() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        listOf(GPS_MAIN_UUID, GPS_TIME_UUID).forEach { charUuid ->
            val characteristic = service.getCharacteristic(charUuid) ?: return@forEach
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return@forEach
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }
}
