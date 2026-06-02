// @IgnoreFormatCheck
// driver-display-name round · 车手显示名持久化 roundtrip。
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

class UserProfileRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("user_profile.preferences_pb") },
        )
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun freshStore_driverNameIsEmpty() = runTest {
        val repo = UserProfileRepository(dataStore)
        assertEquals("", repo.driverName.first())
    }

    @Test
    fun setDriverName_roundtrip() = runTest {
        val repo = UserProfileRepository(dataStore)
        repo.setDriverName("老王")
        assertEquals("老王", repo.driverName.first())
    }

    @Test
    fun setDriverName_overwrite() = runTest {
        val repo = UserProfileRepository(dataStore)
        repo.setDriverName("88号")
        repo.setDriverName("99号")
        assertEquals("99号", repo.driverName.first())
    }

    // livetiming-lap-upload round 新增 key

    @Test
    fun livetimingEnabled_defaultsTrue() = runTest {
        val repo = UserProfileRepository(dataStore)
        assertEquals(true, repo.livetimingEnabled.first())
    }

    @Test
    fun setLivetimingEnabled_roundtrip() = runTest {
        val repo = UserProfileRepository(dataStore)
        repo.setLivetimingEnabled(false)
        assertEquals(false, repo.livetimingEnabled.first())
    }

    @Test
    fun hasShownDriverNamePrompt_defaultsFalse() = runTest {
        val repo = UserProfileRepository(dataStore)
        assertEquals(false, repo.hasShownDriverNamePrompt.first())
    }

    @Test
    fun setDriverNamePromptShown_roundtrip() = runTest {
        val repo = UserProfileRepository(dataStore)
        repo.setDriverNamePromptShown()
        assertEquals(true, repo.hasShownDriverNamePrompt.first())
    }
}
