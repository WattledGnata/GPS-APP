package com.blazepush.core.data.repository

import android.content.Context
import com.blazepush.core.data.local.dao.CrossingEventDao
import com.blazepush.core.data.local.dao.TelemetrySessionDao
import com.blazepush.core.data.local.dao.VideoSegmentDao
import com.blazepush.core.data.local.entity.CrossingEventEntity
import com.blazepush.core.data.local.entity.TelemetrySessionEntity
import com.blazepush.core.data.local.entity.VideoSegmentEntity
import com.blazepush.core.data.local.binary.GpsBinaryFormat
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.io.RandomAccessFile

class IncompleteLapSessionRecoveryTest {

    @Test
    fun `field incident - eight start finish crossings recover seven laps and preserve five videos`() = runTest {
        val sessions = FakeSessionDao()
        val crossings = FakeCrossingDao()
        val videos = FakeVideoDao()
        val repo = repository(sessions, crossings, videos)
        val startTs = 1_000_000L
        val sessionId = "field-session"
        sessions.insert(incompleteSession(sessionId, startTs, "/missing/field.bin"))

        val lapDurations = listOf(172_890L, 156_398L, 155_193L, 153_885L, 147_801L, 145_508L, 159_689L)
        var crossingTs = startTs + 10_000L
        crossings.add(startFinish(sessionId, crossingTs, lapIndex = 1))
        lapDurations.forEachIndexed { index, duration ->
            crossingTs += duration
            crossings.add(startFinish(sessionId, crossingTs, lapIndex = index + 1))
            // Sector 和 rejected StartFinish 不得污染配对结果。
            crossings.add(sector(sessionId, crossingTs - 1_000L, lapIndex = index + 1))
            crossings.add(startFinish(sessionId, crossingTs - 500L, lapIndex = index + 1, accepted = false))
        }

        repeat(5) { index ->
            videos.insert(
                VideoSegmentEntity(
                    sessionId = sessionId,
                    segmentIndex = index,
                    filePath = "/data/user/0/com.blazepush/files/video/$index.mp4",
                    startWallClock = startTs + 20_000L + index * 200_000L,
                    endWallClock = if (index == 4) startTs + 1_200_000L else startTs + 200_000L + index * 200_000L,
                    durationMs = 180_000L,
                    playable = true,
                )
            )
        }
        val beforeVideos = videos.queryBySessionId(sessionId)

        val report = repo.recoverIncompleteLapSessions(
            processStartedAtMs = startTs + 2_000_000L,
            recoveryNowMs = startTs + 2_500_000L,
        )

        val recovered = requireNotNull(sessions.queryBySessionId(sessionId))
        assertEquals(1, report.recovered.size)
        assertEquals(7, recovered.lapCount)
        assertEquals(145_508L, recovered.bestLapMs)
        assertEquals(startTs + 1_200_000L, recovered.endTs)
        assertNull(recovered.topSpeedKmh)
        assertEquals(beforeVideos, videos.queryBySessionId(sessionId))
        assertEquals(5, report.recovered.single().videoSegmentCount)
    }

    @Test
    fun `recovery is idempotent and excludes closed or current process sessions`() = runTest {
        val sessions = FakeSessionDao()
        val repo = repository(sessions, FakeCrossingDao(), FakeVideoDao())
        sessions.insert(incompleteSession("old", 1_000L, "/missing/old.bin"))
        sessions.insert(incompleteSession("new", 5_000L, "/missing/new.bin"))
        sessions.insert(incompleteSession("closed", 500L, "/missing/closed.bin").copy(endTs = 900L, lapCount = 3))

        val first = repo.recoverIncompleteLapSessions(processStartedAtMs = 5_000L, recoveryNowMs = 10_000L)
        val oldAfterFirst = requireNotNull(sessions.queryBySessionId("old"))
        val second = repo.recoverIncompleteLapSessions(processStartedAtMs = 5_000L, recoveryNowMs = 20_000L)

        assertEquals(listOf("old"), first.recovered.map { it.sessionId })
        assertEquals(1_001L, oldAfterFirst.endTs)
        assertEquals(0, second.candidates)
        assertEquals(5_000L, requireNotNull(sessions.queryBySessionId("new")).endTs)
        assertEquals(900L, requireNotNull(sessions.queryBySessionId("closed")).endTs)
    }

    @Test
    fun `future video evidence is ignored and empty session closes one millisecond after start`() = runTest {
        val sessions = FakeSessionDao()
        val videos = FakeVideoDao()
        val repo = repository(sessions, FakeCrossingDao(), videos)
        sessions.insert(incompleteSession("future", 1_000L, "/missing/future.bin"))
        videos.insert(
            VideoSegmentEntity(
                sessionId = "future",
                segmentIndex = 0,
                filePath = "/data/user/0/com.blazepush/files/video/future.mp4",
                startWallClock = 20_000L,
                endWallClock = 30_000L,
                durationMs = 10_000L,
                playable = true,
            )
        )

        repo.recoverIncompleteLapSessions(processStartedAtMs = 5_000L, recoveryNowMs = 10_000L)

        val recovered = requireNotNull(sessions.queryBySessionId("future"))
        assertEquals(1_001L, recovered.endTs)
        assertEquals(0, recovered.lapCount)
        assertNull(recovered.bestLapMs)
    }

