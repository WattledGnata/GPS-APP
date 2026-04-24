package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.GeoLine
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.model.track.GeoVector
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TimingGateType
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.model.track.TrackPath

internal val presetTracks: List<Track> = listOf(
    Track(
        id = "preset-tfic-lpcc",
        name = "TFIC LPCC",
        layoutName = "RaceChrono RCZ",
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(30.4945735, 104.4332358),
                GeoPoint(30.4927678, 104.4332757),
                GeoPoint(30.4914073, 104.4346407),
                GeoPoint(30.4903109, 104.4329748),
                GeoPoint(30.4905453, 104.4350638),
                GeoPoint(30.4920068, 104.4362783),
                GeoPoint(30.4937000, 104.4369812),
                GeoPoint(30.4955157, 104.4371740),
                GeoPoint(30.4969511, 104.4359008),
                GeoPoint(30.4977035, 104.4339855),
                GeoPoint(30.4978068, 104.4318679),
                GeoPoint(30.4963642, 104.4331724),
                GeoPoint(30.4945735, 104.4332358)
            )
        ),
        startFinishGate = TimingGate(
            id = "start-finish",
            name = "起点",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(30.496167246506413, 104.43343794245452),
                end = GeoPoint(30.49619075349359, 104.43291739087881)
            ),
            passDirection = GeoVector(x = -0.0002602757878550089, y = -0.000023506987175358924),
            sequenceIndex = 0,
            minDirectionalSpeedMps = null
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "s1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(30.49004451419976, 104.43252709154902),
                    end = GeoPoint(30.48959781913357, 104.43258157511764)
                ),
                passDirection = GeoVector(x = -0.00002724178431097556, y = -0.00044669506619011374),
                sequenceIndex = 1,
                minDirectionalSpeedMps = null
            ),
            TimingGate(
                id = "s2",
                name = "s2",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(30.4957579139104, 104.4369620745035),
                    end = GeoPoint(30.495765752756267, 104.43748325882984)
                ),
                passDirection = GeoVector(x = -0.0002605921631704301, y = 0.000007838845867048829),
                sequenceIndex = 2,
                minDirectionalSpeedMps = null
            )
        )
    )
)

class PresetTrackCatalog : TrackCatalog {
    // A37：内存直返，suspend 不强制 withContext(Dispatchers.IO)
    override suspend fun getAllTracks(): List<Track> = presetTracks

    override fun getTrack(trackId: String): Track? = presetTracks.firstOrNull { it.id == trackId }
}
