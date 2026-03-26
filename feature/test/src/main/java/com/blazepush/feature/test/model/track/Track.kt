package com.blazepush.feature.test.model.track

data class Track(
    val id: String,
    val name: String,
    val layoutName: String? = null,
    val source: TrackSource = TrackSource.Preset,
    val referencePath: TrackPath,
    val startFinishGate: TimingGate,
    val sectorGates: List<TimingGate> = emptyList()
)
