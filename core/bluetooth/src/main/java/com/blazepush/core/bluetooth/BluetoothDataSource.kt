// @IgnoreFormatCheck
// 理由：本文件含 legacy 格式违规（`_dataFlow` / `_connectionState` MutableStateFlow
//       backing 属性 / class comment / public fun comment / import-order / trailing
//       newline）。rename backing 属性会扩散到下游消费者，超出战役 G R4/R5
//       scope。评审方 2026-04-24 commit 阶段 B 方案批准此 ignore。
package com.blazepush.core.bluetooth
import com.blazepush.core.domain.model.ConnectionState

import android.content.Context
import android.util.Log
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.domain.model.GpsData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
    dispatcher: CoroutineDispatcher = Dispatchers.IO
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

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var bleConnection: BleConnection? = null

    /**
     * 公开连接入口（用户意图=想连）：记录目标地址、复位重连状态后委托 [doConnect]。
     * 切设备时取消针对旧地址的挂起重连（spec R2）。
     */
    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        lastRequestedAddress = deviceAddress
        userInitiatedDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        doConnect(deviceAddress)
    }

    /**
     * 实际连接路径（A27 清理顺序不变）。重连路径直接走此函数,**不**复位
     * reconnectAttempt——否则退避退化为恒 1s 高频 connectGatt（design Decision 4）。
     */
    private fun doConnect(deviceAddress: String) {
        scope.launch {
            try {
                // A27 切设备前清旧连接（严格顺序，原子化）：
                // 1. 先 cancel 旧 collectJob，避免旧 bleConnection 终态 state 在新连接
                //    构造窗口内传导到 _connectionState 与 CONNECTING 竞争
                // 2. 再 disconnect 旧 bleConnection（走 R3 回调释放路径）
                // 3. 最后重置 _connectionState 进入 CONNECTING
                connectionCollectJob?.cancel()
                connectionCollectJob = null
                bleConnection?.disconnect()
                bleConnection = null

                _connectionState.value = ConnectionState.CONNECTING
                Log.d(TAG, "状态设置为 CONNECTING，创建 BleConnection")

                bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
                    handleIncomingData(uuid, rawData)
                }

                // 在单独的协程中监听 BleConnection 的状态变化
                bleConnection?.connectionState?.let { stateFlow ->
                    connectionCollectJob = scope.launch {
                        // 同 job 内子协程收集 dataStale → 写 GpsData.isStale（ble-connection-liveness
                        // spec R3）。父 job（connectionCollectJob）取消时子协程一并取消，无需独立
                        // cancel，不破 A27 cleanup 严格顺序契约（connectionCollectJob 单点取消即清干净）。
                        launch {
                            bleConnection?.dataStale?.collect { stale ->
                                _dataFlow.value = _dataFlow.value.copy(isStale = stale)
                            }
                        }
                        // drop(1):跳过 BleConnection StateFlow 的 replay 初值(新实例恒 DISCONNECTED,
                        // 无信息量)——不跳过会在每次 doConnect 时误调度假重连,1s 后拆掉刚建好的连接
                        stateFlow.drop(1).collect { state ->
                            Log.d(TAG, "BleConnection 状态变化: $state")
                            _connectionState.value = state
                            when (state) {
                                ConnectionState.CONNECTED -> {
                                    // 连上即复位退避(下次意外断开从 1s 重来,spec R1 Scenario 2)
                                    // 并取消任何挂起重连(防御残留假重连拆好链)
                                    reconnectAttempt = 0
                                    reconnectJob?.cancel()
                                    reconnectJob = null
                                }
                                // 意外断开(远端断/连接超时)→ 调度重连(spec R1)
                                ConnectionState.DISCONNECTED -> maybeScheduleReconnect()
                                else -> { /* CONNECTING/DISCONNECTING 无重连动作 */ }
                            }
                        }
                    }
                }

                bleConnection?.connect()
                Log.d(TAG, "BleConnection.connect() 调用完成")

            } catch (e: Exception) {
                Log.e(TAG, "连接异常", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                _dataFlow.value = _dataFlow.value.copy(
                    isConnected = false,
                    errorMessage = e.message
                )
                // 异常分支不经 collect 传导(直接赋值),需显式调度重连(design Risks 竞态条款)
                maybeScheduleReconnect()
            }
        }
    }

    /**
     * 意外断开的退避重连调度（ble-auto-reconnect spec R1/R2）。
     * guard:用户主动断开 / 无连接历史 / 已有挂起重连 → 不调度。
     */
    private fun maybeScheduleReconnect() {
        val address = lastRequestedAddress ?: return
        if (userInitiatedDisconnect) return
        if (reconnectJob?.isActive == true) return
        val delayMs = minOf(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS shl reconnectAttempt)
        Log.d(TAG, "意外断开,${delayMs}ms 后第 ${reconnectAttempt + 1} 次自动重连 $address")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (userInitiatedDisconnect) return@launch // delay 期间用户断开,作废
            reconnectAttempt = minOf(reconnectAttempt + 1, 30) // 防 shl 溢出,30 次后恒封顶
            doConnect(address)
        }
    }

    fun disconnect() {
        // 用户意图=想断:取消挂起重连,后续 DISCONNECTED 不再调度(spec R2)
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch {
            connectionCollectJob?.cancel()
            connectionCollectJob = null
            bleConnection?.disconnect()
            bleConnection = null
            _connectionState.value = ConnectionState.DISCONNECTED
            // isStale 一并清：断开后由 connectionState 主导 UI，陈旧软状态无意义残留会污染重连首帧前的判定
            _dataFlow.value = _dataFlow.value.copy(isConnected = false, isStale = false)
        }
    }

    /**
     * 战役 G R4（A25 isConnected 语义收敛）：BLE 数据回调的数据处理入口。
     *
     * 从 `connect()` 里的 onDataReceived lambda 提取为 `internal fun`，便于单测
     * 不经过真实 BLE 链路直接喂数据。可见性 `internal` 保证同 module 测试可调，
     * 对外仍是实现细节。
     *
     * 契约：`isConnected == true` 的充要条件是 "GATT 连上 + **最近一次** parse 成功"。
     *
     * 语义：
     * - 已知 UUID（主包 / 时间包）→ parser 产出 GpsData（可能含 errorMessage）
     *   - `errorMessage == null`：parse 成功 → 置 isConnected=true + 清 errorMessage
     *   - `errorMessage != null`：parse 失败（短包 / catch）→ **显式** 置
     *     isConnected=false + 保留 parser 的 errorMessage。
     *     parser 的失败路径是 `currentData.copy(errorMessage = ...)`，`copy` 保留了
     *     上一帧的 `isConnected` 字段 —— 若不显式翻转，上一帧成功（isConnected=true）
     *     → 当前帧短包会导致 `isConnected = true + errorMessage != null` 的状态自相
     *     矛盾，破坏"最近一次 parse 成功"契约。
     * - 未知 UUID → parseResult == null → 整个写入块跳过，_dataFlow.value 完全不
     *   触碰，isConnected 原值保留（硬区分 v1 的 `else -> _dataFlow.value.copy(isConnected = true)`）
     */
    internal fun handleIncomingData(uuid: java.util.UUID, rawData: ByteArray) {
        val parseResult: com.blazepush.core.domain.model.GpsData? = when (uuid.toString()) {
            "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
            "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
            else -> null
        }
        if (parseResult != null) {
            // isStale = false 两分支都显式置：收到帧 = 非陈旧（无论 parse 成败，数据在流）。
            // parser 的 currentData.copy(...) 会保留前帧 isStale，不显式翻转会让"静默置 true"
            // 在下一帧错误残留（同 isConnected 契约陷阱，ble-connection-liveness spec R3）。
            _dataFlow.value = if (parseResult.errorMessage != null) {
                // 失败分支 MUST 显式设 isConnected=false：parser copy 保留前帧 isConnected，
                // 若前帧是 true，不显式翻转会违反"最近一次 parse 成功"契约
                parseResult.copy(isConnected = false, isStale = false)
            } else {
                parseResult.copy(isConnected = true, errorMessage = null, isStale = false)
            }
        }
    }
}

