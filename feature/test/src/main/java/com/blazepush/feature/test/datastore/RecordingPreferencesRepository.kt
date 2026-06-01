// @IgnoreFormatCheck
package com.blazepush.feature.test.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.blazepush.feature.test.recording.CameraFacing
import com.blazepush.feature.test.recording.FocusMode
import com.blazepush.feature.test.recording.RecordingConfig
import com.blazepush.feature.test.recording.RecordingPrefsKeys
import com.blazepush.feature.test.recording.RecordingResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recordingConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "recording_config")

/**
 * 录制参数配置持久化（DataStore Preferences）。
 *
 * 仿 [RecentTracksStore] 的双构造：
 * - 主构造 internal(DataStore)：JVM 单测注入 `PreferenceDataStoreFactory.create` 临时文件
 * - 生产构造(Context)：通过顶层 delegate；DI 在 AppModule 调此构造
 *
 * 缺 key（新安装）/ 枚举名解析失败 → 走 [RecordingConfig.DEFAULT] 字段默认（spec 持久化反例：不崩）。
 * targetFps 不持久化（本 round 锁 30）。
 *
 * recording-params-config-screen round · spec 持久化 Requirement。
 */
class RecordingPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.recordingConfigDataStore)

    val configFlow: Flow<RecordingConfig> = dataStore.data.map { prefs ->
        RecordingConfig(
            resolution = prefs[RecordingPrefsKeys.RESOLUTION]
                ?.let { runCatching { RecordingResolution.valueOf(it) }.getOrNull() }
                ?: RecordingConfig.DEFAULT.resolution,
            // targetFps 不持久化，恒为默认（本 round 不暴露 60fps）
            targetFps = RecordingConfig.DEFAULT.targetFps,
            audioEnabled = prefs[RecordingPrefsKeys.AUDIO_ENABLED]
                ?: RecordingConfig.DEFAULT.audioEnabled,
            cameraFacing = prefs[RecordingPrefsKeys.CAMERA_FACING]
                ?.let { runCatching { CameraFacing.valueOf(it) }.getOrNull() }
                ?: RecordingConfig.DEFAULT.cameraFacing,
            focusMode = prefs[RecordingPrefsKeys.FOCUS_MODE]
                ?.let { runCatching { FocusMode.valueOf(it) }.getOrNull() }
                ?: RecordingConfig.DEFAULT.focusMode,
            exposureCompensationEv = prefs[RecordingPrefsKeys.EXPOSURE_EV]
                ?: RecordingConfig.DEFAULT.exposureCompensationEv,
        )
    }

    suspend fun update(config: RecordingConfig) {
        dataStore.edit { prefs ->
            prefs[RecordingPrefsKeys.RESOLUTION] = config.resolution.name
            prefs[RecordingPrefsKeys.AUDIO_ENABLED] = config.audioEnabled
            prefs[RecordingPrefsKeys.CAMERA_FACING] = config.cameraFacing.name
            prefs[RecordingPrefsKeys.FOCUS_MODE] = config.focusMode.name
            prefs[RecordingPrefsKeys.EXPOSURE_EV] = config.exposureCompensationEv
        }
    }
}
