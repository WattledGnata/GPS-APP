// @IgnoreFormatCheck
package com.blazepush.feature.test.livetiming

import com.blazepush.core.domain.model.LapEvidence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.blazepush.core.data.local.dao.PendingLapUploadDao
import com.blazepush.core.data.local.entity.PendingLapUploadEntity
import com.blazepush.core.network.LapUploadApi
import com.blazepush.core.network.LapUploadDto
import com.blazepush.core.network.UploadResult
import com.blazepush.feature.test.datastore.UserProfileRepository
import com.blazepush.feature.test.model.laptiming.LapRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** livetiming-lap-upload spec R1/R3/R4 编排覆盖（前置校验 / 队列幂等补传 / 错误分流）。 */
class LapUploadOrchestratorTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var userProfile: UserProfileRepository
    private lateinit var api: FakeUploadApi
    private lateinit var dao: FakePendingDao

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("user_profile.preferences_pb") },
        )
        userProfile = UserProfileRepository(dataStore)
        api = FakeUploadApi()
        dao = FakePendingDao()
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    private fun orchestrator() = LapUploadOrchestrator(api, dao, userProfile, nowMs = { 123L })

    private fun record(lapIndex: Int = 2, trackId: String = "preset-tfic-lpcc") = LapRecord(
        recordId = "r$lapIndex",
        sessionId = "s1",
        trackId = trackId,
        lapIndex = lapIndex,
        startedAtMillis = 0L,
        finishedAtMillis = 1_700_000_000_000L,
        durationMillis = 92345L,
        sectorTimes = listOf(31000L, 30000L, 31345L),
        evidence = LapEvidence(
            startCrossingTimestampMillis = 0L,
            finishCrossingTimestampMillis = 1_700_000_000_000L,
            requiredGateIds = setOf("SF"),
            acceptedGateIds = setOf("SF"),
        ),
    )

    @Test
    fun disabled_skipsUploadAndQueue() = runTest {
        userProfile.setLivetimingEnabled(false)
        orchestrator().onLapCompleted(record())
        assertTrue("开关关:不上报", api.calls.isEmpty())
        assertEquals("开关关:不入队", 0, dao.store.size)
    }

    @Test
    fun noDriverName_skipsAndDoesNotQueue() = runTest {
        // 默认开 + 无车手名
        orchestrator().onLapCompleted(record())
        assertTrue("无车手名:不上报", api.calls.isEmpty())
        assertEquals("无车手名:不入队", 0, dao.store.size)
    }

    @Test
    fun blankTrackId_skips() = runTest {
        userProfile.setDriverName("老王")
        orchestrator().onLapCompleted(record(trackId = ""))
        assertTrue(api.calls.isEmpty())
        assertEquals(0, dao.store.size)
    }

    @Test
    fun success_uploadsAndNoQueue() = runTest {
        userProfile.setDriverName("老王")
        api.result = UploadResult.Success
        orchestrator().onLapCompleted(record())
        assertEquals(1, api.calls.size)
        assertEquals("s1:2", api.calls.first().clientLapId)
        assertEquals("成功不入队", 0, dao.store.size)
    }

    @Test
    fun networkError_enqueuesWithStableClientLapId() = runTest {
        userProfile.setDriverName("老王")
        api.result = UploadResult.NetworkError(RuntimeException("offline"))
        orchestrator().onLapCompleted(record())
        assertEquals(1, dao.store.size)
        assertTrue("入队键 = sessionId:lapIndex", dao.store.containsKey("s1:2"))
    }

    @Test
    fun http400_doesNotEnqueue() = runTest {
        userProfile.setDriverName("老王")
        api.result = UploadResult.HttpError(400)
        orchestrator().onLapCompleted(record())
        assertEquals("400 永久失败:不入队不死循环", 0, dao.store.size)
    }

    @Test
    fun flush_reusesSameClientLapId_thenDequeuesOn201() = runTest {
        userProfile.setDriverName("老王")
        // 先失败入队
        api.result = UploadResult.NetworkError(null)
        orchestrator().onLapCompleted(record())
        assertEquals(1, dao.store.size)
        api.calls.clear()
        // flush 成功
        api.result = UploadResult.Success
        orchestrator().flush()
        assertEquals("flush 复用同一 clientLapId", "s1:2", api.calls.first().clientLapId)
        assertEquals("201 出队", 0, dao.store.size)
    }

    @Test
    fun reEnqueueSameLap_doesNotDuplicate() = runTest {
        userProfile.setDriverName("老王")
        api.result = UploadResult.NetworkError(null)
        val o = orchestrator()
        o.onLapCompleted(record())
        o.onLapCompleted(record()) // 同圈再次失败
        assertEquals("同圈不重复堆积（clientLapId 唯一）", 1, dao.store.size)
    }

    @Test
    fun concurrentFlush_uploadsPendingLapOnlyOnce() = runTest {
        userProfile.setDriverName("老王")
        api.result = UploadResult.NetworkError(null)
        val o = orchestrator()
        o.onLapCompleted(record())
        api.calls.clear()

        api.result = UploadResult.Success
        api.delayMs = 10L
        coroutineScope {
            launch { o.flush() }
            launch { o.flush() }
        }

        assertEquals("启动与网络回调并发时同一待传圈只能上传一次", 1, api.calls.size)
        assertEquals(0, dao.store.size)
    }

    @Test
    fun legacyPending_withoutQuality_isPreservedAndNotUploaded() = runTest {
        dao.enqueue(
            PendingLapUploadEntity(
                clientLapId = "legacy",
                trackId = "track",
                driver = "driver",
                lapNo = 1,
                lapTimeMs = 1000,
                createdAtMs = 1,
            )
        )
        orchestrator().flush()
        assertTrue(api.calls.isEmpty())
        assertTrue(dao.store.containsKey("legacy"))
    }

    // ---- fakes ----

    private class FakeUploadApi(var result: UploadResult = UploadResult.Success) : LapUploadApi {
        val calls = mutableListOf<LapUploadDto>()
        var delayMs: Long = 0L
        override suspend fun upload(dto: LapUploadDto): UploadResult {
            calls.add(dto)
            if (delayMs > 0) delay(delayMs)
            return result
        }
    }

    private class FakePendingDao : PendingLapUploadDao {
        val store = LinkedHashMap<String, PendingLapUploadEntity>()
        override suspend fun enqueue(entity: PendingLapUploadEntity) {
            store.putIfAbsent(entity.clientLapId, entity) // 模拟 OnConflict.IGNORE 唯一约束
        }
        override suspend fun all(): List<PendingLapUploadEntity> = store.values.toList()
        override suspend fun deleteByClientLapId(clientLapId: String) {
            store.remove(clientLapId)
        }
        override suspend fun incrementRetry(clientLapId: String) {
            store[clientLapId]?.let { store[clientLapId] = it.copy(retryCount = it.retryCount + 1) }
        }
        override suspend fun count(): Int = store.size
    }
}
