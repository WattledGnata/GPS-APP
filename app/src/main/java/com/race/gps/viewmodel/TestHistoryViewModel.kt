package com.race.gps.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.race.gps.data.local.entity.TestRecordEntity
import com.race.gps.data.repository.TestResultRepository
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
