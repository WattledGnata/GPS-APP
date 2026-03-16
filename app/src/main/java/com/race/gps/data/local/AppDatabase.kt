package com.race.gps.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    companion object {
        // v1 → v2：重构test_records表，添加新字段，移除旧的acceleration_data_points表
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 删除旧的acceleration_data_points表
                db.execSQL("DROP TABLE IF EXISTS acceleration_data_points")

                // 重建test_records表（添加新字段）
                db.execSQL("ALTER TABLE test_records ADD COLUMN testTemplateId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE test_records ADD COLUMN totalTime REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE test_records ADD COLUMN totalDistance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE test_records ADD COLUMN avgAcceleration REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE test_records ADD COLUMN maxAcceleration REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE test_records ADD COLUMN dataFilePath TEXT NOT NULL DEFAULT ''")

                // 创建speed_segments表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS speed_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        testRecordId TEXT NOT NULL,
                        startSpeed INTEGER NOT NULL,
                        endSpeed INTEGER NOT NULL,
                        time REAL NOT NULL,
                        distance REAL NOT NULL,
                        FOREIGN KEY (testRecordId) REFERENCES test_records(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_speed_segments_testRecordId ON speed_segments(testRecordId)")
            }
        }
    }
}
