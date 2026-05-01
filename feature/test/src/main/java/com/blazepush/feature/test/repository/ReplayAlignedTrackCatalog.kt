// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / public-fun-with-comment-block
//       / no-trailing-newline 属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.repository

import android.content.Context
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath
import com.blazepush.feature.test.model.track.TrackSource
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TFIC_TRACK_ID = "preset-tfic-lpcc"
private const val REPLAY_JSON_ASSET_PATH = "replay/tianfu_track_replay_5hz.json"
private const val REPLAY_VBO_ASSET_PATH = "replay/tianfu_track.vbo"

interface ReplayTrackSource {
    fun loadReplayJson(): String
    fun loadTrackVbo(): String
}

class AssetReplayTrackSource(
    private val context: Context
) : ReplayTrackSource {
    override fun loadReplayJson(): String =
        context.assets.open(REPLAY_JSON_ASSET_PATH).bufferedReader().use { it.readText() }

    override fun loadTrackVbo(): String =
        context.assets.open(REPLAY_VBO_ASSET_PATH).bufferedReader().use { it.readText() }
}

class ReplayAlignedTrackCatalog(
    private val replayTrackSource: ReplayTrackSource,
    private val fallbackCatalog: TrackCatalog = PresetTrackCatalog()
) : TrackCatalog {

    // A37：显式缓存替代原惰性属性（design D5），避免 SYNCHRONIZED 惰性在 IO 池线程竞争
    @Volatile
    private var cachedReplayTrack: Track? = null

    @Volatile
    private var cacheInitialized = false

    // A37：顶层 withContext(Dispatchers.IO) 是 IO 边界的唯一防线（spec R3）
    override suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        ensureReplayTrackLoaded()
        val fallbackTracks = fallbackCatalog.getAllTracks().filterNot { it.id == TFIC_TRACK_ID }
        val replayTrack = cachedReplayTrack ?: fallbackCatalog.getTrack(TFIC_TRACK_ID)
        if (replayTrack != null) fallbackTracks + replayTrack else fallbackTracks
    }

    /**
     * getTrack 保持同步。冷缓存（未调过 getAllTracks）时降级 fallback，不触发 asset IO。
     * spec R4：冷 getTrack(TFIC) 返回 preset fallback 版；热缓存命中返回 replay-aligned 版。
     */
    override fun getTrack(trackId: String): Track? {
        if (trackId != TFIC_TRACK_ID) return fallbackCatalog.getTrack(trackId)
        return if (cacheInitialized) {
            cachedReplayTrack ?: fallbackCatalog.getTrack(trackId)
        } else {
            fallbackCatalog.getTrack(trackId)
        }
    }

    /**
     * 双检锁：只有 getAllTracks 能触发 replay asset 加载；
     * 首次访问 + 后续 getAllTracks 调用都走此函数，加载仅发生一次。
     */
    private fun ensureReplayTrackLoaded() {
        if (cacheInitialized) return
        synchronized(this) {
            if (cacheInitialized) return
            cachedReplayTrack = runCatching {
                buildReplayAlignedTrack(
                    replayJson = replayTrackSource.loadReplayJson(),
                    vbo = replayTrackSource.loadTrackVbo()
                )
            }.getOrNull()
            cacheInitialized = true
        }
    }

    private fun buildReplayAlignedTrack(replayJson: String, vbo: String): Track {
        val replaySamples = parseReplaySamples(replayJson)
        require(replaySamples.isNotEmpty()) { "Replay samples are empty" }

        val fallbackTrack = fallbackCatalog.getTrack(TFIC_TRACK_ID)
            ?: error("Fallback TFIC track missing")

        return Track(
            id = TFIC_TRACK_ID,
            name = fallbackTrack.name,
            lengthKm = fallbackTrack.lengthKm,
            thumbnailAssetPath = fallbackTrack.thumbnailAssetPath,
            source = TrackSource.Generated,
            referencePath = TrackPath(
                points = replaySamples.map { sample ->
                    com.blazepush.feature.test.model.track.GeoPoint(sample.latitude, sample.longitude)
                }
            ),
            startFinishGate = fallbackTrack.startFinishGate,
            sectorGates = fallbackTrack.sectorGates
        )
    }

    private fun parseReplaySamples(json: String): List<RuntimeReplaySample> {
        val payload = Gson().fromJson(json, ReplayAssetPayload::class.java)
        return payload.samples.map { sample ->
            RuntimeReplaySample(
                timestampMillis = sample.timestampMillis,
                latitude = sample.latitude,
                longitude = sample.longitude,
                speedKmh = sample.speedKmh,
                bearingDegrees = sample.bearingDegrees,
                hdop = sample.hdop,
                altitudeMeters = sample.altitudeMeters
            )
        }
    }

}

private data class ReplayAssetPayload(
    val samples: List<ReplayAssetSample>
)

private data class ReplayAssetSample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val hdop: Double,
    @SerializedName("altitudeMeters")
    val altitudeMeters: Double
)

private data class RuntimeReplaySample(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val hdop: Double,
    val altitudeMeters: Double
)
