package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.FilteredGpsData
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.TrackSource
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.ReplayAlignedTrackCatalog
import com.blazepush.feature.test.repository.ReplayTrackSource
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blazepush.core.domain.usecase.GpsDataFilter
import kotlinx.coroutines.flow.MutableStateFlow as MutableStateFlowType
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackLapTest {

    private val dispatcher = StandardTestDispatcher()
    private var gpsFlow = MutableStateFlow(emptyGpsSample())
    private var connectionState = MutableStateFlow(ConnectionState.CONNECTED)

    @Test
    fun selectingLapDebugModeWithTrack_storesLapRunConfig() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            val config = LapRunConfig(trackId = "preset-tfic-lpcc")
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertEquals("preset-tfic-lpcc", viewModel.lapRunConfig.value?.trackId)
            assertEquals(config, viewModel.lapRunConfig.value)
            assertTrue(viewModel.availableTracks.value.any { track -> track.id == "preset-tfic-lpcc" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectingLapDebugModeWithTficPreset_entersLapDebugSelectionFlow() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            val config = LapRunConfig(trackId = "preset-tfic-lpcc")
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertEquals("preset-tfic-lpcc", viewModel.lapRunConfig.value?.trackId)
            assertEquals("preset-tfic-lpcc", viewModel.lapSession.value?.trackId)
            assertTrue(viewModel.availableTracks.value.any { track -> track.id == "preset-tfic-lpcc" })
        } finally {
            Dispatchers.resetMain()
        }
    }


    @Test
    fun lapDebugMode_runtimeReplayCatalogUsesGeneratedTrackGeometry() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            val viewModel = createViewModel(trackCatalog)

            viewModel.selectLapDebugMode("preset-tfic-lpcc")
            dispatcher.scheduler.advanceUntilIdle()

            emitGps(1773477876490L, 30.4941093, 104.4334198)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1773477876690L, 30.4941096, 104.4334258)
            dispatcher.scheduler.advanceUntilIdle()

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals("preset-tfic-lpcc", session.trackId)
            assertEquals(1, session.currentLapIndex)
            val selectedTrack = trackCatalog.getTrack("preset-tfic-lpcc")
            assertEquals(TrackSource.Generated, selectedTrack?.source)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(runtimeTrackCatalog())

            viewModel.selectLapDebugMode("preset-tfic-lpcc")
            dispatcher.scheduler.advanceUntilIdle()

            emitGps(1773477876490L, 30.4941093, 104.4334198)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1773477876690L, 30.4941096, 104.4334258)
            dispatcher.scheduler.advanceUntilIdle()

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals("preset-tfic-lpcc", session.trackId)
            assertEquals(1, session.currentLapIndex)
            assertEquals(1, session.nextExpectedGateIndex)
            assertTrue(session.crossingEvents.any { it.accepted })
            assertTrue(session.crossingEvents.any { it.accepted && it.gateId == "start-finish" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_bridgesTficGpsSamplesIntoLapSessionAndRetainsStateAfterStop() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            viewModel.selectLapDebugMode("preset-tfic-lpcc")
            dispatcher.scheduler.advanceUntilIdle()

            emitGps(500L, 30.49681330622183, 104.43181645726466)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_000L, 30.49681330622183, 104.43181645726466)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(2_000L, 30.49670954528353, 104.43448713340022)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(3_000L, 30.488409019777094, 104.43452055830684)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(4_000L, 30.49112394730996, 104.43394397527129)
            dispatcher.scheduler.advanceUntilIdle()

            val recordingSession = requireNotNull(viewModel.lapSession.value)
            assertEquals("preset-tfic-lpcc", recordingSession.trackId)
            assertTrue(recordingSession.samples.isNotEmpty())
            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertTrue(viewModel.latestLapRecords.value.isEmpty())

            viewModel.stopLapDebugSession()
            dispatcher.scheduler.advanceUntilIdle()

            val stoppedSession = viewModel.lapSession.value
            val stoppedRecords = viewModel.latestLapRecords.value

            emitGps(4_000L, 30.4903109, 104.4329748)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(5_000L, 30.4905453, 104.4350638)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(stoppedRecords, viewModel.latestLapRecords.value)
            assertEquals(stoppedSession, viewModel.lapSession.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_reentryCreatesFreshReadySessionWithoutPreviousSamplesOrCrossings() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            val config = LapRunConfig(trackId = "preset-tfic-lpcc")

            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            val firstSession = requireNotNull(viewModel.lapSession.value)
            val firstSessionId = firstSession.sessionId
            val dirtyFirstSession = firstSession.copy(
                status = LapSessionStatus.Recording,
                samples = listOf(
                    GpsSample(
                        timestampMillis = 1_000L,
                        latitude = 30.4941093,
                        longitude = 104.4334198,
                        speedKmh = 36.0
                    )
                ),
                crossingEvents = listOf(
                    CrossingEvent(
                        gateId = "start-finish",
                        gateType = TimingGateType.StartFinish,
                        timestampMillis = 1_000L,
                        sampleIndex = 0,
                        accepted = true,
                        reason = CrossingReason.Accepted
                    )
                )
            )
            setLapSession(viewModel, dirtyFirstSession)

            val updatedFirstSession = requireNotNull(viewModel.lapSession.value)
            assertTrue(updatedFirstSession.samples.isNotEmpty())
            assertTrue(updatedFirstSession.crossingEvents.isNotEmpty())

            viewModel.stopLapDebugSession()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.exitLapDebugMode()
            dispatcher.scheduler.advanceUntilIdle()
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            val secondSession = requireNotNull(viewModel.lapSession.value)
            assertNotEquals(firstSessionId, secondSession.sessionId)
            assertEquals(LapSessionStatus.Ready, secondSession.status)
            assertTrue(secondSession.samples.isEmpty())
            assertTrue(secondSession.crossingEvents.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun setLapSession(
        viewModel: TestSessionViewModel,
        session: com.blazepush.feature.test.model.laptiming.LapSession
    ) {
        val field = TestSessionViewModel::class.java.getDeclaredField("_lapSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlowType<com.blazepush.feature.test.model.laptiming.LapSession?>
        stateFlow.value = session
    }

    private fun createViewModel(trackCatalog: com.blazepush.feature.test.repository.TrackCatalog = PresetTrackCatalog()): TestSessionViewModel {
        gpsFlow = MutableStateFlow(emptyGpsSample())
        connectionState = MutableStateFlow(ConnectionState.CONNECTED)

        val gpsDataViewModel = mock(GpsDataViewModel::class.java)
        doReturn(gpsFlow).`when`(gpsDataViewModel).gpsData

        val bleDeviceManager = mock(BleDeviceManager::class.java)
        doReturn(connectionState).`when`(bleDeviceManager).connectionState

        val gpsDataFilter = GpsDataFilter()

        return TestSessionViewModel(
            gpsDataViewModel = gpsDataViewModel,
            bleDeviceManager = bleDeviceManager,
            testResultRepository = mock(TestResultRepository::class.java),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            smartTestLauncher = mock(com.blazepush.core.domain.usecase.SmartTestLauncher::class.java),
            gpsDataFilter = gpsDataFilter,
            trackCatalog = trackCatalog,
            lapTimingEngine = LapTimingEngine()
        )
    }


    private fun runtimeTrackCatalog() = ReplayAlignedTrackCatalog(
        replayTrackSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String = java.io.File(
                projectRoot(),
                "feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json"
            ).readText()

            override fun loadTrackVbo(): String = java.io.File(
                projectRoot(),
                "feature/test/src/main/assets/replay/tianfu_track.vbo"
            ).readText()
        },
        fallbackCatalog = PresetTrackCatalog()
    )

    private fun projectRoot(): java.io.File {
        val classesDir = java.io.File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = java.io.File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { java.io.File(it, "settings.gradle").exists() || java.io.File(it, "settings.gradle.kts").exists() }
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
