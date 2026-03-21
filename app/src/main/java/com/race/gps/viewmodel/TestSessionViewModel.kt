package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.data.repository.TestResultRepository
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.model.TestSession
import com.race.gps.domain.model.TestState
import com.race.gps.domain.model.TestTemplate
import com.race.gps.domain.usecase.CalculateResultUseCase
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
 * 集成智能启动测试系统
 *
 * 状态流转：
 * Idle → Preparing(条件检查+倒计时) → Running(速度触发) → Completed → Idle
 */
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher()
) : ViewModel() {

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    // 智能启动状态
    private val _launchStatus = MutableStateFlow(
        SmartTestLauncher.LaunchStatus(
            conditions = emptyList(),
            canLaunch = false,
            unmetConditionIds = emptyList()
        )
    )
    val launchStatus: StateFlow<SmartTestLauncher.LaunchStatus> = _launchStatus.asStateFlow()

    // 倒计时状态
    private val _countdownSeconds = MutableStateFlow(5)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    // 倒计时协程
    private var countdownJob: Job? = null

    // 记录最后一次GPS数据时间，用于计算数据年龄
    private var lastGpsDataTime = 0L

    companion object {
        private const val COUNTDOWN_DURATION = 5
    }

    init {
        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                lastGpsDataTime = System.currentTimeMillis()
                updateLaunchStatus(gpsData)
                processGpsData(gpsData)
            }
        }
    }

    /**
     * 进入智能启动检查阶段
     */
    fun enterSmartLaunch(template: TestTemplate, carModel: String) {
        _countdownSeconds.value = COUNTDOWN_DURATION
        _testState.value = TestState.Preparing(template, carModel)
    }

    /**
     * 用户手动点击开始（跳过倒计时）
     */
    fun skipCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
    }

    /**
     * 取消测试
     */
    fun cancelTest() {
        countdownJob?.cancel()
        countdownJob = null
        _testState.value = TestState.Idle
    }

    /**
     * 更新启动状态
     */
    private fun updateLaunchStatus(gpsData: com.race.gps.domain.model.GpsData) {
        val currentState = _testState.value
        if (currentState !is TestState.Preparing) return

        val connectionState = gpsDataViewModel.connectionState.value
        val lastDataAge = if (gpsData.timestamp > 0) {
            System.currentTimeMillis() - gpsData.timestamp
        } else {
            System.currentTimeMillis() - lastGpsDataTime
        }

        // 根据测试类型确定起点速度范围
        val (startSpeedMin, startSpeedMax) = getStartSpeedRange(currentState.template)

        val newStatus = smartTestLauncher.checkLaunchConditions(
            gpsData = gpsData,
            connectionState = connectionState,
            lastDataAge = lastDataAge,
            startSpeedMin = startSpeedMin,
            startSpeedMax = startSpeedMax
        )
        _launchStatus.value = newStatus

        // 条件满足时启动倒计时
        if (newStatus.canLaunch && countdownJob == null) {
            startCountdown()
        }
        // 条件不满足时取消倒计时
        if (!newStatus.canLaunch && countdownJob != null) {
            cancelCountdown()
        }
    }

    /**
     * 根据测试类型获取起点速度范围
     */
    private fun getStartSpeedRange(template: TestTemplate): Pair<Double, Double> {
        return when (template) {
            // 0-100 加速：需要接近静止
            is TestTemplate.Acceleration0To100 -> Pair(0.0, 3.0)
            // 100-0 刹车：需要接近 100 km/h
            is TestTemplate.Braking100To0 -> Pair(95.0, 105.0)
            else -> Pair(0.0, 5.0)
        }
    }

    /**
     * 启动5秒倒计时（视觉提示）
     */
    private fun startCountdown() {
        _countdownSeconds.value = COUNTDOWN_DURATION
        countdownJob = viewModelScope.launch {
            for (i in COUNTDOWN_DURATION downTo 1) {
                _countdownSeconds.value = i
                delay(1000)
            }
            // 倒计时结束，显示0，不自动开始
            _countdownSeconds.value = 0
            countdownJob = null
        }
    }

    /**
     * 取消倒计时
     */
    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = COUNTDOWN_DURATION
    }

    /**
     * 处理GPS数据：速度触发启动计时
     */
    private fun processGpsData(gpsData: com.race.gps.domain.model.GpsData) {
        when (val state = _testState.value) {
            is TestState.Preparing -> {
                // 速度触发 → 开始测试
                if (state.template.shouldTrigger(gpsData)) {
                    startTest(state.template, state.carModel, gpsData)
                }
            }
            is TestState.Running -> {
                state.session.addDataPoint(gpsData)
                if (state.session.template.shouldEnd(gpsData)) {
                    finishTest(state.session)
                }
            }
            else -> { /* 其他状态不处理 */ }
        }
    }

    /**
     * 开始测试
     */
    private fun startTest(template: TestTemplate, carModel: String, gpsData: com.race.gps.domain.model.GpsData) {
        val session = TestSession(
            id = UUID.randomUUID().toString(),
            template = template,
            carModel = carModel,
            startTime = System.currentTimeMillis()
        )
        session.markStarted(gpsData)
        _testState.value = TestState.Running(session)
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
