package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class BinaryTelemetryRecoveryTest {
    private val startTs = 10_000L

    @Test
    fun `dead process complete samples repair stale header without inventing samples`() {
        withCrashFile(completeSamples = 3, partialBytes = 0) { file ->
            val result = BinaryTelemetryRecovery.repair(file.absolutePath)

            assertEquals(3, result.sampleCount)
            assertEquals(0L, result.truncatedBytes)
            assertEquals(3, readHeader(file).sampleCount)
            assertEquals(startTs + 80L, readHeader(file).endTs)
            assertEquals(3, PerformanceTestTelemetryReader.read(file.absolutePath).size)
        }
    }

    @Test
    fun `half written tail is truncated and excluded from repaired count`() {
        withCrashFile(completeSamples = 2, partialBytes = 8) { file ->
            val result = BinaryTelemetryRecovery.repair(file.absolutePath)

            assertEquals(2, result.sampleCount)
            assertEquals(8L, result.truncatedBytes)
            assertEquals(
                (GpsBinaryFormat.HEADER_SIZE + 2 * GpsBinaryFormat.SAMPLE_SIZE).toLong(),
                file.length(),
            )
            assertEquals(2, PerformanceTestTelemetryReader.read(file.absolutePath).size)
        }
    }

    @Test
    fun `second recovery is idempotent`() {
        withCrashFile(completeSamples = 1, partialBytes = 5) { file ->
            assertTrue(BinaryTelemetryRecovery.repair(file.absolutePath).repaired)
            val second = BinaryTelemetryRecovery.repair(file.absolutePath)
            assertFalse(second.repaired)
            assertEquals(1, second.sampleCount)
            assertEquals(0L, second.truncatedBytes)
        }
    }

    private fun withCrashFile(completeSamples: Int, partialBytes: Int, block: (File) -> Unit) {
        val file = File.createTempFile("telemetry_crash", ".bin")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(
                    GpsBinaryFormat.encodeHeader(
                        TelemetrySessionType.LAP_SESSION,
                        sampleCount = 0,
                        startTs = startTs,
                        endTs = startTs,
                    )
                )
                repeat(completeSamples) { raf.write(GpsBinaryFormat.encodeSample(sample(it))) }
                if (partialBytes > 0) {
                    raf.write(GpsBinaryFormat.encodeSample(sample(completeSamples)), 0, partialBytes)
                }
                raf.channel.force(true)
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun sample(index: Int) = TelemetrySample(
        tsDeltaMs = index * 40L,
        lat = 30.0 + index,
        lon = 104.0,
        speedKmh = 80.0,
        bearingDeg = null,
    )

    private fun readHeader(file: File): GpsBinaryFormat.ChunkHeader =
        RandomAccessFile(file, "r").use { raf ->
            ByteArray(GpsBinaryFormat.HEADER_SIZE).also(raf::readFully)
                .let(GpsBinaryFormat::decodeHeader)
        }
}
