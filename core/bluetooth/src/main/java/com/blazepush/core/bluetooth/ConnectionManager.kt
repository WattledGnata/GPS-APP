package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 连接管理器 - 处理假连接检测和��动重连
 */
class ConnectionManager(
    private val context: Context,
    private val bluetoothDataSource: BluetoothDataSource
) {
    companion object {
        private const val TAG = "ConnectionManager"
        private const val FAKE_CONNECTION_CHECK_INTERVAL = 5000L // 5秒检查一次
        private const val INACTIVE_THRESHOLD = 10000L // 10秒无活动算假连接
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 假连接检测标志
    private var isCheckingFakeConnection = false
    private var checkJob: Job? = null

    // 上次数据接收时间
    private var lastDataTime = 0L

    // 当前连接的设备地址
    private var currentDeviceAddress: String? = null

    // 连接状态观察
    private val _isFakeConnection = MutableStateFlow(false)
    val isFakeConnection: StateFlow<Boolean> = _isFakeConnection.asStateFlow()

    init {
        // 监听GPS数据流以检测数据接收
        scope.launch {
            bluetoothDataSource.dataFlow.collect { gpsData ->
                if (gpsData.isConnected && gpsData.timestamp > 0) {
                    lastDataTime = gpsData.timestamp
                    checkForFakeConnection()
                }
            }
        }

        // 监听连接状态变化，记录当前设备地址
        scope.launch {
            bluetoothDataSource.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED && currentDeviceAddress == null) {
                    // 连接成功时，从 dataFlow 中获取地址（通过 timestamp 非零来判断有数据）
                }
            }
        }

        // 定期检查假连接
        startFakeConnectionCheck()
    }

    /**
     * 设置当前设备地址（由外部调用，传入要连接的设备地址）
     */
    fun setCurrentDevice(address: String) {
        currentDeviceAddress = address
    }

    /**
     * 开始检查假连接
     */
    private fun startFakeConnectionCheck() {
        if (isCheckingFakeConnection) return

        isCheckingFakeConnection = true

        checkJob = scope.launch {
            while (isActive) {
                delay(FAKE_CONNECTION_CHECK_INTERVAL)
                checkForFakeConnection()
            }
        }
    }

    /**
     * 检查是否有假连接
     */
    private fun checkForFakeConnection() {
        val currentState = bluetoothDataSource.connectionState.value
        val now = System.currentTimeMillis()

        // 如果显示已连接但没有数据接收或很久没有数据
        if (currentState == ConnectionState.CONNECTED) {
            if (lastDataTime == 0L) {
                // 从未收到数据
                _isFakeConnection.value = true
                Log.w(TAG, "检测到假连接：已连接但从未收到数据")
            } else if (now - lastDataTime > INACTIVE_THRESHOLD) {
                // 超过阈值没有数据
                _isFakeConnection.value = true
                Log.w(TAG, "检测到假连接：超过 ${INACTIVE_THRESHOLD}ms 无数据")

                // 触发重连
                triggerReconnect()
            } else {
                _isFakeConnection.value = false
            }
        } else {
            _isFakeConnection.value = false
        }
    }

    /**
     * 触发重连
     */
    private fun triggerReconnect() {
        Log.d(TAG, "触发重连逻辑")

        val device = currentDeviceAddress
        if (device != null) {
            scope.launch {
                bluetoothDataSource.disconnect()
                delay(1000)
                bluetoothDataSource.connect(device)
            }
        }
    }

    /**
     * 停止检查
     */
    fun stopChecking() {
        isCheckingFakeConnection = false
        checkJob?.cancel()
        _isFakeConnection.value = false
    }

    /**
     * 手动检查假连接
     */
    fun checkFakeConnectionNow() {
        scope.launch {
            checkForFakeConnection()
        }
    }
}