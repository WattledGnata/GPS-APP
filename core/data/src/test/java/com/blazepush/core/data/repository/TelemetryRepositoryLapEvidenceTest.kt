package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.LapEvidenceDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.LapEvidenceEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapEvidenceFlag
import com.blazepush.core.domain.model.LapGapInterval
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class TelemetryRepositoryLapEvidenceTest {
    @Test
    fun `non clean evidence round trips without altering raw fields`() = runTest {
        val dao = FakeLapEvidenceDao()
        val repository = TelemetryRepository(
            context = mock(Context::class.java),
            sessionDao = mock(TelemetrySessionDao::class.java),
            crossingDao = mock(CrossingEventDao::class.java),
            videoSegmentDao = mock(VideoSegmentDao::class.java),
            lapEvidenceDao = dao,
        )
        val evidence = LapEvidence(
            startCrossingTimestampMillis = 100,
            finishCrossingTimestampMillis = 900,
            requiredGateIds = setOf("SF", "S1"),
            acceptedGateIds = setOf("SF", "S1"),
            gaps = listOf(LapGapInterval(300, 500, setOf("S1"))),
            flags = setOf(LapEvidenceFlag.LowAccuracy),
        )

        repository.writeLapEvidence("session", 1, evidence)

        assertEquals(evidence, repository.getLapEvidence("session", 1))
        assertEquals(evidence, repository.getLapEvidenceForSession("session")[1])
    }

    @Test
    fun `session export snapshot carries persisted evidence`() = runTest {
        val evidenceDao = FakeLapEvidenceDao()
        val sessionDao = mock(TelemetrySessionDao::class.java)
        val crossingDao = mock(CrossingEventDao::class.java)
        `when`(sessionDao.queryBySessionId("session")).thenReturn(
            TelemetrySessionEntity(
                sessionId = "session",
                sessionType = TelemetrySessionType.LAP_SESSION.name,
                startTs = 1_800_000_000_000L,
                endTs = 1_800_000_001_000L,
                binaryFilePath = "/missing/session.bin",
            ),
        )
        `when`(crossingDao.queryBySessionId("session")).thenReturn(emptyList())
        val repository = TelemetryRepository(
            context = mock(Context::class.java),
            sessionDao = sessionDao,
            crossingDao = crossingDao,
            videoSegmentDao = mock(VideoSegmentDao::class.java),
            lapEvidenceDao = evidenceDao,
        )
        val evidence = LapEvidence(
            startCrossingTimestampMillis = 100,
            finishCrossingTimestampMillis = 900,
            requiredGateIds = setOf("SF"),
            acceptedGateIds = setOf("SF"),
        )
        repository.writeLapEvidence("session", 1, evidence)

        val snapshot = requireNotNull(repository.getLapSessionExportSnapshot("session"))

        assertEquals(evidence, snapshot.evidenceByLap[1])
    }

    private class FakeLapEvidenceDao : LapEvidenceDao {
        private val rows = linkedMapOf<Pair<String, Int>, LapEvidenceEntity>()
        override suspend fun upsert(entity: LapEvidenceEntity) {
            rows[entity.sessionId to entity.lapIndex] = entity
        }
        override suspend fun find(sessionId: String, lapIndex: Int): LapEvidenceEntity? =
            rows[sessionId to lapIndex]
        override suspend fun findBySession(sessionId: String): List<LapEvidenceEntity> =
            rows.values.filter { it.sessionId == sessionId }.sortedBy { it.lapIndex }
    }
}
