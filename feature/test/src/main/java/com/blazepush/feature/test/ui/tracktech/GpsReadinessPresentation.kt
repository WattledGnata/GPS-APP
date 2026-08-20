package com.blazepush.feature.test.ui.tracktech

import androidx.annotation.StringRes
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness

enum class GpsReadinessTone {
    WAITING,
    CONNECTING,
    READY,
}

data class GpsReadinessPresentation(
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int,
    @StringRes val shortLabelRes: Int,
    val tone: GpsReadinessTone,
)

/** Single user-facing mapping for BLE connection and lap GPS readiness. */
object GpsReadinessPresentationMapper {
    fun present(
        readiness: LapGpsReadiness,
        connectionState: ConnectionState,
        isReconnecting: Boolean = false,
    ): GpsReadinessPresentation = when (connectionState) {
        ConnectionState.DISCONNECTED -> presentation(
            R.string.gps_readiness_waiting_device_title,
            R.string.gps_readiness_waiting_device_detail,
            R.string.gps_readiness_waiting_device_short,
            GpsReadinessTone.WAITING,
        )
        ConnectionState.CONNECTING -> if (isReconnecting) {
            presentation(
                R.string.gps_readiness_reconnecting_title,
                R.string.gps_readiness_reconnecting_detail,
                R.string.gps_readiness_reconnecting_short,
                GpsReadinessTone.CONNECTING,
            )
        } else {
            presentation(
                R.string.gps_readiness_connecting_title,
                R.string.gps_readiness_connecting_detail,
                R.string.gps_readiness_connecting_short,
                GpsReadinessTone.CONNECTING,
            )
        }
        ConnectionState.DISCONNECTING -> presentation(
            R.string.gps_readiness_disconnecting_title,
            R.string.gps_readiness_disconnecting_detail,
            R.string.gps_readiness_disconnecting_short,
            GpsReadinessTone.WAITING,
        )
        ConnectionState.CONNECTED -> when (readiness) {
            LapGpsReadiness.WAITING_DEVICE -> presentation(
                R.string.gps_readiness_waiting_device_title,
                R.string.gps_readiness_waiting_device_detail,
                R.string.gps_readiness_waiting_device_short,
                GpsReadinessTone.WAITING,
            )
            LapGpsReadiness.WAITING_MAIN -> presentation(
                R.string.gps_readiness_waiting_main_title,
                R.string.gps_readiness_waiting_main_detail,
                R.string.gps_readiness_waiting_main_short,
                GpsReadinessTone.CONNECTING,
            )
            LapGpsReadiness.ACQUIRING_FIX -> presentation(
                R.string.gps_readiness_acquiring_fix_title,
                R.string.gps_readiness_acquiring_fix_detail,
                R.string.gps_readiness_acquiring_fix_short,
                GpsReadinessTone.CONNECTING,
            )
            LapGpsReadiness.STABILIZING -> presentation(
                R.string.gps_readiness_stabilizing_title,
                R.string.gps_readiness_stabilizing_detail,
                R.string.gps_readiness_stabilizing_short,
                GpsReadinessTone.CONNECTING,
            )
            LapGpsReadiness.ARMED -> presentation(
                R.string.gps_readiness_armed_title,
                R.string.gps_readiness_armed_detail,
                R.string.gps_readiness_armed_short,
                GpsReadinessTone.READY,
            )
        }
    }

    private fun presentation(
        @StringRes titleRes: Int,
        @StringRes detailRes: Int,
        @StringRes shortLabelRes: Int,
        tone: GpsReadinessTone,
    ) = GpsReadinessPresentation(titleRes, detailRes, shortLabelRes, tone)
}
