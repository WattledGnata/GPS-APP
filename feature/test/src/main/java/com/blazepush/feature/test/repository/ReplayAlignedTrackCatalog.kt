package com.blazepush.feature.test.repository

import android.content.Context
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath
import com.blazepush.feature.test.model.track.TrackSource
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

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

    private val replayAlignedTrack: Track? by lazy {
        runCatching {
            buildReplayAlignedTrack(
                replayJson = replayTrackSource.loadReplayJson(),
                vbo = replayTrackSource.loadTrackVbo()
            )
        }.getOrNull()
    }

    override fun getAllTracks(): List<Track> {
        val fallbackTracks = fallbackCatalog.getAllTracks().filterNot { it.id == TFIC_TRACK_ID }
        val replayTrack = replayAlignedTrack ?: fallbackCatalog.getTrack(TFIC_TRACK_ID)
        return if (replayTrack != null) fallbackTracks + replayTrack else fallbackTracks
    }

    override fun getTrack(trackId: String): Track? {
        if (trackId != TFIC_TRACK_ID) return fallbackCatalog.getTrack(trackId)
        return replayAlignedTrack ?: fallbackCatalog.getTrack(trackId)
    }

    private fun buildReplayAlignedTrack(replayJson: String, vbo: String): Track {
        val replaySamples = parseReplaySamples(replayJson)
        require(replaySamples.isNotEmpty()) { "Replay samples are empty" }

        val fallbackTrack = fallbackCatalog.getTrack(TFIC_TRACK_ID)
            ?: error("Fallback TFIC track missing")

        return Track(
            id = TFIC_TRACK_ID,
            name = fallbackTrack.name,
            layoutName = "REAL_TRACK_REPLAY",
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
