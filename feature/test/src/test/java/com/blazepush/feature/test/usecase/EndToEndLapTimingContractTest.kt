package com.blazepush.feature.test.usecase

import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.domain.model.GpsData
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.laptiming.LapQualityFlag
import com.blazepush.feature.test.model.laptiming.LapSession
import com.blazepush.feature.test.model.laptiming.LapSessionStatus
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.simulator.data.GpsDataGenerator
import com.blazepush.simulator.data.TestScenario
import com.blazepush.simulator.data.replay.ReplaySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 端到端圈速契约测试（fix-laptime-clock-source-integrity 合流硬门槛）。
 *
 * 构造 JVM 内完整链路：
 *   GpsDataGenerator.generateGps{Main,Time}Data (bytes)
 *     → RaceChronoParser.parseGps{Time,Main}Data → GpsData
 *     → 转换为 GpsSample
 *     → LapTimingEngine.processSample → LapSession
 *
 * 不经过真实 BLE，不经过 TestSessionViewModel。直接把三个 use case 串起来，
 * 在 JVM 里锁定"发射端时钟 → 接收端圈时"的误差契约。
 */
class EndToEndLapTimingContractTest {

    private val track: Track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))

    // --------------------------- 8.2 ---------------------------

    /**
     * Spec: STATIC 模式 10 秒圈时误差 ≤ 20ms
     *
     * fakeClock 严格 40ms 递增 250 次；帧 0 位于 gate 起点侧（off-gate 反向偏置）；
     * 帧 1 位于 gate 终点侧（正向偏置，完成第一次穿线）；帧 2..250 保持起点侧（静止
     * 避免任何额外穿线）；帧 251 再次跳到终点侧完成闭圈。
     *
     * 断言 completedLaps.size == 1，durationMillis ∈ [9_980, 10_020]。
     */
    @Test
    fun staticMode_lapDurationMatchesSenderClockDelta() {
        val fakeClock = FakeClock(startAtMillis = 0L)
        val pipeline = E2EPipeline(
            generator = GpsDataGenerator(
                scenario = TestScenario.STATIC,
                clock = fakeClock::now
            ),
            parser = RaceChronoParser(),
            engine = LapTimingEngine(),
            track = track
        )

        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        // 在 passDirection 正反方向各偏 0.25 个向量长度（与 LapTimingEngineTest.crossingSamples 同构）
        val offGateLat = centerLat - gate.passDirection.x * 0.25
        val offGateLon = centerLon - gate.passDirection.y * 0.25
        val throughGateLat = centerLat + gate.passDirection.x * 0.25
        val throughGateLon = centerLon + gate.passDirection.y * 0.25

        // 帧 0: 起点侧（off-gate）
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        pipeline.generator.setCurrentSpeed(36f)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)

        // 帧 1: 终点侧（第一次穿线）
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)

        // 帧 2..250: 回到起点侧后保持静止
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        for (i in 2..250) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = true)
        }

        // 帧 251: 再次跳到终点侧（闭圈）
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)

        val session = pipeline.session
        assertEquals("should close exactly 1 lap", 1, session.completedLaps.size)
        val lap = session.completedLaps.first()
        assertTrue(
            "durationMillis=${lap.durationMillis} must be in [9_980, 10_020]",
            lap.durationMillis in 9_980..10_020
        )
    }

    // --------------------------- 8.3 ---------------------------

    /**
     * Spec: REPLAY 模式圈时与 replay 时钟精确对齐（< 5ms）
     *
     * 采用 fake replay 替代完整 tianfu_track_replay_5hz.json 扫描，以控制实现
     * 复杂度：手工构造 10 帧 ReplaySample，`timestampMillis = 0, 200, 400, ..., 1800`，
     * 第 0 帧和第 9 帧位置构造"穿过起终点 gate"，断言 durationMillis ≈ 1800ms ± 5。
     */
    @Test
    fun replayMode_lapDurationMatchesReplayClock() {
        // Replay 场景下 clock 函数对 timestamp 编码无影响（走 replayTimestampMillis 分支），
        // 但仍提供一个稳定 fake clock 以符合单调时钟契约
        val fakeClock = FakeClock(startAtMillis = 1_000_000L)
        val pipeline = E2EPipeline(
            generator = GpsDataGenerator(
                scenario = TestScenario.REAL_TRACK_REPLAY,
                clock = fakeClock::now
            ),
            parser = RaceChronoParser(),
            engine = LapTimingEngine(),
            track = track
        )

        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offGateLat = centerLat - gate.passDirection.x * 0.25
        val offGateLon = centerLon - gate.passDirection.y * 0.25
        val throughGateLat = centerLat + gate.passDirection.x * 0.25
        val throughGateLon = centerLon + gate.passDirection.y * 0.25

        // replay 序列：ts 以 200ms 步长递增（5Hz，与真实 tianfu 资源一致）
        val tsSeries = (0..10).map { it * 200L }  // 共 11 个样本 [0, 200, ..., 2000]

        // 帧 0: off-gate 起点侧，ts=0
        pipeline.generator.applyReplaySample(
            makeReplaySample(tsSeries[0], offGateLat, offGateLon)
        )
        pipeline.feedFrame(sendTimePacket = true)

        // 帧 1: through-gate 终点侧，ts=200（第一次穿线）
        pipeline.generator.applyReplaySample(
            makeReplaySample(tsSeries[1], throughGateLat, throughGateLon)
        )
        pipeline.feedFrame(sendTimePacket = true)

        // 帧 2..9: off-gate 起点侧保持静止
        for (i in 2..9) {
            pipeline.generator.applyReplaySample(
                makeReplaySample(tsSeries[i], offGateLat, offGateLon)
            )
            pipeline.feedFrame(sendTimePacket = true)
        }

        // 帧 10: through-gate 终点侧，ts=2000（闭圈）
        pipeline.generator.applyReplaySample(
            makeReplaySample(tsSeries[10], throughGateLat, throughGateLon)
        )
        pipeline.feedFrame(sendTimePacket = true)

        val session = pipeline.session
        assertEquals("should close exactly 1 lap", 1, session.completedLaps.size)
        val lap = session.completedLaps.first()
        val expected = tsSeries[10] - tsSeries[1]  // = 1800ms
        val deltaAbs = kotlin.math.abs(lap.durationMillis - expected)
        assertTrue(
            "durationMillis=${lap.durationMillis} vs expected=$expected, delta=$deltaAbs should be < 5ms",
            deltaAbs < 5L
        )
    }

    // --------------------------- 8.4 ---------------------------

    /**
     * Spec: 冷启动仅发主包不发时间包时 engine 不开圈
     *
     * 连续 50 帧只调用 generateGpsMainData()，parser 输出 isTimeSynced=false、
     * timestamp=Long.MIN_VALUE；bridge 守卫（E2EPipeline 复刻）拒收；engine
     * samples/activeLap/completedLaps 全空。
     */
    @Test
    fun coldStartOnlyMainNoTimePacket_engineDoesNotStartLap() {
        val fakeClock = FakeClock(startAtMillis = 0L)
        val pipeline = E2EPipeline(
            generator = GpsDataGenerator(
                scenario = TestScenario.STATIC,
                clock = fakeClock::now
            ),
            parser = RaceChronoParser(),
            engine = LapTimingEngine(),
            track = track
        )

        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        pipeline.generator.setCurrentPosition(centerLat, centerLon)

        // 50 帧只发主包，不发 time 包
        repeat(50) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = false)
        }

        // 全部 parser 输出均未同步
        assertTrue("at least 50 frames observed", pipeline.observedGpsData.size >= 50)
        pipeline.observedGpsData.forEach { gpsData ->
            assertFalse("isTimeSynced must be false when no time packet", gpsData.isTimeSynced)
            assertEquals(
                "timestamp must be Long.MIN_VALUE sentinel when unsynced",
                Long.MIN_VALUE,
                gpsData.timestamp
            )
        }

        val session = pipeline.session
        assertEquals("engine samples must remain empty", 0, session.samples.size)
        assertTrue("completedLaps must be empty", session.completedLaps.isEmpty())
        assertNull("activeLap must be null", session.activeLap)
    }

    // --------------------------- 8.5 ---------------------------

    /**
     * Spec: 中途 time 包丢失 3 帧后恢复
     *
     * 5 帧正常同步（off-gate 位置）→ 3 帧模拟 syncBits 失配（且故意构造跨 gate
     * 大位移以验证"detector 不会把这对 prev/current 判成一次过线"）→ 5 帧恢复同步。
     *
     * 断言：
     * - 失联 3 帧的 parser 输出 isTimeSynced=false, timestamp=Long.MIN_VALUE
     * - 失联帧不入 engine
     * - 恢复首帧走首样本分支（不入引擎）
     * - 未伪造开圈（activeLap 始终为 null）
     */
    @Test
    fun shortTimeDesyncRecoversWithoutSpuriousCrossing() {
        val fakeClock = FakeClock(startAtMillis = 0L)
        val pipeline = E2EPipeline(
            generator = GpsDataGenerator(
                scenario = TestScenario.STATIC,
                clock = fakeClock::now
            ),
            parser = RaceChronoParser(),
            engine = LapTimingEngine(),
            track = track
        )

        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offGateLat = centerLat - gate.passDirection.x * 0.25
        val offGateLon = centerLon - gate.passDirection.y * 0.25
        val throughGateLat = centerLat + gate.passDirection.x * 0.25
        val throughGateLon = centerLon + gate.passDirection.y * 0.25

        // 5 帧正常（位置 A，off-gate，不穿线）
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        repeat(5) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = true)
        }
        // 此阶段应累积 engine samples（位置静止，不穿线，不开圈）——但因所有帧
        // 位置相同，detector 永远 no intersect，activeLap 保持 null
        assertNull("no crossing yet → activeLap must be null", pipeline.session.activeLap)

        // 3 帧失联（强制 main 包 syncBits 失配，位置故意构造跨 gate 大位移）
        val desyncSyncedObservedBefore = pipeline.observedSyncedCount
        val desyncStartIdx = pipeline.observedGpsData.size
        // 第 1 帧：跳到 through-gate 一侧
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = false, forceSyncMismatch = true)
        // 第 2 帧：跳回 off-gate 另一侧
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = false, forceSyncMismatch = true)
        // 第 3 帧：再跳到 through-gate 侧（大位移跨 gate）
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = false, forceSyncMismatch = true)

        // 验证失联 3 帧的 parser 输出
        val desyncFrames = pipeline.observedGpsData.subList(desyncStartIdx, pipeline.observedGpsData.size)
        assertEquals(3, desyncFrames.size)
        desyncFrames.forEach { f ->
            assertFalse("desync frame isTimeSynced must be false", f.isTimeSynced)
            assertEquals(
                "desync frame timestamp must be Long.MIN_VALUE",
                Long.MIN_VALUE,
                f.timestamp
            )
        }
        // 失联期间 engine 未接收新 synced sample
        assertEquals(
            "desync frames must not grow engine's synced-sample count",
            desyncSyncedObservedBefore,
            pipeline.observedSyncedCount
        )

        // 关键反证：activeLap 始终为 null（即使失联 3 帧位置跨 gate，detector 也
        // 不能基于 sentinel ts 帧伪造开圈）
        assertNull(
            "activeLap must remain null — desync frames must not start a spurious lap",
            pipeline.session.activeLap
        )

        // 5 帧恢复同步（位置 B，off-gate，不穿线）
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        val preRecoverSamples = pipeline.session.samples.size
        val preRecoverSyncedCount = pipeline.observedSyncedCount
        repeat(5) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = true)
        }
        // 恢复后第 1 帧：走首样本分支（不入 engine），第 2..5 帧：4 帧进 engine
        val postRecoverSamples = pipeline.session.samples.size
        val postRecoverSyncedCount = pipeline.observedSyncedCount
        assertEquals(
            "recovery must observe 5 synced frames",
            preRecoverSyncedCount + 5,
            postRecoverSyncedCount
        )
        assertEquals(
            "recovery first frame skipped (首样本)，仅后 4 帧入 engine samples",
            preRecoverSamples + 4,
            postRecoverSamples
        )
        // 全程未伪造开圈
        assertNull(
            "activeLap must still be null after recovery (位置未穿 gate)",
            pipeline.session.activeLap
        )
    }

    // --------------------------- 8.6 ---------------------------

    /**
     * Spec: 端到端链路内核心时间戳派生路径不得访问 System.currentTimeMillis()
     *
     * Mockito 禁止 mock java.lang.System（会触发类加载无限递归，B/C 组均遇到），
     * 故采用字节码常量池扫描：对 `GpsDataGenerator` 与 `LapTimingEngine` 做 class
     * 常量池检查，断言二者都未引用 "currentTimeMillis"。
     *
     * 注意：`RaceChronoParser` 因频率统计与 tracking 路径仍调用
     * `System.currentTimeMillis()`（这些是与 GpsData.timestamp 派生无关的非时间戳路径，
     * B 组评审后保留），本测试豁免 parser 类；测试名已相应重命名为
     * `endToEndCoreClockSourceIntegrity_generatorAndEngineNotInvolveSystemClock`。
     */
    @Test
    fun endToEndCoreClockSourceIntegrity_generatorAndEngineNotInvolveSystemClock() {
        assertClassDoesNotReferenceCurrentTimeMillis(GpsDataGenerator::class.java)
        assertClassDoesNotReferenceCurrentTimeMillis(LapTimingEngine::class.java)
    }

    // --------------------------- 8.7 ---------------------------

    /**
     * Spec: 圈内短暂失联后恢复累计，LapRecord 打 ProtocolDesyncGap 标记
     *
     * 跑一圈：帧 0 off-gate → 帧 1 through-gate（开圈）→ 中间插入 5 帧 isTimeSynced=false
     * （相邻 ts 差必然 > 200ms，实际是 sentinel 被跳过后恢复帧与开圈帧的大间隔）
     * → 恢复同步后继续累积 → 最后一帧闭圈。
     *
     * 断言 completedLaps.first().qualityFlags 含 ProtocolDesyncGap。
     */
    @Test
    fun lapWithProtocolDesyncGap_laprecordFlagged() {
        val fakeClock = FakeClock(startAtMillis = 0L)
        val pipeline = E2EPipeline(
            generator = GpsDataGenerator(
                scenario = TestScenario.STATIC,
                clock = fakeClock::now
            ),
            parser = RaceChronoParser(),
            engine = LapTimingEngine(),
            track = track
        )

        val gate = track.startFinishGate
        val centerLat = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLon = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offGateLat = centerLat - gate.passDirection.x * 0.25
        val offGateLon = centerLon - gate.passDirection.y * 0.25
        val throughGateLat = centerLat + gate.passDirection.x * 0.25
        val throughGateLon = centerLon + gate.passDirection.y * 0.25

        // 帧 0: off-gate
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)
        // 帧 1: through-gate（开圈）
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)
        assertNotNull("lap should be active after 1st crossing", pipeline.session.activeLap)

        // 插入 5 帧失联（强制 syncBits 失配；位置保持 off-gate 静止不影响 gate 判定）
        pipeline.generator.setCurrentPosition(offGateLat, offGateLon)
        repeat(5) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = false, forceSyncMismatch = true)
        }

        // 恢复 10 帧同步（off-gate 静止）——注意恢复首帧走首样本分支，trajectory
        // 中相邻 ts 差会跨越失联 5 帧约 240ms，触发 ProtocolDesyncGap
        repeat(10) {
            fakeClock.tick(40L)
            pipeline.feedFrame(sendTimePacket = true)
        }

        // 闭圈：跳到 through-gate
        pipeline.generator.setCurrentPosition(throughGateLat, throughGateLon)
        fakeClock.tick(40L)
        pipeline.feedFrame(sendTimePacket = true)

        val session = pipeline.session
        assertEquals("should close exactly 1 lap", 1, session.completedLaps.size)
        val lap = session.completedLaps.first()
        assertTrue(
            "expected ProtocolDesyncGap flag, got=${lap.qualityFlags}",
            lap.qualityFlags.contains(LapQualityFlag.ProtocolDesyncGap)
        )
    }

    // --------------------------- 工具 ---------------------------

    private fun makeReplaySample(
        tsMillis: Long,
        lat: Double,
        lon: Double
    ): ReplaySample = ReplaySample(
        timestampMillis = tsMillis,
        latitude = lat,
        longitude = lon,
        speedKmh = 36.0,
        bearingDegrees = 0.0,
        satellites = 12,
        fixType = 1,
        hdop = 1.0,
        altitudeMeters = 100.0,
        altitudePrecisionMeters = 1.0
    )

    /**
     * 字节码常量池扫描：断言给定 class 的 .class 文件（常量池）不引用
     * `currentTimeMillis`。这比 Mockito mockStatic 稳定（mockStatic 无法 mock
     * java.lang.System），也比 instrumentation agent 简单。
     */
    private fun assertClassDoesNotReferenceCurrentTimeMillis(clazz: Class<*>) {
        val resourceName = "/${clazz.name.replace('.', '/')}.class"
        val classBytes = clazz.getResourceAsStream(resourceName)
            ?.use { it.readBytes() }
            ?: run {
                fail("Could not locate ${clazz.name}.class in test classpath")
                return
            }
        val constantPoolText = String(classBytes, Charsets.ISO_8859_1)
        assertFalse(
            "${clazz.simpleName}.class should NOT reference 'currentTimeMillis' " +
                "in its constant pool (spec: 核心时间戳派生路径不得调用 System.currentTimeMillis())",
            constantPoolText.contains("currentTimeMillis")
        )
    }

    // --------------------------- FakeClock ---------------------------

    /**
     * 单调递增 fake clock，用于注入 [GpsDataGenerator.clock]。
     * 初始值由 [startAtMillis] 控制；[tick] 推进给定毫秒数；[now] 返回当前毫秒。
     */
    private class FakeClock(startAtMillis: Long) {
        private var current: Long = startAtMillis

        fun now(): Long = current

        fun tick(deltaMillis: Long) {
            require(deltaMillis >= 0) { "FakeClock.tick must be monotonic" }
            current += deltaMillis
        }
    }

    // --------------------------- E2EPipeline ---------------------------

    /**
     * 端到端管道辅助类：封装 generator / parser / engine 三个 use case 对象的
     * 联合调用。外部设置好位置和时钟后，调用 [feedFrame] 推进一帧。
     *
     * 内部复刻 TestSessionViewModel.bridgeGpsToLapTiming 的守卫逻辑：
     * - gpsData.isTimeSynced == false → 不调 engine，lastLapGpsSample 清零
     * - 首个同步帧 → 不调 engine（首样本分支），更新 lastLapGpsSample
     * - 后续同步帧 → 调 engine.processSample(previous=lastLapGpsSample, current=本帧)
     */
    private class E2EPipeline(
        val generator: GpsDataGenerator,
        val parser: RaceChronoParser,
        val engine: LapTimingEngine,
        val track: Track
    ) {
        private var currentGpsData: GpsData = GpsData.Empty
        private var currentSession: LapSession = LapSession(
            sessionId = "e2e-session",
            trackId = track.id,
            status = LapSessionStatus.Ready
        )
        private var lastLapGpsSample: GpsSample? = null

        /** 所有 parser 输出累积（用于断言 isTimeSynced / timestamp sentinel）。 */
        val observedGpsData: MutableList<GpsData> = mutableListOf()

        /** 累计多少个 isTimeSynced=true 帧穿过 bridge 守卫。 */
        var observedSyncedCount: Int = 0
            private set

        val session: LapSession
            get() = currentSession

        /**
         * 推进一帧：
         *   1. 若 sendTimePacket=true：先编码 time 包并喂 parser.parseGpsTimeData
         *   2. 若 forceSyncMismatch=true：通过反射临时改写 generator.syncCounter
         *      强制生成一个与 parser 最近一次 time reference 不匹配 syncBits 的 main
         *      包（用于模拟"time 包尚未到达"或"协议失联"）
         *   3. 编码 main 包并喂 parser.parseGpsData
         *   4. 应用 bridge 守卫后决定是否调 engine.processSample
         */
        fun feedFrame(
            sendTimePacket: Boolean,
            forceSyncMismatch: Boolean = false
        ) {
            if (sendTimePacket) {
                val timeBytes = generator.generateGpsTimeData()
                currentGpsData = parser.parseGpsTimeData(timeBytes, currentGpsData)
            }
            val originalSync: Int? = if (forceSyncMismatch) {
                val field = GpsDataGenerator::class.java.getDeclaredField("syncCounter").apply {
                    isAccessible = true
                }
                val saved = field.getInt(generator)
                // 选一个与当前 syncCounter 不同的值 ∈ [0..7]
                val mismatched = (saved + 1) and 0x07
                field.setInt(generator, mismatched)
                saved
            } else null
            val mainBytes = generator.generateGpsMainData()
            if (originalSync != null) {
                // 还原，避免污染后续同步帧
                val field = GpsDataGenerator::class.java.getDeclaredField("syncCounter").apply {
                    isAccessible = true
                }
                field.setInt(generator, originalSync)
            }
            currentGpsData = parser.parseGpsData(mainBytes, currentGpsData)
            observedGpsData.add(currentGpsData)

            // bridge 守卫
            if (!currentGpsData.isTimeSynced) {
                lastLapGpsSample = null
                return
            }
            observedSyncedCount += 1
            val currentSample = toLapGpsSample(currentGpsData)
            val previousSample = lastLapGpsSample
            if (previousSample == null) {
                // 首样本分支，不调 engine
                lastLapGpsSample = currentSample
                return
            }
            currentSession = engine.processSample(
                session = currentSession,
                track = track,
                previousSample = previousSample,
                currentSample = currentSample
            )
            lastLapGpsSample = currentSample
        }

        private fun toLapGpsSample(gpsData: GpsData): GpsSample = GpsSample(
            timestampMillis = gpsData.timestamp,
            latitude = gpsData.latitude,
            longitude = gpsData.longitude,
            speedKmh = gpsData.speed,
            bearingDegrees = gpsData.bearing,
            altitudeMeters = gpsData.altitude,
            accuracyMeters = gpsData.hdop
        )
    }
}
