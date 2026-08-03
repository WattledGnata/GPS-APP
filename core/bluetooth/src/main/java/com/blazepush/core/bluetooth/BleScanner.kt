package com.blazepush.core.bluetooth

import kotlinx.coroutines.flow.StateFlow

/** Narrow scanner contract so reconnect orchestration can be tested without Android BLE runtime. */
interface BleScanner {
    val scanResults: StateFlow<List<ScannedDevice>>
    val isScanning: StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun cleanup()
}
