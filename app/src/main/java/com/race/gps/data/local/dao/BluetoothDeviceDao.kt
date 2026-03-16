package com.race.gps.data.local.dao

import androidx.room.*
import com.race.gps.data.local.entity.BluetoothDeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BluetoothDeviceDao {
    @Query("SELECT * FROM bluetooth_devices")
    fun getAllDevices(): Flow<List<BluetoothDeviceEntity>>

    @Query("SELECT * FROM bluetooth_devices")
    suspend fun getAllDevicesSync(): List<BluetoothDeviceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: BluetoothDeviceEntity)

    @Query("DELETE FROM bluetooth_devices WHERE address = :address")
    suspend fun deleteDevice(address: String)
}
