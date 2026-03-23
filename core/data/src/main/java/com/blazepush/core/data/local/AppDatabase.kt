package com.blazepush.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.blazepush.core.data.local.dao.BluetoothDeviceDao
import com.blazepush.core.data.local.dao.CarModelDao
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.local.entity.CarModelEntity
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TestRecordEntity

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
