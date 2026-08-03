package com.blazepush.core.data.local.binary

import java.io.File
import java.io.RandomAccessFile

/** Repairs the durable prefix of a telemetry file left open by a dead process. */
object BinaryTelemetryRecovery {
    data class Result(
        val sampleCount: Int,
        val truncatedBytes: Long,
        val repaired: Boolean,
    )

    /**
     * Keeps only complete samples, derives count/end time from that prefix, rewrites the header,
     * and forces both the truncate and header update to stable storage before returning.
     */
    fun repair(filePath: String): Result {
        val file = File(filePath)
        if (!file.exists() || file.length() < GpsBinaryFormat.HEADER_SIZE) {
            return Result(sampleCount = 0, truncatedBytes = 0, repaired = false)
        }

        return RandomAccessFile(file, "rw").use { raf ->
            val headerBytes = ByteArray(GpsBinaryFormat.HEADER_SIZE)
            raf.readFully(headerBytes)
            val header = GpsBinaryFormat.decodeHeader(headerBytes)
            val sampleCount = GpsBinaryFormat.validSampleCount(raf.length())
            val durableLength = GpsBinaryFormat.HEADER_SIZE.toLong() +
                sampleCount.toLong() * GpsBinaryFormat.SAMPLE_SIZE
            val truncatedBytes = raf.length() - durableLength

            val endTs = if (sampleCount == 0) {
                header.startTs
            } else {
                val lastSampleBytes = ByteArray(GpsBinaryFormat.SAMPLE_SIZE)
                raf.seek(durableLength - GpsBinaryFormat.SAMPLE_SIZE)
                raf.readFully(lastSampleBytes)
                val lastSample = GpsBinaryFormat.decodeSample(lastSampleBytes, 0)
                runCatching { Math.addExact(header.startTs, lastSample.tsDeltaMs) }
                    .getOrDefault(header.startTs)
            }

            val repaired = truncatedBytes != 0L ||
                header.sampleCount != sampleCount ||
                header.endTs != endTs
            if (repaired) {
                if (truncatedBytes != 0L) raf.setLength(durableLength)
                raf.seek(0)
                raf.write(
                    GpsBinaryFormat.encodeHeader(
                        type = header.type,
                        sampleCount = sampleCount,
                        startTs = header.startTs,
                        endTs = endTs,
                    )
                )
                raf.channel.force(true)
            }
            Result(sampleCount, truncatedBytes, repaired)
        }
    }
}
