package com.blazepush.simulator.data.replay

import com.google.gson.Gson

class ReplayAssetLoader(
    private val gson: Gson = Gson()
) {

    fun loadReplayJson(json: String): ReplaySession {
        val payload = gson.fromJson(json, ReplayAssetPayload::class.java)
        return ReplaySession(
            sessionTitle = payload.sessionTitle,
            samples = payload.samples
        )
    }
}

private data class ReplayAssetPayload(
    val sessionTitle: String,
    val samples: List<ReplaySample>
)
