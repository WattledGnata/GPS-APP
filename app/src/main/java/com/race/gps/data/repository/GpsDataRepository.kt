package com.race.gps.data.repository

import com.race.gps.bluetooth.BluetoothDataSource
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.domain.model.GpsData
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS数据仓库 - 直接暴露BluetoothDataSource的数据流
 */
class GpsDataRepository(
    private val bluetoothDataSource: BluetoothDataSource
) {
    val gpsDataFlow: StateFlow<GpsData> = bluetoothDataSource.dataFlow
    val connectionState: StateFlow<ConnectionState> = bluetoothDataSource.connectionState

    fun connect(deviceAddress: String) {
        bluetoothDataSource.connect(deviceAddress)
    }

    fun disconnect() {
        bluetoothDataSource.disconnect()
    }
}
