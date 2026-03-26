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
        id = "preset-demo-circuit",
        name = "Demo Circuit",
        layoutName = "Forward",
        referencePath = TrackPath(
            points = listOf(
                GeoPoint(39.900000, 116.400000),
                GeoPoint(39.900300, 116.400400),
                GeoPoint(39.900700, 116.400350),
                GeoPoint(39.900900, 116.399900),
                GeoPoint(39.900500, 116.399500),
                GeoPoint(39.900000, 116.400000)
            )
        ),
        startFinishGate = TimingGate(
            id = "sf",
            name = "Start/Finish",
            type = TimingGateType.StartFinish,
            line = GeoLine(
                start = GeoPoint(39.900050, 116.399950),
                end = GeoPoint(39.899950, 116.400050)
            ),
            passDirection = GeoVector(x = 1.0, y = 0.0),
            sequenceIndex = 0,
            minDirectionalSpeedMps = 2.0
        ),
        sectorGates = listOf(
            TimingGate(
                id = "s1",
                name = "Sector 1",
                type = TimingGateType.Sector,
                line = GeoLine(
                    start = GeoPoint(39.900650, 116.400300),
                    end = GeoPoint(39.900750, 116.400450)
                ),
                passDirection = GeoVector(x = 0.0, y = 1.0),
                sequenceIndex = 1,
                minDirectionalSpeedMps = 2.0
            )
        )
    )
)

class PresetTrackCatalog : TrackCatalog {
    override fun getAllTracks(): List<Track> = presetTracks

    override fun getTrack(trackId: String): Track? = presetTracks.firstOrNull { it.id == trackId }
}
