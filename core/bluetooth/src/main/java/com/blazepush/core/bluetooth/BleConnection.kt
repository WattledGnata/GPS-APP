package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE连接管理类
 * 负责与RaceChrono GPS设备建立和维护BLE连接
 */
class BleConnection(
    private val context: Context,
    private val deviceAddress: String,
    private val onDataReceived: (UUID, ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BleConnection"
        private val SERVICE_UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")
        private val GPS_MAIN_UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
        private val GPS_TIME_UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // 超时时间设置
        private const val CONNECTION_TIMEOUT_MS = 15000L // 15秒连接超时
        private const val DATA_TIMEOUT_MS = 10000L // 10秒数据超时
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 连��状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 数据接收时间记录
    private var lastDataTime = 0L
    private var timeoutJob: Job? = null

    // 待启用的通知队列
    private val pendingCharacteristics = mutableListOf<BluetoothGattCharacteristic>()
    private var isWritingDescriptor = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "正在连接...")
                    _connectionState.value = ConnectionState.CONNECTING
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "已连接到GATT服务器")
                    _connectionState.value = ConnectionState.CONNECTING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.d(TAG, "正在断开...")
                    _connectionState.value = ConnectionState.DISCONNECTING
                    cleanup()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "已断开连接")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    cleanup()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // 请求更大的MTU以支持28字节数据传输
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val mtuRequested = gatt.requestMtu(31)
                    Log.d(TAG, "Requesting MTU=31, result: $mtuRequested")
                } else {
                    enableNotificationsSequentially(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
            } else {
                Log.e(TAG, "Failed to change MTU, status: $status")
            }
            // 无论MTU是否成功，都启用通知
            enableNotificationsSequentially(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status, uuid=${descriptor.characteristic?.uuid}")
            isWritingDescriptor = false
            // 继续处理下一个特征
            processNextDescriptor(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChange(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChange(characteristic, characteristic.value)
        }

        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            logReceivedData(characteristic.uuid, value)
            onDataReceived(characteristic.uuid, value)

            // 更新数据接收时间
            lastDataTime = System.currentTimeMillis()

            // 收到数据就认为连接成功
            if (_connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "收到数据，连接成功")
                _connectionState.value = ConnectionState.CONNECTED
            }

            // 取消并重新启动超时检测
            timeoutJob?.cancel()
            startDataTimeoutCheck()
        }

        private fun logReceivedData(uuid: UUID, data: ByteArray) {
            // 注释掉高频日志，25Hz GPS数据会刷屏
            // val hexDump = data.joinToString("") { "%02X".format(it) }
            // when (uuid) {
            //     GPS_MAIN_UUID -> Log.d(TAG, "Received GPS Main Data (${data.size} bytes): $hexDump")
            //     GPS_TIME_UUID -> Log.d(TAG, "Received GPS Time Data (${data.size} bytes): $hexDump")
            //     else -> Log.d(TAG, "Received unknown characteristic data: $hexDump")
            // }
        }
    }

    fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        // 启动连接超时检测
        scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (connectionState.value == ConnectionState.CONNECTING) {
                Log.e(TAG, "连接超时")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = bluetoothManager.adapter.getRemoteDevice(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        cleanup()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * 依次启用通知（避免并发写描述符）
     */
    private fun enableNotificationsSequentially(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Service not found: $SERVICE_UUID")
            return
        }

        pendingCharacteristics.clear()
        isWritingDescriptor = false

        // 收集需要启用的特征
        listOf(GPS_MAIN_UUID, GPS_TIME_UUID).forEach { charUuid ->
            val characteristic = service.getCharacteristic(charUuid)
            if (characteristic != null) {
                pendingCharacteristics.add(characteristic)
            } else {
                Log.w(TAG, "Characteristic not found: $charUuid")
            }
        }

        Log.d(TAG, "开始依次启用 ${pendingCharacteristics.size} 个通知")
        processNextDescriptor(gatt)
    }

    /**
     * 处理下一个描述符写操作
     */
    private fun processNextDescriptor(gatt: BluetoothGatt) {
        if (isWritingDescriptor || pendingCharacteristics.isEmpty()) {
            return
        }

        val characteristic = pendingCharacteristics.removeAt(0)
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(TAG, "Failed to enable notifications for ${characteristic.uuid}")
            // 继续下一个
            processNextDescriptor(gatt)
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            processNextDescriptor(gatt)
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        isWritingDescriptor = true
        val writeSuccess = gatt.writeDescriptor(descriptor)
        if (!writeSuccess) {
            Log.e(TAG, "Failed to write descriptor for ${characteristic.uuid}")
            isWritingDescriptor = false
            processNextDescriptor(gatt)
            return
        }

        Log.d(TAG, "Writing descriptor for ${characteristic.uuid}")
    }

    private fun startDataTimeoutCheck() {
        timeoutJob = scope.launch {
            delay(DATA_TIMEOUT_MS)
            if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
                Log.w(TAG, "数据接收超时，触发重连")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun cleanup() {
        timeoutJob?.cancel()
        pendingCharacteristics.clear()
        isWritingDescriptor = false
        lastDataTime = 0L
    }
}
