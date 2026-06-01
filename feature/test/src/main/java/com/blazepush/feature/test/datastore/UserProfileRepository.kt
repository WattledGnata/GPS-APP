// @IgnoreFormatCheck
package com.blazepush.feature.test.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile")

/**
 * 用户身份（车手显示名）持久化（driver-display-name round / livetiming 第一步）。
 *
 * lap-upload API 必填 `driver`（车手显示名）。本仓库存一个本地车手名，设置页填写、跨会话保留。
 * 仿 [RecentTracksStore] / [RecordingPreferencesRepository] 双构造：
 * - 主构造 internal(DataStore)：JVM 单测注入临时 DataStore
 * - 生产构造(Context)：DI 在 AppModule 调
 *
 * 缺值（未填）→ 空串（上层 livetiming 上报前需校验非空）。
 */
class UserProfileRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.userProfileDataStore)

    /** 车手显示名（未填则空串）。 */
    val driverName: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_DRIVER_NAME] ?: ""
    }

    suspend fun setDriverName(name: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DRIVER_NAME] = name
        }
    }

    companion object {
        val KEY_DRIVER_NAME = stringPreferencesKey("driver_name")
    }
}
