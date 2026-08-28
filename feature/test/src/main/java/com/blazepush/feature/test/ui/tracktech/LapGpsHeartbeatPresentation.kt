package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness

internal enum class LapGpsHeartbeatState {
    LIVE,
    ACQUIRING_FIX,
    STABILIZING,
    WAITING_MAIN,
    STALE,
    DISCONNECTED,
}

internal data class LapGpsHeartbeatPresentation(
    val state: LapGpsHeartbeatState,
    val speedKmh: Double?,
    val frequencyHz: Double?,
    val satelliteCount: Int?,
    val ageMs: Long?,
    val isMainFresh: Boolean,
)

/**
 * Projects the driver HUD from the same filtered speed and monotonic Main-frame evidence used by
 * lap timing. The caller supplies elapsed realtime so age keeps advancing after the final frame.
 */
internal object LapGpsHeartbeatPresentationMapper {
    fun present(
        connectionState: ConnectionState,
        readiness: LapGpsReadiness,
        gpsData: GpsData,
        filteredSpeedKmh: Double,
        nowElapsedRealtimeMs: Long,
    ): LapGpsHeartbeatPresentation {
        val hasMainTimestamp = gpsData.hasMainFrame &&
            gpsData.mainFrameReceivedAtElapsedRealtimeMs > 0L
        val ageMs = if (hasMainTimestamp) {
            (nowElapsedRealtimeMs - gpsData.mainFrameReceivedAtElapsedRealtimeMs).coerceAtLeast(0L)
        } else {
            null
        }
        val freshnessDeadlineMs = gpsData.mainFrameSilenceTimeoutMs
            .takeIf { it > 0L }
            ?.coerceAtMost(GPS_MAIN_SILENCE_MAX_TIMEOUT_MS)
            ?: GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
        val isMainFresh = connectionState == ConnectionState.CONNECTED &&
            hasMainTimestamp &&
            !gpsData.isStale &&
            ageMs != null &&
            ageMs < freshnessDeadlineMs

        val state = when {
            connectionState != ConnectionState.CONNECTED -> LapGpsHeartbeatState.DISCONNECTED
            !hasMainTimestamp -> LapGpsHeartbeatState.WAITING_MAIN
            !isMainFresh -> LapGpsHeartbeatState.STALE
            readiness == LapGpsReadiness.ACQUIRING_FIX -> LapGpsHeartbeatState.ACQUIRING_FIX
            readiness == LapGpsReadiness.STABILIZING -> LapGpsHeartbeatState.STABILIZING
            readiness == LapGpsReadiness.ARMED -> LapGpsHeartbeatState.LIVE
            else -> LapGpsHeartbeatState.WAITING_MAIN
        }
        val canTrustSpeed = state == LapGpsHeartbeatState.LIVE

        return LapGpsHeartbeatPresentation(
            state = state,
            speedKmh = filteredSpeedKmh.coerceAtLeast(0.0).takeIf { canTrustSpeed },
            frequencyHz = gpsData.frequency.coerceAtLeast(0.0).takeIf { isMainFresh },
            satelliteCount = gpsData.satelliteCount.coerceAtLeast(0).takeIf { isMainFresh },
            ageMs = ageMs,
            isMainFresh = isMainFresh,
        )
    }
}
