package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import java.io.RandomAccessFile

/**
 * 加减速测试 session 全文件读取器：1 session = 1 chunk file，顺序解码所有 sample。
 *
 * @author CC
 * @description performance test telemetry reader
 * @date 2026-04-30
 */
object PerformanceTestTelemetryReader {

    /**
     * 读取整个文件 sample；文件不存在或截断时返回空 list。
     */
    fun read(filePath: String): List<TelemetrySample> {
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

            List(validCount) { i ->
                GpsBinaryFormat.decodeSample(sampleBytes, i * GpsBinaryFormat.SAMPLE_SIZE)
            }
        }
    }
}