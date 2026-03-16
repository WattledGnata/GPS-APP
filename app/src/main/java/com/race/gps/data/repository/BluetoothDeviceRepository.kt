package com.race.gps.data.repository

import com.race.gps.data.local.dao.BluetoothDeviceDao
import com.race.gps.data.local.mapper.toEntity
import com.race.gps.data.local.mapper.toModel
import com.race.gps.data.model.BluetoothDeviceModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BluetoothDeviceRepository(
    private val bluetoothDeviceDao: BluetoothDeviceDao
) {
    // Flow自动从Room获取
    val devicesFlow: Flow<List<BluetoothDeviceModel>> =
        bluetoothDeviceDao.getAllDevices()
            .map { list -> list.map { it.toModel() } }

    suspend fun saveDevices(devices: List<BluetoothDeviceModel>) {
        devices.forEach { device ->
            bluetoothDeviceDao.insertDevice(device.toEntity())
        }
    }

    suspend fun getSavedDevices(): List<BluetoothDeviceModel> {
        return bluetoothDeviceDao.getAllDevicesSync().map { it.toModel() }
    }

    suspend fun addDevice(device: BluetoothDeviceModel) {
        bluetoothDeviceDao.insertDevice(device.toEntity())
    }

    suspend fun removeDevice(address: String) {
        bluetoothDeviceDao.deleteDevice(address)
    }
}
