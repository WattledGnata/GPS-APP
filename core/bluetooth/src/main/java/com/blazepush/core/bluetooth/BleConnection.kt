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
import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.BleHandshakeStage
import com.blazepush.core.domain.model.BleHandshakeState
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsChannelSubscriptionState
import com.blazepush.core.domain.model.TimingHandshakeState
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
import java.io.File
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
    private val connectionGeneration: Long = 0L,
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

    // Battery capability 是唯一真相源；Pending/Unsupported/Failed 不再折叠成 null。
    @Suppress("PropertyName")
    private val _batteryCapability = MutableStateFlow<BatteryCapabilityState>(
        BatteryCapabilityState.Pending,
    )
    val batteryCapability: StateFlow<BatteryCapabilityState> = _batteryCapability.asStateFlow()

    @Suppress("PropertyName")
    private val _handshakeState = MutableStateFlow(
        BleHandshakeState(connectionGeneration = connectionGeneration),
    )
    val handshakeState: StateFlow<BleHandshakeState> = _handshakeState.asStateFlow()

    // 数据接收时间记录
    @Volatile
    private var lastDataTime = 0L
    private var dataWatchdogJob: Job? = null
    private val mainFrameCadenceTracker = MainFrameCadenceTracker()
    private val mainFrameSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    // 待启用的通知队列
    private val pendingCharacteristics = mutableListOf<BluetoothGattCharacteristic>()
    private var isWritingDescriptor = false
    private var activeDescriptorUuid: UUID? = null
    private var batterySetupStarted = false
    private var batteryNotificationsSubscribed = false
    private var gpsTimeRetryJob: Job? = null
    @Volatile
    private var timingHandshakeState = TimingHandshakeState.WAITING_MAIN

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
                logGpsCharacteristicCapabilities(gatt)
                // 请求更大的MTU以支持28字节数据传输
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    val mtuRequested = gatt.requestMtu(31)
                    Log.d(TAG, "Requesting MTU=31, result: $mtuRequested")
                    if (!mtuRequested) enableNotificationsSequentially(gatt)
                } else {
                    enableNotificationsSequentially(gatt)
                }
            } else {
                failGpsHandshake(gatt, null)
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
            val characteristicUuid = descriptor.characteristic?.uuid
            Log.d(TAG, "onDescriptorWrite: status=$status, uuid=$characteristicUuid")
            if (characteristicUuid == null || characteristicUuid != activeDescriptorUuid) {
                Log.d(TAG, "ignore out-of-order descriptor callback uuid=$characteristicUuid")
                return
            }
            isWritingDescriptor = false
            activeDescriptorUuid = null
            when (characteristicUuid) {
                GPS_MAIN_UUID, GPS_TIME_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failGpsHandshake(gatt, characteristicUuid)
                        return
                    }
                    markGpsChannelSubscribed(checkNotNull(characteristicUuid))
                    processNextDescriptor(gatt)
                }
                BATTERY_LEVEL_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        _batteryCapability.value = BatteryCapabilityState.Failed
                    } else {
                        batteryNotificationsSubscribed = true
                        val characteristic = descriptor.characteristic
                        if (characteristic == null || !gatt.readCharacteristic(characteristic)) {
                            _batteryCapability.value = BatteryCapabilityState.Failed
                        }
                    }
                    finishHandshake()
                }
                else -> Unit
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
            handleCharacteristicRead(characteristic, value, status)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(characteristic, characteristic.value, status)
        }

        private fun handleCharacteristicRead(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            when (characteristic.uuid) {
                BATTERY_LEVEL_UUID -> {
                    val percent = if (status == BluetoothGatt.GATT_SUCCESS) {
                        parseBatteryPercent(value)
                    } else {
                        null
                    }
                    _batteryCapability.value = percent?.let(BatteryCapabilityState::Available)
                        ?: BatteryCapabilityState.Failed
                    if (percent != null) Log.d(TAG, "Battery level read: $percent%")
                }
                GPS_TIME_UUID -> {
                    val hexDump = value.joinToString("") { "%02X".format(it) }
                    writeProtocolProbeLog(
                        "GPS Time probe read: status=$status length=${value.size} value=$hexDump",
                    )
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        onDataReceived(characteristic.uuid, value)
                    }
                }
            }
        }

        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Battery Level 通知：独立处理，不进入 RaceChronoParser 数据流
            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                if (!batteryNotificationsSubscribed) return
                val percent = parseBatteryPercent(value)
                _batteryCapability.value = percent?.let(BatteryCapabilityState::Available)
                    ?: BatteryCapabilityState.Failed
                return
            }

            if (characteristic.uuid == GPS_MAIN_UUID &&
                _handshakeState.value.main != GpsChannelSubscriptionState.SUBSCRIBED
            ) return
            if (characteristic.uuid == GPS_TIME_UUID &&
                _handshakeState.value.time != GpsChannelSubscriptionState.SUBSCRIBED
            ) return

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
            failGpsHandshake(gatt, null)
            return
        }

        pendingCharacteristics.clear()
        isWritingDescriptor = false
        activeDescriptorUuid = null
        batterySetupStarted = false
        batteryNotificationsSubscribed = false
        _batteryCapability.value = BatteryCapabilityState.Pending
        _handshakeState.value = BleHandshakeState(connectionGeneration = connectionGeneration)

        // 收集需要启用的特征
        listOf(GPS_MAIN_UUID, GPS_TIME_UUID).forEach { charUuid ->
            val characteristic = service.getCharacteristic(charUuid)
            if (characteristic != null) {
                pendingCharacteristics.add(characteristic)
            } else {
                Log.e(TAG, "Mandatory GPS characteristic not found: $charUuid")
                failGpsHandshake(gatt, charUuid)
                return
            }
        }

        Log.d(TAG, "开始依次启用 ${pendingCharacteristics.size} 个通知")
        processNextDescriptor(gatt)
    }

    /**
     * 处理下一个描述符写操作
     */
    private fun processNextDescriptor(gatt: BluetoothGatt) {
        if (isWritingDescriptor) {
            return
        }
        if (pendingCharacteristics.isEmpty()) {
            markGpsChannelsReady()
            setupBattery(gatt)
            return
        }

        val characteristic = pendingCharacteristics.removeAt(0)
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(TAG, "Failed to enable notifications for ${characteristic.uuid}")
            failGpsHandshake(gatt, characteristic.uuid)
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            failGpsHandshake(gatt, characteristic.uuid)
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        isWritingDescriptor = true
        activeDescriptorUuid = characteristic.uuid
        val writeSuccess = gatt.writeDescriptor(descriptor)
        if (!writeSuccess) {
            Log.e(TAG, "Failed to write descriptor for ${characteristic.uuid}")
            isWritingDescriptor = false
            activeDescriptorUuid = null
            failGpsHandshake(gatt, characteristic.uuid)
            return
        }

        Log.d(TAG, "Writing descriptor for ${characteristic.uuid}")
    }

    internal fun updateTimingHandshakeState(state: TimingHandshakeState) {
        timingHandshakeState = state
        if (state == TimingHandshakeState.WAITING_TIME) {
            startGpsTimeRetryLoop()
        } else {
            gpsTimeRetryJob?.cancel()
            gpsTimeRetryJob = null
        }
    }

    private fun startGpsTimeRetryLoop() {
        if (gpsTimeRetryJob?.isActive == true) return
        val gatt = bluetoothGatt ?: return
        gpsTimeRetryJob = scope.launch {
            var attempt = 0
            while (
                bluetoothGatt === gatt &&
                timingHandshakeState == TimingHandshakeState.WAITING_TIME
            ) {
                val delayMs = when (attempt) {
                    0 -> 1_500L
                    1 -> 2_000L
                    else -> 3_000L
                }
                delay(delayMs)
                if (
                    bluetoothGatt !== gatt ||
                    timingHandshakeState != TimingHandshakeState.WAITING_TIME
                ) {
                    break
                }
                val characteristic =
                    gatt.getService(SERVICE_UUID)?.getCharacteristic(GPS_TIME_UUID)
                if (characteristic == null) {
                    writeProtocolProbeLog(
                        "GPS Time retry #${attempt + 1}: characteristic missing",
                    )
                    attempt++
                    continue
                }
                val supportsRead =
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                val started = supportsRead && gatt.readCharacteristic(characteristic)
                writeProtocolProbeLog(
                    "GPS Time retry #${attempt + 1}: delayMs=$delayMs " +
                        "supportsRead=$supportsRead started=$started",
                )
                attempt++
            }
        }
    }

    private fun logGpsCharacteristicCapabilities(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        listOf(GPS_MAIN_UUID, GPS_TIME_UUID).forEach { uuid ->
            val characteristic = service.getCharacteristic(uuid)
            if (characteristic == null) {
                writeProtocolProbeLog("GPS capability: uuid=$uuid missing")
            } else {
                writeProtocolProbeLog(
                    "GPS capability: uuid=$uuid properties=0x${characteristic.properties.toString(16)} " +
                        "read=${characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0} " +
                        "notify=${characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0} " +
                        "indicate=${characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0} " +
                        "cccd=${characteristic.getDescriptor(CCCD_UUID) != null}",
                )
            }
        }
    }

    private fun writeProtocolProbeLog(message: String) {
        Log.d(TAG, message)
        runCatching {
            File(context.filesDir, "ble_protocol_probe.txt")
                .appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    /**
     * 发现并配置 Battery Service (0x180F)。在 GPS 通知启用完毕后调用，
     * 避免与 GPS CCCD 写操作产生 GATT 并发冲突。
     * 优先订阅 Notify/Indicate；仅支持 READ 时主动读一次。
     */
    private fun setupBattery(gatt: BluetoothGatt) {
        if (batterySetupStarted) return
        batterySetupStarted = true
        _handshakeState.value = _handshakeState.value.copy(stage = BleHandshakeStage.PROBING_BATTERY)
        val service = gatt.getService(BATTERY_SERVICE_UUID)
        if (service == null) {
            Log.d(TAG, "Battery Service (0x180F) not found — device has no battery reporting")
            _batteryCapability.value = BatteryCapabilityState.Unsupported
            finishHandshake()
            return
        }
        val characteristic = service.getCharacteristic(BATTERY_LEVEL_UUID)
        if (characteristic == null) {
            Log.d(TAG, "Battery Level (0x2A19) not found")
            _batteryCapability.value = BatteryCapabilityState.Unsupported
            finishHandshake()
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
                _batteryCapability.value = BatteryCapabilityState.Failed
                finishHandshake()
                return
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD not found for Battery Level")
                _batteryCapability.value = BatteryCapabilityState.Failed
                finishHandshake()
                return
            }
            cccd.value = if (supportsNotify) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            isWritingDescriptor = true
            activeDescriptorUuid = BATTERY_LEVEL_UUID
            val writeSuccess = gatt.writeDescriptor(cccd)
            if (!writeSuccess) {
                Log.e(TAG, "Failed to write Battery CCCD")
                isWritingDescriptor = false
                activeDescriptorUuid = null
                _batteryCapability.value = BatteryCapabilityState.Failed
                finishHandshake()
            }
        } else {
            Log.d(TAG, "Battery Level: no notify/indicate — read once")
            if (!gatt.readCharacteristic(characteristic)) {
                _batteryCapability.value = BatteryCapabilityState.Failed
            }
            finishHandshake()
        }
    }

    private fun markGpsChannelSubscribed(uuid: UUID) {
        _handshakeState.value = when (uuid) {
            GPS_MAIN_UUID -> _handshakeState.value.copy(
                stage = BleHandshakeStage.PENDING_TIME,
                main = GpsChannelSubscriptionState.SUBSCRIBED,
            )
            GPS_TIME_UUID -> _handshakeState.value.copy(
                stage = BleHandshakeStage.PROBING_BATTERY,
                time = GpsChannelSubscriptionState.SUBSCRIBED,
            )
            else -> _handshakeState.value
        }
    }

    private fun failGpsHandshake(gatt: BluetoothGatt, uuid: UUID?) {
        _handshakeState.value = _handshakeState.value.copy(
            stage = BleHandshakeStage.FAILED,
            main = if (uuid == null || uuid == GPS_MAIN_UUID) {
                GpsChannelSubscriptionState.FAILED
            } else {
                _handshakeState.value.main
            },
            time = if (uuid == null || uuid == GPS_TIME_UUID) {
                GpsChannelSubscriptionState.FAILED
            } else {
                _handshakeState.value.time
            },
        )
        _connectionState.value = ConnectionState.DISCONNECTED
        gatt.disconnect()
    }

    private fun finishHandshake() {
        _handshakeState.value = _handshakeState.value.copy(stage = BleHandshakeStage.COMPLETE)
        markGpsChannelsReady()
    }

    private fun markGpsChannelsReady() {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        Log.d(TAG, "Main/Time 订阅完成 → 判定已连接；Battery 继续串行探测")
        _connectionState.value = ConnectionState.CONNECTED
        mainFrameCadenceTracker.reset()
        lastDataTime = elapsedRealtimeMs()
        startDataWatchdog()
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
        activeDescriptorUuid = null
        lastDataTime = 0L
        _batteryCapability.value = BatteryCapabilityState.Pending
        batterySetupStarted = false
        batteryNotificationsSubscribed = false
        gpsTimeRetryJob?.cancel()
        gpsTimeRetryJob = null
        timingHandshakeState = TimingHandshakeState.WAITING_MAIN
    }
}
