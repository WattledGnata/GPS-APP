package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 二进制 telemetry 编解码（A56 设计）。
 * 22-byte header + 17 bytes/sample，big-endian；bearing=0xFFFF 为无效哨兵。
 *
 * @author CC
 * @description binary telemetry codec
 * @date 2026-04-30
 */
internal object GpsBinaryFormat {
    const val HEADER_SIZE = 22
    const val SAMPLE_SIZE = 17
    private const val VERSION: Byte = 1
    private const val BEARING_INVALID = 0xFFFF

    /**
     * 编码 22-byte header：version(1) + type(1) + sampleCount(4) + startTs(8) + endTs(8)。
     */
    fun encodeHeader(
        type: TelemetrySessionType,
        sampleCount: Int,
        startTs: Long,
        endTs: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(VERSION)
        buf.put(type.ordinal.toByte())
        buf.putInt(sampleCount)
        buf.putLong(startTs)
        buf.putLong(endTs)
        return buf.array()
    }

    data class ChunkHeader(
        val type: TelemetrySessionType,
        val sampleCount: Int,
        val startTs: Long,
        val endTs: Long,
    )

    /**
     * 解码 header；type ordinal 越界时降级为 PERFORMANCE_TEST。
     */
    fun decodeHeader(bytes: ByteArray): ChunkHeader {
        val buf = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.get() // version – reserved
        val typeOrdinal = buf.get().toInt() and 0xFF
        val type = TelemetrySessionType.entries.getOrElse(typeOrdinal) { TelemetrySessionType.PERFORMANCE_TEST }
        val sampleCount = buf.int
        val startTs = buf.long
        val endTs = buf.long
        return ChunkHeader(type, sampleCount, startTs, endTs)
    }

    /**
     * 编码 17-byte sample：tsDelta(4) + lat(4) + lon(4) + speed(2) + bearing(2) + flags(1)。
     */
    fun encodeSample(sample: TelemetrySample): ByteArray {
        val buf = ByteBuffer.allocate(SAMPLE_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sample.tsDeltaMs.toInt())
        buf.putInt(Math.round(sample.lat * 1e7).toInt())
        buf.putInt(Math.round(sample.lon * 1e7).toInt())
        val speedU16 = Math.round(sample.speedKmh * 10).toInt().coerceIn(0, 65535)
        buf.putShort(speedU16.toShort())
        val bearingU16 = sample.bearingDeg
            ?.let { Math.round(it * 10).toInt().coerceIn(0, 3599) }
            ?: BEARING_INVALID
        buf.putShort(bearingU16.toShort())
        buf.put(sample.flags.toByte())
        return buf.array()
    }

    /**
     * 解码 sample；bearing=0xFFFF 视为无效返回 null。
     */
    fun decodeSample(bytes: ByteArray, offset: Int): TelemetrySample {
        val buf = ByteBuffer.wrap(bytes, offset, SAMPLE_SIZE).order(ByteOrder.BIG_ENDIAN)
        val tsDeltaMs = buf.int.toLong() and 0xFFFFFFFFL
        val latI32 = buf.int
        val lonI32 = buf.int
        val speedU16 = buf.short.toInt() and 0xFFFF
        val bearingU16 = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        return TelemetrySample(
            tsDeltaMs = tsDeltaMs,
            lat = latI32 / 1e7,
            lon = lonI32 / 1e7,
            speedKmh = speedU16 / 10.0,
            bearingDeg = if (bearingU16 == BEARING_INVALID) null else bearingU16 / 10.0,
            flags = flags,
        )
    }

    /**
     * 由文件总长反推有效 sample 数量（截断半条 sample）。
     */
    fun validSampleCount(fileSize: Long): Int =
        if (fileSize <= HEADER_SIZE) 0
        else ((fileSize - HEADER_SIZE) / SAMPLE_SIZE).toInt()
}