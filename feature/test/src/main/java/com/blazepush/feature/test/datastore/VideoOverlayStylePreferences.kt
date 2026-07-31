package com.blazepush.feature.test.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blazepush.feature.test.overlay.VideoOverlayStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.videoOverlayStyleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "video_overlay_style",
)

class VideoOverlayStylePreferences internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.videoOverlayStyleDataStore)

    val style: Flow<VideoOverlayStyle> = dataStore.data.map { prefs ->
        VideoOverlayStyle.fromStored(prefs[KEY_STYLE])
    }

    suspend fun setStyle(style: VideoOverlayStyle) {
        dataStore.edit { prefs -> prefs[KEY_STYLE] = style.name }
    }

    companion object {
        val KEY_STYLE = stringPreferencesKey("video_overlay_style")
    }
}
