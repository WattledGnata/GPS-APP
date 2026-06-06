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
        // ble-device-memory round（v8）：链尾已升至 7→8，链尾断言移交 BleDeviceMemoryMigrationTest。
        // 本测试名称保留兼容（沿用 AppDatabaseMigrationSqlTest 既有惯例），断言更新为
        // "6→7 存在且其后继为 7→8"（位置语义不丢）。
        val idx = AppDatabase.migrationChain.indexOfFirst { it.startVersion == 6 && it.endVersion == 7 }
        assertTrue("6→7 必须在链中", idx >= 0)
        val next = AppDatabase.migrationChain.getOrNull(idx + 1)
        assertEquals("6→7 的后继必须是 7→8", 7, next?.startVersion)
        assertEquals("6→7 的后继必须是 7→8", 8, next?.endVersion)
    }
}
