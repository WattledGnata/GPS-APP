// @IgnoreFormatCheck
package com.blazepush.feature.test.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazepush.feature.test.FileLogger
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.bluetooth.ScannedDevice
import com.blazepush.core.bluetooth.GpsDataRepository
import com.blazepush.core.data.model.BluetoothDeviceModel
import com.blazepush.core.data.model.displayName
import com.blazepush.core.data.repository.BluetoothDeviceRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.DataStats
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.DataQualityEvaluator
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
    // ble-device-memory round：设备记忆（别名/已保存列表/记录管理）
    private val bluetoothDeviceRepository: BluetoothDeviceRepository,
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

    // 已连接设备名（connectDevice 时写入，DISCONNECTED 时清零；
    // ble-device-memory：冷启动自动连成功后由 Decision 8 回填，别名优先）
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    // ble-device-memory：当前连接目标 address（setAlias 判断是否需同步主屏名 + 管理 sheet 绿点标识）
    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    // ble-device-memory：已保存设备列表（扫描列表 join 别名/Last connected 徽标 + 管理 sheet 数据源）
    val savedDevices: StateFlow<List<BluetoothDeviceModel>> = bluetoothDeviceRepository.devicesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 数据统计
    private var lastDataTime = 0L

    // road-test-first 持久日志去重：仅在 isStale 翻转时落盘，避免 25Hz gpsData 刷屏
    private var lastLoggedIsStale: Boolean? = null

    init {
        // 监控GPS数据并计算质量
        viewModelScope.launch {
            gpsData.collect { data ->
                Log.d(
                    "GpsDataViewModel",
                    "gpsData: ts=${data.timestamp}, lat=${data.latitude}, lon=${data.longitude}, speed=${data.speed}, bearing=${data.bearing}, sats=${data.satelliteCount}, hdop=${data.hdop}, fix=${data.fixQuality}, ready=${data.isTestReady}"
                )
                // road-test-first 兜底：isStale 翻转持久落盘（debug_log.txt），路测可 adb pull 诊断
                // 丢星不拆链是否生效（ble-connection-liveness）。去重防 25Hz 刷屏。
                if (lastLoggedIsStale != data.isStale) {
                    FileLogger.d(
                        "BleLiveness",
                        "isStale $lastLoggedIsStale -> ${data.isStale} (conn=${connectionState.value}, sats=${data.satelliteCount}, fix=${data.fixQuality})"
                    )
                    lastLoggedIsStale = data.isStale
                }
                updateDataStats(data)
            }
        }

        // road-test-first 兜底：connectionState 全部转移持久落盘，确认"丢星不再发 DISCONNECTED"
        // + "真断开仍发 DISCONNECTED"（ble-connection-liveness spec R1/R2）。低频，不去重。
        viewModelScope.launch {
            connectionState.collect { state ->
                FileLogger.d("BleLiveness", "connectionState -> $state")
            }
        }

        // A28 新增：DISCONNECTED → resetStats（design D1 / spec R3）。
        // connectionState 已是 StateFlow，operator fusion 自带 distinctUntilChanged 语义，
        // 这里不显式调 .distinctUntilChanged()（显式调用会触发 Kotlin 编译期 warning-as-error）。
        // spec R3 "distinctUntilChanged 防重复 reset" 契约由 StateFlow 语义直接满足。
        viewModelScope.launch {
            connectionState
                .filter { it == ConnectionState.DISCONNECTED }
                .collect {
                    resetStats()
                    _connectedDeviceName.value = null
                    _connectedDeviceAddress.value = null
                }
        }

        // ble-device-memory（design Decision 8）：冷启动自动连成功后主屏设备名回填。
        // 自动连不经 connectDevice()，_connectedDeviceName 为 null 会显示 "No device"
        // 与 Connected 状态矛盾。仅 name 为 null 时触发（手动连接路径不受影响）；
        // 冷启动目标本就是表内 lastConnectedAtMs 最大者，落库 touch 不改查询结果，无时序竞争。
        viewModelScope.launch {
            connectionState
                .filter { it == ConnectionState.CONNECTED }
                .collect {
                    if (_connectedDeviceName.value == null) {
                        val last = bluetoothDeviceRepository.getLastConnectedDevice()
                        if (last != null) {
                            _connectedDeviceName.value = last.displayName
                            _connectedDeviceAddress.value = last.address
                            FileLogger.d(
                                "BleDeviceMemory",
                                "cold-start name backfill addr=${last.address} name=${last.displayName}"
                            )
                        }
                    }
                }
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

        // dataAge = "上次包到现在多久了"，用本地 System.currentTimeMillis 差值。
        // 不使用协议 timestamp：parseGpsTimeData 用 Calendar.getInstance()（本地时区）
        // 把 GPS UTC 时间映射到本地时区，中国 UTC+8 时差 8 小时，直接 now-timestamp
        // 会得到天文数字，永远触发 DATA_FRESH 失败。
        val dataAge = if (lastDataTime > 0L) now - lastDataTime else 0L
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
     * ble-device-memory：传广播名给 manager（CONNECTED 后落库）；
     * 已设别名的设备主屏显示别名优先（异步查表覆盖）。
     */
    fun connectDevice(device: ScannedDevice) {
        _connectedDeviceName.value = device.name
        _connectedDeviceAddress.value = device.address
        viewModelScope.launch {
            val alias = bluetoothDeviceRepository.getSavedDevices()
                .firstOrNull { it.address == device.address }
                ?.alias?.takeIf { it.isNotBlank() }
            if (alias != null && _connectedDeviceAddress.value == device.address) {
                _connectedDeviceName.value = alias
            }
        }
        bleDeviceManager.connect(device.address, device.name)
    }

    /**
     * ble-device-memory：设置/清除设备别名（null 或空白 = 还原固件名显示）。
     * 改的是当前连接设备时同步刷新主屏显示名（design Decision 8）。
     */
    fun setAlias(address: String, alias: String?) {
        viewModelScope.launch {
            val normalized = alias?.takeIf { it.isNotBlank() }
            bluetoothDeviceRepository.setAlias(address, normalized)
            FileLogger.d("BleDeviceMemory", "alias set addr=$address alias=$normalized")
            if (_connectedDeviceAddress.value == address) {
                val updated = bluetoothDeviceRepository.getSavedDevices()
                    .firstOrNull { it.address == address }
                if (updated != null) {
                    _connectedDeviceName.value = updated.displayName
                }
            }
        }
    }

    /**
     * ble-device-memory：删除已保存设备记录（不断开当前连接——spec 锁此语义）。
     */
    fun deleteSavedDevice(address: String) {
        viewModelScope.launch {
            bluetoothDeviceRepository.removeDevice(address)
            FileLogger.d("BleDeviceMemory", "record deleted addr=$address")
        }
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
