package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.TrackSource
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.ReplayAlignedTrackCatalog
import com.blazepush.feature.test.repository.ReplayTrackSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TestSessionViewModelTrackLapTest {

    private companion object {
        private const val DEFAULT_TRACK_ID = "preset-tfic-lpcc"
        private const val REPLAY_JSON_PATH = "feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json"
        private const val REPLAY_VBO_PATH = "feature/test/src/main/assets/replay/tianfu_track.vbo"
    }

    private val dispatcher = StandardTestDispatcher()
    private var gpsFlow = MutableStateFlow(emptyGpsSample())
    private var connectionState = MutableStateFlow(ConnectionState.CONNECTED)

    @Test
    fun selectingLapDebugModeWithTrack_storesLapRunConfig() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            val config = LapRunConfig(trackId = DEFAULT_TRACK_ID)
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertEquals(DEFAULT_TRACK_ID, viewModel.lapRunConfig.value?.trackId)
            assertEquals(config, viewModel.lapRunConfig.value)
            assertTrue(viewModel.availableTracks.value.any { track -> track.id == DEFAULT_TRACK_ID })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectingLapDebugModeWithTficPreset_entersLapDebugSelectionFlow() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            val config = LapRunConfig(trackId = DEFAULT_TRACK_ID)
            viewModel.selectLapDebugMode(config)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(TestMode.LapDebug, viewModel.currentMode.value)
            assertEquals(DEFAULT_TRACK_ID, viewModel.lapRunConfig.value?.trackId)
            assertEquals(DEFAULT_TRACK_ID, viewModel.lapSession.value?.trackId)
            assertTrue(viewModel.availableTracks.value.any { track -> track.id == DEFAULT_TRACK_ID })
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
            val track = requireNotNull(trackCatalog.getTrack(DEFAULT_TRACK_ID))

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            emitCrossing(track.startFinishGate, 1773477876490L, 1773477876690L)
            dispatcher.scheduler.advanceUntilIdle()

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals(DEFAULT_TRACK_ID, session.trackId)
            assertEquals(1, session.currentLapIndex)
            val selectedTrack = trackCatalog.getTrack(DEFAULT_TRACK_ID)
            assertEquals(TrackSource.Generated, selectedTrack?.source)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_trackDebugSummaryIncludesRuntimeGeometryMetadata() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel(runtimeTrackCatalog())

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            val summary = viewModel.currentLapTrackDebugSummary()

            assertFalse(summary.isNullOrBlank())
            assertTrue(summary!!.contains("trackId=preset-tfic-lpcc"))
            assertTrue(summary.contains("source=Generated"))
            assertTrue(summary.contains("layoutName=REAL_TRACK_REPLAY"))
            assertTrue(summary.contains("startFinish="))
            assertTrue(summary.contains("startFinish=30.496167246506413,104.43343794245452->30.49619075349359,104.43291739087881"))
            assertTrue(summary.contains("s1="))
            assertTrue(summary.contains("s1=30.49004451419976,104.43252709154902->30.48959781913357,104.43258157511764"))
            assertTrue(summary.contains("s2="))
            assertTrue(summary.contains("s2=30.4957579139104,104.4369620745035->30.495765752756267,104.43748325882984"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            val viewModel = createViewModel(trackCatalog)
            val track = requireNotNull(trackCatalog.getTrack(DEFAULT_TRACK_ID))

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            emitCrossing(track.startFinishGate, 1773477876490L, 1773477876690L)
            dispatcher.scheduler.advanceUntilIdle()

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals(DEFAULT_TRACK_ID, session.trackId)
            assertEquals(1, session.currentLapIndex)
            assertEquals(1, session.nextExpectedGateIndex)
            assertTrue(session.crossingEvents.any { it.accepted })
            assertTrue(session.crossingEvents.any { it.accepted && it.gateId == "start-finish" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            val viewModel = createViewModel(trackCatalog)
            val track = requireNotNull(trackCatalog.getTrack(DEFAULT_TRACK_ID))

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            emitCrossing(track.startFinishGate, 1773477876490L, 1773477876690L)
            dispatcher.scheduler.advanceUntilIdle()

            emitCrossing(track.startFinishGate, 1773478143490L, 1773478143690L)
            dispatcher.scheduler.advanceUntilIdle()

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals(1, session.completedLaps.size)
            assertEquals(2, session.currentLapIndex)
            assertTrue(session.completedLaps.first().qualityFlags.isNotEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lapDebugMode_bridgesTficGpsSamplesIntoLapSessionAndRetainsStateAfterStop() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
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
            assertEquals(DEFAULT_TRACK_ID, recordingSession.trackId)
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
            val config = LapRunConfig(trackId = DEFAULT_TRACK_ID)

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
        session: LapSession
    ) {
        val field = TestSessionViewModel::class.java.getDeclaredField("_lapSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as MutableStateFlow<LapSession?>
        stateFlow.value = session
    }

    private fun createViewModel(trackCatalog: TrackCatalog = PresetTrackCatalog()): TestSessionViewModel {
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

    private fun runtimeTrackCatalog(): TrackCatalog {
        val projectRoot = projectRoot()

        return ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = File(projectRoot, REPLAY_JSON_PATH).readText()

                override fun loadTrackVbo(): String = File(projectRoot, REPLAY_VBO_PATH).readText()
            },
            fallbackCatalog = PresetTrackCatalog()
        )
    }

    private fun projectRoot(): File {
        val classesDir = File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = File(System.getProperty("user.dir"))

        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { candidate ->
                File(candidate, "settings.gradle").exists() || File(candidate, "settings.gradle.kts").exists()
            }
    }

    private fun emitGps(timestamp: Long, latitude: Double, longitude: Double) {
        gpsFlow.value = emptyGpsSample().copy(
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            speed = 36.0
        )
    }

    private fun emitCrossing(gate: TimingGate, previousTimestamp: Long, currentTimestamp: Long) {
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offsetScale = 0.25
        emitGps(
            previousTimestamp,
            centerLatitude - (gate.passDirection.x * offsetScale),
            centerLongitude - (gate.passDirection.y * offsetScale)
        )
        dispatcher.scheduler.advanceUntilIdle()
        emitGps(
            currentTimestamp,
            centerLatitude + (gate.passDirection.x * offsetScale),
            centerLongitude + (gate.passDirection.y * offsetScale)
        )
        dispatcher.scheduler.advanceUntilIdle()
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
        fixQuality = 1,
        // 默认已同步 —— 历史用例都期望 emit 的帧能走到 engine
        // 新加的"未同步"用例会显式 .copy(isTimeSynced = false) 覆盖
        isTimeSynced = true
    )

    // ==================== v2 (fix-laptime-clock-source-integrity) ====================

    @Test
    fun bridgeGpsToLapTiming_skipsFrameWhenTimeNotSynced_andResetsPrev() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            // 先喂 2 帧 isTimeSynced=true 建立 previousSample（第 1 帧走首样本分支不入列，第 2 帧才进 engine）
            emitGps(1_000L, 30.4970, 104.4330)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_040L, 30.4971, 104.4331)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesBeforeUnsynced = viewModel.lapSession.value?.samples?.size ?: 0
            assertTrue(
                "第 2 帧已同步样本应当进入 session.samples",
                samplesBeforeUnsynced >= 1
            )

            // 喂 1 帧未同步 —— 应被整帧跳过，不改变 session
            gpsFlow.value = emptyGpsSample().copy(
                timestamp = Long.MIN_VALUE,
                latitude = 30.4980,
                longitude = 104.4340,
                speed = 36.0,
                isTimeSynced = false
            )
            dispatcher.scheduler.advanceUntilIdle()

            val samplesAfterUnsynced = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "未同步帧不应进入 session.samples",
                samplesBeforeUnsynced,
                samplesAfterUnsynced
            )

            // 再喂 1 帧同步 —— 由于 lastLapGpsSample 在未同步帧里被重置为 null，
            // 本帧走首样本分支（previousSample == null），不调 engine、不入列
            emitGps(2_000L, 30.4990, 104.4350)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesAfterResumeFirstFrame = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "失联恢复后首帧应走首样本分支（previousSample == null），不应进入 session.samples",
                samplesBeforeUnsynced,
                samplesAfterResumeFirstFrame
            )

            // 再喂 1 帧同步 —— 这次 lastLapGpsSample 已是上一帧，engine 被调用，样本入列
            emitGps(2_040L, 30.4991, 104.4351)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesAfterResumeSecondFrame = viewModel.lapSession.value?.samples?.size ?: 0
            assertTrue(
                "失联恢复后第 2 帧同步样本应进入 session.samples",
                samplesAfterResumeSecondFrame > samplesBeforeUnsynced
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun preTriggerBuffer_rejectsUnsyncedFrames() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.enterSmartLaunch(
                template = com.blazepush.core.domain.model.TestTemplate.Acceleration0To100,
                carModel = "test-car"
            )
            dispatcher.scheduler.advanceUntilIdle()

            val field = TestSessionViewModel::class.java.getDeclaredField("preTriggerBuffer")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val buffer = field.get(viewModel) as MutableList<Any?>
            // gpsFlow 初值 emptyGpsSample() 携带 isTimeSynced=true，ViewModel 订阅时会收到；
            // 清一下保证本测试只观察"未同步帧的行为"
            buffer.clear()

            // 喂 5 帧未同步 —— 不应进 preTriggerBuffer
            repeat(5) { i ->
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = Long.MIN_VALUE,
                    latitude = 30.49 + i * 0.0001,
                    longitude = 104.43,
                    speed = 1.0,
                    isTimeSynced = false
                )
                dispatcher.scheduler.advanceUntilIdle()
            }

            assertEquals(
                "未同步帧不应进入 preTriggerBuffer",
                0,
                buffer.size
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun processFilteredData_preparingPhase_doesNotTriggerWhenUnsynced() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.enterSmartLaunch(
                template = com.blazepush.core.domain.model.TestTemplate.Acceleration0To100,
                carModel = "test-car"
            )
            // 跳过倒计时
            viewModel.skipCountdown()
            dispatcher.scheduler.advanceUntilIdle()

            // 此时状态是 Preparing 且倒计时=0；喂一个"满足触发条件但未同步"的样本
            // （满足 Acceleration 触发：先静止 3 帧 + 后续加速 5 帧；但所有帧 isTimeSynced=false，应全部被守卫拦）
            repeat(10) { i ->
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = Long.MIN_VALUE,
                    latitude = 30.49,
                    longitude = 104.43,
                    speed = if (i < 5) 0.5 else 10.0,
                    isTimeSynced = false
                )
                dispatcher.scheduler.advanceUntilIdle()
            }

            assertTrue(
                "未同步时不应从 Preparing 转入 Running",
                viewModel.testState.value is com.blazepush.core.domain.model.TestState.Preparing
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun launchStatus_lastDataAgeUsesElapsedRealtime_notGpsTimestamp() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()

            // 喂一帧 isTimeSynced=true，timestamp = 1_000L
            gpsFlow.value = emptyGpsSample().copy(
                timestamp = 1_000L,
                latitude = 30.49,
                longitude = 104.43,
                speed = 1.0,
                satelliteCount = 10,
                hdop = 1.0,
                isTimeSynced = true
            )
            dispatcher.scheduler.advanceUntilIdle()

            // JVM 单测下 SystemClock.elapsedRealtime() 默认返回 0L（isReturnDefaultValues=true），
            // 所以 lastReceivedAtElapsed 在测试环境里也是 0L；
            // 关键断言是：它**不等于** gpsData.timestamp(1_000L)，说明生产代码确实切换到了独立时钟源
            val field = TestSessionViewModel::class.java.getDeclaredField("lastReceivedAtElapsed")
            field.isAccessible = true
            val elapsed = field.getLong(viewModel)
            assertTrue(
                "lastReceivedAtElapsed 必须独立于 gpsData.timestamp；" +
                    "JVM 单测里 SystemClock.elapsedRealtime() 默认返回 0L，gpsData.timestamp=1_000L；" +
                    "两者不等证明生产路径使用了独立的 elapsedRealtime 时钟源（要求 3.5 (c)）",
                elapsed != 1_000L
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}
