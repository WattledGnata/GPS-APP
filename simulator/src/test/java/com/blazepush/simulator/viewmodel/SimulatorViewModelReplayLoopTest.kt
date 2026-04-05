package com.blazepush.simulator.viewmodel

import com.blazepush.simulator.data.replay.ReplayFrame
import com.blazepush.simulator.data.replay.ReplaySample
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatorViewModelReplayLoopTest {

    @Test
    fun `replay frames loop back to first frame after last frame`() = runBlocking {
        val emitted = mutableListOf<Long>()
        val frames = listOf(
            ReplayFrame(
                delayMillis = 0,
                sample = ReplaySample(
                    timestampMillis = 1000L,
                    latitude = 30.0,
                    longitude = 104.0,
                    speedKmh = 80.0,
                    bearingDegrees = 180.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 400.0,
                    altitudePrecisionMeters = 0.0
                )
            ),
            ReplayFrame(
                delayMillis = 1,
                sample = ReplaySample(
                    timestampMillis = 1200L,
                    latitude = 30.1,
                    longitude = 104.1,
                    speedKmh = 82.0,
                    bearingDegrees = 182.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 401.0,
                    altitudePrecisionMeters = 0.0
                )
            )
        )

        val job = launch {
            withTimeout(200) {
                SimulatorViewModel.playReplayFramesForever(frames) { frame ->
                    emitted += frame.sample.timestampMillis
                    if (emitted.size >= 4) {
                        cancel()
                    }
                }
            }
        }

        job.join()

        assertEquals(listOf(1000L, 1200L, 1000L, 1200L), emitted.take(4))

        job.cancelAndJoin()
    }

    @Test
    fun `playReplayFramesForever stops after coroutine cancellation`() = runBlocking {
        val emitted = mutableListOf<Long>()
        val frames = listOf(
            ReplayFrame(
                delayMillis = 0,
                sample = ReplaySample(
                    timestampMillis = 1000L,
                    latitude = 30.0,
                    longitude = 104.0,
                    speedKmh = 80.0,
                    bearingDegrees = 180.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 400.0,
                    altitudePrecisionMeters = 0.0
                )
            ),
            ReplayFrame(
                delayMillis = 100,
                sample = ReplaySample(
                    timestampMillis = 1100L,
                    latitude = 30.1,
                    longitude = 104.1,
                    speedKmh = 81.0,
                    bearingDegrees = 181.0,
                    satellites = 10,
                    fixType = 1,
                    hdop = 0.8,
                    altitudeMeters = 401.0,
                    altitudePrecisionMeters = 0.0
                )
            )
        )

        val job = launch {
            SimulatorViewModel.playReplayFramesForever(frames) { frame ->
                emitted += frame.sample.timestampMillis
            }
        }

        delay(120)
        job.cancelAndJoin()
        val sizeAfterCancel = emitted.size

        delay(300)

        assertEquals(sizeAfterCancel, emitted.size)
    }
}
