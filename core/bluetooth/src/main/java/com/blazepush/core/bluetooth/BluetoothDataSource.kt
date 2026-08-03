// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（`_dataFlow` / `_connectionState` MutableStateFlow
//       backing 属性 / class comment / public fun comment / import-order / trailing
//       newline）。rename backing 属性会扩散到下游消费者，超出战役 G R4/R5
//       scope。评审方 2026-04-24 commit 阶段 B 方案批准此 ignore。
package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.domain.model.GPS_FIX_RECOVERY_MAIN_FRAMES
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.hasReliableFixEvidence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * 蓝牙数据源 - 唯一的GPS数据发射点
 * 替代原有的BluetoothManager，使用普通Kotlin类而非Android Service
 *
 * fix-ble-auto-reconnect（2026-06-04）：增加会话内意外断开自动重连——
 * DISCONNECTED 且最近用户意图为连接时,指数退避(1s·2^n 封顶 30s)无限重试,
 * 直到 CONNECTED / disconnect() / 切设备。dispatcher 构造参数仅供单测注入
 * TestDispatcher(生产默认 Dispatchers.IO,AppModule 构造点零改动)。
 */
class BluetoothDataSource(
    private val context: Context,
    private val parser: RaceChronoParser,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onConnectAttempt: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "BluetoothDataSource"
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
    }

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var connectionCollectJob: Job? = null

    // 自动重连状态（ble-auto-reconnect spec R1/R2）
    private var lastRequestedAddress: String? = null
    private var userInitiatedDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var connectionIntentToken = 0L
    private var activeAttemptToken = 0L
    private var connectSetupJob: Job? = null
    private var attemptInFlight = false
    private val orchestrationLock = Any()
    private var beforeConnectAttempt: () -> Unit = {}
    private var activeConnectionGeneration = 0L
    private var mainFrameSequence = 0L
    private val mainFrameCadenceTracker = MainFrameCadenceTracker()
    private var consecutiveReliableMainFrames = 0

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 外接 GPS 设备电量百分比（null = 无此服务 / 未读到）
    @Suppress("PropertyName")
    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private var bleConnection: BleConnection? = null

    /**
     * 公开连接入口（用户意图=想连）：记录目标地址、复位重连状态后委托 [doConnect]。
     * 切设备时取消针对旧地址的挂起重连（spec R2）。
     */
    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        val intentToken = synchronized(orchestrationLock) {
            if (
                lastRequestedAddress == deviceAddress &&
                !userInitiatedDisconnect &&
                (attemptInFlight ||
                    _connectionState.value in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED))
            ) {
                Log.d(TAG, "coalesce duplicate connect target=$deviceAddress state=${_connectionState.value}")
                return
            }
            connectionIntentToken++
            activeAttemptToken++
            attemptInFlight = false
            lastRequestedAddress = deviceAddress
            userInitiatedDisconnect = false
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            connectSetupJob?.cancel()
            connectionIntentToken
        }
        doConnect(deviceAddress, intentToken)
    }

    /**
     * 前台/lap session/扫描命中/蓝牙重开共用的立即重试入口。
     * 仅抢占等待中的退避；已有 CONNECTING/CONNECTED attempt 时合并为 no-op。
     */
    fun requestImmediateReconnect(reason: String): Boolean {
        val request = synchronized(orchestrationLock) {
            val address = lastRequestedAddress ?: return false
            if (userInitiatedDisconnect) return false
            if (
                attemptInFlight ||
                _connectionState.value in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)
            ) {
                Log.d(TAG, "coalesce immediate trigger=$reason state=${_connectionState.value}")
                return false
            }
            reconnectJob?.cancel()
            reconnectJob = null
            address to connectionIntentToken
        }
        Log.d(TAG, "immediate reconnect trigger=$reason target=${request.first}")
        doConnect(request.first, request.second)
        return true
    }

    /**
     * 实际连接路径（A27 清理顺序不变）。重连路径直接走此函数,**不**复位
     * reconnectAttempt——否则退避退化为恒 1s 高频 connectGatt（design Decision 4）。
     */
    private fun doConnect(deviceAddress: String, intentToken: Long) {
        val attemptToken = synchronized(orchestrationLock) {
            if (
                intentToken != connectionIntentToken ||
                userInitiatedDisconnect ||
                lastRequestedAddress != deviceAddress ||
                attemptInFlight
            ) return
            attemptInFlight = true
            ++activeAttemptToken
        }
        connectSetupJob = scope.launch {
            try {
                synchronized(orchestrationLock) {
                    if (!ownsAttempt(intentToken, attemptToken, deviceAddress)) return@launch
                    beforeConnectAttempt()
                    if (!ownsAttempt(intentToken, attemptToken, deviceAddress)) return@launch
                    onConnectAttempt(deviceAddress)
                // A27 切设备前清旧连接（严格顺序，原子化）：
                // 1. 先 cancel 旧 collectJob，避免旧 bleConnection 终态 state 在新连接
                //    构造窗口内传导到 _connectionState 与 CONNECTING 竞争
                // 2. 再 disconnect 旧 bleConnection（走 R3 回调释放路径）
                // 3. 最后重置 _connectionState 进入 CONNECTING
                connectionCollectJob?.cancel()
                connectionCollectJob = null
                bleConnection?.disconnect()
                bleConnection = null

                if (!ownsAttempt(intentToken, attemptToken, deviceAddress)) return@launch

                val generation = beginConnectionGeneration()

                _connectionState.value = ConnectionState.CONNECTING
                Log.d(TAG, "状态设置为 CONNECTING，创建 BleConnection")

                bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
                    handleIncomingData(generation, uuid, rawData)
                }

                // 在单独的协程中监听 BleConnection 的状态变化
                bleConnection?.connectionState?.let { stateFlow ->
                    connectionCollectJob = scope.launch {
                        // 同 job 内子协程收集 dataStale → 写 GpsData.isStale（ble-connection-liveness
                        // spec R3）。父 job（connectionCollectJob）取消时子协程一并取消，无需独立
                        // cancel，不破 A27 cleanup 严格顺序契约（connectionCollectJob 单点取消即清干净）。
                        launch {
                            bleConnection?.dataStale?.collect { stale ->
                                if (stale) consecutiveReliableMainFrames = 0
                                val current = _dataFlow.value
                                _dataFlow.value = current.copy(
                                    isStale = stale,
                                    consecutiveReliableMainFrames = if (stale) {
                                        0
                                    } else {
                                        current.consecutiveReliableMainFrames
                                    },
                                )
                            }
                        }
                        launch {
                            bleConnection?.batteryPercent?.collect { pct ->
                                _batteryPercent.value = pct
                            }
                        }
                        // drop(1):跳过 BleConnection StateFlow 的 replay 初值(新实例恒 DISCONNECTED,
                        // 无信息量)——不跳过会在每次 doConnect 时误调度假重连,1s 后拆掉刚建好的连接
                        stateFlow.drop(1).collect { state ->
                            if (!ownsAttempt(intentToken, attemptToken, deviceAddress)) {
                                Log.d(TAG, "ignore state from old attempt=$attemptToken state=$state")
                                return@collect
                            }
                            Log.d(TAG, "BleConnection 状态变化: $state")
                            _connectionState.value = state
                            when (state) {
                                ConnectionState.CONNECTED -> {
                                    // 连上即复位退避(下次意外断开从 1s 重来,spec R1 Scenario 2)
                                    // 并取消任何挂起重连(防御残留假重连拆好链)
                                    synchronized(orchestrationLock) {
                                        attemptInFlight = false
                                        reconnectAttempt = 0
                                        reconnectJob?.cancel()
                                        reconnectJob = null
                                    }
                                }
                                // 意外断开(远端断/连接超时)→ 调度重连(spec R1)
                                ConnectionState.DISCONNECTED -> {
                                    synchronized(orchestrationLock) { attemptInFlight = false }
                                    invalidateConnectionGeneration(isStale = false)
                                    maybeScheduleReconnect()
                                }
                                else -> { /* CONNECTING/DISCONNECTING 无重连动作 */ }
                            }
                        }
                    }
                }

                    bleConnection?.connect()
                    Log.d(TAG, "BleConnection.connect() 调用完成")
                }

            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                _dataFlow.value = _dataFlow.value.copy(
                    isConnected = false,
                    errorMessage = e.message
                )
                // 异常分支不经 collect 传导(直接赋值),需显式调度重连(design Risks 竞态条款)
                if (ownsAttempt(intentToken, attemptToken, deviceAddress)) {
                    synchronized(orchestrationLock) { attemptInFlight = false }
                    maybeScheduleReconnect()
                }
            }
        }
    }

    private fun ownsAttempt(intentToken: Long, attemptToken: Long, address: String): Boolean =
        synchronized(orchestrationLock) {
            intentToken == connectionIntentToken &&
                attemptToken == activeAttemptToken &&
                !userInitiatedDisconnect &&
                lastRequestedAddress == address
        }

    /** Manager installs scan cancellation once; every initial/retry attempt crosses this gate. */
    internal fun installBeforeConnectAttempt(hook: () -> Unit) {
        beforeConnectAttempt = hook
    }

    /**
     * 意外断开的退避重连调度（ble-auto-reconnect spec R1/R2）。
     * guard:用户主动断开 / 无连接历史 / 已有挂起重连 → 不调度。
     */
    private fun maybeScheduleReconnect() {
        val schedule = synchronized(orchestrationLock) {
            val address = lastRequestedAddress ?: return
            if (userInitiatedDisconnect) return
            if (reconnectJob != null) return
            if (_connectionState.value != ConnectionState.DISCONNECTED) return
            val intentToken = connectionIntentToken
            val delayMs = reconnectDelayMs(reconnectAttempt)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                val valid = synchronized(orchestrationLock) {
                    if (
                        userInitiatedDisconnect ||
                        intentToken != connectionIntentToken ||
                        address != lastRequestedAddress ||
                        _connectionState.value != ConnectionState.DISCONNECTED
                    ) {
                        false
                    } else {
                        reconnectJob = null
                        reconnectAttempt = minOf(reconnectAttempt + 1, 30)
                        true
                    }
                }
                if (!valid) return@launch
                doConnect(address, intentToken)
            }
            reconnectJob = job
            Triple(job, address, delayMs)
        }
        Log.d(TAG, "意外断开,${schedule.third}ms 后第 ${reconnectAttempt + 1} 次自动重连 ${schedule.second}")
        schedule.first.start()
    }

    internal fun reconnectDelayMs(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 5)
        return minOf(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS shl exponent)
    }

    fun disconnect() {
        // 用户意图=想断:取消挂起重连,后续 DISCONNECTED 不再调度(spec R2)
        synchronized(orchestrationLock) {
            userInitiatedDisconnect = true
            connectionIntentToken++
            activeAttemptToken++
            attemptInFlight = false
            reconnectJob?.cancel()
            reconnectJob = null
            connectSetupJob?.cancel()
            connectSetupJob = null
            lastRequestedAddress = null
        }
        scope.launch {
            connectionCollectJob?.cancel()
            connectionCollectJob = null
            bleConnection?.disconnect()
            bleConnection = null
            _connectionState.value = ConnectionState.DISCONNECTED
            invalidateConnectionGeneration(isStale = false)
            _batteryPercent.value = null
        }
    }

    /**
     * 战役 G R4（A25 isConnected 语义收敛）：BLE 数据回调的数据处理入口。
     *
     * 从 `connect()` 里的 onDataReceived lambda 提取为 `internal fun`，便于单测
     * 不经过真实 BLE 链路直接喂数据。可见性 `internal` 保证同 module 测试可调，
     * 对外仍是实现细节。
     *
     * 契约：GPS Main 成功才建立主帧身份；GPS Time 只更新时间基准，
     * 不递增主帧 sequence、不刷新接收时刻、不清 stale。
     *
     * 语义：
     * - GPS Main parse 成功 → 递增 sequence，记录 monotonic receipt time，hasMainFrame=true。
     * - GPS Main parse 失败 → hasMainFrame=false，禁止沿用上一位置。
     * - GPS Time 成功/失败 → 保留主帧 freshness 元数据，等下一主帧确认同步。
     * - 未知 UUID → parseResult == null → 整个写入块跳过，_dataFlow.value 完全不
     *   触碰，isConnected 原值保留（硬区分 v1 的 `else -> _dataFlow.value.copy(isConnected = true)`）
     */
    internal fun handleIncomingData(uuid: java.util.UUID, rawData: ByteArray) {
        handleIncomingData(activeConnectionGeneration, uuid, rawData)
    }

    internal fun handleIncomingData(generation: Long, uuid: java.util.UUID, rawData: ByteArray) {
        if (generation != activeConnectionGeneration) {
            Log.d(TAG, "ignore frame from old generation=$generation active=$activeConnectionGeneration")
            return
        }
        val isMainFrame = uuid.toString() == "00000003-0000-1000-8000-00805f9b34fb"
        val parseResult: com.blazepush.core.domain.model.GpsData? = when (uuid.toString()) {
            "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
            "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
            else -> null
        }
        if (parseResult != null) {
            _dataFlow.value = if (isMainFrame && parseResult.errorMessage == null) {
                val receivedAt = elapsedRealtimeMs()
                val previous = _dataFlow.value
                if (previous.hasMainFrame) {
                    val gapMs = receivedAt - previous.mainFrameReceivedAtElapsedRealtimeMs
                    if (gapMs !in 0..<previous.mainFrameSilenceTimeoutMs) {
                        consecutiveReliableMainFrames = 0
                    }
                }
                mainFrameCadenceTracker.onMainFrame(receivedAt)
                mainFrameSequence++
                val current = parseResult.copy(
                    isConnected = true,
                    errorMessage = null,
                    isStale = false,
                    connectionGeneration = generation,
                    mainFrameSequence = mainFrameSequence,
                    mainFrameReceivedAtElapsedRealtimeMs = receivedAt,
                    hasMainFrame = true,
                    mainFrameSilenceTimeoutMs = mainFrameCadenceTracker.currentSilenceTimeoutMs(),
                    consecutiveReliableMainFrames = 0,
                )
                consecutiveReliableMainFrames = if (current.hasReliableFixEvidence()) {
                    minOf(consecutiveReliableMainFrames + 1, GPS_FIX_RECOVERY_MAIN_FRAMES)
                } else {
                    0
                }
                current.copy(consecutiveReliableMainFrames = consecutiveReliableMainFrames)
            } else if (isMainFrame) {
                consecutiveReliableMainFrames = 0
                // 主包解析失败不能沿用前一个可用位置的 freshness 身份。
                parseResult.copy(
                    isConnected = false,
                    // 链路确实收到了主包，静默语义为 false；但包不可解析，
                    // hasMainFrame=false 保证不会被计时消费。
                    isStale = false,
                    connectionGeneration = generation,
                    hasMainFrame = false,
                    consecutiveReliableMainFrames = 0,
                )
            } else {
                // GPS Time 包不是 Main 帧：不递增 sequence，不刷新接收时刻，不清 stale。
                val current = _dataFlow.value
                parseResult.copy(
                    isConnected = if (parseResult.errorMessage != null) {
                        false
                    } else {
                        current.isConnected
                    },
                    connectionGeneration = generation,
                    mainFrameSequence = current.mainFrameSequence,
                    mainFrameReceivedAtElapsedRealtimeMs =
                        current.mainFrameReceivedAtElapsedRealtimeMs,
                    hasMainFrame = current.hasMainFrame,
                    isStale = current.isStale,
                    mainFrameSilenceTimeoutMs = current.mainFrameSilenceTimeoutMs,
                    consecutiveReliableMainFrames = current.consecutiveReliableMainFrames,
                )
            }
        }
    }

    /** 开始一个新 GATT 代次，立即废弃所有上一代位置与 parser 时间基准。 */
    internal fun beginConnectionGeneration(): Long {
        invalidateConnectionGeneration(isStale = true)
        return activeConnectionGeneration
    }

    private fun invalidateConnectionGeneration(isStale: Boolean) {
        activeConnectionGeneration++
        mainFrameSequence = 0L
        mainFrameCadenceTracker.reset()
        consecutiveReliableMainFrames = 0
        parser.reset()
        _dataFlow.value = GpsData.Empty.copy(
            connectionGeneration = activeConnectionGeneration,
            isStale = isStale,
        )
    }
}
