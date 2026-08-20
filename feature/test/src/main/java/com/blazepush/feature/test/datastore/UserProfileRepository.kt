// @IgnoreFormatCheck
package com.blazepush.feature.test.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    /** livetiming 上报开关。默认关闭，由用户在填写车手名后主动开启。 */
    val livetimingEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LIVETIMING_ENABLED] ?: false
    }

    suspend fun setLivetimingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_LIVETIMING_ENABLED] = enabled
        }
    }

    /** 首开车手名引导是否已弹过（first-launch-driver-prompt）。默认 false（缺值 = 没弹过）。 */
    val hasShownDriverNamePrompt: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DRIVER_PROMPT_SHOWN] ?: false
    }

    suspend fun setDriverNamePromptShown() {
        dataStore.edit { prefs ->
            prefs[KEY_DRIVER_PROMPT_SHOWN] = true
        }
    }

    /**
     * 单圈详情面板顺序（lap-detail-triview-panel round design Decision 3,per-app）。
     * 存逗号分隔 PanelId 名;缺值/损坏由 LapDetailPanelOrder.parse 兜底默认顺序。
     */
    val lapDetailPanelOrder: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LAP_DETAIL_PANEL_ORDER] ?: ""
    }

    suspend fun setLapDetailPanelOrder(serialized: String) {
        dataStore.edit { prefs ->
            prefs[KEY_LAP_DETAIL_PANEL_ORDER] = serialized
        }
    }

    companion object {
        val KEY_DRIVER_NAME = stringPreferencesKey("driver_name")
        val KEY_LIVETIMING_ENABLED = booleanPreferencesKey("livetiming_enabled")
        val KEY_DRIVER_PROMPT_SHOWN = booleanPreferencesKey("driver_name_prompt_shown")
        val KEY_LAP_DETAIL_PANEL_ORDER = stringPreferencesKey("lap_detail_panel_order")
    }
}
