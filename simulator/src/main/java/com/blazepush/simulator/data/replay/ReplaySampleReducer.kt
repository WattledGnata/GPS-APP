package com.blazepush.simulator.data.replay

class ReplaySampleReducer {

    fun reduceToTargetHz(samples: List<ReplaySample>, targetHz: Int): List<ReplaySample> {
        if (samples.isEmpty()) return emptyList()
        require(targetHz > 0) { "targetHz must be positive" }

        val minIntervalMillis = 1000L / targetHz
        val reduced = mutableListOf(samples.first())
        var lastKeptTimestamp = samples.first().timestampMillis

        for (sample in samples.drop(1)) {
            if (sample.timestampMillis - lastKeptTimestamp >= minIntervalMillis) {
                reduced += sample
                lastKeptTimestamp = sample.timestampMillis
            }
        }

        return reduced
    }
}