    @Test
    fun `one failed summary write does not block another candidate`() = runTest {
        val sessions = FakeSessionDao(failOnUpdate = "broken")
        val repo = repository(sessions, FakeCrossingDao(), FakeVideoDao())
        sessions.insert(incompleteSession("broken", 1_000L, "/missing/broken.bin"))
        sessions.insert(incompleteSession("healthy", 2_000L, "/missing/healthy.bin"))

        val report = repo.recoverIncompleteLapSessions(processStartedAtMs = 5_000L, recoveryNowMs = 10_000L)

        assertEquals(listOf("healthy"), report.recovered.map { it.sessionId })
        assertEquals(listOf("broken"), report.failed.map { it.sessionId })
        assertEquals(2_001L, requireNotNull(sessions.queryBySessionId("healthy")).endTs)
        assertEquals(1_000L, requireNotNull(sessions.queryBySessionId("broken")).endTs)
    }

    @Test
    fun `coordinator serializes concurrent triggers and keeps process start cutoff`() = runTest {
        val sessions = FakeSessionDao(yieldDuringIncompleteQuery = true)
        val repo = repository(sessions, FakeCrossingDao(), FakeVideoDao())
        val coordinator = IncompleteLapSessionRecoveryCoordinator(
            telemetryRepository = repo,
            processStartedAtMs = 5_000L,
        )
        sessions.insert(incompleteSession("old", 1_000L, "/missing/old.bin"))
        sessions.insert(incompleteSession("current-process", 5_000L, "/missing/current.bin"))

        val reports = listOf(
            async { coordinator.recover(recoveryNowMs = 10_000L) },
            async { coordinator.recover(recoveryNowMs = 11_000L) },
        ).awaitAll()

        assertEquals(1, reports.sumOf { it.recovered.size })
        assertEquals(1, sessions.updateSummaryCalls)
        assertEquals(1, sessions.maxConcurrentQueries)
        assertEquals(5_000L, requireNotNull(sessions.queryBySessionId("current-process")).endTs)
    }

    @Test
    fun `cold start repairs durable sample prefix before closing interrupted session`() = runTest {
        val file = File.createTempFile("interrupted_lap", ".bin")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(GpsBinaryFormat.encodeHeader(TelemetrySessionType.LAP_SESSION, 0, 1_000L, 1_000L))
                repeat(2) { index ->
                    raf.write(
                        GpsBinaryFormat.encodeSample(
                            TelemetrySample(index * 40L, 30.0, 104.0, 90.0, null)
                        )
                    )
                }
                raf.write(ByteArray(7) { 1 })
                raf.channel.force(true)
            }
            val sessions = FakeSessionDao()
            sessions.insert(incompleteSession("crashed", 1_000L, file.absolutePath))

            val report = repository(sessions, FakeCrossingDao(), FakeVideoDao())
                .recoverIncompleteLapSessions(processStartedAtMs = 5_000L, recoveryNowMs = 10_000L)

