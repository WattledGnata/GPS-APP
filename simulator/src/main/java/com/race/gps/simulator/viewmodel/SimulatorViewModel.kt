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
    val currentLongitude: Double = 24.9375
)

/**
 * 模拟器ViewModel
 */
class SimulatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    private var peripheralManager: GpsPeripheralManager? = null
    private var dataGenerator: GpsDataGenerator? = null

    companion object {
        private const val TAG = "SimulatorViewModel"

        // 必需的权限
        val REQUIRED_PERMISSIONS = buildList {
            // Android 12+ BLE权限
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            // 位置权限 (BLE扫描需要，即使不实际使用位置)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
     * 开始数据更新
     */
    private fun startDataUpdate() {
        viewModelScope.launch {
            val generator = dataGenerator ?: return@launch
            val manager = peripheralManager ?: return@launch

            generator.startGpsDataStream().collect { (mainData, timeData) ->
                manager.updateGpsData(mainData, timeData)

                // 更新UI状态中的当前数据
                _uiState.value = _uiState.value.copy(
                    currentSpeed = getCurrentSpeed(),
                    currentLatitude = getCurrentLatitude(),
                    currentLongitude = getCurrentLongitude()
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

    /**
     * 获取当前速度（用于UI显示）
     */
    private fun getCurrentSpeed(): Float {
        return when (_uiState.value.currentScenario) {
            TestScenario.STATIC -> _uiState.value.initialSpeed
            TestScenario.ACCELERATION -> _uiState.value.currentSpeed.coerceAtMost(100f)
            TestScenario.BRAKING -> _uiState.value.currentSpeed.coerceAtLeast(0f)
        }
    }

    /**
     * 获取当前纬度
     */
    private fun getCurrentLatitude(): Double {
        return 60.1725 + (_uiState.value.currentSpeed * 0.0001)
    }

    /**
     * 获取当前经度
     */
    private fun getCurrentLongitude(): Double {
        return 24.9375 + (_uiState.value.currentSpeed * 0.0001)
    }

    override fun onCleared() {
        super.onCleared()
        peripheralManager?.cleanup()
    }
}
