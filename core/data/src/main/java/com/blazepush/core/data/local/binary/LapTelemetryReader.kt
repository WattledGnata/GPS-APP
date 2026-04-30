package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import java.io.RandomAccessFile

/**
 * 圈速 session 时间窗口读取器：从 binary 文件按 [lapStartTs, lapEndTs] 过滤 sample。
 *
 * @author CC
 * @description lap session time-window telemetry reader
 * @date 2026-04-30
 */
object LapTelemetryReader {

    /**
     * 读取并过滤；文件不存在或截断时返回空 list。
     */
    fun read(filePath: String, lapStartTs: Long, lapEndTs: Long): List<TelemetrySample> {
        val file = java.io.File(filePath)
        if (!file.exists() || file.length() < GpsBinaryFormat.HEADER_SIZE) return emptyList()

        return RandomAccessFile(filePath, "r").use { raf ->
            val headerBytes = ByteArray(GpsBinaryFormat.HEADER_SIZE)
            raf.readFully(headerBytes)
            val header = GpsBinaryFormat.decodeHeader(headerBytes)

            val actualCount = GpsBinaryFormat.validSampleCount(file.length())
            val validCount = minOf(header.sampleCount, actualCount)
            if (validCount <= 0) return emptyList()

            val sampleBytes = ByteArray(validCount * GpsBinaryFormat.SAMPLE_SIZE)
            raf.readFully(sampleBytes)

            val sessionStartTs = header.startTs
            (0 until validCount)
                .map { i -> GpsBinaryFormat.decodeSample(sampleBytes, i * GpsBinaryFormat.SAMPLE_SIZE) }
                .filter { sample ->
                    val absoluteTs = sessionStartTs + sample.tsDeltaMs
                    absoluteTs in lapStartTs..lapEndTs
                }
        }
    }
}