            assertEquals(listOf("crashed"), report.recovered.map { it.sessionId })
            assertEquals(2, PerformanceTestTelemetryReader.read(file.absolutePath).size)
            assertEquals(
                (GpsBinaryFormat.HEADER_SIZE + 2 * GpsBinaryFormat.SAMPLE_SIZE).toLong(),
                file.length(),
            )
            assertEquals(1_040L, requireNotNull(sessions.queryBySessionId("crashed")).endTs)
        } finally {
            file.delete()
        }
    }

    private fun repository(
        sessions: FakeSessionDao,
        crossings: FakeCrossingDao,
        videos: FakeVideoDao,
    ) = TelemetryRepository(mock(Context::class.java), sessions, crossings, videos)

    private fun incompleteSession(sessionId: String, startTs: Long, binaryPath: String) =
        TelemetrySessionEntity(
            sessionId = sessionId,
            sessionType = "LAP_SESSION",
            startTs = startTs,
            endTs = startTs,
            binaryFilePath = binaryPath,
            trackId = "preset-tfic-lpcc",
            trackNameSnapshot = "成都天府国际赛道",
        )

    private fun startFinish(sessionId: String, wallClock: Long, lapIndex: Int, accepted: Boolean = true) =
        CrossingEventEntity(
            sessionId = sessionId,
            lapIndex = lapIndex,
            crossingTimestampMs = wallClock,
            crossingWallClockTimestampMs = wallClock,
            speedKmh = 100.0,
            gateId = "start-finish",
            gateType = "StartFinish",
            accepted = accepted,
            reason = if (accepted) "Accepted" else "WrongDirection",
            directionScore = null,
        )

    private fun sector(sessionId: String, wallClock: Long, lapIndex: Int) =
        CrossingEventEntity(
            sessionId = sessionId,
            lapIndex = lapIndex,
            crossingTimestampMs = wallClock,
            crossingWallClockTimestampMs = wallClock,
            speedKmh = 100.0,
            gateId = "s1",
            gateType = "Sector",
            accepted = true,
            reason = "Accepted",
            directionScore = null,
        )

    private class FakeSessionDao(
        private val failOnUpdate: String? = null,
        private val yieldDuringIncompleteQuery: Boolean = false,
    ) : TelemetrySessionDao {
        private val sessions = mutableListOf<TelemetrySessionEntity>()
        var updateSummaryCalls = 0
            private set
        var maxConcurrentQueries = 0
            private set
        private var activeQueries = 0

        override suspend fun insert(entity: TelemetrySessionEntity) {
            sessions.removeIf { it.sessionId == entity.sessionId }
            sessions += entity
        }

        override suspend fun updateEndTs(sessionId: String, endTs: Long) {
            replace(sessionId) { it.copy(endTs = endTs) }
        }

        override suspend fun updateSummary(
            sessionId: String,
            endTs: Long,
            lapCount: Int,
            bestLapMs: Long?,
            topSpeedKmh: Double?,
        ) {
            if (sessionId == failOnUpdate) error("injected update failure")
            updateSummaryCalls += 1
            replace(sessionId) {
                it.copy(
                    endTs = endTs,
                    lapCount = lapCount,
                    bestLapMs = bestLapMs,
                    topSpeedKmh = topSpeedKmh,
                )
            }
        }

        override suspend fun queryBySessionId(sessionId: String) = sessions.find { it.sessionId == sessionId }
        override suspend fun queryAll() = sessions.toList()
        override suspend fun queryIncompleteLapSessions(createdBeforeMs: Long): List<TelemetrySessionEntity> {
            activeQueries += 1
            maxConcurrentQueries = maxOf(maxConcurrentQueries, activeQueries)
            return try {
                if (yieldDuringIncompleteQuery) yield()
                sessions.filter { entity ->
                    entity.sessionType == "LAP_SESSION" &&
                        entity.endTs <= entity.startTs &&
                        entity.startTs < createdBeforeMs
                }
            } finally {
                activeQueries -= 1
            }
        }
        override fun getBestLapForTrack(trackId: String) = flowOf<TelemetrySessionEntity?>(null)
        override fun getSessionCountForTrack(trackId: String) = flowOf(0)
        override fun getTotalLapCountForTrack(trackId: String) = flowOf(0)
        override fun getRecentSessionsForTrack(trackId: String, limit: Int) = flowOf<List<TelemetrySessionEntity>>(emptyList())
        override suspend fun updateVideoMetadata(sessionId: String, videoFilePath: String, videoStartedAtWallClock: Long) = Unit
        override suspend fun clearVideo(sessionId: String) = Unit
        override suspend fun deleteSession(entity: TelemetrySessionEntity) { sessions.remove(entity) }
        override suspend fun deletePerftestOrphans(): Int = 0

        private fun replace(sessionId: String, transform: (TelemetrySessionEntity) -> TelemetrySessionEntity) {
            val index = sessions.indexOfFirst { it.sessionId == sessionId }
            if (index >= 0) sessions[index] = transform(sessions[index])
        }
    }

    private class FakeCrossingDao : CrossingEventDao {
        private val crossings = mutableListOf<CrossingEventEntity>()
        fun add(entity: CrossingEventEntity) { crossings += entity }
        override suspend fun insertInTransaction(entity: CrossingEventEntity) { crossings += entity }
        override suspend fun queryBySessionId(sessionId: String) = crossings.filter { it.sessionId == sessionId }
        override suspend fun deleteCrossingsBySessionId(sessionId: String) { crossings.removeIf { it.sessionId == sessionId } }
    }

    private class FakeVideoDao : VideoSegmentDao {
        private val segments = mutableListOf<VideoSegmentEntity>()
        override suspend fun insert(entity: VideoSegmentEntity): Long { segments += entity; return segments.size.toLong() }
        override suspend fun queryBySessionId(sessionId: String) = segments.filter { it.sessionId == sessionId }.sortedBy { it.segmentIndex }
        override suspend fun maxSegmentIndex(sessionId: String) = segments.filter { it.sessionId == sessionId }.maxOfOrNull { it.segmentIndex }
        override suspend fun deleteBySessionId(sessionId: String) { segments.removeIf { it.sessionId == sessionId } }
        override suspend fun updatePlayable(id: Long, playable: Boolean) = Unit
    }
}
