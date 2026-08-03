package com.blazepush

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothAdapterDiagnosticsTest {
    @Test
    fun labelsAllPlatformStatesAndUnavailableValues() {
        assertEquals("ON", bluetoothAdapterStateLabel(BluetoothAdapter.STATE_ON))
        assertEquals("OFF", bluetoothAdapterStateLabel(BluetoothAdapter.STATE_OFF))
        assertEquals("TURNING_ON", bluetoothAdapterStateLabel(BluetoothAdapter.STATE_TURNING_ON))
        assertEquals("TURNING_OFF", bluetoothAdapterStateLabel(BluetoothAdapter.STATE_TURNING_OFF))
        assertEquals("UNAVAILABLE", bluetoothAdapterStateLabel(null))
        assertEquals("UNKNOWN(99)", bluetoothAdapterStateLabel(99))
    }

    @Test
    fun initialReadFailsSafeForMissingServiceOrPermissionFailure() {
        assertEquals("UNAVAILABLE", initialBluetoothAdapterLabel { null })
        assertEquals("UNKNOWN", initialBluetoothAdapterLabel { throw SecurityException("denied") })
    }
}
