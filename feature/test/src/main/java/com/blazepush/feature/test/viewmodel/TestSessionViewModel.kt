// @IgnoreFormatCheck
// 理由：本文件仅由 change fix-file-logger-and-engine-coord-hygiene（战役 F A18+A39）
//       修改 2 处 R4 高频 bridge 日志 call site（line 335 附近 GPS 推进 + line 373
//       附近 lapTiming 结果）—— `FileLogger.d` → `FileLogger.v` + 坐标
//       `"%.3f".format(...)` 降级 + `if (isVerboseEnabled)` 守卫。kt-check 报的
//       class-comment / property-name(_前缀 MutableStateFlow backing 惯例) /
//       public-fun-with-comment-block / my-max-line-length / when-else-required /
//       import-order / no-trailing-newline 均为 pre-existing legacy 违规，与本
//       战役 R4 日志精修语义正交。评审方 2026-04-24 战役 G B 方案纪律批准 legacy
//       文件 ignore，避免 scope 漂移到周边 refactor。
package com.blazepush.feature.test.viewmodel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazepush.feature.test.livetiming.LapUploadTrigger
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.datastore.RecentTracksStoreApi
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import com.blazepush.core.domain.model.TestSession
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TestResultSummary
import com.blazepush.core.domain.model.TestState
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.FilteredGpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.LapLiveState
import com.blazepush.feature.test.usecase.LapLiveStateDeriver
import com.blazepush.feature.test.usecase.LapTimingEngine
import com.blazepush.feature.test.usecase.ReferenceLapIndex
import com.blazepush.feature.test.usecase.buildReferenceLapIndex
import com.blazepush.feature.test.usecase.projectDelta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class TestMode {
    Acceleration,
    Braking,
    LapDebug
}

/**
 * HOLD TO END / EndConfirmationDialog 完成保存后返回给 UI 的派生 summary。
 *
 * @author CC
 * @description lap session save result snapshot for snackbar / nav
 * @date 2026-05-01
 */
data class LapSessionSaveResult(
    val sessionId: String,
    val lapCount: Int,
    val bestLapMs: Long?,
    val totalDurationMs: Long,
)

/**
 * 实时秒差跨帧状态聚合体。round redesign-realtime-delta-projection-search 重设计（Alt B stateless）：
 * - reference：当前 best 圈预计算索引；null = 无 best（首圈进行中）
 * - prevDeltaMs：上一帧成功投影的 delta；失效时 UI 维持显示这个值
 * - staleFrameCount：连续失效帧计数；累计 ≥ STALE_FRAME_THRESHOLD 时进 stale
 * - outDeltaMs / outIsStale：本帧的最终 LapLiveState 输出值（在 update 同事务内派生，避免 race）
 *
 * prevMatchedIdx 已删除（Alt B 全量扫描不需要跨帧 cache，根除连续性假设是本 round 核心目标）。
 * 所有字段每帧 GPS data 来到时通过 [_realtimeDeltaState.value] atomic 替换更新；
 * Deriver 通过订阅本 StateFlow 直接读 outDeltaMs / outIsStale 两个标量。
 *
 * @author CC
 * @description cross-frame state for realtime lap delta projection
 * @date 2026-05-02
 */
internal data class RealtimeDeltaState(
    val reference: ReferenceLapIndex?,
    val prevDeltaMs: Long? = null,
    val staleFrameCount: Int = 0,
    val outDeltaMs: Long? = null,
    val outIsStale: Boolean = false,
)

/**
 * 测试会话ViewModel - 管理测试状态机
 */
