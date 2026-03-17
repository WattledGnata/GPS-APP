package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.bluetooth.BleDeviceManager
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.bluetooth.ScannedDevice
import com.race.gps.data.repository.GpsDataRepository
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * GPS数据ViewModel - 单例，所有页面共享同一个数据流
 * 解决原有架构中数据不一致的核心问题
 *
 * 集成BLE设备扫描和连接功能
 */
class GpsDataViewModel(
    private val gpsDataRepository: GpsDataRepository,
    private val bleDeviceManager: BleDeviceManager
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

    // BLE扫描状态（代理自BleDeviceManager）
    val isScanning: StateFlow<Boolean> = bleDeviceManager.isScanning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // BLE扫描结果（代理自BleDeviceManager）
    val scanResults = bleDeviceManager.scanResults
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 连接GPS设备
     */
    fun connect(deviceAddress: String) {
        viewModelScope.launch {
            gpsDataRepository.connect(deviceAddress)
        }
    }

    /**
     * 断开当前连接
     */
    fun disconnect() {
        viewModelScope.launch {
            gpsDataRepository.disconnect()
        }
    }

    /**
     * 开始扫描BLE设备
     */
    fun startScan() {
        bleDeviceManager.startScan()
    }

    /**
     * 停止扫描BLE设备
     */
    fun stopScan() {
        bleDeviceManager.stopScan()
    }

    /**
     * 连接扫描到的BLE设备
     */
    fun connectDevice(device: ScannedDevice) {
        bleDeviceManager.connect(device.address)
    }

    /**
     * ViewModel清理时释放BLE资源
     */
    override fun onCleared() {
        super.onCleared()
        bleDeviceManager.cleanup()
    }
}
