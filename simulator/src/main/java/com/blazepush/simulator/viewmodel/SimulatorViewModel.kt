package com.blazepush.simulator.viewmodel
// @IgnoreFormatCheck

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
import com.blazepush.simulator.ble.GpsPeripheralManager
import com.blazepush.simulator.data.GpsDataGenerator
import com.blazepush.simulator.data.SpeedController
import com.blazepush.simulator.data.SpeedMode
import com.blazepush.simulator.data.TestScenario
import com.blazepush.simulator.data.replay.ReplayAssetLoader
import com.blazepush.simulator.data.replay.ReplayFrame
import com.blazepush.simulator.data.replay.ReplayPlaybackPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    val isReplayMode: Boolean = false,
    val dataSourceLabel: String = "手动模拟",
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
    val speedStatus: String = "静止 (0 km/h)",
    // Replay 单次播放控制：true = 当前正在播放一段 replay；false = idle（等用户触发）
    val isReplayPlaying: Boolean = false,
)

/**
 * 模拟器ViewModel
 */
class SimulatorViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SimulatorUiState())
    val uiState: StateFlow<SimulatorUiState> = _uiState.asStateFlow()

    private var peripheralManager: GpsPeripheralManager? = null
    private var dataGenerator: GpsDataGenerator? = null
    private var dataUpdateJob: Job? = null
    private var appContext: Context? = null
    private val speedController = SpeedController()
    private val replayAssetLoader = ReplayAssetLoader()
    private val replayPlaybackPlanner = ReplayPlaybackPlanner()

    // 物理轨迹计算：记录起点和累计位移
    private var baseLatitude = 60.1725
    private var baseLongitude = 24.9375
    private var totalDistanceNorth = 0.0  // 向北累计位移（米）
    private var totalDistanceEast = 0.0   // 向东累计位移（米）
    private var lastUpdateTime = 0L       // 上次更新时间
    private var bearing = 45.0            // 运动方向（度）

    companion object {
        private const val TAG = "SimulatorViewModel"

        /**
         * 单次播放 replay frames（不再 forever 循环，避免循环边界处反向穿线把 lap session 判 INVALID）。
         * 跑完最后一帧自然返回；外部 caller 可重复调用触发重放。
         */
        suspend fun playReplayFramesOnce(
            frames: List<ReplayFrame>,
            emitFrame: suspend (ReplayFrame) -> Unit
        ) {
            if (frames.isEmpty()) return
            for (frame in frames) {
                kotlin.coroutines.coroutineContext.ensureActive()
                if (frame.delayMillis > 0) delay(frame.delayMillis)
                kotlin.coroutines.coroutineContext.ensureActive()
                emitFrame(frame)
            }
        }

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
        appContext = context.applicationContext
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
        dataUpdateJob?.cancel()
        dataUpdateJob = null
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
        dataUpdateJob?.cancel()
        dataUpdateJob = null

        // Replay 模式：advertising 启动后 **不**自动开始播放，等用户点 "PLAY ONCE" 按钮触发
        // 单次播放（避免循环边界反向穿线把 lap session 判 INVALID）。
        if (_uiState.value.isReplayMode || _uiState.value.currentScenario == TestScenario.REAL_TRACK_REPLAY) {
            return
        }

        dataUpdateJob = viewModelScope.launch {
            val generator = dataGenerator ?: return@launch
            val manager = peripheralManager ?: return@launch

            generator.startGpsDataStream().collect { (mainData, timeData) ->
                val now = System.currentTimeMillis()
                val currentSpeed = updateSpeed(now)
                generator.setCurrentSpeed(currentSpeed)

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

    fun enableReplayMode() {
        _uiState.value = _uiState.value.copy(
            isReplayMode = true,
            currentScenario = TestScenario.REAL_TRACK_REPLAY,
            dataSourceLabel = "圈速模拟回放",
            frequency = 5
        )
        dataGenerator = GpsDataGenerator(
            scenario = TestScenario.REAL_TRACK_REPLAY,
            frequency = 5,
            initialSpeed = _uiState.value.initialSpeed,
            satellites = _uiState.value.satellites
        )
        if (_uiState.value.isAdvertising) {
            startDataUpdate()
        }
    }

    /**
     * 用户点击 "PLAY ONCE" 触发：从头跑一次 replay frames，跑完自然停。
     * 跑期间 isReplayPlaying = true，UI 按钮置灰；跑完 reset 为 false。
     * 本方法幂等：已在播放时调用立即返回，不会叠加 job。
     */
    fun triggerReplayOnce() = triggerReplayOnce("replay/tianfu_track_replay_5hz.json")

    /**
     * 真实路测 0-100 回放(2026-06-05):2026-06-03 23:11 真车 telemetry 转制
     * (25Hz,53s,含蠕动 3 次起步 + 全力加速到 99 km/h)——0-100 链路桌面验证
     * 用真实数据形态,不再依赖手拖滑块。注:速度为接收端滤波后值,回放会被
     * 再滤一遍(双重 median 轻微钝化,形态影响可忽略)。
     */
    fun triggerRealRoadReplayOnce() = triggerReplayOnce("replay/real_0to100_20260603.json")

    private fun triggerReplayOnce(assetName: String) {
        if (_uiState.value.isReplayPlaying) return
        if (!_uiState.value.isReplayMode && _uiState.value.currentScenario != TestScenario.REAL_TRACK_REPLAY) return
        if (!_uiState.value.isAdvertising) return

        dataUpdateJob?.cancel()
        dataUpdateJob = viewModelScope.launch {
            val context = appContext ?: return@launch
            val generator = dataGenerator ?: return@launch
            val manager = peripheralManager ?: return@launch
            val replayJson = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val replay = replayAssetLoader.loadReplayJson(replayJson)
            val frames = replayPlaybackPlanner.plan(replay.samples)

            _uiState.value = _uiState.value.copy(isReplayPlaying = true)
            try {
                playReplayFramesOnce(frames) { frame ->
                    generator.applyReplaySample(frame.sample)
                    Log.d(
                        TAG,
                        "Replay frame: ts=${frame.sample.timestampMillis}, lat=${frame.sample.latitude}, lon=${frame.sample.longitude}, speed=${frame.sample.speedKmh}, bearing=${frame.sample.bearingDegrees}, sats=${frame.sample.satellites}"
                    )
                    val mainData = generator.generateGpsMainData()
                    val timeData = generator.generateGpsTimeData()
                    manager.updateGpsData(mainData, timeData)
                    _uiState.value = _uiState.value.copy(
                        currentSpeed = frame.sample.speedKmh.toFloat(),
                        currentLatitude = frame.sample.latitude,
                        currentLongitude = frame.sample.longitude,
                        satellites = frame.sample.satellites,
                        frequency = 5
                    )
                }
            } finally {
                _uiState.value = _uiState.value.copy(isReplayPlaying = false)
            }
        }
    }

    fun disableReplayMode() {
        _uiState.value = _uiState.value.copy(
            isReplayMode = false,
            currentScenario = TestScenario.STATIC,
            dataSourceLabel = "手动模拟"
        )
        dataGenerator = GpsDataGenerator(
            scenario = _uiState.value.currentScenario,
            frequency = _uiState.value.frequency,
            initialSpeed = _uiState.value.initialSpeed,
            satellites = _uiState.value.satellites
        )
        if (_uiState.value.isAdvertising) {
            startDataUpdate()
        }
    }
    /**
     * 设置测试场景
     */
    fun setScenario(scenario: TestScenario) {
        val frequency = if (scenario == TestScenario.REAL_TRACK_REPLAY) 5 else _uiState.value.frequency
        _uiState.value = _uiState.value.copy(
            currentScenario = scenario,
            isReplayMode = scenario == TestScenario.REAL_TRACK_REPLAY,
            dataSourceLabel = if (scenario == TestScenario.REAL_TRACK_REPLAY) "圈速模拟回放" else "手动模拟",
            frequency = frequency
        )
        dataGenerator?.let {
            dataGenerator = GpsDataGenerator(
                scenario = scenario,
                frequency = frequency,
                initialSpeed = _uiState.value.initialSpeed,
                satellites = _uiState.value.satellites
            )
            if (_uiState.value.isAdvertising) {
                startDataUpdate()
            }
        }
    }

    /**
     * 设置频率
     */
    fun setFrequency(hz: Int) {
        if (_uiState.value.isReplayMode || _uiState.value.currentScenario == TestScenario.REAL_TRACK_REPLAY) return
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
