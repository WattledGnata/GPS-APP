// @IgnoreFormatCheck
// 理由：本文件含 5 处 legacy property-name 违规（SERVICE_UUID / GPS_MAIN_UUID /
//       GPS_TIME_UUID / CCCD_UUID / _connectionState），其中 UUID 常量遵循
//       Kotlin coding convention `const/val` 的 ALL_CAPS 惯例，`_connectionState`
//       是 MutableStateFlow backing field 业界惯例 —— rename 会扩散到反射测试 +
//       其他引用，超出战役 G R1~R3 scope。评审方第六轮 commit 阶段 B 方案
//       批准此 ignore（2026-04-24）。其他格式违规（class comment / public fun
//       comment / import-order / when-else / trailing newline）已全部修到位。
package com.blazepush.core.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.blazepush.core.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * @description BLE 连接管理类。负责与 RaceChrono GPS 设备建立和维护 BLE 连接，
 *              涵盖 GATT 连接生命周期（connect / disconnect / close）、数据接收
 *              超时检测、state 流传导。战役 G R1 后作为 GATT 资源唯一所有者
 *              （ConnectionManager 已删除）；A40 后 close + null 统一在
 *              onConnectionStateChange(STATE_DISCONNECTED) 回调内执行。
 * @author haozhang93
 * @date 2026-04-24
 */
