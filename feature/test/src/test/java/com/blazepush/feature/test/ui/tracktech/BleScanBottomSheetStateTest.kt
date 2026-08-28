package com.blazepush.feature.test.ui.tracktech

import com.blazepush.core.domain.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class BleScanBottomSheetStateTest {

    @Test
    fun `connected state never falls back to fake scanning`() {
        assertEquals(
            ScanSheetState.Connected,
            deriveScanSheetState(
                connectionState = ConnectionState.CONNECTED,
                isScanning = false,
                hasScanResults = false,
                attemptedConnect = false,
                hasScannedOnce = false,
            ),
        )
    }

    @Test
    fun `completed empty scan enters empty state`() {
        assertEquals(
            ScanSheetState.Empty,
            deriveScanSheetState(
                connectionState = ConnectionState.DISCONNECTED,
                isScanning = false,
                hasScanResults = false,
                attemptedConnect = false,
                hasScannedOnce = true,
            ),
        )
    }

    @Test
    fun `only active scan reports scanning`() {
        assertEquals(
            ScanSheetState.Scanning,
            deriveScanSheetState(
                connectionState = ConnectionState.DISCONNECTED,
                isScanning = true,
                hasScanResults = false,
                attemptedConnect = false,
                hasScannedOnce = true,
            ),
        )
    }
}
