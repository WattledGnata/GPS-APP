// @IgnoreFormatCheck
// 理由：测试支持类，不进生产代码；class-comment 等规范由 D round 统一处理。
package com.blazepush.feature.test.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory 实现 [RecentTracksStoreApi]，供 ViewModel test helper 注入。
 *
 * 算法与生产 [RecentTracksStore.add] 一致（头插 + 去重 + 5 条滚动覆盖），
 * 不复用生产代码避免 Fake 与 DataStore 实现耦合。
 *
 * change `replace-nearby-tracks-with-recent-strip` §2.4。
 */
class FakeRecentTracksStore : RecentTracksStoreApi {

    private val state = MutableStateFlow<List<String>>(emptyList())

    override val recentIds: Flow<List<String>> = state.asStateFlow()

    override suspend fun add(trackId: String) {
        val current = state.value
        val deduped = listOf(trackId) + current.filter { it != trackId }
        state.value = deduped.take(MAX_RECENT_COUNT)
    }

    private companion object {
        const val MAX_RECENT_COUNT = 5
    }
}
