package com.blazepush

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEvidenceWiringTest {
    @Test
    fun applicationInitializesAdapterAndDoesNotUseActivityEventsAsAppState() {
        val source = java.io.File("src/main/java/com/blazepush/BlazePushApplication.kt").readText()

        assertTrue(source.contains("initializeBluetoothAdapterEvidence()"))
        assertTrue(source.contains("getSystemService(BluetoothManager::class.java)?.adapter?.state"))
        assertTrue(source.contains("appForegroundState.onActivityStarted()"))
        assertTrue(source.contains("appForegroundState.onActivityStopped()"))
        assertFalse(source.contains("updateAppLifecycle(\"ACTIVITY_"))
    }

    @Test
    fun diagnosticKoinReadsHaveFailSafeBranches() {
        val source = java.io.File("src/main/java/com/blazepush/BlazePushApplication.kt").readText()
        assertTrue(source.contains("runCatching { GlobalContext.get() }"))
        assertTrue(source.contains("runCatching { koin.get<GpsDataRepository>() }"))
        assertTrue(source.contains("runCatching { koin.get<CameraRecordingEngine>() }"))
    }
}
