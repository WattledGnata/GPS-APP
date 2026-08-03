// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（class comment / public fun comment /
//       import-order / trailing newline 等）。本战役 G R6 仅在 else 分支追加
//       startScan() fallback 一句，rename/补注释其他 legacy 内容超出 R6 scope。
//       评审方 2026-04-24 commit 阶段 B 方案批准此 ignore。
package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BLE设备管理器
 * 统一管理设备扫描、连接和自动重连逻辑
 */
class BleDeviceManager(
    private val context: Context,
    private val bluetoothDataSource: BluetoothDataSource,
    // ble-device-memory round（design Decision 1）：Koin 闭包注入设备记忆能力，
    // core/bluetooth 不依赖 core/data（模块图不动）；默认 null 保持既有构造兼容。
    private val lastDeviceProvider: (suspend () -> String?)? = null,
    private val onDeviceConnected: (suspend (address: String, name: String?) -> Unit)? = null,
    private val scanner: BleScanner = BleDeviceScanner(context),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoReconnectOnInit: Boolean = true,
    private val scanPermissionGranted: () -> Boolean = {
        PermissionChecker.hasAllRequiredPermissions(context)
    },
) {
    companion object {
        private const val TAG = "BleDeviceManager"
        private const val RECONNECT_TIMEOUT_MS = 10000L // 10秒重连超时
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(dispatcher.limitedParallelism(1) + SupervisorJob())

    // 连接状态 (代理自BluetoothDataSource)
    val connectionState = bluetoothDataSource.connectionState

    // 扫描状态 (代理自BleDeviceScanner)
    val isScanning = scanner.isScanning

    // 扫描结果 (代理自BleDeviceScanner)
    val scanResults = scanner.scanResults

    private var autoReconnectJob: Job? = null
    private var targetResolutionJob: Job? = null
    private val intentLock = Any()
    private var connectionIntentToken = 0L
    @Volatile private var connectionIntentEnabled = true
    @Volatile private var currentTargetAddress: String? = null

    // ble-device-memory（design Decision 2）：最后一次经 manager 发起的连接意图（address + 扫描广播名）。
    // 仅 connect() 设置/覆盖，首次 CONNECTED 落库后清空；不在 DISCONNECTED 清——
    // connect 发起 → 连接超时 → BluetoothDataSource 退避重连 → 最终 CONNECTED 时仍正确落表。
    @Volatile
    private var pendingPersist: Pair<String, String?>? = null

    init {
        bluetoothDataSource.installBeforeConnectAttempt { scanner.stopScan() }
        // ble-device-memory：首次 CONNECTED 时把 pending 设备经闭包落库（FileLogger 由 feature 层闭包侧落）
        scope.launch {
            connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    val pending = pendingPersist
                    if (pending != null) {
                        pendingPersist = null
                        try {
                            onDeviceConnected?.invoke(pending.first, pending.second)
                        } catch (e: Exception) {
                            Log.e(TAG, "设备记录持久化失败", e)
                        }
                    }
                }
            }
        }
        scope.launch {
            scanResults.collect { devices ->
                val target = currentTargetAddress ?: return@collect
                if (connectionIntentEnabled && devices.any { it.address == target }) {
                    stopScan()
                    bluetoothDataSource.requestImmediateReconnect("scan target discovered")
                }
            }
        }
        // 自动重连上次设备
        if (autoReconnectOnInit) autoReconnectLastDevice()
    }

    /**
     * 自动重连上次使用的设备
     */
    fun autoReconnectLastDevice() {
        val token = synchronized(intentLock) {
            if (autoReconnectJob != null || currentTargetAddress != null) {
                Log.w(TAG, "自动重连已在进行中或已有目标")
                return
            }
            connectionIntentEnabled = true
            ++connectionIntentToken
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // ble-device-memory（design Decision 6）：经 Koin 闭包查最近成功连接设备
                // （单表真相源 bluetooth_devices，lastConnectedAtMs 最大者；删记录即遗忘）。
                val lastDeviceAddress: String? = lastDeviceProvider?.invoke()
                if (!ownsIntent(token)) return@launch
                Log.d(TAG, "cold-start target=${lastDeviceAddress ?: "none"}")

                if (lastDeviceAddress != null) {
                    Log.d(TAG, "尝试自动重连设备: $lastDeviceAddress")

                    // 等待一小段时间确保蓝牙就绪
                    delay(1000)
                    if (!ownsIntent(token)) return@launch

                    // 尝试连接（经自身 connect 统一 pending 落库机制，design Decision 2）
                    connectForIntent(lastDeviceAddress, null, token)

                    // 等待连接结果
                    var waited = 0L
                    while (waited < RECONNECT_TIMEOUT_MS) {
                        delay(500)
                        waited += 500
                        if (!ownsIntent(token, lastDeviceAddress)) return@launch

                        if (connectionState.value == ConnectionState.CONNECTED) {
                            Log.d(TAG, "自动重连成功")
                            return@launch
                        }
                    }

                    // 超时未连接成功
                    if (!ownsIntent(token, lastDeviceAddress)) return@launch
                    Log.w(TAG, "自动重连超时，开始扫描其他设备")
                    startScan()
                } else {
                    // A29：冷启动 else 分支 fallback 扫描（当前 lastDeviceAddress
                    // 硬编码 null 时必走此分支）。用户期望"打开 app 看到设备列表"，
                    // 战役 G 前只 log 不 scan 导致用户必须手动点"扫描"按钮。
                    if (!ownsIntent(token)) return@launch
                    Log.d(TAG, "没有上次连接的设备记录，fallback 到扫描")
                    startScan()
                }
            } catch (e: Exception) {
                if (ownsIntent(token)) {
                    Log.e(TAG, "自动重连失败", e)
                    startScan()
                }
            } finally {
                synchronized(intentLock) {
                    if (connectionIntentToken == token) {
                        autoReconnectJob = null
                    }
                }
            }
        }
        synchronized(intentLock) {
            if (!connectionIntentEnabled || connectionIntentToken != token) {
                job.cancel()
                return
            }
            autoReconnectJob = job
        }
        job.start()
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        if (connectionState.value in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) {
            Log.d(TAG, "skip scan while state=${connectionState.value}")
            return
        }
        if (!scanPermissionGranted()) {
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
     * @param deviceName 扫描广播名（手动连接时传入）；冷启动自动连无广播名传 null
     *（落库时 COALESCE 保留已存固件名）。
     */
    fun connect(deviceAddress: String, deviceName: String? = null) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        val token = synchronized(intentLock) {
            connectionIntentToken++
            connectionIntentEnabled = true
            currentTargetAddress = deviceAddress
            pendingPersist = deviceAddress to deviceName
            autoReconnectJob?.cancel()
            autoReconnectJob = null
            targetResolutionJob?.cancel()
            targetResolutionJob = null
            connectionIntentToken
        }
        scope.launch {
            connectForIntent(deviceAddress, deviceName, token)
        }
    }

    private fun connectForIntent(deviceAddress: String, deviceName: String?, token: Long) {
        if (!ownsIntent(token)) return
        synchronized(intentLock) {
            if (!ownsIntent(token)) return
            currentTargetAddress = deviceAddress
            pendingPersist = deviceAddress to deviceName
        }
        try {
            stopScan()
            if (!ownsIntent(token, deviceAddress)) return
            Log.d(TAG, "停止扫描，开始连接")
            bluetoothDataSource.connect(deviceAddress)
            Log.d(TAG, "bluetoothDataSource.connect() 调用完成")
        } catch (e: Exception) {
            Log.e(TAG, "连接失败", e)
        }
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        synchronized(intentLock) {
            connectionIntentToken++
            connectionIntentEnabled = false
            currentTargetAddress = null
            pendingPersist = null
            autoReconnectJob?.cancel()
            autoReconnectJob = null
            targetResolutionJob?.cancel()
            targetResolutionJob = null
        }
        stopScan()
        bluetoothDataSource.disconnect()
    }

    /** Lifecycle, lap-session and Bluetooth-adapter signals converge here. */
    fun requestImmediateReconnect(reason: String) {
        val token = synchronized(intentLock) {
            if (!connectionIntentEnabled) return
            connectionIntentToken
        }
        stopScan()
        if (!ownsIntent(token)) return
        if (bluetoothDataSource.requestImmediateReconnect(reason)) return
        if (connectionState.value in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)) return
        if (currentTargetAddress != null || targetResolutionJob?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val saved = lastDeviceProvider?.invoke()
            if (saved != null && ownsIntent(token)) {
                connectForIntent(saved, null, token)
            }
        }
        synchronized(intentLock) {
            if (!ownsIntent(token) || currentTargetAddress != null || targetResolutionJob != null) {
                job.cancel()
                return
            }
            targetResolutionJob = job
        }
        job.invokeOnCompletion {
            synchronized(intentLock) {
                if (targetResolutionJob === job) targetResolutionJob = null
            }
        }
        job.start()
    }

    /** Forget future reconnect without tearing down an already CONNECTED GATT. */
    fun forget(deviceAddress: String) {
        val invalidate = synchronized(intentLock) {
            if (currentTargetAddress != deviceAddress && currentTargetAddress != null) {
                false
            } else {
                connectionIntentToken++
                connectionIntentEnabled = false
                currentTargetAddress = null
                pendingPersist = null
                autoReconnectJob?.cancel()
                autoReconnectJob = null
                targetResolutionJob?.cancel()
                targetResolutionJob = null
                true
            }
        }
        if (invalidate) {
            stopScan()
            bluetoothDataSource.forget(deviceAddress)
        }
    }

    private fun ownsIntent(token: Long, target: String? = null): Boolean =
        synchronized(intentLock) {
            connectionIntentEnabled &&
                connectionIntentToken == token &&
                (target == null || currentTargetAddress == target)
        }

    /**
     * 清理资源
     */
    fun cleanup() {
        scanner.cleanup()
    }
}
