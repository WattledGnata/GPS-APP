package com.blazepush.simulator.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAssetPresenceTest {

    @Test
    fun `tianfu replay asset exists in simulator module`() {
        val assetFile = File("src/main/assets/replay/tianfu_track_replay_5hz.json")

        assertTrue(assetFile.exists())
    }
}
