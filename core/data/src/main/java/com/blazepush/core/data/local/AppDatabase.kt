package com.blazepush.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 5,
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

    companion object {
        /**
         * MIGRATION_3_4 SQL 字符串列表（v3 → v4）。
         *
         * `internal` 暴露给 JVM 单元测试断言：本 round 不引入 room-testing / Robolectric / Context 依赖，
         * MigrationTestHelper 自动化跑 v3→v4 schema 验证作为 follow-up `room-test-infrastructure`；
         * 本 round 用直接断言这个 list 含 3 条 ALTER TABLE 字符串 + 反射读 @Database version=4 的形式兜底。
         *
         * @author CC
         * @description schema v3→v4 ALTER TABLE 字符串列表（暴露给 JVM 单元测试自检）
         * @date 2026-05-01
         */
        internal val migration3To4Sql: List<String> = listOf(
            "ALTER TABLE telemetry_sessions ADD COLUMN topSpeedKmh REAL",
            "ALTER TABLE telemetry_sessions ADD COLUMN trackId TEXT",
            "ALTER TABLE telemetry_sessions ADD COLUMN trackNameSnapshot TEXT",
        )

        /**
         * Room migration v3 → v4：ADD COLUMN 三个 nullable 字段，不重建表，向下兼容历史数据。
         * 历史 row（v3 数据库）migration 后新字段值为 NULL，detail 屏 D5 fallback 链兜底。
         *
         * @author CC
         * @description Room migration from schema v3 to v4
         * @date 2026-05-01
         */
        val migration3To4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration3To4Sql.forEach { db.execSQL(it) }
            }
        }
    }
}