package com.blazepush.feature.test.diagnostic

import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.BleHandshakeStage
import com.blazepush.core.domain.model.BleHandshakeState
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsChannelSubscriptionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.TimingHandshakeState
import com.blazepush.feature.test.recording.RecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM simulations only. Hardware acceptance remains explicitly outside this suite. */
class DiagnosticRecoveryMatrixTest {
    @Test
    fun coldStart_twoToThreeMinutePolicy_usesVirtualClockWithoutWaiting() {
        val recorder = DiagnosticEvidenceRecorder(processStartedAtElapsedMs = 10_000L)

        assertTrue(recorder.snapshot(129_999L).contains("coldStart=WAITING"))
        assertTrue(recorder.snapshot(130_000L).contains("coldStart=EXTENDED_WAIT"))
        assertTrue(recorder.snapshot(190_000L).contains("coldStart=HARDWARE_ACCEPTANCE_REQUIRED"))

        recorder.updateGps(main(generation = 1, sequence = 1, receivedAt = 190_001L))
        assertTrue(recorder.snapshot(190_001L).contains("coldStart=MAIN_OBSERVED"))
    }

    @Test
    fun gpsPowerCycle_bleCanRemainConnectedWhileMainBecomesStaleThenRecovers() {
        val recorder = DiagnosticEvidenceRecorder(0L)
        recorder.updateConnection(ConnectionState.CONNECTED)
        recorder.updateGps(main(generation = 4, sequence = 20, receivedAt = 1_000L))
        recorder.updateGps(main(generation = 4, sequence = 20, receivedAt = 1_000L).copy(isStale = true))
        val stale = recorder.snapshot(2_500L)
        assertTrue(stale.contains("ble=CONNECTED"))
        assertTrue(stale.contains("main(has=true,stale=true,rxAgeMs=1500"))

        recorder.updateGps(main(generation = 4, sequence = 21, receivedAt = 2_600L))
        val recovered = recorder.snapshot(2_600L)
        assertTrue(recovered.contains("seq=21"))
        assertTrue(recovered.contains("stale=false"))
    }

    @Test
    fun outOfRangeReturn_andPhoneBluetoothCycle_preserveGenerationEvidence() {
        val recorder = DiagnosticEvidenceRecorder(0L)
        recorder.updateBluetoothAdapter("OFF")
        recorder.updateConnection(ConnectionState.DISCONNECTED)
        assertTrue(recorder.snapshot(1_000L).contains("adapter=OFF ble=DISCONNECTED"))

        recorder.updateBluetoothAdapter("ON")
        recorder.updateConnection(ConnectionState.CONNECTING)
        recorder.updateGps(main(generation = 8, sequence = 1, receivedAt = 2_000L))
        recorder.updateConnection(ConnectionState.CONNECTED)
        val recovered = recorder.snapshot(2_000L)
        assertTrue(recovered.contains("adapter=ON ble=CONNECTED gen=8 seq=1"))
    }

    @Test
    fun cameraStateRemainsVisibleAlongsideMainGap() {
        val recorder = DiagnosticEvidenceRecorder(0L)
        recorder.updateCamera(RecordingState.Starting("not-exported"))
        recorder.updateCamera(RecordingState.Recording(1_000L, "not-exported"))
        recorder.updateGps(main(generation = 2, sequence = 9, receivedAt = 1_000L).copy(isStale = true))

        val evidence = recorder.snapshot(2_100L)
        assertTrue(evidence.contains("stale=true"))
        assertTrue(evidence.contains("camera=RECORDING"))
        assertFalse(evidence.contains("not-exported"))
    }

    @Test
    fun forceKillBoundary_startsNewProcessEvidenceWithoutInventingRecovery() {
        val beforeKill = DiagnosticEvidenceRecorder(0L)
        beforeKill.updateAppLifecycle(AppProcessState.BACKGROUND)
        beforeKill.updateGps(main(generation = 5, sequence = 99, receivedAt = 4_000L))

        val afterRestart = DiagnosticEvidenceRecorder(10_000L)
        val evidence = afterRestart.snapshot(10_000L)
        assertTrue(evidence.contains("app=PROCESS_CREATED"))
        assertTrue(evidence.contains("gen=0 seq=0"))
        assertTrue(evidence.contains("coldStart=WAITING"))
        assertFalse(evidence.contains("gen=5"))
    }

    @Test
    fun handshakeMainTimeRecoveryGate_andAllBatteryStatesAreExportable() {
        val recorder = DiagnosticEvidenceRecorder(0L)
        recorder.updateHandshake(
            BleHandshakeState(
                connectionGeneration = 3,
                stage = BleHandshakeStage.COMPLETE,
                main = GpsChannelSubscriptionState.SUBSCRIBED,
                time = GpsChannelSubscriptionState.SUBSCRIBED,
            ),
        )
        recorder.updateGps(
            main(generation = 3, sequence = 30, receivedAt = 3_000L).copy(
                timingHandshakeState = TimingHandshakeState.SYNCHRONIZED,
                consecutiveReliableMainFrames = 11,
                requiredReliableMainFrames = 11,
                reliableMainStableDurationMs = 1_000L,
                requiredReliableMainStableDurationMs = 1_000L,
                isRecoveryStable = true,
            ),
        )
        val labels = listOf(
            BatteryCapabilityState.Pending to "battery=PENDING",
            BatteryCapabilityState.Available(0) to "battery=AVAILABLE(0)",
            BatteryCapabilityState.Unsupported to "battery=UNSUPPORTED",
            BatteryCapabilityState.Failed to "battery=FAILED",
        )
        labels.forEach { (state, expected) ->
            recorder.updateBattery(state)
            assertTrue(recorder.snapshot(3_000L).contains(expected))
        }
        val evidence = recorder.snapshot(3_000L)
        assertTrue(evidence.contains("handshake(gen=3,stage=COMPLETE,main=SUBSCRIBED,time=SUBSCRIBED)"))
        assertTrue(evidence.contains("timingGate=SYNCHRONIZED"))
        assertTrue(evidence.contains("recovery=11/11,stableMs=1000/1000,gate=true"))
        assertTrue(evidence.contains("sats=9,fix=1,hdop=0.8"))
    }

    @Test
    fun applicationForegroundCounting_isNotOverwrittenByUnrelatedActivityDestroy() {
        val tracker = AppForegroundStateTracker()
        val recorder = DiagnosticEvidenceRecorder(0L)

        tracker.onActivityStarted()?.let(recorder::updateAppLifecycle)
        tracker.onActivityStarted()?.let(recorder::updateAppLifecycle)
        tracker.onActivityStopped()?.let(recorder::updateAppLifecycle)
        assertTrue(recorder.snapshot(1L).contains("app=FOREGROUND"))

        tracker.onActivityStopped()?.let(recorder::updateAppLifecycle)
        assertTrue(recorder.snapshot(2L).contains("app=BACKGROUND"))
        assertTrue(tracker.onActivityStopped() == null)
    }

    private fun main(generation: Long, sequence: Long, receivedAt: Long): GpsData = GpsData.Empty.copy(
        timestamp = 123L,
        satelliteCount = 9,
        hdop = 0.8,
        fixQuality = 1,
        isConnected = true,
        isTimeSynced = true,
        connectionGeneration = generation,
        mainFrameSequence = sequence,
        mainFrameReceivedAtElapsedRealtimeMs = receivedAt,
        hasMainFrame = true,
    )
}
