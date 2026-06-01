// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 录制参数 DataStore Preferences key 集中定义（防散落字符串拼错）。
 * recording-params-config-screen round · spec 持久化 Requirement。
 *
 * 注：[com.blazepush.feature.test.recording.RecordingConfig.targetFps] 本 round 不持久化
 * （UI 不暴露 60fps，永远 30），故无对应 key。
 */
object RecordingPrefsKeys {
    val RESOLUTION = stringPreferencesKey("recording_resolution") // RecordingResolution.name
    val AUDIO_ENABLED = booleanPreferencesKey("recording_audio_enabled")
    val CAMERA_FACING = stringPreferencesKey("recording_camera_facing") // CameraFacing.name
    val FOCUS_MODE = stringPreferencesKey("recording_focus_mode") // FocusMode.name
    val EXPOSURE_EV = intPreferencesKey("recording_exposure_ev")
}
