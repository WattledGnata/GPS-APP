package com.blazepush.feature.test.model.track

data class TrackPath(
    val points: List<GeoPoint>,
    val closed: Boolean = true
)
