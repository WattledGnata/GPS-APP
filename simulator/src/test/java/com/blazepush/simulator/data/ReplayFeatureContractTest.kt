package com.blazepush.simulator.data

import com.blazepush.simulator.data.replay.ReplayAssetLoader
import com.blazepush.simulator.data.replay.ReplayPlaybackPlanner
import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayFeatureContractTest {

    @Test
    fun `ReplayAssetLoader parses replay payload`() {
        val replay = ReplayAssetLoader().loadReplayJson(SAMPLE_JSON)
        val firstSample = replay.samples.first()
        val lastSample = replay.samples.last()

        assertEquals("天府赛道", replay.sessionTitle)
        assertEquals(2, replay.samples.size)
        assertEquals(1773477689848L, lastSample.timestampMillis)
        assertTrue(lastSample.speedKmh > firstSample.speedKmh)
    }

    @Test
    fun `ReplayPlaybackPlanner builds frame delays from timestamps`() {
        val samples = listOf(
            ReplaySample(1000L, 30.0, 104.0, 10.0, 180.0, 10, 1, 0.8, 400.0, 0.0),
            ReplaySample(1200L, 30.1, 104.1, 20.0, 181.0, 10, 1, 0.8, 401.0, 0.0),
            ReplaySample(1600L, 30.2, 104.2, 30.0, 182.0, 10, 1, 0.8, 402.0, 0.0)
        )

        val frames = ReplayPlaybackPlanner().plan(samples)

        assertEquals(3, frames.size)
        assertEquals(0L, frames[0].delayMillis)
        assertEquals(200L, frames[1].delayMillis)
        assertEquals(400L, frames[2].delayMillis)
        assertEquals(30.2, frames[2].sample.latitude, 0.000001)
    }

    companion object {
        private val SAMPLE_JSON = """
            {
              "sessionTitle":"天府赛道",
              "source":"RaceChrono v3 csv reduced to 5Hz",
              "sampleCount":2,
              "samples":[
                {"timestampMillis":1773477689648,"latitude":30.4945735,"longitude":104.4332358,"speedKmh":0.03744,"bearingDegrees":212.91,"satellites":12,"fixType":1,"hdop":0.9,"altitudeMeters":444.0,"altitudePrecisionMeters":0.0},
                {"timestampMillis":1773477689848,"latitude":30.4945736,"longitude":104.4332360,"speedKmh":0.18,"bearingDegrees":212.00,"satellites":12,"fixType":1,"hdop":0.8,"altitudeMeters":444.1,"altitudePrecisionMeters":0.0}
              ]
            }
        """.trimIndent()
    }
}
