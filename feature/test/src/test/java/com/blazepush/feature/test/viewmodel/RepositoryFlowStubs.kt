// @IgnoreFormatCheck
// 理由：测试支持类，class-comment 等规范由 D round 统一处理；本文件随
//       round wire-real-data-to-records-and-laps-tabs §2.3 新建。
package com.blazepush.feature.test.viewmodel

import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TestResultSummary
import com.blazepush.core.domain.model.TestTemplate
import kotlinx.coroutines.flow.flowOf
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/**
 * ViewModel test helper 共用 stub：返回 mockito mock + stub 全部新增 Flow 方法返回
 * 空数据。避免 [com.blazepush.feature.test.viewmodel.TestSessionViewModel] 顶层
 * `.stateIn(...)` 调 mock 默认 null 返回 NPE。
 *
 * 用法：所有 `TestSessionViewModel*Test.createViewModel(...)` 默认参数走这两个工厂，
 * 单测如需覆盖某个 trackId 的 Flow，再用 `doReturn(flowOf(...)).` `` `when` `` `(repo).method(...)`
 * 重新 stub。
 *
 * **MUST 用 `doReturn(...).` `` `when` `` `(...)` 风格**（工程无 mockito-kotlin、`whenever` 不可用）。
 */
fun mockTestResultRepositoryWithEmptyFlows(): TestResultRepository {
    val repo = mock(TestResultRepository::class.java)
    doReturn(flowOf<TestResultSummary?>(null)).`when`(repo).getBestResult(TestTemplate.Acceleration0To100)
    doReturn(flowOf<TestResultSummary?>(null)).`when`(repo).getBestResult(TestTemplate.Braking100To0)
    doReturn(flowOf(0)).`when`(repo).getTotalRunCount()
    doReturn(flowOf<List<TestResultSummary>>(emptyList())).`when`(repo).getRecentResultsFlow(anyInt())
    return repo
}

fun mockTelemetryRepositoryWithEmptyFlows(): TelemetryRepository {
    val repo = mock(TelemetryRepository::class.java)
    doReturn(flowOf<TelemetrySession?>(null)).`when`(repo).getBestLapForTrack(anyString())
    doReturn(flowOf(0)).`when`(repo).getSessionCountForTrack(anyString())
    doReturn(flowOf(0)).`when`(repo).getTotalLapCountForTrack(anyString())
    doReturn(flowOf<List<TelemetrySession>>(emptyList())).`when`(repo).getRecentSessionsForTrack(anyString(), anyInt())
    return repo
}
