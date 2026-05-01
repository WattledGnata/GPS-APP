package com.blazepush.feature.test.datastore
// @IgnoreFormatCheck

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户最近选过的赛道列表持久化抽象。change `replace-nearby-tracks-with-recent-strip` §1.2。
 *
 * ViewModel / DI MUST 绑此接口，禁止绑具体 [RecentTracksStore] 类，便于测试注入
 * [com.blazepush.feature.test.datastore.FakeRecentTracksStore]。
 */
interface RecentTracksStoreApi {
    /**
     * 当前最近选过的赛道 ID 列表（时间倒序，最近选的在头部，最多 5 条）。
     * DataStore 自动 Flow，跨进程恢复。
     */
    val recentIds: Flow<List<String>>

    /**
     * 追加一条最近选过的赛道：头插 + 自动去重（移除既有相同 ID）+ 滚动覆盖
     * 最多 5 条（超出尾部丢弃）。事务性 [androidx.datastore.preferences.core.edit]。
     */
    suspend fun add(trackId: String)
}

private val Context.recentTracksDataStore: DataStore<Preferences> by preferencesDataStore(name = "recent_tracks")

/**
 * 生产实现：用 androidx DataStore Preferences 持久化。
 *
 * 双入口构造（spec §1.2 强约束）：
 * - 主构造 [internal constructor(DataStore)]：接收已构造的 DataStore，便于 JVM 单测
 *   注入 [androidx.datastore.preferences.core.PreferenceDataStoreFactory.create] 临时文件
 * - 生产构造 [constructor(Context)]：通过顶层 delegate `Context.recentTracksDataStore`
 *   拿到 DataStore；DI 在 AppModule 调此构造
 */
class RecentTracksStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : RecentTracksStoreApi {

    constructor(context: Context) : this(context.recentTracksDataStore)

    override val recentIds: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_RECENT_TRACK_IDS]
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    override suspend fun add(trackId: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_TRACK_IDS]
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val deduped = listOf(trackId) + current.filter { it != trackId }
            val capped = deduped.take(MAX_RECENT_COUNT)
            prefs[KEY_RECENT_TRACK_IDS] = capped.joinToString(",")
        }
    }

    companion object {
        const val MAX_RECENT_COUNT = 5
        private val KEY_RECENT_TRACK_IDS = stringPreferencesKey("recent_track_ids")
    }
}
