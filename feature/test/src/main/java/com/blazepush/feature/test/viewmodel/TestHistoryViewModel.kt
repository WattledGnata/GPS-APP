package com.blazepush.feature.test.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.data.repository.TestResultRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 测试历史ViewModel
 */
class TestHistoryViewModel(
    private val testResultRepository: TestResultRepository
) : ViewModel() {

    val testRecords: StateFlow<List<TestRecordEntity>> =
        testResultRepository.testResultsFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    fun deleteRecord(record: TestRecordEntity) {
        viewModelScope.launch {
            testResultRepository.deleteResult(record)
        }
    }
}
