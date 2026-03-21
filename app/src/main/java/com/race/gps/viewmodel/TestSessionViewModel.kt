package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.data.repository.TestResultRepository
import com.race.gps.domain.model.TestResult
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
 * 集成智能启动测试系统和GPS数据过滤器
 *
 * 设计规范：docs/superpowers/specs/2026-03-21-gps-data-filter-design.md
 *
 * 状态流转：
 * Idle → Preparing(条件检查+倒计时) → Running(速度触发) → Completed → Idle
 */
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher(),
    // GPS数据过滤器
    private val gpsDataFilter: GpsDataFilter = GpsDataFilter()
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

    // Pre-trigger缓冲：存储最近2秒的滤波数据
    private val preTriggerBuffer = mutableListOf<FilteredGpsData>()

    companion object {
        private const val COUNTDOWN_DURATION = 5
        private const val PRE_TRIGGER_DURATION_MS = 2000L
        private const val TRIGGER_ACCELERATION_THRESHOLD = 1.0 // m/s² ≈ 0.1G
        private const val TRIGGER_CONFIRMATION_COUNT = 5 // 连续5个点确认
    }

    init {
        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                lastGpsDataTime = System.currentTimeMillis()

                // 1. 通过过滤器处理数据
                val filteredData = gpsDataFilter.process(gpsData)

                // 2. 更新pre-trigger缓冲
                updatePreTriggerBuffer(filteredData)

                // 3. 更新启动状态（用原始数据检查连接状态等）
                updateLaunchStatus(gpsData)

                // 4. 处理滤波后的数据
                processFilteredData(filteredData)
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
     * 更新pre-trigger缓冲
     * 维护最近2秒的滤波数据滚动窗口
     */
    private fun updatePreTriggerBuffer(filteredData: FilteredGpsData) {
        preTriggerBuffer.add(filteredData)

        // 移除超过2秒的旧数据
        val cutoffTime = filteredData.timestamp - PRE_TRIGGER_DURATION_MS
        while (preTriggerBuffer.isNotEmpty() && preTriggerBuffer.first().timestamp < cutoffTime) {
            preTriggerBuffer.removeAt(0)
        }
    }

    /**
     * 处理滤波后的数据
     * 使用加速度检测替代简单的速度触发
     */
    private fun processFilteredData(filteredData: FilteredGpsData) {
        when (val state = _testState.value) {
            is TestState.Preparing -> {
                // 使用加速度检测触发条件
                if (checkTriggerCondition(filteredData)) {
                    startTest(state.template, state.carModel, filteredData)
                }
            }
            is TestState.Running -> {
                state.session.addFilteredDataPoint(filteredData)
                // 使用原始数据判断结束条件（保持兼容）
                if (state.session.template.shouldEnd(filteredData.raw)) {
                    finishTest(state.session)
                }
            }
            else -> { /* 其他状态不处理 */ }
        }
    }

    /**
     * 检查触发条件
     * 加速度 > 0.1G 且连续5个点确认
     */
    private var consecutiveTriggerCount = 0

    private fun checkTriggerCondition(filteredData: FilteredGpsData): Boolean {
        // 加速度阈值检查：> 1.0 m/s² (约 0.1G)
        if (filteredData.acceleration > TRIGGER_ACCELERATION_THRESHOLD) {
            consecutiveTriggerCount++
            return consecutiveTriggerCount >= TRIGGER_CONFIRMATION_COUNT
        } else {
            // 重置计数器
            consecutiveTriggerCount = 0
            return false
        }
    }

    /**
     * 开始测试
     */
    private fun startTest(
        template: TestTemplate,
        carModel: String,
        filteredData: FilteredGpsData
    ) {
        // 重置触发计数器
        consecutiveTriggerCount = 0

        // 锁定pre-trigger缓冲
        val lockedPreTriggerBuffer = preTriggerBuffer.toList()

        val session = TestSession(
            id = UUID.randomUUID().toString(),
            template = template,
            carModel = carModel,
            startTime = System.currentTimeMillis()
        )

        // 使用新的markStarted接口
        session.markStarted(filteredData, lockedPreTriggerBuffer)

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
