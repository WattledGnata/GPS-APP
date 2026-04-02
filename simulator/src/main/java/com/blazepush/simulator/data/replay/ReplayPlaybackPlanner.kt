package com.blazepush.simulator.data.replay

data class ReplayFrame(
    val sample: ReplaySample,
    val delayMillis: Long
)

class ReplayPlaybackPlanner {

    fun plan(samples: List<ReplaySample>): List<ReplayFrame> {
        if (samples.isEmpty()) return emptyList()

        return samples.mapIndexed { index, sample ->
            val delayMillis = if (index == 0) {
                0L
            } else {
                (sample.timestampMillis - samples[index - 1].timestampMillis).coerceAtLeast(0L)
            }
            ReplayFrame(sample = sample, delayMillis = delayMillis)
        }
    }
}
