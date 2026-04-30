package com.blazepush.core.data.local.binary

import com.blazepush.core.domain.model.TelemetrySample
import com.blazepush.core.domain.model.TelemetrySessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * LapTelemetryReader 单元测试（A56 task 6.3）。
 * 验证 lap 时间窗口过滤：相邻窗口无遗漏 / 边界包含 / 截断 / 缺文件 5 个场景。
 *
 * @author CC
 * @description lap telemetry reader unit tests
 * @date 2026-04-30
 */
class LapTelemetryReaderTest {

    private lateinit var tempFile: File
    private lateinit var writer: BinaryTelemetryWriter

    // Session anchor: all tsDeltaMs are relative to this
    private val sessionStartTs = 1_700_000_000_000L
    private val type = TelemetrySessionType.LAP_SESSION

    /**
     * 每个 case 之前创建空临时文件 + 新 writer 实例。
     */
    @Before
    fun setup() {
        tempFile = File.createTempFile("lap_telemetry_test", ".bin")
        writer = BinaryTelemetryWriter()
    }

    /**
     * 清理临时文件。
     */
    @After
    fun teardown() {
        tempFile.delete()
    }

    private fun sampleAt(deltaMs: Long) = TelemetrySample(
        tsDeltaMs = deltaMs,
        lat = 39.9042 + deltaMs * 0.000001,
        lon = 116.4074,
        speedKmh = 100.0,
        bearingDeg = 0.0,
    )

    // Write N samples with tsDeltaMs = [0, 100, 200, ..., (N-1)*100]
    private suspend fun writeSession(count: Int) {
        writer.open(tempFile.absolutePath, type, sessionStartTs)
        repeat(count) { i -> writer.write(sampleAt(i * 100L)) }
        writer.close()
    }

    // 6.3.1 相邻两圈窗口无越界无遗漏
    @Test
    fun `adjacent lap windows cover all samples without overlap or gap`() = runTest {
        writeSession(10) // tsDeltaMs: 0, 100, ..., 900 → absolute: T, T+100, ..., T+900

        val lap1Start = sessionStartTs + 0
        val lap1End   = sessionStartTs + 400   // includes delta 0..400 → 5 samples

        val lap2Start = sessionStartTs + 500
        val lap2End   = sessionStartTs + 900   // includes delta 500..900 → 5 samples

        val lap1 = LapTelemetryReader.read(tempFile.absolutePath, lap1Start, lap1End)
        val lap2 = LapTelemetryReader.read(tempFile.absolutePath, lap2Start, lap2End)

        assertEquals(5, lap1.size)
        assertEquals(5, lap2.size)
        assertEquals(0L,   lap1.first().tsDeltaMs)
        assertEquals(400L, lap1.last().tsDeltaMs)
        assertEquals(500L, lap2.first().tsDeltaMs)
        assertEquals(900L, lap2.last().tsDeltaMs)

        // No sample appears in both windows
        val lap1Deltas = lap1.map { it.tsDeltaMs }.toSet()
        val lap2Deltas = lap2.map { it.tsDeltaMs }.toSet()
        assertTrue("No overlap expected", lap1Deltas.intersect(lap2Deltas).isEmpty())
    }

    // 6.3.2 窗口边界包含性：精确在 lapStartTs 和 lapEndTs 的样本被纳入
    @Test
    fun `samples exactly at window boundary are included`() = runTest {
        writeSession(3) // tsDeltaMs: 0, 100, 200

        val exact = LapTelemetryReader.read(
            tempFile.absolutePath,
            lapStartTs = sessionStartTs + 100,
            lapEndTs   = sessionStartTs + 100
        )
        assertEquals(1, exact.size)
        assertEquals(100L, exact[0].tsDeltaMs)
    }

    // 6.3.3 窗口外无样本时返回空列表
    @Test
    fun `window outside all samples returns empty`() = runTest {
        writeSession(5) // tsDeltaMs: 0, 100, 200, 300, 400

        val result = LapTelemetryReader.read(
            tempFile.absolutePath,
            lapStartTs = sessionStartTs + 1000,
            lapEndTs   = sessionStartTs + 2000
        )
        assertTrue(result.isEmpty())
    }

    // 6.3.4 header count > actual（文件截断）时按 actual 截断，窗口过滤正常
    @Test
    fun `truncated file - header inflated count - window filter still works`() = runTest {
        writeSession(6) // actual: 6 samples

        // Inflate header sampleCount to 20
        RandomAccessFile(tempFile, "rw").use { raf ->
            val fakeHeader = GpsBinaryFormat.encodeHeader(type, 20, sessionStartTs, sessionStartTs + 500)
            raf.seek(0)
            raf.write(fakeHeader)
        }

        // Should read only 6 actual samples, then filter by window
        val result = LapTelemetryReader.read(
            tempFile.absolutePath,
            lapStartTs = sessionStartTs + 200,
            lapEndTs   = sessionStartTs + 400
        )
        // tsDeltaMs 200, 300, 400 → 3 samples
        assertEquals(3, result.size)
        assertEquals(200L, result[0].tsDeltaMs)
        assertEquals(400L, result[2].tsDeltaMs)
    }

    // 6.3.5 空文件返回空列表
    @Test
    fun `empty or missing file returns empty list`() {
        val missing = File(tempFile.parent, "nonexistent.bin")
        assertTrue(LapTelemetryReader.read(missing.absolutePath, 0L, Long.MAX_VALUE).isEmpty())
    }
}