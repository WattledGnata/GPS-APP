// @IgnoreFormatCheck
package com.blazepush.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.blazepush.core.data.local.dao.BluetoothDeviceDao
import com.blazepush.core.data.local.dao.CarModelDao
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.LapEvidenceDao
import com.blazepush.core.data.local.dao.PendingLapUploadDao
import com.blazepush.core.data.local.dao.SpeedSegmentDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.TestRecordDao
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.local.entity.CarModelEntity
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.LapEvidenceEntity
import com.blazepush.core.data.local.entity.PendingLapUploadEntity
import com.blazepush.core.data.local.entity.SpeedSegmentEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.data.local.entity.VideoSegmentEntity

@Database(
    entities = [
        TestRecordEntity::class,
        SpeedSegmentEntity::class,
        CarModelEntity::class,
        BluetoothDeviceEntity::class,
        TelemetrySessionEntity::class,
        CrossingEventEntity::class,
        PendingLapUploadEntity::class,
        VideoSegmentEntity::class,
        LapEvidenceEntity::class,
    ],
    version = 10,
    exportSchema = false
)
/**
 * Room 数据库总入口。
 * 包含测试记录、车型、蓝牙设备、速度分段、telemetry session 与过线事件 6 个 Entity。
 *
 * schema v6（session-video-metadata-persist）：telemetry_sessions 加 videoFilePath TEXT + videoStartedAtWallClock INTEGER，
 * 供 Phase 2 视频帧↔遥测对齐（round 3 camera-recording-and-gps-sync 录制引擎写入）。
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

    /**
     * livetiming 待传圈队列 DAO（schema v7，livetiming-lap-upload round）。
     */
    abstract fun pendingLapUploadDao(): PendingLapUploadDao

    /**
     * 视频段一对多 DAO（schema v9，video-segment-schema round ②a）。
     */
    abstract fun videoSegmentDao(): VideoSegmentDao

    abstract fun lapEvidenceDao(): LapEvidenceDao

    companion object {
        /**
         * MIGRATION_2_3 SQL 字符串列表（v2 → v3）。
         *
         * A56 round（d15a60c）新增 telemetry_sessions + crossing_events 两张表，
         * 当时无显式 Migration（走 fallbackToDestructiveMigration）。
         * restore-strict-migrations-pre-release round 补回严格 migration。
         *
         * v1/v2 为 pre-A56 开发期 schema（包名 com.race.gps.*），无 release 用户，
         * 保留 destructiveMigrationFrom(1, 2) 兜底；本 Migration 覆盖 v2→v3。
         *
         * @author CC
         * @description schema v2→v3 CREATE TABLE SQL（暴露给 JVM 单元测试自检）
         * @date 2026-05-30
         */
        internal val migration2To3Sql: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS telemetry_sessions (
                sessionId TEXT NOT NULL PRIMARY KEY,
                sessionType TEXT NOT NULL,
                startTs INTEGER NOT NULL,
                endTs INTEGER NOT NULL,
                binaryFilePath TEXT NOT NULL,
                lapCount INTEGER NOT NULL DEFAULT 0,
                bestLapMs INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS crossing_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                lapIndex INTEGER NOT NULL,
                crossingTimestampMs INTEGER NOT NULL,
                speedKmh REAL NOT NULL,
                gateId TEXT NOT NULL,
                gateType TEXT NOT NULL,
                accepted INTEGER NOT NULL,
                reason TEXT NOT NULL,
                directionScore REAL,
                FOREIGN KEY (sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_crossing_events_sessionId ON crossing_events(sessionId)",
        )

        /**
         * Room migration v2 → v3：CREATE TABLE telemetry_sessions + crossing_events。
         * A56（d15a60c）引入两张新表，本 Migration 补回严格路径。
         *
         * @author CC
         * @description Room migration from schema v2 to v3
         * @date 2026-05-30
         */
        val migration2To3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration2To3Sql.forEach { db.execSQL(it) }
            }
        }

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

        /**
         * MIGRATION_4_5 SQL：test_records 加 maxDeceleration（smooth-perftest-acceleration-curve，c7e5b06）。
         *
         * 注意：fix-lap-crossing-clock-hygiene round（5b9704f）在 v4 schema 内直接加了
         * crossing_events.crossingWallClockTimestampMs（没 bump version）。
         * 这导致部分 v4 设备已有该列，部分没有。migration4To5 通过 PRAGMA table_info
         * 条件检查幂等处理两种状态，不在此列表中（由 migration4To5 直接实现）。
         *
         * @author CC
         * @description schema v4→v5 ALTER TABLE SQL（暴露给 JVM 单元测试自检）
         * @date 2026-05-30
         */
        internal val migration4To5Sql: List<String> = listOf(
            "ALTER TABLE test_records ADD COLUMN maxDeceleration REAL NOT NULL DEFAULT 0.0",
        )

        /**
         * Room migration v4 → v5：
         * 1. test_records 加 maxDeceleration 列（smooth-perftest-acceleration-curve，c7e5b06）
         * 2. crossing_events 条件加 crossingWallClockTimestampMs 列（fix-lap-crossing-clock-hygiene，5b9704f；
         *    该字段在 v4 内无 version bump 直接加，部分 v4 设备已有，需 PRAGMA 条件幂等处理）
         *
         * @author CC
         * @description Room migration from schema v4 to v5
         * @date 2026-05-30
         */
        val migration4To5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. test_records.maxDeceleration（c7e5b06）
                migration4To5Sql.forEach { db.execSQL(it) }
                // 2. crossing_events.crossingWallClockTimestampMs（5b9704f 在 v4 无 bump 直接加）
                //    通过 PRAGMA table_info 检查是否已有该列，幂等处理两种 v4 状态
                val hasWallClockCol = db.query(
                    "PRAGMA table_info(crossing_events)"
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val nameIndex = cursor.getColumnIndex("name")
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == "crossingWallClockTimestampMs") {
                            return@use true
                        }
                    }
                    false
                }
                if (!hasWallClockCol) {
                    db.execSQL("ALTER TABLE crossing_events ADD COLUMN crossingWallClockTimestampMs INTEGER")
                }
            }
        }

        /**
         * MIGRATION_5_6 SQL 字符串列表（v5 → v6）。
         *
         * session-video-metadata-persist round：telemetry_sessions 加 videoFilePath + videoStartedAtWallClock。
         * 两列均 nullable（无 NOT NULL 约束），对应 TelemetrySessionEntity 的 String?/Long? 字段。
         * 历史 v5 row migration 后新字段值为 NULL = "无视频"（正确降级）。
         *
         * @author CC
         * @description schema v5→v6 ALTER TABLE SQL（暴露给 JVM 单元测试自检）
         * @date 2026-05-30
         */
        internal val migration5To6Sql: List<String> = listOf(
            "ALTER TABLE telemetry_sessions ADD COLUMN videoFilePath TEXT",
            "ALTER TABLE telemetry_sessions ADD COLUMN videoStartedAtWallClock INTEGER",
        )

        /**
         * Room migration v5 → v6：ADD COLUMN 两个 nullable 视频元数据字段。
         * 不重建表，向下兼容历史数据。旧 row migration 后 videoFilePath=NULL / videoStartedAtWallClock=NULL。
         *
         * @author CC
         * @description Room migration from schema v5 to v6
         * @date 2026-05-30
         */
        val migration5To6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration5To6Sql.forEach { db.execSQL(it) }
            }
        }

        /**
         * MIGRATION_6_7 SQL（v6 → v7）。
         *
         * livetiming-lap-upload round：新建 pending_lap_uploads 待传圈队列表（CREATE TABLE，非破坏）。
         * clientLapId 作 PRIMARY KEY = 幂等唯一约束（同圈失败重复入队被挡）。retryCount NOT NULL DEFAULT 0
         * 是合理初值（非哨兵语义，盲点 #6）。历史 v6 库 migration 后多一张空表，无数据依赖。
         *
         * @author CC
         * @description schema v6→v7 CREATE TABLE SQL（暴露给 JVM 单元测试自检）
         * @date 2026-06-03
         */
        internal val migration6To7Sql: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS pending_lap_uploads (
                clientLapId TEXT NOT NULL PRIMARY KEY,
                trackId TEXT NOT NULL,
                driver TEXT NOT NULL,
                lapNo INTEGER NOT NULL,
                lapTimeMs INTEGER NOT NULL,
                sectorsMsCsv TEXT,
                lappedAtRfc3339 TEXT,
                createdAtMs INTEGER NOT NULL,
                retryCount INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        /**
         * Room migration v6 → v7：CREATE TABLE pending_lap_uploads（待传圈队列）。
         *
         * @author CC
         * @description Room migration from schema v6 to v7
         * @date 2026-06-03
         */
        val migration6To7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration6To7Sql.forEach { db.execSQL(it) }
            }
        }

        /**
         * MIGRATION_7_8 SQL（v7 → v8）。
         *
         * ble-device-memory round：bluetooth_devices 加 alias + lastConnectedAtMs 两个 nullable 列
         * （无 NOT NULL / 无 DEFAULT——0 哨兵会被"最近设备"排序误命中，盲点 #6）。
         * 历史 v7 行 migration 后 alias=NULL（无别名）/ lastConnectedAtMs=NULL（无成功连接记录，
         * 冷启动自动连查询 WHERE lastConnectedAtMs IS NOT NULL 天然排除）。
         * 该表在 v7 及之前零写入链路（dead code），实际作用于空表。
         *
         * @author CC
         * @description schema v7→v8 ALTER TABLE SQL（暴露给 JVM 单元测试自检）
         * @date 2026-06-06
         */
        internal val migration7To8Sql: List<String> = listOf(
            "ALTER TABLE bluetooth_devices ADD COLUMN alias TEXT",
            "ALTER TABLE bluetooth_devices ADD COLUMN lastConnectedAtMs INTEGER",
        )

        /**
         * Room migration v7 → v8：ADD COLUMN 两个 nullable 设备记忆字段。
         * 不重建表，向下兼容历史数据。
         *
         * @author CC
         * @description Room migration from schema v7 to v8
         * @date 2026-06-06
         */
        val migration7To8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration7To8Sql.forEach { db.execSQL(it) }
            }
        }

        /**
         * MIGRATION_8_9 SQL（v8 → v9）。
         *
         * video-segment-schema round ②a：CREATE TABLE video_segments（一对多视频段）
         * + sessionId 索引 + 存量单路径数据迁移（videoFilePath 非空的 session 各迁出
         * segmentIndex=0 一行，playable=1——历史段都走过 Finalize OK 或救援入库，按可播处理，
         * 错判由 ②c 首播回写纠正；COALESCE 防御 path 非空但 wallClock 空的脏行，0=epoch
         * 排序稳定且永不命中圈窗口，不让 migration 崩）。
         *
         * ⚠️ CREATE TABLE 列定义/NOT NULL/FK/索引名 MUST 与 Room 注解期望 schema 精确一致
         * （AUTOINCREMENT PK / 索引名 index_video_segments_sessionId / FK ON DELETE CASCADE），
         * 任一不符升级用户开库抛 "Migration didn't properly handle"。真机升级安装是攒批 MUST 第一项。
         *
         * @author CC
         * @description schema v8→v9 CREATE TABLE + 存量迁移 SQL（暴露给 JVM 单元测试自检）
         * @date 2026-06-07
         */
        internal val migration8To9Sql: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS video_segments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "sessionId TEXT NOT NULL, " +
                "segmentIndex INTEGER NOT NULL, " +
                "filePath TEXT NOT NULL, " +
                "startWallClock INTEGER NOT NULL, " +
                "endWallClock INTEGER, " +
                "durationMs INTEGER, " +
                "startLapIndex INTEGER, " +
                "endLapIndex INTEGER, " +
                "playable INTEGER, " +
                "FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS index_video_segments_sessionId ON video_segments(sessionId)",
            "INSERT INTO video_segments (sessionId, segmentIndex, filePath, startWallClock, playable) " +
                "SELECT sessionId, 0, videoFilePath, COALESCE(videoStartedAtWallClock, 0), 1 " +
                "FROM telemetry_sessions WHERE videoFilePath IS NOT NULL",
        )

        /**
         * Room migration v8 → v9：建 video_segments 表 + 存量单视频 session 迁移。
         *
         * @author CC
         * @description Room migration from schema v8 to v9
         * @date 2026-06-07
         */
        val migration8To9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration8To9Sql.forEach { db.execSQL(it) }
            }
        }

        internal val migration9To10Sql: List<String> = listOf(
            """
            CREATE TABLE IF NOT EXISTS lap_evidence (
                sessionId TEXT NOT NULL,
                lapIndex INTEGER NOT NULL,
                evidenceVersion INTEGER NOT NULL,
                startCrossingTimestampMillis INTEGER NOT NULL,
                finishCrossingTimestampMillis INTEGER NOT NULL,
                requiredGateIdsCsv TEXT NOT NULL,
                acceptedGateIdsCsv TEXT NOT NULL,
                gapIntervalsJson TEXT NOT NULL,
                qualityFlagsCsv TEXT NOT NULL,
                reviewProvenance TEXT NOT NULL,
                PRIMARY KEY(sessionId, lapIndex),
                FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_lap_evidence_sessionId ON lap_evidence(sessionId)",
            "ALTER TABLE pending_lap_uploads ADD COLUMN quality TEXT",
            "ALTER TABLE pending_lap_uploads ADD COLUMN qualityFlagsCsv TEXT",
            "ALTER TABLE pending_lap_uploads ADD COLUMN evidenceVersion INTEGER",
        )

        val migration9To10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migration9To10Sql.forEach { db.execSQL(it) }
            }
        }

        /**
         * 完整迁移链（v2→v9），供 AppModule Room builder 和 JVM 单测使用。
         * v1 由 AppModule 的 destructiveMigrationFrom(1) 兜底（pre-A56 开发期 v1 schema，旧包名，无 release 用户）。
         * v2→v9 全程严格覆盖，fallbackFrom 列表不含 2-8。
         *
         * @author CC
         * @description aggregated migration chain v2→v9
         * @date 2026-06-07
         */
        val migrationChain: List<Migration> = listOf(
            migration2To3,
            migration3To4,
            migration4To5,
            migration5To6,
            migration6To7,
            migration7To8,
            migration8To9,
            migration9To10,
        )
    }
}
