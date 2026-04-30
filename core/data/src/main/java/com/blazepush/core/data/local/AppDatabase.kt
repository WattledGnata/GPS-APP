package com.blazepush.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.blazepush.core.data.local.dao.BluetoothDeviceDao
import com.blazepush.core.data.local.dao.CarModelDao
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.local.entity.CarModelEntity
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.TestRecordEntity

@Database(
    entities = [
        TestRecordEntity::class,
        SpeedSegmentEntity::class,
        CarModelEntity::class,
        BluetoothDeviceEntity::class,
        TelemetrySessionEntity::class,
        CrossingEventEntity::class,
    ],
    version = 3,
    exportSchema = false
)
/**
 * Room 数据库总入口。
 * 包含测试记录、车型、蓝牙设备、速度分段、telemetry session 与过线事件 6 个 Entity。
 *
 * @author CC
 * @description Room 数据库聚合入口，封装所有 DAO
 * @date 2026-04-30
 */
abstract class AppDatabase : RoomDatabase() {
    /**
     * 测试记录表 DAO。
     */
    abstract fun testRecordDao(): TestRecordDao

    /**
     * 车型表 DAO。
     */
    abstract fun carModelDao(): CarModelDao

    /**
     * 蓝牙设备记忆表 DAO。
     */
    abstract fun bluetoothDeviceDao(): BluetoothDeviceDao

    /**
     * 加减速测试速度分段 DAO。
     */
    abstract fun speedSegmentDao(): SpeedSegmentDao

    /**
     * Telemetry session metadata DAO（A56 引入）。
     */
    abstract fun telemetrySessionDao(): TelemetrySessionDao

    /**
     * 圈速过线事件 DAO（A56 引入）。
     */
    abstract fun crossingEventDao(): CrossingEventDao
}