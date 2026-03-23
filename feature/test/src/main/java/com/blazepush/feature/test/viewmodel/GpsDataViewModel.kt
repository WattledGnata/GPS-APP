package com.blazepush.feature.test.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.bluetooth.ScannedDevice
import com.blazepush.core.bluetooth.GpsDataRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.DataStats
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.DataQualityEvaluator
import com.blazepush.core.domain.usecase.DataSmoothing
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * GPS数据ViewModel - 单例，所有页面共享同一个数据流
 * 解决原有架构中数据不一致的核心问题
 *
 * 集成BLE设备扫描、连接功能和数据质量监控
 */
class GpsDataViewModel(
    private val gpsDataRepository: GpsDataRepository,
    private val bleDeviceManager: BleDeviceManager,
    private val dataQualityEvaluator: DataQualityEvaluator,
    private val dataSmoothing: DataSmoothing
) : ViewModel() {

    // GPS数据流（直接使用repository的数据）
    val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow

    val connectionState: StateFlow<ConnectionState> = gpsDataRepository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ConnectionState.DISCONNECTED
        )

    // BLE扫描状态
    val isScanning: StateFlow<Boolean> = bleDeviceManager.isScanning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // BLE扫描结果
    val scanResults = bleDeviceManager.scanResults
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 数据质量监控
    private val _dataQuality = MutableStateFlow(DataQuality.Empty)
    val dataQuality: StateFlow<DataQuality> = _dataQuality.asStateFlow()

    // 数据统计
    private var lastDataTime = 0L
    private var dataCount = 0
    private var dataCountStartTime = 0L

    init {
        // 监控GPS数据并计算质量
        viewModelScope.launch {
            gpsData.collect { data ->
                updateDataStats(data)
            }
        }
    }

    /**
     * 更新数据统计和质量评估
     */
    private fun updateDataStats(data: GpsData) {
        val now = System.currentTimeMillis()

        // 初始化统计起点
        if (dataCountStartTime == 0L) {
            dataCountStartTime = now
        }

        // 计算数据年龄
        val dataAge = if (data.timestamp > 0) {
            now - data.timestamp
        } else {
            now - lastDataTime
        }
        lastDataTime = now

        // 计算频率
        dataCount++
        val elapsedSeconds = (now - dataCountStartTime) / 1000.0
        val frequency = if (elapsedSeconds > 0) dataCount / elapsedSeconds else 0.0

        // 简化的丢包率计算（基于数据间隔）
        val expectedInterval = 100L // 期望100ms间隔
        val packetLossRate = if (dataAge > expectedInterval * 2) {
            ((dataAge - expectedInterval) / expectedInterval.toDouble()).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        // 构建统计信息
        val stats = DataStats(
            dataAge = dataAge,
            packetLossRate = packetLossRate,
            frequency = frequency
        )

        // 计算质量
        _dataQuality.value = dataQualityEvaluator.calculateQuality(data, stats)
    }

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
     * 重置统计数据
     */
    fun resetStats() {
        dataCount = 0
        dataCountStartTime = 0L
        lastDataTime = 0L
        dataSmoothing.reset()
    }

    /**
     * ViewModel清理时释放BLE资源
     */
    override fun onCleared() {
        super.onCleared()
        bleDeviceManager.cleanup()
    }
}
