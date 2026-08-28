package com.blazepush.core.bluetooth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BleDeviceScannerTimeoutContractTest {

    @Test
    fun `scan duration schedules automatic stop and every stop clears scanning state`() {
        val source = File(
            "src/main/java/com/blazepush/core/bluetooth/BleDeviceScanner.kt",
        ).readText()

        assertTrue(source.contains("delay(SCAN_DURATION_MS)"))
        assertTrue(source.contains("扫描超时，自动停止"))
        assertTrue(source.contains("finally {\n            _isScanning.value = false"))
    }
}
