package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GpsBinaryFormat 单元测试（A56 task 2.3）。
 * 验证 17-byte sample 编解码精度 + bearing 哨兵 + 22-byte header 往返 + sample count 截断。
 *
 * @author CC
 * @description binary codec unit tests
 * @date 2026-04-30
 */
class GpsBinaryFormatTest {

    private fun sample(
        tsDeltaMs: Long = 0L,
        lat: Double = 39.9042,
        lon: Double = 116.4074,
        speedKmh: Double = 100.0,
        bearingDeg: Double? = 45.0,
        flags: Int = 0,
    ) = TelemetrySample(tsDeltaMs, lat, lon, speedKmh, bearingDeg, flags)

    @Test
    fun `encode decode roundtrip preserves precision`() {
        val s = sample(tsDeltaMs = 1234L, lat = 39.9042, lon = 116.4074, speedKmh = 100.0, bearingDeg = 45.0)
        val bytes = GpsBinaryFormat.encodeSample(s)
        assertEquals(GpsBinaryFormat.SAMPLE_SIZE, bytes.size)

        val decoded = GpsBinaryFormat.decodeSample(bytes, 0)
        assertEquals(1234L, decoded.tsDeltaMs)
        assertEquals(399042000, Math.round(decoded.lat * 1e7).toInt())
        assertEquals(1164074000, Math.round(decoded.lon * 1e7).toInt())
        assertEquals(1000, Math.round(decoded.speedKmh * 10).toInt())
        val bearing = decoded.bearingDeg
        assertNotNull(bearing)
        assertEquals(450, Math.round((bearing ?: 0.0) * 10).toInt())
    }

    @Test
    fun `invalid bearing encodes to 0xFFFF and decodes to null`() {
        val s = sample(bearingDeg = null)
        val bytes = GpsBinaryFormat.encodeSample(s)
        val decoded = GpsBinaryFormat.decodeSample(bytes, 0)
        assertNull(decoded.bearingDeg)
    }

    @Test
    fun `valid bearing 0 degrees encodes and decodes correctly`() {
        val s = sample(bearingDeg = 0.0)
        val decoded = GpsBinaryFormat.decodeSample(GpsBinaryFormat.encodeSample(s), 0)
        assertEquals(0.0, decoded.bearingDeg ?: error("bearing should be non-null"), 0.1)
    }

    @Test
    fun `valid bearing 359_9 encodes and decodes correctly`() {
        val s = sample(bearingDeg = 359.9)
        val decoded = GpsBinaryFormat.decodeSample(GpsBinaryFormat.encodeSample(s), 0)
        assertEquals(359.9, decoded.bearingDeg ?: error("bearing should be non-null"), 0.1)
    }

    @Test
    fun `header encode decode roundtrip`() {
        val encoded = GpsBinaryFormat.encodeHeader(
            TelemetrySessionType.LAP_SESSION,
            sampleCount = 42,
            startTs = 1_700_000_000_000L,
            endTs = 1_700_000_300_000L,
        )
        assertEquals(GpsBinaryFormat.HEADER_SIZE, encoded.size)

        val header = GpsBinaryFormat.decodeHeader(encoded)
        assertEquals(TelemetrySessionType.LAP_SESSION, header.type)
        assertEquals(42, header.sampleCount)
        assertEquals(1_700_000_000_000L, header.startTs)
        assertEquals(1_700_000_300_000L, header.endTs)
    }

    @Test
    fun `validSampleCount returns correct count`() {
        val fileSize = GpsBinaryFormat.HEADER_SIZE + 3L * GpsBinaryFormat.SAMPLE_SIZE
        assertEquals(3, GpsBinaryFormat.validSampleCount(fileSize))
    }

    @Test
    fun `validSampleCount ignores partial trailing sample`() {
        val fileSize = GpsBinaryFormat.HEADER_SIZE + 3L * GpsBinaryFormat.SAMPLE_SIZE + 5
        assertEquals(3, GpsBinaryFormat.validSampleCount(fileSize))
    }

    @Test
    fun `validSampleCount returns 0 for header-only file`() {
        assertEquals(0, GpsBinaryFormat.validSampleCount(GpsBinaryFormat.HEADER_SIZE.toLong()))
    }
}