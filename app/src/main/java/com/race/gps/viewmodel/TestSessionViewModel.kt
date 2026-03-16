package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.data.repository.TestResultRepository
import com.race.gps.data.local.entity.TestRecordEntity
import com.race.gps.domain.model.TestResult
import com.race.gps.domain.model.TestSession
import com.race.gps.domain.model.TestState
import com.race.gps.domain.model.TestTemplate
import com.race.gps.domain.usecase.CalculateResultUseCase
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
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase
) : ViewModel() {

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    init {
        // 订阅GPS数据流，处理测试逻辑
        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                processGpsData(gpsData)
            }
        }
    }

    fun startTest(template: TestTemplate, carModel: String) {
        _testState.value = TestState.Waiting(template, carModel)
    }

    fun cancelTest() {
        _testState.value = TestState.Idle
    }

    private fun processGpsData(gpsData: com.race.gps.domain.model.GpsData) {
        when (val state = _testState.value) {
            is TestState.Waiting -> {
                if (state.template.shouldTrigger(gpsData)) {
                    val session = TestSession(
                        id = UUID.randomUUID().toString(),
                        template = state.template,
                        carModel = state.carModel,
                        startTime = System.currentTimeMillis()
                    )
                    session.markStarted(gpsData)
                    _testState.value = TestState.Running(session)
                }
            }
            is TestState.Running -> {
                state.session.addDataPoint(gpsData)
                if (state.template.shouldEnd(gpsData)) {
                    finishTest(state.session)
                }
            }
            else -> { /* 其他状态不处理 */ }
        }
    }

    private fun finishTest(session: TestSession) {
        viewModelScope.launch {
            val dataFilePath = "pending" // 先占位，saveResult时会更新
            val result = calculateResultUseCase(session, dataFilePath)
            testResultRepository.saveResult(result)
            _testState.value = TestState.Completed(result)
        }
    }
}
