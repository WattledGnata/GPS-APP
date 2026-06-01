// @IgnoreFormatCheck
// recording-params-config-screen round · spec 持久化 Requirement（roundtrip + 缺 key→DEFAULT）。
// 每个 @Test 独立 tmp 文件，避免串数据（仿 RecentTracksStoreTest）。
package com.blazepush.feature.test.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.blazepush.feature.test.recording.CameraFacing
import com.blazepush.feature.test.recording.FocusMode
import com.blazepush.feature.test.recording.RecordingConfig
import com.blazepush.feature.test.recording.RecordingResolution
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

class RecordingPreferencesRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("recording_config.preferences_pb") },
        )
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun freshStore_returnsDefault() = runTest {
        val repo = RecordingPreferencesRepository(dataStore)
        assertEquals(RecordingConfig.DEFAULT, repo.configFlow.first())
    }

    @Test
    fun update_roundtrip_readsBackSameConfig() = runTest {
        val repo = RecordingPreferencesRepository(dataStore)
        val cfg = RecordingConfig(
            resolution = RecordingResolution.HD_720P,
            audioEnabled = false,
            cameraFacing = CameraFacing.FRONT,
            focusMode = FocusMode.LOCKED_INFINITY,
            exposureCompensationEv = -2,
        )
        repo.update(cfg)
        assertEquals(cfg, repo.configFlow.first())
    }

    @Test
    fun partialUpdate_missingKeysFallBackToDefault() = runTest {
        // 只改分辨率，其余 key 不写 → 读回时缺失字段走 DEFAULT
        val repo = RecordingPreferencesRepository(dataStore)
        repo.update(RecordingConfig.DEFAULT.copy(resolution = RecordingResolution.UHD_4K))
        val read = repo.configFlow.first()
        assertEquals(RecordingResolution.UHD_4K, read.resolution)
        assertEquals(RecordingConfig.DEFAULT.audioEnabled, read.audioEnabled)
        assertEquals(RecordingConfig.DEFAULT.cameraFacing, read.cameraFacing)
        assertEquals(RecordingConfig.DEFAULT.focusMode, read.focusMode)
        assertEquals(RecordingConfig.DEFAULT.exposureCompensationEv, read.exposureCompensationEv)
    }
}
