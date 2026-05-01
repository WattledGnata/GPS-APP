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
import com.blazepush.feature.test.FileLogger
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
import com.blazepush.core.domain.model.TestState
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.FilteredGpsData
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapRecord
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.LapLiveState
import com.blazepush.feature.test.usecase.LapLiveStateDeriver
import com.blazepush.feature.test.usecase.LapTimingEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    fun selectTrack(track: Track) {
        _currentSelectedTrack.value = track
    }

    private val _lapRunConfig = MutableStateFlow<LapRunConfig?>(null)
    val lapRunConfig: StateFlow<LapRunConfig?> = _lapRunConfig.asStateFlow()

    private val _lapSession = MutableStateFlow<LapSession?>(null)
    val lapSession: StateFlow<LapSession?> = _lapSession.asStateFlow()

    val lapLiveState: StateFlow<LapLiveState> = combine(
        _lapSession,
        gpsDataViewModel.gpsData,
        gpsDataViewModel.connectionState,
        gpsDataViewModel.dataQuality,
        tickerFlow(LAP_LIVE_TICK_PERIOD_MS),
    ) { session: LapSession?, gps: GpsData, conn: ConnectionState, quality: DataQuality, _: Unit ->
        LapLiveStateDeriver.derive(
            session = session,
            currentTimeMs = gps.timestamp,
            gpsData = gps,
            connectionState = conn,
            dataQuality = quality,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(LAP_LIVE_SUBSCRIPTION_TIMEOUT_MS),
        initialValue = LapLiveState(
            currentLapTimerMs = null,
            lastLapTimeMs = null,
            bestLapTimeMs = null,
            deltaToBestMs = null,
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
                _currentSelectedTrack.value = loaded.firstOrNull()
            }
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

                bridgeGpsToLapTiming(gpsData)
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
        val completedLaps = sessionSnapshot?.completedLaps.orEmpty()
        val lapCount = completedLaps.size
        val bestLapMs = completedLaps.minOfOrNull { it.durationMillis }
        val totalDurationMs = startTs?.let { System.currentTimeMillis() - it } ?: 0L

        telemetryRepository.endSession(sessionId)

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
                val anchorTs = activeTestStartTs
                if (anchorTs != null) {
                    telemetryRepository.writeSample(
                        TelemetrySample(
                            tsDeltaMs = filteredData.timestamp - anchorTs,
                            lat = filteredData.latitude,
                            lon = filteredData.longitude,
                            speedKmh = filteredData.speed,
                            bearingDeg = filteredData.bearing,
                        )
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
        for (frame in lockedPreTriggerBuffer) {
            telemetryRepository.writeSample(
                TelemetrySample(
                    tsDeltaMs = frame.timestamp - anchorTs,
                    lat = frame.latitude,
                    lon = frame.longitude,
                    speedKmh = frame.speed,
                    bearingDeg = frame.bearing,
                )
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

        // A18 + A39 战役 F R4：25Hz 高频 bridge 推进日志降级为 VERBOSE + 坐标
        // %.3f 精度；isVerboseEnabled 早退避免 verbose 关闭时仍执行字符串插值。
        if (FileLogger.isVerboseEnabled) {
            FileLogger.v(
                TAG,
                "bridgeGpsToLapTiming: track=${track.id}, sessionStatus=${currentSession.status}, currentLapIndex=${currentSession.currentLapIndex}, nextGate=${currentSession.nextExpectedGateIndex}, gpsTs=${gpsData.timestamp}, lat=${"%.3f".format(gpsData.latitude)}, lon=${"%.3f".format(gpsData.longitude)}, speed=${gpsData.speed}, bearing=${gpsData.bearing}, prevTs=${previousSample?.timestampMillis}, prevLat=${previousSample?.latitude?.let { "%.3f".format(it) }}, prevLon=${previousSample?.longitude?.let { "%.3f".format(it) }}"
            )
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
                activeLapSessionId = telemetryRepository.startSession(TelemetrySessionType.LAP_SESSION)
            }
            telemetryRepository.writeSample(
                TelemetrySample(
                    tsDeltaMs = gpsData.timestamp - lapAnchorTs,
                    lat = gpsData.latitude,
                    lon = gpsData.longitude,
                    speedKmh = gpsData.speed,
                    bearingDeg = gpsData.bearing,
                )
            )
        }

        val updatedSession = lapTimingEngine.processSample(
            session = currentSession,
            track = track,
            previousSample = previousSample,
            currentSample = currentSample
        )

        // A18 战役 F R4：25Hz 高频 bridge 结果日志降级为 VERBOSE（无坐标，只改
        // 方法名）；isVerboseEnabled 早退避免 verbose 关闭时仍执行字符串插值。
        if (FileLogger.isVerboseEnabled) {
            FileLogger.v(
                TAG,
                "lapTimingResult: status=${updatedSession.status}, currentLapIndex=${updatedSession.currentLapIndex}, nextGate=${updatedSession.nextExpectedGateIndex}, crossings=${updatedSession.crossingEvents.takeLast(3)}, completedLaps=${updatedSession.completedLaps.size}"
            )
        }

        _lapSession.value = updatedSession
        _latestLapRecords.value = updatedSession.completedLaps

        // 4.2：检测圈速完成，5 秒后触发 settle flush
        if (updatedSession.completedLaps.size > currentSession.completedLaps.size) {
            viewModelScope.launch {
                delay(5_000L)
                telemetryRepository.flush()
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
                lastWrittenCrossingCount = newCrossings.size
                toWrite.forEach { crossing ->
                    telemetryRepository.writeCrossing(
                        TelemetryCrossingEvent(
                            sessionId = lapSessionId,
                            lapIndex = lapIndexAtCrossing,
                            crossingTimestampMs = crossing.timestampMillis,
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
            testResultRepository.saveResult(result)
            _testState.value = TestState.Completed(result)
        }
    }

    fun resetFinishingFlag() {
        isFinishing = false
    }
}
