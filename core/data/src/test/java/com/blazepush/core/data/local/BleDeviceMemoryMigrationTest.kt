// @IgnoreFormatCheck
package com.blazepush.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ble-device-memory：v7→v8 设备记忆两列 migration 自检（沿用 core/data migration 直断惯例，
 * 参 PendingLapUploadMigrationTest）。
 */
class BleDeviceMemoryMigrationTest {

    @Test
    fun migrationChain_containsSevenToEight() {
        val m = AppDatabase.migrationChain.firstOrNull { it.startVersion == 7 && it.endVersion == 8 }
        assertTrue("migrationChain MUST 含 7→8（version bump 后严格覆盖）", m != null)
    }

    @Test
    fun migrationChain_lastIsSevenToEight() {
        val last = AppDatabase.migrationChain.last()
        assertEquals(7, last.startVersion)
        assertEquals(8, last.endVersion)
    }

    @Test
    fun migration7To8Sql_addsAliasAndLastConnectedColumns() {
        val sqls = AppDatabase.migration7To8Sql
        assertEquals("恰好 2 条 ALTER", 2, sqls.size)
        assertTrue("两条都是对 bluetooth_devices 的 ALTER TABLE", sqls.all { it.contains("ALTER TABLE bluetooth_devices") })
        assertTrue("含 alias TEXT 列", sqls.any { it.contains("ADD COLUMN alias TEXT") })
        assertTrue("含 lastConnectedAtMs INTEGER 列", sqls.any { it.contains("ADD COLUMN lastConnectedAtMs INTEGER") })
    }

    @Test
    fun migration7To8Sql_mustNotContainSentinelDefaults() {
        // 盲点 #6 反例守护：两列 MUST nullable 无哨兵——
        // lastConnectedAtMs NOT NULL DEFAULT 0 会被"最近设备"排序误命中（1970 时间戳）。
        val joined = AppDatabase.migration7To8Sql.joinToString("\n")
        assertFalse("MUST NOT 含 NOT NULL", joined.contains("NOT NULL", ignoreCase = true))
        assertFalse("MUST NOT 含 DEFAULT", joined.contains("DEFAULT", ignoreCase = true))
    }

    // 注：@Database version=8 无法 runtime 反射断言（androidx.room.Database retention 非 RUNTIME，
    // 参 AppDatabaseMigrationSqlTest:83 既有结论）；version 语义由 migrationChain_lastIsSevenToEight
    // 等价覆盖（链尾 endVersion == @Database version 由 Room runtime 配套校验兜底）。
}
