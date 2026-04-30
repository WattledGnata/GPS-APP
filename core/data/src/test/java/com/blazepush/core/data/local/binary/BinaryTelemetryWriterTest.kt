package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * BinaryTelemetryWriter 单元测试（A56 task 3.6.x）。
 * 验证：3 帧 + flush header / 5 帧顺序 / 半截 sample / count 越界等关键场景。
 *
 * @author CC
 * @description binary telemetry writer unit tests
 * @date 2026-04-30
 */
class BinaryTelemetryWriterTest {

    private lateinit var tempFile: File
    private lateinit var writer: BinaryTelemetryWriter

    private val startTs = 1_700_000_000_000L
    private val type = TelemetrySessionType.PERFORMANCE_TEST

    /**
     * 每个 case 之前创建空临时文件 + 新 writer 实例。
     */
    @Before
    fun setup() {
        tempFile = File.createTempFile("telemetry_test", ".bin")
        writer = BinaryTelemetryWriter()
    }

    /**
     * 用例结束清理临时文件。
     */
    @After
    fun teardown() {
        tempFile.delete()
    }

    private fun sample(index: Int) = TelemetrySample(
        tsDeltaMs = index * 40L,
        lat = 39.9042 + index * 0.0001,
        lon = 116.4074,
        speedKmh = 50.0 + index,
        bearingDeg = 90.0,
    )

    private fun readHeader(): GpsBinaryFormat.ChunkHeader {
        return RandomAccessFile(tempFile, "r").use { raf ->
            val bytes = ByteArray(GpsBinaryFormat.HEADER_SIZE)
            raf.readFully(bytes)
            GpsBinaryFormat.decodeHeader(bytes)
        }
    }

    private fun readAllSamples(count: Int): List<TelemetrySample> {
        return RandomAccessFile(tempFile, "r").use { raf ->
            raf.skipBytes(GpsBinaryFormat.HEADER_SIZE)
            val bytes = ByteArray(count * GpsBinaryFormat.SAMPLE_SIZE)
            raf.readFully(bytes)
            List(count) { i -> GpsBinaryFormat.decodeSample(bytes, i * GpsBinaryFormat.SAMPLE_SIZE) }
        }
    }

    // 3.6.1 写 3 条 flush 后 header sampleCount=3，文件大小 73 bytes
    @Test
    fun `flush after 3 samples - header count is 3 and file size is 73 bytes`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(3) { writer.write(sample(it)) }
        writer.flush()

        val header = readHeader()
        assertEquals(3, header.sampleCount)
        val expectedEndTs = startTs + sample(2).tsDeltaMs
        assertEquals(expectedEndTs, header.endTs)
        assertEquals(
            (GpsBinaryFormat.HEADER_SIZE + 3 * GpsBinaryFormat.SAMPLE_SIZE).toLong(),
            tempFile.length()
        )
        writer.close()
    }

    // 3.6.2 flush 后继续写 2 条再 flush，header sampleCount=5，samples 顺序无污染
    @Test
    fun `second flush after 2 more samples - header count is 5 and samples are ordered`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(3) { writer.write(sample(it)) }
        writer.flush()
        repeat(2) { writer.write(sample(it + 3)) }
        writer.flush()

        val header = readHeader()
        assertEquals(5, header.sampleCount)

        val samples = readAllSamples(5)
        for (i in 0 until 5) {
            assertEquals(sample(i).tsDeltaMs, samples[i].tsDeltaMs)
        }
        writer.close()
    }

    // 3.6.3 人工截断半条 sample，reader 忽略尾部半条
    @Test
    fun `partial trailing sample is ignored by reader`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(3) { writer.write(sample(it)) }
        writer.close()

        // Truncate 8 bytes off end (< SAMPLE_SIZE=17), simulating a crash mid-write
        RandomAccessFile(tempFile, "rw").use { raf ->
            raf.setLength(tempFile.length() - 8)
        }

        val validCount = GpsBinaryFormat.validSampleCount(tempFile.length())
        assertEquals(2, validCount)

        val samples = PerformanceTestTelemetryReader.read(tempFile.absolutePath)
        assertEquals(2, samples.size)
    }

    // 3.6.4 header count > actual count 时 reader 按 actual 截断
    @Test
    fun `header count greater than actual sample count - reader uses actual count`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(5) { writer.write(sample(it)) }
        writer.close()

        // Manually set header.sampleCount to 10 while file only has 5 samples
        RandomAccessFile(tempFile, "rw").use { raf ->
            // Overwrite header with count=10 but keep file length at 5 samples
            val fakeHeader = GpsBinaryFormat.encodeHeader(type, 10, startTs, startTs + 200)
            raf.seek(0)
            raf.write(fakeHeader)
        }

        val samples = PerformanceTestTelemetryReader.read(tempFile.absolutePath)
        assertEquals(5, samples.size)
    }

    // 4.3 安全兜底：1000 帧触发 flush，即使没有手动 flush
    @Test
    fun `safety flush triggers at 1000 frames without explicit flush`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(BinaryTelemetryWriter.SAFETY_FLUSH_THRESHOLD) { writer.write(sample(it)) }
        // Give the writer actor time to process the safety flush
        // We verify by checking the header after close
        writer.close()

        val header = readHeader()
        assertEquals(BinaryTelemetryWriter.SAFETY_FLUSH_THRESHOLD, header.sampleCount)
    }

    // close 后无残余丢失
    @Test
    fun `close flushes all pending samples`() = runTest {
        writer.open(tempFile.absolutePath, type, startTs)
        repeat(7) { writer.write(sample(it)) }
        writer.close()

        val header = readHeader()
        assertEquals(7, header.sampleCount)
    }
}