package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.track.TimingGateType
import kotlin.math.max

/**
 * @author CC
 * @description LapLiveScreen 订阅的派生状态：当前圈 timer / last / best / delta / lap# / 异常态。
 * @date 2026-05-01
 */
data class LapLiveState(
    val currentLapTimerMs: Long?,
    val lastLapTimeMs: Long?,
    val bestLapTimeMs: Long?,
    val deltaToBestMs: Long?,
    val deltaIsStale: Boolean,
    val currentLapNumber: Int,
    val abnormalState: AbnormalState?,
)

/**
 * Lap live screen 主屏的异常打断状态枚举（按优先级从高到低派生）。
 * 优先级：BLE_DISCONNECTED > GPS_SIGNAL_LOST > WAITING_FOR_GPS_LOCK > LAP_INVALIDATED > null（正常）。
 *
 * @author CC
 * @description abnormal state enum interrupting lap live dashboard
 * @date 2026-05-01
 */
enum class AbnormalState {
    GPS_SIGNAL_LOST,
    WAITING_FOR_GPS_LOCK,
    BLE_DISCONNECTED,
    LAP_INVALIDATED,
}

/**
 * @author CC
 * @description 纯函数派生 LapLiveState。无副作用、无 IO、无 ViewModel 依赖；输入 LapSession + 时钟 + GPS / BLE 信号即可产出 UI 订阅的 6 字段状态。
 * @date 2026-05-01
 */
object LapLiveStateDeriver {

    private const val GPS_DATA_AGE_THRESHOLD_MS = 1000L
    private const val MIN_SATELLITES_FOR_FIX = 6

    // LAP_INVALIDATED banner 显示时长：触发后 banner 持续显示这么久。
    private const val LAP_INVALIDATED_DISPLAY_WINDOW_MS = 5_000L

    // LAP_INVALIDATED banner 触发去抖：要求最近 N ms 内出现 ≥ MIN_COUNT 个 invalidating event。
    // 设计依据：baseline detector 只在 prev→cur 线段与 gate 线段相交瞬间才产生 invalidating event，
    // 单帧 GPS jitter 撞 gate 最多产生 1 个；真反向冲线持续多帧通过 gate 会产生 ≥ 3 个。
    // 此去抖让单帧 jitter 不触发 banner，但保留真反向冲线的反馈语义。
    // wire-laptime-to-gps-filter round 闭环后 filter 接通，jitter 已从数据流根因消除，
    // 阈值降至 1，单次真 invalidating event 即触发 banner 恢复实时反馈语义。
    private const val LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1_000L
    private const val LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 1

    private val invalidatingReasons = setOf(
        CrossingReason.WrongDirection,
        CrossingReason.UnexpectedGateOrder,
        CrossingReason.TooSlow,
    )

    /**
     * 派生 LapLiveState：从 LapSession + GPS / BLE 状态计算 7 字段（current/last/best/delta/deltaIsStale/lapNumber/abnormal）。
     * 纯函数 + 无副作用，单元测试直接覆盖。
     *
     * round add-realtime-lap-delta 修订：
     * - `currentTimeMs` 重命名语义为 `currentDisplayTimeMs`（由 ViewModel ticker / SystemClock.elapsedRealtime 推动），
     *   保持 baseline 行为，CURRENT tile 在两个 GPS 帧之间也能平滑外推
     * - 新增 `deltaToBestMs` / `deltaIsStale` 入参：由 ViewModel 调用 [projectDelta] 算好后传入；Deriver 不调
     *   [projectDelta]、不读跨帧状态，仅做 LapLiveState 组装。算法责任完全在 ViewModel（参考 design.md Decision 6）
     * - 删除 baseline 错位减法 `currentLapTimerMs - bestLapTimeMs`（量纲不一致，第二圈起一直显示绿色巨大负数）
     *
     * @author CC
     * @description derive lap live state from session + gps + ble signals + viewmodel-computed delta
     * @date 2026-05-02
     */
    fun derive(
        session: LapSession?,
        currentDisplayTimeMs: Long,
        gpsData: GpsData,
        connectionState: ConnectionState,
        dataQuality: DataQuality,
        deltaToBestMs: Long?,
        deltaIsStale: Boolean,
    ): LapLiveState {
        val acceptedStartFinish = session?.crossingEvents
            ?.filter { it.accepted && it.gateType == TimingGateType.StartFinish }
            ?.sortedBy { it.timestampMillis }
            .orEmpty()

        val currentLapTimerMs = acceptedStartFinish.lastOrNull()
            ?.let { currentDisplayTimeMs - it.timestampMillis }

        val lapDurations = if (acceptedStartFinish.size >= 2) {
            acceptedStartFinish.zipWithNext { prev, next ->
                next.timestampMillis - prev.timestampMillis
            }
        } else {
            emptyList()
        }

        val lastLapTimeMs = lapDurations.lastOrNull()
        val bestLapTimeMs = lapDurations.minOrNull()

        val currentLapNumber = max(1, session?.currentLapIndex ?: 0)

        val abnormalState = deriveAbnormalState(
            session = session,
            currentTimeMs = currentDisplayTimeMs,
            gpsData = gpsData,
            connectionState = connectionState,
            dataQuality = dataQuality,
        )

        return LapLiveState(
            currentLapTimerMs = currentLapTimerMs,
            lastLapTimeMs = lastLapTimeMs,
            bestLapTimeMs = bestLapTimeMs,
            deltaToBestMs = deltaToBestMs,
            deltaIsStale = deltaIsStale,
            currentLapNumber = currentLapNumber,
            abnormalState = abnormalState,
        )
    }

    private fun deriveAbnormalState(
        session: LapSession?,
        currentTimeMs: Long,
        gpsData: GpsData,
        connectionState: ConnectionState,
        dataQuality: DataQuality,
    ): AbnormalState? {
        if (connectionState != ConnectionState.CONNECTED) {
            return AbnormalState.BLE_DISCONNECTED
        }
        if (dataQuality.dataAge > GPS_DATA_AGE_THRESHOLD_MS || gpsData.satelliteCount == 0) {
            return AbnormalState.GPS_SIGNAL_LOST
        }
        if (gpsData.satelliteCount < MIN_SATELLITES_FOR_FIX) {
            return AbnormalState.WAITING_FOR_GPS_LOCK
        }
        val invalidatingEvents = session?.crossingEvents
            ?.filter { !it.accepted && it.reason in invalidatingReasons }
            .orEmpty()
        if (invalidatingEvents.isEmpty()) return null

        val latest = invalidatingEvents.maxBy { it.timestampMillis }

        // 显示时长门：banner 触发后只在 5 秒内显示
        if (currentTimeMs - latest.timestampMillis >= LAP_INVALIDATED_DISPLAY_WINDOW_MS) return null

        // 去抖门：以 latest 为锚点，向前 1 秒窗口内 invalidating event 必须 ≥ 3 个
        val windowStart = latest.timestampMillis - LAP_INVALIDATED_DEBOUNCE_WINDOW_MS
        val countInWindow = invalidatingEvents.count {
            it.timestampMillis in windowStart..latest.timestampMillis
        }
        if (countInWindow < LAP_INVALIDATED_DEBOUNCE_MIN_COUNT) return null

        return AbnormalState.LAP_INVALIDATED
    }
}