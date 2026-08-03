package com.blazepush.core.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite v9 fixture. It intentionally contains legacy session/crossing/video/pending rows. */
@RunWith(AndroidJUnit4::class)
class LapEvidenceMigration9To10SqliteTest {
    @Test
    fun migrateV9ToV10_preservesRowsAndSupportsEvidenceCrudAndCascade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lap-evidence-v9-fixture.db"
        context.deleteDatabase(name)
        val db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("CREATE TABLE telemetry_sessions (sessionId TEXT NOT NULL PRIMARY KEY, sessionType TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER NOT NULL, binaryFilePath TEXT NOT NULL, lapCount INTEGER NOT NULL DEFAULT 0, bestLapMs INTEGER, topSpeedKmh REAL, trackId TEXT, trackNameSnapshot TEXT, videoFilePath TEXT, videoStartedAtWallClock INTEGER)")
            db.execSQL("CREATE TABLE crossing_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, lapIndex INTEGER NOT NULL, crossingTimestampMs INTEGER NOT NULL, speedKmh REAL NOT NULL, gateId TEXT NOT NULL, gateType TEXT NOT NULL, accepted INTEGER NOT NULL, reason TEXT NOT NULL, directionScore REAL, crossingWallClockTimestampMs INTEGER, FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_crossing_events_sessionId ON crossing_events(sessionId)")
            db.execSQL("CREATE TABLE pending_lap_uploads (clientLapId TEXT NOT NULL PRIMARY KEY, trackId TEXT NOT NULL, driver TEXT NOT NULL, lapNo INTEGER NOT NULL, lapTimeMs INTEGER NOT NULL, sectorsMsCsv TEXT, lappedAtRfc3339 TEXT, createdAtMs INTEGER NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE video_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, segmentIndex INTEGER NOT NULL, filePath TEXT NOT NULL, startWallClock INTEGER NOT NULL, endWallClock INTEGER, durationMs INTEGER, startLapIndex INTEGER, endLapIndex INTEGER, playable INTEGER, FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_video_segments_sessionId ON video_segments(sessionId)")
            db.execSQL("INSERT INTO telemetry_sessions(sessionId,sessionType,startTs,endTs,binaryFilePath,lapCount,bestLapMs) VALUES('s1','LAP_SESSION',10,20,'/old.bin',1,1000)")
            db.execSQL("INSERT INTO crossing_events(sessionId,lapIndex,crossingTimestampMs,speedKmh,gateId,gateType,accepted,reason,directionScore,crossingWallClockTimestampMs) VALUES('s1',1,10,80.0,'SF','StartFinish',1,'Accepted',1.0,10010)")
            db.execSQL("INSERT INTO video_segments(sessionId,segmentIndex,filePath,startWallClock,playable) VALUES('s1',0,'/old.mp4',10,1)")
            db.execSQL("INSERT INTO pending_lap_uploads(clientLapId,trackId,driver,lapNo,lapTimeMs,createdAtMs) VALUES('p1','t1','driver',1,1000,30)")

            AppDatabase.migration9To10Sql.forEach(db::execSQL)
            db.version = 10

            assertEquals(1, db.count("telemetry_sessions"))
            assertEquals(1, db.count("crossing_events"))
            assertEquals(1, db.count("video_segments"))
            assertEquals(1, db.count("pending_lap_uploads"))
            db.rawQuery("SELECT binaryFilePath,lapCount,bestLapMs FROM telemetry_sessions WHERE sessionId='s1'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("/old.bin", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(1000L, it.getLong(2))
            }
            db.rawQuery("SELECT quality,qualityFlagsCsv,evidenceVersion FROM pending_lap_uploads WHERE clientLapId='p1'", null).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0) && it.isNull(1) && it.isNull(2))
            }
            db.execSQL("INSERT INTO lap_evidence VALUES('s1',1,1,10,20,'SF','SF','[]','','AutomaticEvidence')")
            assertEquals(1, db.count("lap_evidence"))
            db.rawQuery("PRAGMA index_list(lap_evidence)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_lap_evidence_sessionId") found = true
                assertTrue(found)
            }
            db.execSQL("DELETE FROM telemetry_sessions WHERE sessionId='s1'")
            assertEquals(0, db.count("lap_evidence"))
            assertEquals(0, db.count("crossing_events"))
            assertEquals(0, db.count("video_segments"))
            assertEquals(1, db.count("pending_lap_uploads"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun android.database.sqlite.SQLiteDatabase.count(table: String): Int =
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
}
