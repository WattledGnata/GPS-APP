package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.Track

interface TrackCatalog {
    /**
     * A37 change fix-gps-stats-and-lazy-catalog-hot-start：
     * 签名 suspend 化，ReplayAlignedTrackCatalog 内部 withContext(Dispatchers.IO) 包裹
     * asset 读 + Gson parse，避免 TestSessionViewModel 构造期同步调用阻塞 Main。
     * PresetTrackCatalog 直返内存，suspend 仅为对齐接口契约不强制 IO。
     */
    suspend fun getAllTracks(): List<Track>

    /**
     * getTrack 保持同步（非 suspend），避免 suspend 污染传播到 LapTimingEngine 等
     * 同步消费链路。冷缓存（未调过 getAllTracks）时降级 fallback，不触发 asset IO。
     */
    fun getTrack(trackId: String): Track?
}
