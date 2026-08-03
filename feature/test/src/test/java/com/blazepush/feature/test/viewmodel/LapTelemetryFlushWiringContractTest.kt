package com.blazepush.feature.test.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LapTelemetryFlushWiringContractTest {
    @Test
    fun `accepted start finish persistence schedules delayed telemetry flush`() {
        val source = locateSource().readText()
        val crossingPersistence = source.substringAfter("toWrite.filter(::shouldPersistCrossing)")
            .substringBefore("private fun createLapSession")

        assertTrue(crossingPersistence.contains("crossing.accepted"))
        assertTrue(crossingPersistence.contains("TimingGateType.StartFinish"))
        assertTrue(crossingPersistence.contains("lapTelemetryFlushScheduler.schedule(lapSessionId)"))
    }

    private fun locateSource(): File {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        return generateSequence(start) { it.parentFile }
            .map { File(it, SOURCE_PATH) }
            .firstOrNull(File::exists)
            ?: error("TestSessionViewModel source not found")
    }

    private companion object {
        const val SOURCE_PATH =
            "feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt"
    }
}
