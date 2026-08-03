package com.blazepush

import android.bluetooth.BluetoothAdapter

/** Converts platform adapter state into a stable, exportable diagnostic label. */
internal fun bluetoothAdapterStateLabel(state: Int?): String = when (state) {
    null -> "UNAVAILABLE"
    BluetoothAdapter.STATE_OFF -> "OFF"
    BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
    BluetoothAdapter.STATE_ON -> "ON"
    BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
    else -> "UNKNOWN($state)"
}

/** Permission or service failures are diagnostic degradation and must never block app startup. */
internal fun initialBluetoothAdapterLabel(stateReader: () -> Int?): String =
    runCatching { bluetoothAdapterStateLabel(stateReader()) }.getOrElse { "UNKNOWN" }
