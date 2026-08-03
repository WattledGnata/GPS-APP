package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.usecase.hasReliableFixEvidence
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness

/** Pure, objective projection of the hardware path that gates lap timing. */
object LapGpsReadinessDeriver {
    fun derive(connectionState: ConnectionState, gpsData: GpsData): LapGpsReadiness = when {
        connectionState != ConnectionState.CONNECTED -> LapGpsReadiness.WAITING_DEVICE

        !gpsData.hasMainFrame || gpsData.isStale -> LapGpsReadiness.WAITING_MAIN
        !gpsData.hasReliableFixEvidence() -> LapGpsReadiness.ACQUIRING_FIX
        !gpsData.isRecoveryStable ||
            gpsData.consecutiveReliableMainFrames < gpsData.requiredReliableMainFrames ->
            LapGpsReadiness.STABILIZING

        else -> LapGpsReadiness.ARMED
    }
}
