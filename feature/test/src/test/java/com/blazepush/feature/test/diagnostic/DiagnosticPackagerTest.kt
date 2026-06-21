// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * spec『诊断数据全量打包』：含轨迹/库/日志、排除 video、缺失文件容错。
 * 用临时目录造 fake filesDir，避开 gradle test working-dir 陷阱（盲点 #10）。
 */
class DiagnosticPackagerTest {

    private fun tmp(): File = createTempDir(prefix = "diagpack")

    @Test
    fun pack_includesTelemetryDbLogs_excludesVideo() {
        val files = tmp(); val db = tmp(); val out = tmp()
        File(files, "telemetry").mkdirs()
        File(files, "telemetry/a.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(files, "telemetry/b.bin").writeBytes(byteArrayOf(4, 5))
        File(files, "video").mkdirs()
        File(files, "video/big.mp4").writeBytes(ByteArray(16))
        File(files, "debug_log.txt").writeText("log-current")
        File(files, "debug_log.txt.1").writeText("log-rotated")
        File(db, "race_chrono_database").writeBytes(byteArrayOf(9))
        File(db, "race_chrono_database-wal").writeBytes(byteArrayOf(8))
        File(db, "race_chrono_database-shm").writeBytes(byteArrayOf(7))

        val zip = DiagnosticPackager.pack(files, db, out, nowMs = 123L)

        assertTrue(zip.exists())
        val entries = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertTrue(entries.contains("telemetry/a.bin"))
        assertTrue(entries.contains("telemetry/b.bin"))
        assertTrue(entries.contains("databases/race_chrono_database"))
        assertTrue(entries.contains("databases/race_chrono_database-wal"))
        assertTrue(entries.contains("databases/race_chrono_database-shm"))
        assertTrue(entries.contains("debug_log.txt"))
        assertTrue(entries.contains("debug_log.txt.1"))
        assertTrue("video MUST NOT 入包", entries.none { it.startsWith("video") })
    }

    @Test
    fun pack_missingRotatedLogAndWalShm_stillSucceeds() {
        val files = tmp(); val db = tmp(); val out = tmp()
        File(files, "telemetry").mkdirs()
        File(files, "telemetry/a.bin").writeBytes(byteArrayOf(1))
        File(files, "debug_log.txt").writeText("only current")
        File(db, "race_chrono_database").writeBytes(byteArrayOf(9))

        val zip = DiagnosticPackager.pack(files, db, out, nowMs = 1L)

        assertTrue(zip.exists())
        val entries = ZipFile(zip).use { z -> z.entries().toList().map { it.name }.toSet() }
        assertTrue(entries.contains("debug_log.txt"))
        assertFalse(entries.contains("debug_log.txt.1"))
        assertTrue(entries.contains("databases/race_chrono_database"))
        assertFalse(entries.contains("databases/race_chrono_database-wal"))
    }

    @Test
    fun pack_noTelemetryDir_doesNotCrash() {
        val files = tmp(); val db = tmp(); val out = tmp()
        File(files, "debug_log.txt").writeText("x")

        val zip = DiagnosticPackager.pack(files, db, out, nowMs = 2L)

        assertTrue(zip.exists())
    }
}
