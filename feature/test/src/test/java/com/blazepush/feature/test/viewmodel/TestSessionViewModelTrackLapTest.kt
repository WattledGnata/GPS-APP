package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
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

/**
 * TestSessionViewModel 圈速 / 加减速测试相关单元测试。
 * 覆盖 lap session 全链路（startSession / writeSample / writeCrossing / endSession）+ replay catalog + bridgeGpsToLapTiming。
 *
 * @author CC
 * @description TestSessionViewModel lap-mode unit tests
 * @date 2026-04-30
 */
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

    /**
     * 选择 lap debug 模式后 lapRunConfig 应反映给定 trackId。
     */
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

    /**
     * TFIC 预设 trackId 进入 lap debug 选择流程后 ViewModel 状态正确。
     */
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


    /**
     * 运行时 replay catalog 使用 generated track geometry。
     */
    @Test
    fun lapDebugMode_runtimeReplayCatalogUsesGeneratedTrackGeometry() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            trackCatalog.getAllTracks()  // A37 warm cache：冷 getTrack(TFIC) 走 fallback 不触 IO
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

    /**
     * track debug summary 包含 runtime geometry metadata。
     */
    @Test
    fun lapDebugMode_trackDebugSummaryIncludesRuntimeGeometryMetadata() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            trackCatalog.getAllTracks()  // A37 warm cache
            val viewModel = createViewModel(trackCatalog)

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            val summary = viewModel.currentLapTrackDebugSummary()

            assertFalse(summary.isNullOrBlank())
            assertTrue(requireNotNull(summary).contains("trackId=preset-tfic-lpcc"))
            assertTrue(summary.contains("source=Generated"))
            // layoutName 字段已被 change `enhance-track-presentation` §1.2 / §3.1 移除；
            // 来源标识由 source = TrackSource.Generated 接管（上一行已断）。
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

    /**
     * replay 对齐的 track catalog 产生 accepted start/finish 过线事件。
     */
    @Test
    fun lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            trackCatalog.getAllTracks()  // A37 warm cache
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

    /**
     * 第二次 start/finish 过线即使 sector 链不完整也应闭合 lap。
     */
    @Test
    fun lapDebugMode_secondStartFinishClosesLapEvenWhenSectorChainIsIncomplete() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val trackCatalog = runtimeTrackCatalog()
            trackCatalog.getAllTracks()  // A37 warm cache
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

    /**
     * lap debug 把 TFIC GPS sample 桥接进 lap session，stop 后状态保留。
     */
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

    /**
     * 重新进入 lap debug 创建全新 ready session，不带前次 sample 或 crossing。
     */
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
            testResultRepository = mockTestResultRepositoryWithEmptyFlows(),
            calculateResultUseCase = mock(CalculateResultUseCase::class.java),
            smartTestLauncher = mock(com.blazepush.core.domain.usecase.SmartTestLauncher::class.java),
            gpsDataFilter = gpsDataFilter,
            trackCatalog = trackCatalog,
            lapTimingEngine = LapTimingEngine(),
            telemetryRepository = mockTelemetryRepositoryWithEmptyFlows(),
            recentTracksStore = com.blazepush.feature.test.datastore.FakeRecentTracksStore(),
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

    /**
     * bridgeGpsToLapTiming 时间未同步时跳过该帧并重置 prev。
     */
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

    /**
     * preTrigger buffer 拒绝未同步的 GPS 帧。
     */
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

    /**
     * Preparing 阶段未同步帧不触发 startTest。
     */
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

    /**
     * Running 阶段忽略未同步帧（不污染 dataPoints）。
     */
    @Test
    fun processFilteredData_runningPhase_ignoresUnsyncedFrames() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.enterSmartLaunch(
                template = com.blazepush.core.domain.model.TestTemplate.Acceleration0To100,
                carModel = "test-car"
            )
            viewModel.skipCountdown()
            dispatcher.scheduler.advanceUntilIdle()

            // 先把状态推进到 Running：3 帧静止 + 5 帧加速（全部 isTimeSynced=true，speed 保持 < 100
            // 避免误触发 shouldEnd）。
            repeat(3) { i ->
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = (i * 40).toLong() + 1_000_000L,
                    latitude = 30.49,
                    longitude = 104.43,
                    speed = 0.5,
                    satelliteCount = 10,
                    hdop = 1.0,
                    isTimeSynced = true
                )
                dispatcher.scheduler.advanceUntilIdle()
            }
            // 加速帧约束：
            //   (1) dv 必须 < maxDelta = maxAcceleration(25) × 3.6 × dt(0.04) = 3.6 km/h/帧
            //       否则触发 filter 的 isAnomaly，A14 把异常帧拦在 speedWindow 外，
            //       median 仍靠前 3 帧 speed=0.5 拉平。每帧 +2 km/h 满足 dv=2 < 3.6。
            //   (2) 帧数必须 ≥ 6：静止帧→加速帧 timestamp 跳 920ms > 200ms，A12 重置
            //       previousRaw 导致首个加速帧 acceleration=0（正确行为：信号丢失后首帧
            //       不能凭旧 prev 算加速度），消耗一个触发计数；所以需要 5 + 1 = 6 帧累积
            //       satisfied `consecutiveTriggerCount ≥ TRIGGER_CONFIRMATION_COUNT(5)`。
            repeat(6) { i ->
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = (i * 40).toLong() + 1_001_000L,
                    latitude = 30.49,
                    longitude = 104.43,
                    speed = 10.0 + i * 2.0,
                    satelliteCount = 10,
                    hdop = 1.0,
                    isTimeSynced = true
                )
                dispatcher.scheduler.advanceUntilIdle()
            }

            val runningState = viewModel.testState.value
            assertTrue(
                "前置：前 8 帧应让状态转入 Running",
                runningState is com.blazepush.core.domain.model.TestState.Running
            )
            val session = (runningState as com.blazepush.core.domain.model.TestState.Running).session
            val dataPointsBefore = session.dataPoints.size

            // 喂 5 帧 isTimeSynced=false + sentinel timestamp（filter 会返回零 delta 快照，
            // Running 分支若漏守卫会把 elapsedTime = Long.MIN_VALUE - startTime 溢出值
            // 塞进 dataPoints 污染结果）
            repeat(5) {
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = Long.MIN_VALUE,
                    latitude = 30.49,
                    longitude = 104.43,
                    speed = 30.0,
                    satelliteCount = 10,
                    hdop = 1.0,
                    isTimeSynced = false
                )
                dispatcher.scheduler.advanceUntilIdle()
            }

            assertEquals(
                "未同步帧不应 addFilteredDataPoint 污染 session.dataPoints",
                dataPointsBefore,
                session.dataPoints.size
            )
            assertTrue(
                "未同步帧不应错误触发 finishTest 转 Completed",
                viewModel.testState.value is com.blazepush.core.domain.model.TestState.Running
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * launchStatus 的 lastDataAge 用 elapsedRealtime 而非 GPS 时间戳。
     */
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

    // ==================== 战役 C engine 入口夯实 A38 bridge 三段式回归 ====================
    //
    // Change: openspec/changes/fix-lap-timing-engine-entry-hardening
    //   Requirement 4: bridgeGpsToLapTiming 时间单调守卫 + 三段式 lastLapGpsSample 契约
    //   顺手清理 A34: 首样本分支死码 `_lapSession.value = currentSession` 删除
    //
    // 间接观察手段：
    //   - bridge 段 1（首样本）early return 不调 engine → session.samples.size 不增长
    //   - bridge 段 2（ts 回跳）early return 不调 engine → session.samples.size 不增长 + lastLapGpsSample 保持前帧
    //   - bridge 段 3（正常推进）调 engine → session.samples += currentSample
    //   构造"回跳后紧跟一帧 ts 在 回跳帧 / 前帧 之间"的场景可硬区分 lastLapGpsSample 是否被污染。

    /**
     * 首帧 lap GPS sample 后 lastLapGpsSample 应更新供下一帧用。
     */
    @Test
    fun bridgeGpsToLapTiming_firstSample_updatesLastLapGpsSampleForNextFrame() = runTest {
        // R4 Scenario 1：首样本 MUST 赋 lastLapGpsSample，下一帧才能进入 engine
        //
        // 前置清理：gpsFlow StateFlow 初值 empty(ts=0) 在 selectLapDebugMode 后的 collect
        // 第一次触发中已把 lastLapGpsSample 赋为 empty(ts=0)；先喂一帧 isTimeSynced=false
        // 走 unsynced 分支（战役 A 守卫 `lastLapGpsSample = null`），让后续 emitGps(1_000L)
        // 真正成为"首样本"。
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            // 清 lastLapGpsSample
            gpsFlow.value = emptyGpsSample().copy(
                timestamp = Long.MIN_VALUE,
                latitude = 0.0,
                longitude = 0.0,
                isTimeSynced = false
            )
            dispatcher.scheduler.advanceUntilIdle()
            val samplesAfterReset = viewModel.lapSession.value?.samples?.size ?: 0

            // 真正的首样本 —— bridge 段 1 走首样本分支 early return，engine 未调用，session.samples 不增长
            emitGps(1_000L, 30.4970, 104.4330)
            dispatcher.scheduler.advanceUntilIdle()
            val samplesAfterFirst = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "真正的首样本应走 bridge 段 1 early return，session.samples 不增长",
                samplesAfterReset,
                samplesAfterFirst
            )

            // 第二帧 —— 若首帧正确赋了 lastLapGpsSample，本帧走段 3 正常推进，engine 被调用
            // 硬区分反证：若 R4 段 1 漏赋 lastLapGpsSample，本帧又走段 1，samples 仍不增长
            emitGps(1_040L, 30.4971, 104.4331)
            dispatcher.scheduler.advanceUntilIdle()
            val samplesAfterSecond = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "第二帧应走 bridge 段 3 engine 被调用追加样本；若首帧未赋 lastLapGpsSample 则本帧仍走段 1 samples 不增长",
                samplesAfterReset + 1,
                samplesAfterSecond
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * bridgeGpsToLapTiming 丢弃时间戳回退的 sample。
     */
    @Test
    fun bridgeGpsToLapTiming_dropsSamplesWithRegressingTimestamp() = runTest {
        // R4 Scenario 2：ts 回跳帧 MUST 被 bridge 段 2 整帧丢弃
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            // 建立 lastLapGpsSample（两帧）
            emitGps(1_000L, 30.4970, 104.4330)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_040L, 30.4971, 104.4331)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesBeforeRegression = viewModel.lapSession.value?.samples?.size ?: 0
            assertTrue(
                "第二帧应已进入 session.samples（段 3 正常推进）",
                samplesBeforeRegression >= 1
            )

            // 喂回跳帧：ts=900 < lastLapGpsSample.ts=1_040
            // bridge 段 2 应整帧丢弃 + 不更新 lastLapGpsSample
            emitGps(900L, 30.4972, 104.4332)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesAfterRegression = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "ts 回跳帧应被 bridge 段 2 丢弃，session.samples 不增长",
                samplesBeforeRegression,
                samplesAfterRegression
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * 回退 sample 被丢弃后，下一个前进 sample 应基于上一帧处理。
     */
    @Test
    fun bridgeGpsToLapTiming_afterRegressionDropped_nextForwardSampleIsProcessedAgainstPreviousFrame() = runTest {
        // R4 Scenario 3：回跳帧被丢弃后，lastLapGpsSample MUST 保持为回跳之前的帧
        //   硬区分设计：第四帧 ts 位于 "回跳帧 ts" 和 "前一帧 ts" 之间
        //     - R4 生效：lastLapGpsSample = 前一帧(ts=1_040)，第四帧 ts=1_020 < 1_040 → 再次回跳丢弃
        //     - R4 失效：lastLapGpsSample = 回跳帧(ts=900) 被污染，第四帧 ts=1_020 > 900 → engine 被调用，samples 增长
        //   samples.size 不同 → 硬区分
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            // 建立 lastLapGpsSample
            emitGps(1_000L, 30.4970, 104.4330)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_040L, 30.4971, 104.4331)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesBefore = viewModel.lapSession.value?.samples?.size ?: 0

            // 回跳帧 ts=900
            emitGps(900L, 30.4972, 104.4332)
            dispatcher.scheduler.advanceUntilIdle()
            val samplesAfterRegression = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "前置：回跳帧应被段 2 丢弃",
                samplesBefore,
                samplesAfterRegression
            )

            // 硬区分帧 ts=1_020（介于回跳帧 900 与前一帧 1_040 之间）
            emitGps(1_020L, 30.4973, 104.4333)
            dispatcher.scheduler.advanceUntilIdle()

            val samplesAfterProbe = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "R4 生效：lastLapGpsSample 保持回跳前的 ts=1_040，探测帧 ts=1_020<1_040 再次被段 2 丢弃，samples 不增长；" +
                    "若 R4 失效则 lastLapGpsSample 被污染为回跳帧 ts=900，探测帧 ts=1_020>900 会走段 3，samples 增长。当前断言为 R4 生效版本。",
                samplesBefore,
                samplesAfterProbe
            )

            // 再喂一帧真正前进的样本确认路径恢复正常
            emitGps(1_100L, 30.4974, 104.4334)
            dispatcher.scheduler.advanceUntilIdle()
            val samplesAfterForward = viewModel.lapSession.value?.samples?.size ?: 0
            assertEquals(
                "真正前进的样本（ts=1_100>1_040）应走段 3 正常推进，samples 增长 1",
                samplesBefore + 1,
                samplesAfterForward
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ---- lapIndexForCrossing 语义单元测试 ----

    @Test
    fun `lapIndexForCrossing - opening first lap uses new index not zero`() {
        // prev=0 意味着尚无圈速，首次过线后 updated=1（第一圈开始）
        val result = TestSessionViewModel.lapIndexForCrossing(previousLapIndex = 0, updatedLapIndex = 1)
        assertEquals("开圈事件应使用新 index 1，而不是 0", 1, result)
    }

    @Test
    fun `lapIndexForCrossing - closing lap uses old index not drifted`() {
        // prev=1（圈1进行中），过线闭圈后 updated=2（圈2开始）
        // 该过线事件属于正在结束的圈1，应写 index=1
        val result = TestSessionViewModel.lapIndexForCrossing(previousLapIndex = 1, updatedLapIndex = 2)
        assertEquals("闭圈事件不能漂移到下一圈，应使用旧 index 1", 1, result)
    }

    @Test
    fun `lapIndexForCrossing - sector crossing uses current lap index`() {
        // sector 过线不改变 currentLapIndex（prev==updated）
        val result = TestSessionViewModel.lapIndexForCrossing(previousLapIndex = 2, updatedLapIndex = 2)
        assertEquals("sector 事件应使用当前圈 index 2", 2, result)
    }

    @Test
    fun `lapIndexForCrossing - closing lap 3 uses index 3`() {
        val result = TestSessionViewModel.lapIndexForCrossing(previousLapIndex = 3, updatedLapIndex = 4)
        assertEquals("闭圈事件不漂移，仍为 3", 3, result)
    }
}