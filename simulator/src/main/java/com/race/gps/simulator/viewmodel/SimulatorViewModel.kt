package com.race.gps.simulator.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.simulator.ble.GpsPeripheralManager
import com.race.gps.simulator.data.GpsDataGenerator
import com.race.gps.simulator.data.SpeedController
import com.race.gps.simulator.data.SpeedMode
import com.race.gps.simulator.data.TestScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 模拟器UI状态
 */
data class SimulatorUiState(
    val hasPermissions: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isAdvertising: Boolean = false,
    val isServerReady: Boolean = false,
    val connectedDevices: Set<String> = emptySet(),
    val currentScenario: TestScenario = TestScenario.STATIC,
    val frequency: Int = 10,
    val satellites: Int = 12,
    val initialSpeed: Float = 0f,
    val currentSpeed: Float = 0f,
    val currentLatitude: Double = 60.1725,
    val currentLongitude: Double = 24.9375,
    // 速度控制相关
    val speedMode: SpeedMode = SpeedMode.STATIC,
    val targetSpeed: Float = 60f,
    val speedAcceleration: Float = 2.0f,
    val speedStatus: String = "静止 (0 km/h)"
)

/**
 * 模拟器ViewModel
 */
class SimulatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    private var peripheralManager: GpsPeripheralManager? = null
    private var dataGenerator: GpsDataGenerator? = null
    private val speedController = SpeedController()

    // 物理轨迹计算：记录起点和累计位移
    private var baseLatitude = 60.1725
    private var baseLongitude = 24.9375
    private var totalDistanceNorth = 0.0  // 向北累计位移（米）
    private var totalDistanceEast = 0.0   // 向东累计位移（米）
    private var lastUpdateTime = 0L       // 上次更新时间
    private var bearing = 45.0            // 运动方向（度）

    companion object {
        private const val TAG = "SimulatorViewModel"

        // 必需的权限
        val REQUIRED_PERMISSIONS = buildList {
            // 位置权限 (所有版本都需要)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)

            // Android 12+ BLE权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            // NEARBY_WIFI_DEVICES 不需要，BLE操作不依赖此权限
        }.toTypedArray()
    }

    /**
     * 检查权限
     */
    fun checkPermissions(context: Context) {
        val hasPermissions = REQUIRED_PERMISSIONS.all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        _uiState.value = _uiState.value.copy(hasPermissions = hasPermissions)

        // 检查蓝牙状态
        checkBluetoothStatus(context)
    }

    /**
     * 检查蓝牙状态
     */
    private fun checkBluetoothStatus(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val isEnabled = bluetoothManager.adapter.isEnabled
        _uiState.value = _uiState.value.copy(isBluetoothEnabled = isEnabled)
    }

    /**
     * 开始广播
     */
    fun startAdvertising(context: Context) {
        if (peripheralManager == null) {
            peripheralManager = GpsPeripheralManager(context.applicationContext)
        }

        val manager = peripheralManager ?: return

        // 创建数据生成器
        dataGenerator = GpsDataGenerator(
            scenario = _uiState.value.currentScenario,
            frequency = _uiState.value.frequency,
            initialSpeed = _uiState.value.initialSpeed,
            satellites = _uiState.value.satellites
        )

        // 开始广播
        manager.startAdvertising(
            onSuccess = {
                Log.d(TAG, "Advertising started successfully")
                startDataUpdate()
            },
            onError = { error ->
                Log.e(TAG, "Failed to start advertising: $error")
                _uiState.value = _uiState.value.copy(isAdvertising = false)
            }
        )

        _uiState.value = _uiState.value.copy(isAdvertising = true)

        // 监听连接状态
        viewModelScope.launch {
            manager.isAdvertising.collect { isAdvertising ->
                _uiState.value = _uiState.value.copy(isAdvertising = isAdvertising)
            }
        }

        viewModelScope.launch {
            manager.isServerReady.collect { isReady ->
                _uiState.value = _uiState.value.copy(isServerReady = isReady)
            }
        }

        viewModelScope.launch {
            manager.connectedDevices.collect { devices ->
                _uiState.value = _uiState.value.copy(connectedDevices = devices)
            }
        }
    }

    /**
     * 停止广播
     */
    fun stopAdvertising() {
        peripheralManager?.stopAdvertising()
        _uiState.value = _uiState.value.copy(
            isAdvertising = false,
            isServerReady = false,
            connectedDevices = emptySet()
        )
    }

    /**
     * 获取活跃设备数量
     */
    fun getActiveDeviceCount(): Int {
        return peripheralManager?.getActiveDeviceCount() ?: 0
    }

    /**
     * 开始数据更新
     */
    private fun startDataUpdate() {
        viewModelScope.launch {
            val generator = dataGenerator ?: return@launch
            val manager = peripheralManager ?: return@launch

            generator.startGpsDataStream().collect { (mainData, timeData) ->
                val now = System.currentTimeMillis()
                val currentSpeed = updateSpeed(now)
                generator.setCurrentSpeed(currentSpeed)

                // 基于时间积分计算位移（物理真实轨迹）
                val (currentLat, currentLon) = updatePosition(currentSpeed, now)
                generator.setCurrentPosition(currentLat, currentLon)

                manager.updateGpsData(mainData, timeData)

                _uiState.value = _uiState.value.copy(
                    currentSpeed = currentSpeed,
                    currentLatitude = currentLat,
                    currentLongitude = currentLon
                )
            }
        }
    }

    /**
     * 设置测试场景
     */
    fun setScenario(scenario: TestScenario) {
        _uiState.value = _uiState.value.copy(currentScenario = scenario)
        dataGenerator?.let { generator ->
            // 重新创建生成器以应用新场景
            dataGenerator = GpsDataGenerator(
                scenario = scenario,
                frequency = _uiState.value.frequency,
                initialSpeed = _uiState.value.initialSpeed,
                satellites = _uiState.value.satellites
            )
        }
    }

    /**
     * 设置频率
     */
    fun setFrequency(hz: Int) {
        _uiState.value = _uiState.value.copy(frequency = hz)
        dataGenerator?.setFrequency(hz)
    }

    /**
     * 设置卫星数量
     */
    fun setSatellites(count: Int) {
        _uiState.value = _uiState.value.copy(satellites = count)
        dataGenerator?.setSatellites(count)
    }

    /**
     * 设置初始速度
     */
    fun setInitialSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(initialSpeed = speed)
    }

    // ==================== 速度控制相关方法 ====================

    /**
     * 设置速度模式
     */
    fun setSpeedMode(mode: SpeedMode) {
        speedController.setMode(mode)
        _uiState.value = _uiState.value.copy(
            speedMode = mode,
            speedStatus = speedController.getStatusDescription()
        )
    }

    /**
     * 设置目标速度
     */
    fun setTargetSpeed(speed: Float) {
        speedController.setTargetSpeed(speed)
        _uiState.value = _uiState.value.copy(
            targetSpeed = speed,
            speedStatus = speedController.getStatusDescription()
        )
    }

    /**
     * 设置加速度
     */
    fun setSpeedAcceleration(acc: Float) {
        speedController.setAcceleration(acc)
        _uiState.value = _uiState.value.copy(speedAcceleration = acc)
    }

    /**
     * 更新速度（在数据生成循环中调用）
     */
    private fun updateSpeed(currentTimeMillis: Long): Float {
        val newSpeed = speedController.updateSpeed(currentTimeMillis)
        _uiState.value = _uiState.value.copy(
            currentSpeed = newSpeed,
            speedStatus = speedController.getStatusDescription()
        )
        return newSpeed
    }

    /**
     * 获取当前速度（用于UI显示）
     */
    private fun getCurrentSpeed(): Float {
        return _uiState.value.currentSpeed
    }

    /**
     * 获取当前纬度
     */
    /**
     * 基于时间积分计算当前位置（物理真实轨迹）
     * 位移 = 速度 × 时间
     */
    private fun updatePosition(currentSpeed: Float, now: Long): Pair<Double, Double> {
        if (lastUpdateTime == 0L) {
            lastUpdateTime = now
            return Pair(baseLatitude, baseLongitude)
        }

        val dt = (now - lastUpdateTime) / 1000.0  // 秒
        lastUpdateTime = now

        if (dt <= 0 || dt > 1.0) {
            // 时间间隔异常，跳过这次积分，直接返回当前位置
            val lat = baseLatitude + totalDistanceNorth / 111000.0
            val lon = baseLongitude + totalDistanceEast / (111000.0 * kotlin.math.cos(Math.toRadians(baseLatitude)))
            return Pair(lat, lon)
        }

        // 速度 m/s
        val speedMs = currentSpeed / 3.6

        // 位移增量（米）
        val deltaDistance = speedMs * dt

        // 累加位移
        val deltaNorth = deltaDistance * kotlin.math.cos(Math.toRadians(bearing))
        val deltaEast = deltaDistance * kotlin.math.sin(Math.toRadians(bearing))
        totalDistanceNorth += deltaNorth
        totalDistanceEast += deltaEast

        // 转换为经纬度
        // 1度纬度 ≈ 111km，1度经度 ≈ 111km × cos(lat)
        val latOffset = totalDistanceNorth / 111000.0
        val lonOffset = totalDistanceEast / (111000.0 * kotlin.math.cos(Math.toRadians(baseLatitude)))

        return Pair(baseLatitude + latOffset, baseLongitude + lonOffset)
    }

    /**
     * 重置轨迹（开始新测试时调用）
     */
    private fun resetTrajectory() {
        totalDistanceNorth = 0.0
        totalDistanceEast = 0.0
        lastUpdateTime = 0L
    }

    override fun onCleared() {
        super.onCleared()
        peripheralManager?.cleanup()
    }
}
