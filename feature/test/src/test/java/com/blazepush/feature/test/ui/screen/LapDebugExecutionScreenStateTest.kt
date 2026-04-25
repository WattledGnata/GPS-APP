package com.blazepush.feature.test.ui.screen

import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.ActiveLap
import com.blazepush.feature.test.model.laptiming.CrossingEvent
import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGateType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapDebugExecutionScreenStateTest {

    @Test
    fun toLapDebugTelemetry_withoutSessionSamples_returnsZeroGAndCurrentTelemetry() {
        val gpsData = gpsData(speed = 108.0, bearing = 90.0)

        val telemetry = gpsData.toLapDebugTelemetry(previousSample = null)

        assertEquals(108.0, telemetry.speedKmh, 0.0001)
        assertEquals(90.0, telemetry.bearingDegrees, 0.0001)
        assertEquals(0.0, telemetry.forwardG, 0.0001)
        assertEquals(0.0, telemetry.lateralG, 0.0001)
    }

    @Test
    fun toLapDebugTelemetry_withSessionSamples_computesForwardAndLateralG() {
        val lapSession = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                GpsSample(timestampMillis = 1_000L, latitude = 0.0, longitude = 0.0, speedKmh = 36.0, bearingDegrees = 0.0),
                GpsSample(timestampMillis = 2_000L, latitude = 0.0, longitude = 0.0, speedKmh = 72.0, bearingDegrees = 90.0)
            )
        )

        val telemetry = gpsData(speed = 72.0, bearing = 90.0).toLapDebugTelemetry(lapSession)

        assertEquals(72.0, telemetry.speedKmh, 0.0001)
        assertEquals(90.0, telemetry.bearingDegrees, 0.0001)
        assertEquals(1.0197, telemetry.forwardG, 0.0005)
        assertEquals(3.2036, telemetry.lateralG, 0.0005)
    }

    @Test
    fun rememberStartFinishTimingCardState_withoutAcceptedStartFinishCrossing_keepsWaitingState() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            crossingEvents = listOf(
                crossingEvent(timestampMillis = 1_000L, accepted = false, gateType = TimingGateType.StartFinish),
                crossingEvent(timestampMillis = 2_000L, accepted = true, gateType = TimingGateType.Sector)
            )
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("--", state.lastLapElapsedLabel)
        assertEquals("0.000 s", state.currentLapElapsedLabel)
        assertEquals("0.0 m", state.currentLapDistanceLabel)
        assertEquals("--", state.lastStartFinishTimeLabel)
        assertEquals("等待起点", state.statusLabel)
    }

    @Test
    fun rememberStartFinishTimingCardState_withFirstAcceptedStartFinishCrossing_reportsCurrentLapSummary() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 4_500L, latitude = 39.000000, longitude = 116.000170)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 3_000L)
            ),
            activeLap = activeLap(
                startedAtMillis = 3_000L,
                sampleStartIndex = 0,
                distanceMetersSinceStart = 14.7,  // A22：UI 改读 engine 字段，由 helper 显式传入
            )
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("--", state.lastLapElapsedLabel)
        assertEquals("1.500 s", state.currentLapElapsedLabel)
        assertEquals("14.7 m", state.currentLapDistanceLabel)
        assertEquals(formatExpectedTimeOfDay(3_000L), state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    @Test
    fun rememberStartFinishTimingCardState_withSecondAcceptedStartFinishCrossing_reportsFixedSummaryFields() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 32_529_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 32_533_000L, latitude = 39.000000, longitude = 116.000000),
                gpsSample(timestampMillis = 32_533_625L, latitude = 39.000000, longitude = 116.000188),
                gpsSample(timestampMillis = 32_534_250L, latitude = 39.000000, longitude = 116.000375)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 32_529_000L),
                crossingEvent(timestampMillis = 32_531_500L, accepted = false, gateType = TimingGateType.StartFinish),
                acceptedStartFinishCrossing(timestampMillis = 32_533_000L)
            ),
            activeLap = activeLap(
                startedAtMillis = 32_533_000L,
                sampleStartIndex = 1,
                distanceMetersSinceStart = 32.4,  // A22：UI 改读 engine 字段
            )
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("4.000 s", state.lastLapElapsedLabel)
        assertEquals("1.250 s", state.currentLapElapsedLabel)
        assertEquals("32.4 m", state.currentLapDistanceLabel)
        assertEquals(formatExpectedTimeOfDay(32_533_000L), state.lastStartFinishTimeLabel)
        assertEquals("当前圈进行中", state.statusLabel)
    }

    @Test
    fun statusLabel_showsWaitingForTimeSync_whenUpstreamUnsynced() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            activeLap = null
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = false)

        assertEquals("等待协议时间同步", state.statusLabel)
    }

    @Test
    fun statusLabel_showsWaitingForStart_whenSyncedButNoActiveLap() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Ready,
            activeLap = null
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        assertEquals("等待起点", state.statusLabel)
    }

    @Test
    fun statusLabel_showsInLap_regardlessOfTimeSync() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.000000, longitude = 116.000000)
            ),
            crossingEvents = listOf(
                acceptedStartFinishCrossing(timestampMillis = 3_000L)
            ),
            activeLap = activeLap(startedAtMillis = 3_000L, sampleStartIndex = 0)
        )

        val stateSynced = rememberStartFinishTimingCardState(session, isTimeSynced = true)
        val stateUnsynced = rememberStartFinishTimingCardState(session, isTimeSynced = false)

        assertEquals("当前圈进行中", stateSynced.statusLabel)
        assertEquals("当前圈进行中", stateUnsynced.statusLabel)
    }

    private fun formatExpectedTimeOfDay(timestampMillis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))

    private fun gpsData(speed: Double, bearing: Double) = GpsData(
        timestamp = 2_000L,
        speed = speed,
        latitude = 0.0,
        longitude = 0.0,
        altitude = 0.0,
        bearing = bearing,
        satelliteCount = 0,
        hdop = 0.0,
        vdop = 0.0,
        frequency = 10.0,
        isConnected = true,
        isTestReady = true,
        errorMessage = null,
        fixQuality = 1
    )

    private fun acceptedStartFinishCrossing(timestampMillis: Long) = crossingEvent(
        timestampMillis = timestampMillis,
        accepted = true,
        gateType = TimingGateType.StartFinish
    )

    private fun gpsSample(timestampMillis: Long, latitude: Double, longitude: Double) = GpsSample(
        timestampMillis = timestampMillis,
        latitude = latitude,
        longitude = longitude
    )

    private fun crossingEvent(timestampMillis: Long, accepted: Boolean, gateType: TimingGateType) = CrossingEvent(
        gateId = "gate-$timestampMillis",
        gateType = gateType,
        timestampMillis = timestampMillis,
        sampleIndex = 0,
        accepted = accepted,
        reason = if (accepted) CrossingReason.Accepted else CrossingReason.NoIntersection
    )

    // A22 change fix-active-lap-distance-accumulator：helper 加 distanceMetersSinceStart
    // 参数（default 0.0 让其他不关心 distance 的旧测试零迁移），UI 改读 engine 字段后
    // distance label 由调用方显式传入而非 UI 自算。
    private fun activeLap(
        startedAtMillis: Long,
        sampleStartIndex: Int,
        distanceMetersSinceStart: Double = 0.0,
    ) = ActiveLap(
        lapIndex = 0,
        startedAtMillis = startedAtMillis,
        passedGateIds = emptyList(),
        sectorEntries = emptyList(),
        sampleStartIndex = sampleStartIndex,
        distanceMetersSinceStart = distanceMetersSinceStart,
    )

    // ==================== A22 change fix-active-lap-distance-accumulator ====================
    //
    // §5.4 UI consumer + 7500 samples 性能 smoke / §5.5 源码 grep 自检 / spec R3 R4 R5

    /** §5.4.1 UI 读 engine 字段 */
    @Test
    fun rememberStartFinishTimingCardState_readsEngineDistanceField() {
        val session = LapSession(
            sessionId = "session-1",
            trackId = "track-1",
            status = LapSessionStatus.Recording,
            samples = listOf(
                gpsSample(timestampMillis = 3_000L, latitude = 39.0, longitude = 116.0)
            ),
            crossingEvents = listOf(acceptedStartFinishCrossing(timestampMillis = 3_000L)),
            activeLap = activeLap(
                startedAtMillis = 3_000L,
                sampleStartIndex = 0,
                distanceMetersSinceStart = 1234.5,
            ),
        )

        val state = rememberStartFinishTimingCardState(session, isTimeSynced = true)

        // formatDistanceMeters(1234.5) = "1234.5 m"（Locale.US 一位小数）
        assertEquals("1234.5 m", state.currentLapDistanceLabel)
    }

    /** §5.4.2 7500 samples 性能 smoke < 16ms（三层防抖） */
    @Test
    fun rememberStartFinishTimingCardState_with7500Samples_completesUnder16msMedian() {
        val session = build7500SampleSession()

        // warm-up：JIT 预热 10 次，丢弃测量
        repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }

        // measure：5 次外层取中位，每次内 loop 10 次取均值（ns / 10）
        val measuredNsPerCall = (1..5).map {
            val start = System.nanoTime()
            repeat(10) { rememberStartFinishTimingCardState(session, isTimeSynced = true) }
            (System.nanoTime() - start) / 10
        }.sorted()
        val medianNs = measuredNsPerCall[2]
        val medianMs = medianNs / 1_000_000.0

        assertTrue(
            "7500 samples median ${medianMs}ms 应 < 16ms（60fps 帧预算）。" +
                "硬区分 v1：v1 全量 haversine 7500 × 4 trig + filter ≈ 37500 ops 接近或超阈值；" +
                "v2 单字段读 O(1) 预期 < 1ms，留 16x 间隙",
            medianMs < 16.0,
        )
    }

    /** §5.5.1 UI 源码不再含 calculateDistanceSince / 私有 haversineDistanceMeters */
    @Test
    fun ui_sourceDoesNotContainDistanceCalculationFunctions() {
        val source = File("src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt").readText()
        assertFalse(
            "UI 不应含 calculateDistanceSince（A22 已删）",
            source.contains("calculateDistanceSince"),
        )
        assertFalse(
            "UI 不应含 private fun haversineDistanceMeters（A22 已迁到 GeoMath）",
            source.contains("private fun haversineDistanceMeters"),
        )
    }

    /** §5.5.2 UI 源码不再做 distance pattern 计算 */
    @Test
    fun ui_sourceDoesNotContainDistancePatternCalculations() {
        val source = File("src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt").readText()
        assertFalse(
            "UI 不应含 samples.zipWithNext（O(N) distance pattern）",
            source.contains("samples.zipWithNext"),
        )
        // samples.lastOrNull()?.timestampMillis 仍合法（O(1) 单帧 ts，非距离 pattern），不在断言范围
        val patternRegex = Regex("""samples\.filter \{[^}]*timestampMillis""")
        assertFalse(
            "UI 不应含 samples.filter { ... timestampMillis ... } distance pattern",
            patternRegex.containsMatchIn(source),
        )
    }

    /**
     * §5.5.3 engine handleSectorCrossing 用 activeLapWithDistance!!.copy( 派生
     * + 反向禁止旧本地变量 activeLap.copy(（Review v1 P1-2 修补）
     */
    @Test
    fun engine_handleSectorCrossing_sourceUsesActiveLapWithDistanceCopyAndForbidsLocalActiveLapCopy() {
        val source = File("src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt").readText()
        val handleSectorStart = source.indexOf("private fun handleSectorCrossing")
        assertTrue("handleSectorCrossing 函数应存在", handleSectorStart > 0)
        val handleSectorBody = source.substring(
            handleSectorStart,
            source.length.coerceAtMost(handleSectorStart + 4000),
        )

        // 正向断言
        assertTrue(
            "handleSectorCrossing 必须含 activeLapWithDistance!!.copy( 用于 sector accepted 派生",
            handleSectorBody.contains("activeLapWithDistance!!.copy("),
        )
        assertTrue(
            "handleSectorCrossing 必须有 activeLapWithDistance: ActiveLap? 参数",
            handleSectorBody.contains("activeLapWithDistance: ActiveLap?"),
        )

        // 反向禁止：word boundary 锁本地变量 copy
        val forbiddenLocalActiveLapCopy = Regex("""\bactiveLap\.copy\(""")
        assertFalse(
            "handleSectorCrossing 不应再用本地 `activeLap.copy(` 派生（A22 必须走 activeLapWithDistance!!.copy）",
            forbiddenLocalActiveLapCopy.containsMatchIn(handleSectorBody),
        )

        // 防御性同步禁止
        assertFalse(
            "handleSectorCrossing 不应用 session.activeLap.copy(",
            handleSectorBody.contains("session.activeLap.copy("),
        )
    }

    /** §5.5.4 distanceMetersSinceStart 写入仅在 engine + ActiveLap 字段定义 */
    @Test
    fun distanceMetersSinceStart_writtenOnlyByEngineAndDataClassDefault() {
        val mainDir = File("src/main/java/com/blazepush/feature/test")
        val violations = mainDir.walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val name = f.absolutePath
                !name.endsWith("ActiveLap.kt") && !name.endsWith("LapTimingEngine.kt")
            }
            .filter { f -> Regex("""distanceMetersSinceStart\s*=""").containsMatchIn(f.readText()) }
            .map { it.name }
            .toList()
        assertTrue(
            "distanceMetersSinceStart 写入仅应位于 ActiveLap.kt（default）+ LapTimingEngine.kt（producer），" +
                "实际命中其他文件：$violations",
            violations.isEmpty(),
        )
    }

    /** §5.4.2 helper：构造 7500 samples 的 LapSession + 预填 distance */
    private fun build7500SampleSession(): LapSession {
        val samples = (0 until 7500).map { i ->
            gpsSample(
                timestampMillis = 3_000L + i * 40L,  // 25Hz
                latitude = 39.0 + i * 0.000001,
                longitude = 116.0 + i * 0.000001,
            )
        }
        return LapSession(
            sessionId = "session-perf",
            trackId = "track-perf",
            status = LapSessionStatus.Recording,
            samples = samples,
            crossingEvents = listOf(acceptedStartFinishCrossing(timestampMillis = 3_000L)),
            activeLap = activeLap(
                startedAtMillis = 3_000L,
                sampleStartIndex = 0,
                distanceMetersSinceStart = 1234.5,
            ),
        )
    }
}
