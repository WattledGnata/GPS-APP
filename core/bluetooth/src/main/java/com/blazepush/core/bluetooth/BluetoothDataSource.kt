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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 蓝牙数据源 - 唯一的GPS数据发射点
 * 替代原有的BluetoothManager，使用普通Kotlin类而非Android Service
 */
class BluetoothDataSource(
    private val context: Context,
    private val parser: RaceChronoParser
) {
    companion object {
        private const val TAG = "BluetoothDataSource"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionCollectJob: Job? = null

    // 唯一的数据输出口
    private val _dataFlow = MutableStateFlow(GpsData.Empty)
    val dataFlow: StateFlow<GpsData> = _dataFlow.asStateFlow()

    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var bleConnection: BleConnection? = null

    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
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
                        stateFlow.collect { state ->
                            Log.d(TAG, "BleConnection 状态变化: $state")
                            _connectionState.value = state
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
            }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionCollectJob?.cancel()
            connectionCollectJob = null
            bleConnection?.disconnect()
            bleConnection = null
            _connectionState.value = ConnectionState.DISCONNECTED
            _dataFlow.value = _dataFlow.value.copy(isConnected = false)
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
            _dataFlow.value = if (parseResult.errorMessage != null) {
                // 失败分支 MUST 显式设 isConnected=false：parser copy 保留前帧 isConnected，
                // 若前帧是 true，不显式翻转会违反"最近一次 parse 成功"契约
                parseResult.copy(isConnected = false)
            } else {
                parseResult.copy(isConnected = true, errorMessage = null)
            }
        }
    }
}

