package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.track.Track

interface TrackCatalog {
    fun getAllTracks(): List<Track>
    fun getTrack(trackId: String): Track?
}
