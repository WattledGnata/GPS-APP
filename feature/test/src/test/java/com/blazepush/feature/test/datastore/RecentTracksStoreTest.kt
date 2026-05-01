// @IgnoreFormatCheck
// 理由：JUnit4 测试类命名 snake_case 承载 Gherkin 语义；本文件随 change
//       replace-nearby-tracks-with-recent-strip §1.3 新建。每个 @Test 独立 TestScope
//       + 独立 tmp 文件，避免多测试串数据（Codex 实施提醒）。
package com.blazepush.feature.test.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecentTracksStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("recent_tracks.preferences_pb") },
        )
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun freshStore_recentIdsIsEmpty() = runTest {
        val store = RecentTracksStore(dataStore)
        assertEquals(emptyList<String>(), store.recentIds.first())
    }

    @Test
    fun add_singleEntry_recentIdsHasOneItem() = runTest {
        val store = RecentTracksStore(dataStore)
        store.add("a")
        assertEquals(listOf("a"), store.recentIds.first())
    }

    @Test
    fun add_threeEntries_recentIdsInReverseTimeOrder() = runTest {
        val store = RecentTracksStore(dataStore)
        store.add("a")
        store.add("b")
        store.add("c")
        assertEquals(listOf("c", "b", "a"), store.recentIds.first())
    }

    @Test
    fun add_existingId_movesToHeadAndDedupes() = runTest {
        val store = RecentTracksStore(dataStore)
        store.add("a")
        store.add("b")
        store.add("c")
        store.add("b")
        assertEquals(listOf("b", "c", "a"), store.recentIds.first())
    }

    @Test
    fun add_overFiveEntries_dropsTailEntry() = runTest {
        val store = RecentTracksStore(dataStore)
        store.add("a")
        store.add("b")
        store.add("c")
        store.add("d")
        store.add("e")
        store.add("f")
        assertEquals(listOf("f", "e", "d", "c", "b"), store.recentIds.first())
    }

    @Test
    fun secondStoreInstance_recoversFromSameDataStore() = runTest {
        val storeA = RecentTracksStore(dataStore)
        storeA.add("x")
        storeA.add("y")
        // 同一 dataStore 共享背后文件 / 状态；模拟"第二个 Store 实例"读
        val storeB = RecentTracksStore(dataStore)
        assertEquals(listOf("y", "x"), storeB.recentIds.first())
    }
}
