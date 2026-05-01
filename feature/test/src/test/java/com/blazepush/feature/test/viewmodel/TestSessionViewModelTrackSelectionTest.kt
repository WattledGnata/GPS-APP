// @IgnoreFormatCheck
// 理由：JUnit4 测试类命名 snake_case 承载 Gherkin 语义；本文件随 change
//       enhance-track-presentation §5.2 新建，验证 currentSelectedTrack 状态。
//       与 TestSessionViewModelTrackLoadingTest 同形态、同纪律豁免。
package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackSelectionTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun init_currentSelectedTrackEqualsFirstAvailable_afterTracksLoaded() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(PresetTrackCatalog())

            // 构造期 launch 未推进，availableTracks 仍空，currentSelectedTrack 也应为 null
            assertNull(viewModel.currentSelectedTrack.value)

            dispatcher.scheduler.advanceUntilIdle()

            val first = viewModel.availableTracks.value.first()
            assertEquals(first, viewModel.currentSelectedTrack.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun init_currentSelectedTrackStaysNull_whenAvailableTracksEmpty() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val emptyCatalog = object : TrackCatalog {
                override suspend fun getAllTracks(): List<Track> = emptyList()
                override fun getTrack(trackId: String): Track? = null
            }
            val viewModel = createViewModel(emptyCatalog)

            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(emptyList<Track>(), viewModel.availableTracks.value)
            assertNull(viewModel.currentSelectedTrack.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectTrack_updatesCurrentSelectedTrackImmediately() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(PresetTrackCatalog())
            dispatcher.scheduler.advanceUntilIdle()

            val tracks = viewModel.availableTracks.value
            val target = tracks.first()

            viewModel.selectTrack(target)

            assertEquals(target, viewModel.currentSelectedTrack.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(trackCatalog: TrackCatalog): TestSessionViewModel {
        val gpsFlow = MutableStateFlow<com.blazepush.core.domain.model.GpsData>(
            com.blazepush.core.domain.model.GpsData.Empty
        )
        val connectionState = MutableStateFlow(ConnectionState.CONNECTED)

        val gpsDataViewModel = mock(GpsDataViewModel::class.java)
        doReturn(gpsFlow).`when`(gpsDataViewModel).gpsData

        val bleDeviceManager = mock(BleDeviceManager::class.java)
        doReturn(connectionState).`when`(bleDeviceManager).connectionState

        return TestSessionViewModel(
            gpsDataViewModel = gpsDataViewModel,
            bleDeviceManager = bleDeviceManager,
            testResultRepository = mock(TestResultRepository::class.java),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            smartTestLauncher = mock(SmartTestLauncher::class.java),
            gpsDataFilter = GpsDataFilter(),
            trackCatalog = trackCatalog,
            lapTimingEngine = LapTimingEngine(),
            telemetryRepository = mock(TelemetryRepository::class.java),
        )
    }
}
