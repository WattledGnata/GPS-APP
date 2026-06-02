// @IgnoreFormatCheck
package com.blazepush.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** livetiming-lap-upload：v6→v7 待传队列表 migration 自检（沿用 core/data migration 直断惯例）。 */
class PendingLapUploadMigrationTest {

    @Test
    fun migrationChain_containsSixToSeven() {
        val m = AppDatabase.migrationChain.firstOrNull { it.startVersion == 6 && it.endVersion == 7 }
        assertTrue("migrationChain MUST 含 6→7（version bump 后严格覆盖）", m != null)
    }

    @Test
    fun migration6To7Sql_createsPendingTableWithClientLapIdPk() {
        val sql = AppDatabase.migration6To7Sql.joinToString("\n")
        assertTrue("CREATE TABLE", sql.contains("CREATE TABLE", ignoreCase = true))
        assertTrue("表名 pending_lap_uploads", sql.contains("pending_lap_uploads"))
        assertTrue(
            "clientLapId 作 PRIMARY KEY = 幂等唯一约束",
            sql.contains("clientLapId") && sql.contains("PRIMARY KEY", ignoreCase = true),
        )
        assertTrue("retryCount DEFAULT 0（合理初值,非哨兵）", sql.contains("retryCount") && sql.contains("DEFAULT 0"))
    }

    @Test
    fun migrationChain_lastIsSixToSeven() {
        val last = AppDatabase.migrationChain.last()
        assertEquals(6, last.startVersion)
        assertEquals(7, last.endVersion)
    }
}
