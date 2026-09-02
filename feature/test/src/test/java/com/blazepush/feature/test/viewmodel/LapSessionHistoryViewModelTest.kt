package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TelemetrySessionType
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.datastore.FakeRecentTracksStore
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class LapSessionHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `current track history exposes all seven stored sessions`() = runTest(dispatcher) {
        val storedSessions = (1..7).map { index -> lapSession(index) }
        val telemetryRepository = mockTelemetryRepositoryWithEmptyFlows()
        doAnswer { invocation ->
            val limit = invocation.getArgument<Int>(1)
            flowOf(storedSessions.take(limit))
        }.`when`(telemetryRepository).getRecentSessionsForTrack(anyString(), anyInt())

        val viewModel = createViewModel(telemetryRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.recentSessionsForCurrentTrack.collect()
        }
        advanceUntilIdle()

        assertEquals(storedSessions, viewModel.recentSessionsForCurrentTrack.value)
    }

    private fun createViewModel(telemetryRepository: TelemetryRepository): TestSessionViewModel {
        val gpsDataViewModel = mock(GpsDataViewModel::class.java)
        doReturn(MutableStateFlow(GpsData.Empty)).`when`(gpsDataViewModel).gpsData

        val bleDeviceManager = mock(BleDeviceManager::class.java)
        doReturn(MutableStateFlow(ConnectionState.CONNECTED)).`when`(bleDeviceManager).connectionState

        return TestSessionViewModel(
            gpsDataViewModel = gpsDataViewModel,
            bleDeviceManager = bleDeviceManager,
            testResultRepository = mockTestResultRepositoryWithEmptyFlows(),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            smartTestLauncher = mock(SmartTestLauncher::class.java),
            gpsDataFilter = GpsDataFilter(),
            trackCatalog = PresetTrackCatalog(),
            lapTimingEngine = LapTimingEngine(),
            telemetryRepository = telemetryRepository,
            recentTracksStore = FakeRecentTracksStore(),
            lapUploadOrchestrator = FakeLapUploadTrigger(),
        )
    }

    private fun lapSession(index: Int) = TelemetrySession(
        sessionId = "session-$index",
        sessionType = TelemetrySessionType.LAP_SESSION,
        startTs = index.toLong(),
        endTs = index + 1L,
        binaryFilePath = "",
        lapCount = index,
        trackId = "preset-tfic-lpcc",
    )
}
