package com.race.gps.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.FileLogger
import com.race.gps.bluetooth.BleDeviceManager
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.data.repository.TestResultRepository
import com.race.gps.domain.model.GpsData
import com.race.gps.domain.model.TestSession
import com.race.gps.domain.model.TestState
import com.race.gps.domain.model.TestTemplate
import com.race.gps.domain.usecase.CalculateResultUseCase
import com.race.gps.domain.usecase.FilteredGpsData
import com.race.gps.domain.usecase.GpsDataFilter
import com.race.gps.domain.usecase.SmartTestLauncher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 测试会话ViewModel - 管理测试状态机
 */
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val bleDeviceManager: BleDeviceManager,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher(),
    private val gpsDataFilter: GpsDataFilter = GpsDataFilter()
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
    private var lastDataTime = 0L
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
                val filteredData = gpsDataFilter.process(gpsData)
                lastDataTime = gpsData.timestamp
                updatePreTriggerBuffer(filteredData)
                updateLaunchStatus(gpsData)
                processFilteredData(filteredData)
            }
        }
    }

    fun enterSmartLaunch(template: TestTemplate, carModel: String) {
        isStartReady = false
        standstillCount = 0
        consecutiveTriggerCount = 0
        _testState.value = TestState.Preparing(template, carModel)
        startCountdown()
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
        preTriggerBuffer.add(filteredData)
        val cutoffTime = filteredData.timestamp - PRE_TRIGGER_DURATION_MS
        while (preTriggerBuffer.isNotEmpty() && preTriggerBuffer.first().timestamp < cutoffTime) {
            preTriggerBuffer.removeAt(0)
        }
    }

    private fun processFilteredData(filteredData: FilteredGpsData) {
        when (val state = _testState.value) {
            is TestState.Preparing -> {
                if (_countdownSeconds.value == 0) {
                    if (checkTriggerCondition(filteredData, state.template)) {
                        startTest(state.template, state.carModel, filteredData)
                    }
                }
            }
            is TestState.Running -> {
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
            else -> filteredData.acceleration > TRIGGER_ACCELERATION_THRESHOLD
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

    private fun updateLaunchStatus(gpsData: GpsData) {
        val connectionState = _connectionState.value
        val lastDataAge = System.currentTimeMillis() - lastDataTime

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

    private fun finishTest(session: TestSession) {
        viewModelScope.launch {
            val dataFilePath = "pending"
            val result = calculateResultUseCase(session, dataFilePath)
            testResultRepository.saveResult(result)
            _testState.value = TestState.Completed(result)
        }
    }
}
