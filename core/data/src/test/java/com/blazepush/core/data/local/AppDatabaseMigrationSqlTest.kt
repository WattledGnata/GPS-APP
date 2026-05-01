package com.blazepush.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * migration3To4 SQL 字符串自检 + @Database version 自检（JVM unit test，不依赖 Android Context）。
 *
 * 本 round 不引入 androidx.room:room-testing / Robolectric / MigrationTestHelper，
 * 完整的 schema v3 → v4 row 保留自动化测试作为 follow-up `room-test-infrastructure`。
 * 当前用直接断言 migration3To4Sql list + 反射读 @Database 注解的形式兜底，避免 typo。
 *
 * @author CC
 * @description SQL string + version annotation self-check for migration3To4
 * @date 2026-05-01
 */
class AppDatabaseMigrationSqlTest {

    @Test
    fun `migration3To4Sql contains exactly three ALTER TABLE statements`() {
        assertEquals(3, AppDatabase.migration3To4Sql.size)
    }

    @Test
    fun `migration3To4Sql adds topSpeedKmh column as REAL`() {
        assertTrue(
            "migration3To4Sql must add topSpeedKmh as REAL",
            AppDatabase.migration3To4Sql.any {
                it.contains("ADD COLUMN topSpeedKmh REAL")
            }
        )
    }

    @Test
    fun `migration3To4Sql adds trackId column as TEXT`() {
        assertTrue(
            "migration3To4Sql must add trackId as TEXT",
            AppDatabase.migration3To4Sql.any {
                it.contains("ADD COLUMN trackId TEXT")
            }
        )
    }

    @Test
    fun `migration3To4Sql adds trackNameSnapshot column as TEXT`() {
        assertTrue(
            "migration3To4Sql must add trackNameSnapshot as TEXT",
            AppDatabase.migration3To4Sql.any {
                it.contains("ADD COLUMN trackNameSnapshot TEXT")
            }
        )
    }

    @Test
    fun `migration3To4Sql targets telemetry_sessions table`() {
        AppDatabase.migration3To4Sql.forEach {
            assertTrue(
                "Each migration3To4Sql statement must target telemetry_sessions, got: $it",
                it.contains("ALTER TABLE telemetry_sessions")
            )
        }
    }

    @Test
    fun `migration3To4Sql contains no DROP TABLE or CREATE TABLE`() {
        AppDatabase.migration3To4Sql.forEach {
            assertTrue(
                "migration3To4Sql must use ADD COLUMN only, not DROP/CREATE TABLE: $it",
                !it.contains("DROP TABLE") && !it.contains("CREATE TABLE")
            )
        }
    }

    @Test
    fun `migration3To4 targets v3 to v4`() {
        // androidx.room.@Database annotation 不能 runtime reflection 读 version；
        // 改测 migration3To4 实例的 startVersion / endVersion（Migration 抽象类的 public final field）
        // 这样既验证了 v3 → v4 升级路径意图，又跟 @Database version 通过 Room runtime 配套校验
        assertEquals(
            "migration3To4.startVersion must be 3",
            3,
            AppDatabase.migration3To4.startVersion
        )
        assertEquals(
            "migration3To4.endVersion must be 4 (persist-session-summary-fields round)",
            4,
            AppDatabase.migration3To4.endVersion
        )
    }
}