package com.blazepush.feature.test.viewmodel

import android.util.Log
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

    init {
        // 监控GPS数据并计算质量
        viewModelScope.launch {
            gpsData.collect { data ->
                Log.d(
                    "GpsDataViewModel",
                    "gpsData: ts=${data.timestamp}, lat=${data.latitude}, lon=${data.longitude}, speed=${data.speed}, bearing=${data.bearing}, sats=${data.satelliteCount}, hdop=${data.hdop}, fix=${data.fixQuality}, ready=${data.isTestReady}"
                )
                updateDataStats(data)
            }
        }

        // A28 新增：DISCONNECTED → resetStats（design D1 / spec R3）。
        // connectionState 已是 StateFlow，operator fusion 自带 distinctUntilChanged 语义，
        // 这里不显式调 .distinctUntilChanged()（显式调用会触发 Kotlin 编译期 warning-as-error）。
        // spec R3 "distinctUntilChanged 防重复 reset" 契约由 StateFlow 语义直接满足。
        viewModelScope.launch {
            connectionState
                .filter { it == ConnectionState.DISCONNECTED }
                .collect { resetStats() }
        }
    }

    /**
     * 更新数据统计和质量评估
     *
     * A28 重写（change fix-gps-stats-and-lazy-catalog-hot-start）：
     * - frequency 直接透传 data.frequency（parser 1 秒滑窗结果），不再累计平均
     * - packetLoss 调用纯函数 computePacketLossRate 从 data.frequency 反推期望采样周期，
     *   不再硬编码 10Hz 假设
     */
    private fun updateDataStats(data: GpsData) {
        val now = System.currentTimeMillis()

        // 计算数据年龄
        val dataAge = if (data.timestamp > 0) {
            now - data.timestamp
        } else {
            now - lastDataTime
        }
        lastDataTime = now

        // A28 frequency：透传 parser 1 秒滑窗结果
        val frequency = data.frequency

        // A28 packetLoss：纯函数计算（测试确定性）
        val packetLossRate = computePacketLossRate(dataAge = dataAge, frequency = data.frequency)

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
     *
     * A28 裁剪：旧累计平均状态已随 A28 删除；
     * 重置同时把 _dataQuality 回到 DataQuality.Empty，确保 DISCONNECTED 后 UI 立即回初始态
     */
    fun resetStats() {
        lastDataTime = 0L
        _dataQuality.value = DataQuality.Empty
        dataSmoothing.reset()
    }

    /**
     * ViewModel清理时释放BLE资源
     */
    override fun onCleared() {
        super.onCleared()
        bleDeviceManager.cleanup()
    }

    companion object {
        /**
         * A28 纯函数：根据数据年龄 + parser 滑窗 frequency 计算丢包率。
         *
         * 从 data.frequency 反推期望采样周期（1000 / frequency），适配 10Hz / 25Hz / 50Hz 设备；
         * frequency ≤ 0 为暖启动 / 丢连，回退 0 避免 NaN / 除零 / 冷启动误告警；
         * 2x 阈值门槛保留（与 v1 容忍度一致），短暂抖动不触发。
         *
         * VisibleForTesting：单元测试直接对纯函数做精确断言，不经 System.currentTimeMillis() 路径。
         */
        @androidx.annotation.VisibleForTesting
        internal fun computePacketLossRate(dataAge: Long, frequency: Double): Double {
            val expectedSampleInterval = if (frequency > 0.0) 1000.0 / frequency else 0.0
            return if (expectedSampleInterval > 0.0 && dataAge > expectedSampleInterval * 2) {
                ((dataAge - expectedSampleInterval) / expectedSampleInterval).coerceIn(0.0, 100.0)
            } else {
                0.0
            }
        }
    }
}
