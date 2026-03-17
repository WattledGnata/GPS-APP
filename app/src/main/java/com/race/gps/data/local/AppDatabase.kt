package com.race.gps.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.race.gps.data.local.dao.BluetoothDeviceDao
import com.race.gps.data.local.dao.CarModelDao
import com.race.gps.data.local.dao.SpeedSegmentDao
import com.race.gps.data.local.dao.TestRecordDao
import com.race.gps.data.local.entity.BluetoothDeviceEntity
import com.race.gps.data.local.entity.CarModelEntity
import com.race.gps.data.local.entity.SpeedSegmentEntity
import com.race.gps.data.local.entity.TestRecordEntity

@Database(
    entities = [
        TestRecordEntity::class,
        SpeedSegmentEntity::class,
        CarModelEntity::class,
        BluetoothDeviceEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun testRecordDao(): TestRecordDao
    abstract fun carModelDao(): CarModelDao
    abstract fun bluetoothDeviceDao(): BluetoothDeviceDao
    abstract fun speedSegmentDao(): SpeedSegmentDao
}
