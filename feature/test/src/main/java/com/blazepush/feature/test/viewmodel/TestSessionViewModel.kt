package com.blazepush.feature.test.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazepush.feature.test.FileLogger
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TestState
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.FilteredGpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class TestMode {
    Acceleration,
    Braking,
    LapDebug
}

/**
 * 测试会话ViewModel - 管理测试状态机
 */
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val bleDeviceManager: BleDeviceManager,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher(),
    private val gpsDataFilter: GpsDataFilter = GpsDataFilter(),
    private val trackCatalog: TrackCatalog,
    private val lapTimingEngine: LapTimingEngine
) : ViewModel() {

    companion object {
        private const val TAG = "TestSessionVM"
        private const val COUNTDOWN_DURATION = 5
        private const val PRE_TRIGGER_DURATION_MS = 2000L
        private const val TRIGGER_ACCELERATION_THRESHOLD = 1.0
        private const val TRIGGER_CONFIRMATION_COUNT = 5
        private const val STANDSTILL_SPEED_THRESHOLD = 3.0
        private const val STANDSTILL_CONFIRMATION_COUNT = 3
    }

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _currentMode = MutableStateFlow(TestMode.Acceleration)
    val currentMode: StateFlow<TestMode> = _currentMode.asStateFlow()

    private val _availableTracks = MutableStateFlow(trackCatalog.getAllTracks())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _lapRunConfig = MutableStateFlow<LapRunConfig?>(null)
    val lapRunConfig: StateFlow<LapRunConfig?> = _lapRunConfig.asStateFlow()

    private val _lapSession = MutableStateFlow<LapSession?>(null)
    val lapSession: StateFlow<LapSession?> = _lapSession.asStateFlow()

    private val _latestLapRecords = MutableStateFlow<List<LapRecord>>(emptyList())
    val latestLapRecords: StateFlow<List<LapRecord>> = _latestLapRecords.asStateFlow()

    private var lastLapGpsSample: GpsSample? = null
    private var isLapRecording = false

    private val _launchStatus = MutableStateFlow(
        SmartTestLauncher.LaunchStatus(
            conditions = emptyList(),
            canLaunch = false,
            unmetConditionIds = emptyList()
        )
    )
    val launchStatus: StateFlow<SmartTestLauncher.LaunchStatus> = _launchStatus.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(5)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private var countdownJob: Job? = null
    // 数据帧接收时刻，使用 elapsedRealtime 单调时钟，**不依赖** `gpsData.timestamp`
    // 供 updateLaunchStatus 的 lastDataAge 计算，与 GpsData 的协议时间字段解耦
    // （对应 fix-laptime-clock-source-integrity spec Requirement 3.5 (c)）
    private var lastReceivedAtElapsed: Long = 0L
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val preTriggerBuffer = mutableListOf<FilteredGpsData>()

    private var isStartReady = false
    private var standstillCount = 0
    private var consecutiveTriggerCount = 0

    init {
        viewModelScope.launch {
            bleDeviceManager.connectionState.collect { state ->
                _connectionState.value = state
            }
        }

        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                // 无论 isTimeSynced 真假，首行更新 elapsedRealtime 以驱动 lastDataAge 计算
                // （Requirement 3.5 (c)：lastDataAge 与协议 timestamp 解耦）
                lastReceivedAtElapsed = SystemClock.elapsedRealtime()

                val filteredData = gpsDataFilter.process(gpsData)
                updatePreTriggerBuffer(filteredData)
                updateLaunchStatus(gpsData)
                processFilteredData(filteredData)

                bridgeGpsToLapTiming(gpsData)
            }
        }
    }

    fun enterSmartLaunch(template: TestTemplate, carModel: String) {
        _currentMode.value = when (template) {
            is TestTemplate.Acceleration0To100 -> TestMode.Acceleration
            is TestTemplate.Braking100To0 -> TestMode.Braking
        }
        isStartReady = false
        standstillCount = 0
        consecutiveTriggerCount = 0
        isFinishing = false  // 重置完成标记，允许新测试
        _testState.value = TestState.Preparing(template, carModel)
        startCountdown()
    }

    fun selectLapDebugMode(trackId: String) {
        selectLapDebugMode(LapRunConfig(trackId = trackId))
    }

    fun selectLapDebugMode(config: LapRunConfig) {
        val track = trackCatalog.getTrack(config.trackId) ?: return

        _currentMode.value = TestMode.LapDebug
        _lapRunConfig.value = config
        _lapSession.value = createLapSession(track.id)
        _latestLapRecords.value = emptyList()
        lastLapGpsSample = null
        isLapRecording = true

        FileLogger.d(TAG, "lapDebugTrackSummary: ${buildTrackDebugSummary(track)}")
    }

    fun stopLapDebugSession() {
        isLapRecording = false
        _lapSession.value = _lapSession.value?.copy(status = LapSessionStatus.Finished)
        lastLapGpsSample = null
    }

    fun exitLapDebugMode() {
        isLapRecording = false
        lastLapGpsSample = null
        _latestLapRecords.value = emptyList()
        _lapRunConfig.value = null
        _lapSession.value = null
        _currentMode.value = TestMode.Acceleration
    }

    fun skipCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
    }

    fun cancelTest() {
        countdownJob?.cancel()
        countdownJob = null
        consecutiveTriggerCount = 0
        isStartReady = false
        standstillCount = 0
        _testState.value = TestState.Idle
    }

    private fun startCountdown() {
        _countdownSeconds.value = COUNTDOWN_DURATION
        countdownJob = viewModelScope.launch {
            for (i in COUNTDOWN_DURATION downTo 1) {
                _countdownSeconds.value = i
                delay(1000)
            }
            _countdownSeconds.value = 0
            countdownJob = null
        }
    }

    private fun updatePreTriggerBuffer(filteredData: FilteredGpsData) {
        // Requirement 3.5 (a)：未同步帧不 append 到 preTriggerBuffer
        if (!filteredData.raw.isTimeSynced) return
        preTriggerBuffer.add(filteredData)
        val cutoffTime = filteredData.timestamp - PRE_TRIGGER_DURATION_MS
        while (preTriggerBuffer.isNotEmpty() && preTriggerBuffer.first().timestamp < cutoffTime) {
            preTriggerBuffer.removeAt(0)
        }
    }

    private fun processFilteredData(filteredData: FilteredGpsData) {
        when (val state = _testState.value) {
            is TestState.Preparing -> {
                // Requirement 3.5 (a)：未同步帧不触发测试转 Running
                if (!filteredData.raw.isTimeSynced) return
                if (_countdownSeconds.value == 0) {
                    if (checkTriggerCondition(filteredData, state.template)) {
                        startTest(state.template, state.carModel, filteredData)
                    }
                }
            }
            is TestState.Running -> {
                // Requirement 3.5 (a) v2（A6 / opsx code review C.1）：
                // Running 期间失联 filter 返回 sentinel timestamp = Long.MIN_VALUE + zero acceleration
                // 的"零 delta 快照"。若吃进 session.dataPoints，elapsedTime = Long.MIN_VALUE - startTime
                // 会溢出污染 0-100 用时等结果计算。Preparing / Running 两分支必须对称守卫。
                if (!filteredData.raw.isTimeSynced) return
                state.session.addFilteredDataPoint(filteredData)
                if (state.session.template.shouldEnd(filteredData.raw)) {
                    finishTest(state.session)
                }
            }
            else -> { /* 其他状态不处理 */ }
        }
    }

    private fun checkTriggerCondition(filteredData: FilteredGpsData, template: TestTemplate): Boolean {
        return when (template) {
            is TestTemplate.Acceleration0To100 -> checkAccelerationTrigger(filteredData)
            is TestTemplate.Braking100To0 -> checkBrakingTrigger(filteredData)
        }
    }

    private fun checkAccelerationTrigger(filteredData: FilteredGpsData): Boolean {
        if (!isStartReady) {
            if (filteredData.speed < STANDSTILL_SPEED_THRESHOLD) {
                standstillCount++
                if (standstillCount >= STANDSTILL_CONFIRMATION_COUNT) {
                    isStartReady = true
                    standstillCount = 0
                }
            } else {
                standstillCount = 0
            }
            return false
        }

        val isAccelerating = filteredData.acceleration > 0
        val isMoving = filteredData.speed > 1.0

        if (isAccelerating || isMoving) {
            consecutiveTriggerCount++
            return consecutiveTriggerCount >= TRIGGER_CONFIRMATION_COUNT
        } else {
            consecutiveTriggerCount = 0
            return false
        }
    }

    private fun checkBrakingTrigger(filteredData: FilteredGpsData): Boolean {
        val isHighSpeed = filteredData.speed in 95.0..105.0
        val isBraking = filteredData.acceleration < -TRIGGER_ACCELERATION_THRESHOLD

        if (isHighSpeed && isBraking) {
            consecutiveTriggerCount++
            return consecutiveTriggerCount >= TRIGGER_CONFIRMATION_COUNT
        } else {
            consecutiveTriggerCount = 0
            return false
        }
    }

    private fun startTest(template: TestTemplate, carModel: String, filteredData: FilteredGpsData) {
        consecutiveTriggerCount = 0
        val lockedPreTriggerBuffer = preTriggerBuffer.toList()

        val session = TestSession(
            id = UUID.randomUUID().toString(),
            template = template,
            carModel = carModel,
            startTime = filteredData.timestamp
        )

        session.markStarted(filteredData, lockedPreTriggerBuffer)

        _testState.value = TestState.Running(session)

        FileLogger.d(TAG, "startTest: session.startTime=${session.startTime}, triggerTime=${session.triggerTime}")
        FileLogger.d(TAG, "startTest: dataPoints.size=${session.dataPoints.size}")
        session.dataPoints.firstOrNull()?.let {
            FileLogger.d(TAG, "startTest: firstPoint elapsedTime=${it.elapsedTime}, speed=${it.speed}")
        }
        session.dataPoints.lastOrNull()?.let {
            FileLogger.d(TAG, "startTest: lastPoint elapsedTime=${it.elapsedTime}, speed=${it.speed}")
        }
    }

    fun currentLapTrackDebugSummary(): String? {
        val trackId = _lapRunConfig.value?.trackId ?: return null
        val track = trackCatalog.getTrack(trackId) ?: return null
        return buildTrackDebugSummary(track)
    }

    private fun bridgeGpsToLapTiming(gpsData: GpsData) {
        val config = _lapRunConfig.value ?: return
        if (_currentMode.value != TestMode.LapDebug || !isLapRecording) return

        // Requirement 3：未同步帧整帧跳过，并重置 lastLapGpsSample
        // 失联恢复后首个同步帧走首样本分支（previousSample == null），
        // 避免 detector 对"跨几秒位移"做线段相交判定伪造过线。
        if (!gpsData.isTimeSynced) {
            lastLapGpsSample = null
            FileLogger.d(TAG, "bridgeGpsToLapTiming: skip unsynced frame, reset prev")
            return
        }

        val currentSession = _lapSession.value ?: return
        val track = trackCatalog.getTrack(config.trackId) ?: return
        val currentSample = gpsData.toLapGpsSample()
        val previousSample = lastLapGpsSample

        FileLogger.d(
            TAG,
            "bridgeGpsToLapTiming: track=${track.id}, sessionStatus=${currentSession.status}, currentLapIndex=${currentSession.currentLapIndex}, nextGate=${currentSession.nextExpectedGateIndex}, gpsTs=${gpsData.timestamp}, lat=${gpsData.latitude}, lon=${gpsData.longitude}, speed=${gpsData.speed}, bearing=${gpsData.bearing}, prevTs=${previousSample?.timestampMillis}, prevLat=${previousSample?.latitude}, prevLon=${previousSample?.longitude}"
        )

        lastLapGpsSample = currentSample
        if (previousSample == null || currentSample.timestampMillis <= 0L) {
            _lapSession.value = currentSession
            return
        }

        val updatedSession = lapTimingEngine.processSample(
            session = currentSession,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample
        )

        FileLogger.d(
            TAG,
            "lapTimingResult: status=${updatedSession.status}, currentLapIndex=${updatedSession.currentLapIndex}, nextGate=${updatedSession.nextExpectedGateIndex}, crossings=${updatedSession.crossingEvents.takeLast(3)}, completedLaps=${updatedSession.completedLaps.size}"
        )

        _lapSession.value = updatedSession
        _latestLapRecords.value = updatedSession.completedLaps
    }

    private fun createLapSession(trackId: String): LapSession {
        return LapSession(
            sessionId = UUID.randomUUID().toString(),
            trackId = trackId,
            status = LapSessionStatus.Ready
        )
    }

    private fun buildTrackDebugSummary(track: Track): String {
        val startFinish = track.startFinishGate
        val sectors = track.sectorGates.joinToString(separator = ";") { gate ->
            "${gate.id}=${gate.line.start.latitude},${gate.line.start.longitude}->${gate.line.end.latitude},${gate.line.end.longitude}|dir=${gate.passDirection.x},${gate.passDirection.y}"
        }

        return "trackId=${track.id},source=${track.source},layoutName=${track.layoutName},startFinish=${startFinish.line.start.latitude},${startFinish.line.start.longitude}->${startFinish.line.end.latitude},${startFinish.line.end.longitude}|dir=${startFinish.passDirection.x},${startFinish.passDirection.y},sectors=[$sectors]"
    }

    private fun GpsData.toLapGpsSample(): GpsSample = GpsSample(
        timestampMillis = timestamp,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speed,
        bearingDegrees = bearing,
        altitudeMeters = altitude,
        accuracyMeters = hdop
    )

    private fun updateLaunchStatus(gpsData: GpsData) {
        val connectionState = _connectionState.value
        // Requirement 3.5 (c)：lastDataAge 用 elapsedRealtime delta，不依赖 gpsData.timestamp
        // 避免未同步 / sentinel 值污染 launchStatus 数据年龄判定
        val lastDataAge = SystemClock.elapsedRealtime() - lastReceivedAtElapsed

        // 根据测试类型确定起点速度范围
        val template = when (val state = _testState.value) {
            is TestState.Preparing -> state.template
            is TestState.Ready -> state.template
            is TestState.Running -> state.session.template
            else -> null
        }

        val (startSpeedMin, startSpeedMax) = when (template) {
            is TestTemplate.Acceleration0To100 -> 0.0 to 3.0
            is TestTemplate.Braking100To0 -> 95.0 to 105.0
            else -> 0.0 to 3.0
        }

        _launchStatus.value = smartTestLauncher.checkLaunchConditions(
            gpsData, connectionState, lastDataAge,
            startSpeedMin = startSpeedMin,
            startSpeedMax = startSpeedMax
        )
    }

    // 防止重复调用 finishTest
    private var isFinishing = false

    private fun finishTest(session: TestSession) {
        // 同步检查并设置标记，防止异步期间重复调用
        if (isFinishing) return
        isFinishing = true

        viewModelScope.launch {
            val dataFilePath = "pending"
            val result = calculateResultUseCase(session, dataFilePath)
            testResultRepository.saveResult(result)
            _testState.value = TestState.Completed(result)
        }
    }

    fun resetFinishingFlag() {
        isFinishing = false
    }
}
