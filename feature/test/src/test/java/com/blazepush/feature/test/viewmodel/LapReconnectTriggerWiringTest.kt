package com.blazepush.feature.test.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapReconnectTriggerWiringTest {
    @Test
    fun `entering lap or debug capture session requests immediate reconnect`() {
        val source = File(
            "src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt",
        ).readText()
        assertTrue(source.contains("requestImmediateReconnect(\"lap session entered\")"))
        assertTrue(source.contains("requestImmediateReconnect(\"lap session running\")"))
    }
}
