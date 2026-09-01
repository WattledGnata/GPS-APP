package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.PerformanceResultWindow
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.core.domain.usecase.PerformanceResultWindowExtractor
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

    /**
     * 读取成绩唯一窗口。新记录优先使用持久化索引/精确时间；旧记录只读重识别最后完整窗口。
     */
    fun readResultPoints(
        filePath: String,
        template: TestTemplate,
        window: PerformanceResultWindow?,
    ): List<GpsDataPoint> = toResultPoints(read(filePath), template, window)

    internal fun toResultPoints(
        samples: List<TelemetrySample>,
        template: TestTemplate,
        window: PerformanceResultWindow?,
    ): List<GpsDataPoint> {
        if (samples.size < 2) return emptyList()
        if (window != null && window.algorithmVersion > 0) {
            pointsFromPersistedWindow(samples, window)?.let { return it }
        }
        val origin = samples.first().tsDeltaMs
        val rawPoints = samples.map { sample -> sample.toGpsDataPoint(origin) }
        return PerformanceResultWindowExtractor.extract(rawPoints, template)?.dataPoints.orEmpty()
    }

    private fun pointsFromPersistedWindow(
        samples: List<TelemetrySample>,
        window: PerformanceResultWindow,
    ): List<GpsDataPoint>? {
        if (window.startSampleIndex !in samples.indices || window.endSampleIndex !in samples.indices) return null
        if (window.startSampleIndex >= window.endSampleIndex || window.startDeltaMs >= window.endDeltaMs) return null
        val origin = samples.first().tsDeltaMs
        val startAbsolute = origin + window.startDeltaMs
        val endAbsolute = origin + window.endDeltaMs
        val startLeft = samples[window.startSampleIndex]
        val startRight = samples.getOrNull(window.startSampleIndex + 1) ?: return null
        val endRight = samples[window.endSampleIndex]
        val endLeft = samples.getOrNull(window.endSampleIndex - 1) ?: return null
        if (startAbsolute !in startLeft.tsDeltaMs..startRight.tsDeltaMs) return null
        if (endAbsolute !in endLeft.tsDeltaMs..endRight.tsDeltaMs) return null

        val preciseStart = interpolateAtTime(startLeft, startRight, startAbsolute)
        val preciseEnd = interpolateAtTime(endLeft, endRight, endAbsolute)
        return buildList {
            add(preciseStart.toGpsDataPoint(startAbsolute))
            samples.asSequence()
                .drop(window.startSampleIndex + 1)
                .take(window.endSampleIndex - window.startSampleIndex - 1)
                .filter { it.tsDeltaMs > startAbsolute && it.tsDeltaMs < endAbsolute }
                .forEach { add(it.toGpsDataPoint(startAbsolute)) }
            add(preciseEnd.toGpsDataPoint(startAbsolute))
        }
    }

    private fun interpolateAtTime(left: TelemetrySample, right: TelemetrySample, timestamp: Long): TelemetrySample {
        if (right.tsDeltaMs <= left.tsDeltaMs) return left.copy(tsDeltaMs = timestamp)
        val ratio = (timestamp - left.tsDeltaMs).toDouble() / (right.tsDeltaMs - left.tsDeltaMs)
        return TelemetrySample(
            tsDeltaMs = timestamp,
            lat = left.lat + ratio * (right.lat - left.lat),
            lon = left.lon + ratio * (right.lon - left.lon),
            speedKmh = left.speedKmh + ratio * (right.speedKmh - left.speedKmh),
            bearingDeg = left.bearingDeg,
            flags = left.flags,
        )
    }

    private fun TelemetrySample.toGpsDataPoint(originMs: Long) = GpsDataPoint(
        elapsedTime = (tsDeltaMs - originMs) / 1000.0,
        speed = speedKmh,
        latitude = lat,
        longitude = lon,
        altitude = 0.0,
    )
}
