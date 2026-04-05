package com.blazepush.feature.test.usecase

import org.junit.Ignore
import org.junit.Test

@Ignore(
    "Out of scope for the stable lap-timing baseline: replay integration still depends on the experimental gate builder chain."
)
class ReplayLapTimingIntegrationTest {

    @Test
    fun `excluded from stable lap timing test baseline until replay gate pipeline is promoted`() {
        // Intentionally left blank. Replay integration coverage is kept out of the stable baseline
        // until it no longer depends on the experimental ReplayTemporaryGateBuilder chain.
    }
}
