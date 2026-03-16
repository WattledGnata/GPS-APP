package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.data.repository.GpsDataRepository
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * GPS数据ViewModel - 单例，所有页面共享同一个数据流
 * 解决原有架构中数据不一致的核心问题
 */
class GpsDataViewModel(
    private val gpsDataRepository: GpsDataRepository
) : ViewModel() {

    // 所有UI都订阅这个Flow
    val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = GpsData.Empty
        )

    val connectionState: StateFlow<ConnectionState> = gpsDataRepository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectionState.DISCONNECTED
        )

    fun connect(deviceAddress: String) {
        viewModelScope.launch {
            gpsDataRepository.connect(deviceAddress)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            gpsDataRepository.disconnect()
        }
    }
}