class BleConnection(
    private val context: Context,
    private val deviceAddress: String,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onDataReceived: (UUID, ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "BleConnection"

        @Suppress("PropertyName")
        private val SERVICE_UUID = UUID.fromString("00001ff8-0000-1000-8000-00805f9b34fb")

        @Suppress("PropertyName")
        private val GPS_MAIN_UUID = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")

        @Suppress("PropertyName")
        private val GPS_TIME_UUID = UUID.fromString("00000004-0000-1000-8000-00805f9b34fb")

        @Suppress("PropertyName")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        @Suppress("PropertyName")
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")

        @Suppress("PropertyName")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        // 超时时间设置
        private const val CONNECTION_TIMEOUT_MS = 15000L // 15秒连接超时
        /**
         * 解析 BLE Battery Level (0x2A19) 特征值。
         * @return 0..100 的百分比，非法值或空数据返回 null。
         */
        fun parseBatteryPercent(value: ByteArray?): Int? {
            val percent = value?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
            return percent.takeIf { it in 0..100 }
        }
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 连接状态
    @Suppress("PropertyName")
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 数据陈旧软状态：链路 CONNECTED，但动态 Main deadline 已过时置 true（不拆链）。
    // ble-connection-liveness spec R1：静默 ≠ 死链（真机固件无卫星不推帧），真死链走
    // onConnectionStateChange(STATE_DISCONNECTED) 回调判定。与 GpsData.isConnected 正交。
    @Suppress("PropertyName")
    private val _dataStale = MutableStateFlow(false)
    val dataStale: StateFlow<Boolean> = _dataStale.asStateFlow()

    // 外接 GPS 设备电量百分比（null = 无此服务 / 未读到 / 非法值）。
    // 连接后由 Battery Service (0x180F) 的 Battery Level (0x2A19) 特征提供。
    @Suppress("PropertyName")
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    // 数据接收时间记录
    @Volatile
    private var lastDataTime = 0L
    private var dataWatchdogJob: Job? = null
    private val mainFrameCadenceTracker = MainFrameCadenceTracker()
    private val mainFrameSignal = Channel<Unit>(capacity = Channel.CONFLATED)

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
                    Log.d(TAG, "已断开连接（回调）")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    cleanup()
                    // A40 统一释放路径：close + null 只在此回调内执行，
                    // 覆盖主动 disconnect / 超时触发 / 远端断连三条路径
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
                else -> {
                    // Android BT stack 目前仅返回上述 4 种 state；else 分支为 kt-check
                    // when-else-required 规则占位，无行为。
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

            // Battery Level CCCD 写完毕 → 读取当前电量
            if (status == BluetoothGatt.GATT_SUCCESS &&
                descriptor.characteristic?.uuid == BATTERY_LEVEL_UUID
            ) {
                descriptor.characteristic?.let { gatt.readCharacteristic(it) }
            }

            // 继续处理下一个 GPS 特征
            processNextDescriptor(gatt)

            // GPS 通知全部启用完成 → 追加 Battery Service 发现
            if (pendingCharacteristics.isEmpty() && !isWritingDescriptor &&
                _connectionState.value != ConnectionState.CONNECTED
            ) {
                setupBattery(gatt)
            }

            // 握手完成即判定已连接（不再依赖"收到第一帧数据"）：
            // 所有通知 CCCD 都写完（无 pending + 当前没在写）→ BLE 链路 + notify 已就绪 → CONNECTED。
            // 适配"无 GPS fix 不主动推数据"的设备（如 blazepush-peter，GPS 模块无卫星不输出）：
            // 否则室内卫星=0 时永远等不到数据帧 → connect() 连接超时 → 转圈重连。
            // 有数据后照常走 onCharacteristicChanged 更新 lastDataTime + 数据流超时监控。
            if (pendingCharacteristics.isEmpty() && !isWritingDescriptor &&
                _connectionState.value != ConnectionState.CONNECTED
            ) {
                Log.d(TAG, "所有通知启用完成，握手成功 → 判定已连接（不等数据帧）")
                _connectionState.value = ConnectionState.CONNECTED
                // 握手完成即开始数据新鲜度监控：冷启动无 fix（设备无卫星不推帧）时，
                // 按设备 Main 节拍计算 deadline，最迟 1 秒置 dataStale=true 而非拆链。
                // 收到首帧后由 handleCharacteristicChange 重置窗口（ble-connection-liveness spec R1）。
                mainFrameCadenceTracker.reset()
                lastDataTime = elapsedRealtimeMs()
                startDataWatchdog()
            }
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

        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Battery Level 通知：独立处理，不进入 RaceChronoParser 数据流
            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                val percent = parseBatteryPercent(value)
                if (percent != null) {
                    _batteryPercent.value = percent
                }
                return
            }

            logReceivedData(characteristic.uuid, value)
            onDataReceived(characteristic.uuid, value)

            // 只有 GPS Main 是新定位帧。GPS Time 通知/主动 read 即使持续活跃，
            // 也不能掩盖主定位特征静默。
            if (characteristic.uuid == GPS_MAIN_UUID) {
                val receivedAt = elapsedRealtimeMs()
                lastDataTime = receivedAt
                mainFrameCadenceTracker.onMainFrame(receivedAt)
                _dataStale.value = false
                mainFrameSignal.trySend(Unit)
            }

            // 收到数据就认为连接成功
            if (_connectionState.value != ConnectionState.CONNECTED) {
                Log.d(TAG, "收到数据，连接成功")
                _connectionState.value = ConnectionState.CONNECTED
            }

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

    /**
     * 建立 BLE GATT 连接。状态转为 CONNECTING，启动连接超时检测（15s），
     * 调 `device.connectGatt` 开始异步连接；后续状态变化由 gattCallback 驱动。
     */
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

    /**
     * 主动断开 BLE GATT 连接。战役 G A40：只触发异步 `gatt.disconnect()`，
     * `close + null + state 赋值` 由 `onConnectionStateChange(STATE_DISCONNECTED)`
     * 回调统一处理，避免 "close 后仍收回调访问已关闭 gatt" 的厂商差异行为。
     */
    fun disconnect() {
        cleanup()
        bluetoothGatt?.disconnect()
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

    /**
     * 发现并配置 Battery Service (0x180F)。在 GPS 通知启用完毕后调用，
     * 避免与 GPS CCCD 写操作产生 GATT 并发冲突。
     * 优先订阅 Notify/Indicate；仅支持 READ 时主动读一次。
     */
    private fun setupBattery(gatt: BluetoothGatt) {
        val service = gatt.getService(BATTERY_SERVICE_UUID)
        if (service == null) {
            Log.d(TAG, "Battery Service (0x180F) not found — device has no battery reporting")
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

    private fun startDataWatchdog() {
        if (dataWatchdogJob?.isActive == true) return
        dataWatchdogJob = scope.launch {
            while (true) {
                if (_dataStale.value) {
                    // 静默后不轮询；下一 Main 帧会清 stale 并通过 conflated signal 唤醒。
                    mainFrameSignal.receive()
                    continue
                }
                val timeoutMs = mainFrameCadenceTracker.currentSilenceTimeoutMs()
                val remainingMs =
                    (lastDataTime + timeoutMs - elapsedRealtimeMs()).coerceAtLeast(0L)
                if (remainingMs > 0L) {
                    val frameArrived = withTimeoutOrNull(remainingMs) {
                        mainFrameSignal.receive()
                        true
                    } ?: false
                    if (frameArrived) continue
                }
                val currentTimeoutMs = mainFrameCadenceTracker.currentSilenceTimeoutMs()
                if (!_dataStale.value &&
                    elapsedRealtimeMs() - lastDataTime >= currentTimeoutMs
                ) {
                    // ble-connection-liveness spec R1：数据静默 MUST NOT 拆链。
                    // 真机固件（无卫星不推帧）丢星 = 蓝牙静默，曾被此处误判死链 disconnect →
                    // 跑圈过隧道/桥洞丢几秒星就掉线且不自愈。改为只置软陈旧状态：链路保持
                    // CONNECTED，等卫星恢复推帧时 handleCharacteristicChange 自动清除。
                    // 真死链（设备关机/出范围）由 onConnectionStateChange(STATE_DISCONNECTED)
                    // 经 BLE supervision timeout 判定（A40 统一释放路径，不在此处拆）。
                    Log.w(TAG, "数据静默：标记陈旧（不拆链，链路保持 CONNECTED）")
                    _dataStale.value = true
                }
            }
        }
    }

    private fun cleanup() {
        dataWatchdogJob?.cancel()
        dataWatchdogJob = null
        while (mainFrameSignal.tryReceive().isSuccess) {
            // 清除复用 BleConnection 时可能残留的 conflated 唤醒信号。
        }
        mainFrameCadenceTracker.reset()
        pendingCharacteristics.clear()
        isWritingDescriptor = false
        lastDataTime = 0L
        _batteryPercent.value = null
    }
}
