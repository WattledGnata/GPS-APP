package com.blazepush.core.bluetooth

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.BleHandshakeState
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS数据仓库 - 直接暴露BluetoothDataSource的数据流
 */
class GpsDataRepository(
    private val bluetoothDataSource: BluetoothDataSource
) {
    val gpsDataFlow: StateFlow<GpsData> = bluetoothDataSource.dataFlow
    val connectionState: StateFlow<ConnectionState> = bluetoothDataSource.connectionState
    val batteryCapability: StateFlow<BatteryCapabilityState> = bluetoothDataSource.batteryCapability
    val bleHandshake: StateFlow<BleHandshakeState> = bluetoothDataSource.bleHandshake
    /** Compatibility only. [batteryCapability] is the source of truth. */
    val batteryPercent: StateFlow<Int?> = bluetoothDataSource.batteryPercent

    fun connect(deviceAddress: String) {
        bluetoothDataSource.connect(deviceAddress)
    }

    fun disconnect() {
        bluetoothDataSource.disconnect()
    }
}
