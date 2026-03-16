package com.race.gps.bluetooth

import android.content.Context
import com.race.gps.data.service.parser.RaceChronoParser
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var bleConnection: BleConnection? = null

    fun connect(deviceAddress: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                bleConnection = BleConnection(context, deviceAddress) { rawData ->
                    // 收到原始数据后立即解析并发送
                    val gpsData = parser.parseGpsData(rawData, _dataFlow.value)
                    _dataFlow.value = gpsData.copy(isConnected = true)
                }
                bleConnection?.connect()
                _connectionState.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
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
    CONNECTED
}
