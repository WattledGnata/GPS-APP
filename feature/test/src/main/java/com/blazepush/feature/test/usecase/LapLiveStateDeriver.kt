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
    // GPS 数据接入 GpsDataFilter 是独立 follow-up round，与本去抖正交（filter 接通后阈值可降至 1）。
    private const val LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1_000L
    private const val LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3

    private val invalidatingReasons = setOf(
        CrossingReason.WrongDirection,
        CrossingReason.UnexpectedGateOrder,
        CrossingReason.TooSlow,
    )

    /**
     * 派生 LapLiveState：从 LapSession + GPS / BLE 状态计算 6 字段（current/last/best/delta/lapNumber/abnormal）。
     * 纯函数 + 无副作用，单元测试直接覆盖。
     *
     * @author CC
     * @description derive lap live state from session + gps + ble signals
     * @date 2026-05-01
     */
    fun derive(
        session: LapSession?,
        currentTimeMs: Long,
        gpsData: GpsData,
        connectionState: ConnectionState,
        dataQuality: DataQuality,
    ): LapLiveState {
        val acceptedStartFinish = session?.crossingEvents
            ?.filter { it.accepted && it.gateType == TimingGateType.StartFinish }
            ?.sortedBy { it.timestampMillis }
            .orEmpty()

        val currentLapTimerMs = acceptedStartFinish.lastOrNull()
            ?.let { currentTimeMs - it.timestampMillis }

        val lapDurations = if (acceptedStartFinish.size >= 2) {
            acceptedStartFinish.zipWithNext { prev, next ->
                next.timestampMillis - prev.timestampMillis
            }
        } else {
            emptyList()
        }

        val lastLapTimeMs = lapDurations.lastOrNull()
        val bestLapTimeMs = lapDurations.minOrNull()
        val deltaToBestMs = if (currentLapTimerMs != null && bestLapTimeMs != null) {
            currentLapTimerMs - bestLapTimeMs
        } else {
            null
        }

        val currentLapNumber = max(1, session?.currentLapIndex ?: 0)

        val abnormalState = deriveAbnormalState(
            session = session,
            currentTimeMs = currentTimeMs,
            gpsData = gpsData,
            connectionState = connectionState,
            dataQuality = dataQuality,
        )

        return LapLiveState(
            currentLapTimerMs = currentLapTimerMs,
            lastLapTimeMs = lastLapTimeMs,
            bestLapTimeMs = bestLapTimeMs,
            deltaToBestMs = deltaToBestMs,
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
            ?.filter { !it.accepted && it.reason in INVALIDATING_REASONS }
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