class TestSessionViewModel(
    private val gpsDataViewModel: GpsDataViewModel,
    private val bleDeviceManager: BleDeviceManager,
    private val testResultRepository: TestResultRepository,
    private val calculateResultUseCase: CalculateResultUseCase,
    private val smartTestLauncher: SmartTestLauncher = SmartTestLauncher(),
    private val gpsDataFilter: GpsDataFilter = GpsDataFilter(),
    private val trackCatalog: TrackCatalog,
    private val lapTimingEngine: LapTimingEngine,
    private val telemetryRepository: TelemetryRepository,
    private val recentTracksStore: RecentTracksStoreApi,
    private val lapUploadOrchestrator: LapUploadTrigger,
) : ViewModel() {

    companion object {
        private const val TAG = "TestSessionVM"
        private const val COUNTDOWN_DURATION = 5
        private const val PRE_TRIGGER_DURATION_MS = 2000L
        private const val TRIGGER_ACCELERATION_THRESHOLD = 1.0
        private const val TRIGGER_CONFIRMATION_COUNT = 5
        private const val STANDSTILL_SPEED_THRESHOLD = 3.0
        private const val STANDSTILL_CONFIRMATION_COUNT = 3
        private const val LAP_LIVE_TICK_PERIOD_MS = 50L
        private const val LAP_LIVE_SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * round add-realtime-lap-delta：连续失效帧门，到达此值后 UI 进 stale（字色降级 muted）。
         * 5Hz GPS 下 5 帧 ≈ 1 秒，避免单帧 GPS 异常 / 短暂变线引起 UI 闪烁；
         * 连续 1 秒持续失效才视为"真问题"。
         */
        private const val STALE_FRAME_THRESHOLD = 5

        /**
         * 开圈（prev==0 → updated>0）用新 index；闭圈和 sector 用旧 index。
         * 避免 start/finish 闭圈后 currentLapIndex 已推进导致事件错位到下一圈。
         */
        internal fun lapIndexForCrossing(previousLapIndex: Int, updatedLapIndex: Int): Int =
            if (previousLapIndex == 0 && updatedLapIndex > 0) updatedLapIndex else previousLapIndex
    }

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState.asStateFlow()

    private val _currentMode = MutableStateFlow(TestMode.Acceleration)
    val currentMode: StateFlow<TestMode> = _currentMode.asStateFlow()

    // A37 change fix-gps-stats-and-lazy-catalog-hot-start：
    // 构造期给空列表避免同步读 catalog 阻塞 Main；viewModelScope.launch 内异步加载（见 init block）。
    // launch 不指定 Dispatchers.IO —— IO 边界唯一防线在 ReplayAlignedTrackCatalog 实现侧。
    private val _availableTracks = MutableStateFlow<List<Track>>(emptyList())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _currentSelectedTrack = MutableStateFlow<Track?>(null)
    val currentSelectedTrack: StateFlow<Track?> = _currentSelectedTrack.asStateFlow()

    // round `replace-nearby-tracks-with-recent-strip` §2.1：用户最近选过的赛道列表，
    // 由 init block collect RecentTracksStoreApi.recentIds 推送。selectTrack 触发持久化写。
    private val _recentTrackIds = MutableStateFlow<List<String>>(emptyList())
    val recentTrackIds: StateFlow<List<String>> = _recentTrackIds.asStateFlow()

    fun selectTrack(track: Track) {
        _currentSelectedTrack.value = track
        viewModelScope.launch { recentTracksStore.add(track.id) }
    }

    // round `wire-real-data-to-records-and-laps-tabs` §2.1：暴露 8 个统计 StateFlow，
    // 4 个性能测试相关（直接 stateIn）+ 4 个圈速 session 相关（flatMapLatest 跟随
    // currentSelectedTrack 切换；订阅 5s 缓冲避免 tab 快速切震荡）。
    @OptIn(ExperimentalCoroutinesApi::class)
    val bestAcceleration0To100: StateFlow<TestResultSummary?> =
        testResultRepository.getBestResult(TestTemplate.Acceleration0To100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val bestBraking100To0: StateFlow<TestResultSummary?> =
        testResultRepository.getBestResult(TestTemplate.Braking100To0)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val totalRunCount: StateFlow<Int> =
        testResultRepository.getTotalRunCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentRuns: StateFlow<List<TestResultSummary>> =
        testResultRepository.getRecentResultsFlow(5)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val bestLapForCurrentTrack: StateFlow<TelemetrySession?> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getBestLapForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionCountForCurrentTrack: StateFlow<Int> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getSessionCountForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val totalLapCountForCurrentTrack: StateFlow<Int> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getTotalLapCountForTrack(track.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSessionsForCurrentTrack: StateFlow<List<TelemetrySession>> = _currentSelectedTrack
        .filterNotNull()
        .flatMapLatest { track -> telemetryRepository.getRecentSessionsForTrack(track.id, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _lapRunConfig = MutableStateFlow<LapRunConfig?>(null)
    val lapRunConfig: StateFlow<LapRunConfig?> = _lapRunConfig.asStateFlow()

    private val _lapSession = MutableStateFlow<LapSession?>(null)
    val lapSession: StateFlow<LapSession?> = _lapSession.asStateFlow()

    /**
     * round add-realtime-lap-delta：实时秒差跨帧状态。本 ViewModel 是 [projectDelta] 唯一调用方（Deriver 不调）。
     * - reference 在 _lapSession.completedLaps 出现新 best 时同步重建（首圈完成立即建 / 后续 PB 刷新触发重建）
     * - 每帧 gpsDataViewModel.gpsData 来到时调 [projectDelta]，atomic update 跨帧字段，同事务派生 outDeltaMs/outIsStale
     * - lapLiveState 订阅本 StateFlow，把 outDeltaMs/outIsStale 喂给 [LapLiveStateDeriver.derive]
     */
    private val _realtimeDeltaState = MutableStateFlow(RealtimeDeltaState(reference = null))

    val lapLiveState: StateFlow<LapLiveState> = combine(
        _lapSession,
        gpsDataViewModel.gpsData,
        gpsDataViewModel.connectionState,
        gpsDataViewModel.dataQuality,
        // 把 _realtimeDeltaState 跟 ticker combine 成 inner flow：
        // ticker 推进时（即使 deltaState 未变）也发射，保证 currentLapTimerMs 能跨 GPS 帧平滑外推。
        combine(_realtimeDeltaState, tickerFlow(LAP_LIVE_TICK_PERIOD_MS)) { d, _ -> d },
    ) { session: LapSession?, gps: GpsData, conn: ConnectionState, quality: DataQuality, deltaState: RealtimeDeltaState ->
        // currentDisplayTimeMs：GPS 时间轴（与 crossing.timestampMillis 同源），
        // 通过 elapsedRealtime 在 GPS 帧间隔内推进，让 ticker 50ms 驱动 CURRENT tile 平滑滚动
        // （不依赖 GPS 帧到达频率：5Hz replay 下 timer 仍 50ms 跳一次）。
        val anchorElapsed = lastReceivedAtElapsed
        val currentDisplayTimeMs = if (anchorElapsed > 0L) {
            gps.timestamp + (SystemClock.elapsedRealtime() - anchorElapsed)
        } else {
            gps.timestamp
        }
        LapLiveStateDeriver.derive(
            session = session,
            currentDisplayTimeMs = currentDisplayTimeMs,
            gpsData = gps,
            connectionState = conn,
            dataQuality = quality,
            deltaToBestMs = deltaState.outDeltaMs,
            deltaIsStale = deltaState.outIsStale,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(LAP_LIVE_SUBSCRIPTION_TIMEOUT_MS),
        initialValue = LapLiveState(
            currentLapTimerMs = null,
            lastLapTimeMs = null,
            bestLapTimeMs = null,
            deltaToBestMs = null,
            deltaIsStale = false,
            currentLapNumber = 1,
            abnormalState = null,
        ),
    )

    private val _latestLapRecords = MutableStateFlow<List<LapRecord>>(emptyList())
    val latestLapRecords: StateFlow<List<LapRecord>> = _latestLapRecords.asStateFlow()

    private var lastLapGpsSample: GpsSample? = null
    private var isLapRecording = false

    private var activeTestSessionId: String? = null
    private var activeTestStartTs: Long? = null

    private var activeLapSessionId: String? = null
    private var activeLapStartSystemTs: Long? = null

    /**
     * 返回当前 active lap session id（录制引擎在 startRecording 时调用，读取当前关联的 session）。
     * null = 无进行中的 lap session（录制将成为孤立视频）。
     *
     * camera-recording-and-gps-sync round（MUST 6）：public accessor，不破坏内部 private 写语义。
     */
    fun getActiveLapSessionId(): String? = activeLapSessionId
    private var lastWrittenCrossingCount: Int = 0

    private val _launchStatus = MutableStateFlow(
        SmartTestLauncher.LaunchStatus(
            conditions = emptyList(),
            canLaunch = false,
            unmetConditionIds = emptyList()
        )
    )
    val launchStatus: StateFlow<SmartTestLauncher.LaunchStatus> = _launchStatus.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(5)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private var countdownJob: Job? = null
    // 数据帧接收时刻，使用 elapsedRealtime 单调时钟，**不依赖** `gpsData.timestamp`
    // 供 updateLaunchStatus 的 lastDataAge 计算，与 GpsData 的协议时间字段解耦
    // （对应 fix-laptime-clock-source-integrity spec Requirement 3.5 (c)）
    private var lastReceivedAtElapsed: Long = 0L
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val preTriggerBuffer = mutableListOf<FilteredGpsData>()

    private var isStartReady = false
    private var standstillCount = 0
    private var consecutiveTriggerCount = 0

    init {
        // A37：异步加载 availableTracks，不指定 Dispatchers.IO（catalog 实现自负 IO 边界）
        viewModelScope.launch {
            val loaded = trackCatalog.getAllTracks()
            _availableTracks.value = loaded
            if (_currentSelectedTrack.value == null) {
                // 直接赋值，**不**通过 selectTrack —— 避免初始化 fallback 触发 RecentTracksStore.add
                // 污染 RECENT 列表（spec §2.1 + Requirement 「初始化不污染 RECENT」）
                _currentSelectedTrack.value = loaded.firstOrNull()
            }
        }

        // round `replace-nearby-tracks-with-recent-strip` §2.1：collect RecentTracksStore 推送
        viewModelScope.launch {
            recentTracksStore.recentIds.collect { _recentTrackIds.value = it }
        }

        viewModelScope.launch {
            bleDeviceManager.connectionState.collect { state ->
                _connectionState.value = state
            }
        }

        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                // 无论 isTimeSynced 真假，首行更新 elapsedRealtime 以驱动 lastDataAge 计算
                // （Requirement 3.5 (c)：lastDataAge 与协议 timestamp 解耦）
                lastReceivedAtElapsed = SystemClock.elapsedRealtime()

                val filteredData = gpsDataFilter.process(gpsData)
                updatePreTriggerBuffer(filteredData)
                updateLaunchStatus(gpsData)
                processFilteredData(filteredData)

                // round wire-laptime-to-gps-filter（2026-05-05 hotfix B 回滚）：
                // 替换 4 字段 latitude/longitude/speed/bearing 与 design Decision 1+2 锁死契约一致。
                // detector directionScore = movement · passUnit 由 prev/cur 的 lat/lon 差完全决定，
                // 仅 lat/lon median 才能消除单帧 GPS jitter 导致 WrongDirection 误判。
                // MUST NOT 替换 timestamp（detector 插值精度依赖 raw 时间戳）。
                val cleaned = gpsData.copy(
                    latitude = filteredData.latitude,
                    longitude = filteredData.longitude,
                    speed = filteredData.speed,
                    bearing = filteredData.bearing,
                )
                bridgeGpsToLapTiming(cleaned)

                // round add-realtime-lap-delta：每帧 GPS data 调一次 projectDelta，atomic update RealtimeDeltaState
                updateRealtimeDelta(gpsData)
            }
        }

        // round add-realtime-lap-delta：监听 _lapSession.completedLaps，
        // 首圈完成立即建 reference / 后续 PB 刷新重建 reference
        viewModelScope.launch {
            _lapSession.collect { session ->
                maybeRebuildReference(session)
            }
        }
    }

    /**
     * round add-realtime-lap-delta：检测 session.completedLaps 是否需要重建 reference。
     *
     * 触发条件：
     * - 首圈完成立即建：completedLaps 从空变 ≥ 1 + 当前 reference 为 null
     * - PB 刷新重建：新 best 圈 durationMillis < 当前 reference.lapDurationMs
     *
     * 重建时 atomic update：reference = newRef, prevMatchedIdx = -1, staleFrameCount = 0；
     * **prevDeltaMs 保留**（避免 stale 体验空白），outDeltaMs / outIsStale 待下一帧 update 重新派生。
     *
     * @author CC
     * @description rebuild ReferenceLapIndex on first lap completion or PB refresh
     * @date 2026-05-02
     */
    private fun maybeRebuildReference(session: LapSession?) {
        val completedLaps = session?.completedLaps ?: return
        if (completedLaps.isEmpty()) return
        val newBest = completedLaps.minByOrNull { it.durationMillis } ?: return

        val state = _realtimeDeltaState.value
        val current = state.reference
        val shouldRebuild = current == null || newBest.durationMillis < current.lapDurationMs
        if (!shouldRebuild) return

        val newRef = buildReferenceLapIndex(newBest) ?: return
        FileLogger.d(TAG, "RTDelta ref rebuilt: frames=${newRef.xs.size}")
        _realtimeDeltaState.value = state.copy(
            reference = newRef,
            staleFrameCount = 0,
        )
    }

    /**
     * round redesign-realtime-delta-projection-search（Alt B stateless）：每帧 GPS data 调用一次 projectDelta，
     * atomic update RealtimeDeltaState。
     *
     * 路径：
     * - reference 为 null（无 best） / lastAcceptedCrossing 为 null（首圈进行中）→ outDeltaMs = null
     * - 调 projectDelta（全量 O(n)，无 prevMatchedIdx）：
     *   - 成功 → outDeltaMs = delta, outIsStale = false, prevDeltaMs/staleFrameCount 更新
     *   - 失败 → staleFrameCount++；isStale 时 outDeltaMs = null（不再维持 prevDeltaMs 误导 UI）
     *
     * 时钟域：currentLapElapsedMs 用 `gps.timestamp - lastAcceptedCrossing.timestampMillis`，
     * 两个 ts 都是 GPS sample 域同源相减（避免 wall clock / BLE 链路延迟污染）。
     *
     * @author CC
     * @description per-frame projectDelta call updating cross-frame state for realtime lap delta
     * @date 2026-05-02
     */
    private fun updateRealtimeDelta(gps: GpsData) {
        val session = _lapSession.value
        val lastCrossing = session?.crossingEvents
            ?.lastOrNull { it.accepted && it.gateType == com.blazepush.feature.test.model.track.TimingGateType.StartFinish }

        _realtimeDeltaState.value = run {
            val state = _realtimeDeltaState.value
            val ref = state.reference
            if (ref == null || lastCrossing == null) {
                state.copy(outDeltaMs = null, outIsStale = false)
            } else {
                val (curX, curY) = ref.toLocalMeters(gps.latitude, gps.longitude)
                val currentLapElapsedMs = gps.timestamp - lastCrossing.timestampMillis
                val projection = projectDelta(
                    reference = ref,
                    currentLapElapsedMs = currentLapElapsedMs,
                    currentX = curX,
                    currentY = curY,
                )
                if (projection != null) {
                    // 2026-06-04 降频采样:25Hz 逐帧投影日志每秒最多 1 条
                    FileLogger.vSampled("RTDelta", "rtdelta-proj") { "proj idx=${projection.matchedIdx} dist=${projection.projDistanceM}m delta=${projection.deltaMs}ms elapsed=$currentLapElapsedMs" }
                    state.copy(
                        prevDeltaMs = projection.deltaMs,
                        staleFrameCount = 0,
                        outDeltaMs = projection.deltaMs,
                        outIsStale = false,
                    )
                } else {
                    val newStale = state.staleFrameCount + 1
                    val isStale = newStale >= STALE_FRAME_THRESHOLD
                    FileLogger.d("RTDelta", "stale frame#$newStale isStale=$isStale (proj failed: off-track >failoverDist)")
                    state.copy(
                        staleFrameCount = newStale,
                        outDeltaMs = if (isStale) null else state.prevDeltaMs,
                        outIsStale = isStale,
                    )
                }
            }
        }
    }

    fun enterSmartLaunch(template: TestTemplate, carModel: String) {
        _currentMode.value = when (template) {
            is TestTemplate.Acceleration0To100 -> TestMode.Acceleration
            is TestTemplate.Braking100To0 -> TestMode.Braking
        }
        isStartReady = false
        standstillCount = 0
        consecutiveTriggerCount = 0
        isFinishing = false  // 重置完成标记，允许新测试
        _testState.value = TestState.Preparing(template, carModel)
        startCountdown()
    }

    fun selectLapDebugMode(trackId: String) {
        selectLapDebugMode(LapRunConfig(trackId = trackId))
    }

    fun selectLapDebugMode(config: LapRunConfig) {
        val track = trackCatalog.getTrack(config.trackId) ?: return

        _currentMode.value = TestMode.LapDebug
        _lapRunConfig.value = config
        _lapSession.value = createLapSession(track.id)
        _latestLapRecords.value = emptyList()
        lastLapGpsSample = null
        isLapRecording = true
        lastWrittenCrossingCount = 0
        // session 懒启动：锚点在此记录，writer 在第一帧到达段3时 inline 创建
        activeLapStartSystemTs = System.currentTimeMillis()
        activeLapSessionId = null

        FileLogger.d(TAG, "lapDebugTrackSummary: ${buildTrackDebugSummary(track)}")
    }

    fun stopLapDebugSession() {
        isLapRecording = false
        _lapSession.value = _lapSession.value?.copy(status = LapSessionStatus.Finished)
        lastLapGpsSample = null
        endActiveLapSession()
    }

    fun exitLapDebugMode() {
        isLapRecording = false
        lastLapGpsSample = null
        _latestLapRecords.value = emptyList()
        _lapRunConfig.value = null
        _lapSession.value = null
        _currentMode.value = TestMode.Acceleration
        endActiveLapSession()
    }

    private fun endActiveLapSession() {
        val sessionId = activeLapSessionId ?: return
        activeLapSessionId = null
        activeLapStartSystemTs = null
        lastWrittenCrossingCount = 0
        viewModelScope.launch { telemetryRepository.endSession(sessionId) }
    }

    /**
     * Records → PERFORMANCE 子页 RecentRuns 长按删除入口（add-history-deletion round）。
     * 删除测试记录 + cascade 清 binary 文件（`/telemetry/` 路径白名单防御在 repository 层）。
     * 删除完 Room Flow 自动 emit，UI 列表无需手动刷新。
     *
     * @author CC
     * @description delete test record by id (cascade binary cleanup)
     * @date 2026-05-02
     */
    fun deleteTestRecord(recordId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            testResultRepository.deleteResultById(recordId)
        }
    }

    /**
     * Records → LAPS 子页 SESSION HISTORY 长按删除入口（add-history-deletion round）。
     * 删除 lap session entity + cascade 清 crossing_events 关联行 + binary 文件
     * （`/telemetry/` 路径白名单防御在 repository 层）。
     *
     * @author CC
     * @description delete lap session by id (cascade crossings + binary cleanup)
     * @date 2026-05-02
     */
    fun deleteLapSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            telemetryRepository.deleteSession(sessionId)
        }
    }

    /**
     * HOLD TO END / EndConfirmationDialog 的 session 终止入口（D12）：
     * 先捕获派生 summary（清状态前），await endSession，再清 ViewModel session 状态。
     * 返回 result 给 UI 驱动 Snackbar 与 View Record 跳转；session 未激活时返回 null。
     *
     * @author CC
     * @description finish active lap session and return summary
     * @date 2026-05-01
     */
    suspend fun finishActiveLapSession(): LapSessionSaveResult? {
        val sessionId = activeLapSessionId ?: return null
        val sessionSnapshot = _lapSession.value
        val startTs = activeLapStartSystemTs
        // D12 契约：lapCount / bestLapMs 仅基于 qualityFlags 全空的 valid 圈，
        // 排除 IncompleteSectors / ProtocolDesyncGap / SuspectedJitter 等带 quality flag 的作废圈
        val validLaps = sessionSnapshot?.completedLaps.orEmpty().filter { it.qualityFlags.isEmpty() }
        val lapCount = validLaps.size
        val bestLapMs = validLaps.minOfOrNull { it.durationMillis }
        val totalDurationMs = startTs?.let { System.currentTimeMillis() - it } ?: 0L

        telemetryRepository.endSession(sessionId)

        // unify-lap-count-pairing-semantics round（road-test-first 强制埋点）：读回持久化 lapCount
        // （站点 A，wallClock 配对身份数）与 Snackbar valid 计数（站点 D，qualityFlags 过滤）一并记录，
        // 真机路测核对两口径差异（有作废圈时 expected 不同）+ 旧 session null wallClock 行为（R3/R5）。
        val persistedLapCount = telemetryRepository.getSession(sessionId)?.lapCount
        FileLogger.d(
            "LapPairing",
            "finishLap sid=$sessionId snackbarValid=$lapCount persistedLapCount=$persistedLapCount key=wallClock",
        )

        activeLapSessionId = null
        activeLapStartSystemTs = null
        lastWrittenCrossingCount = 0
        isLapRecording = false
        _lapSession.value = sessionSnapshot?.copy(status = LapSessionStatus.Finished)

        return LapSessionSaveResult(
            sessionId = sessionId,
            lapCount = lapCount,
            bestLapMs = bestLapMs,
            totalDurationMs = totalDurationMs,
        )
    }

    private fun tickerFlow(periodMs: Long) = flow {
        while (true) {
            emit(Unit)
            delay(periodMs)
        }
    }

    fun skipCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
    }

    fun cancelTest() {
        countdownJob?.cancel()
        countdownJob = null
        consecutiveTriggerCount = 0
        isStartReady = false
        standstillCount = 0
        _testState.value = TestState.Idle
    }

    private fun startCountdown() {
        _countdownSeconds.value = COUNTDOWN_DURATION
        countdownJob = viewModelScope.launch {
            for (i in COUNTDOWN_DURATION downTo 1) {
                _countdownSeconds.value = i
                delay(1000)
            }
            _countdownSeconds.value = 0
            countdownJob = null
        }
    }

    private fun updatePreTriggerBuffer(filteredData: FilteredGpsData) {
        // Requirement 3.5 (a)：未同步帧不 append 到 preTriggerBuffer
        if (!filteredData.raw.isTimeSynced) return
        preTriggerBuffer.add(filteredData)
        val cutoffTime = filteredData.timestamp - PRE_TRIGGER_DURATION_MS
        while (preTriggerBuffer.isNotEmpty() && preTriggerBuffer.first().timestamp < cutoffTime) {
            preTriggerBuffer.removeAt(0)
        }
    }

    private suspend fun processFilteredData(filteredData: FilteredGpsData) {
        when (val state = _testState.value) {
            is TestState.Preparing -> {
                // Requirement 3.5 (a)：未同步帧不触发测试转 Running
                if (!filteredData.raw.isTimeSynced) return
                if (_countdownSeconds.value == 0) {
                    if (checkTriggerCondition(filteredData, state.template)) {
                        startTest(state.template, state.carModel, filteredData)
                    }
                }
            }
            is TestState.Running -> {
                // Requirement 3.5 (a) v2（A6 / opsx code review C.1）：
                // Running 期间失联 filter 返回 sentinel timestamp = Long.MIN_VALUE + zero acceleration
                // 的"零 delta 快照"。若吃进 session.dataPoints，elapsedTime = Long.MIN_VALUE - startTime
                // 会溢出污染 0-100 用时等结果计算。Preparing / Running 两分支必须对称守卫。
                if (!filteredData.raw.isTimeSynced) return
                state.session.addFilteredDataPoint(filteredData)
                val sessionStartTs = telemetryRepository.activeSessionStartTs
                if (sessionStartTs != null) {
                    telemetryRepository.writeSample(
                        TelemetrySample(
                            tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
                            lat = filteredData.latitude,
                            lon = filteredData.longitude,
                            speedKmh = filteredData.speed,
                            bearingDeg = filteredData.bearing,
                        )
                    )
                } else {
                    FileLogger.e(
                        TAG,
                        "processFilteredData: missing activeSessionStartTs, skip telemetry write but test pipeline continues"
                    )
                }
                if (state.session.template.shouldEnd(filteredData.raw)) {
                    finishTest(state.session)
                }
            }
            else -> { /* 其他状态不处理 */ }
        }
    }

    private fun checkTriggerCondition(filteredData: FilteredGpsData, template: TestTemplate): Boolean {
        return when (template) {
            is TestTemplate.Acceleration0To100 -> checkAccelerationTrigger(filteredData)
            is TestTemplate.Braking100To0 -> checkBrakingTrigger(filteredData)
        }
    }

    private fun checkAccelerationTrigger(filteredData: FilteredGpsData): Boolean {
        if (!isStartReady) {
            if (filteredData.speed < STANDSTILL_SPEED_THRESHOLD) {
                standstillCount++
                if (standstillCount >= STANDSTILL_CONFIRMATION_COUNT) {
                    isStartReady = true
                    standstillCount = 0
                }
            } else {
                standstillCount = 0
            }
            return false
        }

        val isAccelerating = filteredData.acceleration > 0
        val isMoving = filteredData.speed > 1.0

        if (isAccelerating || isMoving) {
            consecutiveTriggerCount++
            return consecutiveTriggerCount >= TRIGGER_CONFIRMATION_COUNT
        } else {
            consecutiveTriggerCount = 0
            return false
        }
    }

    private fun checkBrakingTrigger(filteredData: FilteredGpsData): Boolean {
        val isHighSpeed = filteredData.speed in 95.0..105.0
        val isBraking = filteredData.acceleration < -TRIGGER_ACCELERATION_THRESHOLD

        if (isHighSpeed && isBraking) {
            consecutiveTriggerCount++
            return consecutiveTriggerCount >= TRIGGER_CONFIRMATION_COUNT
        } else {
            consecutiveTriggerCount = 0
            return false
        }
    }

    private suspend fun startTest(template: TestTemplate, carModel: String, filteredData: FilteredGpsData) {
        consecutiveTriggerCount = 0
        val lockedPreTriggerBuffer = preTriggerBuffer.toList()

        val session = TestSession(
            id = UUID.randomUUID().toString(),
            template = template,
            carModel = carModel,
            startTime = filteredData.timestamp
        )

        session.markStarted(filteredData, lockedPreTriggerBuffer)

        _testState.value = TestState.Running(session)

        // startSession 在此处 inline 完成，保证下一帧进入 Running 时 writer 已就绪
        val anchorTs = lockedPreTriggerBuffer.firstOrNull()?.timestamp ?: filteredData.timestamp
        activeTestStartTs = anchorTs
        val sessionId = telemetryRepository.startSession(TelemetrySessionType.PERFORMANCE_TEST)
        activeTestSessionId = sessionId
        val sessionStartTs = telemetryRepository.activeSessionStartTs
        if (sessionStartTs != null) {
            for (frame in lockedPreTriggerBuffer) {
                telemetryRepository.writeSample(
                    TelemetrySample(
                        tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
                        lat = frame.latitude,
                        lon = frame.longitude,
                        speedKmh = frame.speed,
                        bearingDeg = frame.bearing,
                    )
                )
            }
        } else {
            FileLogger.e(
                TAG,
                "startTest preTrigger backfill: missing activeSessionStartTs after startSession, skip telemetry write"
            )
        }

        FileLogger.d(TAG, "startTest: session.startTime=${session.startTime}, triggerTime=${session.triggerTime}")
        FileLogger.d(TAG, "startTest: dataPoints.size=${session.dataPoints.size}")
        session.dataPoints.firstOrNull()?.let {
            FileLogger.d(TAG, "startTest: firstPoint elapsedTime=${it.elapsedTime}, speed=${it.speed}")
        }
        session.dataPoints.lastOrNull()?.let {
            FileLogger.d(TAG, "startTest: lastPoint elapsedTime=${it.elapsedTime}, speed=${it.speed}")
        }
    }

    fun currentLapTrackDebugSummary(): String? {
        val trackId = _lapRunConfig.value?.trackId ?: return null
        val track = trackCatalog.getTrack(trackId) ?: return null
        return buildTrackDebugSummary(track)
    }

    private suspend fun bridgeGpsToLapTiming(gpsData: GpsData) {
        val config = _lapRunConfig.value ?: return
        if (_currentMode.value != TestMode.LapDebug || !isLapRecording) return

        // Requirement 3：未同步帧整帧跳过，并重置 lastLapGpsSample
        // 失联恢复后首个同步帧走首样本分支（previousSample == null），
        // 避免 detector 对"跨几秒位移"做线段相交判定伪造过线。
        if (!gpsData.isTimeSynced) {
            lastLapGpsSample = null
            FileLogger.d(TAG, "bridgeGpsToLapTiming: skip unsynced frame, reset prev")
            return
        }

        val currentSession = _lapSession.value ?: return
        val track = trackCatalog.getTrack(config.trackId) ?: return
        val currentSample = gpsData.toLapGpsSample()
        val previousSample = lastLapGpsSample

        // A18 + A39 战役 F R4 + 2026-06-04 降频采样:25Hz 逐帧 bridge 推进日志每秒最多 1 条
        FileLogger.vSampled(TAG, "bridge-lap") {
            "bridgeGpsToLapTiming: track=${track.id}, sessionStatus=${currentSession.status}, currentLapIndex=${currentSession.currentLapIndex}, nextGate=${currentSession.nextExpectedGateIndex}, gpsTs=${gpsData.timestamp}, lat=${"%.3f".format(gpsData.latitude)}, lon=${"%.3f".format(gpsData.longitude)}, speed=${gpsData.speed}, bearing=${gpsData.bearing}, prevTs=${previousSample?.timestampMillis}, prevLat=${previousSample?.latitude?.let { "%.3f".format(it) }}, prevLon=${previousSample?.longitude?.let { "%.3f".format(it) }}"
        }

        // A38 三段式守卫（openspec fix-lap-timing-engine-entry-hardening Requirement 4）：
        //   段 1 首样本 → 仅赋 lastLapGpsSample 为下一帧准备基准，A34 死码一并清理
        //   段 2 ts 回跳 → 整帧丢弃，不更新 lastLapGpsSample（保持前帧为污染源截断）
        //   段 3 正常推进 → 赋 lastLapGpsSample + 喂 engine

        // 段 1：首样本分支
        //   历史死码 `_lapSession.value = currentSession` 删除（A34 顺手清理：StateFlow
        //   相同引用不 emit，纯死码；留着未来换 SharedFlow 会引爆"每帧都 emit"的 bug）
        if (previousSample == null || currentSample.timestampMillis <= 0L) {
            lastLapGpsSample = currentSample
            return
        }

        // 段 2：A38 ts 单调守卫
        //   回跳帧 **不** 更新 lastLapGpsSample（保持前帧作为下一帧基准）
        //   与 A13 "异常帧不更新 previousRaw" 模式一致
        if (currentSample.timestampMillis < previousSample.timestampMillis) {
            FileLogger.d(
                TAG,
                "bridgeGpsToLapTiming: ts regression, drop sample prevTs=${previousSample.timestampMillis} curTs=${currentSample.timestampMillis}"
            )
            return
        }

        // 段 3：正常推进
        lastLapGpsSample = currentSample

        // 8.2：GPS 样本写入二进制文件
        // activeLapStartSystemTs != null 表示 lap 模式已激活；session 在首帧懒启动，保证 writer 就绪
        val lapAnchorTs = activeLapStartSystemTs
        if (lapAnchorTs != null) {
            if (activeLapSessionId == null) {
                // persist-session-summary-fields round（**与 round A `fix-lap-binary-ts-hygiene` 同函数潜在冲突**：
                // A 改 line 562 附近 tsDeltaMs 公式；本 round 改本行 startSession 签名加 trackId+trackNameSnapshot）：
                // - trackId 来自当前 LapRunConfig
                // - trackNameSnapshot 是 startSession 时刻 catalog 解析的 zh display name 快照
                //   （多赛道扩展后 catalog 删除赛道时 detail 屏 D5 仍能显示当时赛道名，不 fallback currentSelectedTrack）
                val trackNameSnapshot = trackCatalog.getTrack(config.trackId)?.name?.zh
                activeLapSessionId = telemetryRepository.startSession(
                    type = TelemetrySessionType.LAP_SESSION,
                    trackId = config.trackId,
                    trackNameSnapshot = trackNameSnapshot,
                )
            }
            // fix-lap-binary-ts-hygiene round：anchor 必须等于 header.startTs（同时刻、同时钟域）。
            // 拉 repository.activeSessionStartTs 作为 anchor（startSession 内部赋值与 header.startTs 同源）。
            // sessionStartTs == null 是 invariant 破坏（startSession 刚返回 sessionId 时不该出现），
            // 走 FileLogger.w 警告 + skip telemetry 写入；**不得 ?: return 提前结束 bridgeGpsToLapTiming**——
            // 后面 lapTimingEngine.processSample / _lapSession.value 更新 / 过线事件写 Room 必须照常执行。
            val sessionStartTs = telemetryRepository.activeSessionStartTs
            if (sessionStartTs != null) {
                telemetryRepository.writeSample(
                    TelemetrySample(
                        tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
                        lat = gpsData.latitude,
                        lon = gpsData.longitude,
                        speedKmh = gpsData.speed,
                        bearingDeg = gpsData.bearing,
                    )
                )
            } else {
                // FileLogger 只有 d/v/e；invariant 破坏（startSession 刚返回 sessionId 但 activeSessionStartTs
                // 仍为 null）走 error 级合理但不致命，bridge 必须继续。
                FileLogger.e(
                    TAG,
                    "bridgeGpsToLapTiming: missing activeSessionStartTs after startSession, skip telemetry write but engine continues"
                )
            }
        }

        val updatedSession = lapTimingEngine.processSample(
            session = currentSession,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample
        )

        // A18 战役 F R4 + 2026-06-04 降频采样:25Hz 逐帧 bridge 结果日志每秒最多 1 条
        FileLogger.vSampled(TAG, "lap-result") {
            "lapTimingResult: status=${updatedSession.status}, currentLapIndex=${updatedSession.currentLapIndex}, nextGate=${updatedSession.nextExpectedGateIndex}, crossings=${updatedSession.crossingEvents.takeLast(3)}, completedLaps=${updatedSession.completedLaps.size}"
        }

        _lapSession.value = updatedSession
        _latestLapRecords.value = updatedSession.completedLaps

        // 4.2：检测圈速完成，5 秒后触发 settle flush
        if (updatedSession.completedLaps.size > currentSession.completedLaps.size) {
            viewModelScope.launch {
                delay(5_000L)
                telemetryRepository.flush()
            }
            // livetiming-lap-upload：新完成的圈实时上报（旁路副作用；前置/失败由 orchestrator
            // 内部处理，异常不影响本地圈速记录）。每出圈顺带 flush 待传队列。
            val newLaps = updatedSession.completedLaps.drop(currentSession.completedLaps.size)
            viewModelScope.launch {
                newLaps.forEach { runCatching { lapUploadOrchestrator.onLapCompleted(it) } }
                runCatching { lapUploadOrchestrator.flush() }
            }
        }

        // 8.3：检测新过线事件并写入 Room（事务保障）
        // lapIndex 用 currentSession（processSample 前）的值，避免闭圈后 index 已推进导致错位
        val newCrossings = updatedSession.crossingEvents
        val prevCount = lastWrittenCrossingCount
        if (newCrossings.size > prevCount) {
            val lapSessionId = activeLapSessionId
            if (lapSessionId != null) {
                val lapIndexAtCrossing = lapIndexForCrossing(
                    previousLapIndex = currentSession.currentLapIndex,
                    updatedLapIndex = updatedSession.currentLapIndex,
                )
                val toWrite = newCrossings.subList(prevCount, newCrossings.size)
                // 游标按未过滤 size 推进(in-memory 列表索引语义,与入库量无关)
                lastWrittenCrossingCount = newCrossings.size
                // fix-crossing-events-write-amplification:25Hz 逐帧 NoIntersection 拒绝不入库
                // (2026-06-03 路测一晚 1.8 万行写放大);accepted 真相源 + 有价值拒绝全保留
                toWrite.filter(::shouldPersistCrossing).forEach { crossing ->
                    // fix-lap-crossing-clock-hygiene round：过线事件触发的同一 ViewModel 协程上下文内
                    // 立即取 currentTimeMillis 作为 wallClock，与 binary samples absoluteTs 同时钟域，
                    // 供未来 per-lap segment readLapSamples 窗口截取使用。
                    // **MUST 在此处构造表达式内同步取值**，不得通过 viewModelScope.launch / withContext
                    // / delay 等异步路径间接计算（避免引入 binary writer queue 延迟到 wallClock 上）。
                    telemetryRepository.writeCrossing(
                        TelemetryCrossingEvent(
                            sessionId = lapSessionId,
                            lapIndex = lapIndexAtCrossing,
                            crossingTimestampMs = crossing.timestampMillis,
                            crossingWallClockTimestampMs = System.currentTimeMillis(),
                            speedKmh = crossing.directionalSpeedMps?.let { it * 3.6 } ?: 0.0,
                            gateId = crossing.gateId,
                            gateType = crossing.gateType.name,
                            accepted = crossing.accepted,
                            reason = crossing.reason.name,
                            directionScore = crossing.directionScore,
                        )
                    )
                }
            }
        }
    }

    private fun createLapSession(trackId: String): LapSession {
        return LapSession(
            sessionId = UUID.randomUUID().toString(),
            trackId = trackId,
            status = LapSessionStatus.Ready
        )
    }

    private fun buildTrackDebugSummary(track: Track): String {
        val startFinish = track.startFinishGate
        val sectors = track.sectorGates.joinToString(separator = ";") { gate ->
            "${gate.id}=${gate.line.start.latitude},${gate.line.start.longitude}->${gate.line.end.latitude},${gate.line.end.longitude}|dir=${gate.passDirection.x},${gate.passDirection.y}"
        }

        return "trackId=${track.id},source=${track.source.name},startFinish=${startFinish.line.start.latitude},${startFinish.line.start.longitude}->${startFinish.line.end.latitude},${startFinish.line.end.longitude}|dir=${startFinish.passDirection.x},${startFinish.passDirection.y},sectors=[$sectors]"
    }

    private fun GpsData.toLapGpsSample(): GpsSample = GpsSample(
        timestampMillis = timestamp,
        latitude = latitude,
        longitude = longitude,
        speedKmh = speed,
        bearingDegrees = bearing,
        altitudeMeters = altitude,
        accuracyMeters = hdop
    )

    private fun updateLaunchStatus(gpsData: GpsData) {
        val connectionState = _connectionState.value
        // Requirement 3.5 (c)：lastDataAge 用 elapsedRealtime delta，不依赖 gpsData.timestamp
        // 避免未同步 / sentinel 值污染 launchStatus 数据年龄判定
        val lastDataAge = SystemClock.elapsedRealtime() - lastReceivedAtElapsed

        // 根据测试类型确定起点速度范围
        val template = when (val state = _testState.value) {
            is TestState.Preparing -> state.template
            is TestState.Ready -> state.template
            is TestState.Running -> state.session.template
            else -> null
        }

        val (startSpeedMin, startSpeedMax) = when (template) {
            is TestTemplate.Acceleration0To100 -> 0.0 to 3.0
            is TestTemplate.Braking100To0 -> 95.0 to 105.0
            else -> 0.0 to 3.0
        }

        _launchStatus.value = smartTestLauncher.checkLaunchConditions(
            gpsData, connectionState, lastDataAge,
            startSpeedMin = startSpeedMin,
            startSpeedMax = startSpeedMax
        )
    }

    // 防止重复调用 finishTest
    private var isFinishing = false

    private fun finishTest(session: TestSession) {
        // 同步检查并设置标记，防止异步期间重复调用
        if (isFinishing) return
        isFinishing = true

        viewModelScope.launch {
            val sessionId = activeTestSessionId
            if (sessionId != null) {
                telemetryRepository.endSession(sessionId)
                activeTestSessionId = null
            }
            activeTestStartTs = null

            val binaryFilePath = if (sessionId != null) {
                telemetryRepository.getSession(sessionId)?.binaryFilePath ?: ""
            } else {
                ""
            }
            val result = calculateResultUseCase(session, binaryFilePath)
            // fix-accel-last-crossing design Decision 5:窗口摘要日志（core/domain 无 FileLogger 依赖,锚点放 VM）
            // dnf = 计时窗口缺失（未真正过终点线/未起步）;points=窗口内/全量 反映剔除量
            FileLogger.d(
                TAG,
                "perfResult window: total=${"%.2f".format(result.totalTime)}s dist=${"%.1f".format(result.totalDistance)}m " +
                    "points=${result.dataPoints.size}/${session.dataPoints.size} dnf=${result.dataPoints.isEmpty()}"
            )
            testResultRepository.saveResult(result)
            _testState.value = TestState.Completed(result)
        }
    }

    fun resetFinishingFlag() {
        isFinishing = false
    }
}

/**
 * crossing_events 持久化过滤谓词(fix-crossing-events-write-amplification spec R1):
 * - accepted == true:全写(lapCount/bestLapMs 派生的计时真相源,MUST NOT 过滤)
 * - accepted == false 且 reason != NoIntersection:保留(WrongDirection/UnexpectedGateOrder/
 *   TooSlow/Cooldown——真实过线被拒,罕见且有诊断价值)
 * - NoIntersection 拒绝:25Hz 常规帧,MUST NOT 入库(2026-06-03 路测 1.8 万行写放大根因)
 */
internal fun shouldPersistCrossing(crossing: CrossingEvent): Boolean =
    crossing.accepted || crossing.reason != CrossingReason.NoIntersection
