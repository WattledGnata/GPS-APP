package com.blazepush.simulator.data.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAssetLoaderTest {

    @Test
    fun `loadReplayJson parses reduced replay payload`() {
        val json = """
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

        val replay = ReplayAssetLoader().loadReplayJson(json)

        assertEquals("天府赛道", replay.sessionTitle)
        assertEquals(2, replay.samples.size)
        assertEquals(1773477689848L, replay.samples.last().timestampMillis)
        assertTrue(replay.samples.last().speedKmh > replay.samples.first().speedKmh)
    }
}
