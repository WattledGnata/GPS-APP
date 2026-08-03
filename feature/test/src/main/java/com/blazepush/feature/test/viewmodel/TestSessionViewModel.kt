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
import com.blazepush.core.domain.model.GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
import com.blazepush.core.domain.usecase.isUsableForTiming
import com.blazepush.core.domain.usecase.MOTION_THRESHOLD_KMH
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.LapLiveState
import com.blazepush.feature.test.usecase.LapLiveStateDeriver
import com.blazepush.feature.test.usecase.LapGpsReadinessDeriver
import com.blazepush.feature.test.usecase.LapTimingEngine
import com.blazepush.feature.test.usecase.ReferenceLapIndex
import com.blazepush.feature.test.usecase.buildReferenceLapIndex
import com.blazepush.feature.test.usecase.projectDelta
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class TestMode {
    Acceleration,
    Braking,
    LapDebug,
    DebugCapture,
}

data class DebugCaptureStats(
    val isActive: Boolean = false,
    val sessionId: String? = null,
    val startedAtWallClock: Long? = null,
    val mainFrameCount: Long = 0L,
    val reliableFrameCount: Long = 0L,
    val noFixFrameCount: Long = 0L,
)

internal fun debugCaptureFlags(gpsData: GpsData): Int {
    var flags = 0
    if (gpsData.fixQuality > 0) flags = flags or 0x01
    if (gpsData.isTimeSynced) flags = flags or 0x02
    if (gpsData.isStale) flags = flags or 0x04
    if (gpsData.isUsableForTiming()) flags = flags or 0x08
    if (gpsData.satelliteCount >= 6) flags = flags or 0x10
    if (gpsData.hdop > 0.0 && gpsData.hdop < 2.0) flags = flags or 0x20
    if (gpsData.isConnected) flags = flags or 0x40
    if (gpsData.hasMainFrame) flags = flags or 0x80
    return flags
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
    val isDebugCapture: Boolean = false,
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
        // launch-arming-feedback Decision 1(2026-06-04 路测:2.7 km/h 缓动被当静止武装 → 起步
        // 锚点缺失结构性 DNF):静止阈值与成绩窗口起步锚点 MOTION_THRESHOLD_KMH(1.0)单一口径;
        // 确认改按**数据时间窗**(1000ms)而非帧数——帧数计数隐含 25Hz 假设,5Hz 模拟器回放下
        // "25 帧"=5 秒,把模拟器验证路径锁死(2026-06-04 夜实施缺陷修正)。
        private val STANDSTILL_SPEED_THRESHOLD = MOTION_THRESHOLD_KMH
        private const val STANDSTILL_CONFIRMATION_MS = 1000L
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

    private val _debugCaptureStats = MutableStateFlow(DebugCaptureStats())
    val debugCaptureStats: StateFlow<DebugCaptureStats> = _debugCaptureStats.asStateFlow()

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

    private val _lapGpsReadiness = MutableStateFlow(LapGpsReadiness.WAITING_DEVICE)
    val lapGpsReadiness: StateFlow<LapGpsReadiness> = _lapGpsReadiness.asStateFlow()

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

    // unify-speed-judgement-source Decision 2:滤波后速度暴露给执行屏仪表——
    // 显示/判停/成绩三处同源 filtered,用户所见即成绩所算(raw/filtered 分歧帧不再认知撕裂)
    private val _filteredSpeedKmh = MutableStateFlow(0.0)
    val filteredSpeedKmh: StateFlow<Double> = _filteredSpeedKmh.asStateFlow()

    // launch-arming-feedback Decision 2:静止武装状态暴露——UI 上升沿播"条件就绪,随时可以起步"
    // + Banner 三态文案;enterSmartLaunch/cancelTest 复位(重进页面不误播)
    private val _launchArmed = MutableStateFlow(false)
    val launchArmed: StateFlow<Boolean> = _launchArmed.asStateFlow()

    // 实时尝试计时(2026-06-05 用户反馈"elapsed 从触发一直数 50 多秒"):与成绩窗口同语义——
    // 滤波速度上穿 1.0 起步开始计时,掉回 <1.0(本次尝试作废)立即清零,再起步重新数;
    // 最后一把的实时数值 ≈ 最终播报成绩(所见即所得延续)。Running 期 UI 显示本值,
    // Completed 显示 result.totalTime(不变)。
    private var liveAttemptStartTs: Long? = null
    private val _liveAttemptElapsedSeconds = MutableStateFlow(0.0)
    val liveAttemptElapsedSeconds: StateFlow<Double> = _liveAttemptElapsedSeconds.asStateFlow()

    private var lastLapGpsSample: GpsSample? = null
    private var isLapRecording = false

    private var activeTestSessionId: String? = null
    private var activeTestStartTs: Long? = null

    private var activeLapSessionId: String? = null
    private var activeLapStartSystemTs: Long? = null
    private val lapSessionMutex = Mutex()
    private var lapSessionGeneration = 0L
    private var lapSessionStartJob: Job? = null
    private val lapTelemetryFlushScheduler = LapTelemetryFlushScheduler(viewModelScope) { sessionId ->
        telemetryRepository.flush(sessionId)
    }

    /**
     * 返回当前 active lap session id（录制引擎在 startRecording 时调用，读取当前关联的 session）。
     * null = 尚未持久化或已结束。录制入口不得直接使用 null 启动 CameraX，
     * 必须先 await [prepareActiveLapSessionForRecording]。
     *
     * camera-recording-and-gps-sync round（MUST 6）：public accessor，不破坏内部 private 写语义。
     */
    fun getActiveLapSessionId(): String? = activeLapSessionId

    /**
     * 为 REC 或 GPS 写入确保圈速 Session 已经插入 Room。
     *
     * Mutex 将“第二个有效 GPS 帧”与“用户点 REC”两个并发入口收敛为一次
     * startSession；generation 防止创建期间退出/重进后把旧 Session 误挂到新页面。
     * 返回 null 表示圈速已结束或上下文已换代，调用方必须 fail closed。
     */
    suspend fun prepareActiveLapSessionForRecording(): String? = lapSessionMutex.withLock {
        activeLapSessionId?.let { return@withLock it }
        val mode = _currentMode.value
        if (mode !in setOf(TestMode.LapDebug, TestMode.DebugCapture) ||
            !isLapRecording || activeLapStartSystemTs == null
        ) {
            return@withLock null
        }
        val generation = lapSessionGeneration
        val config = _lapRunConfig.value
        if (mode == TestMode.LapDebug && config == null) return@withLock null
        val created = telemetryRepository.startSession(
            type = TelemetrySessionType.LAP_SESSION,
            trackId = config?.trackId,
            trackNameSnapshot = if (mode == TestMode.DebugCapture) {
                "DEBUG 自由采集"
            } else {
                config?.trackId?.let { trackCatalog.getTrack(it)?.name?.zh }
            },
        )
        if (generation != lapSessionGeneration || !isLapRecording) {
            telemetryRepository.endSession(created)
            return@withLock null
        }
        activeLapSessionId = created
        if (mode == TestMode.DebugCapture) {
            _debugCaptureStats.value = _debugCaptureStats.value.copy(sessionId = created)
        }
        FileLogger.d(TAG, "lap session persisted before consumer start: sessionId=$created generation=$generation")
        created
    }

    /** REC await 返回后、CameraX 真正启动前的无挂起复核。 */
    fun isActiveLapSession(sessionId: String): Boolean =
        isLapRecording && activeLapSessionId == sessionId
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
    private var lastConnectionGeneration: Long = 0L
    private var lastMainFrameSequence: Long = 0L
    private var lastMainFrameSilenceTimeoutMs: Long = GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
    private var lastAcceptedLapGpsData: GpsData = GpsData.Empty
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val preTriggerBuffer = mutableListOf<FilteredGpsData>()

    private var isStartReady = false

    // 静止确认窗口起点(数据时间戳 ms);null = 当前不在静止段。时间窗判定对任意帧率
    // (25Hz 真机 / 5Hz 模拟器)语义一致:持续 <1.0 km/h 满 1000ms 即武装。
    private var standstillSinceTs: Long? = null
    private var consecutiveTriggerCount = 0

    /** 清除依赖连续轨迹的消费者状态，但保留 Main 帧接收元数据用于判断下一段 gap。 */
    private fun resetGpsContinuity() {
        lastLapGpsSample = null
        preTriggerBuffer.clear()
        gpsDataFilter.reset()
        _filteredSpeedKmh.value = 0.0
        _realtimeDeltaState.value = _realtimeDeltaState.value.copy(
            staleFrameCount = STALE_FRAME_THRESHOLD,
            outDeltaMs = null,
            outIsStale = true,
        )
    }

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
                if (state != ConnectionState.CONNECTED) {
                    lastAcceptedLapGpsData = GpsData.Empty
                    _lapGpsReadiness.value = LapGpsReadiness.WAITING_DEVICE
                } else {
                    _lapGpsReadiness.value = LapGpsReadinessDeriver.derive(
                        connectionState = state,
                        gpsData = lastAcceptedLapGpsData,
                    )
                }
            }
        }

        viewModelScope.launch {
            gpsDataViewModel.gpsData.collect { gpsData ->
                // connectionGeneration is monotonic. A delayed callback from an older GATT
                // generation must not alter readiness, continuity, persistence, or the engine.
                if (gpsData.connectionGeneration < lastConnectionGeneration) {
                    FileLogger.d(
                        TAG,
                        "drop old GPS generation=${gpsData.connectionGeneration} active=$lastConnectionGeneration",
                    )
                    return@collect
                }
                if (gpsData.connectionGeneration != lastConnectionGeneration) {
                    lastConnectionGeneration = gpsData.connectionGeneration
                    lastReceivedAtElapsed = 0L
                    lastMainFrameSequence = 0L
                    lastMainFrameSilenceTimeoutMs = GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
                    resetGpsContinuity()
                }

                lastAcceptedLapGpsData = gpsData
                _lapGpsReadiness.value = LapGpsReadinessDeriver.derive(
                    connectionState = _connectionState.value,
                    gpsData = gpsData,
                )

                // 只采信 BluetoothDataSource 在 GPS Main 解析成功时写入的单调时刻。
                // 时间包、stale 翻转、连接状态复位都不能伪装成新定位帧。
                val isNewMainFrame = gpsData.hasMainFrame &&
                    gpsData.mainFrameSequence != lastMainFrameSequence
                if (isNewMainFrame) {
                    val receivedAt = gpsData.mainFrameReceivedAtElapsedRealtimeMs
                    val gapMs = receivedAt - lastReceivedAtElapsed
                    if (lastReceivedAtElapsed > 0L &&
                        gapMs !in 0..<lastMainFrameSilenceTimeoutMs
                    ) {
                        // 即使 stale StateFlow 因调度合并未被观察到，也绝不跨静默 gap
                        // 用旧坐标和恢复首帧做过线/滤波/预触发计算。
                        resetGpsContinuity()
                    }
                    lastMainFrameSequence = gpsData.mainFrameSequence
                    lastReceivedAtElapsed = receivedAt
                    lastMainFrameSilenceTimeoutMs = gpsData.mainFrameSilenceTimeoutMs
                }
                updateLaunchStatus(gpsData)

                if (_currentMode.value == TestMode.DebugCapture) {
                    if (isNewMainFrame) captureDebugMainFrame(gpsData)
                    // 自由采集只落原始 Main 帧，不进滤波、圈门、秒差或计时引擎。
                    return@collect
                }

                if (!gpsData.isUsableForTiming()) {
                    resetGpsContinuity()
                    return@collect
                }
                if (_currentMode.value == TestMode.LapDebug && !isNewMainFrame) {
                    // Time 包、连接状态和 stale 派生更新可能让 StateFlow 发射，但它们不是新轨迹点。
                    // 只允许 mainFrameSequence 前进的可靠 Main 帧进入滤波与圈速引擎。
                    return@collect
                }

                val filteredData = gpsDataFilter.process(gpsData)
                // frequency-agnostic 诊断(2026-06-05):raw/filtered 并排逐帧(VERBOSE 全量,
                // 5Hz 模拟器每秒 5 条/25Hz 真机 25 条;debug 包默认开)——定位"跳 0"是源头还是滤波产物
                FileLogger.v(
                    TAG,
                    "speedPipeline: raw=${"%.1f".format(gpsData.speed)} filtered=${"%.1f".format(filteredData.speed)} " +
                        "anomaly=${filteredData.isAnomaly} posAnom=${filteredData.isPositionAnomaly} ts=${gpsData.timestamp}",
                )
                _filteredSpeedKmh.value = filteredData.speed // 仪表同源(Decision 2)
                updatePreTriggerBuffer(filteredData)
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
        standstillSinceTs = null
        consecutiveTriggerCount = 0
        _launchArmed.value = false // launch-arming-feedback:重进武装流程,复位防误播
        isFinishing = false  // 重置完成标记，允许新测试
        _testState.value = TestState.Preparing(template, carModel)
        startCountdown()
    }

    fun selectLapDebugMode(trackId: String) {
        selectLapDebugMode(LapRunConfig(trackId = trackId))
    }

    fun selectLapDebugMode(config: LapRunConfig) {
        val track = trackCatalog.getTrack(config.trackId) ?: return

        bleDeviceManager.requestImmediateReconnect("lap session entered")

        _currentMode.value = TestMode.LapDebug
        _lapRunConfig.value = config
        _lapSession.value = createLapSession(track.id)
        _lapGpsReadiness.value = LapGpsReadinessDeriver.derive(
            connectionState = _connectionState.value,
            gpsData = lastAcceptedLapGpsData,
        )
        _latestLapRecords.value = emptyList()
        lastLapGpsSample = null
        isLapRecording = true
        lastWrittenCrossingCount = 0
        // 进页锚点：Session 在下方立即异步持久化，GPS/REC 只 await 同一创建任务。
        activeLapStartSystemTs = System.currentTimeMillis()
        activeLapSessionId = null
        lapSessionGeneration++
        // RaceChrono 语义：进入圈速页就是一次 Session attempt。立即落 Room，
        // 不等 GPS 第二帧/不等 REC；快进快出允许留下 0s/几秒短 Session。
        // REC/GPS 仍复用 prepare 的 Mutex，不会重复创建。
        lapSessionStartJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            prepareActiveLapSessionForRecording()
        }

        FileLogger.d(TAG, "lapDebugTrackSummary: ${buildTrackDebugSummary(track)}")
    }

    fun startDebugCaptureMode() {
        bleDeviceManager.requestImmediateReconnect("lap session running")
        _currentMode.value = TestMode.DebugCapture
        _lapRunConfig.value = null
        _lapSession.value = null
        _lapGpsReadiness.value = LapGpsReadiness.WAITING_DEVICE
        _latestLapRecords.value = emptyList()
        resetGpsContinuity()
        isLapRecording = true
        lastWrittenCrossingCount = 0
        activeLapStartSystemTs = System.currentTimeMillis()
        activeLapSessionId = null
        lapSessionGeneration++
        _debugCaptureStats.value = DebugCaptureStats(
            isActive = true,
            startedAtWallClock = activeLapStartSystemTs,
        )
        lapSessionStartJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            prepareActiveLapSessionForRecording()
        }
        FileLogger.d(TAG, "debug free capture started generation=$lapSessionGeneration")
    }

    private suspend fun captureDebugMainFrame(gpsData: GpsData) {
        val sessionId = prepareActiveLapSessionForRecording() ?: return
        if (!isActiveLapSession(sessionId)) return
        val sessionStartTs = telemetryRepository.activeSessionStartTs ?: return
        val phoneMinusGpsAtReceiveMs = gpsData.timestamp
            .takeIf { gpsData.isTimeSynced && it != Long.MIN_VALUE }
            ?.let { System.currentTimeMillis() - it }
        FileLogger.vSampled(TAG, "debug-capture-$sessionId") {
            "debugCapture sid=$sessionId gen=${gpsData.connectionGeneration} " +
                "seq=${gpsData.mainFrameSequence} rxElapsed=${gpsData.mainFrameReceivedAtElapsedRealtimeMs} " +
                "deadline=${gpsData.mainFrameSilenceTimeoutMs}ms rate=${gpsData.frequency}Hz " +
                "fix=${gpsData.fixQuality} sats=${gpsData.satelliteCount} hdop=${gpsData.hdop} " +
                "timeSynced=${gpsData.isTimeSynced} gpsTs=${gpsData.timestamp} " +
                "phoneMinusGpsAtRx=${phoneMinusGpsAtReceiveMs ?: "NA"}ms stale=${gpsData.isStale}"
        }
        telemetryRepository.writeSample(
            TelemetrySample(
                tsDeltaMs = System.currentTimeMillis() - sessionStartTs,
                lat = gpsData.latitude,
                lon = gpsData.longitude,
                speedKmh = gpsData.speed,
                bearingDeg = gpsData.bearing,
                flags = debugCaptureFlags(gpsData),
            ),
        )
        val current = _debugCaptureStats.value
        _debugCaptureStats.value = current.copy(
            sessionId = sessionId,
            mainFrameCount = current.mainFrameCount + 1,
            reliableFrameCount = current.reliableFrameCount +
                if (gpsData.isUsableForTiming()) 1 else 0,
            noFixFrameCount = current.noFixFrameCount +
                if (gpsData.fixQuality <= 0 || gpsData.satelliteCount <= 0) 1 else 0,
        )
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
        _debugCaptureStats.value = DebugCaptureStats()
        endActiveLapSession()
    }

    private fun endActiveLapSession() {
        lapTelemetryFlushScheduler.cancel()
        // 同步摘掉旧 id，避免立即重进/换赛道时新状态覆盖后丢失旧 Session 收尾。
        val sessionId = activeLapSessionId
        activeLapSessionId = null
        activeLapStartSystemTs = null
        lastWrittenCrossingCount = 0
        if (sessionId != null) {
            viewModelScope.launch { telemetryRepository.endSession(sessionId) }
        }
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
        // 快进快出也必须先完成“进页即建 Session”，再收尾为 0s/几秒记录。
        lapSessionStartJob?.join()
        val sessionSnapshot = _lapSession.value
        val wasDebugCapture = _currentMode.value == TestMode.DebugCapture
        val startTs = activeLapStartSystemTs
        // D12 契约：lapCount / bestLapMs 仅基于 qualityFlags 全空的 valid 圈，
        // 排除 IncompleteSectors / ProtocolDesyncGap / SuspectedJitter 等带 quality flag 的作废圈
        val validLaps = sessionSnapshot?.completedLaps.orEmpty().filter { it.qualityFlags.isEmpty() }
        val lapCount = validLaps.size
        val bestLapMs = validLaps.minOfOrNull { it.durationMillis }
        val totalDurationMs = startTs?.let { System.currentTimeMillis() - it } ?: 0L

        val sessionId = lapSessionMutex.withLock {
            lapTelemetryFlushScheduler.cancel()
            val current = activeLapSessionId
            // 先关闭入口，再做 IO：REC await 与 END 交错时，后续的无挂起复核必定失败。
            isLapRecording = false
            activeLapSessionId = null
            activeLapStartSystemTs = null
            lastWrittenCrossingCount = 0
            if (current != null) telemetryRepository.endSession(current)
            current
        } ?: return null

        // unify-lap-count-pairing-semantics round（road-test-first 强制埋点）：读回持久化 lapCount
        // （站点 A，wallClock 配对身份数）与 Snackbar valid 计数（站点 D，qualityFlags 过滤）一并记录，
        // 真机路测核对两口径差异（有作废圈时 expected 不同）+ 旧 session null wallClock 行为（R3/R5）。
        val persistedLapCount = telemetryRepository.getSession(sessionId)?.lapCount
        FileLogger.d(
            "LapPairing",
            "finishLap sid=$sessionId snackbarValid=$lapCount persistedLapCount=$persistedLapCount key=wallClock",
        )

        _lapSession.value = sessionSnapshot?.copy(status = LapSessionStatus.Finished)
        if (_currentMode.value == TestMode.DebugCapture) {
            _debugCaptureStats.value = _debugCaptureStats.value.copy(isActive = false)
        }

        return LapSessionSaveResult(
            sessionId = sessionId,
            lapCount = lapCount,
            bestLapMs = bestLapMs,
            totalDurationMs = totalDurationMs,
            isDebugCapture = wasDebugCapture,
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
        standstillSinceTs = null
        _launchArmed.value = false // launch-arming-feedback:取消即解除武装
        liveAttemptStartTs = null
        _liveAttemptElapsedSeconds.value = 0.0
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
                // 实时尝试计时:掉回静止(<1.0)清零(本次尝试作废),再起步从新锚点重数
                if (filteredData.speed < STANDSTILL_SPEED_THRESHOLD) {
                    liveAttemptStartTs = null
                    _liveAttemptElapsedSeconds.value = 0.0
                } else {
                    val start = liveAttemptStartTs ?: filteredData.timestamp.also { liveAttemptStartTs = it }
                    _liveAttemptElapsedSeconds.value = (filteredData.timestamp - start) / 1000.0
                }
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
                // unify-speed-judgement-source Decision 1:判停用滤波后速度(speed 字段替换),
                // 与成绩窗口算法同源——raw 瞬时尖峰不再触发"done 却 DNF"(2026-06-04 22:37 路测回归)
                if (state.session.template.shouldEnd(filteredData.raw.copy(speed = filteredData.speed))) {
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
                val since = standstillSinceTs ?: filteredData.timestamp.also { standstillSinceTs = it }
                if (filteredData.timestamp - since >= STANDSTILL_CONFIRMATION_MS) {
                    isStartReady = true
                    standstillSinceTs = null
                    // launch-arming-feedback:武装就绪上升沿(UI 据此播报 + Banner 切就绪态)
                    _launchArmed.value = true
                    FileLogger.d(TAG, "launchArmed: 静止确认通过(<${STANDSTILL_SPEED_THRESHOLD}km/h 持续 ${STANDSTILL_CONFIRMATION_MS}ms),随时可起步")
                }
            } else {
                standstillSinceTs = null
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

        // 实时尝试计时初值:从 buffer+触发帧里找最后一次上穿 1.0 的帧(与成绩窗口锚点同语义;
        // 帧级精度即可,显示用途)。找不到(理论上触发时必已 >1.0)退化为触发帧。
        val seqForLive = lockedPreTriggerBuffer + filteredData
        liveAttemptStartTs = seqForLive.zipWithNext()
            .lastOrNull { (a, b) -> a.speed < STANDSTILL_SPEED_THRESHOLD && b.speed >= STANDSTILL_SPEED_THRESHOLD }
            ?.second?.timestamp ?: filteredData.timestamp
        _liveAttemptElapsedSeconds.value = (filteredData.timestamp - liveAttemptStartTs!!) / 1000.0

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
        if (!gpsData.isUsableForTiming()) {
            lastLapGpsSample = null
            FileLogger.d(TAG, "bridgeGpsToLapTiming: skip unusable/stale frame, reset prev")
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
            prepareActiveLapSessionForRecording()
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

        // 4.2：检测圈速完成并触发上报；telemetry flush 在下方每个可靠起终点 crossing 入库后调度。
        if (updatedSession.completedLaps.size > currentSession.completedLaps.size) {
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
                    if (crossing.accepted &&
                        crossing.gateType == com.blazepush.feature.test.model.track.TimingGateType.StartFinish
                    ) {
                        lapTelemetryFlushScheduler.schedule(lapSessionId)
                    }
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
        val lastDataAge = if (lastReceivedAtElapsed > 0L) {
            SystemClock.elapsedRealtime() - lastReceivedAtElapsed
        } else {
            Long.MAX_VALUE
        }

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
