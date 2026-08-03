package com.blazepush.feature.test.diagnostic

import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.BleHandshakeState
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.recording.RecordingState

/** Cold-start observation is diagnostic guidance, not proof that hardware recovered. */
enum class ColdStartObservationPhase {
    WAITING,
    EXTENDED_WAIT,
    HARDWARE_ACCEPTANCE_REQUIRED,
    MAIN_OBSERVED,
}

/** Makes the 2-3 minute cold-start observation window deterministic under a virtual clock. */
data class ColdStartObservationPolicy(
    val extendedWaitAfterMs: Long = 120_000L,
    val hardwareAcceptanceAfterMs: Long = 180_000L,
) {
    init {
        require(extendedWaitAfterMs >= 0L)
        require(hardwareAcceptanceAfterMs >= extendedWaitAfterMs)
    }

    fun phase(elapsedMs: Long, mainObserved: Boolean): ColdStartObservationPhase = when {
        mainObserved -> ColdStartObservationPhase.MAIN_OBSERVED
        elapsedMs >= hardwareAcceptanceAfterMs -> ColdStartObservationPhase.HARDWARE_ACCEPTANCE_REQUIRED
        elapsedMs >= extendedWaitAfterMs -> ColdStartObservationPhase.EXTENDED_WAIT
        else -> ColdStartObservationPhase.WAITING
    }
}

/**
 * Aggregates low-risk diagnostic evidence into one exportable log line.
 *
 * It deliberately excludes coordinates, device identifiers and video content. Satellite/fix/HDOP
 * are nested in the Main section because the protocol has no independent satellite channel.
 */
class DiagnosticEvidenceRecorder(
    private val processStartedAtElapsedMs: Long,
    private val coldStartPolicy: ColdStartObservationPolicy = ColdStartObservationPolicy(),
) {
    private var appLifecycle = "PROCESS_CREATED"
    private var bluetoothAdapter = "UNKNOWN"
    private var connectionState = ConnectionState.DISCONNECTED
    private var handshake = BleHandshakeState()
    private var gpsData = GpsData.Empty
    private var battery: BatteryCapabilityState = BatteryCapabilityState.Pending
    private var camera = "IDLE"
    private var hasObservedMain = false

    @Synchronized
    fun updateAppLifecycle(value: String) {
        appLifecycle = value
    }

    @Synchronized
    fun updateBluetoothAdapter(value: String) {
        bluetoothAdapter = value
    }

    @Synchronized
    fun updateConnection(value: ConnectionState) {
        connectionState = value
    }

    @Synchronized
    fun updateHandshake(value: BleHandshakeState) {
        handshake = value
    }

    @Synchronized
    fun updateGps(value: GpsData) {
        gpsData = value
        if (value.hasMainFrame) hasObservedMain = true
    }

    @Synchronized
    fun updateBattery(value: BatteryCapabilityState) {
        battery = value
    }

    @Synchronized
    fun updateCamera(value: RecordingState) {
        camera = when (value) {
            RecordingState.Idle -> "IDLE"
            is RecordingState.Starting -> "STARTING"
            is RecordingState.Recording -> "RECORDING"
            RecordingState.Stopping -> "STOPPING"
            is RecordingState.Error -> "ERROR"
        }
    }

    @Synchronized
    fun snapshot(nowElapsedMs: Long): String {
        val processAgeMs = (nowElapsedMs - processStartedAtElapsedMs).coerceAtLeast(0L)
        val rxAge = if (gpsData.hasMainFrame) {
            (nowElapsedMs - gpsData.mainFrameReceivedAtElapsedRealtimeMs).coerceAtLeast(0L).toString()
        } else {
            "NA"
        }
        val mainMetrics = if (gpsData.hasMainFrame) {
            "sats=${gpsData.satelliteCount},fix=${gpsData.fixQuality},hdop=${gpsData.hdop}"
        } else {
            "sats=NA,fix=NA,hdop=NA"
        }
        val batteryLabel = when (val value = battery) {
            BatteryCapabilityState.Pending -> "PENDING"
            is BatteryCapabilityState.Available -> "AVAILABLE(${value.percent})"
            BatteryCapabilityState.Unsupported -> "UNSUPPORTED"
            BatteryCapabilityState.Failed -> "FAILED"
        }
        val coldStart = coldStartPolicy.phase(processAgeMs, hasObservedMain)
        return "app=$appLifecycle processAgeMs=$processAgeMs coldStart=$coldStart " +
            "adapter=$bluetoothAdapter ble=$connectionState gen=${gpsData.connectionGeneration} " +
            "seq=${gpsData.mainFrameSequence} " +
            "handshake(stage=${handshake.stage},main=${handshake.main},time=${handshake.time}) " +
            "timingGate=${gpsData.timingHandshakeState} " +
            "main(has=${gpsData.hasMainFrame},stale=${gpsData.isStale},rxAgeMs=$rxAge," +
            "deadlineMs=${gpsData.mainFrameSilenceTimeoutMs},recovery=" +
            "${gpsData.consecutiveReliableMainFrames}/${gpsData.requiredReliableMainFrames}," +
            "stableMs=${gpsData.reliableMainStableDurationMs}/" +
            "${gpsData.requiredReliableMainStableDurationMs},gate=${gpsData.isRecoveryStable}," +
            "$mainMetrics) camera=$camera battery=$batteryLabel"
    }
}
