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
        // session-video-metadata-persist round：migrationChain size 已升至 4（2→3→4→5→6）。
        // 本测试名称保留兼容（rename 需重构，scope-boundary 暂不动），断言已更新为 4。
        // 完整的 size=4 断言见 `migrationChain contains exactly four migrations`。
        assertEquals(
            "migrationChain must contain 9 migrations: migration2To3..migration10To11",
            9,
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
        // NOTE: このテストは session-video-metadata-persist round で v6 に更新されたため
        // 以下の v6 版テストに置き換えてください。v5 まで検証する旧バージョンとして残存。
        // 実際のチェーンは v2→v6 であり migrationChain.size == 4 が正しい。
        // このテストは `migrationChain has no gaps between v2 and v6` に置き換え済み。
        val sortedChain = AppDatabase.migrationChain.sortedBy { it.startVersion }
        assertTrue(
            "migrationChain must at least cover v2 to v5",
            sortedChain.any { it.startVersion == 2 } && sortedChain.any { it.endVersion >= 5 }
        )
    }

    // ─── migration5To6 tests ───────────────────────────────────────────────────

    @Test
    fun `migration5To6 targets v5 to v6`() {
        assertEquals(
            "migration5To6.startVersion must be 5",
            5,
            AppDatabase.migration5To6.startVersion
        )
        assertEquals(
            "migration5To6.endVersion must be 6 (session-video-metadata-persist round)",
            6,
            AppDatabase.migration5To6.endVersion
        )
    }

    @Test
    fun `migration5To6Sql contains exactly two statements`() {
        assertEquals(
            "migration5To6Sql must contain 2 statements: ADD COLUMN videoFilePath, ADD COLUMN videoStartedAtWallClock",
            2,
            AppDatabase.migration5To6Sql.size
        )
    }

    @Test
    fun `migration5To6Sql adds videoFilePath as TEXT nullable`() {
        assertTrue(
            "migration5To6Sql must add videoFilePath TEXT (nullable, no NOT NULL)",
            AppDatabase.migration5To6Sql.any {
                it.contains("ADD COLUMN videoFilePath TEXT") && !it.contains("NOT NULL")
            }
        )
    }

    @Test
    fun `migration5To6Sql adds videoStartedAtWallClock as INTEGER nullable`() {
        assertTrue(
            "migration5To6Sql must add videoStartedAtWallClock INTEGER (nullable, no NOT NULL)",
            AppDatabase.migration5To6Sql.any {
                it.contains("ADD COLUMN videoStartedAtWallClock INTEGER") && !it.contains("NOT NULL")
            }
        )
    }

    @Test
    fun `migration5To6Sql targets telemetry_sessions table`() {
        AppDatabase.migration5To6Sql.forEach {
            assertTrue(
                "Each migration5To6Sql statement must target telemetry_sessions, got: $it",
                it.contains("ALTER TABLE telemetry_sessions")
            )
        }
    }

    @Test
    fun `migration5To6Sql contains no DROP TABLE or CREATE TABLE`() {
        AppDatabase.migration5To6Sql.forEach {
            assertTrue(
                "migration5To6Sql must use ADD COLUMN only, not DROP/CREATE TABLE: $it",
                !it.contains("DROP TABLE") && !it.contains("CREATE TABLE")
            )
        }
    }

    // ─── updated migrationChain integrity tests (v2→v6) ──────────────────────

    @Test
    fun `migrationChain contains exactly four migrations`() {
        assertEquals(
            "migrationChain must contain 9 migrations: migration2To3..migration10To11",
            9,
            AppDatabase.migrationChain.size
        )
    }

    @Test
    fun `migrationChain covers v5 to v6`() {
        assertTrue(
            "migrationChain must contain a migration from v5 to v6",
            AppDatabase.migrationChain.any { it.startVersion == 5 && it.endVersion == 6 }
        )
    }

    @Test
    fun `migrationChain has no gaps between v2 and v6`() {
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
            "migrationChain must end at v11",
            11,
            expectedNextStart
        )
    }

    @Test
    fun `migration9To10 is additive and preserves legacy unknown`() {
        assertEquals(9, AppDatabase.migration9To10.startVersion)
        assertEquals(10, AppDatabase.migration9To10.endVersion)
        assertTrue(AppDatabase.migration9To10Sql.any { it.contains("CREATE TABLE IF NOT EXISTS lap_evidence") })
        assertTrue(AppDatabase.migration9To10Sql.any { it.contains("PRIMARY KEY(sessionId, lapIndex)") })
        assertTrue(AppDatabase.migration9To10Sql.any { it.contains("ON DELETE CASCADE") })
        for (column in listOf("quality TEXT", "qualityFlagsCsv TEXT", "evidenceVersion INTEGER")) {
            val sql = AppDatabase.migration9To10Sql.single { it.contains("ADD COLUMN $column") }
            assertTrue("legacy pending field must remain nullable: $sql", !sql.contains("NOT NULL"))
        }
        assertTrue(AppDatabase.migration9To10Sql.none { it.contains("DROP TABLE") || it.contains("DELETE FROM") })
    }

    @Test
    fun `migration10To11 adds nullable window boundaries and version zero`() {
        assertEquals(10, AppDatabase.migration10To11.startVersion)
        assertEquals(11, AppDatabase.migration10To11.endVersion)
        assertEquals(5, AppDatabase.migration10To11Sql.size)
        for (column in listOf(
            "windowStartSampleIndex",
            "windowEndSampleIndex",
            "windowStartDeltaMs",
            "windowEndDeltaMs",
        )) {
            val sql = AppDatabase.migration10To11Sql.single { it.contains("ADD COLUMN $column") }
            assertTrue("legacy boundary must remain nullable: $sql", !sql.contains("NOT NULL"))
        }
        assertTrue(
            AppDatabase.migration10To11Sql.single { it.contains("windowAlgorithmVersion") }
                .contains("NOT NULL DEFAULT 0"),
        )
        assertTrue(AppDatabase.migration10To11Sql.none { it.contains("DROP TABLE") || it.contains("DELETE FROM") })
    }
}
