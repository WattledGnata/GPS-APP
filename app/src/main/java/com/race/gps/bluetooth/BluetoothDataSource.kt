package com.race.gps.bluetooth

import android.content.Context
import android.util.Log
import com.race.gps.data.service.parser.RaceChronoParser
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 蓝牙数据源 - 唯一的GPS数据发射点
 * 替代原有的BluetoothManager，使用普通Kotlin类而非Android Service
 */
class BluetoothDataSource(
    private val context: Context,
    private val parser: RaceChronoParser
) {
    companion object {
        private const val TAG = "BluetoothDataSource"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionCollectJob: Job? = null

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var bleConnection: BleConnection? = null

    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                Log.d(TAG, "状态设置为 CONNECTING，创建 BleConnection")

                bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
                    // 根据UUID路由数据：Main走parseGpsData，Time走parseGpsTimeData
                    val gpsData = when (uuid.toString()) {
                        "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
                        "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
                        else -> _dataFlow.value
                    }
                    _dataFlow.value = gpsData.copy(isConnected = true)
                }

                // 在单独的协程中监听 BleConnection 的状态变化
                bleConnection?.connectionState?.let { stateFlow ->
                    connectionCollectJob?.cancel()
                    connectionCollectJob = scope.launch {
                        stateFlow.collect { state ->
                            Log.d(TAG, "BleConnection 状态变化: $state")
                            _connectionState.value = state
                        }
                    }
                }

                bleConnection?.connect()
                Log.d(TAG, "BleConnection.connect() 调用完成")

            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                _dataFlow.value = _dataFlow.value.copy(
                    isConnected = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionCollectJob?.cancel()
            connectionCollectJob = null
            bleConnection?.disconnect()
            bleConnection = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _dataFlow.value = _dataFlow.value.copy(isConnected = false)
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}
