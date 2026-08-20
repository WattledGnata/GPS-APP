package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.feature.test.R
import com.blazepush.feature.test.model.laptiming.LapGpsReadiness
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsReadinessPresentationMapperTest {
    @Test
    fun `connected maps every readiness phase to its own copy`() {
        val expected = mapOf(
            LapGpsReadiness.WAITING_DEVICE to R.string.gps_readiness_waiting_device_short,
            LapGpsReadiness.WAITING_MAIN to R.string.gps_readiness_waiting_main_short,
            LapGpsReadiness.ACQUIRING_FIX to R.string.gps_readiness_acquiring_fix_short,
            LapGpsReadiness.STABILIZING to R.string.gps_readiness_stabilizing_short,
            LapGpsReadiness.ARMED to R.string.gps_readiness_armed_short,
        )

        expected.forEach { (readiness, shortLabelRes) ->
            assertEquals(
                shortLabelRes,
                GpsReadinessPresentationMapper.present(readiness, ConnectionState.CONNECTED).shortLabelRes,
            )
        }
    }

    @Test
    fun `armed is the only ready tone`() {
        LapGpsReadiness.entries.forEach { readiness ->
            val expected = if (readiness == LapGpsReadiness.ARMED) {
                GpsReadinessTone.READY
            } else if (readiness == LapGpsReadiness.WAITING_DEVICE) {
                GpsReadinessTone.WAITING
            } else {
                GpsReadinessTone.CONNECTING
            }
            assertEquals(
                expected,
                GpsReadinessPresentationMapper.present(readiness, ConnectionState.CONNECTED).tone,
            )
        }
    }

    @Test
    fun `connection states override stale readiness`() {
        assertEquals(
            R.string.gps_readiness_waiting_device_short,
            GpsReadinessPresentationMapper.present(
                LapGpsReadiness.ARMED,
                ConnectionState.DISCONNECTED,
            ).shortLabelRes,
        )
        assertEquals(
            R.string.gps_readiness_connecting_short,
            GpsReadinessPresentationMapper.present(
                LapGpsReadiness.ARMED,
                ConnectionState.CONNECTING,
            ).shortLabelRes,
        )
        assertEquals(
            R.string.gps_readiness_disconnecting_short,
            GpsReadinessPresentationMapper.present(
                LapGpsReadiness.ARMED,
                ConnectionState.DISCONNECTING,
            ).shortLabelRes,
        )
    }

    @Test
    fun `automatic reconnect has explicit user copy`() {
        val presentation = GpsReadinessPresentationMapper.present(
            LapGpsReadiness.WAITING_DEVICE,
            ConnectionState.CONNECTING,
            isReconnecting = true,
        )

        assertEquals(R.string.gps_readiness_reconnecting_title, presentation.titleRes)
        assertEquals(R.string.gps_readiness_reconnecting_detail, presentation.detailRes)
        assertEquals(R.string.gps_readiness_reconnecting_short, presentation.shortLabelRes)
    }
}
