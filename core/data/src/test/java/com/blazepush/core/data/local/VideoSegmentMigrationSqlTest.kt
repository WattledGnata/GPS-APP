// @IgnoreFormatCheck
package com.blazepush.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8→v9 migration SQL 自检（video-segment-schema round ②a，对齐 BleDeviceMemoryMigrationTest 模式）。
 *
 * 测试边界透明声明：真 SQL 行为不在 JVM 单测覆盖（工程先例 = SQL 字符串自检 + 真机升级
 * 安装实测；room-testing 离线拉不到 artifact 不引入，见 design Decision 4）。
 * 真机攒批 MUST 第一项：v8 旧包直接装 v9 新包 → 开库不崩 + 表存在 + 存量迁出 segmentIndex=0 行。
 *
 * @author CC
 * @description v8→v9 migration SQL string self-check
 * @date 2026-06-07
 */
class VideoSegmentMigrationSqlTest {

    @Test
    fun migrationChain_containsEightToNine() {
        val m = AppDatabase.migrationChain.firstOrNull { it.startVersion == 8 && it.endVersion == 9 }
        assertTrue("migrationChain MUST 含 8→9（version bump 后严格覆盖）", m != null)
    }

    @Test
    fun migrationChain_lastIsEightToNine() {
        val migration = AppDatabase.migrationChain.single { it.startVersion == 8 }
        assertEquals(8, migration.startVersion)
        assertEquals(9, migration.endVersion)
    }

    @Test
    fun migration8To9Sql_createTableMatchesRoomExpectedSchema() {
        val create = AppDatabase.migration8To9Sql.first { it.startsWith("CREATE TABLE") }
        // Room 期望 schema 关键子句逐一锁定（任一不符升级用户开库崩 "Migration didn't properly handle"）
        assertTrue("表名", create.contains("video_segments"))
        assertTrue("自增 PK", create.contains("id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue("sessionId NOT NULL", create.contains("sessionId TEXT NOT NULL"))
        assertTrue("segmentIndex NOT NULL", create.contains("segmentIndex INTEGER NOT NULL"))
        assertTrue("filePath NOT NULL", create.contains("filePath TEXT NOT NULL"))
        assertTrue("startWallClock NOT NULL", create.contains("startWallClock INTEGER NOT NULL"))
        // nullable 列 MUST NOT 带 NOT NULL（拆出 create 中 5 个 nullable 列定义核对）
        for (col in listOf("endWallClock INTEGER", "durationMs INTEGER", "startLapIndex INTEGER", "endLapIndex INTEGER", "playable INTEGER")) {
            assertTrue("含 nullable 列 $col", create.contains("$col,") || create.contains("$col "))
            assertFalse("$col MUST NOT 带 NOT NULL", create.contains("$col NOT NULL"))
        }
        assertTrue("FK CASCADE", create.contains("FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId)"))
        assertTrue("ON DELETE CASCADE", create.contains("ON DELETE CASCADE"))
    }

    @Test
    fun migration8To9Sql_createsRoomConventionIndex() {
        val idx = AppDatabase.migration8To9Sql.first { it.startsWith("CREATE INDEX") }
        // 索引名 MUST 按 Room 生成规约 index_<table>_<column>，否则 schema 校验失配
        assertTrue(idx.contains("index_video_segments_sessionId ON video_segments(sessionId)"))
    }

    @Test
    fun migration8To9Sql_migratesLegacySingleVideoRows() {
        val insert = AppDatabase.migration8To9Sql.first { it.startsWith("INSERT INTO video_segments") }
        assertTrue("仅迁移有视频的 session", insert.contains("WHERE videoFilePath IS NOT NULL"))
        assertTrue("存量段 segmentIndex=0 + playable=1", insert.contains("SELECT sessionId, 0, videoFilePath"))
        assertTrue("COALESCE 防御脏行而非崩", insert.contains("COALESCE(videoStartedAtWallClock, 0)"))
    }

    @Test
    fun migration8To9Sql_exactlyThreeStatements() {
        assertEquals("恰好 3 条 SQL（建表 + 索引 + 存量迁移），多出说明 scope 漂移", 3, AppDatabase.migration8To9Sql.size)
    }
}
