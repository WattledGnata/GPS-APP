package com.blazepush

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityTelemetryFlushContractTest {
    @Test
    fun `background lifecycle requests telemetry flush on IO dispatcher`() {
        val source = File("src/main/java/com/blazepush/MainActivity.kt").readText()
        val onStop = source.substringAfter("override fun onStop()")
            .substringBefore("override fun onCreate")

        assertTrue(onStop.contains("CoroutineScope(Dispatchers.IO).launch"))
        assertTrue(onStop.contains("get<TelemetryRepository>().flush()"))
    }
}
