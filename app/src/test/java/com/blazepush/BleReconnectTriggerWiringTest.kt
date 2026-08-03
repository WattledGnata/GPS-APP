package com.blazepush

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BleReconnectTriggerWiringTest {
    @Test
    fun `app foreground and Bluetooth STATE_ON request immediate reconnect`() {
        val source = File("src/main/java/com/blazepush/BlazePushApplication.kt").readText()
        assertTrue(source.contains("onActivityStarted(activity: Activity)"))
        assertTrue(source.contains("requestImmediateBleReconnect(\"app foreground\")"))
        assertTrue(source.contains("BluetoothAdapter.ACTION_STATE_CHANGED"))
        assertTrue(source.contains("BluetoothAdapter.STATE_ON"))
        assertTrue(source.contains("requestImmediateBleReconnect(\"bluetooth enabled\")"))
    }
}
