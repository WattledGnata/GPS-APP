package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackLapTest {

    private val dispatcher = StandardTestDispatcher()
    private val gpsFlow = MutableStateFlow(emptyGpsSample())
    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)

    @Test
    fun selectingLapDebugModeWithTrack_storesLapRunConfig() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            val config = LapRunConfig(trackId = "preset-demo-circuit")
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertEquals("preset-demo-circuit", viewModel.lapRunConfig.value?.trackId)
            assertEquals(config, viewModel.lapRunConfig.value)
            assertTrue(viewModel.availableTracks.value.any { track -> track.id == "preset-demo-circuit" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_bridgesGpsSamplesIntoLapSessionAndRetainsResultsAfterStop() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            viewModel.selectLapDebugMode("preset-demo-circuit")
            dispatcher.scheduler.advanceUntilIdle()

            emitGps(999L, 39.8980, 116.3999)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_000L, 39.9020, 116.4001)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(5_999L, 39.9006, 119.0000)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(6_000L, 39.9007, 122.0000)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(10_999L, 39.8980, 116.3998)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(11_000L, 39.9020, 116.4002)
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull(viewModel.lapSession.value)
            assertEquals(1, viewModel.latestLapRecords.value.size)
            assertEquals(1, viewModel.latestLapRecords.value.first().lapIndex)

            viewModel.stopLapDebugSession()
            dispatcher.scheduler.advanceUntilIdle()

            val stoppedSession = viewModel.lapSession.value
            val stoppedRecords = viewModel.latestLapRecords.value

            emitGps(16_000L, 39.9021, 116.4003)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(21_000L, 39.8981, 116.3997)
            dispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.latestLapRecords.value.isEmpty())
            assertEquals(1, viewModel.latestLapRecords.value.first().lapIndex)
            assertEquals(stoppedRecords, viewModel.latestLapRecords.value)
            assertEquals(stoppedSession, viewModel.lapSession.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(): TestSessionViewModel {
        val gpsDataViewModel = mock(GpsDataViewModel::class.java)
        `when`(gpsDataViewModel.gpsData).thenReturn(gpsFlow)

        val bleDeviceManager = mock(BleDeviceManager::class.java)
        `when`(bleDeviceManager.connectionState).thenReturn(connectionState)

        return TestSessionViewModel(
            gpsDataViewModel = gpsDataViewModel,
            bleDeviceManager = bleDeviceManager,
            testResultRepository = mock(TestResultRepository::class.java),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            trackCatalog = PresetTrackCatalog(),
            lapTimingEngine = LapTimingEngine()
        )
    }

    private fun emitGps(timestamp: Long, latitude: Double, longitude: Double) {
        gpsFlow.value = emptyGpsSample().copy(
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            speed = 36.0
        )
    }

    private fun emptyGpsSample() = GpsData(
        timestamp = 0L,
        speed = 0.0,
        latitude = 0.0,
        longitude = 0.0,
        altitude = 0.0,
        bearing = 0.0,
        satelliteCount = 0,
        hdop = 0.0,
        vdop = 0.0,
        frequency = 10.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null,
        fixQuality = 1
    )
}
