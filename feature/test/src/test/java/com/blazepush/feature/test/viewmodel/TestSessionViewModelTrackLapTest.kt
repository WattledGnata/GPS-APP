package com.blazepush.feature.test.viewmodel

import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TimingHandshakeState
import com.blazepush.core.domain.model.TelemetrySessionType
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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
    private var nextMainFrameSequence = 2L

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

    @Test
    fun `entering lap page eagerly persists one session before REC`() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockTelemetryRepositoryWithEmptyFlows()
            doReturn("persisted-session").`when`(repository).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
            val viewModel = createViewModel(telemetryRepository = repository)
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)

            assertEquals("persisted-session", viewModel.getActiveLapSessionId())
            assertEquals("persisted-session", viewModel.prepareActiveLapSessionForRecording())
            assertEquals("persisted-session", viewModel.prepareActiveLapSessionForRecording())
            assertTrue(viewModel.isActiveLapSession("persisted-session"))
            verify(repository, times(1)).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `START while device disconnected persists session and waits for device`() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockTelemetryRepositoryWithEmptyFlows()
            doReturn("offline-session").`when`(repository).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
            val viewModel = createViewModel(telemetryRepository = repository)
            connectionState.value = ConnectionState.DISCONNECTED
            gpsFlow.value = GpsData.Empty
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("offline-session", viewModel.getActiveLapSessionId())
            assertEquals(LapGpsReadiness.WAITING_DEVICE, viewModel.lapGpsReadiness.value)
            assertEquals(LapSessionStatus.Ready, viewModel.lapSession.value?.status)
            verify(repository, times(1)).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `unreliable stale stabilizing and old generation frames never feed lap engine`() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            gpsFlow.value = emptyGpsSample().copy(
                timestamp = 1_000L,
                connectionGeneration = 2L,
                mainFrameSequence = 1L,
                mainFrameReceivedAtElapsedRealtimeMs = 1_000L,
                latitude = 30.4970,
                longitude = 104.4330,
            )
            dispatcher.scheduler.advanceUntilIdle()
            gpsFlow.value = emptyGpsSample().copy(
                timestamp = 1_040L,
                connectionGeneration = 2L,
                mainFrameSequence = 2L,
                mainFrameReceivedAtElapsedRealtimeMs = 1_040L,
                latitude = 30.4971,
                longitude = 104.4331,
            )
            dispatcher.scheduler.advanceUntilIdle()
            val acceptedSampleCount = viewModel.lapSession.value?.samples?.size ?: 0
            assertTrue(acceptedSampleCount >= 1)

            val rejectedFrames = listOf(
                emptyGpsSample().copy(
                    timestamp = 1_060L,
                    connectionGeneration = 2L,
                    mainFrameSequence = 2L,
                    mainFrameReceivedAtElapsedRealtimeMs = 1_040L,
                    latitude = 30.4990,
                    longitude = 104.4350,
                ),
                emptyGpsSample().copy(
                    timestamp = 1_080L,
                    connectionGeneration = 2L,
                    mainFrameSequence = 3L,
                    hasMainFrame = false,
                ),
                emptyGpsSample().copy(
                    timestamp = 1_120L,
                    connectionGeneration = 2L,
                    mainFrameSequence = 4L,
                    fixQuality = 0,
                    satelliteCount = 0,
                ),
                emptyGpsSample().copy(
                    timestamp = 1_160L,
                    connectionGeneration = 2L,
                    mainFrameSequence = 5L,
                    consecutiveReliableMainFrames = 2,
                    isRecoveryStable = false,
                ),
                emptyGpsSample().copy(
                    timestamp = 1_200L,
                    connectionGeneration = 2L,
                    mainFrameSequence = 6L,
                    isStale = true,
                ),
                emptyGpsSample().copy(
                    timestamp = 1_240L,
                    connectionGeneration = 1L,
                    mainFrameSequence = 99L,
                ),
            )
            rejectedFrames.forEach { frame ->
                gpsFlow.value = frame
                dispatcher.scheduler.advanceUntilIdle()
                assertEquals(acceptedSampleCount, viewModel.lapSession.value?.samples?.size ?: 0)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ending before first GPS frame keeps and closes short session`() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockTelemetryRepositoryWithEmptyFlows()
            doReturn("short-session").`when`(repository).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
            val viewModel = createViewModel(telemetryRepository = repository)
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)

            assertEquals("short-session", viewModel.finishActiveLapSession()?.sessionId)
            assertEquals(null, viewModel.prepareActiveLapSessionForRecording())
            verify(repository, times(1)).startSession(
                TelemetrySessionType.LAP_SESSION,
                DEFAULT_TRACK_ID,
                "成都天府国际赛道",
            )
            verify(repository, times(1)).endSession("short-session")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `free capture eagerly creates untracked session and closes as debug result`() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val repository = mockTelemetryRepositoryWithEmptyFlows()
            doReturn("debug-session").`when`(repository).startSession(
                TelemetrySessionType.LAP_SESSION,
                null,
                "DEBUG 自由采集",
            )
            val viewModel = createViewModel(telemetryRepository = repository)

            viewModel.startDebugCaptureMode()

            assertEquals(TestMode.DebugCapture, viewModel.currentMode.value)
            assertEquals("debug-session", viewModel.getActiveLapSessionId())
            assertEquals("debug-session", viewModel.debugCaptureStats.value.sessionId)

            val result = viewModel.finishActiveLapSession()
            assertEquals("debug-session", result?.sessionId)
            assertEquals(0, result?.lapCount)
            assertTrue(result?.isDebugCapture == true)
            assertFalse(viewModel.debugCaptureStats.value.isActive)
            verify(repository, times(1)).startSession(
                TelemetrySessionType.LAP_SESSION,
                null,
                "DEBUG 自由采集",
            )
            verify(repository, times(1)).endSession("debug-session")
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
            assertTrue(summary.contains("startFinish=30.495674664699337,104.4333934545891->30.495698171686513,104.43287290301339"))
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

            // 首次过线开圈（lap1）：复用 emitCrossing helper —— 此时 GpsDataFilter 的 median
            // 窗口未被污染，两帧 outputLat 一北一南正确穿过 gate（与
            // lapDebugMode_replayAlignedTrackCatalogProducesAcceptedStartFinishCrossing 同链路）。
            emitCrossing(track.startFinishGate, 1773477876490L, 1773477876690L)
            dispatcher.scheduler.advanceUntilIdle()

            // 第二次过线闭圈（lap1 → completedLaps）：本 round 修复点（round
            // fix-lap-debug-mode-sector-chain-test-after-min-count-1）。
            //
            // W4 把 GpsDataFilter 接进 bridge 后，旧 fixture 用 emitCrossing 注入的两帧大跨度
            // 合成跳变（~14m / 200ms ≈ 261 km/h，远超 reported speed 36 km/h）触发
            // GpsDataFilter.checkPositionVelocityConsistency 的 isPositionAnomaly（ratio > 3.0），
            // 第二帧被 A14 拦在 lat/lon median 窗口外；又因第一次过线遗留的 median 窗口未清空，
            // detector 拿到的两帧 outputLat/outputLon 退化为同一个 median 值（线段退化为点 →
            // NoIntersection），第二次过线判定不 accepted → 永远闭不了圈（baseline fail
            // expected:1 但 was:0）。详见 design.md Decision 1 / proposal.md 关键事实 3。
            //
            // 修法（design Decision 1 手法 A+B 组合，纯测试侧、不改任何生产代码）：
            //   1. 物理合理位移（手法 B）：dt=400ms + 每帧 4m（lat ≈ 0.000036°），
            //      4m / 400ms = 10 m/s = 36 km/h 与 reported speed 一致 → 不触 isPositionAnomaly，
            //      两帧都能进 median 窗口；
            //   2. 稳态预热（手法 A）：先在 gate 北侧固定锚点喂 9 帧，把 GpsDataFilter 的 9 点
            //      lat/lon median 窗口完全刷新收敛到北侧，洗掉第一次过线遗留的脏窗口；
            //   3. 单次穿越保障：median 稳态后输出步长（≈4m）> gate 线带宽（lat 跨度 ~2.7m），
            //      使 median 输出一帧跨过整个 gate 带 → 相邻帧对仅与 gate 线相交一次，
            //      恰好闭 1 圈（completedLaps == 1 / currentLapIndex == 2，非多次重复闭圈）。
            // passDirection ≈ (-lat) → 车向南（lat 递减）穿过东西向 gate 线，directionScore > 0。
            val gate = track.startFinishGate
            val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
            val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
            val northAnchorLat = centerLat + 0.00009   // gate 北侧 ~10m 预热锚点
            val southStepLat = 0.0000360                // ~4m / 帧（与 36 km/h、dt=400ms 物理一致）
            var secondCrossingTs = 1773478000000L
            val secondCrossingTrajectory = buildList {
                repeat(9) { add(northAnchorLat) }                              // 稳态预热：median 收敛北侧
                repeat(8) { i -> add(northAnchorLat - (i + 1) * southStepLat) } // 单调南移穿过 gate + 南侧稳定
            }
            secondCrossingTrajectory.forEach { lat ->
                emitGps(secondCrossingTs, lat, centerLon)
                dispatcher.scheduler.advanceUntilIdle()
                secondCrossingTs += 400L
            }

            val session = requireNotNull(viewModel.lapSession.value)
            assertEquals(1, session.completedLaps.size)
            assertEquals(2, session.currentLapIndex)
            // Decision 2：断言收紧到指名 IncompleteSectors（而非泛 isNotEmpty()），锁死
            // "宽容闭合时该圈被打 sector 不完整标记" 这条提示语义的真实落点（in-memory 信号源）。
            // contains 对该圈可能同时含的其他 flag（如 ProtocolDesyncGap）鲁棒。
            assertTrue(
                session.completedLaps.first().qualityFlags.contains(LapQualityFlag.IncompleteSectors)
            )
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

    private fun createViewModel(
        trackCatalog: TrackCatalog = PresetTrackCatalog(),
        telemetryRepository: com.blazepush.core.data.repository.TelemetryRepository =
            mockTelemetryRepositoryWithEmptyFlows(),
    ): TestSessionViewModel {
        gpsFlow = MutableStateFlow(emptyGpsSample())
        connectionState = MutableStateFlow(ConnectionState.CONNECTED)
        nextMainFrameSequence = 2L

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
            telemetryRepository = telemetryRepository,
            recentTracksStore = com.blazepush.feature.test.datastore.FakeRecentTracksStore(),
            lapUploadOrchestrator = FakeLapUploadTrigger(),
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

    private fun emitGps(
        timestamp: Long,
        latitude: Double,
        longitude: Double,
        mainFrameSequence: Long? = null,
        mainFrameReceivedAtElapsedRealtimeMs: Long = 1L,
        mainFrameSilenceTimeoutMs: Long = 1_000L,
        consecutiveReliableMainFrames: Int = 3,
    ) {
        gpsFlow.value = emptyGpsSample().copy(
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude,
            speed = 36.0,
            mainFrameSequence = mainFrameSequence ?: nextMainFrameSequence++,
            mainFrameReceivedAtElapsedRealtimeMs = mainFrameReceivedAtElapsedRealtimeMs,
            mainFrameSilenceTimeoutMs = mainFrameSilenceTimeoutMs,
            consecutiveReliableMainFrames = consecutiveReliableMainFrames,
            requiredReliableMainFrames = 3,
            reliableMainStableDurationMs = if (consecutiveReliableMainFrames >= 3) 1_000L else 0L,
            isRecoveryStable = consecutiveReliableMainFrames >= 3,
            timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
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
        satelliteCount = 8,
        hdop = 1.2,
        vdop = 0.0,
        frequency = 10.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null,
        fixQuality = 1,
        // 默认已同步 —— 历史用例都期望 emit 的帧能走到 engine
        // 新加的"未同步"用例会显式 .copy(isTimeSynced = false) 覆盖
        isTimeSynced = true,
        hasMainFrame = true,
        mainFrameSequence = 1L,
        mainFrameReceivedAtElapsedRealtimeMs = 1L,
        consecutiveReliableMainFrames = 3,
        requiredReliableMainFrames = 3,
        reliableMainStableDurationMs = 1_000L,
        isRecoveryStable = true,
        timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
    )

    // ==================== v2 (fix-laptime-clock-source-integrity) ====================

    @Test
    fun bridgeGpsToLapTiming_afterDynamicMainGap_waitsForStableRecoveryAndRestartsTrack() = runTest {
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = createViewModel()
            viewModel.selectLapDebugMode(DEFAULT_TRACK_ID)
            dispatcher.scheduler.advanceUntilIdle()

            emitGps(1_000L, 30.4970, 104.4330, 2L, 1_000L, 400L)
            dispatcher.scheduler.advanceUntilIdle()
            emitGps(1_040L, 30.4971, 104.4331, 3L, 1_040L, 400L)
            dispatcher.scheduler.advanceUntilIdle()
            val samplesBeforeGap = viewModel.lapSession.value?.samples?.size ?: 0
            assertTrue("第二个连续帧应已进入计时引擎", samplesBeforeGap >= 1)

            // 25Hz 动态 deadline=400ms。恢复前两帧仍处于稳定性确认阶段。
            emitGps(1_440L, 30.4990, 104.4350, 4L, 1_440L, 400L, 1)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "恢复首帧不得跨动态静默 gap 进入计时引擎",
                samplesBeforeGap,
                viewModel.lapSession.value?.samples?.size ?: 0,
            )

            emitGps(1_480L, 30.4991, 104.4351, 5L, 1_480L, 400L, 2)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "第二个恢复帧仍不得进入计时引擎",
                samplesBeforeGap,
                viewModel.lapSession.value?.samples?.size ?: 0,
            )

            // 第三个可靠帧恢复置信度，但只作为新轨迹基准。
            emitGps(1_520L, 30.4992, 104.4352, 6L, 1_520L, 400L, 3)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "达到恢复门槛的首帧只建立新轨迹基准",
                samplesBeforeGap,
                viewModel.lapSession.value?.samples?.size ?: 0,
            )

            emitGps(1_560L, 30.4993, 104.4353, 7L, 1_560L, 400L, 4)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "置信度恢复后的下一连续帧应重新进入计时引擎",
                (viewModel.lapSession.value?.samples?.size ?: 0) > samplesBeforeGap,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

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

            // 先把状态推进到 Running：25 帧静止 + 12 帧加速（全部 isTimeSynced=true，speed 保持 < 100
            // 避免误触发 shouldEnd）。launch-arming-feedback(2026-06-04):静止确认 3→25 帧(1 秒真停稳,
            // 与成绩窗口 MOTION_THRESHOLD_KMH 同口径),加速帧 8→12 适配 median 窗口全 0.5 后的爬升节奏。
            repeat(25) { i ->
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
            //   (2) 帧数必须 ≥ 8：round smooth-perftest-acceleration-curve §5 切到 outputSpeed
            //       路径后，speedWindow 含前置 3 帧 standstill 0.5 km/h（fixture line 597-616），
            //       9 点 median 把前 2 个加速帧的 outputSpeed 拉到 < 1.0 km/h，让 isMoving 短路
            //       (`filteredData.speed > 1.0`) 在 i=0/i=1 判 false → trigger 计数延后启动 2 帧。
            //       trigger = isAccelerating || isMoving（OR 短路；阈值 ≥ 5 连续）：
            //         i=0: median([0.5×3, 10])=0.5,            isMoving=false → count=0
            //         i=1: median([0.5×3, 10, 12])=0.5,        isMoving=false → count=0
            //         i=2: median([0.5×3, 10, 12, 14])=5.25,   isMoving=true  → count=1
            //         i=3..i=6: median 单调升至 14, isMoving=true              → count=2..5
            //         i=7: count=6 ≥ 5 → fire trigger
            //       spec.md `Requirement 3` Scenario "实时 trigger 行为有限退化" 锁定偏差 ≤ 80ms
            //       (2 帧)，warmup + standstill 耦合的物理来源。8 帧已经实测验证。
            // speed 从 2.0 渐升(dv 1.5/2.0 < maxDelta 3.6,全程无 anomaly 拦截):
            // 9 点 median 窗口在 i=4 起越过 isMoving 阈值(1.0),i=8 连续计数满 5 → fire,12 帧含余量
            repeat(12) { i ->
                gpsFlow.value = emptyGpsSample().copy(
                    timestamp = (i * 40).toLong() + 1_001_000L,
                    latitude = 30.49,
                    longitude = 104.43,
                    speed = 2.0 + i * 2.0,
                    satelliteCount = 10,
                    hdop = 1.0,
                    isTimeSynced = true
                )
                dispatcher.scheduler.advanceUntilIdle()
            }

            val runningState = viewModel.testState.value
            assertTrue(
                "前置：静止确认(25 帧)+加速帧应让状态转入 Running",
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
