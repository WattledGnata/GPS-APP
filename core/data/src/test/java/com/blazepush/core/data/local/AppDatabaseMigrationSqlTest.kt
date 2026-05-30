// @IgnoreFormatCheck
package com.blazepush.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration SQL 字符串自检 + migrationChain 完整性断言（JVM unit test，不依赖 Android Context）。
 *
 * 本 round 不引入 androidx.room:room-testing / Robolectric / MigrationTestHelper，
 * 完整的 schema row 保留自动化测试作为 follow-up `room-test-infrastructure`。
 * 当前用直接断言 migration SQL list + migrationChain 覆盖范围的形式兜底，避免 typo。
 *
 * restore-strict-migrations-pre-release round（2026-05-30）新增：
 * - migration2To3 SQL 断言（CREATE TABLE × 2）
 * - migration4To5 SQL 断言（ADD COLUMN maxDeceleration）
 * - migrationChain 完整性断言（size=3，2→3→4→5 连续）
 *
 * @author CC
 * @description SQL string + migrationChain integrity self-check
 * @date 2026-05-30
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

    // ─── migration2To3 tests ───────────────────────────────────────────────────

    @Test
    fun `migration2To3 targets v2 to v3`() {
        assertEquals(
            "migration2To3.startVersion must be 2",
            2,
            AppDatabase.migration2To3.startVersion
        )
        assertEquals(
            "migration2To3.endVersion must be 3 (A56 unify-gps-telemetry-persistence round)",
            3,
            AppDatabase.migration2To3.endVersion
        )
    }

    @Test
    fun `migration2To3Sql contains exactly three statements`() {
        assertEquals(
            "migration2To3Sql must contain 3 statements: CREATE TABLE telemetry_sessions, CREATE TABLE crossing_events, CREATE INDEX",
            3,
            AppDatabase.migration2To3Sql.size
        )
    }

    @Test
    fun `migration2To3Sql creates telemetry_sessions table`() {
        assertTrue(
            "migration2To3Sql must create telemetry_sessions table",
            AppDatabase.migration2To3Sql.any {
                it.contains("CREATE TABLE") && it.contains("telemetry_sessions")
            }
        )
    }

    @Test
    fun `migration2To3Sql creates crossing_events table`() {
        assertTrue(
            "migration2To3Sql must create crossing_events table",
            AppDatabase.migration2To3Sql.any {
                it.contains("CREATE TABLE") && it.contains("crossing_events")
            }
        )
    }

    @Test
    fun `migration2To3Sql creates index on crossing_events sessionId`() {
        assertTrue(
            "migration2To3Sql must create index on crossing_events.sessionId",
            AppDatabase.migration2To3Sql.any {
                it.contains("CREATE INDEX") && it.contains("crossing_events")
            }
        )
    }

    @Test
    fun `migration2To3Sql crossing_events includes foreign key to telemetry_sessions`() {
        val crossingEventsStatement = AppDatabase.migration2To3Sql.find {
            it.contains("CREATE TABLE") && it.contains("crossing_events")
        }
        assertTrue(
            "crossing_events CREATE TABLE must reference telemetry_sessions via FK",
            crossingEventsStatement != null &&
                crossingEventsStatement.contains("FOREIGN KEY") &&
                crossingEventsStatement.contains("telemetry_sessions")
        )
    }

    // ─── migration4To5 tests ───────────────────────────────────────────────────

    @Test
    fun `migration4To5 targets v4 to v5`() {
        assertEquals(
            "migration4To5.startVersion must be 4",
            4,
            AppDatabase.migration4To5.startVersion
        )
        assertEquals(
            "migration4To5.endVersion must be 5 (smooth-perftest-acceleration-curve round)",
            5,
            AppDatabase.migration4To5.endVersion
        )
    }

    @Test
    fun `migration4To5Sql adds maxDeceleration column to test_records`() {
        assertTrue(
            "migration4To5Sql must add maxDeceleration as REAL NOT NULL DEFAULT 0.0",
            AppDatabase.migration4To5Sql.any {
                it.contains("ADD COLUMN maxDeceleration") && it.contains("test_records")
            }
        )
    }

    @Test
    fun `migration4To5Sql contains exactly one statement`() {
        assertEquals(
            "migration4To5Sql must contain 1 statement (maxDeceleration); " +
                "crossingWallClockTimestampMs is handled via PRAGMA in migration4To5.migrate() directly",
            1,
            AppDatabase.migration4To5Sql.size
        )
    }

    // ─── migrationChain integrity tests ───────────────────────────────────────

    @Test
    fun `migrationChain contains exactly three migrations`() {
        assertEquals(
            "migrationChain must contain 3 migrations: migration2To3, migration3To4, migration4To5",
            3,
            AppDatabase.migrationChain.size
        )
    }

    @Test
    fun `migrationChain covers v2 to v3`() {
        assertTrue(
            "migrationChain must contain a migration from v2 to v3",
            AppDatabase.migrationChain.any { it.startVersion == 2 && it.endVersion == 3 }
        )
    }

    @Test
    fun `migrationChain covers v3 to v4`() {
        assertTrue(
            "migrationChain must contain a migration from v3 to v4",
            AppDatabase.migrationChain.any { it.startVersion == 3 && it.endVersion == 4 }
        )
    }

    @Test
    fun `migrationChain covers v4 to v5`() {
        assertTrue(
            "migrationChain must contain a migration from v4 to v5",
            AppDatabase.migrationChain.any { it.startVersion == 4 && it.endVersion == 5 }
        )
    }

    @Test
    fun `migrationChain has no gaps between v2 and v5`() {
        val sortedChain = AppDatabase.migrationChain.sortedBy { it.startVersion }
        var expectedNextStart = 2
        for (migration in sortedChain) {
            assertEquals(
                "Migration chain has gap: expected migration starting at v$expectedNextStart, got v${migration.startVersion}",
                expectedNextStart,
                migration.startVersion
            )
            expectedNextStart = migration.endVersion
        }
        assertEquals(
            "migrationChain must end at v5 (current @Database version)",
            5,
            expectedNextStart
        )
    }
}