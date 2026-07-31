package com.blazepush.feature.test.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.blazepush.feature.test.overlay.VideoOverlayStyle
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

class VideoOverlayStylePreferencesTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmpFolder.newFile("video_overlay_style.preferences_pb") },
        )
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    @Test
    fun freshStore_defaultsToFlat() = runTest {
        assertEquals(
            VideoOverlayStyle.FLAT,
            VideoOverlayStylePreferences(dataStore).style.first(),
        )
    }

    @Test
    fun selectedStyle_roundTrips() = runTest {
        val preferences = VideoOverlayStylePreferences(dataStore)
        preferences.setStyle(VideoOverlayStyle.MECHANICAL)
        assertEquals(VideoOverlayStyle.MECHANICAL, preferences.style.first())
    }

    @Test
    fun unknownStoredValue_fallsBackToFlat() = runTest {
        dataStore.edit { it[VideoOverlayStylePreferences.KEY_STYLE] = "REMOVED_STYLE" }
        assertEquals(
            VideoOverlayStyle.FLAT,
            VideoOverlayStylePreferences(dataStore).style.first(),
        )
    }
}
